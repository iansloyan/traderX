package finos.traderx.cdmservice.store;

import finos.traderx.cdmservice.ref.CdmRef;
import java.util.Objects;

/**
 * What produced a stored version, when that is an object under another ref: for a trade's
 * {@code TradeState} under {@code cdm:trade:<tradeId>}, the accepted workflow step that carries
 * the execution ({@code cdm:order:<orderId>} for a fill, {@code cdm:direct:<tradeId>} for a
 * direct trade). Stored in {@code produced_by_ref} / {@code produced_by_global_key}, apart from
 * {@code previous_global_key}: version links stay within one ref.
 *
 * @param ref            the ref the producing object is stored under
 * @param storeGlobalKey the producing object's store global key (SHA-256 of its canonical JSON)
 */
public record ProducedBy(CdmRef ref, String storeGlobalKey) {

  public ProducedBy {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(storeGlobalKey, "storeGlobalKey");
  }
}
