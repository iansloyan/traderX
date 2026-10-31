package finos.traderx.cdmservice.api;

import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.event.common.ExecutionInstruction;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.WorkflowStepApproval;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.cdmservice.lifecycle.LifecycleFailureRepository;
import finos.traderx.cdmservice.lifecycle.PendingCancelRepository;
import finos.traderx.cdmservice.lifecycle.PendingTradeRepository;
import finos.traderx.cdmservice.lifecycle.SeedTradeBackfill;
import finos.traderx.cdmservice.lifecycle.WorkflowMapper;
import finos.traderx.cdmservice.store.CdmDocument;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic read APIs over the CDM store (Task 13), used by the state 017 scenario script.
 *
 * <ul>
 *   <li>{@code GET /cdm/rejections[?accountId=]}: the rejected order requests (a proposal and
 *       its rejected {@link WorkflowStep} are stored under {@code cdm:order:<rejectionId>}),
 *       oldest first, optionally only those whose account (the instruction's {@code Party1}) is
 *       {@code accountId}. {@code reason} is the venue's refusal ({@code WorkflowStepApproval}
 *       {@code approved} false) {@code rejectedReason}, else the rejected step's workflow state
 *       comment.</li>
 *   <li>{@code GET /cdm/validation-summary}: validation counts by status, over every distinct
 *       stored object version ({@code objects}: one per ref, type and store global key, with its
 *       latest result) and over the latest version per ref and type ({@code latest}); the first
 *       {@value #NOT_VALID_LIMIT} objects that are not {@code VALID}; the lifecycle failure row
 *       count; the parked (pending) trades and cancels; and the startup backfill's progress
 *       ({@code backfill.complete} is true once the seed trades and open matcher orders are
 *       in, along with order fills missed while cdm-service was down, so callers can wait for it
 *       before taking a baseline). Positions are derived on read and
 *       not stored, so they are not counted here; {@code /cdm/positions/{accountId}} returns
 *       their validation.</li>
 * </ul>
 */
@RestController
public class DiagnosticsController {

  static final int NOT_VALID_LIMIT = 50;
  private static final String WORKFLOW_STEP = WorkflowStep.class.getName();

  private final CdmStore store;
  private final CdmDocumentRepository documents;
  private final LifecycleFailureRepository failures;
  private final PendingTradeRepository pendingTrades;
  private final PendingCancelRepository pendingCancels;
  private final ObjectMapper cdmObjectMapper;
  private final Supplier<SeedTradeBackfill.Status> backfill;
  private final CdmApiSupport support;

  @Autowired
  public DiagnosticsController(CdmStore store, CdmDocumentRepository documents,
      LifecycleFailureRepository failures, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper, SeedTradeBackfill backfill) {
    this(store, documents, failures, pendingTrades, pendingCancels, cdmObjectMapper,
        backfill::status);
  }

  DiagnosticsController(CdmStore store, CdmDocumentRepository documents,
      LifecycleFailureRepository failures, PendingTradeRepository pendingTrades,
      PendingCancelRepository pendingCancels, ObjectMapper cdmObjectMapper,
      Supplier<SeedTradeBackfill.Status> backfill) {
    this.store = store;
    this.documents = documents;
    this.failures = failures;
    this.pendingTrades = pendingTrades;
    this.pendingCancels = pendingCancels;
    this.cdmObjectMapper = cdmObjectMapper;
    this.backfill = backfill;
    this.support = new CdmApiSupport(store, cdmObjectMapper);
  }

  // --- rejections ----------------------------------------------------------------------------

  @GetMapping("/cdm/rejections")
  public ResponseEntity<String> rejections(
      @RequestParam(value = "accountId", required = false) Integer accountId) {
    ObjectNode body = cdmObjectMapper.createObjectNode();
    if (accountId == null) {
      body.putNull("accountId");
    } else {
      body.put("accountId", accountId);
    }
    ArrayNode list = cdmObjectMapper.createArrayNode();
    for (StoredCdm stored : store.latestOfType(WorkflowStep.class)) {
      WorkflowStep step = read(stored);
      if (step == null || !"rejected".equals(CdmApiSupport.stepType(step))) {
        continue;
      }
      String account = accountKey(WorkflowMapper.executionInstruction(step));
      if (accountId != null && !String.valueOf(accountId).equals(account)) {
        continue;
      }
      ObjectNode node = list.addObject();
      String rejectionId = stored.ref().key();
      node.put("rejectionId", rejectionId);
      node.put("cdmRef", stored.ref().toString());
      Integer parsedAccount = parseInt(account);
      if (parsedAccount == null) {
        node.putNull("accountId");
      } else {
        node.put("accountId", parsedAccount);
      }
      node.put("quantity", WorkflowMapper.instructionQuantity(step));
      WorkflowStepApproval refusal = refusal(step);
      node.put("reason", refusal != null && refusal.getRejectedReason() != null
          ? refusal.getRejectedReason()
          : step.getWorkflowState() == null ? null : step.getWorkflowState().getComment());
      node.put("rejectedAt", CdmApiSupport.timestamp(step));
      node.put("storeGlobalKey", stored.globalKey());
      node.put("cdmGlobalKey", step.getMeta() == null ? null : step.getMeta().getGlobalKey());
      node.set("validation", support.validationNode(stored.validation()));
      node.put("lineage", "/cdm/orders/" + rejectionId + "/lineage");
    }
    body.put("count", list.size());
    body.set("rejections", list);
    return json(body);
  }

  /** The venue's refusal ({@code approved} false) the rejected step carries, or null. */
  private static WorkflowStepApproval refusal(WorkflowStep step) {
    return step.getApproval() == null ? null : step.getApproval().stream()
        .filter(a -> Boolean.FALSE.equals(a.getApproved()))
        .map(a -> (WorkflowStepApproval) a)
        .reduce((a, b) -> b).orElse(null);
  }

  private static String accountKey(ExecutionInstruction instruction) {
    if (instruction == null || instruction.getCounterparty() == null) {
      return null;
    }
    return instruction.getCounterparty().stream()
        .filter(c -> c.getRole() == CounterpartyRoleEnum.PARTY_1)
        .map(Counterparty::getPartyReference)
        .filter(r -> r != null)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
  }

  private static Integer parseInt(String text) {
    try {
      return text == null ? null : Integer.valueOf(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private WorkflowStep read(StoredCdm stored) {
    if (!WORKFLOW_STEP.equals(stored.cdmType())) {
      return null;
    }
    try {
      return support.read(stored, WorkflowStep.class);
    } catch (IllegalStateException e) {
      return null; // unreadable rows are not rejections we can describe; lineage shows them
    }
  }

  // --- validation summary --------------------------------------------------------------------

  @GetMapping("/cdm/validation-summary")
  public ResponseEntity<String> validationSummary() {
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.set("objects", counts(documents.countObjectsByTypeAndValidationStatus()));
    body.set("latest", counts(documents.countLatestByTypeAndValidationStatus()));
    ArrayNode notValid = body.putArray("notValid");
    for (CdmDocument doc : documents.findObjectsNotValid(PageRequest.of(0, NOT_VALID_LIMIT))) {
      ObjectNode node = notValid.addObject();
      node.put("cdmRef", doc.getCdmRef());
      node.put("cdmType", doc.getCdmType());
      node.put("storeGlobalKey", doc.getGlobalKey());
      node.put("status", doc.getValidationStatus());
      node.set("messages", messages(doc.getValidationMessages()));
    }
    ObjectNode failureNode = body.putObject("failures");
    failureNode.put("total", failures.count());
    ArrayNode recent = failureNode.putArray("recent");
    failures.findAll(PageRequest.of(0, NOT_VALID_LIMIT, Sort.by("id").descending()))
        .forEach(f -> recent.addObject()
            .put("id", f.getId())
            .put("cdmRef", f.getCdmRef())
            .put("stage", f.getStage())
            .put("message", f.getMessage())
            .put("failedAt", String.valueOf(f.getFailedAt())));
    ObjectNode pending = body.putObject("pending");
    pending.put("trades", pendingTrades.count());
    pending.put("cancels", pendingCancels.count());
    SeedTradeBackfill.Status progress = backfill.get();
    body.putObject("backfill")
        .put("directTrades", progress.directTrades())
        .put("openOrders", progress.openOrders())
        .put("orderFills", progress.orderFills())
        .put("canceledOrders", progress.canceledOrders())
        .put("complete", progress.complete());
    return json(body);
  }

  /** {@code {total, byStatus{VALID,INVALID,NOT_QUALIFIED}, byType{type{status: n}}}}. */
  private ObjectNode counts(List<Object[]> rows) {
    Map<String, Long> byStatus = new TreeMap<>();
    for (CdmValidationResult.Status status : CdmValidationResult.Status.values()) {
      byStatus.put(status.name(), 0L);
    }
    Map<String, Map<String, Long>> byType = new TreeMap<>();
    long total = 0;
    for (Object[] row : rows) {
      String type = (String) row[0];
      String status = (String) row[1];
      long count = ((Number) row[2]).longValue();
      total += count;
      byStatus.merge(status, count, Long::sum);
      byType.computeIfAbsent(type, t -> new TreeMap<>()).merge(status, count, Long::sum);
    }
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("total", total);
    node.set("byStatus", cdmObjectMapper.valueToTree(byStatus));
    node.set("byType", cdmObjectMapper.valueToTree(byType));
    return node;
  }

  private JsonNode messages(String json) {
    if (json == null) {
      return cdmObjectMapper.createArrayNode();
    }
    try {
      return cdmObjectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      return cdmObjectMapper.createArrayNode().add(json);
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  private ResponseEntity<String> json(ObjectNode body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.toString());
  }
}
