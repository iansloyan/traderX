package finos.traderx.cdmservice.api;

import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.TradeState;
import cdm.event.workflow.Workflow;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.WorkflowStepApproval;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import finos.traderx.cdmservice.lifecycle.LifecycleFailure;
import finos.traderx.cdmservice.lifecycle.LifecycleFailureRepository;
import finos.traderx.cdmservice.lifecycle.PendingCancelRepository;
import finos.traderx.cdmservice.lifecycle.PendingTrade;
import finos.traderx.cdmservice.lifecycle.PendingTradeRepository;
import finos.traderx.cdmservice.lifecycle.WorkflowMapper;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.position.PositionProjection;
import finos.traderx.cdmservice.position.PositionView;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.security.SecurityMaster;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * Read APIs over the stored CDM lifecycle (Task 11). CDM objects are returned as the stored
 * Rosetta JSON verbatim (written by {@code cdmObjectMapper}; Spring MVC's Jackson 3 mapper is
 * not used for them), so each object's canonical form hashes back to its {@code globalKey}.
 *
 * <ul>
 *   <li>{@code GET /cdm/resolve/{cdmRef}}: the full latest CDM object of any ref. {@code party},
 *       {@code person} and {@code security} come from the masters (a party with its full
 *       persons; a person as the person master holds it); {@code order} is the latest
 *       {@link WorkflowStep} of the order's own workflow: its cancel, else its latest proposal
 *       version (approved or rejected), never a fill's step (each fill is its own workflow;
 *       the whole lineage is at {@code /cdm/orders/{orderId}/lineage},
 *       also given in a {@code Link} header); {@code trade} its latest {@link TradeState};
 *       {@code direct} the direct trade's workflow as a CDM {@link Workflow} of its stored steps
 *       (built on read; {@code Link} to {@code /cdm/trades/{tradeId}/lineage});
 *       {@code position} the account's CDM {@code PortfolioState}
 *       ({@code cdm:position:<accountId>}) or one CDM {@code Position}
 *       ({@code cdm:position:<accountId>:<security>}). Objects read from the store carry
 *       {@code X-CDM-Type}, {@code X-CDM-Global-Key} (the <b>store</b> key, see below) and
 *       {@code X-CDM-Validation} headers. A
 *       well-formed ref with no object yet (the lifecycle adapter has not caught up) is 404
 *       {@code {"cdmRef":..., "status":"pending"}}; a malformed ref is 400.</li>
 *   <li>{@code GET /cdm/orders/{orderId}/lineage}: the order as {@link OrderLineage} sorts it:
 *       {@code proposal} (its execution type, its {@code versions} as submitted / approved /
 *       rejected, and the venue's {@code approval}), {@code fills} (each fill's number and
 *       label by booking order, its proposed and accepted steps and the trade state it
 *       produced), {@code cancel}, the quantities and status, parties, security, and the
 *       lifecycle failures and parked events recorded for it.
 *       {@code remainingQuantity = instructionQuantity - executedQuantity - canceledQuantity}
 *       (0 for a rejected request); {@code canceledQuantity} is the matcher's, as the cancel
 *       step records it.</li>
 *   <li>{@code GET /cdm/trades/{tradeId}}: the trade's {@link TradeState} ({@code tradeState},
 *       with its object), {@code producedBy} (the accepted step that executed it: a fill of an
 *       order, with its fill number, or a direct trade's workflow) and its execution type with
 *       what it means here.</li>
 *   <li>{@code GET /cdm/positions/{accountId}}: the account's CDM {@code PortfolioState}
 *       derived from stored trade states ({@link PositionView}), with its validation; 404
 *       pending when the account has no CDM trades (Ruling R30: CDM 7.8.0 cannot validate an
 *       empty portfolio state). Each of its {@code positions} lists the {@code trades} (trade
 *       states, signed quantities) that sum to it.</li>
 *   <li>{@code GET /projections/positions/{accountId}}: the same positions in 009
 *       position-service's shape plus {@code cdmRef}.</li>
 * </ul>
 *
 * <p><b>Two kinds of global key (Ruling R29).</b> {@code storeGlobalKey} (and
 * {@code previousStoreGlobalKey}, the approval's {@code stepStoreGlobalKey}, header
 * {@code X-CDM-Global-Key}) is the store's SHA-256 of the object's canonical JSON: the key
 * {@code cdm_documents} links versions by and these APIs navigate by. {@code cdmGlobalKey}
 * ({@code tradeCdmGlobalKey}, {@code executionStepCdmGlobalKey}) is the object's Rune
 * {@code meta.globalKey}, which references inside CDM objects use ({@code previousWorkflowStep},
 * the portfolio state's {@code lineage}). Join CDM references on {@code cdmGlobalKey}.
 */
@RestController
public class LineageController {

  private static final Logger log = LoggerFactory.getLogger(LineageController.class);
  static final String REMAINING_FORMULA =
      "instructionQuantity - executedQuantity - canceledQuantity";

  private final CdmStore store;
  private final PositionView positions;
  private final SecurityMaster securities;
  private final PartyMaster parties;
  private final LifecycleFailureRepository failures;
  private final PendingTradeRepository pendingTrades;
  private final PendingCancelRepository pendingCancels;
  private final ObjectMapper cdmObjectMapper;
  private final CdmApiSupport support;

  public LineageController(CdmStore store, PositionView positions, SecurityMaster securities,
      PartyMaster parties, LifecycleFailureRepository failures,
      PendingTradeRepository pendingTrades, PendingCancelRepository pendingCancels,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper) {
    this.store = store;
    this.positions = positions;
    this.securities = securities;
    this.parties = parties;
    this.failures = failures;
    this.pendingTrades = pendingTrades;
    this.pendingCancels = pendingCancels;
    this.cdmObjectMapper = cdmObjectMapper;
    this.support = new CdmApiSupport(store, cdmObjectMapper);
  }

  /** A malformed ref or key: the caller's fault. */
  static final class MalformedRefException extends RuntimeException {
    private final String cdmRef;

    MalformedRefException(String cdmRef, String message) {
      super(message);
      this.cdmRef = cdmRef;
    }
  }

  // --- resolve -------------------------------------------------------------------------------

  @GetMapping("/cdm/resolve/{cdmRef}")
  public ResponseEntity<String> resolve(@PathVariable String cdmRef) {
    CdmRef ref;
    try {
      ref = CdmRef.parse(cdmRef);
    } catch (IllegalArgumentException e) {
      throw new MalformedRefException(cdmRef, e.getMessage());
    }
    return switch (ref.kind()) {
      case PARTY -> party(ref).map(this::cdmJson).orElseGet(() -> support.pending(ref));
      case PERSON -> parties.findPerson(ref.key()).map(this::cdmJson)
          .orElseGet(() -> support.pending(ref));
      case SECURITY -> securities.findByTicker(ref.key()).map(this::cdmJson)
          .orElseGet(() -> support.pending(ref));
      case ORDER -> Optional.ofNullable(OrderLineage.read(support, ref.key()).latestVersion())
          .map(stored -> ResponseEntity.ok()
              .header("Link", "</cdm/orders/" + UriUtils.encodePathSegment(ref.key(),
                  StandardCharsets.UTF_8) + "/lineage>; rel=\"lineage\"")
              .headers(h -> storedHeaders(stored).forEach(h::add))
              .contentType(MediaType.APPLICATION_JSON)
              .body(stored.json()))
          .orElseGet(() -> support.pending(ref));
      case TRADE -> store.latest(ref, TradeState.class)
          .map(stored -> ResponseEntity.ok()
              .headers(h -> storedHeaders(stored).forEach(h::add))
              .contentType(MediaType.APPLICATION_JSON)
              .body(stored.json()))
          .orElseGet(() -> support.pending(ref));
      case DIRECT -> direct(ref);
      case POSITION -> position(ref);
    };
  }

  /**
   * A direct trade's workflow: a CDM {@link Workflow} of its stored steps (proposed, accepted),
   * built on read. {@code X-CDM-Validation} is the worst stored result of its steps ({@link
   * #worst}: INVALID, then NOT_QUALIFIED, then VALID; the
   * workflow itself is not stored, so it has no store key).
   */
  private ResponseEntity<String> direct(CdmRef ref) {
    List<StoredCdm> rows = support.steps(ref);
    if (rows.isEmpty()) {
      return support.pending(ref);
    }
    Workflow.WorkflowBuilder workflow = Workflow.builder();
    rows.forEach(row -> workflow.addSteps(support.read(row, WorkflowStep.class)));
    CdmValidationResult.Status worst = worst(rows.stream()
        .map(row -> row.validation().status()));
    return ResponseEntity.ok()
        .header("Link", "</cdm/trades/" + UriUtils.encodePathSegment(ref.key(),
            StandardCharsets.UTF_8) + "/lineage>; rel=\"lineage\"")
        .header("X-CDM-Type", Workflow.class.getName())
        .header("X-CDM-Validation", worst.name())
        .contentType(MediaType.APPLICATION_JSON)
        .body(write(workflow.build()));
  }

  private Optional<Party> party(CdmRef ref) {
    if (PartyMapper.VENUE_KEY.equals(ref.key())) {
      return Optional.of(parties.venue());
    }
    return parties.resolvedParty(accountId(ref, ref.key()));
  }

  private ResponseEntity<String> position(CdmRef ref) {
    int separator = ref.key().indexOf(':');
    String account = separator < 0 ? ref.key() : ref.key().substring(0, separator);
    int accountId = accountId(ref, account);
    if (separator < 0) {
      PositionView.AccountPositions view = positions.view(accountId);
      if (!view.hasTrades()) {
        return support.pending(ref); // Ruling R30: no CDM trades, no portfolio state to serve
      }
      return ResponseEntity.ok()
          .header("X-CDM-Type", cdm.event.position.PortfolioState.class.getName())
          .header("X-CDM-Validation", view.validation().status().name())
          .contentType(MediaType.APPLICATION_JSON)
          .body(write(view.portfolioState()));
    }
    String security = ref.key().substring(separator + 1);
    if (security.isBlank()) {
      throw new MalformedRefException(ref.toString(), "position ref without a security: " + ref);
    }
    return positions.position(accountId, security)
        .map(this::cdmJson)
        .orElseGet(() -> support.pending(ref));
  }

  private static int accountId(CdmRef ref, String key) {
    try {
      return Integer.parseInt(key);
    } catch (NumberFormatException e) {
      throw new MalformedRefException(ref.toString(), "account id must be a number: " + key);
    }
  }

  // --- lineage -------------------------------------------------------------------------------

  @GetMapping("/cdm/orders/{orderId}/lineage")
  public ResponseEntity<String> lineage(@PathVariable String orderId) {
    OrderLineage order = OrderLineage.read(support, orderId);
    CdmRef ref = order.ref();
    List<PendingTrade> parked = pendingTrades.findByOrderIdOrderByReceivedAtAscTradeIdAsc(
        orderId);
    var parkedCancel = pendingCancels.findById(orderId);

    Set<String> failureRefs = new LinkedHashSet<>();
    failureRefs.add(ref.toString());
    order.fills().forEach(f -> failureRefs.add(tradeRef(f.tradeId()).toString()));
    parked.forEach(p -> failureRefs.add(tradeRef(p.getTradeId()).toString()));
    List<LifecycleFailure> failed = failureRefs.stream()
        .flatMap(r -> failures.findByCdmRefOrderByIdAsc(r).stream())
        .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
        .toList();

    if (order.isEmpty() && parked.isEmpty() && parkedCancel.isEmpty() && failed.isEmpty()) {
      return support.pending(ref);
    }

    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", ref.toString());
    body.put("orderId", orderId);

    boolean rejected = order.rejected();
    OrderLineage.Approval approval = order.approval();
    int instruction = order.instructionQuantity();
    int executed = order.executedQuantity();
    Integer recorded = order.cancel() == null ? null
        : WorkflowMapper.canceledQuantity(order.cancel().step());
    // The cancel states the matcher's cancelled quantity; derived only if its comment cannot
    // be read (never for steps this service wrote), and then said so.
    int canceled = order.cancel() == null ? 0
        : recorded != null ? recorded : Math.max(0, instruction - executed);
    int unclamped = rejected ? 0 : instruction - executed - canceled;
    int remaining = Math.max(0, unclamped);
    body.put("status", status(order, rejected, approval, executed, remaining));
    body.put("instructionQuantity", instruction);
    body.put("executedQuantity", executed);
    body.put("canceledQuantity", canceled);
    body.put("remainingQuantity", remaining);
    body.put("remainingQuantityFormula", REMAINING_FORMULA);
    List<String> warnings = new ArrayList<>();
    if (unclamped < 0) {
      // More executed (plus canceled) than instructed: shown as 0 remaining, never negative,
      // with the inconsistency stated (each fill's own validation flags its mismatches).
      warnings.add("executedQuantity + canceledQuantity exceeds instructionQuantity by "
          + (-unclamped) + "; remainingQuantity shown as 0");
    }
    if (order.cancel() != null && recorded == null) {
      warnings.add("the cancel's comment does not state the cancelled quantity;"
          + " canceledQuantity derived as instructionQuantity - executedQuantity");
    }
    if (warnings.isEmpty()) {
      body.putNull("quantityWarning");
    } else {
      body.put("quantityWarning", String.join("; ", warnings));
    }

    ObjectNode proposal = body.putObject("proposal");
    CdmApiSupport.putExecutionType(proposal, CdmApiSupport.executionType(order.proposal()),
        true);
    ArrayNode versions = proposal.putArray("versions");
    order.versions().forEach(v -> versions.add(support.stepNode(v.stored(), v.step())));
    proposal.set("approval", approvalNode(approval));

    ArrayNode fills = body.putArray("fills");
    order.fills().forEach(f -> fills.add(fillNode(f)));
    body.set("cancel", order.cancel() == null ? cdmObjectMapper.nullNode()
        : support.stepNode(order.cancel().stored(), order.cancel().step()));

    ExecutionInstruction ordered = WorkflowMapper.executionInstruction(order.proposal());
    body.set("parties", parties(ordered));
    body.set("security", security(ordered));

    ArrayNode failureNodes = body.putArray("failures");
    failed.forEach(f -> failureNodes.add(failureNode(f)));
    ObjectNode pending = body.putObject("pending");
    ArrayNode pendingTradeNodes = pending.putArray("trades");
    for (PendingTrade p : parked) {
      ObjectNode node = pendingTradeNodes.addObject();
      node.put("tradeId", p.getTradeId());
      node.put("receivedAt", String.valueOf(p.getReceivedAt()));
      node.put("reportedAt", p.getReportedAt() == null ? null : p.getReportedAt().toString());
      node.set("payload", jsonOrText(p.getPayload()));
    }
    if (parkedCancel.isPresent()) {
      ObjectNode node = pending.putObject("cancel");
      node.put("receivedAt", String.valueOf(parkedCancel.get().getReceivedAt()));
      node.put("reportedAt", parkedCancel.get().getReportedAt() == null ? null
          : parkedCancel.get().getReportedAt().toString());
      node.set("payload", jsonOrText(parkedCancel.get().getPayload()));
    } else {
      pending.putNull("cancel");
    }
    return json(body);
  }

  /**
   * {@code {approved, party, timestamp, rejectedReason, stepStoreGlobalKey}}: the venue's
   * decision and the store key of the proposal version carrying it; JSON null if none.
   */
  private JsonNode approvalNode(OrderLineage.Approval approval) {
    if (approval == null) {
      return cdmObjectMapper.nullNode();
    }
    WorkflowStepApproval a = approval.approval();
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("approved", Boolean.TRUE.equals(a.getApproved()));
    String party = a.getParty() == null ? null : a.getParty().getExternalReference();
    node.put("party", party == null ? null : new CdmRef(CdmRef.Kind.PARTY, party).toString());
    node.put("timestamp", a.getTimestamp() == null || a.getTimestamp().getDateTime() == null
        ? null : a.getTimestamp().getDateTime().toInstant().toString());
    node.put("rejectedReason", a.getRejectedReason());
    node.put("stepStoreGlobalKey", approval.carriedBy().stored().globalKey());
    return node;
  }

  /**
   * {@code {fillNumber, label, tradeId, quantity, price, executedAt, filledSoFar, executionType,
   * executionTypeMeaning, proposed, accepted, tradeState}}.
   */
  private ObjectNode fillNode(OrderLineage.Fill fill) {
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("fillNumber", fill.number());
    node.put("label", fill.label());
    node.put("tradeId", fill.tradeId());
    node.put("quantity", fill.quantity());
    node.put("price", fill.price());
    node.put("executedAt", fill.executedAt() == null ? null : fill.executedAt().toString());
    node.put("filledSoFar", fill.filledSoFar());
    TradeState after = fill.after();
    CdmApiSupport.putExecutionType(node, after != null
        ? CdmApiSupport.executionType(after.getTrade())
        : CdmApiSupport.executionType(fill.proposed().step()), false);
    node.set("proposed", fill.proposed() == null ? cdmObjectMapper.nullNode()
        : support.stepNode(fill.proposed().stored(), fill.proposed().step()));
    node.set("accepted", fill.accepted() == null ? cdmObjectMapper.nullNode()
        : support.stepNode(fill.accepted().stored(), fill.accepted().step()));
    node.set("tradeState", support.tradeStateSummary(
        store.latest(tradeRef(fill.tradeId()), TradeState.class).orElse(null)));
    return node;
  }

  private static String status(OrderLineage order, boolean rejected,
      OrderLineage.Approval approval, int executed, int remaining) {
    if (order.versions().isEmpty()) {
      return "pending";
    }
    if (rejected) {
      return "rejected";
    }
    if (order.cancel() != null) {
      return "canceled";
    }
    if (executed > 0) {
      return remaining == 0 ? "filled" : "partially-filled";
    }
    return approval != null && Boolean.TRUE.equals(approval.approval().getApproved())
        ? "accepted" : "proposed";
  }

  private ObjectNode parties(ExecutionInstruction ordered) {
    ObjectNode node = cdmObjectMapper.createObjectNode();
    if (ordered == null || ordered.getCounterparty() == null) {
      return node;
    }
    for (Counterparty counterparty : ordered.getCounterparty()) {
      if (counterparty.getPartyReference() == null) {
        continue;
      }
      String key = counterparty.getPartyReference().getExternalReference();
      ObjectNode party = node.putObject(
          counterparty.getRole() == CounterpartyRoleEnum.PARTY_1 ? "account" : "venue");
      party.put("cdmRef", new CdmRef(CdmRef.Kind.PARTY, key).toString());
      party.put("role", counterparty.getRole().toDisplayString());
      if (counterparty.getRole() == CounterpartyRoleEnum.PARTY_1) {
        try {
          party.put("accountId", Integer.parseInt(key));
        } catch (NumberFormatException e) {
          party.putNull("accountId");
        }
      }
      String name = ordered.getParties() == null ? null : ordered.getParties().stream()
          .filter(p -> p.getMeta() != null && key.equals(p.getMeta().getExternalKey()))
          .map(p -> p.getName() == null ? null : p.getName().getValue())
          .filter(Objects::nonNull)
          .findFirst().orElse(null);
      party.put("name", name);
    }
    return node;
  }

  private JsonNode security(ExecutionInstruction ordered) {
    try {
      var security = ordered.getProduct().getEconomicTerms().getPayout().get(0)
          .getSettlementPayout().getUnderlier().getObservable().getValue().getAsset()
          .getInstrument().getSecurity();
      var projection = SecurityMapper.toProjection(security);
      ObjectNode node = cdmObjectMapper.createObjectNode();
      node.put("cdmRef", projection.cdmRef());
      node.put("ticker", projection.ticker());
      node.put("companyName", projection.companyName());
      return node;
    } catch (RuntimeException e) {
      return cdmObjectMapper.nullNode();
    }
  }

  private ObjectNode failureNode(LifecycleFailure failure) {
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("id", failure.getId());
    node.put("cdmRef", failure.getCdmRef());
    node.put("stage", failure.getStage());
    node.put("message", failure.getMessage());
    node.put("failedAt", String.valueOf(failure.getFailedAt()));
    node.put("payload", failure.getPayload());
    return node;
  }

  // --- trades --------------------------------------------------------------------------------

  @GetMapping("/cdm/trades/{tradeId}")
  public ResponseEntity<String> trade(@PathVariable String tradeId) {
    CdmRef ref = tradeRef(tradeId);
    StoredCdm state = store.latest(ref, TradeState.class).orElse(null);
    if (state == null) {
      return support.pending(ref);
    }
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", ref.toString());
    body.set("tradeState", support.storedNode(state));
    OrderLineage.Producer producer = OrderLineage.producerOf(support, state);
    body.set("producedBy", producedByNode(cdmObjectMapper, producer, tradeId));
    CdmApiSupport.putExecutionType(body,
        CdmApiSupport.executionType(support.read(state, TradeState.class).getTrade()), false);
    return json(body);
  }

  /**
   * {@code {cdmRef, storeGlobalKey, kind ("fill"|"direct"), orderId, fillNumber, label,
   * lineage}}: what produced a trade state; JSON null if unknown.
   */
  static JsonNode producedByNode(ObjectMapper mapper, OrderLineage.Producer producer,
      String tradeId) {
    if (producer == null) {
      return mapper.nullNode();
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("cdmRef", producer.producedBy().ref().toString());
    node.put("storeGlobalKey", producer.producedBy().storeGlobalKey());
    node.put("kind", producer.kind());
    node.put("orderId", producer.orderId());
    if (producer.fill() == null) {
      node.putNull("fillNumber");
      node.putNull("label");
    } else {
      node.put("fillNumber", producer.fill().number());
      node.put("label", producer.fill().label());
    }
    node.put("lineage", OrderLineage.lineagePath(producer, tradeId));
    return node;
  }

  // --- positions -----------------------------------------------------------------------------

  /**
   * The account's CDM portfolio state with its validation, both of its keys (R29), its initial
   * state, the trade states counted (each with its store key and the Rune keys the lineage
   * references) and those skipped. 404 pending when the account has no CDM trades (R30).
   */
  @GetMapping("/cdm/positions/{accountId}")
  public ResponseEntity<String> cdmPositions(@PathVariable String accountId) {
    CdmRef ref = new CdmRef(CdmRef.Kind.POSITION, accountId);
    PositionView.AccountPositions view = positions.view(accountId(ref, accountId));
    if (!view.hasTrades()) {
      return support.pending(ref);
    }
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", view.cdmRef().toString());
    body.put("cdmType", cdm.event.position.PortfolioState.class.getName());
    body.put("storeGlobalKey", store.globalKeyOf(view.portfolioState()));
    body.put("cdmGlobalKey", view.portfolioState().getMeta().getGlobalKey());
    body.set("validation", support.validationNode(view.validation()));
    body.putRawValue("object", new RawValue(write(view.portfolioState())));
    ObjectNode initial = body.putObject("initialState");
    initial.put("storeGlobalKey", store.globalKeyOf(view.initialState()));
    // null: Rune assigns no global key to the empty initial state; the portfolio state's
    // lineage.portfolioStateReference points at it by this external key.
    initial.put("cdmGlobalKey", view.initialState().getMeta().getGlobalKey());
    initial.put("cdmExternalKey", view.initialState().getMeta().getExternalKey());
    initial.putRawValue("object", new RawValue(write(view.initialState())));
    ArrayNode trades = body.putArray("trades");
    for (PositionView.Contribution c : view.trades()) {
      ObjectNode node = trades.addObject();
      node.put("cdmRef", c.cdmRef());
      node.put("storeGlobalKey", c.storeGlobalKey());
      node.put("cdmGlobalKey", c.cdmGlobalKey());
      node.put("tradeCdmGlobalKey", c.tradeCdmGlobalKey());
      node.put("executionStepCdmGlobalKey", c.executionStepCdmGlobalKey());
      node.put("security", c.security());
      node.put("signedQuantity", c.signedQuantity());
      node.put("price", c.price());
      putInstant(node, "executedAt", c.executedAt());
      node.set("validation", support.validationNode(c.validation()));
    }
    ArrayNode skipped = body.putArray("skipped");
    view.skipped().forEach(k -> skipped.addObject().put("cdmRef", k.cdmRef())
        .put("reason", k.reason()));
    ArrayNode held = body.putArray("positions");
    for (PositionProjection p : view.positions()) {
      ObjectNode node = held.addObject();
      node.put("cdmRef", p.cdmRef());
      node.put("security", p.security());
      node.put("quantity", p.quantity());
      // The trade states that sum to it (signed: bought plus, sold minus), in execution order.
      ArrayNode contributing = node.putArray("trades");
      for (PositionView.Contribution c : view.tradesOf(p.security())) {
        ObjectNode row = contributing.addObject();
        row
            .put("tradeId", c.tradeId())
            .put("cdmRef", c.cdmRef())
            .put("signedQuantity", c.signedQuantity())
            .put("price", c.price())
            .put("side", c.side());
        putInstant(row, "executedAt", c.executedAt());
      }
    }
    return json(body);
  }

  @GetMapping("/projections/positions/{accountId}")
  public List<PositionProjection> positionProjection(@PathVariable int accountId) {
    return positions.projection(accountId);
  }

  // --- errors --------------------------------------------------------------------------------

  @ExceptionHandler(MalformedRefException.class)
  public ResponseEntity<String> malformed(MalformedRefException e) {
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", e.cdmRef);
    body.put("error", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
        .body(body.toString());
  }

  @ExceptionHandler(PartyMaster.NotReadyException.class)
  public ResponseEntity<String> notReady(PartyMaster.NotReadyException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
  }

  // --- helpers -------------------------------------------------------------------------------

  private static Map<String, String> storedHeaders(StoredCdm stored) {
    return Map.of("X-CDM-Type", stored.cdmType(), "X-CDM-Global-Key", stored.globalKey(),
        "X-CDM-Validation", stored.validation().status().name());
  }

  private JsonNode jsonOrText(String text) {
    try {
      return cdmObjectMapper.readTree(text);
    } catch (JsonProcessingException e) {
      return cdmObjectMapper.getNodeFactory().textNode(text);
    }
  }

  private ResponseEntity<String> cdmJson(Object cdmObject) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(write(cdmObject));
  }

  private String write(Object cdmObject) {
    try {
      return cdmObjectMapper.writeValueAsString(cdmObject);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise CDM object", e);
    }
  }

  private ResponseEntity<String> json(ObjectNode body) {
    try {
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
          .body(cdmObjectMapper.writer().writeValueAsString(body));
    } catch (JsonProcessingException e) {
      log.error("Cannot write lineage response", e);
      throw new IllegalStateException("Cannot write response", e);
    }
  }

  private static CdmRef tradeRef(String tradeId) {
    return new CdmRef(CdmRef.Kind.TRADE, tradeId);
  }
  /**
   * The worst of {@code statuses}, ranked explicitly (not by enum order): {@code INVALID}, then
   * {@code NOT_QUALIFIED}, then {@code VALID}.
   */
  static CdmValidationResult.Status worst(Stream<CdmValidationResult.Status> statuses) {
    return statuses.max(Comparator.comparingInt(LineageController::severity)).orElseThrow();
  }

  private static int severity(CdmValidationResult.Status status) {
    return switch (status) {
      case VALID -> 0;
      case NOT_QUALIFIED -> 1;
      case INVALID -> 2;
    };
  }

  /** Puts {@code at} as ISO-8601 text, or JSON {@code null} when there is none. */
  static void putInstant(ObjectNode node, String field, Instant at) {
    if (at == null) {
      node.putNull(field);
    } else {
      node.put(field, at.toString());
    }
  }
}
