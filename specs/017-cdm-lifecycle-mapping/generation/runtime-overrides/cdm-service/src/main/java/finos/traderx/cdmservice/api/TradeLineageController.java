package finos.traderx.cdmservice.api;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /cdm/trades/{tradeId}/lineage}: a trade, trade state first, then what produced it.
 *
 * <p>The trade's ref {@code cdm:trade:<tradeId>} holds only its {@link TradeState}, whose stored
 * "produced by" is the accepted step that executed it. The response leads with the trade state
 * ({@code tradeState}: keys, position state, quantity, price, the account's side, parties,
 * security, validation) and its execution type with what it means, then {@code producedBy}: the
 * producing workflow's ref and the accepted step's store key, {@code kind} {@code fill} (a fill
 * of an order: its order id, fill number and label, by booking order) or {@code direct} (a
 * trade-ticket trade, its workflow under {@code cdm:direct:<tradeId>}), the path of the lineage
 * that shows it in context, and its {@code proposed} and {@code accepted} steps. The execution
 * {@code BusinessEvent} is the accepted step's ({@code accepted.object.businessEvent}).
 * {@code producedBy} is null for a trade state with no (findable) producer. Keys follow Ruling
 * R29 ({@code storeGlobalKey} vs {@code cdmGlobalKey}). 404 pending until the trade state is
 * stored.
 */
@RestController
public class TradeLineageController {

  private final CdmStore store;
  private final ObjectMapper cdmObjectMapper;
  private final CdmApiSupport support;

  public TradeLineageController(CdmStore store,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper) {
    this.store = store;
    this.cdmObjectMapper = cdmObjectMapper;
    this.support = new CdmApiSupport(store, cdmObjectMapper);
  }

  @GetMapping("/cdm/trades/{tradeId}/lineage")
  public ResponseEntity<String> tradeLineage(@PathVariable String tradeId) {
    CdmRef ref = new CdmRef(CdmRef.Kind.TRADE, tradeId);
    StoredCdm state = store.latest(ref, TradeState.class).orElse(null);
    if (state == null) {
      return support.pending(ref);
    }
    ObjectNode body = cdmObjectMapper.createObjectNode();
    body.put("cdmRef", ref.toString());
    body.put("tradeId", tradeId);
    body.set("tradeState", support.tradeStateDetail(state));
    CdmApiSupport.putExecutionType(body,
        CdmApiSupport.executionType(support.read(state, TradeState.class).getTrade()), false);
    OrderLineage.Producer producer = OrderLineage.producerOf(support, state);
    JsonNode producedBy = LineageController.producedByNode(cdmObjectMapper, producer, tradeId);
    if (producer != null) {
      ObjectNode node = (ObjectNode) producedBy;
      node.set("proposed", producer.proposed() == null ? cdmObjectMapper.nullNode()
          : support.stepNode(producer.proposed().stored(), producer.proposed().step()));
      node.set("accepted", support.stepNode(producer.accepted().stored(),
          producer.accepted().step()));
    }
    body.set("producedBy", producedBy);
    try {
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
          .body(cdmObjectMapper.writeValueAsString(body));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write trade lineage response", e);
    }
  }
}
