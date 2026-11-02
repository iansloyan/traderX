package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CDM_JSON;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.INJECTOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Party;
import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.cdmservice.master.CdmMaster;
import finos.traderx.cdmservice.metrics.CdmMetrics;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.position.PositionProjection;
import finos.traderx.cdmservice.position.PositionView;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seed-trade backfill (Ruling R28) over the {@code trades} table on H2, with the real
 * adapter, mappers, validator and store. The rows are the 009 seed's four trades (same values as
 * postgres-init/initialSchema.sql), whose positions the 009 seed also inserts directly.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeedTradeBackfillTest {

  private static final LocalDateTime SEEDED_AT = LocalDateTime.parse("2026-10-29T09:00:00");

  /** Reference data for the 009 seed: its accounts and the securities its trades use. */
  private static final LifecycleReferenceData SEED_DATA = new LifecycleReferenceData() {
    private final Map<String, Security> securities = Map.of(
        "IBM", SecurityMapper.toCdm("IBM", "International Business Machines"),
        "MS", SecurityMapper.toCdm("MS", "Morgan Stanley"),
        "C", SecurityMapper.toCdm("C", "Citigroup"),
        "BAC", SecurityMapper.toCdm("BAC", "Bank of America"));
    private final Map<Integer, Party> accounts = Map.of(
        22214, PartyMapper.toCdmParty(22214, "Test Account 20", List.of()),
        52355, PartyMapper.toCdmParty(52355, "Big Corporate Fund", List.of()));

    @Override
    public Optional<Security> security(String ticker) {
      return Optional.ofNullable(securities.get(ticker));
    }

    @Override
    public Optional<Party> account(int accountId) {
      return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public Party dealer(Integer accountId) {
      return LifecycleFixtures.NYC;
    }

    @Override
    public Party venue() {
      return PartyMapper.venueParty();
    }
  };

  @Autowired
  private DataSource dataSource;
  @Autowired
  private CdmDocumentRepository documents;
  @Autowired
  private PendingTradeRepository pendingTrades;
  @Autowired
  private PendingCancelRepository pendingCancels;
  @Autowired
  private LifecycleFailureRepository failureRows;
  @Autowired
  private PlatformTransactionManager transactionManager;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    clean();
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from trades");
    documents.deleteAll();
    pendingTrades.deleteAll();
    pendingCancels.deleteAll();
    failureRows.deleteAll();
  }

  /** A "restart": new store, adapter and backfill over the same database. */
  private SeedTradeBackfill backfill(List<CdmMaster> masters, boolean enabled) {
    return backfill(OrderSource.NONE, masters, enabled);
  }

  private SeedTradeBackfill backfill(OrderSource matcher) {
    return backfill(matcher, List.of(), false);
  }

  private SeedTradeBackfill backfill(OrderSource matcher, List<CdmMaster> masters,
      boolean enabled) {
    CdmStore store = store();
    LifecycleFailures failures = new LifecycleFailures(failureRows,
        new CdmMetrics(new SimpleMeterRegistry()));
    return new SeedTradeBackfill(dataSource, store, adapter(store, failures, matcher), failures,
        matcher, masters, enabled);
  }

  /** The live adapter over the same database, with the seed reference data. */
  private LifecycleAdapter adapter(CdmStore store, LifecycleFailures failures,
      OrderSource matcher) {
    return new LifecycleAdapter(store,
        new WorkflowMapper(INJECTOR, SEED_DATA), new ExecutionMapper(INJECTOR, SEED_DATA),
        VALIDATOR, pendingTrades, pendingCancels, failures, matcher,
        CdmPublisher.NONE, new CdmMetrics(new SimpleMeterRegistry()), CDM_JSON,
        Clock.systemUTC());
  }

  private SeedTradeBackfill backfill() {
    return backfill(List.of(), false);
  }

  private void insert(String id, int account, String security, String side, int quantity,
      String price, String state, String orderId) {
    insert(id, account, security, side, quantity, price, state, orderId, SEEDED_AT);
  }

  private void insert(String id, int account, String security, String side, int quantity,
      String price, String state, String orderId, Object created) {
    jdbc.update("insert into trades (id, created, updated, security, side, quantity, price,"
        + " state, accountid, orderid) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id, created, created, security, side, quantity,
        price == null ? null : new BigDecimal(price), state, account, orderId);
  }

  /** The 009 seed trades (postgres-init/initialSchema.sql). */
  private void insertSeed() {
    insert("TRADE-22214-AABBCC", 22214, "IBM", "Sell", 100, "136.250", "Settled", null);
    insert("TRADE-22214-DDEEFF", 22214, "MS", "Buy", 1000, "95.125", "Settled", null);
    insert("TRADE-22214-GGHHII", 22214, "C", "Sell", 2000, "57.500", "Settled", null);
    insert("TRADE-52355-AABBCC", 52355, "BAC", "Sell", 2400, "41.125", "Settled", null);
  }

  /** An open order as the matcher's {@code GET /orders?status=open} lists it (OrderResponse). */
  private static JsonNode openOrder(String orderId, int account, String security, String side,
      int quantity, String limitPrice) {
    ObjectNode node = LifecycleMessages.JSON.createObjectNode();
    node.put("orderId", orderId);
    node.put("accountId", account);
    node.put("security", security);
    node.put("side", side);
    node.put("quantity", quantity);
    node.put("remainingQuantity", quantity);
    node.put("limitPrice", new BigDecimal(limitPrice));
    node.put("status", "NEW");
    node.put("createdAt", "2026-10-29T09:00:00Z");
    node.put("updatedAt", "2026-10-29T09:00:00Z");
    return node;
  }

  /** A matcher listing the given open orders. */
  private static OrderSource matcher(JsonNode... open) {
    return new OrderSource() {
      @Override
      public Optional<OrderEvent> submitted(String orderId) {
        return Optional.empty();
      }

      @Override
      public List<JsonNode> openOrders() {
        return List.of(open);
      }
    };
  }

  private CdmStore store() {
    return new CdmStore(documents, CDM_JSON, transactionManager, Clock.systemUTC());
  }

  @Test
  void seededTradesAreBackfilledOnceAsDirectTrades() {
    insertSeed();
    insert("TRADE-FILL-1", 22214, "IBM", "Buy", 10, "130.000", "Settled", "ord-1"); // a fill
    insert("TRADE-CANCELLED", 22214, "IBM", "Buy", 10, "130.000", "Cancelled", null);

    SeedTradeBackfill.Result result = backfill().backfill();

    assertThat(result).isEqualTo(new SeedTradeBackfill.Result(5, 0, 4, 4));
    CdmStore store = store();
    CdmRef ref = CdmRef.parse("cdm:trade:TRADE-22214-AABBCC");
    CdmRef direct = CdmRef.parse("cdm:direct:TRADE-22214-AABBCC");
    // The direct-trade shape: proposed then accepted under cdm:direct; the trade ref holds only
    // the trade state, produced by the accepted step.
    assertThat(store.history(direct)).extracting(s -> s.cdmType().substring(
        s.cdmType().lastIndexOf('.') + 1))
        .containsExactly("WorkflowStep", "WorkflowStep");
    assertThat(store.history(ref)).extracting(s -> s.cdmType().substring(
        s.cdmType().lastIndexOf('.') + 1))
        .containsExactly("TradeState");
    assertThat(store.history(ref).get(0).producedBy().ref()).isEqualTo(direct);
    assertThat(store.history(ref).get(0).producedBy().storeGlobalKey())
        .isEqualTo(store.history(direct).get(1).globalKey());
    assertThat(java.util.stream.Stream.concat(store.history(direct).stream(),
        store.history(ref).stream())).allSatisfy(s ->
        assertThat(s.validation().status().name()).as("%s %s", s.cdmType(),
            s.validation().messages()).isEqualTo("VALID"));
    TradeState state = readState(store, ref);
    assertThat(ExecutionMapper.tradeId(state)).isEqualTo("TRADE-22214-AABBCC");
    // created is read as UTC.
    WorkflowStep execution = readStep(store, direct);
    assertThat(execution.getTimestamp().get(0).getDateTime().toInstant())
        .isEqualTo(SEEDED_AT.toInstant(ZoneOffset.UTC));
    assertThat(store.refs(TradeState.class)).hasSize(4)
        .doesNotContain(CdmRef.parse("cdm:trade:TRADE-FILL-1"),
            CdmRef.parse("cdm:trade:TRADE-CANCELLED"));
    assertThat(failureRows.findAll()).isEmpty();
  }

  private static TradeState readState(CdmStore store, CdmRef ref) {
    try {
      return CDM_JSON.readValue(store.latest(ref, TradeState.class).orElseThrow().json(),
          TradeState.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static WorkflowStep readStep(CdmStore store, CdmRef ref) {
    try {
      return CDM_JSON.readValue(store.latest(ref, WorkflowStep.class).orElseThrow().json(),
          WorkflowStep.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void backfillIsIdempotentAcrossRestarts() {
    insertSeed();
    backfill().backfill();
    long rows = documents.count();

    SeedTradeBackfill.Result again = backfill().backfill(); // a restart

    assertThat(again).isEqualTo(new SeedTradeBackfill.Result(4, 4, 0, 0));
    assertThat(documents.count()).isEqualTo(rows);
    assertThat(failureRows.findAll()).isEmpty();
  }

  @Test
  void backfilledSeedPositionsEqualThe009SeedPositions() {
    insertSeed();
    backfill().backfill();
    PositionView view = new PositionView(store(), CDM_JSON, VALIDATOR, INJECTOR);

    // The 009 seed's Positions rows (postgres-init/initialSchema.sql).
    assertThat(view.projection(22214))
        .extracting(PositionProjection::accountId, PositionProjection::security,
            PositionProjection::quantity, PositionProjection::averageCostBasis)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(22214, "C", -2000, new BigDecimal("57.500")),
            org.assertj.core.groups.Tuple.tuple(22214, "IBM", -100, new BigDecimal("136.250")),
            org.assertj.core.groups.Tuple.tuple(22214, "MS", 1000, new BigDecimal("95.125")));
    assertThat(view.projection(52355))
        .extracting(PositionProjection::security, PositionProjection::quantity,
            PositionProjection::averageCostBasis)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("BAC", -2400, new BigDecimal("41.125")));
    assertThat(view.view(22214).validation().status().name()).isEqualTo("VALID");
  }

  @Test
  void badRowsAreRecordedAsFailuresAndTheRestIsBackfilled() {
    insertSeed();
    insert("TRADE-NO-PRICE", 22214, "IBM", "Buy", 5, null, "Settled", null);
    insert("TRADE-UNKNOWN-SEC", 22214, "ZZZZ", "Buy", 5, "1.000", "Settled", null);

    SeedTradeBackfill.Result result = backfill().backfill();

    assertThat(result.stored()).isEqualTo(4);
    assertThat(failureRows.findAll())
        .extracting(LifecycleFailure::getCdmRef, LifecycleFailure::getStage)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("cdm:trade:TRADE-NO-PRICE", "backfill"),
            org.assertj.core.groups.Tuple.tuple("cdm:direct:TRADE-UNKNOWN-SEC", "direct-trade"));
  }

  @Test
  void startupRunWaitsForTheMastersThenBackfills() throws Exception {
    insertSeed();
    AtomicBoolean ready = new AtomicBoolean(false);
    SeedTradeBackfill backfill = backfill(List.of(ready::get), true);
    try {
      backfill.onApplicationReady();
      Thread.sleep(300);
      assertThat(documents.count()).as("nothing before the masters are ready").isZero();
      ready.set(true);
      Instant deadline = Instant.now().plusSeconds(10);
      while (store().refs(TradeState.class).size() < 4 && Instant.now().isBefore(deadline)) {
        Thread.sleep(100);
      }
      assertThat(store().refs(TradeState.class)).hasSize(4);
    } finally {
      backfill.stop();
    }
  }

  @Test
  void openMatcherOrdersAreBackfilledAsAcceptedProposals() {
    OrderSource matcher = matcher(
        openOrder("ord-013-0001", 22214, "IBM", "Buy", 1800, "187.250"),
        openOrder("ord-013-0003", 52355, "BAC", "Sell", 1200, "41.500"));

    SeedTradeBackfill.OrderResult result = backfill(matcher).backfillOpenOrders();

    assertThat(result).isEqualTo(new SeedTradeBackfill.OrderResult(2, 0, 2, 2));
    CdmStore store = store();
    for (String orderId : List.of("ord-013-0001", "ord-013-0003")) {
      CdmRef ref = CdmRef.parse("cdm:order:" + orderId);
      // proposed, then accepted chained to it
      assertThat(store.history(ref)).hasSize(2);
      assertThat(store.history(ref).get(0).previousGlobalKey()).isNull();
      assertThat(store.history(ref).get(1).previousGlobalKey())
          .isEqualTo(store.history(ref).get(0).globalKey());
      assertThat(store.history(ref)).allSatisfy(s -> {
        assertThat(s.cdmType()).isEqualTo(WorkflowStep.class.getName());
        assertThat(s.validation().status().name()).as("%s", s.validation().messages())
            .isEqualTo("VALID");
      });
    }
    assertThat(failureRows.findAll()).isEmpty();
  }

  @Test
  void openOrderBackfillIsIdempotentAcrossRestarts() {
    OrderSource matcher = matcher(
        openOrder("ord-013-0001", 22214, "IBM", "Buy", 1800, "187.250"));
    backfill(matcher).backfillOpenOrders();
    long rows = documents.count();

    SeedTradeBackfill.OrderResult again = backfill(matcher).backfillOpenOrders(); // a restart

    assertThat(again).isEqualTo(new SeedTradeBackfill.OrderResult(1, 1, 0, 0));
    assertThat(documents.count()).isEqualTo(rows);
    assertThat(failureRows.findAll()).isEmpty();
  }

  @Test
  void unreadableOrUnmappableOpenOrdersAreRecordedAsFailures() {
    ObjectNode noCreatedAt = (ObjectNode) openOrder("ord-013-0008", 22214, "IBM", "Buy", 5,
        "1.000");
    noCreatedAt.remove("createdAt");
    OrderSource matcher = matcher(
        openOrder("ord-013-0001", 22214, "IBM", "Buy", 1800, "187.250"),
        noCreatedAt,
        openOrder("ord-013-0009", 22214, "ZZZZ", "Buy", 5, "1.000"));

    SeedTradeBackfill.OrderResult result = backfill(matcher).backfillOpenOrders();

    assertThat(result.stored()).isEqualTo(1);
    assertThat(failureRows.findAll())
        .extracting(LifecycleFailure::getCdmRef, LifecycleFailure::getStage)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("cdm:order:ord-013-0008", "backfill"),
            org.assertj.core.groups.Tuple.tuple("cdm:order:ord-013-0009", "order"));
  }

  @Test
  void startupRunRetriesTheMatcherUntilItAnswers() throws Exception {
    insertSeed();
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    JsonNode order = openOrder("ord-013-0001", 22214, "IBM", "Buy", 1800, "187.250");
    OrderSource flaky = new OrderSource() {
      @Override
      public Optional<OrderEvent> submitted(String orderId) {
        return Optional.empty();
      }

      @Override
      public List<JsonNode> openOrders() {
        if (calls.incrementAndGet() == 1) {
          throw new IllegalStateException("order matcher unreachable");
        }
        return List.of(order);
      }
    };
    SeedTradeBackfill backfill = backfill(flaky, List.of(() -> true), true);
    try {
      backfill.onApplicationReady();
      CdmRef ref = CdmRef.parse("cdm:order:ord-013-0001");
      Instant deadline = Instant.now().plusSeconds(10);
      while (store().history(ref).size() < 2 && Instant.now().isBefore(deadline)) {
        Thread.sleep(100);
      }
      assertThat(store().history(ref)).hasSize(2);
      // the direct trades were backfilled once, not again on the retry
      assertThat(store().refs(TradeState.class)).hasSize(4);
      assertThat(documents.count()).isEqualTo(4 * 3 + 2);
    } finally {
      backfill.stop();
    }
  }

  // --- order fills booked while cdm-service was down (Ruling R31) ---------------------------

  /** A matcher that no longer lists the (filled) order as open but can read it back. */
  private static OrderSource filledOrderMatcher() {
    return new OrderSource() {
      @Override
      public Optional<OrderEvent> submitted(String orderId) {
        return LifecycleFixtures.ORDER_ID.equals(orderId)
            ? Optional.of(LifecycleFixtures.newOrder()) : Optional.empty();
      }

      @Override
      public List<JsonNode> openOrders() {
        return List.of();
      }
    };
  }

  private void insertMissedFills() {
    // Inserted out of order: the backfill must chain them by created time.
    insert("TRADE-FILL-2", 22214, "IBM", "Buy", 750, "179.500", "Settled",
        LifecycleFixtures.ORDER_ID, java.sql.Timestamp.valueOf("2026-10-29 14:30:10"));
    insert("TRADE-FILL-1", 22214, "IBM", "Buy", 750, "179.500", "Settled",
        LifecycleFixtures.ORDER_ID, java.sql.Timestamp.valueOf("2026-10-29 14:30:05"));
  }

  private static String key(StoredCdm stored) {
    try {
      return CDM_JSON.readTree(stored.json()).at("/meta/externalKey").asText();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void missedOrderFillsAreBackfilledIntoTheOrderChain() {
    insertSeed();
    insertMissedFills();

    SeedTradeBackfill backfill = backfill(filledOrderMatcher());
    backfill.backfill();
    backfill.backfillOpenOrders();
    SeedTradeBackfill.FillResult result = backfill.backfillOrderFills();

    assertThat(result).isEqualTo(new SeedTradeBackfill.FillResult(2, 0, 2, 2));
    CdmStore store = store();
    List<StoredCdm> chain = store.history(CdmRef.parse("cdm:order:" + LifecycleFixtures.ORDER_ID))
        .stream().filter(s -> s.cdmType().equals(WorkflowStep.class.getName())).toList();
    // The order recovered from the matcher (proposal, approval), then each fill's own
    // workflow (proposed, accepted) in created order.
    assertThat(chain).extracting(SeedTradeBackfillTest::key).containsExactly(
        LifecycleFixtures.ORDER_ID + ":proposed", LifecycleFixtures.ORDER_ID + ":approved",
        "TRADE-FILL-1:proposed", "TRADE-FILL-1:accepted",
        "TRADE-FILL-2:proposed", "TRADE-FILL-2:accepted");
    assertLinks(chain, null, 0, null, 2, null, 4);
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status().name())
        .as("%s", s.validation().messages()).isEqualTo("VALID"));
    for (String trade : List.of("TRADE-FILL-1", "TRADE-FILL-2")) {
      CdmRef ref = CdmRef.parse("cdm:trade:" + trade);
      assertThat(store.latest(ref, TradeState.class)).as(trade).isPresent();
      assertThat(store.latest(ref, TradeState.class).get().validation().status().name())
          .isEqualTo("VALID");
    }
    assertThat(failureRows.count()).isZero();
    assertThat(pendingTrades.count()).isZero();
  }

  @Test
  void orderFillBackfillIsIdempotent() {
    insertMissedFills();
    SeedTradeBackfill first = backfill(filledOrderMatcher());
    assertThat(first.backfillOrderFills()).isEqualTo(new SeedTradeBackfill.FillResult(2, 0, 2, 2));
    long documentsAfterFirst = documents.count();

    // A restart: nothing new is written.
    SeedTradeBackfill second = backfill(filledOrderMatcher());
    assertThat(second.backfillOrderFills())
        .isEqualTo(new SeedTradeBackfill.FillResult(2, 2, 0, 0));
    assertThat(documents.count()).isEqualTo(documentsAfterFirst);
    // Direct-trade pass ignores fills.
    assertThat(second.backfill()).isEqualTo(new SeedTradeBackfill.Result(0, 0, 0, 0));
    assertThat(failureRows.count()).isZero();
  }

  // --- cancels missed while cdm-service was down (Ruling R41, gap G9) -----------------------

  /** A canceled order as the matcher's {@code GET /orders?status=canceled} lists it. */
  private static JsonNode canceledOrder(String orderId, int quantity, int filled) {
    ObjectNode node = LifecycleMessages.JSON.createObjectNode();
    node.put("orderId", orderId);
    node.put("accountId", LifecycleFixtures.ACCOUNT);
    node.put("security", "IBM");
    node.put("side", "Buy");
    node.put("quantity", quantity);
    node.put("remainingQuantity", 0); // the matcher's shape once canceled (Ruling R25)
    node.put("filledQuantity", filled);
    node.put("canceledQuantity", quantity - filled);
    node.put("limitPrice", new BigDecimal("180.000"));
    node.put("status", "CANCELED");
    node.put("createdAt", "2026-10-29T14:30:00Z");
    node.put("updatedAt", "2026-10-29T14:31:00Z");
    return node;
  }

  /**
   * A matcher that lists {@code canceled} and reads back only the fixture order (so an order
   * created and canceled during the outage must be recovered from the listing alone).
   */
  private static OrderSource canceledMatcher(JsonNode... canceled) {
    return new OrderSource() {
      @Override
      public Optional<OrderEvent> submitted(String orderId) {
        return LifecycleFixtures.ORDER_ID.equals(orderId)
            ? Optional.of(LifecycleFixtures.newOrder()) : Optional.empty();
      }

      @Override
      public List<JsonNode> canceledOrders() {
        return List.of(canceled);
      }
    };
  }

  private List<StoredCdm> chain(String orderId) {
    return store().history(CdmRef.parse("cdm:order:" + orderId)).stream()
        .filter(s -> s.cdmType().equals(WorkflowStep.class.getName())).toList();
  }

  /**
   * Each row's store previous key is the key of the row at the given index ({@code null}: no
   * previous); every row is VALID.
   */
  private static void assertLinks(List<StoredCdm> chain, Integer... previous) {
    assertThat(chain).hasSize(previous.length);
    for (int i = 0; i < chain.size(); i++) {
      assertThat(chain.get(i).previousGlobalKey()).as("previous of row %d", i)
          .isEqualTo(previous[i] == null ? null : chain.get(previous[i]).globalKey());
    }
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status().name())
        .as("%s", s.validation().messages()).isEqualTo("VALID"));
  }

  private static void assertChained(List<StoredCdm> chain) {
    for (int i = 1; i < chain.size(); i++) {
      assertThat(chain.get(i).previousGlobalKey()).isEqualTo(chain.get(i - 1).globalKey());
    }
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status().name())
        .as("%s", s.validation().messages()).isEqualTo("VALID"));
  }

  @Test
  void missedCancelIsBackfilled() {
    // The order and its first fill reached CDM; the cancel of the rest was missed.
    insert("TRADE-FILL-1", 22214, "IBM", "Buy", 750, "179.500", "Settled",
        LifecycleFixtures.ORDER_ID, java.sql.Timestamp.valueOf("2026-10-29 14:30:05"));
    SeedTradeBackfill backfill = backfill(
        canceledMatcher(canceledOrder(LifecycleFixtures.ORDER_ID, 1500, 750)));
    backfill.backfillOrderFills();

    SeedTradeBackfill.CancelResult result = backfill.backfillCanceledOrders();

    assertThat(result).isEqualTo(new SeedTradeBackfill.CancelResult(1, 0, 1, 1));
    List<StoredCdm> chain = chain(LifecycleFixtures.ORDER_ID);
    assertThat(chain).extracting(SeedTradeBackfillTest::key).containsExactly(
        LifecycleFixtures.ORDER_ID + ":proposed", LifecycleFixtures.ORDER_ID + ":approved",
        "TRADE-FILL-1:proposed", "TRADE-FILL-1:accepted", LifecycleFixtures.ORDER_ID + ":cancel");
    // The cancel follows the approved version, not the fill.
    assertLinks(chain, null, 0, null, 2, 1);
    assertThat(failureRows.count()).isZero();
    assertThat(pendingCancels.count()).isZero();
  }

  @Test
  void orderCreatedAndCanceledDuringOutageIsBackfilled() {
    SeedTradeBackfill backfill = backfill(canceledMatcher(canceledOrder("ord-013-0102", 1500, 0)));

    SeedTradeBackfill.CancelResult result = backfill.backfillCanceledOrders();

    assertThat(result).isEqualTo(new SeedTradeBackfill.CancelResult(1, 0, 1, 1));
    List<StoredCdm> chain = chain("ord-013-0102");
    assertThat(chain).extracting(SeedTradeBackfillTest::key).containsExactly(
        "ord-013-0102:proposed", "ord-013-0102:approved", "ord-013-0102:cancel");
    assertChained(chain);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void canceledOrderBackfillIsIdempotent() {
    OrderSource matcher = canceledMatcher(canceledOrder("ord-013-0102", 1500, 0));
    backfill(matcher).backfillCanceledOrders();
    long rows = documents.count();

    SeedTradeBackfill.CancelResult again = backfill(matcher).backfillCanceledOrders(); // restart

    assertThat(again).isEqualTo(new SeedTradeBackfill.CancelResult(1, 1, 0, 0));
    assertThat(documents.count()).isEqualTo(rows);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void unreadableCanceledOrderIsRecordedAsAFailure() {
    ObjectNode noStatus = (ObjectNode) canceledOrder("ord-013-0103", 10, 0);
    noStatus.remove("status");
    SeedTradeBackfill backfill = backfill(canceledMatcher(noStatus,
        canceledOrder("ord-013-0102", 1500, 0)));

    SeedTradeBackfill.CancelResult result = backfill.backfillCanceledOrders();

    assertThat(result).isEqualTo(new SeedTradeBackfill.CancelResult(2, 0, 1, 1));
    assertThat(failureRows.findAll())
        .extracting(LifecycleFailure::getCdmRef, LifecycleFailure::getStage)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("cdm:order:ord-013-0103",
            "backfill"));
  }

  // --- backfill and live produce the same CDM documents ---------------------------------------

  /** Every stored row as ref, type, global key, previous key and producer, in write order. */
  private List<String> documentsByRef() {
    return documents.findAll().stream()
        .sorted(java.util.Comparator.comparing(d -> d.getId()))
        .map(d -> String.join(" ", d.getCdmRef(), d.getCdmType(), d.getGlobalKey(),
            String.valueOf(d.getPreviousGlobalKey()), String.valueOf(d.getProducedByRef()),
            String.valueOf(d.getProducedByGlobalKey())))
        .sorted() // refs are written in a different order by the two paths
        .toList();
  }

  @Test
  void backfillProducesSameDocumentsAsLive() {
    Instant fillAt = Instant.parse("2026-10-29T14:30:05Z");
    Instant directAt = Instant.parse("2026-10-29T15:00:00Z");
    BookedTrade fill = new BookedTrade("TRADE-FILL-1", LifecycleFixtures.ORDER_ID, 22214, "IBM",
        "Buy", 750, new BigDecimal("179.500"), fillAt, "Settled");
    BookedTrade direct = new BookedTrade("TRADE-DIRECT-1", null, 22214, "IBM", "Sell", 200,
        new BigDecimal("181.250"), directAt, "Settled");
    JsonNode canceled = canceledOrder(LifecycleFixtures.ORDER_ID, 1500, 750);

    // Live: the NATS events as they arrive.
    LifecycleAdapter live = adapter(store(), new LifecycleFailures(failureRows,
        new CdmMetrics(new SimpleMeterRegistry())), OrderSource.NONE);
    live.onOrder(LifecycleFixtures.newOrder());
    live.onBookedTrade(fill);
    live.onOrder(LifecycleMessages.orderEvent(canceled));
    live.onBookedTrade(direct);
    List<String> fromLive = documentsByRef();
    assertThat(failureRows.count()).isZero();
    assertThat(fromLive).hasSize(2 + 2 + 1 + 1 + 2 + 1);

    // Backfill: every event missed, rebuilt from the trades table and the matcher.
    documents.deleteAll();
    insert(fill.id(), 22214, "IBM", "Buy", 750, "179.500", "Settled",
        LifecycleFixtures.ORDER_ID, java.sql.Timestamp.valueOf("2026-10-29 14:30:05"));
    insert(direct.id(), 22214, "IBM", "Sell", 200, "181.250", "Settled", null,
        java.sql.Timestamp.valueOf("2026-10-29 15:00:00"));
    SeedTradeBackfill backfill = backfill(canceledMatcher(canceled));
    backfill.backfill();
    backfill.backfillOpenOrders();
    backfill.backfillOrderFills();
    backfill.backfillCanceledOrders();

    assertThat(documentsByRef()).isEqualTo(fromLive);
    assertThat(failureRows.count()).isZero();
  }
}
