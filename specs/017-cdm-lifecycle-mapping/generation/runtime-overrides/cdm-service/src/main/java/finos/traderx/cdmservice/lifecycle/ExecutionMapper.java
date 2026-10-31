package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.CdmLifecycle.ORDER_ID_SCHEME;
import static finos.traderx.cdmservice.lifecycle.CdmLifecycle.TRADE_ID_SCHEME;

import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.event.common.BusinessEvent;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.TradeState;
import cdm.event.position.PositionStatusEnum;
import cdm.event.common.functions.Create_BusinessEvent;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowState;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.functions.Create_AcceptedWorkflowStep;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.TradeLot;
import com.google.inject.Injector;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import finos.traderx.cdmservice.validation.CdmValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps booked TraderX trades to CDM execution workflow steps, business events and trade states.
 * Pure: the output depends only on the inputs and the reference data.
 *
 * <p>Every booked trade is its own two-step CDM workflow ({@link FillWorkflow}): a proposal
 * ({@code Create_ProposedWorkflowStep}, action {@code New}, no previous step) of the trade as an
 * {@link ExecutionInstruction} (quantity and price as booked), then its acceptance
 * ({@code Create_AcceptedWorkflowStep}, previous step the proposal) carrying the execution
 * {@link BusinessEvent} that {@code Create_BusinessEvent} builds from that instruction, whose
 * after state is the trade's {@link TradeState}. A fill of an order ({@link #fill}) is a child
 * instruction of the order: it links to the order by the order id in its event identifiers (CDM
 * has no parent link between instructions; {@code previousWorkflowStep} cannot carry one under
 * the workflow conditions), execution type {@code OnVenue}. A direct trade ({@link #direct}) has
 * execution type {@code Electronic}. cdm-service reconstructs the proposal when the trade
 * arrives: the matcher publishes only booked trades.
 *
 * <p>The execution event has one instruction whose only primitive is an
 * {@link ExecutionInstruction} (so {@code Qualify_Execution} is true) and no before state (CDM's
 * {@code Instruction} condition {@code NewTrade}: "There must be no before trade state if the
 * primitive instructions contain an execution"). Its after {@link TradeState} is one trade per
 * booked trade, identified by the TraderX trade id. An order's executed quantity is the sum over
 * its fills' accepted steps.
 */
public final class ExecutionMapper {

  /**
   * One booked trade as its own CDM workflow: the {@code proposed} step (the trade as an
   * instruction) and the {@code accepted} step carrying the execution event, plus any way a fill
   * disagrees with its order's instruction (side, security, account; empty for a direct trade).
   * A mismatch never stops the mapping.
   */
  public record FillWorkflow(WorkflowStep proposed, WorkflowStep accepted,
      List<String> mismatches) {

    public FillWorkflow {
      mismatches = List.copyOf(mismatches);
    }

    /** The execution event of the accepted step. */
    public BusinessEvent event() {
      return accepted.getBusinessEvent();
    }

    /** The trade state the execution produced. */
    public TradeState after() {
      return afterState(event());
    }

    /** Validation of the proposed step, with each mismatch added as an {@code INVALID} message. */
    public CdmValidationResult validateProposed(CdmValidator validator) {
      return withMismatches(validator.validate(proposed), null);
    }

    /**
     * Validation of the accepted step and {@code Qualify_Execution} of its event
     * ({@code NOT_QUALIFIED} if the event is not an execution), with each mismatch added as an
     * {@code INVALID} message.
     */
    public CdmValidationResult validateAccepted(CdmValidator validator) {
      return withMismatches(validator.validate(accepted), validator.validateExecution(event()));
    }

    private CdmValidationResult withMismatches(CdmValidationResult step,
        CdmValidationResult execution) {
      List<String> messages = new ArrayList<>(execution == null ? List.of()
          : execution.messages());
      step.messages().stream().filter(m -> !messages.contains(m)).forEach(messages::add);
      messages.addAll(mismatches);
      Status status = execution != null && execution.status() == Status.NOT_QUALIFIED
          ? Status.NOT_QUALIFIED
          : messages.isEmpty() ? Status.VALID : Status.INVALID;
      return new CdmValidationResult(status, messages);
    }
  }

  /**
   * Scheme of the TraderX trade id on a fill's event identifiers, next to the order id (Ruling
   * R42): stable whatever order fills arrive in. The fill number shown to users is computed when
   * read (booking order: trade time, then trade id), never stored.
   */
  public static final String FILL_ID_SCHEME = "https://traderx.finos.org/fill-id";

  /** Prefix of every mismatch message of a {@link FillWorkflow}. */
  public static final String MISMATCH_PREFIX = "fill does not match the order: ";

  private final LifecycleReferenceData referenceData;
  private final CdmProducts products;
  private final WorkflowMapper workflow;
  private final Create_BusinessEvent createBusinessEvent;
  private final Create_AcceptedWorkflowStep createAccepted;

  public ExecutionMapper(Injector cdmInjector, LifecycleReferenceData referenceData) {
    this.referenceData = referenceData;
    this.products = new CdmProducts(cdmInjector);
    this.workflow = new WorkflowMapper(cdmInjector, referenceData);
    this.createBusinessEvent = cdmInjector.getInstance(Create_BusinessEvent.class);
    this.createAccepted = cdmInjector.getInstance(Create_AcceptedWorkflowStep.class);
  }

  /**
   * The fill {@code trade} of the order of {@code orderProposal}, as its own workflow: the fill
   * proposed (a child instruction for the fill quantity at the fill price, execution type
   * {@code OnVenue}; event identifiers the order id and the trade id under
   * {@link #FILL_ID_SCHEME}) and the fill accepted with its execution event.
   *
   * @param orderProposal any proposal version of the trade's order; the trade must belong to
   *                      it, else this throws. Side, security and account that differ from it
   *                      are mapped as booked and reported in {@link FillWorkflow#mismatches()}.
   */
  public FillWorkflow fill(WorkflowStep orderProposal, BookedTrade trade) {
    requireTrade(trade);
    if (trade.orderId() == null) {
      throw new IllegalArgumentException("trade " + trade.id() + " fills no order; it is a"
          + " direct trade");
    }
    ExecutionInstruction ordered = requireProposal(orderProposal, trade);
    WorkflowStep proposed = workflow.proposedStep(
        tradeInstruction(trade, ExecutionTypeEnum.ON_VENUE), trade.created(),
        List.of(CdmLifecycle.identifier(trade.orderId(), ORDER_ID_SCHEME, 1),
            CdmLifecycle.identifier(trade.id(), FILL_ID_SCHEME, 1)),
        trade.id());
    return new FillWorkflow(proposed, accept(proposed, trade), mismatches(ordered, trade));
  }

  /**
   * A direct trade (trade ticket, no order) as its own workflow: the trade proposed as an
   * instruction (execution type {@code Electronic}, event identifier the trade id) and accepted
   * with its execution event.
   */
  public FillWorkflow direct(BookedTrade trade) {
    requireTrade(trade);
    if (trade.orderId() != null) {
      throw new IllegalArgumentException("trade " + trade.id() + " fills order "
          + trade.orderId() + "; it is not a direct trade");
    }
    WorkflowStep proposed = workflow.proposedStep(
        tradeInstruction(trade, ExecutionTypeEnum.ELECTRONIC), trade.created(),
        List.of(CdmLifecycle.identifier(trade.id(), TRADE_ID_SCHEME, 1)), trade.id());
    return new FillWorkflow(proposed, accept(proposed, trade), List.of());
  }

  /**
   * The trade as booked, as an execution instruction: its quantity at its price (with the gross
   * consideration), the account against the venue, identified by the trade id.
   */
  private ExecutionInstruction tradeInstruction(BookedTrade trade,
      ExecutionTypeEnum executionType) {
    Side side = CdmLifecycle.requireSide(trade.side());
    return CdmLifecycle.executionInstruction(
        products.cashEquity(CdmLifecycle.requireSecurity(referenceData, trade.security()), side),
        side, CdmLifecycle.requireAccount(referenceData, trade.accountId()),
        referenceData.venue(), CdmProducts.priceQuantity(trade.price(), trade.quantity(), true),
        trade.created(), List.of(CdmLifecycle.tradeIdentifier(trade.id(), TRADE_ID_SCHEME)),
        executionType);
  }

  /**
   * The acceptance of the trade's proposal: {@code Create_AcceptedWorkflowStep} referencing it,
   * with the proposal's identifiers and action ({@code New}, as CDM's
   * {@code Create_AcceptedWorkflowStepFromInstruction} copies it), the execution time, status
   * {@code Accepted}, and the execution event {@code Create_BusinessEvent} builds from the
   * proposal's instruction (no before state: CDM's {@code Instruction} condition
   * {@code NewTrade}), its after state's position state from the TraderX trade state (Rulings
   * R35, R37).
   */
  private WorkflowStep accept(WorkflowStep proposed, BookedTrade trade) {
    BusinessEvent created = createBusinessEvent.evaluate(
        List.copyOf(proposed.getProposedEvent().getInstruction()), null,
        proposed.getProposedEvent().getEventDate(),
        proposed.getProposedEvent().getEffectiveDate());
    BusinessEvent.BusinessEventBuilder withState = created.toBuilder();
    withState.getAfter().get(0).getOrCreateState()
        .setPositionState(positionState(trade.state()));
    WorkflowStep step = createAccepted.evaluate(null,
        List.of(CdmLifecycle.timestamp(trade.created(),
            EventTimestampQualificationEnum.EXECUTION_DATE_TIME)),
        List.copyOf(proposed.getEventIdentifier()), List.of(), List.of(), proposed,
        CdmLifecycle.keyed(withState.build()));
    return CdmLifecycle.keyed(step.toBuilder()
        .setAction(proposed.getAction())
        .setWorkflowState(WorkflowState.builder().setWorkflowStatus(WorkflowStatusEnum.ACCEPTED))
        .setMeta(MetaFields.builder().setExternalKey(trade.id() + ":accepted"))
        .build());
  }

  private static void requireTrade(BookedTrade trade) {
    Objects.requireNonNull(trade, "trade");
    Objects.requireNonNull(trade.id(), "trade.id");
    Objects.requireNonNull(trade.created(), "trade.created");
    Objects.requireNonNull(trade.price(), "trade.price");
  }

  /**
   * CDM position state of a TraderX trade state (Rulings R35, R37). TraderX has no settlement
   * process: trade-processor moves a trade New, Processing, Settled in one transaction with no
   * transfer or settlement event, so CDM records what actually happened, the execution:
   * {@code New}, {@code Processing}, {@code Settled} and {@code null} (the source did not say)
   * are {@code EXECUTED}; {@code Cancelled} is {@code CANCELLED}. TraderX's "Settled" label is
   * therefore not kept through CDM. Any other value throws (the caller records a failure).
   */
  public static PositionStatusEnum positionState(String traderxState) {
    if (traderxState == null || traderxState.isBlank()) {
      return PositionStatusEnum.EXECUTED;
    }
    return switch (traderxState.trim()) {
      case "New", "Processing", "Settled" -> PositionStatusEnum.EXECUTED;
      case "Cancelled" -> PositionStatusEnum.CANCELLED;
      default -> throw new IllegalArgumentException("unknown TraderX trade state: "
          + traderxState);
    };
  }

  /** The trade state an execution event produced. */
  public static TradeState afterState(BusinessEvent event) {
    if (event == null || event.getAfter() == null || event.getAfter().size() != 1) {
      throw new IllegalArgumentException("an execution event has exactly one after state");
    }
    return event.getAfter().get(0);
  }

  /**
   * Shares executed: the sum of the after states' trade lot quantities of the given execution
   * {@link WorkflowStep}s or {@link BusinessEvent}s (steps without a business event, such as
   * proposals, contribute nothing).
   */
  public static int executedQuantity(List<?> executions) {
    int total = 0;
    for (Object item : executions) {
      BusinessEvent event = item instanceof WorkflowStep step ? step.getBusinessEvent()
          : item instanceof BusinessEvent e ? e : null;
      if (event == null || event.getAfter() == null) {
        continue;
      }
      for (TradeState state : event.getAfter()) {
        for (TradeLot lot : state.getTrade().getTradeLot()) {
          for (PriceQuantity pq : lot.getPriceQuantity()) {
            if (pq.getQuantity() != null && pq.getQuantity().getValue() != null) {
              total += pq.getQuantity().getValue().getValue().intValueExact();
            }
          }
        }
      }
    }
    return total;
  }

  /** The TraderX trade id of a trade state. */
  public static String tradeId(TradeState state) {
    return CdmLifecycle.identifierValue(state.getTrade().getTradeIdentifier(), TRADE_ID_SCHEME);
  }

  /**
   * The TraderX trade id of a fill's step (its identifier under {@link #FILL_ID_SCHEME}), or
   * {@code null} for any other step (an order's proposal versions, a direct trade).
   */
  public static String fillTradeId(WorkflowStep step) {
    return step == null ? null
        : CdmLifecycle.identifierValue(step.getEventIdentifier(), FILL_ID_SCHEME);
  }

  /**
   * The instruction of {@code proposed}, which must be a proposal version of the trade's order
   * (not a fill's step, which also carries the order id).
   */
  private static ExecutionInstruction requireProposal(WorkflowStep proposed, BookedTrade trade) {
    ExecutionInstruction ordered = WorkflowMapper.executionInstruction(proposed);
    if (ordered == null || fillTradeId(proposed) != null) {
      throw new IllegalArgumentException("trade " + trade.id()
          + " needs a proposal version of its order");
    }
    String expected = trade.orderId() != null ? trade.orderId() : trade.id();
    String actual = WorkflowMapper.eventKey(proposed);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("trade " + trade.id() + " of "
          + (trade.orderId() != null ? "order " + trade.orderId() : "no order")
          + " does not belong to the proposal of " + actual);
    }
    return ordered;
  }

  /** How {@code trade} differs from the order's instruction: side, security, account. */
  private static List<String> mismatches(ExecutionInstruction ordered, BookedTrade trade) {
    List<String> mismatches = new ArrayList<>();
    String account = ordered.getCounterparty().stream()
        .filter(c -> c.getRole() == CounterpartyRoleEnum.PARTY_1)
        .map(Counterparty::getPartyReference)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
    if (!Integer.toString(trade.accountId()).equals(account)) {
      mismatches.add(MISMATCH_PREFIX + "account " + trade.accountId() + " but the order's is "
          + account);
    }
    String buyer = ordered.getPartyRoles().stream()
        .filter(r -> r.getRole() == PartyRoleEnum.BUYER)
        .map(PartyRole::getPartyReference)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
    Side orderedSide = buyer == null ? null : buyer.equals(account) ? Side.BUY : Side.SELL;
    Side tradeSide = Side.parse(trade.side());
    if (orderedSide != tradeSide) {
      mismatches.add(MISMATCH_PREFIX + "side " + tradeSide + " but the order's is "
          + orderedSide);
    }
    Security security = ordered.getProduct().getEconomicTerms().getPayout().get(0)
        .getSettlementPayout().getUnderlier().getObservable().getValue().getAsset()
        .getInstrument().getSecurity();
    String ticker = SecurityMapper.identifierValue(security, AssetIdTypeEnum.EXCHANGE_CODE);
    if (!Objects.equals(ticker, trade.security())) {
      mismatches.add(MISMATCH_PREFIX + "security " + trade.security() + " but the order's is "
          + ticker);
    }
    return mismatches;
  }
}
