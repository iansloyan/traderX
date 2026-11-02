package finos.traderx.cdmservice.api;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT_PARTY;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CDM_JSON;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.IBM;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ORDER_ID;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.bd;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.directTrade;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill1;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.rejectedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cdm.base.staticdata.asset.common.Security;
import cdm.event.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.cdmservice.lifecycle.LifecycleAdapter;
import finos.traderx.cdmservice.lifecycle.LifecycleFailure;
import finos.traderx.cdmservice.lifecycle.LifecycleFailureRepository;
import finos.traderx.cdmservice.lifecycle.LifecycleFixtures;
import finos.traderx.cdmservice.lifecycle.PendingCancelRepository;
import finos.traderx.cdmservice.lifecycle.PendingTrade;
import finos.traderx.cdmservice.lifecycle.PendingTradeRepository;
import finos.traderx.cdmservice.lifecycle.RejectedOrder;
import finos.traderx.cdmservice.lifecycle.SeedTradeBackfill;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The diagnostics endpoints (rejections, validation summary) and the trade lineage endpoint
 * over MockMvc, against what the real lifecycle adapter stored on H2 (same set-up as
 * {@link LineageControllerTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagnosticsControllerTest {

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

  private CdmStore store;
  private LifecycleAdapter adapter;
  private MockMvc mvc;
  private SeedTradeBackfill.Status backfillStatus =
      new SeedTradeBackfill.Status("done", "done", "done", "done");

  @BeforeEach
  void setUp() {
    clean();
    store = new CdmStore(documents, CDM_JSON, transactionManager, Clock.systemUTC());
    adapter = LifecycleFixtures.adapter(store, pendingTrades, pendingCancels, failureRows);
    mvc = MockMvcBuilders.standaloneSetup(
        new DiagnosticsController(store, documents, failureRows, pendingTrades, pendingCancels,
            CDM_JSON, () -> backfillStatus),
        new TradeLineageController(store, CDM_JSON)).build();
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

  @Test
  void rejectionsListedByAccount() throws Exception {
    adapter.onOrder(newOrder()); // an accepted order is not a rejection
    adapter.onRejected(rejectedOrder()); // account 22214, quantity 1500
    adapter.onRejected(new RejectedOrder("rej-017-0002", 52355, "IBM", "Buy", 0, bd("100"),
        "invalid order payload", Instant.parse("2026-10-29T14:29:30Z")));

    JsonNode mine = json("/cdm/rejections?accountId=22214");
    assertThat(mine.at("/accountId").asInt()).isEqualTo(22214);
    assertThat(mine.at("/count").asInt()).isEqualTo(1);
    JsonNode rejection = mine.at("/rejections/0");
    assertThat(rejection.at("/rejectionId").asText()).isEqualTo("rej-017-0001");
    assertThat(rejection.at("/cdmRef").asText()).isEqualTo("cdm:order:rej-017-0001");
    assertThat(rejection.at("/accountId").asInt()).isEqualTo(22214);
    assertThat(rejection.at("/quantity").asInt()).isEqualTo(1500);
    assertThat(rejection.at("/reason").asText()).isEqualTo("invalid order payload");
    assertThat(rejection.at("/rejectedAt").asText()).isEqualTo("2026-10-29T14:29:00Z");
    assertThat(rejection.at("/validation/status").asText()).isEqualTo("VALID");
    assertThat(rejection.at("/storeGlobalKey").asText()).isNotBlank();
    assertThat(rejection.at("/lineage").asText())
        .isEqualTo("/cdm/orders/rej-017-0001/lineage");

    JsonNode other = json("/cdm/rejections?accountId=52355");
    assertThat(other.at("/count").asInt()).isEqualTo(1);
    assertThat(other.at("/rejections/0/rejectionId").asText()).isEqualTo("rej-017-0002");
    assertThat(other.at("/rejections/0/quantity").asInt()).isZero();

    assertThat(json("/cdm/rejections?accountId=99999").at("/count").asInt()).isZero();
    JsonNode all = json("/cdm/rejections");
    assertThat(all.at("/accountId").isNull()).isTrue();
    assertThat(all.at("/rejections").findValuesAsText("rejectionId"))
        .containsExactly("rej-017-0001", "rej-017-0002");
  }

  @Test
  void validationSummaryCountsByStatus() throws Exception {
    adapter.onOrder(newOrder()); // proposed + approved versions under one ref, both VALID
    store.put(new CdmRef(CdmRef.Kind.PARTY, "99"), ACCOUNT_PARTY, null,
        new CdmValidationResult(CdmValidationResult.Status.INVALID, List.of("broken party")));
    StoredCdm security = store.put(new CdmRef(CdmRef.Kind.SECURITY, "IBM"), IBM, null,
        new CdmValidationResult(CdmValidationResult.Status.VALID, List.of()));
    // A re-validation supersedes the earlier result for the same content.
    store.revalidate(new CdmRef(CdmRef.Kind.SECURITY, "IBM"), Security.class,
        security.globalKey(), new CdmValidationResult(
            CdmValidationResult.Status.NOT_QUALIFIED, List.of("Qualify_Execution=false")));
    failureRows.save(new LifecycleFailure("cdm:order:ord-x", "order", "boom", "{}",
        Instant.parse("2026-10-29T14:00:00Z")));
    pendingTrades.save(new PendingTrade("trd-parked", "ord-parked", "{}",
        Instant.parse("2026-10-29T14:00:00Z")));

    JsonNode summary = json("/cdm/validation-summary");

    assertThat(summary.at("/objects/total").asLong()).isEqualTo(4);
    assertThat(summary.at("/objects/byStatus/VALID").asLong()).isEqualTo(2);
    assertThat(summary.at("/objects/byStatus/INVALID").asLong()).isEqualTo(1);
    assertThat(summary.at("/objects/byStatus/NOT_QUALIFIED").asLong()).isEqualTo(1);
    assertThat(summary.at("/objects/byType/cdm.event.workflow.WorkflowStep/VALID").asLong())
        .isEqualTo(2);
    assertThat(summary.at("/latest/total").asLong()).isEqualTo(3);
    assertThat(summary.at("/latest/byStatus/VALID").asLong()).isEqualTo(1);
    assertThat(summary.at("/latest/byStatus/INVALID").asLong()).isEqualTo(1);
    assertThat(summary.at("/latest/byStatus/NOT_QUALIFIED").asLong()).isEqualTo(1);
    assertThat(summary.at("/notValid").findValuesAsText("status"))
        .containsExactly("INVALID", "NOT_QUALIFIED");
    assertThat(summary.at("/notValid/0/cdmRef").asText()).isEqualTo("cdm:party:99");
    assertThat(summary.at("/notValid/0/messages/0").asText()).isEqualTo("broken party");
    assertThat(summary.at("/failures/total").asLong()).isEqualTo(1);
    assertThat(summary.at("/failures/recent/0/cdmRef").asText()).isEqualTo("cdm:order:ord-x");
    assertThat(summary.at("/failures/recent/0/stage").asText()).isEqualTo("order");
    assertThat(summary.at("/pending/trades").asLong()).isEqualTo(1);
    assertThat(summary.at("/pending/cancels").asLong()).isZero();
    assertThat(summary.at("/backfill/complete").asBoolean()).isTrue();
  }

  @Test
  void validationSummaryReportsBackfillProgress() throws Exception {
    backfillStatus = new SeedTradeBackfill.Status("done", "done", "running", "running");
    JsonNode running = json("/cdm/validation-summary").at("/backfill");
    assertThat(running.at("/directTrades").asText()).isEqualTo("done");
    assertThat(running.at("/openOrders").asText()).isEqualTo("done");
    assertThat(running.at("/orderFills").asText()).isEqualTo("running");
    assertThat(running.at("/canceledOrders").asText()).isEqualTo("running");
    assertThat(running.at("/complete").asBoolean()).isFalse();

    backfillStatus = SeedTradeBackfill.Status.DISABLED;
    assertThat(json("/cdm/validation-summary").at("/backfill/complete").asBoolean()).isTrue();
  }

  @Test
  void validationSummaryOfAnEmptyStoreHasZeroCounts() throws Exception {
    JsonNode summary = json("/cdm/validation-summary");
    assertThat(summary.at("/objects/total").asLong()).isZero();
    assertThat(summary.at("/objects/byStatus/INVALID").asLong()).isZero();
    assertThat(summary.at("/objects/byStatus/VALID").asLong()).isZero();
    assertThat(summary.at("/notValid")).isEmpty();
    assertThat(summary.at("/failures/total").asLong()).isZero();
    assertThat(summary.at("/failures/recent")).isEmpty();
  }

  @Test
  void rejectionReasonIsTheVenuesRejectedReason() throws Exception {
    // The rejected step carries the venue's approval (approved false, rejectedReason); the
    // reason is read from it, the workflow state comment being only a fallback.
    WorkflowStep rejected = LifecycleFixtures.WORKFLOW.rejected(rejectedOrder());
    WorkflowStep withoutComment = rejected.toBuilder().setWorkflowState(
        rejected.getWorkflowState().toBuilder().setComment(null)).build();
    store.put(new CdmRef(CdmRef.Kind.ORDER, "rej-017-0001"), withoutComment, null,
        new CdmValidationResult(CdmValidationResult.Status.VALID, List.of()));

    JsonNode rejection = json("/cdm/rejections").at("/rejections/0");

    assertThat(rejection.at("/reason").asText()).isEqualTo("invalid order payload");
    assertThat(rejection.at("/rejectedAt").asText()).isEqualTo("2026-10-29T14:29:00Z");
  }

  @Test
  void tradeLineageOfDirectTradeLeadsWithTradeStateThenItsWorkflow() throws Exception {
    adapter.onBookedTrade(directTrade());

    JsonNode lineage = json("/cdm/trades/trd-017-0100/lineage");

    assertThat(lineage.at("/cdmRef").asText()).isEqualTo("cdm:trade:trd-017-0100");
    assertThat(lineage.at("/tradeId").asText()).isEqualTo("trd-017-0100");
    // The trade state first.
    assertThat(lineage.fieldNames().next()).isEqualTo("cdmRef");
    JsonNode state = lineage.at("/tradeState");
    assertThat(state.at("/cdmRef").asText()).isEqualTo("cdm:trade:trd-017-0100");
    assertThat(state.at("/storeGlobalKey").asText()).hasSize(64);
    assertThat(state.at("/positionState").asText()).isEqualTo("Executed");
    assertThat(state.at("/quantity").asInt()).isEqualTo(200);
    assertThat(state.at("/price").decimalValue()).isEqualByComparingTo("181.25");
    assertThat(state.at("/side").asText()).isEqualTo("Sell");
    assertThat(state.at("/security/ticker").asText()).isEqualTo("IBM");
    assertThat(state.at("/account/cdmRef").asText()).isEqualTo("cdm:party:22214");
    assertThat(state.at("/validation/status").asText()).isEqualTo("VALID");
    assertThat(lineage.at("/executionType").asText()).isEqualTo("OffFacility");
    assertThat(lineage.at("/executionTypeMeaning").asText())
        .isEqualTo("Booked bilaterally between the TraderX subsidiary and the client from the trade"
            + " ticket, with no venue");

    // Then what produced it: the direct workflow under cdm:direct, proposed then accepted.
    JsonNode producedBy = lineage.at("/producedBy");
    assertThat(producedBy.at("/kind").asText()).isEqualTo("direct");
    assertThat(producedBy.at("/cdmRef").asText()).isEqualTo("cdm:direct:trd-017-0100");
    assertThat(producedBy.at("/orderId").isNull()).isTrue();
    assertThat(producedBy.at("/fillNumber").isNull()).isTrue();
    assertThat(producedBy.at("/proposed/stepType").asText()).isEqualTo("proposed");
    assertThat(producedBy.at("/accepted/stepType").asText()).isEqualTo("accepted");
    assertThat(producedBy.at("/accepted/storeGlobalKey").asText())
        .isEqualTo(producedBy.at("/storeGlobalKey").asText());
    assertThat(producedBy.at("/accepted/previousStoreGlobalKey").asText())
        .isEqualTo(producedBy.at("/proposed/storeGlobalKey").asText());
    assertThat(producedBy.at("/proposed/validation/status").asText()).isEqualTo("VALID");
    assertThat(producedBy.at("/accepted/validation/status").asText()).isEqualTo("VALID");
    assertThat(producedBy.at("/proposed/object/proposedEvent").isObject()).isTrue();
    assertThat(producedBy.at("/accepted/object/businessEvent/after").isArray()).isTrue();
    // Removed: the flat steps list, the stored event and the instruction ref.
    assertThat(lineage.has("steps")).isFalse();
    assertThat(lineage.has("event")).isFalse();
    assertThat(lineage.has("instructionRef")).isFalse();
  }

  @Test
  void tradeLineageOfOrderFillGivesTheFillAndItsOrder() throws Exception {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());

    JsonNode lineage = json("/cdm/trades/trd-017-0001/lineage");

    assertThat(lineage.at("/tradeState/quantity").asInt()).isEqualTo(750);
    assertThat(lineage.at("/tradeState/side").asText()).isEqualTo("Buy");
    assertThat(lineage.at("/executionType").asText()).isEqualTo("OnVenue");
    JsonNode producedBy = lineage.at("/producedBy");
    assertThat(producedBy.at("/kind").asText()).isEqualTo("fill");
    assertThat(producedBy.at("/cdmRef").asText()).isEqualTo("cdm:order:" + ORDER_ID);
    assertThat(producedBy.at("/orderId").asText()).isEqualTo(ORDER_ID);
    assertThat(producedBy.at("/fillNumber").asInt()).isEqualTo(1);
    assertThat(producedBy.at("/label").asText()).isEqualTo("Partial fill 1");
    assertThat(producedBy.at("/lineage").asText())
        .isEqualTo("/cdm/orders/" + ORDER_ID + "/lineage");
    assertThat(producedBy.at("/proposed/stepType").asText()).isEqualTo("fill-proposed");
    assertThat(producedBy.at("/accepted/stepType").asText()).isEqualTo("fill-accepted");
    assertThat(producedBy.at("/accepted/storeGlobalKey").asText())
        .isEqualTo(producedBy.at("/storeGlobalKey").asText());

    mvc.perform(get("/cdm/trades/trd-unknown/lineage"))
        .andExpect(status().isNotFound());
  }

  @Test
  void tradeLineageOfUnlinkedTradeStateHasNoProducer() throws Exception {
    // A trade state stored with no producer (as a damaged store might hold).
    var fill = LifecycleFixtures.EXECUTION.fill(LifecycleFixtures.WORKFLOW.proposed(newOrder()),
        fill1());
    store.put(new CdmRef(CdmRef.Kind.TRADE, "trd-orphan"), fill.after(), null,
        new CdmValidationResult(CdmValidationResult.Status.VALID, List.of()));

    JsonNode lineage = json("/cdm/trades/trd-orphan/lineage");

    assertThat(lineage.at("/tradeState/storeGlobalKey").asText()).hasSize(64);
    assertThat(lineage.at("/producedBy").isNull()).isTrue();
  }
}
