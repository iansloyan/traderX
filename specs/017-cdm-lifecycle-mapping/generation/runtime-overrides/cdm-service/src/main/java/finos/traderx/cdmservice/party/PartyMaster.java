package finos.traderx.cdmservice.party;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.Party;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rosetta.model.lib.RosettaModelObject;
import finos.traderx.cdmservice.master.CdmMaster;
import finos.traderx.cdmservice.master.SeedRetry;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.master.Revalidation;
import finos.traderx.cdmservice.validation.CdmValidator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The authoritative party master: one CDM {@link Party} per TraderX account
 * ({@code cdm:party:<accountId>}, with its users as related persons in the role Trader), one
 * {@link NaturalPerson} per person ({@code cdm:person:<logonId>}, the single person authority:
 * account parties embed identifier-only persons), one Party per TraderX group subsidiary that
 * faces clients ({@code cdm:party:traderx-nyc}, ... from {@code seed/entities.json}) and the
 * venue party ({@code cdm:party:traderx-matching-engine}). Each account party's CDM
 * {@code account.servicingParty} names the subsidiary that faces it; {@link #setServicingEntity}
 * changes it.
 *
 * <p><b>Seeding.</b> On {@link ApplicationReadyEvent} it reads 009's {@code accounts} and
 * {@code accountusers} tables and {@code seed/people.json}, and stores a CDM object for each
 * account, person and the venue that has none yet. Objects already in the store are loaded, not
 * rewritten: after the first seed the store is the record and the tables are never read again
 * for anything but missing seed rows. Accounts created later through this master (which are not
 * in the tables) are found from the store by type. Like {@code SecurityMaster}, readiness is all
 * or nothing, and a failed seed is retried with backoff until it succeeds.
 *
 * <p><b>Reads</b> are served from one immutable index snapshot. <b>Writes</b>
 * ({@link #createAccount}, {@link #updateAccount}, {@link #addUser}) are serialised on this
 * master: each first writes the {@code accounts}/{@code accountusers} foreign-key anchor row
 * ({@link AccountsTable}, Ruling R19), then appends the next version with
 * {@link CdmStore#putNext} (outside any transaction of ours, so the store's per-ref lock holds;
 * a row this call inserted is removed again if that fails) and then publishes a new snapshot,
 * so a reader sees either the old or the new state, never a mix. A store failure leaves the
 * snapshot unchanged and propagates to the caller (the controller answers 503).
 *
 * <p><b>Validation.</b> Every object is stored with its {@link CdmValidator} result; objects
 * loaded at seed time that still carry the placeholder result of an earlier build are
 * re-validated ({@link Revalidation}, Ruling R16). Validation status is recorded, never a
 * readiness gate or a reason to reject a write.
 */
@Service
public class PartyMaster implements CdmMaster {

  private static final Logger log = LoggerFactory.getLogger(PartyMaster.class);
  static final Duration DEFAULT_INITIAL_RETRY = Duration.ofSeconds(2);
  static final Duration DEFAULT_MAX_RETRY = Duration.ofSeconds(30);

  private final CdmStore store;
  private final ObjectMapper cdmObjectMapper;
  private final CdmValidator validator;
  private final DataSource dataSource;
  private final AccountsTable accountsTable;
  private final SeedRetry seedRetry;

  /** How many {@code accounts_seq} values createAccount tries before answering 409. */
  static final int MAX_ID_DRAWS = 20;

  /** {@code null} until the first complete seed; replaced whole on every write. */
  private volatile Index index;

  /** Immutable view: accounts by id, persons by logon id, subsidiaries by key, the venue. */
  private record Index(SortedMap<Integer, Party> accounts, Map<String, NaturalPerson> persons,
      Map<String, Party> entities, Party venue) {

    Index withAccount(int id, Party party) {
      TreeMap<Integer, Party> next = new TreeMap<>(accounts);
      next.put(id, party);
      return new Index(Collections.unmodifiableSortedMap(next), persons, entities, venue);
    }

    Index withPerson(String logonId, NaturalPerson person) {
      Map<String, NaturalPerson> next = new LinkedHashMap<>(persons);
      next.put(logonId, person);
      return new Index(accounts, Collections.unmodifiableMap(next), entities, venue);
    }
  }

  /** No TraderX subsidiary with this key (HTTP 400). */
  public static class UnknownEntityException extends IllegalArgumentException {

    public UnknownEntityException(String key) {
      super("Unknown TraderX entity: " + key);
    }
  }

  private final EntitySeed.Seed entitySeed = EntitySeed.load();

  /** The master has not completed its seed yet (HTTP 503). */
  public static class NotReadyException extends RuntimeException {

    public NotReadyException() {
      super("CDM party master is not ready");
    }
  }

  /** No account party with this id (HTTP 404). */
  public static class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(int accountId) {
      super("Account with id " + accountId + " not found");
    }
  }

  /** No free account id could be drawn from {@code accounts_seq} (HTTP 409). */
  public static class AccountIdConflictException extends RuntimeException {

    public AccountIdConflictException(String message) {
      super(message);
    }
  }

  @Autowired
  public PartyMaster(CdmStore store, @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper,
      CdmValidator validator, DataSource dataSource) {
    this(store, cdmObjectMapper, validator, dataSource, new AccountsTable(dataSource),
        DEFAULT_INITIAL_RETRY, DEFAULT_MAX_RETRY);
  }

  PartyMaster(CdmStore store, ObjectMapper cdmObjectMapper, CdmValidator validator,
      DataSource dataSource, AccountsTable accountsTable, Duration initialRetry,
      Duration maxRetry) {
    this.store = store;
    this.cdmObjectMapper = cdmObjectMapper;
    this.validator = validator;
    this.dataSource = dataSource;
    this.accountsTable = accountsTable;
    this.seedRetry = new SeedRetry("party-master", initialRetry, maxRetry,
        () -> seedIfEmpty(this.dataSource));
  }

  /** Seeds now; on failure schedules retries (backoff from 2s, capped at 30s) until ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    seedRetry.start();
  }

  @PreDestroy
  void stopRetries() {
    seedRetry.stop();
  }

  /**
   * Stores whatever of the seed (people.json persons, the venue, the 009 accounts with their
   * users) is not yet in the store, loads the rest plus every other stored account party, and
   * publishes the index.
   *
   * @return true when everything was stored or loaded and the master is ready
   */
  public synchronized boolean seedIfEmpty(DataSource tables) {
    JdbcTemplate jdbc = new JdbcTemplate(tables);
    Map<Integer, String> tableAccounts = new LinkedHashMap<>();
    jdbc.query("select id, displayname from accounts order by id",
        rs -> {
          tableAccounts.put(rs.getInt("id"), rs.getString("displayname"));
        });
    Map<Integer, List<String>> tableUsers = new LinkedHashMap<>();
    jdbc.query("select accountid, username from accountusers order by accountid, username",
        rs -> {
          tableUsers.computeIfAbsent(rs.getInt("accountid"), k -> new ArrayList<>())
              .add(rs.getString("username"));
        });

    int failed = 0;
    int created = 0;
    Map<String, NaturalPerson> persons = new LinkedHashMap<>();
    for (PersonSeed.SeedPerson seed : PersonSeed.load()) {
      try {
        CdmRef ref = PartyMapper.personRef(seed.logonId());
        Optional<NaturalPerson> stored = loadStored(ref, NaturalPerson.class);
        NaturalPerson person = stored.orElseGet(() -> PartyMapper.toCdmPerson(seed.logonId(),
            seed.fullName(), seed.email(), seed.employeeId(), seed.department(),
            seed.photoUrl()));
        if (stored.isEmpty()) {
          write(ref, person);
          created++;
        }
        persons.put(seed.logonId(), person);
      } catch (RuntimeException e) {
        failed++;
        log.error("Cannot seed CDM person {}", seed.logonId(), e);
      }
    }

    // Every table user must resolve as cdm:person:<logonId> (identifier-only if unknown).
    for (String logonId : tableUsers.values().stream().flatMap(List::stream).distinct().toList()) {
      if (persons.containsKey(logonId)) {
        continue;
      }
      try {
        persons.put(logonId, storedOrIdentifiedPerson(logonId));
      } catch (RuntimeException e) {
        failed++;
        log.error("Cannot seed identifier-only CDM person {}", logonId, e);
      }
    }

    Map<String, Party> entities = new LinkedHashMap<>();
    for (EntitySeed.Entity seed : entitySeed.entities()) {
      try {
        CdmRef ref = PartyMapper.entityRef(seed.key());
        Optional<Party> stored = loadStored(ref, Party.class);
        Party entity = stored.orElseGet(() -> PartyMapper.entityParty(seed));
        if (stored.isEmpty()) {
          write(ref, entity);
          created++;
        }
        entities.put(seed.key(), entity);
      } catch (RuntimeException e) {
        failed++;
        log.error("Cannot seed CDM entity party {}", seed.key(), e);
      }
    }

    Party venue = null;
    try {
      Optional<Party> stored = loadStored(PartyMapper.venueRef(), Party.class);
      venue = stored.orElseGet(PartyMapper::venueParty);
      if (stored.isEmpty()) {
        write(PartyMapper.venueRef(), venue);
        created++;
      }
    } catch (RuntimeException e) {
      failed++;
      log.error("Cannot seed the CDM venue party", e);
    }

    TreeMap<Integer, Party> accounts = new TreeMap<>();
    for (Map.Entry<Integer, String> row : tableAccounts.entrySet()) {
      int id = row.getKey();
      try {
        Optional<Party> stored = loadStored(PartyMapper.partyRef(id), Party.class);
        Party party = stored.orElseGet(() -> PartyMapper.toCdmParty(id, row.getValue(),
            tableUsers.getOrDefault(id, List.of()).stream()
                .map(PartyMapper::identifiedPerson)
                .toList(), entitySeed.entityFor(id)));
        if (stored.isEmpty()) {
          write(PartyMapper.partyRef(id), party);
          created++;
        } else if (PartyMapper.servicingEntity(party).isEmpty()) {
          // Stored by a build without facing subsidiaries: give it its seed assignment.
          party = PartyMapper.withServicingEntity(party, entitySeed.entityFor(id));
          write(PartyMapper.partyRef(id), party);
        }
        accounts.put(id, party);
      } catch (RuntimeException e) {
        failed++;
        log.error("Cannot seed CDM party for account {}", id, e);
      }
    }
    try {
      for (CdmRef ref : store.refs(Party.class)) {
        if (ref.key().equals(PartyMapper.VENUE_KEY) || entitySeed.isEntity(ref.key())) {
          continue;
        }
        Integer id = parseAccountId(ref.key());
        if (id != null && !accounts.containsKey(id)) {
          loadStored(ref, Party.class).ifPresent(party -> accounts.put(id, party));
        }
      }
    } catch (RuntimeException e) {
      failed++;
      log.error("Cannot load the stored CDM account parties", e);
    }

    if (failed > 0) {
      log.warn("CDM party master incomplete: {} objects failed; staying not ready", failed);
      return false;
    }
    index = new Index(Collections.unmodifiableSortedMap(accounts),
        Collections.unmodifiableMap(persons), Collections.unmodifiableMap(entities), venue);
    log.info("CDM party master ready: {} accounts, {} persons ({} objects newly seeded)",
        accounts.size(), persons.size(), created);
    return true;
  }

  @Override
  public boolean isReady() {
    return index != null;
  }

  /** Every account, ordered by id (as account-service always listed them). */
  public List<AccountProjection> accounts() {
    return ready().accounts().values().stream().map(this::projection).toList();
  }

  public Optional<AccountProjection> findAccount(int id) {
    return Optional.ofNullable(ready().accounts().get(id)).map(this::projection);
  }

  /** Account users ordered by account id then username; only one account's if given. */
  public List<AccountUserProjection> accountUsers(Integer accountId) {
    Index current = ready();
    if (accountId != null) {
      Party party = current.accounts().get(accountId);
      return party == null ? List.of() : PartyMapper.toAccountUserProjections(party);
    }
    return current.accounts().values().stream()
        .flatMap(party -> PartyMapper.toAccountUserProjections(party).stream())
        .toList();
  }

  /** The logon ids related to an account, sorted (empty for an unknown account). */
  public List<String> relatedPersons(int accountId) {
    return Optional.ofNullable(ready().accounts().get(accountId))
        .map(PartyMapper::relatedLogonIds)
        .orElse(List.of());
  }

  /**
   * The person directory people-service serves: every person with full directory details,
   * ordered by logon id. Identifier-only persons are left out (Ruling R20).
   */
  public List<PersonProjection> people() {
    return ready().persons().values().stream()
        .filter(PartyMapper::isDirectoryPerson)
        .map(PartyMapper::toPersonProjection)
        .sorted(Comparator.comparing(PersonProjection::logonId))
        .toList();
  }

  /**
   * Directory persons whose logon id or full name contains {@code text} (case-sensitive, as
   * people-service's {@code GetMatchingPeople} always matched), ordered by logon id.
   */
  public List<PersonProjection> searchPeople(String text) {
    return people().stream()
        .filter(p -> p.logonId().contains(text) || p.fullName().contains(text))
        .toList();
  }

  /** The directory person with this logon id; empty if unknown or identifier-only. */
  public Optional<PersonProjection> findDirectoryPerson(String logonId) {
    NaturalPerson person = logonId == null ? null : ready().persons().get(logonId);
    return Optional.ofNullable(person)
        .filter(PartyMapper::isDirectoryPerson)
        .map(PartyMapper::toPersonProjection);
  }

  /** The first directory person (by logon id) with this employee id. */
  public Optional<PersonProjection> findPersonByEmployeeId(String employeeId) {
    return people().stream()
        .filter(p -> p.employeeId().equals(employeeId))
        .findFirst();
  }

  /**
   * The account party (from the index once ready; from the store before that, for internal
   * callers such as the lifecycle mappers while seeding is retried).
   */
  public Optional<Party> findParty(int accountId) {
    Index current = index;
    if (current != null) {
      return Optional.ofNullable(current.accounts().get(accountId));
    }
    return readStored(PartyMapper.partyRef(accountId), Party.class);
  }

  public Optional<NaturalPerson> findPerson(String logonId) {
    if (logonId == null || logonId.isBlank()) {
      return Optional.empty();
    }
    Index current = index;
    if (current != null) {
      return Optional.ofNullable(current.persons().get(logonId));
    }
    return readStored(PartyMapper.personRef(logonId), NaturalPerson.class);
  }

  /** The TraderX subsidiaries that can face a client, in seed order. */
  public List<Party> entities() {
    Index current = index;
    if (current != null) {
      return List.copyOf(current.entities().values());
    }
    return entitySeed.entities().stream().map(PartyMapper::entityParty).toList();
  }

  private AccountProjection projection(Party party) {
    return PartyMapper.toAccountProjection(party,
        key -> entity(key).map(e -> e.getName() == null ? null : e.getName().getValue()));
  }

  /** The subsidiary party with this key (stored by the seed; the mapped constant before that). */
  public Optional<Party> entity(String key) {
    if (key == null || !entitySeed.isEntity(key)) {
      return Optional.empty();
    }
    Index current = index;
    if (current != null && current.entities().containsKey(key)) {
      return Optional.of(current.entities().get(key));
    }
    return entitySeed.entities().stream().filter(e -> e.key().equals(key)).findFirst()
        .map(PartyMapper::entityParty);
  }

  /** True if {@code key} is a TraderX subsidiary's key. */
  public boolean isEntity(String key) {
    return key != null && entitySeed.isEntity(key);
  }

  /** The default TraderX subsidiary (seed {@code defaultEntity}), facing accounts without an assignment. */
  public Party defaultEntity() {
    return entity(entitySeed.defaultEntity()).orElseThrow();
  }

  /**
   * The subsidiary facing {@code accountId}: its party's {@code account.servicingParty}, or the
   * seed assignment for an account party that has none.
   */
  public Party facingEntity(int accountId) {
    String key = findParty(accountId).flatMap(PartyMapper::servicingEntity)
        .filter(entitySeed::isEntity)
        .orElse(entitySeed.entityFor(accountId));
    return entity(key).orElseThrow();
  }

  /**
   * Makes {@code entityKey} the subsidiary servicing account {@code accountId}: a new version of
   * the account party with {@code account.servicingParty} set. Orders and trades already mapped
   * keep the subsidiary they were mapped with; later ones face the new one.
   *
   * @throws UnknownAccountException if there is no such account
   * @throws UnknownEntityException if {@code entityKey} is not a TraderX subsidiary
   */
  public synchronized AccountProjection setServicingEntity(int accountId, String entityKey) {
    Index current = ready();
    if (!isEntity(entityKey)) {
      throw new UnknownEntityException(entityKey);
    }
    Party existing = current.accounts().get(accountId);
    if (existing == null) {
      throw new UnknownAccountException(accountId);
    }
    if (!entityKey.equals(PartyMapper.servicingEntity(existing).orElse(null))) {
      Party party = PartyMapper.withServicingEntity(existing, entityKey);
      write(PartyMapper.partyRef(accountId), party);
      index = current.withAccount(accountId, party);
      return projection(party);
    }
    return projection(existing);
  }

  /** The venue party (stored by the seed; the mapped constant before that). */
  public Party venue() {
    Index current = index;
    return current != null && current.venue() != null ? current.venue() : PartyMapper.venueParty();
  }

  /**
   * The account party as served by {@code GET /cdm/parties/{id}}: the stored Party (which embeds
   * identifier-only persons) with each related person replaced by the person master's record,
   * so person data has a single authority ({@code cdm:person:<logonId>}).
   */
  public Optional<Party> resolvedParty(int accountId) {
    return findParty(accountId).map(party -> {
      if (party.getPerson() == null || party.getPerson().isEmpty()) {
        return party;
      }
      List<NaturalPerson> resolved = party.getPerson().stream()
          .map(embedded -> {
            String logonId = PartyMapper.logonId(embedded);
            return findPerson(logonId).orElse(embedded);
          })
          .toList();
      Party.PartyBuilder builder = party.toBuilder();
      builder.setPerson(resolved);
      return builder.build();
    });
  }

  /**
   * Creates an account with the next free id of {@code accounts_seq} (account-service's id
   * semantics). An id that is already an account (in the index, the store or the
   * {@code accounts} table, for example one set earlier by a client-supplied id) is skipped,
   * never overwritten; after {@link #MAX_ID_DRAWS} taken ids this is a 409.
   *
   * <p>The {@code accounts} row (foreign-key anchor) is written first, then the CDM Party; if
   * the CDM write fails the row is removed again, so a failure leaves neither.
   */
  public synchronized AccountProjection createAccount(String displayName) {
    Index current = ready();
    validateDisplayName(displayName);
    for (int draw = 0; draw < MAX_ID_DRAWS; draw++) {
      int id = accountsTable.nextId();
      if (current.accounts().containsKey(id)
          || store.latest(PartyMapper.partyRef(id), Party.class).isPresent()) {
        log.warn("accounts_seq value {} is already an account; drawing again", id);
        continue;
      }
      if (!accountsTable.insertIfAbsent(id, displayName)) {
        log.warn("accounts_seq value {} already has an accounts row; drawing again", id);
        continue;
      }
      Party party = PartyMapper.toCdmParty(id, displayName, List.of(),
          entitySeed.entityFor(id));
      try {
        write(PartyMapper.partyRef(id), party);
      } catch (RuntimeException e) {
        compensate(() -> accountsTable.delete(id), "accounts row " + id);
        throw e;
      }
      index = current.withAccount(id, party);
      return projection(party);
    }
    throw new AccountIdConflictException("No free account id after " + MAX_ID_DRAWS
        + " values of accounts_seq");
  }

  /**
   * Sets the display name of account {@code id}, keeping its related persons; creates the
   * account with that id if there is none (account-service's upsert semantics). The
   * {@code accounts} row is upserted first, then the CDM Party.
   */
  public synchronized AccountProjection updateAccount(int id, String displayName) {
    Index current = ready();
    validateDisplayName(displayName);
    Party existing = current.accounts().get(id);
    List<NaturalPerson> persons = existing == null || existing.getPerson() == null
        ? List.of() : List.copyOf(existing.getPerson());
    String servicing = existing == null ? null
        : PartyMapper.servicingEntity(existing).orElse(null);
    Party party = PartyMapper.toCdmParty(id, displayName, persons,
        servicing != null ? servicing : entitySeed.entityFor(id));
    boolean inserted = accountsTable.upsert(id, displayName);
    try {
      write(PartyMapper.partyRef(id), party);
    } catch (RuntimeException e) {
      if (inserted) {
        compensate(() -> accountsTable.delete(id), "accounts row " + id);
      }
      throw e;
    }
    index = current.withAccount(id, party);
    return projection(party);
  }

  /**
   * Relates {@code logonId} to account {@code accountId} as a Trader (no change if it already
   * is). The party embeds an identifier-only person; a logon id missing from the person master
   * is stored there as an identifier-only person, so its {@code cdmRef} resolves. The
   * {@code accountusers} row is written first, as for accounts.
   *
   * @throws UnknownAccountException if there is no such account
   * @throws IllegalArgumentException for a blank or over-long ({@value AccountsTable#USERNAME_MAX})
   *     logon id
   */
  public synchronized AccountUserProjection addUser(int accountId, String logonId) {
    Index current = ready();
    if (logonId == null || logonId.isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    if (logonId.length() > AccountsTable.USERNAME_MAX) {
      throw new IllegalArgumentException("username must be at most " + AccountsTable.USERNAME_MAX
          + " characters");
    }
    Party existing = current.accounts().get(accountId);
    if (existing == null) {
      throw new UnknownAccountException(accountId);
    }
    if (!PartyMapper.relatedLogonIds(existing).contains(logonId)) {
      Index next = current;
      if (!current.persons().containsKey(logonId)) {
        next = next.withPerson(logonId, storedOrIdentifiedPerson(logonId));
      }
      List<NaturalPerson> persons = new ArrayList<>(
          existing.getPerson() == null ? List.of() : existing.getPerson());
      persons.add(PartyMapper.identifiedPerson(logonId));
      String name = existing.getName() == null ? null : existing.getName().getValue();
      Party party = PartyMapper.toCdmParty(accountId, name, persons,
          PartyMapper.servicingEntity(existing).orElse(entitySeed.entityFor(accountId)));
      boolean inserted = accountsTable.addUserIfAbsent(accountId, logonId);
      try {
        write(PartyMapper.partyRef(accountId), party);
      } catch (RuntimeException e) {
        if (inserted) {
          compensate(() -> accountsTable.deleteUser(accountId, logonId),
              "accountusers row " + accountId + "/" + logonId);
        }
        throw e;
      }
      index = next.withAccount(accountId, party);
    }
    return new AccountUserProjection(accountId, logonId,
        PartyMapper.personRef(logonId).toString());
  }

  private static void validateDisplayName(String displayName) {
    if (displayName != null && displayName.length() > AccountsTable.DISPLAY_NAME_MAX) {
      throw new IllegalArgumentException("displayName must be at most "
          + AccountsTable.DISPLAY_NAME_MAX + " characters");
    }
  }

  /** The stored person, or a new identifier-only person stored under cdm:person:<logonId>. */
  private NaturalPerson storedOrIdentifiedPerson(String logonId) {
    CdmRef ref = PartyMapper.personRef(logonId);
    Optional<NaturalPerson> stored = loadStored(ref, NaturalPerson.class);
    if (stored.isPresent()) {
      return stored.get();
    }
    NaturalPerson person = PartyMapper.identifiedPerson(logonId);
    write(ref, person);
    return person;
  }

  private static void compensate(Runnable undo, String what) {
    try {
      undo.run();
    } catch (RuntimeException e) {
      log.error("Could not remove {} after a failed CDM write; the next seed will create its "
          + "CDM party from it", what, e);
    }
  }

  private Index ready() {
    Index current = index;
    if (current == null) {
      throw new NotReadyException();
    }
    return current;
  }

  private void write(CdmRef ref, RosettaModelObject object) {
    store.putNext(ref, object, validator.validate(object));
  }

  private static Integer parseAccountId(String key) {
    try {
      return Integer.valueOf(key);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * {@link #readStored} for the seed: a row that still carries the placeholder validation
   * result is re-validated on the way (Ruling R16; never blocks the seed).
   */
  private <T extends RosettaModelObject> Optional<T> loadStored(CdmRef ref, Class<T> type) {
    return store.latest(ref, type).map(stored -> {
      T object = read(stored, type);
      Revalidation.ifPlaceholder(store, validator, stored, object);
      return object;
    });
  }

  private <T extends RosettaModelObject> Optional<T> readStored(CdmRef ref, Class<T> type) {
    return store.latest(ref, type).map(stored -> read(stored, type));
  }

  private <T> T read(StoredCdm stored, Class<T> type) {
    try {
      return cdmObjectMapper.readValue(stored.json(), type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored " + type.getSimpleName() + " " + stored.ref()
          + " is unreadable", e);
    }
  }
}
