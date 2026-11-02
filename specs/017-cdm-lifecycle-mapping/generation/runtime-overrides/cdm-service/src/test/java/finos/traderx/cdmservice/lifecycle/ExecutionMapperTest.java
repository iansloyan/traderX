package finos.traderx.cdmservice.lifecycle;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CANCEL_AT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.EXECUTION;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_ID;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.WORKFLOW;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.bd;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.canceledOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.directTrade;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill1;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill2;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.golden;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.readGolden;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.rejectedOrder;
import static finos.traderx.cdmservice.lifecycle.WorkflowMapperTest.assertValid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.event.common.ActionEnum;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.position.PositionStatusEnum;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.observable.asset.PriceSchedule;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionMapperTest {

  private final WorkflowStep proposed = WORKFLOW.proposed(newOrder());
  private final WorkflowStep approved = WORKFLOW.approved(proposed, LifecycleFixtures.ORDER_AT);

  @Test
  void partialThenFullFillSumsToInstructionQuantity() {
    ExecutionMapper.FillWorkflow first = EXECUTION.fill(approved, fill1());
    ExecutionMapper.FillWorkflow second = EXECUTION.fill(approved, fill2());

    assertThat(ExecutionMapper.executedQuantity(List.of(first.accepted()))).isEqualTo(750);
    // Proposals (the order's and the fills') contribute nothing; acceptances sum to the order.
    assertThat(ExecutionMapper.executedQuantity(List.of(proposed, approved, first.proposed(),
        first.accepted(), second.proposed(), second.accepted())))
        .isEqualTo(WorkflowMapper.instructionQuantity(proposed))
        .isEqualTo(1500);
  }

  @Test
  void everyExecutionQualifies() {
    for (ExecutionMapper.FillWorkflow fill : List.of(EXECUTION.fill(approved, fill1()),
        EXECUTION.fill(approved, fill2()), EXECUTION.direct(directTrade()))) {
      assertValid(VALIDATOR.validateExecution(fill.event()));
      assertValid(fill.validateProposed(VALIDATOR));
      assertValid(fill.validateAccepted(VALIDATOR));
      assertValid(VALIDATOR.validate(fill.after()));
      assertThat(fill.mismatches()).isEmpty();
    }
  }

  @Test
  void orderLinksProposalVersionsWhileFillsAreTheirOwnWorkflows() {
    ExecutionMapper.FillWorkflow first = EXECUTION.fill(approved, fill1());
    ExecutionMapper.FillWorkflow second = EXECUTION.fill(approved, new BookedTrade(
        "trd-017-0002", ORDER_ID, 22214, "IBM", "Buy", 500, bd("179.500"),
        LifecycleFixtures.FILL2_AT));
    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, 250, 1250, CANCEL_AT);

    // The order: submitted and approved are one proposal (no CDM link); the cancel corrects the
    // approved version.
    assertThat(proposed.getPreviousWorkflowStep()).isNull();
    assertThat(approved.getPreviousWorkflowStep()).isNull();
    assertThat(cancel.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(approved.getMeta().getGlobalKey());
    assertThat(List.of(proposed, approved, cancel)).extracting(WorkflowStep::getAction)
        .containsExactly(ActionEnum.NEW, ActionEnum.NEW, ActionEnum.CORRECT);
    // Each fill: proposed (no previous) then accepted (linked to it).
    for (ExecutionMapper.FillWorkflow fill : List.of(first, second)) {
      assertThat(fill.proposed().getPreviousWorkflowStep()).isNull();
      assertThat(fill.accepted().getPreviousWorkflowStep().getGlobalReference())
          .isEqualTo(fill.proposed().getMeta().getGlobalKey());
      assertValid(VALIDATOR.validate(fill.proposed()));
      assertValid(VALIDATOR.validate(fill.accepted()));
    }
    assertValid(VALIDATOR.validate(cancel));
    // Each fill is its own trade and its own event: identifiers never collide across fills.
    assertThat(List.<Object>copyOf(first.after().getTrade().getTradeIdentifier()))
        .doesNotContainAnyElementsOf(List.copyOf(second.after().getTrade().getTradeIdentifier()));
    assertThat(ExecutionMapper.executedQuantity(List.of(proposed, approved, first.accepted(),
        second.accepted(), cancel))).isEqualTo(1250);
  }

  @Test
  void tradeLotPriceIsBookedPrice() {
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, fill1());

    TradeState after = fill.after();
    Trade trade = after.getTrade();
    PriceSchedule price = trade.getTradeLot().get(0).getPriceQuantity().get(0).getPrice().get(0)
        .getValue();
    // The booked trade price (179.5, normalised to 3 decimals), not the order's limit price.
    assertThat(price.getValue()).isEqualTo(bd("179.500"));
    assertThat(trade.getTradeLot().get(0).getPriceQuantity().get(0).getQuantity().getValue()
        .getValue()).isEqualTo(bd("750"));
    // Gross consideration in USD = 750 x 179.500.
    assertThat(price.getDerivedQuantity().getValue()).isEqualTo(bd("134625.000"));
    assertThat(trade.getExecutionDetails().getExecutionType())
        .isEqualTo(ExecutionTypeEnum.ON_VENUE);
    assertThat(trade.getExecutionDetails().getExecutionVenue().getName().getValue())
        .isEqualTo(PartyMapper.VENUE_NAME);
    assertThat(trade.getTradeDate().getValue().toString()).isEqualTo("2026-10-29");
    // Ruling R37: TraderX has no settlement process, so a booked trade is EXECUTED.
    assertThat(after.getState().getPositionState()).isEqualTo(PositionStatusEnum.EXECUTED);
    assertThat(after.getValuationHistory()).isNullOrEmpty();
    assertThat(ExecutionMapper.tradeId(after)).isEqualTo("trd-017-0001");
    assertThat(trade.getTradeIdentifier()).hasSize(1);
  }

  @Test
  void tradeStateCarriesPositionState() {
    for (String[] c : new String[][] {{"New", "EXECUTED"}, {"Processing", "EXECUTED"},
        {"Settled", "EXECUTED"}, {"Cancelled", "CANCELLED"}, {null, "EXECUTED"}}) {
      BookedTrade booked = new BookedTrade("trd-017-0001", ORDER_ID, 22214, "IBM", "Buy", 750,
          bd("179.500"), LifecycleFixtures.FILL1_AT, c[0]);
      ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, booked);
      assertThat(fill.after().getState().getPositionState())
          .as("TraderX state %s", c[0])
          .isEqualTo(PositionStatusEnum.valueOf(c[1]));
      assertThat(fill.validateAccepted(VALIDATOR).status())
          .as("TraderX state %s", c[0])
          .isEqualTo(Status.VALID);
    }
    BookedTrade odd = new BookedTrade("trd-017-0001", ORDER_ID, 22214, "IBM", "Buy", 750,
        bd("179.500"), LifecycleFixtures.FILL1_AT, "Pending");
    assertThatThrownBy(() -> EXECUTION.fill(approved, odd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pending");
  }

  @Test
  void mismatchedFillIsFlagged() {
    BookedTrade wrong = new BookedTrade("trd-017-0009", ORDER_ID, 22214, "IBM", "Sell", 750,
        bd("179.500"), LifecycleFixtures.FILL1_AT);

    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, wrong);

    // Mapped as booked (never thrown), and both steps flagged INVALID with a clear message.
    assertThat(fill.accepted()).isNotNull();
    for (CdmValidationResult result : List.of(fill.validateProposed(VALIDATOR),
        fill.validateAccepted(VALIDATOR))) {
      assertThat(result.status()).isEqualTo(Status.INVALID);
      assertThat(result.messages()).contains(fill.mismatches().get(0));
    }
  }

  @Test
  void tradeOfAnotherOrderIsRefused() {
    BookedTrade other = new BookedTrade("trd-x", "ord-013-9999", 22214, "IBM", "Buy", 1,
        bd("1"), LifecycleFixtures.FILL1_AT);

    assertThatThrownBy(() -> EXECUTION.fill(approved, other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ord-013-9999");
  }

  @Test
  void fillRefusesAFillStepAsItsOrder() {
    ExecutionMapper.FillWorkflow first = EXECUTION.fill(approved, fill1());

    // A fill's proposal carries the order id too, but it is not a version of the order.
    for (WorkflowStep notTheOrder : List.of(first.proposed(), first.accepted())) {
      assertThatThrownBy(() -> EXECUTION.fill(notTheOrder, fill2()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proposal version of its order");
    }
    assertThat(ExecutionMapper.fillTradeId(first.proposed())).isEqualTo("trd-017-0001");
    assertThat(ExecutionMapper.fillTradeId(approved)).isNull();
  }

  @Test
  void cancelAfterPartialFillKeepsExecutionsAndCancelsRemainder() {
    ExecutionMapper.FillWorkflow first = EXECUTION.fill(approved, fill1());
    int executed = ExecutionMapper.executedQuantity(List.of(first.accepted()));
    // The remainder is derived (instruction quantity - executed) and agrees with the matcher.
    int derivedRemainder = WorkflowMapper.instructionQuantity(proposed) - executed;
    assertThat(derivedRemainder).isEqualTo(750).isEqualTo(canceledOrder().canceledRemainder());

    WorkflowStep cancel = WORKFLOW.cancelCorrection(approved, derivedRemainder, executed,
        CANCEL_AT);

    // The cancel corrects the approved proposal, never the fill; the fill stands.
    assertThat(cancel.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(approved.getMeta().getGlobalKey());
    assertThat(WorkflowMapper.isCancel(cancel)).isTrue();
    assertThat(cancel.getBusinessEvent()).isNull();
    assertThat(cancel.getTimestamp().get(0).getDateTime().toInstant()).isEqualTo(CANCEL_AT);
    assertValid(first.validateAccepted(VALIDATOR));
    assertValid(VALIDATOR.validate(cancel));
  }

  @Test
  void directTradeHasSameShapeAsAFill() {
    ExecutionMapper.FillWorkflow direct = EXECUTION.direct(directTrade());
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, fill1());

    assertThat(List.of(direct.proposed(), direct.accepted()))
        .extracting(WorkflowStep::getAction, s -> s.getWorkflowState() == null ? null
            : s.getWorkflowState().getWorkflowStatus(), s -> s.getBusinessEvent() != null)
        .containsExactlyElementsOf(List.of(fill.proposed(), fill.accepted()).stream()
            .map(s -> org.assertj.core.groups.Tuple.tuple(s.getAction(),
                s.getWorkflowState() == null ? null : s.getWorkflowState().getWorkflowStatus(),
                s.getBusinessEvent() != null))
            .toList());
    // Executed in full, at the trade price; the account sold, so it is the seller.
    Trade trade = direct.after().getTrade();
    assertThat(trade.getPartyRole()).anySatisfy(r -> {
      assertThat(r.getRole()).isEqualTo(PartyRoleEnum.SELLER);
      assertThat(r.getPartyReference().getExternalReference()).isEqualTo("22214");
    });
    assertValid(direct.validateProposed(VALIDATOR));
    assertValid(direct.validateAccepted(VALIDATOR));
  }

  @Test
  void fillIsItsOwnTwoStepWorkflow() {
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, fill1());

    WorkflowStep fillProposed = fill.proposed();
    assertThat(fillProposed.getAction()).isEqualTo(ActionEnum.NEW);
    assertThat(fillProposed.getPreviousWorkflowStep()).isNull();
    assertThat(fillProposed.getProposedEvent()).isNotNull();
    assertThat(fillProposed.getBusinessEvent()).isNull();
    // The child instruction: the fill quantity at the fill price.
    assertThat(WorkflowMapper.instructionQuantity(fillProposed)).isEqualTo(750);
    assertThat(WorkflowMapper.executionInstruction(fillProposed).getPriceQuantity().get(0)
        .getPrice().get(0).getValue().getValue()).isEqualTo(bd("179.500"));

    WorkflowStep fillAccepted = fill.accepted();
    assertThat(fillAccepted.getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(fillProposed.getMeta().getGlobalKey());
    assertThat(fillAccepted.getBusinessEvent()).isNotNull().isEqualTo(fill.event());
    assertThat(fillAccepted.getProposedEvent()).isNull();
    assertThat(fillAccepted.getWorkflowState().getWorkflowStatus())
        .isEqualTo(WorkflowStatusEnum.ACCEPTED);
    assertValid(VALIDATOR.validateExecution(fill.event()));
    assertThat(fill.event().getAfter()).hasSize(1);
    assertThat(fill.after().getState().getPositionState()).isEqualTo(PositionStatusEnum.EXECUTED);
    assertThat(ExecutionMapper.tradeId(fill.after())).isEqualTo("trd-017-0001");
    assertThat(fill.mismatches()).isEmpty();
    assertValid(VALIDATOR.validate(fillProposed));
    assertValid(VALIDATOR.validate(fillAccepted));
    assertValid(VALIDATOR.validate(fill.after()));
  }

  @Test
  void fillCarriesOrderAndTradeIdentifiers() {
    ExecutionMapper.FillWorkflow first = EXECUTION.fill(approved, fill1());
    ExecutionMapper.FillWorkflow second = EXECUTION.fill(approved, fill2());

    for (ExecutionMapper.FillWorkflow fill : List.of(first, second)) {
      for (WorkflowStep step : List.of(fill.proposed(), fill.accepted())) {
        assertThat(WorkflowMapper.orderId(step)).isEqualTo(ORDER_ID);
        assertThat(CdmLifecycle.identifierValue(step.getEventIdentifier(),
            ExecutionMapper.FILL_ID_SCHEME)).isEqualTo(ExecutionMapper.tradeId(fill.after()));
      }
    }
    assertThat(CdmLifecycle.identifierValue(first.accepted().getEventIdentifier(),
        ExecutionMapper.FILL_ID_SCHEME)).isEqualTo("trd-017-0001");
    // Fills of one order never share an event identifier, in whatever order they arrive.
    assertThat(List.<Object>copyOf(first.proposed().getEventIdentifier()))
        .isNotEqualTo(List.<Object>copyOf(second.proposed().getEventIdentifier()));
    assertThat(EXECUTION.fill(approved, fill2())).isEqualTo(second);
    assertThat(ExecutionMapper.executedQuantity(List.of(first.accepted(), second.accepted())))
        .isEqualTo(WorkflowMapper.instructionQuantity(proposed));
  }

  @Test
  void fillIsOnVenueDirectIsOffFacilityWithoutVenue() {
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, fill1());
    ExecutionMapper.FillWorkflow direct = EXECUTION.direct(directTrade());

    assertThat(WorkflowMapper.executionInstruction(fill.proposed()).getExecutionDetails()
        .getExecutionType()).isEqualTo(ExecutionTypeEnum.ON_VENUE);
    assertThat(fill.after().getTrade().getExecutionDetails().getExecutionType())
        .isEqualTo(ExecutionTypeEnum.ON_VENUE);
    assertThat(WorkflowMapper.executionInstruction(direct.proposed()).getExecutionDetails()
        .getExecutionType()).isEqualTo(ExecutionTypeEnum.OFF_FACILITY);
    assertThat(direct.after().getTrade().getExecutionDetails().getExecutionType())
        .isEqualTo(ExecutionTypeEnum.OFF_FACILITY);
    // A direct trade is a bilateral booking: no execution venue and no ExecutionFacility party.
    assertThat(direct.after().getTrade().getExecutionDetails().getExecutionVenue()).isNull();
    assertThat(direct.after().getTrade().getPartyRole())
        .noneMatch(r -> r.getRole() == PartyRoleEnum.EXECUTION_FACILITY);
    assertThat(direct.after().getTrade().getParty()).extracting(p -> p.getMeta().getExternalKey())
        .containsExactly("traderx-nyc", Integer.toString(ACCOUNT));
    // A fill keeps the venue as execution facility, never as a counterparty.
    assertThat(fill.after().getTrade().getCounterparty())
        .extracting(c -> c.getPartyReference().getExternalReference())
        .containsExactlyInAnyOrder("traderx-nyc", Integer.toString(ACCOUNT));
    assertThat(fill.after().getTrade().getPartyRole())
        .anyMatch(r -> r.getRole() == PartyRoleEnum.EXECUTION_FACILITY
            && PartyMapper.VENUE_KEY.equals(r.getPartyReference().getExternalReference()));
  }

  @Test
  void directTradeIsProposedThenAcceptedWithItsExecution() {
    ExecutionMapper.FillWorkflow direct = EXECUTION.direct(directTrade());

    assertThat(direct.proposed().getAction()).isEqualTo(ActionEnum.NEW);
    assertThat(direct.proposed().getPreviousWorkflowStep()).isNull();
    assertThat(direct.accepted().getPreviousWorkflowStep().getGlobalReference())
        .isEqualTo(direct.proposed().getMeta().getGlobalKey());
    assertThat(WorkflowMapper.orderId(direct.accepted())).isNull();
    assertThat(ExecutionMapper.tradeId(direct.after())).isEqualTo("trd-017-0100");
    assertThat(ExecutionMapper.executedQuantity(List.of(direct.accepted())))
        .isEqualTo(WorkflowMapper.instructionQuantity(direct.proposed()))
        .isEqualTo(200);
    assertThat(direct.after().getTrade().getPartyRole()).anySatisfy(r -> {
      assertThat(r.getRole()).isEqualTo(PartyRoleEnum.SELLER);
      assertThat(r.getPartyReference().getExternalReference()).isEqualTo("22214");
    });
    assertThat(direct.mismatches()).isEmpty();
    assertValid(VALIDATOR.validate(direct.proposed()));
    assertValid(VALIDATOR.validate(direct.accepted()));
    assertValid(VALIDATOR.validateExecution(direct.event()));
    assertThatThrownBy(() -> EXECUTION.direct(fill1()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noAcceptedStepHasAnEmptyEvent() {
    List<ExecutionMapper.FillWorkflow> workflows = List.of(EXECUTION.fill(approved, fill1()),
        EXECUTION.fill(approved, fill2()), EXECUTION.direct(directTrade()));

    for (ExecutionMapper.FillWorkflow workflow : workflows) {
      assertThat(workflow.accepted().getBusinessEvent().getInstruction()).isNotEmpty();
      assertThat(workflow.accepted().getBusinessEvent().getAfter()).isNotEmpty();
      assertThat(workflow.proposed().getBusinessEvent()).isNull();
    }
    // The order's own steps carry no business event at all.
    assertThat(approved.getBusinessEvent()).isNull();
    assertThat(WORKFLOW.cancelCorrection(approved, 750, 750, CANCEL_AT).getBusinessEvent())
        .isNull();
  }

  @Test
  void mismatchedFillWorkflowIsFlagged() {
    BookedTrade wrong = new BookedTrade("trd-017-0009", ORDER_ID, 22214, "IBM", "Sell", 750,
        bd("179.500"), LifecycleFixtures.FILL1_AT);

    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, wrong);

    assertThat(fill.mismatches()).containsExactly(
        ExecutionMapper.MISMATCH_PREFIX + "side SELL but the order's is BUY");
    BookedTrade other = new BookedTrade("trd-x", "ord-013-9999", 22214, "IBM", "Buy", 1,
        bd("1"), LifecycleFixtures.FILL1_AT);
    assertThatThrownBy(() -> EXECUTION.fill(approved, other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ord-013-9999");
  }

  @Test
  void goldenExamplesMatch() {
    ExecutionMapper.FillWorkflow fill = EXECUTION.fill(approved, fill1());
    ExecutionMapper.FillWorkflow direct = EXECUTION.direct(directTrade());
    Map<String, Object> examples = new LinkedHashMap<>();
    examples.put("order-proposed", proposed);
    examples.put("order-approved", approved);
    examples.put("order-rejected",
        List.of(WORKFLOW.rejectedProposal(rejectedOrder()), WORKFLOW.rejected(rejectedOrder())));
    examples.put("fill-proposed", fill.proposed());
    examples.put("fill-accepted", fill.accepted());
    examples.put("order-cancel-remainder",
        WORKFLOW.cancelCorrection(approved, 750, 750, CANCEL_AT));
    examples.put("direct-trade", List.of(direct.proposed(), direct.accepted()));

    examples.forEach((name, cdm) ->
        assertThat(golden(name, cdm)).as(name).isEqualTo(readGolden(name)));
  }
}
