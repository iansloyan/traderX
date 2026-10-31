package finos.traderx.cdmservice.api;

import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.event.common.BusinessEvent;
import cdm.event.common.ExecutionDetails;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.WorkflowStatusEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.observable.asset.PriceQuantity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.rosetta.model.lib.RosettaModelObject;
import finos.traderx.cdmservice.lifecycle.ExecutionMapper;
import finos.traderx.cdmservice.lifecycle.WorkflowMapper;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Reads and JSON pieces shared by the CDM read controllers ({@link LineageController},
 * {@link TradeLineageController}, {@link DiagnosticsController}): one place for how a stored
 * object is read back, how a validation result, a workflow step and a trade state are rendered,
 * what an execution type means and how a not-yet-known ref is answered.
 */
final class CdmApiSupport {

  /** {@code executionTypeMeaning} of a matcher fill's (or a trade's) {@code OnVenue}. */
  static final String ON_VENUE_EXECUTED =
      "Executed by the TraderX matching engine (the venue, Party2)";
  /** {@code executionTypeMeaning} of an order proposal's {@code OnVenue}: nothing executed yet. */
  static final String ON_VENUE_SENT = "Sent to the TraderX matching engine (the venue, Party2)";
  /** {@code executionTypeMeaning} of a direct trade's {@code Electronic}. */
  static final String ELECTRONIC_BOOKED =
      "Booked electronically from the trade ticket, not matched";

  private final CdmStore store;
  private final ObjectMapper cdmObjectMapper;

  CdmApiSupport(CdmStore store, ObjectMapper cdmObjectMapper) {
    this.store = store;
    this.cdmObjectMapper = cdmObjectMapper;
  }

  /** The last version of {@code type} in {@code history} (write order), or null. */
  static StoredCdm latestOf(List<StoredCdm> history, String type) {
    StoredCdm latest = null;
    for (StoredCdm s : history) {
      if (type.equals(s.cdmType())) {
        latest = s;
      }
    }
    return latest;
  }

  /**
   * The rows with each version once: a re-validation ({@link CdmStore#revalidate}) repeats a
   * version (same global and previous key) with a new result; the version keeps its position and
   * takes the latest result.
   */
  static List<StoredCdm> latestValidation(List<StoredCdm> rows) {
    Map<String, StoredCdm> byVersion = new LinkedHashMap<>();
    for (StoredCdm row : rows) {
      String version = row.globalKey() + " " + row.previousGlobalKey();
      StoredCdm first = byVersion.get(version);
      byVersion.put(version, first == null ? row : new StoredCdm(first.globalKey(), first.ref(),
          first.cdmType(), first.json(), first.previousGlobalKey(), row.validation(),
          first.createdAt(), first.producedBy()));
    }
    return List.copyOf(byVersion.values());
  }

  /** The {@link WorkflowStep} rows of {@code ref}, each version once, in write order. */
  List<StoredCdm> steps(CdmRef ref) {
    String type = WorkflowStep.class.getName();
    return latestValidation(store.history(ref).stream()
        .filter(s -> type.equals(s.cdmType()))
        .toList());
  }

  /** {@code {"status": ..., "messages": [...]}}. */
  ObjectNode validationNode(CdmValidationResult validation) {
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("status", validation.status().name());
    ArrayNode messages = node.putArray("messages");
    validation.messages().forEach(messages::add);
    return node;
  }

  /** The stored Rosetta JSON read back as {@code type}; unreadable rows are a server error. */
  <T extends RosettaModelObject> T read(StoredCdm stored, Class<T> type) {
    try {
      return cdmObjectMapper.readValue(stored.json(), type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored " + type.getSimpleName() + " "
          + stored.globalKey() + " under " + stored.ref() + " is unreadable", e);
    }
  }

  /** The stored Rosetta JSON as a tree, for responses that embed the object. */
  JsonNode tree(StoredCdm stored) {
    try {
      return cdmObjectMapper.readTree(stored.json());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored object " + stored.globalKey() + " under "
          + stored.ref() + " is unreadable", e);
    }
  }

  /** The stored object's Rune {@code meta.globalKey}, or null. */
  String cdmGlobalKey(StoredCdm stored) {
    try {
      JsonNode key = cdmObjectMapper.readTree(stored.json()).at("/meta/globalKey");
      return key.isTextual() ? key.asText() : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * {@code {storeGlobalKey, cdmGlobalKey, cdmType, previousStoreGlobalKey, validation, createdAt,
   * object}} (Ruling R29: store keys navigate {@code cdm_documents}; the CDM key is the object's
   * Rune {@code meta.globalKey}, which CDM references such as {@code previousWorkflowStep} use).
   */
  ObjectNode storedNode(StoredCdm stored) {
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("storeGlobalKey", stored.globalKey());
    node.put("cdmGlobalKey", cdmGlobalKey(stored));
    node.put("cdmType", stored.cdmType());
    node.put("previousStoreGlobalKey", stored.previousGlobalKey());
    node.set("validation", validationNode(stored.validation()));
    node.put("createdAt", String.valueOf(stored.createdAt()));
    node.putRawValue("object", new RawValue(stored.json()));
    return node;
  }

  /** A workflow step: {@link #storedNode} plus {@code stepType} and {@code timestamp}. */
  ObjectNode stepNode(StoredCdm stored, WorkflowStep step) {
    ObjectNode node = storedNode(stored);
    node.put("stepType", stepType(step));
    node.put("timestamp", timestamp(step));
    return node;
  }

  /** {@link #stepNode}, or JSON null when the step is not stored (yet). */
  JsonNode stepNodeOrNull(StoredCdm stored, WorkflowStep step) {
    return stored == null ? cdmObjectMapper.nullNode() : stepNode(stored, step);
  }

  /**
   * What a workflow step is in TraderX's lifecycle: {@code proposed} (an order or direct trade
   * as proposed), {@code approved} (an order's proposal version carrying the venue's approval),
   * {@code rejected} (a rejected request), {@code cancel} (the Cancelled correction of an order),
   * {@code fill-proposed} / {@code fill-accepted} (a fill's own workflow) or {@code accepted}
   * (a direct trade's acceptance, carrying its execution).
   */
  static String stepType(WorkflowStep step) {
    WorkflowStatusEnum status = step.getWorkflowState() == null ? null
        : step.getWorkflowState().getWorkflowStatus();
    if (Boolean.TRUE.equals(step.getRejected()) || status == WorkflowStatusEnum.REJECTED) {
      return "rejected";
    }
    if (WorkflowMapper.isCancel(step)) {
      return "cancel";
    }
    boolean executes = step.getBusinessEvent() != null;
    if (ExecutionMapper.fillTradeId(step) != null) {
      return executes ? "fill-accepted" : "fill-proposed";
    }
    if (executes) {
      return "accepted";
    }
    return WorkflowMapper.isApproved(step) ? "approved" : "proposed";
  }

  /** The step's first timestamp (ISO-8601 instant), or null. */
  static String timestamp(WorkflowStep step) {
    Instant at = instant(step);
    return at == null ? null : at.toString();
  }

  /** The step's first timestamp, or null. */
  static Instant instant(WorkflowStep step) {
    return step == null || step.getTimestamp() == null ? null : step.getTimestamp().stream()
        .map(EventTimestamp::getDateTime)
        .filter(Objects::nonNull)
        .map(dt -> dt.toInstant())
        .findFirst().orElse(null);
  }

  // --- execution type --------------------------------------------------------------------------

  /**
   * What {@code type} means here: for an order's proposal ({@code proposal} true) {@code OnVenue}
   * says where it was sent; for a fill or trade it says how the trade was executed. Null for a
   * type TraderX never uses.
   */
  static String executionTypeMeaning(ExecutionTypeEnum type, boolean proposal) {
    if (type == ExecutionTypeEnum.ON_VENUE) {
      return proposal ? ON_VENUE_SENT : ON_VENUE_EXECUTED;
    }
    if (type == ExecutionTypeEnum.ELECTRONIC) {
      return ELECTRONIC_BOOKED;
    }
    return null;
  }

  /** {@code executionType} (CDM display name) and {@code executionTypeMeaning} on {@code node}. */
  static void putExecutionType(ObjectNode node, ExecutionTypeEnum type, boolean proposal) {
    node.put("executionType", type == null ? null : type.toDisplayString());
    node.put("executionTypeMeaning", executionTypeMeaning(type, proposal));
  }

  /** The execution type of a trade ({@code ExecutionDetails.executionType}), or null. */
  static ExecutionTypeEnum executionType(Trade trade) {
    ExecutionDetails details = trade == null ? null : trade.getExecutionDetails();
    return details == null ? null : details.getExecutionType();
  }

  /** The execution type of a proposed step's execution instruction, or null. */
  static ExecutionTypeEnum executionType(WorkflowStep proposed) {
    var instruction = WorkflowMapper.executionInstruction(proposed);
    ExecutionDetails details = instruction == null ? null : instruction.getExecutionDetails();
    return details == null ? null : details.getExecutionType();
  }

  // --- trade states ----------------------------------------------------------------------------

  /** The trade state a step's execution produced, or null (no execution). */
  static TradeState afterState(WorkflowStep step) {
    BusinessEvent event = step == null ? null : step.getBusinessEvent();
    return event == null || event.getAfter() == null || event.getAfter().size() != 1 ? null
        : event.getAfter().get(0);
  }

  /** A trade's first price/quantity, or null. */
  static PriceQuantity priceQuantity(Trade trade) {
    if (trade == null || trade.getTradeLot() == null) {
      return null;
    }
    return trade.getTradeLot().stream()
        .filter(lot -> lot.getPriceQuantity() != null && !lot.getPriceQuantity().isEmpty())
        .map(lot -> (PriceQuantity) lot.getPriceQuantity().get(0))
        .findFirst().orElse(null);
  }

  /** The price of a price/quantity, or null. */
  static BigDecimal price(PriceQuantity pq) {
    return pq == null || pq.getPrice() == null || pq.getPrice().isEmpty()
        || pq.getPrice().get(0).getValue() == null ? null
        : pq.getPrice().get(0).getValue().getValue();
  }

  /** The quantity (shares) of a price/quantity, or null. */
  static Integer quantity(PriceQuantity pq) {
    return pq == null || pq.getQuantity() == null || pq.getQuantity().getValue() == null
        || pq.getQuantity().getValue().getValue() == null ? null
        : pq.getQuantity().getValue().getValue().intValue();
  }

  /**
   * {@code {cdmRef, storeGlobalKey, cdmGlobalKey, positionState, validation}}: a stored trade
   * state, summarised; JSON null when not stored (yet).
   */
  JsonNode tradeStateSummary(StoredCdm stored) {
    if (stored == null) {
      return cdmObjectMapper.nullNode();
    }
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("cdmRef", stored.ref().toString());
    node.put("storeGlobalKey", stored.globalKey());
    node.put("cdmGlobalKey", cdmGlobalKey(stored));
    TradeState state = read(stored, TradeState.class);
    node.put("positionState", state.getState() == null
        || state.getState().getPositionState() == null ? null
        : state.getState().getPositionState().toDisplayString());
    node.set("validation", validationNode(stored.validation()));
    return node;
  }

  /**
   * {@link #tradeStateSummary} plus what a trade page shows first: {@code tradeId},
   * {@code previousStoreGlobalKey}, {@code quantity}, {@code price}, {@code side} (the account's),
   * {@code account} and {@code venue} ({@code cdmRef}, name), {@code security} ({@code cdmRef},
   * ticker).
   */
  ObjectNode tradeStateDetail(StoredCdm stored) {
    ObjectNode node = (ObjectNode) tradeStateSummary(stored);
    TradeState state = read(stored, TradeState.class);
    Trade trade = state.getTrade();
    node.put("tradeId", stored.ref().key());
    node.put("previousStoreGlobalKey", stored.previousGlobalKey());
    PriceQuantity pq = priceQuantity(trade);
    Integer quantity = quantity(pq);
    if (quantity == null) {
      node.putNull("quantity");
    } else {
      node.put("quantity", quantity);
    }
    node.put("price", price(pq));
    String account = counterparty(trade, CounterpartyRoleEnum.PARTY_1);
    String buyer = role(trade, PartyRoleEnum.BUYER);
    node.put("side", account == null || buyer == null ? null
        : account.equals(buyer) ? "Buy" : "Sell");
    node.set("account", partyNode(trade, account));
    node.set("venue", partyNode(trade, counterparty(trade, CounterpartyRoleEnum.PARTY_2)));
    node.set("security", securityNode(trade));
    return node;
  }

  private JsonNode partyNode(Trade trade, String key) {
    if (key == null) {
      return cdmObjectMapper.nullNode();
    }
    ObjectNode node = cdmObjectMapper.createObjectNode();
    node.put("cdmRef", new CdmRef(CdmRef.Kind.PARTY, key).toString());
    node.put("name", trade.getParty() == null ? null : trade.getParty().stream()
        .filter(p -> p.getMeta() != null && key.equals(p.getMeta().getExternalKey()))
        .map(p -> p.getName() == null ? null : p.getName().getValue())
        .filter(Objects::nonNull)
        .findFirst().orElse(null));
    return node;
  }

  private JsonNode securityNode(Trade trade) {
    try {
      Security security = trade.getProduct().getEconomicTerms().getPayout().get(0)
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

  private static String counterparty(Trade trade, CounterpartyRoleEnum role) {
    return trade == null || trade.getCounterparty() == null ? null
        : trade.getCounterparty().stream()
            .filter(c -> c.getRole() == role)
            .map(Counterparty::getPartyReference)
            .filter(Objects::nonNull)
            .map(r -> r.getExternalReference())
            .findFirst().orElse(null);
  }

  private static String role(Trade trade, PartyRoleEnum role) {
    return trade == null || trade.getPartyRole() == null ? null : trade.getPartyRole().stream()
        .filter(r -> r.getRole() == role)
        .map(PartyRole::getPartyReference)
        .filter(Objects::nonNull)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
  }

  /** 404 {@code {"cdmRef": ..., "status": "pending"}}: well-formed, nothing stored yet. */
  ResponseEntity<String> pending(CdmRef ref) {
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", ref.toString());
    body.put("status", "pending");
    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
        .body(body.toString());
  }
}
