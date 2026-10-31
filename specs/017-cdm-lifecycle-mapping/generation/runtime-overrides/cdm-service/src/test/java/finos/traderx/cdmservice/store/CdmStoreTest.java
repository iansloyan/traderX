package finos.traderx.cdmservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs against H2 in PostgreSQL mode with the schema created from the same DDL that
 * postgres-init/initialSchema.sql applies (see src/test/resources/config/application.yml);
 * Hibernate validates the entity mapping against it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CdmStoreTest {

  private static final CdmRef PARTY_REF = CdmRef.parse("cdm:party:22214");
  private static final CdmValidationResult VALID = new CdmValidationResult(Status.VALID, List.of());

  @Autowired
  private CdmDocumentRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  /** A frozen clock: ordering must not depend on wall-clock time advancing between puts. */
  private final Clock frozen = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);

  private CdmStore store;

  @BeforeEach
  void setUp() {
    store = new CdmStore(repository, cdmObjectMapper, transactionManager, frozen);
  }

  private static Party party(String name) {
    return Party.builder()
        .setNameValue(name)
        .addPartyId(PartyIdentifier.builder().setIdentifierValue("22214"))
        .build();
  }

  @Test
  void putIsIdempotentForIdenticalContent() {
    StoredCdm first = store.put(PARTY_REF, party("Test Account 20"), null, VALID);
    StoredCdm second = store.put(PARTY_REF, party("Test Account 20"), null, VALID);

    assertThat(repository.count()).isEqualTo(1);
    assertThat(second.globalKey()).isEqualTo(first.globalKey());
    assertThat(second.createdAt()).isEqualTo(first.createdAt());
  }

  @Test
  void historyIsOrderedAndLinked() {
    StoredCdm v1 = store.put(PARTY_REF, party("v1"), null, VALID);
    StoredCdm v2 = store.put(PARTY_REF, party("v2"), v1.globalKey(), VALID);
    StoredCdm v3 = store.put(PARTY_REF, party("v3"), v2.globalKey(), VALID);
    store.put(CdmRef.parse("cdm:party:62654"), party("other"), null, VALID);

    List<StoredCdm> history = store.history(PARTY_REF);

    assertThat(history).extracting(StoredCdm::globalKey)
        .containsExactly(v1.globalKey(), v2.globalKey(), v3.globalKey());
    assertThat(history).extracting(StoredCdm::previousGlobalKey)
        .containsExactly(null, v1.globalKey(), v2.globalKey());
  }

  @Test
  void latestReturnsNewestDocumentOfTheRequestedType() {
    store.put(PARTY_REF, party("v1"), null, VALID);
    StoredCdm v2 = store.put(PARTY_REF, party("v2"), null, VALID);

    assertThat(store.latest(PARTY_REF, Party.class)).get()
        .extracting(StoredCdm::globalKey).isEqualTo(v2.globalKey());
    assertThat(store.latest(PARTY_REF, NaturalPerson.class)).isEmpty();
    assertThat(store.latest(CdmRef.parse("cdm:party:99999"), Party.class)).isEmpty();
  }

  @Test
  void storedDocumentRoundTripsPayloadTypeAndValidation() throws Exception {
    CdmValidationResult invalid =
        new CdmValidationResult(Status.INVALID, List.of("Party.name: required", "line\nbreak"));
    Party party = party("Test Account 20");

    store.put(PARTY_REF, party, null, invalid);
    StoredCdm stored = store.latest(PARTY_REF, Party.class).orElseThrow();

    assertThat(stored.ref()).isEqualTo(PARTY_REF);
    assertThat(stored.cdmType()).isEqualTo(Party.class.getName());
    assertThat(stored.validation()).isEqualTo(invalid);
    assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-10-29T10:00:00Z"));
    assertThat(cdmObjectMapper.readValue(stored.json(), Party.class)).isEqualTo(party);
  }

  @Test
  void computedGlobalKeyIsSha256OfCanonicalJsonAndDeterministic() {
    StoredCdm stored = store.put(PARTY_REF, party("Test Account 20"), null, VALID);
    // A fresh mapper instance and a separately built but equal object must give the same key.
    CdmStore other = new CdmStore(repository, new CdmRuntimeConfig().cdmObjectMapper(), transactionManager, frozen);

    assertThat(stored.globalKey()).matches("[0-9a-f]{64}");
    assertThat(other.globalKeyOf(party("Test Account 20"))).isEqualTo(stored.globalKey());
    assertThat(other.globalKeyOf(party("Test Account 21"))).isNotEqualTo(stored.globalKey());
  }

  @Test
  void canonicalJsonSortsObjectKeysRecursively() {
    String canonical = store.canonicalJson(party("Test Account 20"));

    assertThat(canonical).isEqualTo(
        "{\"name\":{\"value\":\"Test Account 20\"},"
            + "\"partyId\":[{\"identifier\":{\"value\":\"22214\"}}]}");
  }

  @Test
  void createdAtIsTruncatedToMicrosForPostgresTimestampPrecision() {
    Clock nanos = Clock.fixed(Instant.parse("2026-10-29T10:00:00.123456789Z"), ZoneOffset.UTC);
    StoredCdm stored = new CdmStore(repository, cdmObjectMapper, transactionManager, nanos)
        .put(PARTY_REF, party("v1"), null, VALID);

    assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-10-29T10:00:00.123456Z"));
    assertThat(store.latest(PARTY_REF, Party.class).orElseThrow().createdAt())
        .isEqualTo(stored.createdAt().truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void revertingContentIsStoredAsNewVersion() {
    StoredCdm a = store.put(PARTY_REF, party("A"), null, VALID);
    StoredCdm b = store.put(PARTY_REF, party("B"), a.globalKey(), VALID);
    StoredCdm a2 = store.put(PARTY_REF, party("A"), b.globalKey(), VALID);

    assertThat(a2.globalKey()).isEqualTo(a.globalKey());
    assertThat(store.latest(PARTY_REF, Party.class)).get()
        .satisfies(latest -> {
          assertThat(latest.globalKey()).isEqualTo(a.globalKey());
          assertThat(latest.previousGlobalKey()).isEqualTo(b.globalKey());
        });
    List<StoredCdm> history = store.history(PARTY_REF);
    assertThat(history).extracting(StoredCdm::globalKey)
        .containsExactly(a.globalKey(), b.globalKey(), a.globalKey());
    assertThat(history).extracting(StoredCdm::previousGlobalKey)
        .containsExactly(null, a.globalKey(), b.globalKey());
  }

  @Test
  void retryingTheSameVersionIsIdempotentButAChangedPreviousKeyIsNot() {
    StoredCdm a = store.put(PARTY_REF, party("A"), null, VALID);
    StoredCdm b = store.put(PARTY_REF, party("B"), a.globalKey(), VALID);
    store.put(PARTY_REF, party("B"), a.globalKey(), VALID);
    assertThat(repository.count()).isEqualTo(2);

    store.put(PARTY_REF, party("B"), b.globalKey(), VALID);
    assertThat(repository.count()).isEqualTo(3);
  }

  @Test
  void staleMetaGlobalKeyDoesNotHideNewContent() {
    Party v1 = party("v1").toBuilder()
        .setMeta(MetaFields.builder().setGlobalKey("abc123").setExternalKey("22214"))
        .build();
    // A toBuilder() update keeps the old meta.globalKey.
    Party v2 = v1.toBuilder().setNameValue("v2").build();
    assertThat(v2.getMeta().getGlobalKey()).isEqualTo("abc123");

    StoredCdm s1 = store.put(PARTY_REF, v1, null, VALID);
    StoredCdm s2 = store.put(PARTY_REF, v2, s1.globalKey(), VALID);

    assertThat(s1.globalKey()).isNotEqualTo("abc123").matches("[0-9a-f]{64}");
    assertThat(s2.globalKey()).isNotEqualTo(s1.globalKey());
    assertThat(repository.count()).isEqualTo(2);
    assertThat(store.latest(PARTY_REF, Party.class).orElseThrow().json()).contains("v2");
  }

  @Test
  void identicalContentUnderDifferentRefsIsAllowed() {
    CdmRef otherRef = CdmRef.parse("cdm:party:62654");
    StoredCdm first = store.put(PARTY_REF, party("Same"), null, VALID);
    StoredCdm second = store.put(otherRef, party("Same"), null, VALID);

    assertThat(second.globalKey()).isEqualTo(first.globalKey());
    assertThat(repository.count()).isEqualTo(2);
    assertThat(store.latest(otherRef, Party.class)).get()
        .extracting(StoredCdm::ref).isEqualTo(otherRef);
  }

  @Test
  void latestAcceptsImplClass() {
    Party party = party("v1");
    StoredCdm stored = store.put(PARTY_REF, party.toBuilder(), null, VALID);

    assertThat(stored.cdmType()).isEqualTo(Party.class.getName());
    assertThat(store.latest(PARTY_REF, party.getClass())).isPresent();
    assertThat(store.latest(PARTY_REF, Party.PartyImpl.class)).isPresent();
    assertThat(store.latest(PARTY_REF, Party.PartyBuilder.class)).isPresent();
    assertThat(store.latest(PARTY_REF, Party.PartyBuilderImpl.class)).isPresent();
  }

  @Test
  void putNextChainsFromTheLatestVersionAndSkipsUnchangedContent() {
    StoredCdm v1 = store.putNext(PARTY_REF, party("v1"), VALID);
    StoredCdm v2 = store.putNext(PARTY_REF, party("v2"), VALID);
    StoredCdm again = store.putNext(PARTY_REF, party("v2"), VALID);

    assertThat(v1.previousGlobalKey()).isNull();
    assertThat(v2.previousGlobalKey()).isEqualTo(v1.globalKey());
    assertThat(again).isEqualTo(v2);
    assertThat(repository.count()).isEqualTo(2);
  }

  /**
   * Runs outside the test transaction so each put commits and is visible to the other thread,
   * as in production. Cleans up after itself.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentPutsOnOneRefKeepLinearHistory() throws Exception {
    CdmRef ref = CdmRef.parse("cdm:party:concurrent");
    int threads = 4;
    int putsPerThread = 15;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int thread = t;
        futures.add(pool.submit(() -> {
          start.await();
          for (int i = 0; i < putsPerThread; i++) {
            store.putNext(ref, party("t" + thread + "-" + i), VALID);
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }

      List<CdmDocument> rows = repository.findByCdmRefOrderByIdAsc(ref.toString());
      assertThat(rows).hasSize(threads * putsPerThread);
      assertThat(rows.get(0).getPreviousGlobalKey()).isNull();
      for (int i = 1; i < rows.size(); i++) {
        assertThat(rows.get(i).getId()).isGreaterThan(rows.get(i - 1).getId());
        assertThat(rows.get(i).getPreviousGlobalKey())
            .as("row %d links to row %d", i, i - 1)
            .isEqualTo(rows.get(i - 1).getGlobalKey());
      }
      assertThat(store.history(ref)).extracting(StoredCdm::globalKey)
          .containsExactlyElementsOf(rows.stream().map(CdmDocument::getGlobalKey).toList());
    } finally {
      pool.shutdownNow();
      repository.deleteAll(repository.findByCdmRefOrderByIdAsc(ref.toString()));
    }
  }

  @Test
  void revalidateRecordsAChangedResultAsANewVersionOfTheSameContent() {
    CdmValidationResult placeholder =
        new CdmValidationResult(Status.VALID, List.of("not validated"));
    StoredCdm first = store.putNext(PARTY_REF, party("Test Account 20"), placeholder);
    CdmValidationResult invalid = new CdmValidationResult(Status.INVALID, List.of("Party: x"));

    StoredCdm revalidated =
        store.revalidate(PARTY_REF, Party.class, first.globalKey(), invalid).orElseThrow();

    assertThat(revalidated.globalKey()).isEqualTo(first.globalKey());
    assertThat(revalidated.json()).isEqualTo(first.json());
    // Same link as the row it re-validates (the first version: none), never a self-link.
    assertThat(revalidated.previousGlobalKey()).isEqualTo(first.previousGlobalKey()).isNull();
    assertThat(revalidated.validation()).isEqualTo(invalid);
    assertThat(store.latest(PARTY_REF, Party.class)).contains(revalidated);
    assertThat(store.history(PARTY_REF)).extracting(StoredCdm::validation)
        .containsExactly(placeholder, invalid);
  }

  @Test
  void revalidateWithAnUnchangedResultWritesNothing() {
    StoredCdm first = store.putNext(PARTY_REF, party("Test Account 20"), VALID);

    assertThat(store.revalidate(PARTY_REF, Party.class, first.globalKey(), VALID))
        .contains(first);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void revalidateOfStaleContentWritesNothing() {
    StoredCdm first = store.putNext(PARTY_REF, party("Old Name"), VALID);
    StoredCdm second = store.putNext(PARTY_REF, party("New Name"), VALID);
    CdmValidationResult invalid = new CdmValidationResult(Status.INVALID, List.of("x"));

    // The result was computed for "Old Name", which is no longer the latest version.
    assertThat(store.revalidate(PARTY_REF, Party.class, first.globalKey(), invalid))
        .contains(second);
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void revalidateOfAnUnknownRefIsEmpty() {
    assertThat(store.revalidate(PARTY_REF, Party.class, "nope", VALID)).isEmpty();
    assertThat(repository.count()).isZero();
  }

  @Test
  void putRetryAfterRevalidateIsIdempotent() {
    StoredCdm v1 = store.put(PARTY_REF, party("Old Name"), null, VALID);
    StoredCdm v2 = store.put(PARTY_REF, party("New Name"), v1.globalKey(), VALID);
    CdmValidationResult invalid = new CdmValidationResult(Status.INVALID, List.of("x"));
    StoredCdm revalidated =
        store.revalidate(PARTY_REF, Party.class, v2.globalKey(), invalid).orElseThrow();
    long rows = repository.count();

    // A retry of the v2 put (same content, same previous key) must not write another row.
    StoredCdm retried = store.put(PARTY_REF, party("New Name"), v1.globalKey(), VALID);

    assertThat(repository.count()).isEqualTo(rows);
    assertThat(retried).isEqualTo(revalidated);
    assertThat(store.putNext(PARTY_REF, party("New Name"), VALID)).isEqualTo(revalidated);
    assertThat(repository.count()).isEqualTo(rows);
  }

  @Test
  void revalidatedRowHasNoSelfLink() {
    StoredCdm v1 = store.putNext(PARTY_REF, party("Old Name"), VALID);
    StoredCdm v2 = store.putNext(PARTY_REF, party("New Name"), VALID);
    store.revalidate(PARTY_REF, Party.class, v2.globalKey(),
        new CdmValidationResult(Status.INVALID, List.of("x")));
    store.revalidate(PARTY_REF, Party.class, v2.globalKey(), VALID);

    List<StoredCdm> history = store.history(PARTY_REF);
    assertThat(history).hasSize(4);
    assertThat(history).allSatisfy(row ->
        assertThat(row.previousGlobalKey()).isNotEqualTo(row.globalKey()));
    assertThat(history).extracting(StoredCdm::previousGlobalKey)
        .containsExactly(null, v1.globalKey(), v1.globalKey(), v1.globalKey());
  }

  @Test
  void putWithProducedByStoresItAndLinksVersionsWithinTheRef() {
    CdmRef trade = CdmRef.parse("cdm:trade:T-1");
    ProducedBy by = new ProducedBy(CdmRef.parse("cdm:order:O-1"), "a".repeat(64));

    StoredCdm first = store.put(trade, party("state"), VALID, by);

    assertThat(first.producedBy()).isEqualTo(by);
    assertThat(first.previousGlobalKey()).isNull();
    assertThat(store.latest(trade, Party.class).orElseThrow().producedBy()).isEqualTo(by);
    assertThat(store.history(trade)).singleElement()
        .satisfies(row -> assertThat(row.producedBy()).isEqualTo(by));

    // A retry with the same content and producer writes nothing.
    assertThat(store.put(trade, party("state"), VALID, by)).isEqualTo(first);
    assertThat(store.history(trade)).hasSize(1);

    // A new version links to the previous one under the same ref, never across refs.
    StoredCdm second = store.put(trade, party("state 2"), VALID, by);
    assertThat(second.previousGlobalKey()).isEqualTo(first.globalKey());
  }

  @Test
  void producedByIsNullWhenNotGiven() {
    StoredCdm stored = store.put(PARTY_REF, party("Test Account 20"), null, VALID);

    assertThat(stored.producedBy()).isNull();
    assertThat(store.putNext(PARTY_REF, party("v2"), VALID).producedBy()).isNull();
  }

  @Test
  void revalidateKeepsProducedBy() {
    CdmRef trade = CdmRef.parse("cdm:trade:T-2");
    ProducedBy by = new ProducedBy(CdmRef.parse("cdm:direct:T-2"), "b".repeat(64));
    StoredCdm first = store.put(trade, party("state"), VALID, by);

    StoredCdm revalidated = store.revalidate(trade, Party.class, first.globalKey(),
        new CdmValidationResult(Status.INVALID, List.of("x"))).orElseThrow();

    assertThat(revalidated.producedBy()).isEqualTo(by);
  }
  @Test
  void putWithANewProducerForUnchangedContentIsNeverASelfLink() {
    CdmRef trade = CdmRef.parse("cdm:trade:T-3");
    ProducedBy fill = new ProducedBy(CdmRef.parse("cdm:order:O-3"), "c".repeat(64));
    ProducedBy direct = new ProducedBy(CdmRef.parse("cdm:direct:T-3"), "d".repeat(64));
    StoredCdm v1 = store.put(trade, party("state"), VALID, fill);
    StoredCdm v2 = store.put(trade, party("state 2"), VALID, fill);

    // Same content as the latest, different producer: a repeat of that version with the new
    // producer, linked like the row it repeats (as revalidate does), not to itself.
    StoredCdm v3 = store.put(trade, party("state 2"), VALID, direct);

    assertThat(v3.globalKey()).isEqualTo(v2.globalKey());
    assertThat(v3.producedBy()).isEqualTo(direct);
    assertThat(v3.previousGlobalKey()).isEqualTo(v1.globalKey());
    assertThat(store.history(trade)).hasSize(3).allSatisfy(row ->
        assertThat(row.previousGlobalKey()).isNotEqualTo(row.globalKey()));

    // First version re-produced: still no link (it had none).
    CdmRef other = CdmRef.parse("cdm:trade:T-4");
    store.put(other, party("only"), VALID, fill);
    StoredCdm repeat = store.put(other, party("only"), VALID, direct);
    assertThat(repeat.previousGlobalKey()).isNull();
    assertThat(repeat.producedBy()).isEqualTo(direct);
  }
}
