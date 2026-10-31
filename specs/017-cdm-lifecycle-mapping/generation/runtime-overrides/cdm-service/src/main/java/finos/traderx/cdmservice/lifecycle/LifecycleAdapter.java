package finos.traderx.cdmservice.lifecycle;

import cdm.event.common.BusinessEvent;
import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.rosetta.model.lib.RosettaModelObject;
import finos.traderx.cdmservice.metrics.CdmMetrics;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.ProducedBy;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import finos.traderx.cdmservice.validation.CdmValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Turns TraderX order and trade events into stored, linked CDM objects (Task 10; stored layout
 * of the fill-instruction amendment).
 *
 * <p><b>Refs and layout.</b>
 * <ul>
 *   <li>{@code cdm:order:<orderId>}: the order's proposal as submitted, its approved version (the
 *       matcher's {@code NEW}: the venue's approval), then per fill its own workflow (fill
 *       proposed, fill accepted carrying the execution event), and the cancel (a correction of
 *       the latest proposal version, status Cancelled) last. Store links
 *       ({@code previous_global_key}) follow the CDM workflows: the approved version links to the
 *       submitted one (in CDM it has no previous step: it is the same proposal, re-stated with
 *       its approval); the cancel links to the latest proposal version (as its
 *       {@code previousWorkflowStep} does); a fill's proposal has no previous (it starts its own
 *       workflow, linked to the order by the order id in its identifiers) and its acceptance
 *       links to it. Version links never cross refs.</li>
 *   <li>{@code cdm:trade:<tradeId>}: only the trade's {@link TradeState}, with "produced by"
 *       ({@link ProducedBy}) the accepted step that executed it.</li>
 *   <li>{@code cdm:direct:<tradeId>}: a direct trade's workflow (proposed, accepted).</li>
 *   <li>A rejected request: {@code cdm:order:<rejectionId>} (proposal, rejected step).</li>
 * </ul>
 * No {@link BusinessEvent} is stored on its own: it is the accepted step's.
 *
 * <p><b>NATS.</b> Every step written is published on {@code cdm.workflow}; each newly written
 * accepted step's business event, taken from the step, on {@code cdm.events}; each trade state
 * on {@code cdm.tradestate}.
 *
 * <p><b>Ordering and redelivery.</b> Every decision for a ref ("is this trade already
 * executed?", "what is the latest step?") is read from the store and acted on under the ref's
 * write lock ({@link CdmStore#withRefLock}), so concurrent events for one order cannot fork the
 * chain, redelivered events are no-ops, and a chain whose write stopped part-way resumes from
 * its last stored step.
 *
 * <p><b>Out-of-order events (Ruling R26).</b> A booked trade or a cancel for an order with no
 * approved proposal first reads the order back from the matcher ({@link OrderSource}) and stores
 * its proposal and approval; if that fails it is parked ({@code cdm_pending_trades},
 * {@code cdm_pending_cancels}). Parked trades, then a parked cancel, are replayed when the
 * order's {@code NEW} arrives, and {@link #sweep} retries everything parked for more than
 * {@value #RETRY_AFTER_SECONDS}s. A {@code CANCELED} event whose order has fewer executed shares
 * than the matcher's {@code filledQuantity} (fills not booked yet) is parked too, so the cancel
 * stays the last step. Anything parked for more than {@value #STALE_AFTER_MINUTES} minutes is
 * recorded as a failure, once.
 *
 * <p><b>Failures.</b> CDM failures never reach TraderX: no method throws. Mapper, validation,
 * store and publish failures are recorded by {@link LifecycleFailures} (log, metric, stored
 * marker). An object that maps but does not validate is stored with its {@code INVALID} or
 * {@code NOT_QUALIFIED} result. A failed publish is recorded and <b>not retried</b>: the object
 * is stored, and consumers that missed it read it from the store (lineage/resolve APIs).
 *
 * <p>Store writes of the {@code cdm:trade:} refs and publishing happen after the order's (or
 * direct trade's) ref lock is released: the store's locks are striped, so holding two could deadlock.
 */
@Component
public class LifecycleAdapter {

  static final int RETRY_AFTER_SECONDS = 10;
  static final int STALE_AFTER_MINUTES = 5;

  private static final Logger log = LoggerFactory.getLogger(LifecycleAdapter.class);
  private static final String WORKFLOW_STEP = WorkflowStep.class.getName();

  private final CdmStore store;
  private final WorkflowMapper workflow;
  private final ExecutionMapper execution;
  private final CdmValidator validator;
  private final PendingTradeRepository pendingTrades;
  private final PendingCancelRepository pendingCancels;
  private final LifecycleFailures failures;
  private final OrderSource orders;
  private final Supplier<CdmPublisher> publisher;
  private final CdmMetrics metrics;
  private final ObjectMapper cdmObjectMapper;
  private final Clock clock;

  @Autowired
  public LifecycleAdapter(CdmStore store, WorkflowMapper workflow, ExecutionMapper execution,
      CdmValidator validator, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels, LifecycleFailures failures, OrderSource orders,
      ObjectProvider<CdmPublisher> publisher, CdmMetrics metrics,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper) {
    this(store, workflow, execution, validator, pendingTrades, pendingCancels, failures, orders,
        () -> publisher.getIfAvailable(() -> CdmPublisher.NONE), metrics, cdmObjectMapper,
        Clock.systemUTC());
  }

  public LifecycleAdapter(CdmStore store, WorkflowMapper workflow, ExecutionMapper execution,
      CdmValidator validator, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels, LifecycleFailures failures, OrderSource orders,
      CdmPublisher publisher, CdmMetrics metrics, ObjectMapper cdmObjectMapper, Clock clock) {
    this(store, workflow, execution, validator, pendingTrades, pendingCancels, failures, orders,
        () -> publisher, metrics, cdmObjectMapper, clock);
  }

  private LifecycleAdapter(CdmStore store, WorkflowMapper workflow, ExecutionMapper execution,
      CdmValidator validator, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels, LifecycleFailures failures, OrderSource orders,
      Supplier<CdmPublisher> publisher, CdmMetrics metrics, ObjectMapper cdmObjectMapper,
      Clock clock) {
    this.store = store;
    this.workflow = workflow;
    this.execution = execution;
    this.validator = validator;
    this.pendingTrades = pendingTrades;
    this.pendingCancels = pendingCancels;
    this.failures = failures;
    this.orders = orders;
    this.publisher = publisher;
    this.metrics = metrics;
    this.cdmObjectMapper = cdmObjectMapper;
    this.clock = clock;
    metrics.pendingTrades(() -> count(pendingTrades::count));
    metrics.pendingCancels(() -> count(pendingCancels::count));
  }

  // --- entry points (never throw) ------------------------------------------------------------

  /**
   * An order update from {@code /orders}. {@code NEW} stores the proposal and its approved
   * version (whichever is not stored yet; a redelivered {@code NEW} writes nothing) and replays
   * the trades, then the cancel, parked for the order; {@code CANCELED} stores the cancel of the
   * unfilled remainder; {@code PARTIALLY_FILLED} and {@code FILLED} store
   * nothing, because executions come from booked trades.
   */
  public void onOrder(OrderEvent order) {
    String ref = order == null || order.orderId() == null ? null : orderRef(order.orderId())
        .toString();
    guard(ref, "order", order, () -> {
      if (order == null || order.orderId() == null || order.status() == null) {
        throw new IllegalArgumentException("order event without orderId or status");
      }
      switch (order.status()) {
        case "NEW" -> newOrder(order);
        case "CANCELED" -> cancel(order);
        case "PARTIALLY_FILLED", "FILLED" ->
            log.debug("order {} {}: executions come from booked trades", order.orderId(),
                order.status());
        default -> throw new IllegalArgumentException("unsupported order status "
            + order.status());
      }
    });
  }

  /** A rejected order request from {@code /orders/rejected}. */
  public void onRejected(RejectedOrder rejected) {
    String ref = rejected == null || rejected.rejectionId() == null ? null
        : orderRef(rejected.rejectionId()).toString();
    guard(ref, "rejection", rejected, () -> {
      if (rejected == null || rejected.rejectionId() == null) {
        throw new IllegalArgumentException("rejected order without rejectionId");
      }
      CdmRef orderRef = orderRef(rejected.rejectionId());
      List<Stored> written = store.withRefLock(orderRef, () -> {
        List<Stored> existing = steps(orderRef);
        if (existing.size() >= 2) {
          return List.<Stored>of(); // redelivered
        }
        List<WorkflowStep> expected = stage("rejection", () ->
            List.of(workflow.rejectedProposal(rejected), workflow.rejected(rejected)));
        return resume(orderRef, "rejection", existing, expected);
      });
      announce(written);
    });
  }

  /**
   * A booked trade from {@code /trades/booked}. With an {@code orderId} it is a fill, stored as
   * its own workflow under the order's ref (or parked until the order's approval is stored);
   * without one it is a direct trade under {@code cdm:direct:<tradeId>}. Either way its trade
   * state goes under {@code cdm:trade:<tradeId>}.
   */
  public void onBookedTrade(BookedTrade trade) {
    String ref = trade == null ? null
        : trade.orderId() != null ? orderRef(trade.orderId()).toString()
        : trade.id() != null ? directRef(trade.id()).toString() : null;
    guard(ref, trade != null && trade.orderId() == null ? "direct-trade" : "execution", trade,
        () -> {
          if (trade == null || trade.id() == null) {
            throw new IllegalArgumentException("booked trade without id");
          }
          if (trade.orderId() == null) {
            directTrade(trade);
          } else {
            fill(trade);
          }
        });
  }

  /**
   * Retries what is parked (Ruling R26): for every order with trades parked for more than
   * {@value #RETRY_AFTER_SECONDS}s, reads the order back from the matcher if its proposal is
   * still missing and replays the trades; re-applies cancels parked that long. Then records a
   * failure, once, for anything parked for more than {@value #STALE_AFTER_MINUTES} minutes.
   * Never throws. Called every 30s by {@link NatsLifecycleListener}.
   */
  public void sweep() {
    try {
      Instant now = clock.instant();
      Instant retryBefore = now.minusSeconds(RETRY_AFTER_SECONDS);
      Set<String> orderIds = new LinkedHashSet<>();
      pendingTrades.findByReceivedAtBeforeOrderByReceivedAtAscTradeIdAsc(retryBefore)
          .forEach(p -> orderIds.add(p.getOrderId()));
      for (String orderId : orderIds) {
        guard(orderRef(orderId).toString(), "pending", "sweep of order " + orderId, () -> {
          recoverProposal(orderId);
          replayPendingTrades(orderId);
        });
      }
      for (PendingCancel cancel : pendingCancels.findByReceivedAtBefore(retryBefore)) {
        applyPendingCancel(cancel.getOrderId());
      }
      reportStale(now.minus(Duration.ofMinutes(STALE_AFTER_MINUTES)));
    } catch (RuntimeException e) {
      failures.record(null, "pending", e, "sweep");
    }
  }

  /**
   * Records each entry parked before {@code staleBefore} as a failure, once. Each entry is
   * re-read and marked under its order's lock, the lock under which entries are parked and
   * removed, so an entry that was just replayed is never written back.
   */
  private void reportStale(Instant staleBefore) {
    for (PendingTrade stale
        : pendingTrades.findByReceivedAtBeforeOrderByReceivedAtAscTradeIdAsc(staleBefore)) {
      if (stale.getReportedAt() != null) {
        continue;
      }
      CdmRef ref = orderRef(stale.getOrderId());
      store.withRefLock(ref, () -> {
        pendingTrades.findById(stale.getTradeId())
            .filter(trade -> trade.getReportedAt() == null)
            .ifPresent(trade -> {
              failures.record(ref.toString(), "stale", "trade " + trade.getTradeId()
                  + " parked since " + trade.getReceivedAt() + ": order "
                  + trade.getOrderId() + " has no approved proposal", trade.getPayload());
              trade.setReportedAt(micros());
              pendingTrades.save(trade);
            });
        return null;
      });
    }
    for (PendingCancel stale : pendingCancels.findByReceivedAtBefore(staleBefore)) {
      if (stale.getReportedAt() != null) {
        continue;
      }
      CdmRef ref = orderRef(stale.getOrderId());
      store.withRefLock(ref, () -> {
        pendingCancels.findById(stale.getOrderId())
            .filter(cancel -> cancel.getReportedAt() == null)
            .ifPresent(cancel -> {
              failures.record(ref.toString(), "stale", "cancel of order " + cancel.getOrderId()
                  + " parked since " + cancel.getReceivedAt() + ": its fills are not all"
                  + " booked, or the order has no approved proposal", cancel.getPayload());
              cancel.setReportedAt(micros());
              pendingCancels.save(cancel);
            });
        return null;
      });
    }
  }

  // --- order path ----------------------------------------------------------------------------

  private void newOrder(OrderEvent order) {
    CdmRef ref = orderRef(order.orderId());
    List<Stored> written = store.withRefLock(ref, () -> storeProposal(ref, chain(ref), order));
    announce(written);
    replayPendingTrades(order.orderId());
    // A cancel parked because the order was unknown (no fills to wait for) can go in now.
    applyPendingCancel(order.orderId());
  }

  /**
   * Stores whichever of the order's proposal and approved version is missing: the proposal of
   * {@code order}, then its approval by the venue at the order's time (the matcher's {@code NEW}
   * or, on recovery, its {@code createdAt}). Nothing if both are stored.
   */
  private List<Stored> storeProposal(CdmRef ref, Chain chain, OrderEvent order) {
    List<Stored> out = new ArrayList<>();
    Stored submitted = chain.submitted();
    if (submitted == null) {
      WorkflowStep proposed = stage("order", () -> workflow.proposed(order));
      submitted = putStep(ref, proposed, null, validate(ref, proposed));
      out.add(submitted);
    }
    if (chain.approved() == null) {
      WorkflowStep from = submitted.step();
      WorkflowStep approved = stage("order", () -> workflow.approved(from, order.updatedAt()));
      out.add(putStep(ref, approved, submitted.stored().globalKey(), validate(ref, approved)));
    }
    return out;
  }

  /**
   * Under the order's lock: if the order has no approved proposal, reads it back from the
   * matcher ({@code GET /orders/{id}}) and stores its proposal and approval. A matcher that
   * cannot be asked is logged (the caller parks the event; the sweep retries and reports it if
   * it stays parked).
   */
  private Recovered recoverProposal(CdmRef ref, Chain chain, String orderId) {
    if (chain.approved() != null) {
      return new Recovered(chain, List.of());
    }
    Optional<OrderEvent> submitted;
    try {
      submitted = orders.submitted(orderId);
    } catch (RuntimeException e) {
      log.warn("Cannot read order {} back from the matcher: {}", orderId, e.getMessage());
      return new Recovered(chain, List.of());
    }
    if (submitted.isEmpty() || !orderId.equals(submitted.get().orderId())) {
      return new Recovered(chain, List.of());
    }
    List<Stored> written = storeProposal(ref, chain, submitted.get());
    log.info("Order {} proposal and approval rebuilt from the matcher", orderId);
    return new Recovered(chain(ref), written);
  }

  private void recoverProposal(String orderId) {
    CdmRef ref = orderRef(orderId);
    announce(store.withRefLock(ref, () -> recoverProposal(ref, chain(ref), orderId).written()));
  }

  private record Recovered(Chain chain, List<Stored> written) {
  }

  private void replayPendingTrades(String orderId) {
    List<PendingTrade> parked = pendingTrades.findByOrderIdOrderByReceivedAtAscTradeIdAsc(
        orderId);
    for (PendingTrade pending : parked) {
      BookedTrade trade;
      try {
        trade = LifecycleMessages.bookedTrade(LifecycleMessages.read(pending.getPayload()));
      } catch (RuntimeException e) {
        failures.record(orderRef(orderId).toString(), "pending", e, pending.getPayload());
        continue;
      }
      // Each replay is guarded on its own: one bad trade does not hold back the others. A
      // trade that fails stays parked (visible in the gauge) and is retried by the sweep.
      onBookedTrade(trade);
    }
  }

  /**
   * A fill: its own workflow (proposed, accepted) under the order's ref, after the order's
   * approval, then its trade state under {@code cdm:trade:<tradeId>}. A redelivered fill writes
   * nothing; a fill whose write stopped part-way resumes.
   */
  private void fill(BookedTrade trade) {
    CdmRef ref = orderRef(trade.orderId());
    FillOutcome outcome = store.withRefLock(ref, () -> {
      Chain chain = chain(ref);
      Optional<Stored> done = chain.fillAccepted(trade.id());
      if (done.isPresent()) {
        unpark(trade.id());
        return new FillOutcome(List.of(), done.get()); // redelivered
      }
      Recovered recovered = recoverProposal(ref, chain, trade.orderId());
      Chain current = recovered.chain();
      List<Stored> written = new ArrayList<>(recovered.written());
      if (current.approved() == null) {
        park(trade);
        return new FillOutcome(written, null);
      }
      if (current.cancel() != null) {
        throw new LifecycleStageException("execution", "order " + trade.orderId()
            + " is already cancelled; trade " + trade.id() + " cannot be stored before its"
            + " cancel");
      }
      WorkflowStep orderProposal = current.latestProposal().step();
      ExecutionMapper.FillWorkflow fill = stage("execution",
          () -> execution.fill(orderProposal, trade));
      written.addAll(storeWorkflow(ref, "execution", current.fillSteps(trade.id()), fill,
          (step, previous, validation) -> putStep(ref, step, previous, validation)));
      unpark(trade.id());
      return new FillOutcome(written, written.get(written.size() - 1));
    });
    List<Stored> written = new ArrayList<>(outcome.written());
    if (outcome.accepted() != null) {
      written.addAll(tradeState(trade.id(), ref, outcome.accepted()));
    }
    announce(written);
    if (outcome.accepted() != null) {
      applyPendingCancel(trade.orderId());
    }
  }

  private void park(BookedTrade trade) {
    if (pendingTrades.existsById(trade.id())) {
      return; // redelivered while parked
    }
    pendingTrades.save(new PendingTrade(trade.id(), trade.orderId(),
        LifecycleMessages.toJson(trade), micros()));
    log.info("Trade {} parked until order {} is approved", trade.id(), trade.orderId());
  }

  private void unpark(String tradeId) {
    if (pendingTrades.existsById(tradeId)) {
      pendingTrades.deleteById(tradeId);
    }
  }

  /**
   * The cancel: a correction of the order's latest proposal version (its approved version: the
   * cancel waits for the approval, recovered from the matcher if the {@code NEW} was missed),
   * stored after every fill the matcher made (else parked until they are booked).
   */
  private void cancel(OrderEvent order) {
    CdmRef ref = orderRef(order.orderId());
    List<Stored> written = store.withRefLock(ref, () -> {
      Chain chain = chain(ref);
      if (chain.cancel() != null) {
        dropPendingCancel(order.orderId());
        return List.<Stored>of(); // redelivered
      }
      Recovered recovered = recoverProposal(ref, chain, order.orderId());
      Chain current = recovered.chain();
      List<Stored> out = new ArrayList<>(recovered.written());
      if (current.approved() == null) {
        parkCancel(order, "the order has no approved proposal");
        return out;
      }
      Integer filled = order.executed();
      int executed = ExecutionMapper.executedQuantity(current.steps());
      if (filled != null && executed < filled) {
        // Fills the matcher made are not booked yet: store the cancel after them.
        parkCancel(order, (filled - executed) + " executed shares are not booked yet");
        return out;
      }
      Stored latest = current.latestProposal();
      WorkflowStep cancel = stage("cancel", () -> workflow.cancelCorrection(latest.step(),
          order.canceledRemainder(), executed, order.updatedAt()));
      out.add(putStep(ref, cancel, latest.stored().globalKey(), validate(ref, cancel)));
      dropPendingCancel(order.orderId());
      return out;
    });
    announce(written);
  }

  private void parkCancel(OrderEvent order, String why) {
    if (!pendingCancels.existsById(order.orderId())) {
      // A re-parked cancel keeps its first arrival time, so it can go stale.
      pendingCancels.save(new PendingCancel(order.orderId(), LifecycleMessages.toJson(order),
          micros()));
    }
    log.info("Cancel of order {} parked: {}", order.orderId(), why);
  }

  private void applyPendingCancel(String orderId) {
    Optional<PendingCancel> pending = pendingCancels.findById(orderId);
    if (pending.isEmpty()) {
      return;
    }
    OrderEvent canceled;
    try {
      canceled = LifecycleMessages.orderEvent(LifecycleMessages.read(pending.get().getPayload()));
    } catch (RuntimeException e) {
      failures.record(orderRef(orderId).toString(), "pending", e, pending.get().getPayload());
      dropPendingCancel(orderId);
      return;
    }
    onOrder(canceled);
  }

  private void dropPendingCancel(String orderId) {
    if (pendingCancels.existsById(orderId)) {
      pendingCancels.deleteById(orderId);
    }
  }

  /** Whether the order's approved proposal version is stored (for the startup backfill). */
  boolean hasApprovedProposal(String orderId) {
    return chain(orderRef(orderId)).approved() != null;
  }

  /** Whether the order's cancel is stored (for the startup backfill). */
  boolean hasCancel(String orderId) {
    return chain(orderRef(orderId)).cancel() != null;
  }

  // --- direct trades -------------------------------------------------------------------------

  /**
   * A direct trade: its workflow (proposed, accepted) under {@code cdm:direct:<tradeId>}, then
   * its trade state under {@code cdm:trade:<tradeId>}.
   */
  private void directTrade(BookedTrade trade) {
    CdmRef ref = directRef(trade.id());
    FillOutcome outcome = store.withRefLock(ref, () -> {
      List<Stored> existing = steps(ref);
      Optional<Stored> done = new Chain(existing).fillAccepted(trade.id());
      if (done.isPresent()) {
        return new FillOutcome(List.of(), done.get()); // redelivered
      }
      ExecutionMapper.FillWorkflow direct = stage("direct-trade",
          () -> execution.direct(trade));
      List<Stored> written = storeWorkflow(ref, "direct-trade", existing, direct,
          (step, previous, validation) -> appendStep(ref, step, validation));
      return new FillOutcome(written, written.get(written.size() - 1));
    });
    List<Stored> written = new ArrayList<>(outcome.written());
    written.addAll(tradeState(trade.id(), ref, outcome.accepted()));
    announce(written);
  }

  /** How {@link #storeWorkflow} writes one step after {@code previousGlobalKey}. */
  @FunctionalInterface
  private interface StepWriter {
    Stored write(WorkflowStep step, String previousGlobalKey, CdmValidationResult validation);
  }

  /**
   * Stores the steps of {@code workflow} (proposed, accepted) that are not stored yet: a
   * workflow whose write stopped after the proposal resumes with the acceptance. The stored
   * steps must be the mapped ones, else this throws rather than mixing two versions of the
   * workflow. Returns what was written plus, last, the accepted step (written or stored).
   */
  private List<Stored> storeWorkflow(CdmRef ref, String stage, List<Stored> existing,
      ExecutionMapper.FillWorkflow workflow, StepWriter writer) {
    List<WorkflowStep> expected = List.of(workflow.proposed(), workflow.accepted());
    for (int i = 0; i < existing.size(); i++) {
      if (i >= expected.size()
          || !existing.get(i).stored().globalKey().equals(store.globalKeyOf(expected.get(i)))) {
        throw new LifecycleStageException(stage, "stored step " + i + " of trade "
            + ExecutionMapper.tradeId(workflow.after()) + " under " + ref
            + " differs from the mapped workflow; not appending to it");
      }
    }
    List<Stored> out = new ArrayList<>();
    Stored proposed = existing.isEmpty() ? null : existing.get(0);
    if (proposed == null) {
      proposed = writer.write(workflow.proposed(), null,
          validated(ref, () -> workflow.validateProposed(validator)));
      out.add(proposed);
    }
    if (existing.size() < 2) {
      out.add(writer.write(workflow.accepted(), proposed.stored().globalKey(),
          validated(ref, () -> workflow.validateAccepted(validator))));
    }
    return out;
  }

  /**
   * Appends the steps of {@code expected} that are not stored yet: a chain whose write stopped
   * part-way (for example the store failed after the first step) resumes from its last stored
   * step. The stored steps must be the expected ones, else this throws rather than mixing two
   * versions of the workflow in one chain.
   */
  private List<Stored> resume(CdmRef ref, String stage, List<Stored> existing,
      List<WorkflowStep> expected) {
    for (int i = 0; i < existing.size(); i++) {
      if (i >= expected.size()
          || !existing.get(i).stored().globalKey().equals(store.globalKeyOf(expected.get(i)))) {
        throw new LifecycleStageException(stage, "stored step " + i + " under " + ref
            + " differs from the mapped workflow; not appending to it");
      }
    }
    List<Stored> out = new ArrayList<>();
    for (WorkflowStep step : expected.subList(existing.size(), expected.size())) {
      out.add(appendStep(ref, step, validate(ref, step)));
    }
    return out;
  }

  // --- trade refs ----------------------------------------------------------------------------

  /**
   * Stores the trade state of {@code accepted} (the step under {@code producerRef} that executed
   * it) as the only object under {@code cdm:trade:<tradeId>}, produced by that step, unless
   * already stored. Returns what was written.
   */
  private List<Stored> tradeState(String tradeId, CdmRef producerRef, Stored accepted) {
    CdmRef ref = tradeRef(tradeId);
    try {
      TradeState after = ExecutionMapper.afterState(accepted.step().getBusinessEvent());
      ProducedBy producedBy = new ProducedBy(producerRef, accepted.stored().globalKey());
      return store.withRefLock(ref, () -> {
        Optional<StoredCdm> before = store.latest(ref, TradeState.class);
        if (before.isPresent() && before.get().globalKey().equals(store.globalKeyOf(after))
            && producedBy.equals(before.get().producedBy())) {
          return List.<Stored>of(); // redelivered
        }
        CdmValidationResult validation = validated(ref, () -> validator.validate(after));
        StoredCdm stored = stage("store", () -> store.put(ref, after, validation, producedBy));
        return List.of(new Stored(stored, CdmPublisher.TRADE_STATE, true, null));
      });
    } catch (RuntimeException e) {
      failures.record(ref.toString(), stageOf(e, "store"), e, accepted.stored().globalKey());
      return List.of();
    }
  }

  // --- chain, validation, storage ------------------------------------------------------------

  /** One stored version, the subject it is published on, and whether this call wrote it. */
  private record Stored(StoredCdm stored, String subject, boolean isNew, WorkflowStep step) {
  }

  /** What a fill or direct trade wrote, and its accepted step (null if parked). */
  private record FillOutcome(List<Stored> written, Stored accepted) {
  }

  /**
   * The workflow steps stored under a ref, oldest first, read back as CDM objects. Under an
   * order's ref: the order's proposal versions (submitted, approved, cancel) and the fills'
   * steps (which carry a fill identifier).
   */
  private record Chain(List<Stored> stored) {

    List<WorkflowStep> steps() {
      return stored.stream().map(Stored::step).toList();
    }

    /** The order's proposal versions, oldest first (not the fills' steps). */
    private List<Stored> proposalVersions() {
      return stored.stream().filter(s -> ExecutionMapper.fillTradeId(s.step()) == null).toList();
    }

    /** The proposal as submitted, or null. */
    Stored submitted() {
      List<Stored> versions = proposalVersions();
      return versions.isEmpty() ? null : versions.get(0);
    }

    /** The approved proposal version, or null. */
    Stored approved() {
      return proposalVersions().stream().filter(s -> WorkflowMapper.isApproved(s.step()))
          .findFirst().orElse(null);
    }

    /** The cancel, or null. */
    Stored cancel() {
      return proposalVersions().stream().filter(s -> WorkflowMapper.isCancel(s.step()))
          .findFirst().orElse(null);
    }

    /** The latest proposal version (never a fill's step), or null. */
    Stored latestProposal() {
      List<Stored> versions = proposalVersions();
      return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    /** The stored steps of trade {@code tradeId}'s own workflow (proposed, accepted). */
    List<Stored> fillSteps(String tradeId) {
      return stored.stream().filter(s -> tradeId.equals(ExecutionMapper.fillTradeId(s.step())))
          .toList();
    }

    /** The accepted step that executed trade {@code tradeId}, if stored. */
    Optional<Stored> fillAccepted(String tradeId) {
      return stored.stream()
          .filter(s -> s.step().getBusinessEvent() != null
              && s.step().getBusinessEvent().getAfter() != null
              && s.step().getBusinessEvent().getAfter().size() == 1
              && tradeId.equals(ExecutionMapper.tradeId(
                  s.step().getBusinessEvent().getAfter().get(0))))
          .findFirst();
    }
  }

  private Chain chain(CdmRef ref) {
    return new Chain(steps(ref));
  }

  private List<Stored> steps(CdmRef ref) {
    return stage("store", () -> store.history(ref)).stream()
        .filter(s -> WORKFLOW_STEP.equals(s.cdmType()))
        .map(s -> new Stored(s, CdmPublisher.WORKFLOW, false, readStep(s)))
        .toList();
  }

  private WorkflowStep readStep(StoredCdm stored) {
    try {
      return cdmObjectMapper.readValue(stored.json(), WorkflowStep.class);
    } catch (JsonProcessingException e) {
      throw new LifecycleStageException("store", new IllegalStateException(
          "stored WorkflowStep " + stored.globalKey() + " under " + stored.ref()
              + " is unreadable", e));
    }
  }

  /** Appends a step to the ref's chain; the store links it to the current latest step. */
  private Stored appendStep(CdmRef ref, WorkflowStep step, CdmValidationResult validation) {
    StoredCdm stored = stage("store", () -> store.putNext(ref, step, validation));
    return new Stored(stored, CdmPublisher.WORKFLOW, true, step);
  }

  /** Stores a step under {@code ref} linked to {@code previousGlobalKey} (null: none). */
  private Stored putStep(CdmRef ref, WorkflowStep step, String previousGlobalKey,
      CdmValidationResult validation) {
    StoredCdm stored = stage("store", () -> store.put(ref, step, previousGlobalKey, validation));
    return new Stored(stored, CdmPublisher.WORKFLOW, true, step);
  }

  private CdmValidationResult validate(CdmRef ref, RosettaModelObject object) {
    return validated(ref, () -> validator.validate(object));
  }

  /** A validation that throws is recorded and stored as {@code INVALID}, never propagated. */
  private CdmValidationResult validated(CdmRef ref, Supplier<CdmValidationResult> validation) {
    try {
      return validation.get();
    } catch (RuntimeException e) {
      failures.record(ref.toString(), "validate", e, null);
      return new CdmValidationResult(Status.INVALID,
          List.of("validation error: " + LifecycleFailures.describe(e)));
    }
  }

  // --- publish and metrics -------------------------------------------------------------------

  /**
   * Counts and publishes newly stored objects, in order; after a newly stored accepted step,
   * also its business event on {@code cdm.events} (taken from the step; not stored on its own).
   * A publish that fails is recorded (stage {@code publish}) and not retried.
   */
  private void announce(List<Stored> written) {
    for (Stored s : written) {
      if (!s.isNew()) {
        continue;
      }
      StoredCdm stored = s.stored();
      metrics.objectStored(stored.cdmType(), stored.validation().status().name());
      publish(stored, s.subject(), () -> message(stored));
      BusinessEvent event = s.step() == null ? null : s.step().getBusinessEvent();
      if (event != null) {
        publish(stored, CdmPublisher.EVENTS, () -> eventMessage(stored, event));
      }
    }
  }

  private void publish(StoredCdm stored, String subject, Supplier<byte[]> message) {
    try {
      publisher.get().publish(subject, message.get());
    } catch (RuntimeException e) {
      failures.record(stored.ref().toString(), "publish", e, stored.globalKey());
    }
  }

  /**
   * {@code {cdmRef, globalKey, cdmType, validation, object}}. {@code object} is the stored
   * Rosetta JSON verbatim, so its canonical form hashes back to {@code globalKey}.
   */
  private byte[] message(StoredCdm stored) {
    return write(stored.ref(), header(stored.ref(), stored.globalKey(), stored.cdmType(),
        stored.validation()).putRawValue("object", new RawValue(stored.json())));
  }

  /**
   * The business event of the accepted step {@code step}:
   * {@code {cdmRef, globalKey, cdmType, validation, stepGlobalKey, object}}, with
   * {@code cdmRef} the step's ref, {@code globalKey} the SHA-256 of the event's canonical JSON
   * (as the store keys objects), {@code validation} its {@code Qualify_Execution} validation and
   * {@code stepGlobalKey} the step's store global key.
   */
  private byte[] eventMessage(StoredCdm step, BusinessEvent event) {
    CdmValidationResult validation = validated(step.ref(),
        () -> validator.validateExecution(event));
    ObjectNode body = header(step.ref(), store.globalKeyOf(event),
        BusinessEvent.class.getName(), validation);
    body.put("stepGlobalKey", step.globalKey());
    try {
      // Written as the store writes objects (decimals keep their scale).
      body.putRawValue("object", new RawValue(cdmObjectMapper.writeValueAsString(event)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot write the business event of " + step.ref(), e);
    }
    return write(step.ref(), body);
  }

  private ObjectNode header(CdmRef ref, String globalKey, String cdmType,
      CdmValidationResult result) {
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", ref.toString());
    body.put("globalKey", globalKey);
    body.put("cdmType", cdmType);
    ObjectNode validation = body.putObject("validation");
    validation.put("status", result.status().name());
    result.messages().forEach(validation.putArray("messages")::add);
    return body;
  }

  private byte[] write(CdmRef ref, ObjectNode body) {
    try {
      return cdmObjectMapper.writer().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot write CDM message for " + ref, e);
    }
  }

  // --- guards --------------------------------------------------------------------------------

  /** Runs {@code work}; any failure is recorded with the event, never thrown. */
  private void guard(String ref, String stage, Object event, Runnable work) {
    try {
      work.run();
    } catch (RuntimeException e) {
      failures.record(ref, stageOf(e, stage), e, String.valueOf(event));
    }
  }

  /** Runs one step of the lifecycle, tagging any failure with {@code stage}. */
  private static <T> T stage(String stage, Supplier<T> work) {
    try {
      return work.get();
    } catch (LifecycleStageException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new LifecycleStageException(stage, e);
    }
  }

  private static String stageOf(RuntimeException e, String fallback) {
    return e instanceof LifecycleStageException tagged ? tagged.stage() : fallback;
  }

  /** A gauge reading: the count, or NaN (never a fake number) if it cannot be read. */
  private static double count(Supplier<Long> count) {
    try {
      return count.get();
    } catch (RuntimeException e) {
      log.warn("Cannot count parked lifecycle events", e);
      return Double.NaN;
    }
  }

  private Instant micros() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  static CdmRef orderRef(String orderId) {
    return new CdmRef(CdmRef.Kind.ORDER, orderId);
  }

  static CdmRef tradeRef(String tradeId) {
    return new CdmRef(CdmRef.Kind.TRADE, tradeId);
  }

  static CdmRef directRef(String tradeId) {
    return new CdmRef(CdmRef.Kind.DIRECT, tradeId);
  }
}
