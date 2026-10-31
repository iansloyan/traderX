package finos.traderx.cdmservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import cdm.base.staticdata.asset.common.Security;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidator;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SecurityMasterTest {

  /** 505 distinct tickers in the S&P 500 CSV (FB renamed META) + 4 supplemental tickers. */
  private static final int SEEDED_TICKERS = 509;

  @Autowired
  private CdmDocumentRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private static final CdmValidator VALIDATOR =
      new CdmValidator(new CdmRuntimeConfig().cdmInjector());

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  private CdmStore store;

  @BeforeEach
  void setUp() {
    store = new CdmStore(repository, cdmObjectMapper, transactionManager);
  }

  private SecurityMaster newMaster() {
    return new SecurityMaster(store, cdmObjectMapper, VALIDATOR);
  }

  @Test
  void seedIfEmptyStoresOneSecurityPerSeedTicker() {
    SecurityMaster master = newMaster();

    master.seedIfEmpty();

    assertThat(repository.count()).isEqualTo(SEEDED_TICKERS);
    assertThat(master.isReady()).isTrue();
    assertThat(master.all()).hasSize(SEEDED_TICKERS);
    StoredCdm ibm = store.latest(new CdmRef(CdmRef.Kind.SECURITY, "IBM"), Security.class).orElseThrow();
    assertThat(ibm.cdmType()).isEqualTo("cdm.base.staticdata.asset.common.Security");
    assertThat(ibm.validation().status()).isEqualTo(Status.VALID);
    assertThat(ibm.validation().messages()).isEmpty();
  }

  @Test
  void seedKeepsReferenceDataTickersNamesAndOrder() {
    SecurityMaster master = newMaster();
    master.seedIfEmpty();

    List<StockProjection> projections = master.all().stream().map(SecurityMapper::toProjection).toList();

    // Same list reference-data used to build from the CSV: CSV order first (FB -> META,
    // preferred names), then the supplemental tickers that are not in the CSV.
    assertThat(projections.get(0)).isEqualTo(new StockProjection("MMM", "3M", "cdm:security:MMM"));
    assertThat(projections).extracting(StockProjection::ticker).doesNotContain("FB")
        .contains("META", "IBM", "MS", "GS", "JPM")
        .endsWith("UBS", "DB", "FNMA", "FNF");
    assertThat(master.findByTicker("META").map(SecurityMapper::toProjection))
        .contains(new StockProjection("META", "Meta Platforms, Inc.", "cdm:security:META"));
    assertThat(master.findByTicker("GS").map(SecurityMapper::toProjection))
        .contains(new StockProjection("GS", "Goldman Sachs Group, Inc.", "cdm:security:GS"));
  }

  @Test
  void seedIfEmptyIsIdempotentAndSurvivesRestart() {
    newMaster().seedIfEmpty();
    long rows = repository.count();

    SecurityMaster restarted = newMaster();
    restarted.seedIfEmpty();

    assertThat(repository.count()).isEqualTo(rows);
    assertThat(restarted.all()).hasSize(SEEDED_TICKERS);
    assertThat(restarted.findByTicker("IBM")).contains(
        SecurityMapper.toCdm("IBM", "IBM"));
  }

  @Test
  void findByTickerReadsTheStoreBeforeSeedingCompletes() {
    newMaster().seedIfEmpty();

    SecurityMaster notYetSeeded = newMaster();

    assertThat(notYetSeeded.isReady()).isFalse();
    assertThat(notYetSeeded.findByTicker("IBM")).isPresent();
    assertThat(notYetSeeded.findByTicker("NOPE")).isEmpty();
  }

  @Test
  void findByTickerIsCaseSensitiveLikeReferenceData() {
    SecurityMaster master = newMaster();
    master.seedIfEmpty();

    assertThat(master.findByTicker("IBM")).isPresent();
    assertThat(master.findByTicker("ibm")).isEmpty();
  }

  @Test
  void demoIdentifiersAreUniqueAcrossTheSeed() {
    SecurityMaster master = newMaster();
    master.seedIfEmpty();

    Set<String> isins = new HashSet<>();
    Set<String> figis = new HashSet<>();
    for (Security security : master.all()) {
      String ticker = SecurityMapper.toProjection(security).ticker();
      isins.add(DemoIdentifiers.isin(ticker));
      figis.add(DemoIdentifiers.figi(ticker));
    }
    assertThat(isins).hasSize(SEEDED_TICKERS);
    assertThat(figis).hasSize(SEEDED_TICKERS);
  }

  /**
   * Not transactional: the retry runs on the master's own thread, so every put must commit on
   * its own. Rows are removed afterwards because the in-memory database outlives the test.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void seedFailureKeepsMasterNotReadyThenRecovers() throws Exception {
    CdmStore failingOnce = spy(store);
    doThrow(new IllegalStateException("database unavailable"))
        .doCallRealMethod()
        .when(failingOnce).put(any(), any(), ArgumentMatchers.<String>any(), any());
    SecurityMaster master = new SecurityMaster(failingOnce, cdmObjectMapper,
        VALIDATOR, Duration.ofMillis(50), Duration.ofMillis(200));
    try {
      master.onApplicationReady();

      // First pass: one ticker failed, so nothing partial is published.
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      boolean sawNotReady = !master.isReady();
      assertThat(sawNotReady).isTrue();
      assertThat(master.all()).isEmpty();

      // The scheduled retry stores the missing ticker and publishes the full index.
      while (!master.isReady() && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertThat(master.isReady()).isTrue();
      assertThat(master.all()).hasSize(SEEDED_TICKERS);
      assertThat(repository.count()).isEqualTo(SEEDED_TICKERS);
    } finally {
      master.stopRetries();
      repository.deleteAll();
    }
  }

  @Test
  void seedIfEmptyReportsFailureAndStaysNotReady() {
    CdmStore alwaysFailing = spy(store);
    doThrow(new IllegalStateException("database unavailable"))
        .when(alwaysFailing).put(any(), any(), ArgumentMatchers.<String>any(), any());
    SecurityMaster master = new SecurityMaster(alwaysFailing, cdmObjectMapper,
        VALIDATOR);

    assertThat(master.seedIfEmpty()).isFalse();
    assertThat(master.isReady()).isFalse();
    assertThat(master.all()).isEmpty();

    doCallRealMethod().when(alwaysFailing).put(any(), any(), ArgumentMatchers.<String>any(), any());
    assertThat(master.seedIfEmpty()).isTrue();
    assertThat(master.isReady()).isTrue();
    assertThat(master.all()).hasSize(SEEDED_TICKERS);
  }

  @Test
  void listAndSingleLookupAreConsistent() {
    SecurityMaster master = newMaster();
    master.seedIfEmpty();
    // A Security that is in the store but not in the seed (and so not in the index).
    Security notSeeded = SecurityMapper.toCdm("ZZZZ", "Not Seeded");
    store.put(SecurityMapper.cdmRef("ZZZZ"), notSeeded, null, VALIDATOR.validate(notSeeded));

    for (Security listed : master.all()) {
      String ticker = SecurityMapper.toProjection(listed).ticker();
      assertThat(master.findByTicker(ticker)).contains(listed);
    }
    assertThat(master.all()).extracting(s -> SecurityMapper.toProjection(s).ticker())
        .doesNotContain("ZZZZ");
    assertThat(master.findByTicker("ZZZZ")).isEmpty();
  }

  /**
   * Ruling R16: rows stored before the real validator existed carry the placeholder result.
   * Seeding re-validates them once, recording the real result as a new version of the same
   * content; a second seed writes nothing more.
   */
  @Test
  void seedRevalidatesRowsStoredWithThePlaceholderResult() {
    Security ibm = SecurityMapper.toCdm("IBM", "IBM");
    CdmRef ref = SecurityMapper.cdmRef("IBM");
    StoredCdm placeholder = store.put(ref, ibm, null, new CdmValidationResult(Status.VALID,
        List.of(CdmValidator.LEGACY_PLACEHOLDER_MESSAGE)));

    newMaster().seedIfEmpty();

    List<StoredCdm> history = store.history(ref);
    assertThat(history).hasSize(2);
    StoredCdm revalidated = history.get(1);
    assertThat(revalidated.globalKey()).isEqualTo(placeholder.globalKey());
    assertThat(revalidated.previousGlobalKey()).isNull();
    assertThat(revalidated.validation()).isEqualTo(new CdmValidationResult(Status.VALID, List.of()));

    long rows = repository.count();
    SecurityMaster restarted = newMaster();
    restarted.seedIfEmpty();
    assertThat(repository.count()).isEqualTo(rows);
    assertThat(restarted.findByTicker("IBM")).contains(ibm);
  }

  /** Validation status is data, not a gate: an INVALID stored object does not block readiness. */
  @Test
  void invalidStoredSecurityDoesNotBlockReadiness() {
    Security broken = Security.builder().build();
    store.put(SecurityMapper.cdmRef("IBM"), broken, null, new CdmValidationResult(Status.VALID,
        List.of(CdmValidator.LEGACY_PLACEHOLDER_MESSAGE)));

    SecurityMaster master = newMaster();

    assertThat(master.seedIfEmpty()).isTrue();
    assertThat(master.isReady()).isTrue();
    StoredCdm latest = store.latest(SecurityMapper.cdmRef("IBM"), Security.class).orElseThrow();
    assertThat(latest.validation().status()).isEqualTo(Status.INVALID);
    assertThat(latest.validation().messages()).isNotEmpty();
  }
}
