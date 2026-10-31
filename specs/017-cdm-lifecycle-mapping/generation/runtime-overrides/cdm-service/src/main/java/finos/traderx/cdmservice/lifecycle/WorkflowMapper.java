package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.CdmLifecycle.ORDER_ID_SCHEME;
import static finos.traderx.cdmservice.lifecycle.CdmLifecycle.REJECTION_ID_SCHEME;
import static finos.traderx.cdmservice.lifecycle.CdmLifecycle.TRADE_ID_SCHEME;

import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.AssetTypeEnum;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.asset.common.SecurityTypeEnum;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.Party;
import cdm.event.common.ActionEnum;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.Instruction;
import cdm.event.common.PrimitiveInstruction;
import cdm.event.workflow.EventInstruction;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowState;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.WorkflowStepApproval;
import cdm.event.workflow.functions.Create_ProposedWorkflowStep;
import cdm.event.workflow.functions.Create_RejectedWorkflowStep;
import com.google.inject.Injector;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.party.PartyMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the TraderX order lifecycle to CDM workflow steps. Pure: the output depends only on the
 * inputs and the reference data, every timestamp comes from the input, and every step carries
 * CDM global keys computed from its content.
 *
 * <p>The order's chain holds only versions of its proposal (CDM 7.8.0's workflow conditions:
 * a proposal may be followed only by a correction, an acceptance or a rejection, and nothing
 * executes when the matcher takes an order onto the book). Each fill is its own two-step
 * workflow ({@link ExecutionMapper#fill}).
 *
 * <ul>
 *   <li><b>Order submitted</b>: a proposed step ({@code Create_ProposedWorkflowStep}, action
 *       {@code New}) whose proposed event holds one instruction with an
 *       {@link ExecutionInstruction}: the cash-equity product, the order quantity at the limit
 *       price, the account ({@code Party1}) against the venue ({@code Party2}), execution type
 *       {@code OnVenue}.</li>
 *   <li><b>Order accepted by the matcher ({@code NEW})</b>: {@link #approved}, the same proposal
 *       carrying the venue's {@code WorkflowStepApproval} ({@code approved} true).</li>
 *   <li><b>Order rejected</b>: a proposal of the request as received and a rejected step
 *       ({@code Create_RejectedWorkflowStep}) with the reason as the workflow state comment and
 *       the venue's approval {@code approved} false.</li>
 *   <li><b>Cancel ({@code CANCELED})</b>: {@link #cancelCorrection}, a correction of the latest
 *       proposal version ({@code Create_ProposedWorkflowStep}, action {@code Correct}) with status
 *       {@code Cancelled}; the fills, which are their own workflows, stand.</li>
 * </ul>
 */
public final class WorkflowMapper {

  /** Start of a cancel's comment; {@link #canceledQuantity} reads the number after it. */
  private static final String CANCEL_COMMENT_PREFIX = "Unfilled remainder of ";
  private static final Pattern CANCEL_COMMENT = Pattern.compile(
      "^" + Pattern.quote(CANCEL_COMMENT_PREFIX) + "(\\d+) shares cancelled; \\d+ executed");

  private final LifecycleReferenceData referenceData;
  private final CdmProducts products;
  private final Create_ProposedWorkflowStep createProposed;
  private final Create_RejectedWorkflowStep createRejected;

  public WorkflowMapper(Injector cdmInjector, LifecycleReferenceData referenceData) {
    this.referenceData = referenceData;
    this.products = new CdmProducts(cdmInjector);
    this.createProposed = cdmInjector.getInstance(Create_ProposedWorkflowStep.class);
    this.createRejected = cdmInjector.getInstance(Create_RejectedWorkflowStep.class);
  }

  /** The proposed step of a submitted order: instruction at the limit price. */
  public WorkflowStep proposed(OrderEvent order) {
    return proposedStep(orderInstruction(order, order.quantity()), order.updatedAt(),
        List.of(CdmLifecycle.identifier(order.orderId(), ORDER_ID_SCHEME, 1)), order.orderId());
  }

  /**
   * The matcher's acceptance of an order ({@code NEW}): the order's proposal carrying the venue's
   * approval, {@code WorkflowStepApproval} {@code approved} true, party the venue, timestamp
   * {@code at}. It is the same proposal (same instruction, identifiers, timestamps, action
   * {@code New}, no previous step), built again by {@code Create_ProposedWorkflowStep} with the
   * approval as its {@code approval} input, and stored as the proposal's next version. Approval
   * is not acceptance: CDM accepts a step by giving it a business event, and taking an order
   * onto the book executes nothing. Nor is it a {@code Correct}: nothing in the instruction is
   * corrected.
   *
   * @param proposed the order's proposal as submitted ({@link #proposed}), without approval;
   *                 not a fill's proposal (which also carries the order id) nor a rejected
   *                 request's
   */
  public WorkflowStep approved(WorkflowStep proposed, Instant at) {
    if (proposed == null || proposed.getProposedEvent() == null
        || proposed.getAction() != ActionEnum.NEW || proposed.getPreviousWorkflowStep() != null
        || orderId(proposed) == null || ExecutionMapper.fillTradeId(proposed) != null
        || Boolean.TRUE.equals(proposed.getRejected())
        || (proposed.getApproval() != null && !proposed.getApproval().isEmpty())) {
      throw new IllegalArgumentException("the order's proposal as submitted is required");
    }
    Objects.requireNonNull(at, "at");
    WorkflowStepApproval approval = WorkflowStepApproval.builder()
        .setApproved(true)
        .setParty(CdmLifecycle.partyReference(referenceData.venue()))
        .setTimestamp(CdmLifecycle.timestamp(at,
            EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
        .build();
    WorkflowStep step = createProposed.evaluate(proposed.getMessageInformation(),
        List.copyOf(proposed.getTimestamp()), List.copyOf(proposed.getEventIdentifier()),
        List.of(), List.of(), null, ActionEnum.NEW, proposed.getProposedEvent(),
        List.of(approval));
    return CdmLifecycle.keyed(step.toBuilder()
        .setMeta(MetaFields.builder().setExternalKey(eventKey(proposed) + ":approved"))
        .build());
  }

  /**
   * The cancel of an order's unfilled remainder ({@code CANCELED}): a correction of the order's
   * latest proposal version, {@code Create_ProposedWorkflowStep} with action {@code Correct}
   * (CDM's condition {@code CorrectAction}: after a proposal with action New or Correct, the
   * action can only be Correct) and the same instruction, with {@code workflowState}
   * {@code Cancelled} and a comment giving the cancelled remainder and the executed quantity.
   * The quantity is not reduced (it would be zero for an unfilled order); the fills, which are
   * their own workflows, stand. The event identifier is the proposal's, one version up (CDM:
   * a correction's identifier has "an associated version greater than 1"). No business event:
   * nothing executes.
   *
   * @param latestProposal    the order's latest proposal version (approved or as submitted);
   *                          not a fill's step (which also carries the order id)
   * @param canceledQuantity  the unfilled remainder the matcher cancelled
   * @param executedQuantity  the shares executed by the order's fills
   */
  public WorkflowStep cancelCorrection(WorkflowStep latestProposal, int canceledQuantity,
      int executedQuantity, Instant at) {
    String orderId = orderId(latestProposal);
    if (latestProposal == null || latestProposal.getProposedEvent() == null || orderId == null
        || Boolean.TRUE.equals(latestProposal.getRejected())
        || ExecutionMapper.fillTradeId(latestProposal) != null) {
      throw new IllegalArgumentException("the order's latest proposal is required");
    }
    if (canceledQuantity < 0 || executedQuantity < 0) {
      throw new IllegalArgumentException("cancelled and executed quantities must not be"
          + " negative");
    }
    Objects.requireNonNull(at, "at");
    WorkflowStep step = createProposed.evaluate(null,
        List.of(CdmLifecycle.timestamp(at,
            EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME)),
        nextVersion(latestProposal.getEventIdentifier()), List.of(), List.of(), latestProposal,
        ActionEnum.CORRECT, latestProposal.getProposedEvent(), List.of());
    return CdmLifecycle.keyed(step.toBuilder()
        .setWorkflowState(WorkflowState.builder()
            .setWorkflowStatus(WorkflowStatusEnum.CANCELLED)
            .setComment(CANCEL_COMMENT_PREFIX + canceledQuantity + " shares cancelled; "
                + executedQuantity + " executed by fills, which stand"))
        .setMeta(MetaFields.builder().setExternalKey(orderId + ":cancel"))
        .build());
  }

  /**
   * The cancelled remainder (the matcher's {@code canceledQuantity}) a cancel
   * ({@link #cancelCorrection}) records, read back from its comment, or {@code null} if
   * {@code step} is not a cancel or its comment does not have the wording
   * {@link #cancelCorrection} writes. CDM's correction carries the original instruction
   * unchanged, so the comment is the only place the cancel states the quantity.
   */
  public static Integer canceledQuantity(WorkflowStep step) {
    if (!isCancel(step) || step.getWorkflowState().getComment() == null) {
      return null;
    }
    Matcher matcher = CANCEL_COMMENT.matcher(step.getWorkflowState().getComment());
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.valueOf(matcher.group(1));
    } catch (NumberFormatException e) {
      return null; // more digits than an int holds
    }
  }

  /** {@code identifiers} with every assigned identifier's version raised by one. */
  private static List<Identifier> nextVersion(List<? extends Identifier> identifiers) {
    return identifiers.stream()
        .map(id -> {
          Identifier.IdentifierBuilder next = id.toBuilder();
          next.getAssignedIdentifier().forEach(assigned -> assigned.setVersion(
              (assigned.getVersion() == null ? 1 : assigned.getVersion()) + 1));
          return (Identifier) next.build();
        })
        .toList();
  }

  /** The order's execution instruction for {@code quantity} shares at its limit price. */
  private ExecutionInstruction orderInstruction(OrderEvent order, int quantity) {
    Objects.requireNonNull(order.orderId(), "orderId");
    Objects.requireNonNull(order.updatedAt(), "updatedAt");
    Side side = CdmLifecycle.requireSide(order.side());
    Security security = CdmLifecycle.requireSecurity(referenceData, order.security());
    Party account = CdmLifecycle.requireAccount(referenceData, order.accountId());
    return CdmLifecycle.executionInstruction(
        products.cashEquity(security, side), side, account, referenceData.venue(),
        CdmProducts.priceQuantity(order.limitPrice(), quantity, false),
        order.updatedAt(),
        List.of(CdmLifecycle.tradeIdentifier(order.orderId(), ORDER_ID_SCHEME)),
        ExecutionTypeEnum.ON_VENUE);
  }

  /**
   * The proposal a rejected request would have made: the request as received. Missing or
   * unknown values are left out (an unknown ticker is kept as an exchange-code-only security,
   * an unknown account as an identifier-only party), so such a proposal may not validate; its
   * validation result records that.
   */
  public WorkflowStep rejectedProposal(RejectedOrder rejected) {
    requireRejection(rejected);
    Side side = Side.parse(rejected.side());
    ExecutionInstruction instruction = CdmLifecycle.executionInstruction(
        products.cashEquity(requestedSecurity(rejected.security()), side), side,
        requestedAccount(rejected.accountId()), referenceData.venue(),
        CdmProducts.priceQuantity(rejected.limitPrice(), rejected.quantity(), false),
        rejected.rejectedAt(),
        List.of(CdmLifecycle.tradeIdentifier(rejected.rejectionId(), REJECTION_ID_SCHEME)),
        ExecutionTypeEnum.ON_VENUE);
    return proposedStep(instruction, rejected.rejectedAt(),
        List.of(CdmLifecycle.identifier(rejected.rejectionId(), REJECTION_ID_SCHEME, 1)),
        rejected.rejectionId());
  }

  /**
   * The rejection of {@link #rejectedProposal}: {@code Create_RejectedWorkflowStep}, with the
   * reason as {@code workflowState.comment} (CDM: "e.g. to specify why a transaction event was
   * rejected by a party") and status {@code Rejected}. The step repeats the rejected proposal's
   * {@code proposedEvent}: CDM 7.8.0's {@code WorkflowStep} choice rule requires one of
   * {@code businessEvent}/{@code proposedEvent}/{@code counterpartyPositionBusinessEvent}, which
   * the bare output of {@code Create_RejectedWorkflowStep} lacks, and the condition
   * {@code WorkflowStepStatus} allows a proposed event on a rejected step.
   *
   * <p>The step also carries the venue's refusal as a {@code WorkflowStepApproval}
   * ({@code approved} false, party the venue, the reason as {@code rejectedReason}, timestamp
   * the rejection time). {@code Create_RejectedWorkflowStep} has no approval input, so it is
   * added to the function's output; {@code WorkflowStep.approval} is allowed on any step.
   */
  public WorkflowStep rejected(RejectedOrder rejected) {
    WorkflowStep proposal = rejectedProposal(rejected);
    WorkflowStep step = createRejected.evaluate(null,
        List.of(CdmLifecycle.timestamp(rejected.rejectedAt(),
            EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME)),
        proposal.getEventIdentifier(), proposal);
    WorkflowState.WorkflowStateBuilder state = WorkflowState.builder()
        .setWorkflowStatus(WorkflowStatusEnum.REJECTED);
    WorkflowStepApproval.WorkflowStepApprovalBuilder refusal = WorkflowStepApproval.builder()
        .setApproved(false)
        .setParty(CdmLifecycle.partyReference(referenceData.venue()))
        .setTimestamp(CdmLifecycle.timestamp(rejected.rejectedAt(),
            EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME));
    if (rejected.reason() != null) {
      state.setComment(rejected.reason());
      refusal.setRejectedReason(rejected.reason());
    }
    return CdmLifecycle.keyed(step.toBuilder()
        .setProposedEvent(proposal.getProposedEvent())
        .addApproval(refusal.build())
        .setWorkflowState(state)
        .setMeta(MetaFields.builder().setExternalKey(rejected.rejectionId() + ":rejected"))
        .build());
  }

  /** The execution instruction proposed by a proposed step. */
  public static ExecutionInstruction executionInstruction(WorkflowStep proposed) {
    EventInstruction event = proposed == null ? null : proposed.getProposedEvent();
    if (event == null || event.getInstruction() == null || event.getInstruction().isEmpty()
        || event.getInstruction().get(0).getPrimitiveInstruction() == null) {
      return null;
    }
    return event.getInstruction().get(0).getPrimitiveInstruction().getExecution();
  }

  /** The quantity (shares) the proposed step instructs, 0 if none. */
  public static int instructionQuantity(WorkflowStep proposed) {
    ExecutionInstruction instruction = executionInstruction(proposed);
    if (instruction == null || instruction.getPriceQuantity() == null) {
      return 0;
    }
    return instruction.getPriceQuantity().stream()
        .filter(pq -> pq.getQuantity() != null && pq.getQuantity().getValue() != null)
        .mapToInt(pq -> pq.getQuantity().getValue().getValue().intValueExact())
        .sum();
  }

  /**
   * Whether {@code step} is an order's cancel ({@link #cancelCorrection}): a correction with
   * workflow status {@code Cancelled}.
   */
  public static boolean isCancel(WorkflowStep step) {
    return step != null && step.getAction() == ActionEnum.CORRECT
        && step.getWorkflowState() != null
        && step.getWorkflowState().getWorkflowStatus() == WorkflowStatusEnum.CANCELLED;
  }

  /** Whether {@code step} carries the venue's approval ({@code approved} true). */
  public static boolean isApproved(WorkflowStep step) {
    return step != null && step.getApproval() != null && step.getApproval().stream()
        .anyMatch(a -> Boolean.TRUE.equals(a.getApproved()));
  }

  /** The TraderX order id of a step of the order path, or {@code null} (direct trade). */
  public static String orderId(WorkflowStep step) {
    return step == null ? null
        : CdmLifecycle.identifierValue(step.getEventIdentifier(), ORDER_ID_SCHEME);
  }

  /** The TraderX key a step is identified by: the order, trade or rejection id. */
  static String eventKey(WorkflowStep step) {
    List<? extends Identifier> ids = step.getEventIdentifier();
    for (String scheme : List.of(ORDER_ID_SCHEME, TRADE_ID_SCHEME, REJECTION_ID_SCHEME)) {
      String value = CdmLifecycle.identifierValue(ids, scheme);
      if (value != null) {
        return value;
      }
    }
    throw new IllegalArgumentException("workflow step has no TraderX event identifier");
  }

  /**
   * A new proposal ({@code Create_ProposedWorkflowStep}, action {@code New}, no previous step) of
   * {@code instruction}, created at {@code at}, external key {@code key:proposed}.
   */
  WorkflowStep proposedStep(ExecutionInstruction instruction, Instant at,
      List<Identifier> eventIdentifiers, String key) {
    EventInstruction proposedEvent = eventInstruction(instruction, at);
    List<EventTimestamp> timestamps = List.of(
        CdmLifecycle.timestamp(at, EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME));
    WorkflowStep step = createProposed.evaluate(null, timestamps, eventIdentifiers,
        List.of(), List.of(), null, ActionEnum.NEW, proposedEvent, List.of());
    return CdmLifecycle.keyed(step.toBuilder()
        .setMeta(MetaFields.builder().setExternalKey(key + ":proposed"))
        .build());
  }

  private static EventInstruction eventInstruction(ExecutionInstruction instruction,
      Instant at) {
    Date eventDate = CdmLifecycle.date(at);
    return EventInstruction.builder()
        .setEventDate(eventDate)
        .setEffectiveDate(eventDate)
        .addInstruction(Instruction.builder()
            .setPrimitiveInstruction(PrimitiveInstruction.builder().setExecution(instruction)))
        .build();
  }

  private static void requireRejection(RejectedOrder rejected) {
    if (rejected == null || rejected.rejectionId() == null || rejected.rejectedAt() == null) {
      throw new IllegalArgumentException("rejectionId and rejectedAt are required");
    }
  }

  private Security requestedSecurity(String ticker) {
    if (ticker != null) {
      var known = referenceData.security(ticker);
      if (known.isPresent()) {
        return known.get();
      }
    }
    Security.SecurityBuilder security = Security.builder()
        .setAssetType(AssetTypeEnum.SECURITY)
        .setSecurityType(SecurityTypeEnum.EQUITY);
    if (ticker != null) {
      security.addIdentifier(AssetIdentifier.builder()
          .setIdentifier(FieldWithMetaString.builder().setValue(ticker))
          .setIdentifierType(AssetIdTypeEnum.EXCHANGE_CODE));
    }
    return security.build();
  }

  private Party requestedAccount(Integer accountId) {
    if (accountId == null) {
      return Party.builder().setMeta(MetaFields.builder().setExternalKey("unknown")).build();
    }
    return referenceData.account(accountId).orElse(PartyMapper.toCdmParty(accountId, null));
  }
}
