package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CANCEL_AT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.EXECUTION;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_ID;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.WORKFLOW;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.bd;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.rejectedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cdm.base.math.FinancialUnitEnum;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.event.common.ActionEnum;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.WorkflowStepApproval;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.product.template.SettlementPayout;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkflowMapperTest {

  @Test
  void proposedStepCarriesInstructionWithBuyerAccountSellerVenue() {
    WorkflowStep proposed = WORKFLOW.proposed(newOrder());

    assertThat(proposed.getAction()).isEqualTo(ActionEnum.NEW);
    assertThat(proposed.getBusinessEvent()).isNull();
    ExecutionInstruction instruction = WorkflowMapper.executionInstruction(proposed);

    // Account = Party1, venue = Party2.
    Map<CounterpartyRoleEnum, String> counterparties = instruction.getCounterparty().stream()
        .collect(Collectors.toMap(c -> c.getRole(),
            c -> c.getPartyReference().getExternalReference()));
    assertThat(counterparties).containsExactlyInAnyOrderEntriesOf(Map.of(
        CounterpartyRoleEnum.PARTY_1, Integer.toString(ACCOUNT),
        CounterpartyRoleEnum.PARTY_2, PartyMapper.VENUE_KEY));
    // A buy: the account is Buyer, the venue Seller.
    Map<PartyRoleEnum, String> roles = instruction.getPartyRoles().stream()
        .collect(Collectors.toMap(r -> r.getRole(),
            r -> r.getPartyReference().getExternalReference()));
    assertThat(roles).containsEntry(PartyRoleEnum.BUYER, Integer.toString(ACCOUNT))
        .containsEntry(PartyRoleEnum.SELLER, PartyMapper.VENUE_KEY);
    // The seller (venue, Party2) delivers the shares to the buyer (Party1).
    SettlementPayout payout = instruction.getProduct().getEconomicTerms().getPayout().get(0)
        .getSettlementPayout();
    assertThat(payout.getPayerReceiver().getPayer()).isEqualTo(CounterpartyRoleEnum.PARTY_2);
    assertThat(payout.getPayerReceiver().getReceiver()).isEqualTo(CounterpartyRoleEnum.PARTY_1);
    assertThat(payout.getUnderlier().getObservable().getValue().getAsset().getInstrument()
        .getSecurity()).isEqualTo(LifecycleFixtures.IBM);

    // Limit price per share in USD, quantity in shares.
    PriceSchedule price = instruction.getPriceQuantity().get(0).getPrice().get(0).getValue();
    assertThat(price.getValue()).isEqualTo(bd("180.000"));
    assertThat(price.getPriceType()).isEqualTo(PriceTypeEnum.ASSET_PRICE);
    assertThat(price.getUnit().getCurrency().getValue()).isEqualTo("USD");
    assertThat(price.getPerUnitOf().getFinancialUnit()).isEqualTo(FinancialUnitEnum.SHARE);
    assertThat(instruction.getPriceQuantity().get(0).getQuantity().getValue().getValue())
        .isEqualTo(bd("1500"));
    // The order is an instruction to execute on the venue (the matching engine, Party2).
    assertThat(instruction.getExecutionDetails().getExecutionType())
        .isEqualTo(ExecutionTypeEnum.ON_VENUE);
    assertThat(WorkflowMapper.orderId(proposed)).isEqualTo(ORDER_ID);

    assertValid(VALIDATOR.validate(proposed));
  }

  @Test
  void sellMakesAccountSellerAndPayer() {
    OrderEvent sell = new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Sell", 100, 100, bd("181"),
        "NEW", LifecycleFixtures.ORDER_AT, null, null);

    ExecutionInstruction instruction = WorkflowMapper.executionInstruction(WORKFLOW.proposed(sell));

    Map<PartyRoleEnum, String> roles = instruction.getPartyRoles().stream()
        .collect(Collectors.toMap(r -> r.getRole(),
            r -> r.getPartyReference().getExternalReference()));
    assertThat(roles).containsEntry(PartyRoleEnum.SELLER, Integer.toString(ACCOUNT))
        .containsEntry(PartyRoleEnum.BUYER, PartyMapper.VENUE_KEY);
    SettlementPayout payout = instruction.getProduct().getEconomicTerms().getPayout().get(0)
        .getSettlementPayout();
    assertThat(payout.getPayerReceiver().getPayer()).isEqualTo(CounterpartyRoleEnum.PARTY_1);
    // Price scale is normalised to 3 decimals.
    assertThat(instruction.getPriceQuantity().get(0).getPrice().get(0).getValue().getValue())
        .isEqualTo(bd("181.000"));
  }

  @Test
  void approvedProposalCarriesVenueApprovalAndValidates() {
    WorkflowStep proposed = WORKFLOW.proposed(newOrder());

    WorkflowStep approved = WORKFLOW.approved(proposed, LifecycleFixtures.ORDER_AT);

    assertThat(approved.getApproval()).hasSize(1);
    WorkflowStepApproval approval = approved.getApproval().get(0);
    assertThat(approval.getApproved()).isTrue();
    assertThat(approval.getParty().getExternalReference()).isEqualTo(PartyMapper.VENUE_KEY);
    assertThat(approval.getRejectedReason()).isNull();
    assertThat(approval.getTimestamp().getDateTime().toInstant())
        .isEqualTo(LifecycleFixtures.ORDER_AT);
    // Approval is not acceptance: no business event, the same proposal and instruction.
    assertThat(approved.getBusinessEvent()).isNull();
    assertThat(approved.getProposedEvent()).isEqualTo(proposed.getProposedEvent());
    assertThat(approved.getAction()).isEqualTo(ActionEnum.NEW);
    assertThat(approved.getPreviousWorkflowStep()).isNull();
    assertThat(approved.getEventIdentifier()).isEqualTo(proposed.getEventIdentifier());
    assertThat(WorkflowMapper.orderId(approved)).isEqualTo(ORDER_ID);
    assertThat(approved.getMeta().getGlobalKey()).isNotEqualTo(proposed.getMeta().getGlobalKey());
    assertValid(VALIDATOR.validate(approved));
  }

  @Test
  void cancelWithNoFillsIsValidCorrection() {
    WorkflowStep approved = WORKFLOW.approved(WORKFLOW.proposed(newOrder()),
        LifecycleFixtures.ORDER_AT);

    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, 1500, 0, CANCEL_AT);

    assertThat(cancel.getAction()).isEqualTo(ActionEnum.CORRECT);
    assertThat(cancel.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(approved.getMeta().getGlobalKey());
    assertThat(cancel.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.CANCELLED);
    assertThat(cancel.getWorkflowState().getComment()).contains("1500").contains("0 executed");
    // The same instruction: the cancel does not reduce the quantity.
    assertThat(cancel.getProposedEvent()).isEqualTo(approved.getProposedEvent());
    assertThat(cancel.getBusinessEvent()).isNull();
    // A correction: same event, version 2 (CDM ActionEnum Correct: "version greater than 1").
    assertThat(cancel.getEventIdentifier().get(0).getAssignedIdentifier().get(0).getVersion())
        .isEqualTo(2);
    assertThat(cancel.getTimestamp().get(0).getDateTime().toInstant()).isEqualTo(CANCEL_AT);
    assertThat(WorkflowMapper.orderId(cancel)).isEqualTo(ORDER_ID);
    assertValid(VALIDATOR.validate(cancel));
  }

  @Test
  void cancelAfterFillsStatesBothQuantities() {
    WorkflowStep approved = WORKFLOW.approved(WORKFLOW.proposed(newOrder()),
        LifecycleFixtures.ORDER_AT);

    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, 750, 750, CANCEL_AT);

    assertThat(cancel.getWorkflowState().getComment())
        .isEqualTo("Unfilled remainder of 750 shares cancelled; 750 executed by fills, which"
            + " stand");
    assertValid(VALIDATOR.validate(cancel));
    assertThatThrownBy(() -> WORKFLOW.cancelCorrection(approved, -1, 0, CANCEL_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancelQuantitiesReadBackFromTheCancelComment() {
    WorkflowStep approved = WORKFLOW.approved(WORKFLOW.proposed(newOrder()),
        LifecycleFixtures.ORDER_AT);
    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, 700, 750, CANCEL_AT);

    assertThat(WorkflowMapper.canceledQuantity(cancel)).isEqualTo(700);
    // Defensive: anything else (not a cancel, no comment, another wording) reads as unknown.
    assertThat(WorkflowMapper.canceledQuantity(approved)).isNull();
    assertThat(WorkflowMapper.canceledQuantity(null)).isNull();
    WorkflowStep reworded = cancel.toBuilder().setWorkflowState(cancel.getWorkflowState()
        .toBuilder().setComment("cancelled 700")).build();
    assertThat(WorkflowMapper.canceledQuantity(reworded)).isNull();
    WorkflowStep noComment = cancel.toBuilder().setWorkflowState(cancel.getWorkflowState()
        .toBuilder().setComment(null)).build();
    assertThat(WorkflowMapper.canceledQuantity(noComment)).isNull();
  }

  @Test
  void rejectedStepIsCdmRejected() {
    WorkflowStep proposal = WORKFLOW.rejectedProposal(rejectedOrder());

    WorkflowStep rejected = WORKFLOW.rejected(rejectedOrder());

    assertThat(rejected.getRejected()).isTrue();
    assertThat(rejected.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(proposal.getMeta().getGlobalKey());
    assertThat(rejected.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.REJECTED);
    assertThat(rejected.getWorkflowState().getComment()).isEqualTo("invalid order payload");
    // The venue's refusal, as a CDM approval with approved = false.
    assertThat(rejected.getApproval()).hasSize(1);
    WorkflowStepApproval approval = rejected.getApproval().get(0);
    assertThat(approval.getApproved()).isFalse();
    assertThat(approval.getParty().getExternalReference()).isEqualTo(PartyMapper.VENUE_KEY);
    assertThat(approval.getRejectedReason()).isEqualTo("invalid order payload");
    assertValid(VALIDATOR.validate(rejected));
  }

  @Test
  void venueApprovalIsNotAnAcceptance() {
    WorkflowStep proposed = WORKFLOW.proposed(newOrder());

    WorkflowStep approved = WORKFLOW.approved(proposed, LifecycleFixtures.ORDER_AT);

    // The same proposal with the venue's approval: nothing executes when the matcher takes the
    // order onto the book, so there is no business event and no Accepted status.
    assertThat(approved.getPreviousWorkflowStep()).isNull();
    assertThat(approved.getBusinessEvent()).isNull();
    assertThat(approved.getProposedEvent()).isEqualTo(proposed.getProposedEvent());
    assertThat(approved.getWorkflowState() == null ? null
        : approved.getWorkflowState().getWorkflowStatus())
        .isNotEqualTo(WorkflowStatusEnum.ACCEPTED);
    assertThat(WorkflowMapper.isApproved(approved)).isTrue();
    assertThat(WorkflowMapper.isApproved(proposed)).isFalse();
    assertThat(WorkflowMapper.orderId(approved)).isEqualTo(ORDER_ID);
    assertValid(VALIDATOR.validate(approved));
  }

  @Test
  void approvedRefusesFillAndRejectedRequestProposals() {
    WorkflowStep proposed = WORKFLOW.proposed(newOrder());
    WorkflowStep approved = WORKFLOW.approved(proposed, LifecycleFixtures.ORDER_AT);
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, LifecycleFixtures.fill1());

    for (WorkflowStep notAnOrderProposal : List.of(fill.proposed(),
        WORKFLOW.rejectedProposal(rejectedOrder()), WORKFLOW.rejected(rejectedOrder()),
        approved, WORKFLOW.cancelCorrection(approved, 1500, 0, CANCEL_AT))) {
      assertThatThrownBy(() -> WORKFLOW.approved(notAnOrderProposal, LifecycleFixtures.ORDER_AT))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void cancelCorrectionRefusesFillSteps() {
    WorkflowStep approved = WORKFLOW.approved(WORKFLOW.proposed(newOrder()),
        LifecycleFixtures.ORDER_AT);
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, LifecycleFixtures.fill1());

    for (WorkflowStep fillStep : List.of(fill.proposed(), fill.accepted())) {
      assertThatThrownBy(() -> WORKFLOW.cancelCorrection(fillStep, 750, 750, CANCEL_AT))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> WORKFLOW.cancelCorrection(WORKFLOW.rejected(rejectedOrder()), 0, 0,
        CANCEL_AT)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectedCarriesReason() {
    WorkflowStep proposal = WORKFLOW.rejectedProposal(rejectedOrder());

    WorkflowStep rejected = WORKFLOW.rejected(rejectedOrder());

    assertThat(rejected.getRejected()).isTrue();
    assertThat(rejected.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(proposal.getMeta().getGlobalKey());
    assertThat(rejected.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.REJECTED);
    assertThat(rejected.getWorkflowState().getComment()).isEqualTo("invalid order payload");
    assertThat(rejected.getEventIdentifier().get(0).getAssignedIdentifier().get(0)
        .getIdentifier().getValue()).isEqualTo("rej-017-0001");
    // The request had no limit price: the proposal has a quantity but no price, and is valid.
    assertThat(WorkflowMapper.executionInstruction(proposal).getPriceQuantity().get(0).getPrice())
        .isNullOrEmpty();
    assertValid(VALIDATOR.validate(proposal));
    // The rejected proposal travels with the rejection.
    assertThat(rejected.getProposedEvent()).isEqualTo(proposal.getProposedEvent());
    assertValid(VALIDATOR.validate(rejected));
  }

  @Test
  void cancelCorrectsTheLatestProposalVersionKeepingItsInstruction() {
    WorkflowStep proposed = WORKFLOW.proposed(newOrder());
    WorkflowStep approved = WORKFLOW.approved(proposed, LifecycleFixtures.ORDER_AT);

    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, 750, 750, CANCEL_AT);

    assertThat(cancel.getAction()).isEqualTo(ActionEnum.CORRECT);
    assertThat(WorkflowMapper.isCancel(cancel)).isTrue();
    assertThat(WorkflowMapper.isCancel(approved)).isFalse();
    assertThat(cancel.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(approved.getMeta().getGlobalKey());
    assertThat(cancel.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.CANCELLED);
    assertThat(cancel.getTimestamp().get(0).getDateTime().toInstant()).isEqualTo(CANCEL_AT);
    // The instruction is the order's, unchanged: same quantity, limit, trade date and time.
    assertThat(WorkflowMapper.executionInstruction(cancel))
        .isEqualTo(WorkflowMapper.executionInstruction(proposed));
    assertThat(WorkflowMapper.instructionQuantity(cancel)).isEqualTo(1500);
    assertThat(WorkflowMapper.executionInstruction(cancel).getPriceQuantity().get(0).getPrice()
        .get(0).getValue().getValue()).isEqualTo(bd("180.000"));
    assertThat(cancel.getEventIdentifier().get(0).getAssignedIdentifier().get(0).getVersion())
        .isEqualTo(2);
    assertThat(WorkflowMapper.orderId(cancel)).isEqualTo(ORDER_ID);
    assertValid(VALIDATOR.validate(cancel));

    // An order never approved: the cancel corrects the proposal as submitted.
    WorkflowStep unapproved = WORKFLOW.cancelCorrection(proposed, 1500, 0, CANCEL_AT);
    assertThat(unapproved.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(proposed.getMeta().getGlobalKey());
    assertValid(VALIDATOR.validate(unapproved));
  }

  @Test
  void rejectedRequestWithUnknownValuesIsMappedAndReportedInvalid() {
    RejectedOrder bad = new RejectedOrder("rej-017-0002", 99999, "NOPE", null, -5, null,
        "invalid order payload", LifecycleFixtures.ORDER_AT);

    WorkflowStep rejected = WORKFLOW.rejected(bad);

    // Mapped as received (never thrown), and CDM validation records what is wrong.
    assertThat(rejected.getRejected()).isTrue();
    assertThat(VALIDATOR.validate(WORKFLOW.rejectedProposal(bad)).status())
        .isEqualTo(Status.INVALID);
  }

  @Test
  void unknownSecurityIsRefused() {
    OrderEvent unknown = new OrderEvent(ORDER_ID, ACCOUNT, "NOPE", "Buy", 1, 1, bd("1"), "NEW",
        LifecycleFixtures.ORDER_AT, null, null);

    assertThatThrownBy(() -> WORKFLOW.proposed(unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NOPE");
  }

  @Test
  void mappingIsDeterministic() {
    assertThat(WORKFLOW.approved(WORKFLOW.proposed(newOrder()), LifecycleFixtures.ORDER_AT))
        .isEqualTo(WORKFLOW.approved(WORKFLOW.proposed(newOrder()), LifecycleFixtures.ORDER_AT));
  }

  static void assertValid(CdmValidationResult result) {
    assertThat(result.messages()).isEmpty();
    assertThat(result.status()).isEqualTo(Status.VALID);
  }
}
