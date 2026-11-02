package finos.traderx.cdmservice.api;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT_PARTY;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CDM_JSON;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.IBM;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.INJECTOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_ID;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.bd;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.canceledOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.directTrade;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill1;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill2;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.rejectedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.Party;
import cdm.event.common.TradeState;
import cdm.event.position.PortfolioState;
import cdm.event.workflow.Workflow;
import cdm.event.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.cdmservice.lifecycle.BookedTrade;
import finos.traderx.cdmservice.lifecycle.LifecycleAdapter;
import finos.traderx.cdmservice.lifecycle.LifecycleFailureRepository;
import finos.traderx.cdmservice.lifecycle.LifecycleFixtures;
import finos.traderx.cdmservice.lifecycle.OrderEvent;
import finos.traderx.cdmservice.lifecycle.PendingCancelRepository;
import finos.traderx.cdmservice.lifecycle.PendingTradeRepository;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.position.PositionView;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.security.SecurityMaster;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lineage, resolve, trade and position endpoints over MockMvc, against what the real
 * lifecycle adapter stored on H2 (same DDL as runtime). Not a {@code @WebMvcTest} slice: that
 * slice has no JPA, and a mocked store would only test the mock; the controller runs in
 * standalone MockMvc with the real store, repositories and position view, and mocked masters.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LineageControllerTest {

  private static final String ORDER_REF = "cdm:order:" + ORDER_ID;

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

  private LifecycleAdapter adapter;
  private CdmStore store;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    clean();
    store = new CdmStore(documents, CDM_JSON, transactionManager, Clock.systemUTC());
    adapter = LifecycleFixtures.adapter(store, pendingTrades, pendingCancels, failureRows);
    SecurityMaster securities = mock(SecurityMaster.class);
    when(securities.findByTicker("IBM")).thenReturn(Optional.of(IBM));
    when(securities.findByTicker("NOPE")).thenReturn(Optional.empty());
    PartyMaster parties = mock(PartyMaster.class);
    when(parties.resolvedParty(ACCOUNT)).thenReturn(Optional.of(ACCOUNT_PARTY));
    when(parties.resolvedParty(99999)).thenReturn(Optional.empty());
    when(parties.venue()).thenReturn(PartyMapper.venueParty());
    when(parties.findPerson("user01")).thenReturn(Optional.of(
        PartyMapper.identifiedPerson("user01")));
    when(parties.findPerson("ghost")).thenReturn(Optional.empty());
    PositionView positions = new PositionView(store, CDM_JSON, VALIDATOR, INJECTOR);
    mvc = MockMvcBuilders.standaloneSetup(new LineageController(store, positions, securities,
        parties, failureRows, pendingTrades, pendingCancels, CDM_JSON)).build();
  }

  @AfterEach
  void clean() {
    documents.deleteAll();
    pendingTrades.deleteAll();
    pendingCancels.deleteAll();
    failureRows.deleteAll();
  }

  private JsonNode json(String url) throws Exception {
    return CDM_JSON.readTree(mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andReturn().getResponse().getContentAsString());
  }

  // --- resolve -------------------------------------------------------------------------------

  @Test
  void resolveUnknownRefReturnsPendingNotFound() throws Exception {
    for (String ref : new String[] {"cdm:order:ord-not-yet", "cdm:trade:trd-not-yet",
        "cdm:position:22214:IBM", "cdm:position:22214", "cdm:security:NOPE",
        "cdm:party:99999", "cdm:person:ghost"}) {
      mvc.perform(get("/cdm/resolve/" + ref))
          .andExpect(status().isNotFound())
          .andExpect(content().json("{\"cdmRef\":\"" + ref + "\",\"status\":\"pending\"}",
              JsonCompareMode.STRICT));
    }
  }

  @Test
  void resolveMalformedRefIsBadRequest() throws Exception {
    for (String ref : new String[] {"order-1", "cdm:widget:1", "cdm:order", "cdm:party:abc",
        "cdm:position:abc:IBM", "cdm:position:22214:"}) {
      mvc.perform(get("/cdm/resolve/" + ref))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.cdmRef").value(ref))
          .andExpect(jsonPath("$.error").exists());
    }
  }

  @Test
  void resolveOrderReturnsItsLatestVersionAndLinksTheLineage() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());

    String body = mvc.perform(get("/cdm/resolve/" + ORDER_REF))
        .andExpect(status().isOk())
        .andExpect(header().string("X-CDM-Type", WorkflowStep.class.getName()))
        .andExpect(header().string("X-CDM-Validation", "VALID"))
        .andExpect(header().string("Link",
            "</cdm/orders/" + ORDER_ID + "/lineage>; rel=\"lineage\""))
        .andReturn().getResponse().getContentAsString();

    WorkflowStep latest = CDM_JSON.readValue(body, WorkflowStep.class);
    // The order's own latest version (approved), not the fill's acceptance stored after it:
    // each fill is its own workflow.
    assertThat(latest.getMeta().getExternalKey()).isEqualTo(ORDER_ID + ":approved");

    adapter.onOrder(canceledOrder());
    assertThat(CDM_JSON.readValue(mvc.perform(get("/cdm/resolve/" + ORDER_REF))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
        WorkflowStep.class).getMeta().getExternalKey()).isEqualTo(ORDER_ID + ":cancel");
  }

  @Test
  void resolveTradeReturnsItsTradeState() throws Exception {
    adapter.onBookedTrade(directTrade());

    String body = mvc.perform(get("/cdm/resolve/cdm:trade:trd-017-0100"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-CDM-Type", TradeState.class.getName()))
        .andReturn().getResponse().getContentAsString();

    assertThat(CDM_JSON.readValue(body, TradeState.class).getTrade()).isNotNull();
  }

  @Test
  void resolveMastersAndPositions() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());

    assertThat(CDM_JSON.readValue(mvc.perform(get("/cdm/resolve/cdm:party:22214"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Party.class))
        .isEqualTo(ACCOUNT_PARTY);
    assertThat(json("/cdm/resolve/cdm:party:traderx-matching-engine").at("/name/value")
        .asText()).isEqualTo(PartyMapper.VENUE_NAME);
    assertThat(CDM_JSON.readValue(mvc.perform(get("/cdm/resolve/cdm:person:user01"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
        NaturalPerson.class)).isEqualTo(PartyMapper.identifiedPerson("user01"));
    assertThat(json("/cdm/resolve/cdm:security:IBM").at("/securityType").asText())
        .isEqualTo("Equity");

    JsonNode portfolio = json("/cdm/resolve/cdm:position:22214");
    assertThat(CDM_JSON.treeToValue(portfolio, PortfolioState.class).getPositions()).hasSize(1);
    JsonNode position = json("/cdm/resolve/cdm:position:22214:IBM");
    assertThat(position.at("/priceQuantity/0/quantity/value/value").asInt()).isEqualTo(750);
  }

  // --- lineage -------------------------------------------------------------------------------

  private static final String VENUE_REF = "cdm:party:traderx-matching-engine";

  private static BookedTrade fillOf(String tradeId, int quantity, String at) {
    return new BookedTrade(tradeId, ORDER_ID, ACCOUNT, "IBM", "Buy", quantity, bd("179.5"),
        Instant.parse(at), "Settled");
  }

  @Test
  void orderLineageNestsFillsWithTradeStates() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1()); // 750
    adapter.onBookedTrade(fillOf("trd-017-0003", 250, "2026-10-29T14:30:10Z"));

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/cdmRef").asText()).isEqualTo(ORDER_REF);
    assertThat(lineage.at("/orderId").asText()).isEqualTo(ORDER_ID);
    assertThat(lineage.at("/status").asText()).isEqualTo("partially-filled");
    assertThat(lineage.at("/instructionQuantity").asInt()).isEqualTo(1500);
    assertThat(lineage.at("/executedQuantity").asInt()).isEqualTo(1000);
    assertThat(lineage.at("/remainingQuantity").asInt()).isEqualTo(500);
    assertThat(lineage.at("/canceledQuantity").asInt()).isZero();
    // The flat chain and the executions list are replaced by proposal / fills / cancel.
    assertThat(lineage.has("steps")).isFalse();
    assertThat(lineage.has("executions")).isFalse();

    JsonNode versions = lineage.at("/proposal/versions");
    assertThat(versions.findValuesAsText("stepType")).containsExactly("proposed", "approved");
    assertThat(versions.get(0).at("/previousStoreGlobalKey").isNull()).isTrue();
    assertThat(versions.get(1).at("/previousStoreGlobalKey").asText())
        .isEqualTo(versions.get(0).at("/storeGlobalKey").asText());
    JsonNode approval = lineage.at("/proposal/approval");
    assertThat(approval.at("/approved").asBoolean()).isTrue();
    assertThat(approval.at("/party").asText()).isEqualTo(VENUE_REF);
    assertThat(approval.at("/timestamp").asText()).isEqualTo("2026-10-29T14:30:00Z");
    assertThat(approval.at("/rejectedReason").isNull()).isTrue();
    assertThat(approval.at("/stepStoreGlobalKey").asText())
        .isEqualTo(versions.get(1).at("/storeGlobalKey").asText());

    JsonNode fills = lineage.at("/fills");
    assertThat(fills).hasSize(2);
    assertThat(fills.findValuesAsText("label"))
        .containsExactly("Partial fill 1", "Partial fill 2");
    assertThat(fills.findValues("fillNumber")).extracting(JsonNode::asInt).containsExactly(1, 2);
    assertThat(fills.findValues("filledSoFar")).extracting(JsonNode::asInt)
        .containsExactly(750, 1000);
    assertThat(List.of(fills.get(0).at("/quantity").asInt(), fills.get(1).at("/quantity").asInt()))
        .containsExactly(750, 250);
    JsonNode first = fills.get(0);
    assertThat(first.at("/tradeId").asText()).isEqualTo("trd-017-0001");
    assertThat(first.at("/price").decimalValue()).isEqualByComparingTo("179.5");
    assertThat(first.at("/executedAt").asText()).isEqualTo("2026-10-29T14:30:05Z");
    assertThat(first.at("/proposed/stepType").asText()).isEqualTo("fill-proposed");
    assertThat(first.at("/accepted/stepType").asText()).isEqualTo("fill-accepted");
    // Each fill is its own workflow: the proposal has no previous step; the acceptance follows it.
    assertThat(first.at("/proposed/previousStoreGlobalKey").isNull()).isTrue();
    assertThat(first.at("/accepted/previousStoreGlobalKey").asText())
        .isEqualTo(first.at("/proposed/storeGlobalKey").asText());
    assertThat(first.at("/accepted/object/previousWorkflowStep/globalReference").asText())
        .isEqualTo(first.at("/proposed/cdmGlobalKey").asText());
    for (JsonNode step : List.of(versions.get(0), versions.get(1), first.at("/proposed"),
        first.at("/accepted"))) {
      assertThat(step.at("/storeGlobalKey").asText()).hasSize(64);
      assertThat(step.at("/cdmGlobalKey").asText())
          .isEqualTo(step.at("/object/meta/globalKey").asText());
      assertThat(step.at("/cdmType").asText()).isEqualTo(WorkflowStep.class.getName());
      assertThat(step.at("/validation/status").asText()).isEqualTo("VALID");
      assertThat(step.at("/timestamp").isTextual()).isTrue();
    }
    // The trade state the fill produced, inline (not a further chain step).
    JsonNode tradeState = first.at("/tradeState");
    assertThat(tradeState.at("/cdmRef").asText()).isEqualTo("cdm:trade:trd-017-0001");
    assertThat(tradeState.at("/storeGlobalKey").asText()).hasSize(64);
    assertThat(tradeState.at("/cdmGlobalKey").asText()).isEqualTo(first.at(
        "/accepted/object/businessEvent/after/0/meta/globalKey").asText());
    assertThat(tradeState.at("/positionState").asText()).isEqualTo("Executed");
    assertThat(tradeState.at("/validation/status").asText()).isEqualTo("VALID");
    assertThat(lineage.at("/cancel").isNull()).isTrue();

    assertThat(lineage.at("/parties/account/cdmRef").asText()).isEqualTo("cdm:party:22214");
    assertThat(lineage.at("/parties/account/name").asText()).isEqualTo("Test Account 20");
    assertThat(lineage.at("/parties/venue/cdmRef").asText()).isEqualTo(VENUE_REF);
    assertThat(lineage.at("/security/ticker").asText()).isEqualTo("IBM");
    assertThat(lineage.at("/failures")).isEmpty();
    assertThat(lineage.at("/pending/trades")).isEmpty();
    assertThat(lineage.at("/pending/cancel").isNull()).isTrue();
  }

  @Test
  void fillNumbersFollowBookingOrderNotArrival() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill2()); // booked later, arrives first
    adapter.onBookedTrade(fill1());

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    JsonNode fills = lineage.at("/fills");
    assertThat(fills.findValuesAsText("tradeId")).containsExactly("trd-017-0001", "trd-017-0002");
    assertThat(fills.findValuesAsText("label")).containsExactly("Partial fill 1", "Final fill 2");
    assertThat(fills.findValues("filledSoFar")).extracting(JsonNode::asInt)
        .containsExactly(750, 1500);
    assertThat(lineage.at("/status").asText()).isEqualTo("filled");
    assertThat(lineage.at("/remainingQuantity").asInt()).isZero();
  }

  @Test
  void fillNumbersBreakTimeTiesByTradeId() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fillOf("trd-017-0005", 100, "2026-10-29T14:30:05Z"));
    adapter.onBookedTrade(fillOf("trd-017-0004", 100, "2026-10-29T14:30:05Z"));

    assertThat(json("/cdm/orders/" + ORDER_ID + "/lineage").at("/fills")
        .findValuesAsText("tradeId")).containsExactly("trd-017-0004", "trd-017-0005");
  }

  @Test
  void singleCompleteFillIsFullFill() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fillOf("trd-017-0007", 1500, "2026-10-29T14:30:05Z"));

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/fills").findValuesAsText("label")).containsExactly("Full fill");
    assertThat(lineage.at("/fills/0/filledSoFar").asInt()).isEqualTo(1500);
    assertThat(lineage.at("/status").asText()).isEqualTo("filled");
  }

  @Test
  void lineageOfAcceptedOrderWithoutFills() throws Exception {
    adapter.onOrder(newOrder());

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/proposal/versions").findValuesAsText("stepType"))
        .containsExactly("proposed", "approved");
    assertThat(lineage.at("/status").asText()).isEqualTo("accepted");
    assertThat(lineage.at("/fills")).isEmpty();
    assertThat(lineage.at("/executedQuantity").asInt()).isZero();
    assertThat(lineage.at("/canceledQuantity").asInt()).isZero();
    assertThat(lineage.at("/remainingQuantity").asInt()).isEqualTo(1500);
    assertThat(lineage.at("/remainingQuantityFormula").asText())
        .isEqualTo("instructionQuantity - executedQuantity - canceledQuantity");
  }

  @Test
  void overExecutedOrderShowsZeroRemainingWithAWarning() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    // The matcher would never do this; a fill is still mapped as booked (R23).
    adapter.onBookedTrade(fillOf("trd-017-0009", 1000, "2026-10-29T14:30:20Z"));

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/executedQuantity").asInt()).isEqualTo(1750);
    assertThat(lineage.at("/remainingQuantity").asInt()).isZero();
    assertThat(lineage.at("/quantityWarning").asText()).contains("by 250");
    assertThat(lineage.at("/fills").findValuesAsText("label"))
        .containsExactly("Partial fill 1", "Final fill 2");
  }

  @Test
  void lineageOfCanceledOrderKeepsFillsAndCancelsTheRemainder() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onOrder(canceledOrder());

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/proposal/versions").findValuesAsText("stepType"))
        .containsExactly("proposed", "approved");
    assertThat(lineage.at("/fills").findValuesAsText("label")).containsExactly("Partial fill 1");
    JsonNode cancel = lineage.at("/cancel");
    assertThat(cancel.at("/stepType").asText()).isEqualTo("cancel");
    assertThat(cancel.at("/validation/status").asText()).isEqualTo("VALID");
    // A correction of the latest proposal version (the approved one), never of a fill step.
    assertThat(cancel.at("/previousStoreGlobalKey").asText())
        .isEqualTo(lineage.at("/proposal/versions/1/storeGlobalKey").asText());
    assertThat(lineage.at("/executedQuantity").asInt()).isEqualTo(750);
    assertThat(lineage.at("/canceledQuantity").asInt()).isEqualTo(750);
    assertThat(lineage.at("/remainingQuantity").asInt()).isZero();
    assertThat(lineage.at("/status").asText()).isEqualTo("canceled");
  }

  @Test
  void canceledQuantityIsTheMatchersCancelledQuantity() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    // The matcher reports 700 cancelled (not instruction - executed = 750): the API shows what
    // the cancel step records, not a derivation.
    adapter.onOrder(new OrderEvent(ORDER_ID, ACCOUNT, "IBM", "Buy", 1500, 0, bd("180.000"),
        "CANCELED", LifecycleFixtures.CANCEL_AT, bd("179.500"), 750, 750, 700));

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(lineage.at("/cancel/object/workflowState/comment").asText()).contains("700");
    assertThat(lineage.at("/canceledQuantity").asInt()).isEqualTo(700);
    assertThat(lineage.at("/remainingQuantity").asInt()).isEqualTo(50);
  }

  @Test
  void lineageShowsFailuresAndParkedEvents() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onOrder(canceledOrder());
    adapter.onBookedTrade(fill2()); // after the cancel: refused and recorded

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");
    assertThat(lineage.at("/failures")).hasSize(1);
    assertThat(lineage.at("/failures/0/stage").asText()).isEqualTo("execution");
    assertThat(lineage.at("/failures/0/cdmRef").asText()).isEqualTo(ORDER_REF);
    assertThat(lineage.at("/failures/0/message").asText()).contains("trd-017-0002");

    // A fill for an order the adapter has not seen (and the matcher does not know) is parked.
    adapter.onBookedTrade(new BookedTrade("trd-017-0300", "ord-017-unseen", ACCOUNT, "IBM",
        "Buy", 10, bd("180"), Instant.parse("2026-10-29T16:00:00Z")));
    JsonNode parked = json("/cdm/orders/ord-017-unseen/lineage");
    assertThat(parked.at("/status").asText()).isEqualTo("pending");
    assertThat(parked.at("/proposal/versions")).isEmpty();
    assertThat(parked.at("/proposal/approval").isNull()).isTrue();
    assertThat(parked.at("/fills")).isEmpty();
    assertThat(parked.at("/pending/trades")).hasSize(1);
    assertThat(parked.at("/pending/trades/0/tradeId").asText()).isEqualTo("trd-017-0300");
    assertThat(parked.at("/pending/trades/0/payload/quantity").asInt()).isEqualTo(10);
  }

  @Test
  void lineageOfUnknownOrderIsPendingNotFound() throws Exception {
    mvc.perform(get("/cdm/orders/ord-not-yet/lineage"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"cdmRef\":\"cdm:order:ord-not-yet\",\"status\":\"pending\"}",
            JsonCompareMode.STRICT));
  }

  @Test
  void lineageOfRejectedRequest() throws Exception {
    adapter.onRejected(rejectedOrder());

    JsonNode lineage = json("/cdm/orders/rej-017-0001/lineage");

    assertThat(lineage.at("/proposal/versions").findValuesAsText("stepType"))
        .containsExactly("proposed", "rejected");
    JsonNode approval = lineage.at("/proposal/approval");
    assertThat(approval.at("/approved").asBoolean()).isFalse();
    assertThat(approval.at("/party").asText()).isEqualTo(VENUE_REF);
    assertThat(approval.at("/rejectedReason").asText()).isEqualTo("invalid order payload");
    assertThat(lineage.at("/status").asText()).isEqualTo("rejected");
    assertThat(lineage.at("/remainingQuantity").asInt()).isZero();
    assertThat(lineage.at("/fills")).isEmpty();
    assertThat(lineage.at("/cancel").isNull()).isTrue();
  }

  @Test
  void executionTypeMeaningPresent() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(directTrade());

    JsonNode lineage = json("/cdm/orders/" + ORDER_ID + "/lineage");
    assertThat(lineage.at("/proposal/executionType").asText()).isEqualTo("OnVenue");
    assertThat(lineage.at("/proposal/executionTypeMeaning").asText())
        .isEqualTo("Sent to the TraderX matching engine (the venue, execution facility)");
    assertThat(lineage.at("/fills/0/executionType").asText()).isEqualTo("OnVenue");
    assertThat(lineage.at("/fills/0/executionTypeMeaning").asText())
        .isEqualTo("Executed on the TraderX matching engine (the venue, execution facility)");

    JsonNode fill = json("/cdm/trades/trd-017-0001");
    assertThat(fill.at("/executionType").asText()).isEqualTo("OnVenue");
    assertThat(fill.at("/executionTypeMeaning").asText())
        .isEqualTo("Executed on the TraderX matching engine (the venue, execution facility)");
    JsonNode direct = json("/cdm/trades/trd-017-0100");
    assertThat(direct.at("/executionType").asText()).isEqualTo("OffFacility");
    assertThat(direct.at("/executionTypeMeaning").asText())
        .isEqualTo("Booked bilaterally between the TraderX subsidiary and the client from the trade"
            + " ticket, with no venue");
  }

  // --- trades --------------------------------------------------------------------------------

  @Test
  void tradeLeadsWithTradeStateAndProducedBy() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill2());
    adapter.onBookedTrade(fill1());

    JsonNode trade = json("/cdm/trades/trd-017-0002");
    JsonNode order = json("/cdm/orders/" + ORDER_ID + "/lineage");

    assertThat(trade.at("/cdmRef").asText()).isEqualTo("cdm:trade:trd-017-0002");
    // The trade ref holds only the trade state: no stored event, no instruction ref.
    assertThat(trade.has("event")).isFalse();
    assertThat(trade.has("instructionRef")).isFalse();
    JsonNode state = trade.at("/tradeState");
    assertThat(state.at("/cdmType").asText()).isEqualTo(TradeState.class.getName());
    assertThat(state.at("/previousStoreGlobalKey").isNull()).isTrue();
    assertThat(state.at("/cdmGlobalKey").asText())
        .isEqualTo(state.at("/object/meta/globalKey").asText());
    assertThat(state.at("/storeGlobalKey").asText())
        .isEqualTo(order.at("/fills/1/tradeState/storeGlobalKey").asText());
    assertThat(CDM_JSON.treeToValue(state.at("/object"), TradeState.class)
        .getTrade().getTradeLot()).hasSize(1);

    JsonNode producedBy = trade.at("/producedBy");
    assertThat(producedBy.at("/kind").asText()).isEqualTo("fill");
    assertThat(producedBy.at("/cdmRef").asText()).isEqualTo(ORDER_REF);
    assertThat(producedBy.at("/orderId").asText()).isEqualTo(ORDER_ID);
    // Fill number by booking order, though this trade arrived first.
    assertThat(producedBy.at("/fillNumber").asInt()).isEqualTo(2);
    assertThat(producedBy.at("/label").asText()).isEqualTo("Final fill 2");
    assertThat(producedBy.at("/storeGlobalKey").asText())
        .isEqualTo(order.at("/fills/1/accepted/storeGlobalKey").asText());
    assertThat(producedBy.at("/lineage").asText())
        .isEqualTo("/cdm/orders/" + ORDER_ID + "/lineage");
  }

  @Test
  void directTradeProducedByDirectWorkflow() throws Exception {
    adapter.onBookedTrade(directTrade());

    JsonNode trade = json("/cdm/trades/trd-017-0100");
    JsonNode producedBy = trade.at("/producedBy");
    assertThat(producedBy.at("/kind").asText()).isEqualTo("direct");
    assertThat(producedBy.at("/cdmRef").asText()).isEqualTo("cdm:direct:trd-017-0100");
    assertThat(producedBy.at("/orderId").isNull()).isTrue();
    assertThat(producedBy.at("/fillNumber").isNull()).isTrue();
    assertThat(producedBy.at("/storeGlobalKey").asText()).hasSize(64);
    assertThat(producedBy.at("/lineage").asText()).isEqualTo("/cdm/trades/trd-017-0100/lineage");

    // cdm:direct:<tradeId> resolves to the direct trade's workflow: a CDM Workflow of its steps.
    String body = mvc.perform(get("/cdm/resolve/cdm:direct:trd-017-0100"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-CDM-Type", Workflow.class.getName()))
        .andExpect(header().string("X-CDM-Validation", "VALID"))
        .andExpect(header().string("Link",
            "</cdm/trades/trd-017-0100/lineage>; rel=\"lineage\""))
        .andReturn().getResponse().getContentAsString();
    Workflow workflow = CDM_JSON.readValue(body, Workflow.class);
    assertThat(workflow.getSteps()).hasSize(2);
    assertThat(workflow.getSteps().get(0).getProposedEvent()).isNotNull();
    assertThat(workflow.getSteps().get(1).getBusinessEvent()).isNotNull();
    assertThat(workflow.getSteps().get(1).getMeta().getGlobalKey()).isNotNull();
    mvc.perform(get("/cdm/resolve/cdm:direct:trd-not-yet"))
        .andExpect(status().isNotFound())
        .andExpect(content().json(
            "{\"cdmRef\":\"cdm:direct:trd-not-yet\",\"status\":\"pending\"}",
            JsonCompareMode.STRICT));
  }

  @Test
  void unknownTradeIsPendingNotFound() throws Exception {
    mvc.perform(get("/cdm/trades/trd-not-yet"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.cdmRef").value("cdm:trade:trd-not-yet"))
        .andExpect(jsonPath("$.status").value("pending"));
  }

  // --- positions -----------------------------------------------------------------------------

  @Test
  void cdmPositionsAreAValidatedPortfolioState() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(directTrade());

    JsonNode body = json("/cdm/positions/" + ACCOUNT);

    assertThat(body.at("/cdmRef").asText()).isEqualTo("cdm:position:22214");
    assertThat(body.at("/cdmType").asText()).isEqualTo(PortfolioState.class.getName());
    assertThat(body.at("/validation/status").asText()).isEqualTo("VALID");
    PortfolioState state = CDM_JSON.treeToValue(body.at("/object"), PortfolioState.class);
    assertThat(state.getPositions()).hasSize(1);
    assertThat(body.at("/trades")).hasSize(2);
    assertThat(body.at("/skipped")).isEmpty();
    assertThat(body.at("/positions/0/quantity").asInt()).isEqualTo(550);
    assertThat(body.at("/initialState/object/lineage").isObject()).isTrue();

    // R29: both kinds of key, and clients can join the CDM lineage on the CDM keys.
    assertThat(state.getMeta().getGlobalKey()).isNotBlank();
    assertThat(body.at("/cdmGlobalKey").asText())
        .isEqualTo(state.getMeta().getGlobalKey());
    assertThat(body.at("/storeGlobalKey").asText()).hasSize(64)
        .isNotEqualTo(body.at("/cdmGlobalKey").asText());
    assertThat(state.getLineage().getPortfolioStateReference().get(0).getExternalReference())
        .isEqualTo(body.at("/initialState/cdmExternalKey").asText()).isEqualTo("22214:initial");
    assertThat(body.at("/trades").findValuesAsText("tradeCdmGlobalKey"))
        .hasSize(2).allSatisfy(k -> assertThat(k).isNotBlank().isNotEqualTo("null"));
    assertThat(body.at("/trades").findValuesAsText("executionStepCdmGlobalKey"))
        .hasSize(2).allSatisfy(k -> assertThat(k).isNotBlank().isNotEqualTo("null"));
    assertThat(state.getLineage().getTradeReference())
        .extracting(r -> r.getGlobalReference())
        .containsExactlyElementsOf(body.at("/trades").findValuesAsText("tradeCdmGlobalKey"));
    assertThat(state.getLineage().getEventReference())
        .extracting(r -> r.getGlobalReference())
        .containsExactlyElementsOf(
            body.at("/trades").findValuesAsText("executionStepCdmGlobalKey"));
    // The store key of a contribution navigates to the trade's stored trade state.
    JsonNode trade = json("/cdm/trades/trd-017-0001");
    assertThat(body.at("/trades/0/storeGlobalKey").asText())
        .isEqualTo(trade.at("/tradeState/storeGlobalKey").asText());
    assertThat(body.at("/trades/0/cdmGlobalKey").asText())
        .isEqualTo(trade.at("/tradeState/cdmGlobalKey").asText());
  }

  @Test
  void positionTradesSumToQuantity() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill2());
    adapter.onBookedTrade(directTrade());

    JsonNode position = json("/cdm/positions/" + ACCOUNT).at("/positions/0");

    assertThat(position.at("/security").asText()).isEqualTo("IBM");
    assertThat(position.at("/cdmRef").asText()).isEqualTo("cdm:position:22214:IBM");
    assertThat(position.at("/quantity").asInt()).isEqualTo(1300);
    JsonNode trades = position.at("/trades");
    assertThat(trades.findValuesAsText("tradeId"))
        .containsExactly("trd-017-0001", "trd-017-0002", "trd-017-0100");
    assertThat(trades.findValues("signedQuantity")).extracting(JsonNode::asInt)
        .containsExactly(750, 750, -200);
    assertThat(trades.findValuesAsText("side")).containsExactly("Buy", "Buy", "Sell");
    int sum = 0;
    for (JsonNode t : trades) {
      sum += t.at("/signedQuantity").asInt();
    }
    assertThat(sum).isEqualTo(position.at("/quantity").asInt());
    assertThat(trades.get(2).at("/cdmRef").asText()).isEqualTo("cdm:trade:trd-017-0100");
    assertThat(trades.get(2).at("/price").decimalValue()).isEqualByComparingTo("181.25");
    assertThat(trades.get(2).at("/executedAt").asText()).isEqualTo("2026-10-29T15:00:00Z");
  }

  @Test
  void accountWithoutCdmTradesIsPendingNotFound() throws Exception {
    for (String url : new String[] {"/cdm/positions/" + ACCOUNT, "/cdm/positions/99999",
        "/cdm/resolve/cdm:position:" + ACCOUNT}) {
      mvc.perform(get(url))
          .andExpect(status().isNotFound())
          .andExpect(content().json("{\"cdmRef\":\"cdm:position:" + url.replaceAll(".*[/:]", "")
              + "\",\"status\":\"pending\"}", JsonCompareMode.STRICT));
    }
  }

  @Test
  void nonNumericAccountIsBadRequest() throws Exception {
    mvc.perform(get("/cdm/positions/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.cdmRef").value("cdm:position:abc"))
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void positionProjectionIsThe009ShapePlusCdmRef() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());

    mvc.perform(get("/projections/positions/" + ACCOUNT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].accountId").value(ACCOUNT))
        .andExpect(jsonPath("$[0].security").value("IBM"))
        .andExpect(jsonPath("$[0].quantity").value(750))
        .andExpect(jsonPath("$[0].averageCostBasis").value(179.5))
        .andExpect(jsonPath("$[0].updated").exists())
        .andExpect(jsonPath("$[0].cdmRef").value("cdm:position:22214:IBM"));
    mvc.perform(get("/projections/positions/52355"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void refHelpersAgree() {
    assertThat(PositionView.positionRef(ACCOUNT, "IBM")).isEqualTo(
        CdmRef.parse("cdm:position:22214:IBM"));
  }
  @Test
  void worstValidationRanksInvalidWorstThenNotQualifiedThenValid() {
    assertThat(LineageController.worst(java.util.stream.Stream.of(
        Status.VALID, Status.INVALID, Status.NOT_QUALIFIED))).isEqualTo(Status.INVALID);
    assertThat(LineageController.worst(java.util.stream.Stream.of(
        Status.NOT_QUALIFIED, Status.INVALID))).isEqualTo(Status.INVALID);
    assertThat(LineageController.worst(java.util.stream.Stream.of(
        Status.VALID, Status.NOT_QUALIFIED))).isEqualTo(Status.NOT_QUALIFIED);
    assertThat(LineageController.worst(java.util.stream.Stream.of(Status.VALID)))
        .isEqualTo(Status.VALID);
  }

  @Test
  void directWorkflowValidationIsTheWorstOfItsSteps() throws Exception {
    adapter.onBookedTrade(directTrade());
    CdmRef direct = CdmRef.parse("cdm:direct:trd-017-0100");
    String accepted = store.latest(direct, WorkflowStep.class).orElseThrow().globalKey();
    store.revalidate(direct, WorkflowStep.class, accepted,
        new CdmValidationResult(Status.INVALID, List.of("broken")));

    mvc.perform(get("/cdm/resolve/cdm:direct:trd-017-0100"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-CDM-Validation", "INVALID"));
  }

  @Test
  void missingExecutionTimeIsJsonNullNotTheStringNull() {
    ObjectNode node = CDM_JSON.createObjectNode();
    LineageController.putInstant(node, "executedAt", null);
    assertThat(node.get("executedAt").isNull()).isTrue();
    LineageController.putInstant(node, "executedAt", Instant.parse("2026-10-29T15:00:00Z"));
    assertThat(node.get("executedAt").asText()).isEqualTo("2026-10-29T15:00:00Z");
  }

  @Test
  void tradeStateWithoutAProducerStillHasAnExecutionTime() throws Exception {
    adapter.onBookedTrade(directTrade());
    TradeState state = CDM_JSON.readValue(store.latest(
        CdmRef.parse("cdm:trade:trd-017-0100"), TradeState.class).orElseThrow().json(),
        TradeState.class);
    // Stored with no producer: positions fall back to the row's write time.
    store.putNext(CdmRef.parse("cdm:trade:trd-orphan"), state,
        new CdmValidationResult(Status.VALID, List.of()));

    JsonNode body = json("/cdm/positions/" + ACCOUNT);
    for (JsonNode t : body.at("/trades")) {
      assertThat(t.at("/executedAt").isTextual()).isTrue();
      assertThat(t.at("/executedAt").asText()).isNotEqualTo("null");
    }
    for (JsonNode t : body.at("/positions/0/trades")) {
      assertThat(t.at("/executedAt").isTextual()).isTrue();
      assertThat(t.at("/executedAt").asText()).isNotEqualTo("null");
    }
    assertThat(body.at("/positions/0/trades").findValuesAsText("tradeId"))
        .contains("trd-orphan");
  }
}
