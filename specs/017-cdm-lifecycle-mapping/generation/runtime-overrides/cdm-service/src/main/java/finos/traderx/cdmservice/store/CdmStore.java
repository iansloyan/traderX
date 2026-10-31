package finos.traderx.cdmservice.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rosetta.model.lib.RosettaModelObject;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Append-only, versioned store of CDM objects in {@code cdm_documents}.
 *
 * <p>Every version is one row with a surrogate {@code id} (BIGSERIAL), which is also the write
 * order: {@link #history} is ordered by id and {@link #latest} is the highest id for a ref and
 * type. {@code global_key} is always the SHA-256 (lower-case hex) of the object's canonical JSON:
 * the {@code cdmObjectMapper} (Rosetta) serialisation with object keys sorted recursively. The
 * Rune {@code meta.globalKey} is never used as the key: it is a short hash that collides, and a
 * {@code toBuilder()} update keeps the stale value. The global key is not unique, because
 * content can recur (A to B to A) and identical content may be stored under different refs.
 *
 * <p><b>Concurrency.</b> Writes to one ref are serialised with an in-process striped lock
 * held around "read latest, then insert and commit". This assumes a <b>single cdm-service
 * instance per state</b>, which is the TraderX deployment design. Running several instances
 * against one database would need a database-level lock (for example
 * {@code pg_advisory_xact_lock}) instead.
 */
@Service
public class CdmStore {

  private static final Logger log = LoggerFactory.getLogger(CdmStore.class);
  private static final int LOCK_STRIPES = 64;
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final CdmDocumentRepository repository;
  private final ObjectMapper cdmObjectMapper;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final ReentrantLock[] refLocks = new ReentrantLock[LOCK_STRIPES];

  @Autowired
  public CdmStore(CdmDocumentRepository repository,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper,
      PlatformTransactionManager transactionManager) {
    this(repository, cdmObjectMapper, transactionManager, Clock.systemUTC());
  }

  public CdmStore(CdmDocumentRepository repository, ObjectMapper cdmObjectMapper,
      PlatformTransactionManager transactionManager, Clock clock) {
    this.repository = repository;
    this.cdmObjectMapper = cdmObjectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
    for (int i = 0; i < LOCK_STRIPES; i++) {
      refLocks[i] = new ReentrantLock();
    }
  }

  /**
   * Stores {@code object} as a new version under {@code ref}, linked to
   * {@code previousGlobalKey}.
   *
   * <p>Idempotent for retries: if the current {@link #latest} of this type under {@code ref}
   * has the same global key <i>and</i> the same previous global key, no row is written and that
   * row is returned. Anything else, including content that returns to an earlier version, is
   * stored as a new row.
   */
  public <T extends RosettaModelObject> StoredCdm put(CdmRef ref, T object,
      String previousGlobalKey, CdmValidationResult validation) {
    return write(ref, object, validation, latest -> {
      String globalKey = globalKeyOf(object);
      if (latest.isPresent()
          && latest.get().getGlobalKey().equals(globalKey)
          && Objects.equals(latest.get().getPreviousGlobalKey(), previousGlobalKey)) {
        return Optional.empty();
      }
      return Optional.of(new Insert(previousGlobalKey, null));
    });
  }

  /**
   * Stores {@code object} as the next version of its type under {@code ref} (previous global
   * key: the current {@link #latest} of its type under {@code ref}, or none), recording
   * {@code producedBy}: the object under another ref that produced it, for example a trade's
   * {@code TradeState} and the accepted workflow step carrying its execution. Version links stay
   * within one ref; the producer is its own stored field.
   *
   * <p>Idempotent for retries: if the latest version of this type under {@code ref} has the same
   * global key and the same producer, no row is written and that row is returned.
   * Same content with a different producer is stored as a repeat of the latest version with the
   * new producer and the latest row's own previous global key, never a self-link.
   */
  public <T extends RosettaModelObject> StoredCdm put(CdmRef ref, T object,
      CdmValidationResult validation, ProducedBy producedBy) {
    Objects.requireNonNull(producedBy, "producedBy");
    return write(ref, object, validation, latest -> {
      if (latest.isPresent() && latest.get().getGlobalKey().equals(globalKeyOf(object))) {
        if (producedBy.equals(producedBy(latest.get()))) {
          return Optional.empty();
        }
        // Same content, new producer: a repeat of the latest version linked like that row
        // (as revalidate does), never a self-link.
        return Optional.of(new Insert(latest.get().getPreviousGlobalKey(), producedBy));
      }
      return Optional.of(new Insert(latest.map(CdmDocument::getGlobalKey).orElse(null),
          producedBy));
    });
  }

  /**
   * Stores {@code object} as the next version after the current {@link #latest} of its type
   * under {@code ref}. The previous global key is read and the row inserted under the ref's
   * lock, so concurrent callers always get one linear chain. Use this in lifecycle code that
   * appends versions rather than passing the previous key explicitly.
   *
   * <p>If the latest version already has identical content, nothing is written and that row is
   * returned.
   */
  public <T extends RosettaModelObject> StoredCdm putNext(CdmRef ref, T object,
      CdmValidationResult validation) {
    return write(ref, object, validation, latest -> {
      if (latest.isPresent() && latest.get().getGlobalKey().equals(globalKeyOf(object))) {
        return Optional.empty();
      }
      return Optional.of(new Insert(latest.map(CdmDocument::getGlobalKey).orElse(null), null));
    });
  }

  /**
   * Records a new validation result for the latest stored version of {@code cdmType} under
   * {@code ref}, for re-validating objects already in the store (Ruling R16: rows written with
   * the placeholder validator before the real one existed).
   *
   * <p>{@link #put} and {@link #putNext} cannot do this: they treat identical content as a
   * retry and write nothing, whatever the validation result. So, under the ref's lock, if the
   * latest version still has {@code globalKey} and its stored result differs from
   * {@code validation}, this inserts a new row with the same content (same global key and
   * payload), the same previous global key and the same producer as the row it re-validates. In
   * the history a re-validation is therefore a repeat of that version with a new result, never
   * a self-link ({@code previous_global_key} is never the row's own {@code global_key}), so
   * lineage walkers stay acyclic and a {@link #put} retry of that version stays idempotent.
   * Nothing is written when the result is unchanged, or when the latest version is no longer
   * {@code globalKey} (it changed since the caller validated it; the result would be stale).
   *
   * @return the latest version after the call (new or unchanged), or empty if there is none
   */
  public Optional<StoredCdm> revalidate(CdmRef ref, Class<? extends RosettaModelObject> cdmType,
      String globalKey, CdmValidationResult validation) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(globalKey, "globalKey");
    Objects.requireNonNull(validation, "validation");
    String typeName = cdmTypeName(cdmType);
    return underRefLock(ref, () -> transactions.execute(status -> {
      Optional<CdmDocument> latest =
          repository.findFirstByCdmRefAndCdmTypeOrderByIdDesc(ref.toString(), typeName);
      if (latest.isEmpty()) {
        return Optional.<StoredCdm>empty();
      }
      StoredCdm current = toStored(latest.get());
      if (!current.globalKey().equals(globalKey) || current.validation().equals(validation)) {
        return Optional.of(current);
      }
      CdmDocument doc = new CdmDocument(
          current.globalKey(),
          ref.toString(),
          typeName,
          latest.get().getPayload(),
          // The re-validated row's own link, never a self-link: lineage stays acyclic and a
          // put retry of that version (same content, same previous key) stays idempotent.
          current.previousGlobalKey(),
          validation.status().name(),
          validation.messages().isEmpty() ? null : writeJson(validation.messages()),
          clock.instant().truncatedTo(ChronoUnit.MICROS),
          latest.get().getProducedByRef(), latest.get().getProducedByGlobalKey());
      return Optional.of(toStored(repository.saveAndFlush(doc)));
    }));
  }

  /**
   * Runs {@code work} holding the write lock of {@code ref}, the same lock {@link #put} and
   * {@link #putNext} take (it is reentrant, so {@code work} may call them for {@code ref}).
   * Lifecycle code uses it to make "read the chain, decide, append" atomic for one ref, for
   * example to skip a redelivered event whose step is already stored.
   *
   * <p>{@code work} must not run inside a caller's transaction, and should not write to
   * other refs: their locks are striped, so holding two of them can deadlock.
   */
  public <R> R withRefLock(CdmRef ref, Supplier<R> work) {
    Objects.requireNonNull(ref, "ref");
    return underRefLock(ref, work);
  }

  /** The newest stored version of {@code cdmType} under {@code ref}. */
  public Optional<StoredCdm> latest(CdmRef ref, Class<? extends RosettaModelObject> cdmType) {
    Objects.requireNonNull(ref, "ref");
    return readOnly(() -> repository
        .findFirstByCdmRefAndCdmTypeOrderByIdDesc(ref.toString(), cdmTypeName(cdmType))
        .map(this::toStored));
  }

  /**
   * Every ref under which at least one version of {@code cdmType} is stored, in no particular
   * order. Masters use it to find objects created after seeding (for example new accounts).
   */
  public List<CdmRef> refs(Class<? extends RosettaModelObject> cdmType) {
    String typeName = cdmTypeName(cdmType);
    return readOnly(() -> repository.findDistinctCdmRefsByCdmType(typeName).stream()
        .map(CdmRef::parse)
        .toList());
  }

  /**
   * The latest version of {@code cdmType} under every ref that has one, in write order (one
   * query). Read models such as positions use it to aggregate, for example, every trade state.
   */
  public List<StoredCdm> latestOfType(Class<? extends RosettaModelObject> cdmType) {
    String typeName = cdmTypeName(cdmType);
    return readOnly(() -> repository.findLatestByCdmType(typeName).stream()
        .map(this::toStored)
        .toList());
  }

  /**
   * Every stored version of {@code cdmType} whose global key is one of {@code globalKeys}, under
   * any ref, in write order. Lineage readers use it to follow {@code previous_global_key} links
   * across refs (for example from a trade's event to its order's execution step).
   */
  public List<StoredCdm> byGlobalKeys(Class<? extends RosettaModelObject> cdmType,
      Collection<String> globalKeys) {
    if (globalKeys.isEmpty()) {
      return List.of();
    }
    String typeName = cdmTypeName(cdmType);
    return readOnly(() -> repository
        .findByCdmTypeAndGlobalKeyInOrderByIdAsc(typeName, globalKeys).stream()
        .map(this::toStored)
        .toList());
  }

  /** Every stored version under {@code ref} (all types), oldest first. */
  public List<StoredCdm> history(CdmRef ref) {
    Objects.requireNonNull(ref, "ref");
    return readOnly(() -> repository.findByCdmRefOrderByIdAsc(ref.toString()).stream()
        .map(this::toStored)
        .toList());
  }

  /** SHA-256 (lower-case hex) of {@link #canonicalJson} of {@code object}. */
  public String globalKeyOf(RosettaModelObject object) {
    return sha256Hex(canonicalJson(object));
  }

  /** {@code cdmObjectMapper} JSON of {@code object} with object keys sorted recursively. */
  public String canonicalJson(Object object) {
    JsonNode sorted = sortKeys(cdmObjectMapper.valueToTree(object));
    try {
      return cdmObjectMapper.writer().without(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(sorted);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write canonical CDM JSON", e);
    }
  }

  /**
   * The CDM interface name stored in {@code cdm_type}, for example
   * {@code cdm.base.staticdata.party.Party}. Generated implementation and builder classes
   * ({@code Party.PartyImpl}, {@code Party.PartyBuilder}, {@code Party.PartyBuilderImpl}) are
   * nested in that interface and resolve to it.
   */
  static String cdmTypeName(Class<? extends RosettaModelObject> type) {
    Objects.requireNonNull(type, "cdmType");
    Class<?> resolved = type;
    for (Class<?> outer = type.getEnclosingClass(); outer != null;
        outer = outer.getEnclosingClass()) {
      if (outer.isInterface() && RosettaModelObject.class.isAssignableFrom(outer)
          && outer.isAssignableFrom(type)) {
        resolved = outer;
      }
    }
    return resolved.getName();
  }

  /**
   * Decision to insert a new row with this previous key ({@code null} for the first) and
   * producer ({@code null} for none).
   */
  private record Insert(String previousGlobalKey, ProducedBy producedBy) {
  }

  /**
   * Under the ref's lock and in one transaction, reads the latest row of the object's type and
   * asks {@code decide} whether to insert (empty means: return the latest row as is).
   */
  private StoredCdm write(CdmRef ref, RosettaModelObject object, CdmValidationResult validation,
      Function<Optional<CdmDocument>, Optional<Insert>> decide) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(object, "object");
    Objects.requireNonNull(validation, "validation");
    String cdmType = cdmTypeName(object.getType());

    return underRefLock(ref, () -> transactions.execute(status -> {
      Optional<CdmDocument> latest =
          repository.findFirstByCdmRefAndCdmTypeOrderByIdDesc(ref.toString(), cdmType);
      Optional<Insert> insert = decide.apply(latest);
      if (insert.isEmpty()) {
        return toStored(latest.orElseThrow());
      }
      CdmDocument doc = new CdmDocument(
          globalKeyOf(object),
          ref.toString(),
          cdmType,
          writeJson(object),
          insert.get().previousGlobalKey(),
          validation.status().name(),
          validation.messages().isEmpty() ? null : writeJson(validation.messages()),
          clock.instant().truncatedTo(ChronoUnit.MICROS),
          insert.get().producedBy() == null ? null : insert.get().producedBy().ref().toString(),
          insert.get().producedBy() == null ? null : insert.get().producedBy().storeGlobalKey());
      // New entity with a generated id: Spring Data persists it (no merge SELECT).
      return toStored(repository.saveAndFlush(doc));
    }));
  }

  /** Runs {@code work} holding the striped lock of {@code ref} (writes to one ref are serial). */
  private <R> R underRefLock(CdmRef ref, Supplier<R> work) {
    ReentrantLock lock = refLocks[Math.floorMod(ref.toString().hashCode(), LOCK_STRIPES)];
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  private <R> R readOnly(Supplier<R> work) {
    TransactionTemplate readOnly = new TransactionTemplate(
        Objects.requireNonNull(transactions.getTransactionManager()));
    readOnly.setReadOnly(true);
    return readOnly.execute(status -> work.get());
  }

  private JsonNode sortKeys(JsonNode node) {
    if (node instanceof ObjectNode object) {
      Map<String, JsonNode> fields = new TreeMap<>();
      object.properties().forEach(e -> fields.put(e.getKey(), sortKeys(e.getValue())));
      ObjectNode out = cdmObjectMapper.getNodeFactory().objectNode();
      fields.forEach(out::set);
      return out;
    }
    if (node instanceof ArrayNode array) {
      ArrayNode out = cdmObjectMapper.getNodeFactory().arrayNode();
      array.forEach(element -> out.add(sortKeys(element)));
      return out;
    }
    return node;
  }

  private static String sha256Hex(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private String writeJson(Object value) {
    try {
      return cdmObjectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialise CDM object", e);
    }
  }

  private StoredCdm toStored(CdmDocument doc) {
    List<String> messages = new ArrayList<>();
    if (doc.getValidationMessages() != null) {
      try {
        messages.addAll(cdmObjectMapper.readValue(doc.getValidationMessages(), STRING_LIST));
      } catch (JsonProcessingException e) {
        log.warn("cdm_documents id={} has malformed validation_messages; returning raw text",
            doc.getId(), e);
        messages.add(doc.getValidationMessages());
      }
    }
    return new StoredCdm(
        doc.getGlobalKey(),
        CdmRef.parse(doc.getCdmRef()),
        doc.getCdmType(),
        doc.getPayload(),
        doc.getPreviousGlobalKey(),
        new CdmValidationResult(CdmValidationResult.Status.valueOf(doc.getValidationStatus()),
            messages),
        doc.getCreatedAt(),
        producedBy(doc));
  }

  private static ProducedBy producedBy(CdmDocument doc) {
    return doc.getProducedByRef() == null || doc.getProducedByGlobalKey() == null ? null
        : new ProducedBy(CdmRef.parse(doc.getProducedByRef()), doc.getProducedByGlobalKey());
  }
}
