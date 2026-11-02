package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CDM_JSON;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.EXECUTION;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.FILL1_AT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_AT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_ID;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.WORKFLOW;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.bd;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.canceledOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.directTrade;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill1;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill2;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.rejectedOrder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cdm.event.common.ActionEnum;
import cdm.event.common.BusinessEvent;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import finos.traderx.cdmservice.metrics.CdmMetrics;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.ProducedBy;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import finos.traderx.cdmservice.validation.CdmValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle adapter called directly (no NATS), with the real mappers and validator and the
 * CDM store on H2 (same DDL as postgres-init/initialSchema.sql). Not wrapped in a test
 * transaction: the adapter must never run store writes inside an outer transaction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LifecycleAdapterTest {

  private static final String WORKFLOW_STEP = WorkflowStep.class.getName();
  private static final String BUSINESS_EVENT = BusinessEvent.class.getName();
  private static final String TRADE_STATE = TradeState.class.getName();
  private static final CdmRef ORDER_REF = CdmRef.parse("cdm:order:" + ORDER_ID);
  private static final CdmRef FILL1_TRADE_REF = CdmRef.parse("cdm:trade:" + fill1().id());
  private static final CdmRef DIRECT_REF = CdmRef.parse("cdm:direct:" + directTrade().id());
  private static final CdmRef DIRECT_TRADE_REF = CdmRef.parse("cdm:trade:" + directTrade().id());

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

  private final List<Published> published = new ArrayList<>();
  /** Orders the fake matcher knows (GET /orders/{id}), as their NEW events. */
  private final Map<String, OrderEvent> matcher = new HashMap<>();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T16:00:00Z"));
  private SimpleMeterRegistry registry;
  private CdmStore store;
  private LifecycleAdapter adapter;

  private record Published(String subject, JsonNode body, byte[] raw) {
  }

  /** Reads JSON keeping decimals exactly as written. */
  private static final ObjectMapper EXACT = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

  private static String sha256(String text) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
  }

  @BeforeEach
  void setUp() {
    clean();
    registry = new SimpleMeterRegistry();
    matcher.clear();
    store = new CdmStore(documents, CDM_JSON, transactionManager, Clock.systemUTC());
    adapter = adapter(VALIDATOR);
  }

  private LifecycleAdapter adapter(CdmValidator validator) {
    CdmMetrics metrics = new CdmMetrics(registry);
    LifecycleFailures failures = new LifecycleFailures(failureRows, metrics, clock);
    CdmPublisher publisher = (subject, payload) -> {
      try {
        published.add(new Published(subject, CDM_JSON.readTree(payload), payload));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
    OrderSource orders = orderId -> Optional.ofNullable(matcher.get(orderId));
    return new LifecycleAdapter(store, WORKFLOW, EXECUTION, validator, pendingTrades,
        pendingCancels, failures, orders, publisher, metrics, CDM_JSON, clock);
  }

  /** A clock the stale/sweep tests move forward. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Rows are committed (no test transaction), and other test classes share the database. */
  @AfterEach
  void clean() {
    documents.deleteAll();
    pendingTrades.deleteAll();
    pendingCancels.deleteAll();
    failureRows.deleteAll();
  }

  // --- helpers -------------------------------------------------------------------------------

  private List<StoredCdm> steps(CdmRef ref) {
    return store.history(ref).stream().filter(s -> s.cdmType().equals(WORKFLOW_STEP)).toList();
  }

  private static WorkflowStep step(StoredCdm stored) {
    try {
      return CDM_JSON.readValue(stored.json(), WorkflowStep.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Each stored step links to the one before it, in the store and in CDM. */
  private static void assertChained(List<StoredCdm> chain) {
    assertThat(chain.get(0).previousGlobalKey()).isNull();
    for (int i = 1; i < chain.size(); i++) {
      assertCdmLinked(chain.get(i - 1), chain.get(i));
    }
  }

  /** {@code next} follows {@code previous}: store version link and CDM previousWorkflowStep. */
  private static void assertCdmLinked(StoredCdm previous, StoredCdm next) {
    assertThat(next.previousGlobalKey()).isEqualTo(previous.globalKey());
    assertThat(step(next).getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(step(previous).getMeta().getGlobalKey());
  }

  /**
   * The order's proposal as submitted (no previous) and its approved version (store link to the
   * submitted version, no CDM previous step, venue approval true).
   */
  private static void assertProposalThenApproval(List<StoredCdm> chain) {
    WorkflowStep submitted = step(chain.get(0));
    WorkflowStep approved = step(chain.get(1));
    assertThat(chain.get(0).previousGlobalKey()).isNull();
    assertThat(submitted.getProposedEvent()).isNotNull();
    assertThat(submitted.getApproval()).isNullOrEmpty();
    assertThat(chain.get(1).previousGlobalKey()).isEqualTo(chain.get(0).globalKey());
    assertThat(approved.getPreviousWorkflowStep()).isNull();
    assertThat(approved.getAction()).isEqualTo(ActionEnum.NEW);
    assertThat(approved.getApproval()).singleElement()
        .satisfies(a -> assertThat(a.getApproved()).isTrue());
    assertThat(approved.getProposedEvent()).isEqualTo(submitted.getProposedEvent());
  }

  /**
   * A fill's own workflow at {@code chain[at]}, {@code chain[at+1]}: proposed (no previous, in
   * the store or in CDM) then accepted (linked to it) with the trade's execution event.
   */
  private static void assertFill(List<StoredCdm> chain, int at, String tradeId) {
    StoredCdm proposed = chain.get(at);
    StoredCdm accepted = chain.get(at + 1);
    assertThat(proposed.previousGlobalKey()).isNull();
    assertThat(step(proposed).getPreviousWorkflowStep()).isNull();
    assertThat(step(proposed).getProposedEvent()).isNotNull();
    assertThat(CdmLifecycle.identifierValue(step(proposed).getEventIdentifier(),
        ExecutionMapper.FILL_ID_SCHEME)).isEqualTo(tradeId);
    assertCdmLinked(proposed, accepted);
    WorkflowStep acceptedStep = step(accepted);
    assertThat(acceptedStep.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.ACCEPTED);
    assertThat(ExecutionMapper.tradeId(ExecutionMapper.afterState(
        acceptedStep.getBusinessEvent()))).isEqualTo(tradeId);
  }

  /** The trade's ref holds only its TradeState, produced by {@code producer}. */
  private void assertTradeStateOnly(CdmRef tradeRef, ProducedBy producer) {
    assertThat(store.history(tradeRef)).singleElement().satisfies(state -> {
      assertThat(state.cdmType()).isEqualTo(TRADE_STATE);
      assertThat(state.previousGlobalKey()).isNull();
      assertThat(state.producedBy()).isEqualTo(producer);
      assertThat(state.validation().status()).isEqualTo(Status.VALID);
    });
  }

  private static boolean isCancel(WorkflowStep step) {
    return step.getAction() == ActionEnum.CORRECT
        && step.getWorkflowState().getWorkflowStatus() == WorkflowStatusEnum.CANCELLED;
  }

  private double objects(String type, Status status) {
    var counter = registry.find("traderx.cdm.objects").tag("type", type)
        .tag("validation", status.name()).counter();
    return counter == null ? 0 : counter.count();
  }

  private double failures(String stage) {
    var counter = registry.find("traderx.cdm.failures").tag("stage", stage).counter();
    return counter == null ? 0 : counter.count();
  }

  private double pendingGauge() {
    return registry.get("traderx.cdm.pending.trades").gauge().value();
  }

  private double pendingCancelsGauge() {
    return registry.get("traderx.cdm.pending.cancels").gauge().value();
  }

  private static OrderEvent order(String orderId) {
    OrderEvent o = newOrder();
    return new OrderEvent(orderId, o.accountId(), o.security(), o.side(), o.quantity(),
        o.remainingQuantity(), o.limitPrice(), "NEW", o.updatedAt(), null, null);
  }

  private static OrderEvent canceledUnfilled(String orderId) {
    return new OrderEvent(orderId, ACCOUNT, "IBM", "Buy", 1500, 0, bd("180.000"), "CANCELED",
        ORDER_AT.plusSeconds(60), null, null, 0, 1500);
  }

  private static BookedTrade fill(String tradeId, String orderId, int quantity, Instant at) {
    return new BookedTrade(tradeId, orderId, ACCOUNT, "IBM", "Buy", quantity, bd("179.500"), at);
  }

  // --- order path ----------------------------------------------------------------------------

  @Test
  void newOrderStoresProposalThenApproval() {
    adapter.onOrder(newOrder());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(2);
    assertProposalThenApproval(chain);
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status()).isEqualTo(Status.VALID));

    assertThat(published).extracting(Published::subject)
        .containsExactly("cdm.workflow", "cdm.workflow");
    JsonNode first = published.get(0).body();
    assertThat(first.get("cdmRef").asText()).isEqualTo(ORDER_REF.toString());
    assertThat(first.get("globalKey").asText()).isEqualTo(chain.get(0).globalKey());
    assertThat(first.get("cdmType").asText()).isEqualTo(WORKFLOW_STEP);
    assertThat(first.get("validation").get("status").asText()).isEqualTo("VALID");
    assertThat(first.get("object").get("proposedEvent")).isNotNull();
    assertThat(published.get(1).body().get("object").get("approval")).hasSize(1);
    assertThat(objects("WorkflowStep", Status.VALID)).isEqualTo(2);

    // A redelivered NEW writes nothing.
    adapter.onOrder(newOrder());
    assertThat(steps(ORDER_REF)).hasSize(2);
    assertThat(published).hasSize(2);
  }

  @Test
  void newOrderAfterAPartialWriteStoresOnlyTheApproval() {
    WorkflowStep proposal = WORKFLOW.proposed(newOrder());
    store.put(ORDER_REF, proposal, null, VALIDATOR.validate(proposal)); // the write stopped here

    adapter.onOrder(newOrder());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(2);
    assertProposalThenApproval(chain);
    assertThat(published).hasSize(1);
  }

  @Test
  void partiallyFilledAndFilledStoreNothing() {
    adapter.onOrder(newOrder());
    OrderEvent o = newOrder();
    adapter.onOrder(new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Buy", 1500, 750, o.limitPrice(),
        "PARTIALLY_FILLED", FILL1_AT, bd("179.500"), 750));
    adapter.onOrder(new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Buy", 1500, 0, o.limitPrice(),
        "FILLED", FILL1_AT, bd("179.500"), 750));

    assertThat(steps(ORDER_REF)).hasSize(2);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void fillStoresTwoStepsUnderOrderAndTradeStateOnlyUnderTrade() {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    assertProposalThenApproval(chain);
    assertFill(chain, 2, fill1().id());
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status()).isEqualTo(Status.VALID));
    WorkflowStep accepted = step(chain.get(3));
    assertThat(ExecutionMapper.executedQuantity(List.of(accepted))).isEqualTo(750);
    assertThat(ExecutionMapper.afterState(accepted.getBusinessEvent()).getTrade()
        .getExecutionDetails().getExecutionType()).isEqualTo(ExecutionTypeEnum.ON_VENUE);

    assertTradeStateOnly(FILL1_TRADE_REF, new ProducedBy(ORDER_REF, chain.get(3).globalKey()));
    TradeState state = store.latest(FILL1_TRADE_REF, TradeState.class)
        .map(s -> read(s, TradeState.class)).orElseThrow();
    assertThat(state).isEqualTo(ExecutionMapper.afterState(accepted.getBusinessEvent()));

    assertThat(published).extracting(Published::subject).containsExactly(
        "cdm.workflow", "cdm.workflow", "cdm.workflow", "cdm.workflow", "cdm.events",
        "cdm.tradestate");
  }

  @Test
  void fillBeforeOrderIsParkedThenReplayedInNewShape() {
    adapter.onBookedTrade(fill1());

    assertThat(store.history(ORDER_REF)).isEmpty();
    assertThat(store.history(FILL1_TRADE_REF)).isEmpty();
    assertThat(pendingTrades.count()).isEqualTo(1);
    assertThat(pendingGauge()).isEqualTo(1);

    adapter.onOrder(newOrder());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    assertProposalThenApproval(chain);
    assertFill(chain, 2, fill1().id());
    assertTradeStateOnly(FILL1_TRADE_REF, new ProducedBy(ORDER_REF, chain.get(3).globalKey()));
    assertThat(pendingTrades.count()).isZero();
    assertThat(pendingGauge()).isZero();
    assertThat(published).extracting(Published::subject).containsExactly(
        "cdm.workflow", "cdm.workflow", "cdm.workflow", "cdm.workflow", "cdm.events",
        "cdm.tradestate");
  }

  @Test
  void redeliveredFillWritesNothing() {
    // Redelivered while parked: one pending row.
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill1());
    assertThat(pendingTrades.count()).isEqualTo(1);

    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill2());
    long rows = documents.count();
    int publishedBefore = published.size();
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill2());

    assertThat(documents.count()).isEqualTo(rows);
    assertThat(published).hasSize(publishedBefore);
    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(6);
    assertFill(chain, 2, fill1().id());
    assertFill(chain, 4, fill2().id());
    assertThat(ExecutionMapper.executedQuantity(chain.stream().map(LifecycleAdapterTest::step)
        .toList())).isEqualTo(1500);
    assertThat(store.history(FILL1_TRADE_REF)).hasSize(1);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void fillResumesAfterPartialWrite() {
    adapter.onOrder(newOrder());
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(WORKFLOW.proposed(newOrder()), fill1());
    store.put(ORDER_REF, fill.proposed(), null, VALIDATOR.validate(fill.proposed()));

    adapter.onBookedTrade(fill1());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    assertFill(chain, 2, fill1().id());
    assertTradeStateOnly(FILL1_TRADE_REF, new ProducedBy(ORDER_REF, chain.get(3).globalKey()));
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void fillsNeverChainAcrossOrders() {
    String other = "ord-013-0102";
    adapter.onOrder(order(ORDER_ID));
    adapter.onOrder(order(other));
    adapter.onBookedTrade(fill("trd-a-1", ORDER_ID, 500, FILL1_AT));
    adapter.onBookedTrade(fill("trd-b-1", other, 400, FILL1_AT));
    adapter.onBookedTrade(fill("trd-a-2", ORDER_ID, 300, FILL1_AT.plusSeconds(1)));
    adapter.onBookedTrade(fill("trd-b-2", other, 200, FILL1_AT.plusSeconds(1)));

    for (String orderId : List.of(ORDER_ID, other)) {
      List<StoredCdm> chain = steps(CdmRef.parse("cdm:order:" + orderId));
      assertThat(chain).hasSize(6);
      assertProposalThenApproval(chain);
      String prefix = orderId.equals(ORDER_ID) ? "trd-a-" : "trd-b-";
      assertFill(chain, 2, prefix + "1");
      assertFill(chain, 4, prefix + "2");
      assertThat(chain).allSatisfy(s ->
          assertThat(WorkflowMapper.orderId(step(s))).isEqualTo(orderId));
    }
    assertThat(ExecutionMapper.executedQuantity(steps(ORDER_REF).stream()
        .map(LifecycleAdapterTest::step).toList())).isEqualTo(800);
  }

  @Test
  void cancelIsACorrectionOfTheApprovedProposalAfterTheFills() {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onOrder(canceledOrder());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(5);
    StoredCdm cancel = chain.get(4);
    assertThat(isCancel(step(cancel))).isTrue();
    // After the latest proposal version (the approved one), never after a fill step.
    assertCdmLinked(chain.get(1), cancel);
    assertThat(step(cancel).getWorkflowState().getComment())
        .isEqualTo("Unfilled remainder of 750 shares cancelled; 750 executed by fills,"
            + " which stand");
    assertThat(cancel.validation().status()).isEqualTo(Status.VALID);
    assertThat(pendingCancels.count()).isZero();

    int publishedBefore = published.size();
    adapter.onOrder(canceledOrder());
    assertThat(steps(ORDER_REF)).hasSize(5);
    assertThat(published).hasSize(publishedBefore);
  }

  @Test
  void cancelWithNoFillsStoresValidCorrection() {
    adapter.onOrder(newOrder());
    adapter.onOrder(canceledUnfilled(ORDER_ID));

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(3);
    assertCdmLinked(chain.get(1), chain.get(2));
    WorkflowStep cancel = step(chain.get(2));
    assertThat(isCancel(cancel)).isTrue();
    assertThat(cancel.getWorkflowState().getComment())
        .isEqualTo("Unfilled remainder of 1500 shares cancelled; 0 executed by fills,"
            + " which stand");
    assertThat(chain.get(2).validation().status()).isEqualTo(Status.VALID);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void cancelBeforeItsFillIsBookedIsAppliedAfterTheFill() {
    adapter.onOrder(newOrder());
    adapter.onOrder(canceledOrder()); // 750 filled per the matcher, not booked yet

    assertThat(steps(ORDER_REF)).hasSize(2);
    assertThat(pendingCancels.count()).isEqualTo(1);

    adapter.onBookedTrade(fill1());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(5);
    assertFill(chain, 2, fill1().id());
    assertThat(isCancel(step(chain.get(4)))).isTrue();
    assertCdmLinked(chain.get(1), chain.get(4));
    assertThat(pendingCancels.count()).isZero();
  }

  @Test
  void cancelBeforeAnyNewIsParkedThenStoredAfterTheApproval() {
    adapter.onOrder(canceledUnfilled(ORDER_ID)); // the matcher does not know it yet: parked

    assertThat(store.history(ORDER_REF)).isEmpty();
    assertThat(pendingCancels.count()).isEqualTo(1);

    adapter.onOrder(newOrder());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(3);
    assertProposalThenApproval(chain);
    assertCdmLinked(chain.get(1), chain.get(2));
    assertThat(isCancel(step(chain.get(2)))).isTrue();
    assertThat(pendingCancels.count()).isZero();
  }

  // --- direct trades, rejections, failures ---------------------------------------------------

  @Test
  void directTradeUsesDirectRefAndOffFacility() {
    adapter.onBookedTrade(directTrade());

    List<StoredCdm> chain = steps(DIRECT_REF);
    assertThat(chain).hasSize(2);
    assertChained(chain);
    assertThat(chain).allSatisfy(s -> assertThat(s.validation().status()).isEqualTo(Status.VALID));
    WorkflowStep accepted = step(chain.get(1));
    assertThat(accepted.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.ACCEPTED);
    assertThat(ExecutionMapper.afterState(accepted.getBusinessEvent()).getTrade()
        .getExecutionDetails().getExecutionType()).isEqualTo(ExecutionTypeEnum.OFF_FACILITY);
    assertTradeStateOnly(DIRECT_TRADE_REF, new ProducedBy(DIRECT_REF, chain.get(1).globalKey()));
    assertThat(published).extracting(Published::subject).containsExactly(
        "cdm.workflow", "cdm.workflow", "cdm.events", "cdm.tradestate");

    adapter.onBookedTrade(directTrade());
    assertThat(store.history(DIRECT_REF)).hasSize(2);
    assertThat(store.history(DIRECT_TRADE_REF)).hasSize(1);
    assertThat(published).hasSize(4);
  }

  @Test
  void noBusinessEventStoredStandalone() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill2());
    adapter.onBookedTrade(directTrade());
    adapter.onRejected(rejectedOrder());

    assertThat(documents.findAll()).extracting(d -> d.getCdmType())
        .doesNotContain(BUSINESS_EVENT);
    assertThat(failureRows.count()).isZero();

    // cdm.events carries each accepted step's business event, taken from the step.
    List<Published> events = published.stream()
        .filter(p -> p.subject().equals("cdm.events")).toList();
    assertThat(events).hasSize(3);
    List<StoredCdm> order = steps(ORDER_REF);
    StoredCdm fill1Accepted = order.get(3);
    JsonNode first = events.get(0).body();
    assertThat(first.get("cdmRef").asText()).isEqualTo(ORDER_REF.toString());
    assertThat(first.get("cdmType").asText()).isEqualTo(BUSINESS_EVENT);
    assertThat(first.get("stepGlobalKey").asText()).isEqualTo(fill1Accepted.globalKey());
    assertThat(first.get("validation").get("status").asText()).isEqualTo("VALID");
    BusinessEvent event = step(fill1Accepted).getBusinessEvent();
    assertThat(EXACT.readTree(new String(events.get(0).raw(), UTF_8)).get("object"))
        .isEqualTo(EXACT.readTree(CDM_JSON.writeValueAsString(event)));
    assertThat(first.get("globalKey").asText()).isEqualTo(store.globalKeyOf(event));
    assertThat(events.get(2).body().get("cdmRef").asText()).isEqualTo(DIRECT_REF.toString());
  }

  @Test
  void invalidObjectIsStoredWithInvalidStatusAndDoesNotThrow() {
    adapter.onOrder(newOrder());
    BookedTrade wrongSide = new BookedTrade("trd-017-0009", ORDER_ID, ACCOUNT, "IBM", "Sell",
        100, bd("179.500"), FILL1_AT);

    assertThatCode(() -> adapter.onBookedTrade(wrongSide)).doesNotThrowAnyException();

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    // Both steps of the fill carry the mismatch against the order's instruction.
    for (StoredCdm fillStep : chain.subList(2, 4)) {
      assertThat(fillStep.validation().status()).isEqualTo(Status.INVALID);
      assertThat(fillStep.validation().messages())
          .anySatisfy(m -> assertThat(m).startsWith(ExecutionMapper.MISMATCH_PREFIX));
    }
    assertThat(objects("WorkflowStep", Status.INVALID)).isEqualTo(2);
    assertThat(published.get(2).body().get("validation").get("status").asText())
        .isEqualTo("INVALID");
  }

  @Test
  void mapperFailureIsRecordedAndDoesNotThrow() {
    OrderEvent unknownSecurity = new OrderEvent("ord-013-0999", ACCOUNT, "NOPE", "Buy", 10, 10,
        bd("1.000"), "NEW", ORDER_AT, null, null);

    assertThatCode(() -> adapter.onOrder(unknownSecurity)).doesNotThrowAnyException();
    assertThatCode(() -> adapter.onOrder(null)).doesNotThrowAnyException();
    assertThatCode(() -> adapter.onBookedTrade(null)).doesNotThrowAnyException();

    assertThat(store.history(CdmRef.parse("cdm:order:ord-013-0999"))).isEmpty();
    List<LifecycleFailure> rows = failureRows.findAll();
    assertThat(rows).hasSize(3);
    LifecycleFailure failure = rows.stream()
        .filter(f -> "cdm:order:ord-013-0999".equals(f.getCdmRef())).findFirst().orElseThrow();
    assertThat(failure.getStage()).isEqualTo("order");
    assertThat(failure.getMessage()).contains("NOPE");
    assertThat(failure.getPayload()).contains("ord-013-0999");
    assertThat(failure.getFailedAt()).isNotNull();
    assertThat(failures("order")).isEqualTo(2);
    assertThat(failures("execution")).isEqualTo(1);
  }

  @Test
  void fillAfterStoredCancelIsRecordedAsFailure() {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onOrder(canceledOrder());

    adapter.onBookedTrade(fill2());

    assertThat(steps(ORDER_REF)).hasSize(5);
    assertThat(store.history(CdmRef.parse("cdm:trade:" + fill2().id()))).isEmpty();
    assertThat(failureRows.findAll()).singleElement().satisfies(f -> {
      assertThat(f.getStage()).isEqualTo("execution");
      assertThat(f.getMessage()).contains("already cancelled");
    });
  }

  @Test
  void cancelOfUnknownOrderIsParked() {
    adapter.onOrder(canceledOrder());

    assertThat(store.history(ORDER_REF)).isEmpty();
    assertThat(pendingCancels.count()).isEqualTo(1);
    assertThat(pendingCancelsGauge()).isEqualTo(1);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void bookedTradeForUnknownOrderRecoversFromMatcher() {
    matcher.put(ORDER_ID, newOrder());

    adapter.onBookedTrade(fill1());

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    assertProposalThenApproval(chain);
    assertFill(chain, 2, fill1().id());
    assertThat(pendingTrades.count()).isZero();
    // The late NEW finds the same proposal and approval (built from the matcher's createdAt).
    adapter.onOrder(newOrder());
    assertThat(steps(ORDER_REF)).hasSize(4);
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void cancelForUnknownOrderRecoversFromMatcher() {
    matcher.put(ORDER_ID, newOrder());
    adapter.onOrder(canceledUnfilled(ORDER_ID));

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(3);
    assertProposalThenApproval(chain);
    assertCdmLinked(chain.get(1), chain.get(2));
    assertThat(isCancel(step(chain.get(2)))).isTrue();
  }

  @Test
  void sweepReplaysParkedTrades() {
    adapter.onBookedTrade(fill1()); // matcher does not know the order yet: parked
    assertThat(pendingTrades.count()).isEqualTo(1);
    matcher.put(ORDER_ID, newOrder());

    adapter.sweep(); // parked less than 10s ago: left alone
    assertThat(pendingTrades.count()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(11));
    adapter.sweep();

    List<StoredCdm> chain = steps(ORDER_REF);
    assertThat(chain).hasSize(4);
    assertProposalThenApproval(chain);
    assertFill(chain, 2, fill1().id());
    assertThat(pendingTrades.count()).isZero();
    assertThat(pendingGauge()).isZero();
  }

  @Test
  void staleParkedCancelIsRecordedAsFailure() {
    adapter.onOrder(newOrder());
    adapter.onOrder(canceledOrder()); // 750 filled per the matcher, none booked: parked

    clock.advance(Duration.ofMinutes(4));
    adapter.sweep();
    assertThat(failureRows.count()).isZero();

    clock.advance(Duration.ofMinutes(2));
    adapter.sweep();
    adapter.sweep();

    assertThat(failureRows.findAll()).singleElement().satisfies(f -> {
      assertThat(f.getStage()).isEqualTo("stale");
      assertThat(f.getCdmRef()).isEqualTo(ORDER_REF.toString());
    });
    assertThat(pendingCancels.findById(ORDER_ID).orElseThrow().getReportedAt()).isNotNull();
    assertThat(failures("stale")).isEqualTo(1);

    // The fill finally arrives: the cancel is stored after it and the entry removed.
    adapter.onBookedTrade(fill1());
    assertThat(steps(ORDER_REF)).hasSize(5);
    assertThat(pendingCancels.count()).isZero();
  }

  @Test
  void staleParkedTradeIsRecordedAsFailureOnce() {
    adapter.onBookedTrade(fill1());
    clock.advance(Duration.ofMinutes(6));
    adapter.sweep();
    adapter.sweep();

    assertThat(failureRows.findAll()).singleElement()
        .satisfies(f -> assertThat(f.getStage()).isEqualTo("stale"));
    assertThat(pendingTrades.count()).isEqualTo(1);
  }

  @Test
  void rejectedResumesAfterPartialWrite() {
    CdmRef ref = CdmRef.parse("cdm:order:" + rejectedOrder().rejectionId());
    WorkflowStep proposal = WORKFLOW.rejectedProposal(rejectedOrder());
    store.putNext(ref, proposal, VALIDATOR.validate(proposal)); // the write stopped here

    adapter.onRejected(rejectedOrder());

    List<StoredCdm> chain = steps(ref);
    assertThat(chain).hasSize(2);
    assertChained(chain);
    assertThat(step(chain.get(1)).getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.REJECTED);
    assertThat(published).hasSize(1);
  }

  @Test
  void directTradeResumesAfterPartialWrite() {
    ExecutionMapper.FillWorkflow direct = EXECUTION.direct(directTrade());
    store.putNext(DIRECT_REF, direct.proposed(), VALIDATOR.validate(direct.proposed()));

    adapter.onBookedTrade(directTrade());

    List<StoredCdm> chain = steps(DIRECT_REF);
    assertThat(chain).hasSize(2);
    assertChained(chain);
    assertTradeStateOnly(DIRECT_TRADE_REF, new ProducedBy(DIRECT_REF, chain.get(1).globalKey()));
    assertThat(failureRows.count()).isZero();
  }

  @Test
  void throwingValidatorStoresInvalidRatherThanSkipping() {
    CdmValidator throwing = new CdmValidator(LifecycleFixtures.INJECTOR) {
      @Override
      public CdmValidationResult validateExecution(BusinessEvent event) {
        throw new IllegalStateException("validator broke");
      }
    };
    adapter = adapter(throwing);

    adapter.onBookedTrade(directTrade());

    StoredCdm accepted = steps(DIRECT_REF).get(1);
    assertThat(accepted.validation().status()).isEqualTo(Status.INVALID);
    assertThat(store.latest(DIRECT_TRADE_REF, TradeState.class)).isPresent();
    assertThat(failures("validate")).isPositive();
  }

  @Test
  void publishedObjectIsTheStoredJsonVerbatim() throws Exception {
    adapter.onOrder(newOrder());

    StoredCdm proposed = steps(ORDER_REF).get(0);
    JsonNode object = EXACT.readTree(new String(published.get(0).raw(), UTF_8)).get("object");
    assertThat(object).isEqualTo(EXACT.readTree(proposed.json()));
    assertThat(sha256(store.canonicalJson(object))).isEqualTo(proposed.globalKey());
  }

  @Test
  void rejectedOrderStoredUnderRejectionId() {
    adapter.onRejected(rejectedOrder());

    CdmRef ref = CdmRef.parse("cdm:order:" + rejectedOrder().rejectionId());
    List<StoredCdm> chain = steps(ref);
    assertThat(chain).hasSize(2);
    assertChained(chain);
    WorkflowStep rejected = step(chain.get(1));
    assertThat(rejected.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.REJECTED);
    assertThat(rejected.getApproval()).singleElement()
        .satisfies(a -> assertThat(a.getApproved()).isFalse());
    assertThat(published).extracting(Published::subject)
        .containsExactly("cdm.workflow", "cdm.workflow");

    adapter.onRejected(rejectedOrder());
    assertThat(steps(ref)).hasSize(2);
  }

  private static <T> T read(StoredCdm stored, Class<T> type) {
    try {
      return CDM_JSON.readValue(stored.json(), type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
