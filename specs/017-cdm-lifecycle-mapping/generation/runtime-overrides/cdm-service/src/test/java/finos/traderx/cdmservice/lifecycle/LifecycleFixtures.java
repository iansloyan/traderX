package finos.traderx.cdmservice.lifecycle;

import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Party;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.metrics.CdmMetrics;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.validation.CdmValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared fixtures of the lifecycle mapper tests: account 22214 buys 1500 IBM at a limit of
 * 180.000 and is filled 750 then 750 at 179.500. Every id and timestamp is fixed, so the
 * golden JSON is byte-stable.
 */
public final class LifecycleFixtures {

  public static final Injector INJECTOR = new CdmRuntimeConfig().cdmInjector();
  public static final ObjectMapper CDM_JSON = new CdmRuntimeConfig().cdmObjectMapper();
  public static final CdmValidator VALIDATOR = new CdmValidator(INJECTOR);

  public static final String ORDER_ID = "ord-013-0101";
  public static final int ACCOUNT = 22214;
  public static final Instant ORDER_AT = Instant.parse("2026-10-29T14:30:00Z");
  public static final Instant FILL1_AT = Instant.parse("2026-10-29T14:30:05Z");
  public static final Instant FILL2_AT = Instant.parse("2026-10-29T14:30:10Z");
  public static final Instant CANCEL_AT = Instant.parse("2026-10-29T14:31:00Z");

  public static final Security IBM = SecurityMapper.toCdm("IBM", "International Business Machines");
  public static final Party ACCOUNT_PARTY = PartyMapper.toCdmParty(ACCOUNT, "Test Account 20",
      List.of(PartyMapper.identifiedPerson("user01"), PartyMapper.identifiedPerson("user03")));

  public static final LifecycleReferenceData REFERENCE_DATA = new LifecycleReferenceData() {
    @Override
    public Optional<Security> security(String ticker) {
      return Optional.ofNullable(Map.of("IBM", IBM).get(ticker));
    }

    @Override
    public Optional<Party> account(int accountId) {
      return accountId == ACCOUNT ? Optional.of(ACCOUNT_PARTY) : Optional.empty();
    }

    @Override
    public Party venue() {
      return PartyMapper.venueParty();
    }
  };

  public static final WorkflowMapper WORKFLOW = new WorkflowMapper(INJECTOR, REFERENCE_DATA);
  public static final ExecutionMapper EXECUTION = new ExecutionMapper(INJECTOR, REFERENCE_DATA);

  private LifecycleFixtures() {
  }

  public static OrderEvent newOrder() {
    return new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Buy", 1500, 1500, bd("180.000"), "NEW",
        ORDER_AT, null, null);
  }

  public static OrderEvent canceledOrder() {
    // The matcher's real shape: remainingQuantity is 0 once canceled; filledQuantity and
    // canceledQuantity (Ruling R25) carry the split.
    return new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Buy", 1500, 0, bd("180.000"), "CANCELED",
        CANCEL_AT, bd("179.500"), 750, 750, 750);
  }

  public static BookedTrade fill1() {
    return new BookedTrade("trd-017-0001", ORDER_ID, ACCOUNT, "IBM", "Buy", 750, bd("179.5"),
        FILL1_AT, "Settled");
  }

  public static BookedTrade fill2() {
    return new BookedTrade("trd-017-0002", ORDER_ID, ACCOUNT, "IBM", "Buy", 750, bd("179.500"),
        FILL2_AT, "Settled");
  }

  public static BookedTrade directTrade() {
    return new BookedTrade("trd-017-0100", null, ACCOUNT, "IBM", "Sell", 200, bd("181.25"),
        Instant.parse("2026-10-29T15:00:00Z"), "Settled");
  }

  public static RejectedOrder rejectedOrder() {
    return new RejectedOrder("rej-017-0001", ACCOUNT, "IBM", "Buy", 1500, null,
        "invalid order payload", Instant.parse("2026-10-29T14:29:00Z"));
  }

  /**
   * The real lifecycle adapter on {@code store}, with the fixture mappers and validator, no
   * publisher and a matcher that knows no orders (a fill for an unknown order is parked). For
   * tests outside this package that need stored lifecycle objects (lineage, positions).
   */
  public static LifecycleAdapter adapter(CdmStore store, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels, LifecycleFailureRepository failureRows) {
    CdmMetrics metrics = new CdmMetrics(new SimpleMeterRegistry());
    return new LifecycleAdapter(store, WORKFLOW, EXECUTION, VALIDATOR, pendingTrades,
        pendingCancels, new LifecycleFailures(failureRows, metrics), orderId -> Optional.empty(),
        CdmPublisher.NONE, metrics, CDM_JSON, Clock.systemUTC());
  }

  public static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  /**
   * Golden example {@code name}: written (pretty Rosetta JSON) when {@code -Dcdm.writeGolden=true},
   * otherwise returned from {@code specs/017-cdm-lifecycle-mapping/contracts/examples/}.
   */
  public static String golden(String name, Object cdm) {
    String json;
    try {
      json = CDM_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(cdm) + "\n";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path file = goldenDir().resolve(name + ".json");
    try {
      if (Boolean.getBoolean("cdm.writeGolden")) {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
      }
      return json;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String readGolden(String name) {
    try {
      return Files.readString(goldenDir().resolve(name + ".json"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("golden example " + name
          + " missing; run the tests once with -Dcdm.writeGolden=true", e);
    }
  }

  /**
   * The examples directory of the state pack, found by walking up from the working directory
   * (the service source in the pack, or its copy in the generated tree), or {@code cdm.goldenDir}.
   */
  public static Path goldenDir() {
    String configured = System.getProperty("cdm.goldenDir");
    if (configured != null) {
      return Paths.get(configured);
    }
    Path relative = Paths.get("specs", "017-cdm-lifecycle-mapping", "contracts", "examples");
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("specs").resolve("017-cdm-lifecycle-mapping"))) {
        return dir.resolve(relative);
      }
    }
    throw new IllegalStateException("cannot find " + relative + " above "
        + Paths.get("").toAbsolutePath() + "; set -Dcdm.goldenDir");
  }
}
