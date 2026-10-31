package finos.traderx.cdmservice.api;

import cdm.event.common.TradeState;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.WorkflowStepApproval;
import cdm.observable.asset.PriceQuantity;
import finos.traderx.cdmservice.lifecycle.ExecutionMapper;
import finos.traderx.cdmservice.lifecycle.WorkflowMapper;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.ProducedBy;
import finos.traderx.cdmservice.store.StoredCdm;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An order's stored workflow steps ({@code cdm:order:<orderId>}) sorted into what they are: the
 * versions of the order's proposal (as submitted, approved by the venue, or rejected), the
 * venue's approval, each fill's own two-step workflow (numbered by booking order), and the
 * cancel. Read-only; built per request from the store.
 *
 * <p><b>Fill numbers and labels.</b> Fills are numbered 1.. by execution time, then trade id
 * (Ruling R42: booking order, never arrival order, so a live run and a rebuild agree).
 * {@code filledSoFar} is the executed quantity up to and including the fill. A fill whose
 * {@code filledSoFar} first reaches the instruction quantity is {@code "Full fill"} when it is
 * fill 1 and {@code "Final fill N"} otherwise; every other fill is {@code "Partial fill N"}.
 */
final class OrderLineage {

  /** A stored step and the step read back. */
  record Row(StoredCdm stored, WorkflowStep step) {
  }

  /**
   * One fill: its proposed and accepted steps (accepted null if not stored yet, e.g. after a
   * partial write) and what it executed (from the accepted step's trade state; the proposal's
   * instruction until then, which then counts nothing towards {@code filledSoFar}).
   */
  record Fill(int number, String label, String tradeId, Row proposed, Row accepted,
      Instant executedAt, int quantity, BigDecimal price, int filledSoFar) {

    /** The trade state the fill's execution produced, or null. */
    TradeState after() {
      return accepted == null ? null : CdmApiSupport.afterState(accepted.step());
    }
  }

  private final CdmRef ref;
  private final List<Row> versions;
  private final List<Fill> fills;
  private final Row cancel;
  private final int instructionQuantity;

  private OrderLineage(CdmRef ref, List<Row> versions, List<Fill> fills, Row cancel,
      int instructionQuantity) {
    this.ref = ref;
    this.versions = versions;
    this.fills = fills;
    this.cancel = cancel;
    this.instructionQuantity = instructionQuantity;
  }

  /** The order {@code orderId}'s stored steps, sorted. */
  static OrderLineage read(CdmApiSupport support, String orderId) {
    CdmRef ref = new CdmRef(CdmRef.Kind.ORDER, orderId);
    List<Row> versions = new ArrayList<>();
    Map<String, Row[]> fillSteps = new LinkedHashMap<>(); // trade id -> [proposed, accepted]
    Row cancel = null;
    for (StoredCdm stored : support.steps(ref)) {
      Row row = new Row(stored, support.read(stored, WorkflowStep.class));
      String fillTradeId = ExecutionMapper.fillTradeId(row.step());
      if (fillTradeId != null) {
        Row[] pair = fillSteps.computeIfAbsent(fillTradeId, k -> new Row[2]);
        pair[row.step().getBusinessEvent() == null ? 0 : 1] = row;
      } else if (WorkflowMapper.isCancel(row.step())) {
        cancel = row;
      } else {
        versions.add(row);
      }
    }
    int instruction = versions.isEmpty() ? 0
        : WorkflowMapper.instructionQuantity(versions.get(0).step());
    return new OrderLineage(ref, List.copyOf(versions), numbered(fillSteps, instruction), cancel,
        instruction);
  }

  private static List<Fill> numbered(Map<String, Row[]> fillSteps, int instruction) {
    record Unnumbered(String tradeId, Row proposed, Row accepted, Instant at) {
    }
    List<Unnumbered> unnumbered = new ArrayList<>();
    fillSteps.forEach((tradeId, pair) -> {
      Row first = pair[1] != null ? pair[1] : pair[0];
      unnumbered.add(new Unnumbered(tradeId, pair[0], pair[1],
          CdmApiSupport.instant(first.step())));
    });
    unnumbered.sort(Comparator.comparing(Unnumbered::at,
            Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(Unnumbered::tradeId));
    List<Fill> fills = new ArrayList<>();
    int filledSoFar = 0;
    boolean reached = false;
    for (int i = 0; i < unnumbered.size(); i++) {
      Unnumbered u = unnumbered.get(i);
      int number = i + 1;
      PriceQuantity pq;
      int quantity;
      if (u.accepted() != null) {
        TradeState after = CdmApiSupport.afterState(u.accepted().step());
        pq = after == null ? null : CdmApiSupport.priceQuantity(after.getTrade());
        quantity = ExecutionMapper.executedQuantity(List.of(u.accepted().step()));
        filledSoFar += quantity;
      } else {
        var instructed = WorkflowMapper.executionInstruction(u.proposed().step());
        pq = instructed == null || instructed.getPriceQuantity() == null
            || instructed.getPriceQuantity().isEmpty() ? null
            : instructed.getPriceQuantity().get(0);
        quantity = WorkflowMapper.instructionQuantity(u.proposed().step());
      }
      String label = "Partial fill " + number;
      if (!reached && u.accepted() != null && instruction > 0 && filledSoFar >= instruction) {
        reached = true;
        label = number == 1 ? "Full fill" : "Final fill " + number;
      }
      fills.add(new Fill(number, label, u.tradeId(), u.proposed(), u.accepted(), u.at(),
          quantity, CdmApiSupport.price(pq), filledSoFar));
    }
    return List.copyOf(fills);
  }

  CdmRef ref() {
    return ref;
  }

  /** The order's proposal versions in write order: as submitted, then approved or rejected. */
  List<Row> versions() {
    return versions;
  }

  /** The fills in booking order. */
  List<Fill> fills() {
    return fills;
  }

  /** The Cancelled correction, or null. */
  Row cancel() {
    return cancel;
  }

  /**
   * The latest version of the order's own workflow: the cancel, else the latest proposal
   * version; null if none is stored. Never a fill's step.
   */
  StoredCdm latestVersion() {
    return cancel != null ? cancel.stored()
        : versions.isEmpty() ? null : versions.get(versions.size() - 1).stored();
  }

  boolean isEmpty() {
    return versions.isEmpty() && fills.isEmpty() && cancel == null;
  }

  /** The order's instructed quantity (its first proposal version's), 0 if none stored. */
  int instructionQuantity() {
    return instructionQuantity;
  }

  /** Shares executed by the fills' accepted steps. */
  int executedQuantity() {
    return fills.stream().filter(f -> f.accepted() != null).mapToInt(Fill::quantity).sum();
  }

  /** The order as submitted (its first proposal version), or null. */
  WorkflowStep proposal() {
    return versions.isEmpty() ? null : versions.get(0).step();
  }

  /** Whether the request was rejected (a rejected step among the versions). */
  boolean rejected() {
    return versions.stream().anyMatch(v -> "rejected".equals(CdmApiSupport.stepType(v.step())));
  }

  /**
   * The venue's latest decision on the proposal, with the version carrying it: an approval
   * ({@code approved} true) or, for a rejected request, the refusal ({@code approved} false);
   * null if the venue has not answered.
   */
  Approval approval() {
    for (int i = versions.size() - 1; i >= 0; i--) {
      Row row = versions.get(i);
      List<? extends WorkflowStepApproval> approvals = row.step().getApproval();
      if (approvals != null && !approvals.isEmpty()) {
        return new Approval(approvals.get(approvals.size() - 1), row);
      }
    }
    return null;
  }

  record Approval(WorkflowStepApproval approval, Row carriedBy) {
  }

  /** The fill whose accepted step has store key {@code acceptedStoreKey}, or null. */
  Fill fillAcceptedAs(String acceptedStoreKey) {
    return fills.stream()
        .filter(f -> f.accepted() != null
            && f.accepted().stored().globalKey().equals(acceptedStoreKey))
        .findFirst().orElse(null);
  }

  /**
   * What produced a trade state: the fill of an order, or a direct trade's workflow
   * ({@code fill} null), with its proposed and accepted steps.
   */
  record Producer(ProducedBy producedBy, String kind, String orderId, Fill fill, Row proposed,
      Row accepted) {
  }

  /**
   * The producer of {@code tradeState} (its stored "produced by", checked against the store:
   * the accepted step must be stored under the producer's ref), or null if it has none or the
   * step is not found.
   */
  static Producer producerOf(CdmApiSupport support, StoredCdm tradeState) {
    ProducedBy producedBy = tradeState == null ? null : tradeState.producedBy();
    if (producedBy == null) {
      return null;
    }
    CdmRef ref = producedBy.ref();
    if (ref.kind() == CdmRef.Kind.ORDER) {
      Fill fill = read(support, ref.key()).fillAcceptedAs(producedBy.storeGlobalKey());
      return fill == null ? null
          : new Producer(producedBy, "fill", ref.key(), fill, fill.proposed(), fill.accepted());
    }
    if (ref.kind() == CdmRef.Kind.DIRECT) {
      Map<String, StoredCdm> byKey = new LinkedHashMap<>();
      support.steps(ref).forEach(s -> byKey.put(s.globalKey(), s));
      StoredCdm acceptedRow = byKey.get(producedBy.storeGlobalKey());
      if (acceptedRow == null) {
        return null;
      }
      StoredCdm proposedRow = acceptedRow.previousGlobalKey() == null ? null
          : byKey.get(acceptedRow.previousGlobalKey());
      Row accepted = new Row(acceptedRow, support.read(acceptedRow, WorkflowStep.class));
      Row proposed = proposedRow == null ? null
          : new Row(proposedRow, support.read(proposedRow, WorkflowStep.class));
      return new Producer(producedBy, "direct", null, null, proposed, accepted);
    }
    return null;
  }

  /** Where to read the producer's workflow: the order lineage, or the trade lineage. */
  static String lineagePath(Producer producer, String tradeId) {
    return "fill".equals(producer.kind()) ? "/cdm/orders/" + producer.orderId() + "/lineage"
        : "/cdm/trades/" + tradeId + "/lineage";
  }
}
