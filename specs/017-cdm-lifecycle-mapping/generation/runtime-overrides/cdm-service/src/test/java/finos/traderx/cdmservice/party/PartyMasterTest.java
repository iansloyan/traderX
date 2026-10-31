package finos.traderx.cdmservice.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.Party;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import finos.traderx.cdmservice.validation.CdmValidator;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Seeds from the H2 fixture {@code schema/traderx_accounts.sql}: accounts 22214 (user01, user03)
 * and 52355 (user09), plus the 10 persons of {@code seed/people.json}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PartyMasterTest {

  private static final int SEED_PERSONS = 10;

  @Autowired
  private CdmDocumentRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private DataSource dataSource;

  private static final CdmValidator VALIDATOR =
      new CdmValidator(new CdmRuntimeConfig().cdmInjector());

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  private CdmStore store;

  @BeforeEach
  void setUp() {
    store = new CdmStore(repository, cdmObjectMapper, transactionManager);
  }

  private PartyMaster newMaster(CdmStore withStore) {
    return new PartyMaster(withStore, cdmObjectMapper, VALIDATOR, dataSource);
  }

  private PartyMaster masterWith(CdmStore withStore, AccountsTable table) {
    return new PartyMaster(withStore, cdmObjectMapper, VALIDATOR, dataSource,
        table, PartyMaster.DEFAULT_INITIAL_RETRY, PartyMaster.DEFAULT_MAX_RETRY);
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  private int accountsRowCount() {
    return jdbc().queryForObject("select count(*) from accounts", Integer.class);
  }

  private PartyMaster seeded() {
    PartyMaster master = newMaster(store);
    assertThat(master.seedIfEmpty(dataSource)).isTrue();
    return master;
  }

  @Test
  void seedCreatesOnePartyPerAccountAndRelatesUsers() {
    PartyMaster master = seeded();

    assertThat(master.isReady()).isTrue();
    assertThat(master.accounts()).containsExactly(
        new AccountProjection(22214, "Test Account 20", "cdm:party:22214"),
        new AccountProjection(52355, "Big Corporate Fund", "cdm:party:52355"));
    assertThat(master.relatedPersons(22214)).containsExactly("user01", "user03");
    assertThat(master.relatedPersons(52355)).containsExactly("user09");
    assertThat(master.accountUsers(null)).containsExactly(
        new AccountUserProjection(22214, "user01", "cdm:person:user01"),
        new AccountUserProjection(22214, "user03", "cdm:person:user03"),
        new AccountUserProjection(52355, "user09", "cdm:person:user09"));

    // 2 account parties + the venue party + one NaturalPerson per people.json entry.
    assertThat(repository.count()).isEqualTo(2 + 1 + SEED_PERSONS);
    StoredCdm stored = store.latest(new CdmRef(CdmRef.Kind.PARTY, "22214"), Party.class).orElseThrow();
    assertThat(stored.cdmType()).isEqualTo("cdm.base.staticdata.party.Party");
    assertThat(stored.validation())
        .isEqualTo(new CdmValidationResult(Status.VALID, List.of()));
    // Every seeded object (accounts, venue, persons) is VALID under the real CDM validator.
    assertThat(repository.findAll()).allSatisfy(doc -> {
      assertThat(doc.getValidationStatus()).as(doc.getCdmRef()).isEqualTo("VALID");
      assertThat(doc.getValidationMessages()).as(doc.getCdmRef()).isNull();
    });

    // The read view resolves the related persons from the person master.
    Party party = master.resolvedParty(22214).orElseThrow();
    assertThat(party.getPerson()).extracting(PartyMapper::fullName)
        .containsExactly("Alice Johnson", "Carol Brown");
    assertThat(store.latest(new CdmRef(CdmRef.Kind.PERSON, "user02"), NaturalPerson.class))
        .isPresent();
    assertThat(master.findPerson("user02").map(PartyMapper::fullName)).contains("Bob Smith");
  }

  @Test
  void seedsTheVenueParty() {
    PartyMaster master = seeded();

    assertThat(master.venue()).isEqualTo(PartyMapper.venueParty());
    assertThat(store.latest(PartyMapper.venueRef(), Party.class)).isPresent();
    assertThat(master.accounts()).extracting(AccountProjection::cdmRef)
        .doesNotContain("cdm:party:traderx-matching-engine");
  }

  @Test
  void seedIsIdempotentAndTheStoreWinsOverTheTablesAfterSeeding() {
    PartyMaster first = seeded();
    first.updateAccount(22214, "Renamed In CDM");
    long rows = repository.count();

    PartyMaster restarted = seeded();

    assertThat(repository.count()).isEqualTo(rows);
    assertThat(restarted.findAccount(22214))
        .contains(new AccountProjection(22214, "Renamed In CDM", "cdm:party:22214"));
    // The rename kept the related persons.
    assertThat(restarted.relatedPersons(22214)).containsExactly("user01", "user03");
  }

  @Test
  void createAccountDrawsIdsFromAccountsSequenceAndSurvivesRestart() {
    PartyMaster master = seeded();

    AccountProjection created = master.createAccount("New Fund");

    assertThat(created.id()).isGreaterThanOrEqualTo(65000);
    assertThat(created.displayName()).isEqualTo("New Fund");
    assertThat(created.cdmRef()).isEqualTo("cdm:party:" + created.id());
    assertThat(master.findAccount(created.id())).contains(created);
    assertThat(master.accounts()).last().isEqualTo(created);

    // Found again from the store after a restart.
    assertThat(seeded().findAccount(created.id())).contains(created);
    // The foreign-key anchor row for trades and positions exists (R19).
    assertThat(jdbc().queryForObject("select displayname from accounts where id = ?",
        String.class, created.id())).isEqualTo("New Fund");
  }

  @Test
  void updateAccountAppendsAChainedVersion() {
    PartyMaster master = seeded();
    CdmRef ref = new CdmRef(CdmRef.Kind.PARTY, "52355");
    String before = store.latest(ref, Party.class).orElseThrow().globalKey();

    master.updateAccount(52355, "Bigger Corporate Fund");

    List<StoredCdm> history = store.history(ref);
    assertThat(history).hasSize(2);
    assertThat(history.get(1).previousGlobalKey()).isEqualTo(before);
    assertThat(master.findAccount(52355))
        .contains(new AccountProjection(52355, "Bigger Corporate Fund", "cdm:party:52355"));
  }

  @Test
  void updateAccountCreatesAnAbsentAccountWithTheGivenIdLikeTheOldUpsert() {
    PartyMaster master = seeded();

    AccountProjection upserted = master.updateAccount(777, "Client Supplied Id");

    assertThat(upserted).isEqualTo(new AccountProjection(777, "Client Supplied Id", "cdm:party:777"));
    assertThat(master.accounts()).first().isEqualTo(upserted);
  }

  @Test
  void addUserRelatesTheKnownPersonOnceAndIsIdempotent() {
    PartyMaster master = seeded();

    AccountUserProjection added = master.addUser(52355, "user02");
    master.addUser(52355, "user02");

    assertThat(added).isEqualTo(new AccountUserProjection(52355, "user02", "cdm:person:user02"));
    assertThat(master.relatedPersons(52355)).containsExactly("user02", "user09");
    assertThat(master.resolvedParty(52355).orElseThrow().getPerson())
        .extracting(PartyMapper::fullName).contains("Bob Smith");
    assertThat(jdbc().queryForObject(
        "select count(*) from accountusers where accountid = 52355 and username = 'user02'",
        Integer.class)).isEqualTo(1);
    assertThat(store.history(new CdmRef(CdmRef.Kind.PARTY, "52355"))).hasSize(2);
    assertThat(master.accountUsers(52355)).extracting(AccountUserProjection::username)
        .containsExactly("user02", "user09");
  }

  @Test
  void unknownUserRefResolves() {
    PartyMaster master = seeded();

    AccountUserProjection added = master.addUser(52355, "zz-temp");

    assertThat(master.relatedPersons(52355)).containsExactly("user09", "zz-temp");
    // Its cdmRef cdm:person:zz-temp resolves, to an identifier-only person.
    CdmRef ref = CdmRef.parse(added.cdmRef());
    assertThat(store.latest(ref, NaturalPerson.class)).isPresent();
    assertThat(master.findPerson("zz-temp")).contains(PartyMapper.identifiedPerson("zz-temp"));
    assertThat(seeded().findPerson("zz-temp")).isPresent();
  }

  @Test
  void personDirectoryServesSeededPersonsWithPhotoUrlAndHidesIdentifierOnlyPersons() {
    PartyMaster master = seeded();
    master.addUser(52355, "zz-dir");

    assertThat(master.people()).hasSize(SEED_PERSONS)
        .extracting(PersonProjection::logonId)
        .isSorted()
        .doesNotContain("zz-dir");
    assertThat(master.findDirectoryPerson("user02")).contains(new PersonProjection("user02",
        "Bob Smith", "bob.smith@example.com", "E0002", "Finance",
        "https://example.com/photos/user02.jpg", "cdm:person:user02"));
    assertThat(master.findDirectoryPerson("zz-dir")).isEmpty();
    assertThat(master.findPerson("zz-dir")).isPresent();
    assertThat(master.findPersonByEmployeeId("E0002")).map(PersonProjection::logonId)
        .contains("user02");
    assertThat(master.searchPeople("Smith")).extracting(PersonProjection::logonId)
        .contains("user02");
    assertThat(master.searchPeople("zz-d")).isEmpty();
    // A restarted master reads the photo back from the store.
    assertThat(seeded().findDirectoryPerson("user02")).map(PersonProjection::photoUrl)
        .contains("https://example.com/photos/user02.jpg");
  }

  @Test
  void partyEmbedsIdentifierOnlyPersons() {
    PartyMaster master = seeded();
    master.addUser(22214, "user02");

    for (int id : List.of(22214, 52355)) {
      Party stored = master.findParty(id).orElseThrow();
      assertThat(stored.getPerson()).isNotEmpty().allSatisfy(person -> {
        assertThat(person).isEqualTo(PartyMapper.identifiedPerson(PartyMapper.logonId(person)));
        assertThat(person.getFirstName()).isNull();
        assertThat(person.getContactInformation()).isNull();
      });
    }
    StoredCdm row = store.latest(new CdmRef(CdmRef.Kind.PARTY, "22214"), Party.class).orElseThrow();
    assertThat(row.json()).doesNotContain("Alice", "alice.johnson@example.com", "E0001");
  }

  @Test
  void displayNameOver50IsRejectedWith400AndCreatesNothing() {
    PartyMaster master = seeded();
    long rows = repository.count();
    int tableRows = accountsRowCount();
    String tooLong = "x".repeat(51);

    assertThatThrownBy(() -> master.createAccount(tooLong))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> master.updateAccount(777, tooLong))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> master.updateAccount(22214, tooLong))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(repository.count()).isEqualTo(rows);
    assertThat(accountsRowCount()).isEqualTo(tableRows);
    assertThat(master.findAccount(777)).isEmpty();
    assertThat(master.findAccount(22214).orElseThrow().displayName()).isEqualTo("Test Account 20");
    // Exactly 50 is fine (accounts.displayname VARCHAR(50)).
    assertThat(master.createAccount("y".repeat(50)).displayName()).hasSize(50);
  }

  @Test
  void usernameOver15IsRejectedWith400() {
    PartyMaster master = seeded();

    assertThatThrownBy(() -> master.addUser(22214, "u".repeat(16)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> master.addUser(22214, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(master.relatedPersons(22214)).containsExactly("user01", "user03");
  }

  @Test
  void failedFkRowWriteLeavesNoCdmParty() {
    AccountsTable failingTable = spy(new AccountsTable(dataSource));
    doThrow(new IllegalStateException("value too long for type character varying(50)"))
        .when(failingTable).insertIfAbsent(anyInt(), any());
    doThrow(new IllegalStateException("accounts unavailable"))
        .when(failingTable).upsert(anyInt(), any());
    PartyMaster master = masterWith(store, failingTable);
    assertThat(master.seedIfEmpty(dataSource)).isTrue();
    long rows = repository.count();

    assertThatThrownBy(() -> master.createAccount("Orphan?")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> master.updateAccount(777, "Orphan?"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.count()).isEqualTo(rows);
    assertThat(store.refs(Party.class)).extracting(CdmRef::key).doesNotContain("777");
    assertThat(master.accounts()).hasSize(2);
  }

  @Test
  void failedCdmWriteRemovesTheAccountsRowItInserted() {
    PartyMaster master = seeded();
    CdmStore failing = spy(store);
    PartyMaster failingMaster = masterWith(failing, new AccountsTable(dataSource));
    assertThat(failingMaster.seedIfEmpty(dataSource)).isTrue();
    doThrow(new IllegalStateException("cdm store down")).when(failing).putNext(any(), any(), any());
    int tableRows = accountsRowCount();

    assertThatThrownBy(() -> failingMaster.createAccount("No Party"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> failingMaster.updateAccount(778, "No Party"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(accountsRowCount()).isEqualTo(tableRows);
    assertThat(master.findAccount(778)).isEmpty();
  }

  @Test
  void createAccountNeverOverwritesExistingId() {
    PartyMaster master = seeded();
    // The value accounts_seq returns next: make it an account through a client-supplied id.
    int next = jdbc().queryForObject("select nextval('accounts_seq')", Integer.class) + 1;
    master.updateAccount(next, "Client Supplied");
    master.addUser(next, "user01");

    AccountProjection created = master.createAccount("Sequence Drawn");

    assertThat(created.id()).isNotEqualTo(next);
    assertThat(master.findAccount(next))
        .contains(new AccountProjection(next, "Client Supplied", "cdm:party:" + next));
    assertThat(master.relatedPersons(next)).containsExactly("user01");
    assertThat(store.history(PartyMapper.partyRef(next))).hasSize(2);
  }

  @Test
  void createAccountNeverOverwritesAnIdThatIsOnlyInTheCdmStore() {
    PartyMaster master = seeded();
    // A CDM party with no accounts row (e.g. stored before Ruling R19 added the anchor row).
    int next = jdbc().queryForObject("select nextval('accounts_seq')", Integer.class) + 1;
    Party existing = PartyMapper.toCdmParty(next, "Store Only");
    store.putNext(PartyMapper.partyRef(next), existing, VALIDATOR.validate(existing));

    AccountProjection created = master.createAccount("Sequence Drawn");

    assertThat(created.id()).isNotEqualTo(next);
    assertThat(store.history(PartyMapper.partyRef(next))).hasSize(1);
  }

  @Test
  void createAccountAnswersConflictWhenEveryDrawnIdIsTaken() {
    AccountsTable stuck = spy(new AccountsTable(dataSource));
    doReturn(22214).when(stuck).nextId();
    PartyMaster master = masterWith(store, stuck);
    assertThat(master.seedIfEmpty(dataSource)).isTrue();

    assertThatThrownBy(() -> master.createAccount("x"))
        .isInstanceOf(PartyMaster.AccountIdConflictException.class);
    assertThat(master.findAccount(22214).orElseThrow().displayName()).isEqualTo("Test Account 20");
  }

  @Test
  void addUserToAnUnknownAccountIsRejected() {
    PartyMaster master = seeded();

    assertThatThrownBy(() -> master.addUser(99999, "user01"))
        .isInstanceOf(PartyMaster.UnknownAccountException.class);
  }

  @Test
  void writesAndReadsAreRefusedUntilReady() {
    PartyMaster master = newMaster(store);

    assertThat(master.isReady()).isFalse();
    assertThatThrownBy(() -> master.createAccount("x"))
        .isInstanceOf(PartyMaster.NotReadyException.class);
    assertThatThrownBy(master::accounts).isInstanceOf(PartyMaster.NotReadyException.class);
    assertThatThrownBy(master::people).isInstanceOf(PartyMaster.NotReadyException.class);
    assertThatThrownBy(() -> master.findDirectoryPerson("user01"))
        .isInstanceOf(PartyMaster.NotReadyException.class);
  }

  @Test
  void seedFailureKeepsMasterNotReadyUntilACompleteSeed() {
    CdmStore failing = spy(store);
    doThrow(new IllegalStateException("database unavailable"))
        .when(failing).putNext(any(), any(), any());
    PartyMaster master = newMaster(failing);

    assertThat(master.seedIfEmpty(dataSource)).isFalse();
    assertThat(master.isReady()).isFalse();

    doCallRealMethod().when(failing).putNext(any(), any(), any());
    assertThat(master.seedIfEmpty(dataSource)).isTrue();
    assertThat(master.isReady()).isTrue();
    assertThat(master.accounts()).hasSize(2);
  }

  /**
   * Ruling R16: parties and persons stored with the placeholder result are re-validated once at
   * seed time; the real result is a new version of the same content. Validation status is data,
   * not a gate: an INVALID stored party does not stop the master becoming ready.
   */
  @Test
  void seedRevalidatesRowsStoredWithThePlaceholderResult() {
    CdmValidationResult placeholder = new CdmValidationResult(Status.VALID,
        List.of(CdmValidator.LEGACY_PLACEHOLDER_MESSAGE));
    NaturalPerson user01 = PartyMapper.identifiedPerson("user01");
    store.putNext(PartyMapper.personRef("user01"), user01, placeholder);
    StoredCdm venue = store.putNext(PartyMapper.venueRef(), PartyMapper.venueParty(), placeholder);
    // A store-only account party that fails CDM validation (no partyId).
    Party broken = Party.builder().setNameValue("Broken").build();
    store.putNext(PartyMapper.partyRef(777), broken, placeholder);

    PartyMaster master = seeded();

    assertThat(master.isReady()).isTrue();
    assertThat(store.history(PartyMapper.venueRef())).extracting(StoredCdm::validation)
        .containsExactly(placeholder, new CdmValidationResult(Status.VALID, List.of()));
    StoredCdm venueLatest = store.latest(PartyMapper.venueRef(), Party.class).orElseThrow();
    assertThat(venueLatest.globalKey()).isEqualTo(venue.globalKey());
    assertThat(venueLatest.previousGlobalKey()).isNull();
    assertThat(store.latest(PartyMapper.personRef("user01"), NaturalPerson.class).orElseThrow()
        .validation().status()).isEqualTo(Status.VALID);
    StoredCdm brokenLatest = store.latest(PartyMapper.partyRef(777), Party.class).orElseThrow();
    assertThat(brokenLatest.validation().status()).isEqualTo(Status.INVALID);
    assertThat(brokenLatest.validation().messages()).anySatisfy(m -> assertThat(m).contains("partyId"));

    long rows = repository.count();
    assertThat(newMaster(store).seedIfEmpty(dataSource)).isTrue();
    assertThat(repository.count()).isEqualTo(rows);
  }
}
