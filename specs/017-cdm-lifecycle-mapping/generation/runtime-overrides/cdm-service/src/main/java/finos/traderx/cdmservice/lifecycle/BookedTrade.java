package finos.traderx.cdmservice.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A trade booked by trade-processor and published on {@code /trades/booked}.
 *
 * @param orderId the matcher order the trade fills, or {@code null} for a direct trade (trade
 *                ticket, no order)
 * @param price   the booked price per share in USD
 * @param created when the trade was booked
 * @param state   the TraderX trade state ({@code New}, {@code Processing}, {@code Settled},
 *                {@code Cancelled}) as booked, or {@code null} if the source did not say; mapped
 *                to the CDM {@code TradeState.state.positionState} (Rulings R35, R37)
 */
public record BookedTrade(String id, String orderId, int accountId, String security,
    String side, int quantity, BigDecimal price, Instant created, String state) {

  /** A booked trade whose TraderX state is not known (mapped as {@code EXECUTED}). */
  public BookedTrade(String id, String orderId, int accountId, String security, String side,
      int quantity, BigDecimal price, Instant created) {
    this(id, orderId, accountId, security, side, quantity, price, created, null);
  }
}
