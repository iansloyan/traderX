package finos.traderx.cdmservice.lifecycle;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order update as published by the order matcher on {@code /orders}: the fields of 009's
 * {@code OrderResponse} that the CDM lifecycle needs.
 *
 * <p>Note the matcher's shape on {@code CANCELED}: it sets {@code remainingQuantity} to 0 before
 * publishing, so the canceled remainder and the filled quantity come from the additive state
 * 017 fields {@code canceledQuantity} and {@code filledQuantity} (Ruling R25).
 *
 * @param side   {@code Buy} or {@code Sell} (009's {@code OrderSide} names)
 * @param status {@code NEW}, {@code PARTIALLY_FILLED}, {@code FILLED}, {@code CANCELED} or
 *               {@code REJECTED}
 * @param updatedAt when the matcher last changed the order (the creation time for {@code NEW})
 * @param filledQuantity   shares executed by the matcher so far, or {@code null} if not sent
 * @param canceledQuantity only on {@code CANCELED}: the unfilled remainder that was canceled,
 *                         or {@code null} if not sent
 */
public record OrderEvent(String orderId, int accountId, String security, String side,
    int quantity, int remainingQuantity, BigDecimal limitPrice, String status, Instant updatedAt,
    BigDecimal lastExecutionPrice, Integer lastFillQuantity, Integer filledQuantity,
    Integer canceledQuantity) {

  /** An update without the state 017 quantities. */
  public OrderEvent(String orderId, int accountId, String security, String side, int quantity,
      int remainingQuantity, BigDecimal limitPrice, String status, Instant updatedAt,
      BigDecimal lastExecutionPrice, Integer lastFillQuantity) {
    this(orderId, accountId, security, side, quantity, remainingQuantity, limitPrice, status,
        updatedAt, lastExecutionPrice, lastFillQuantity, null, null);
  }

  /**
   * The shares the matcher executed: {@code filledQuantity} when sent; otherwise
   * {@code quantity - remainingQuantity}, except on {@code CANCELED} where that difference is
   * meaningless (remaining is 0), so {@code null}.
   */
  public Integer executed() {
    if (filledQuantity != null) {
      return filledQuantity;
    }
    return "CANCELED".equals(status) ? null : quantity - remainingQuantity;
  }

  /**
   * The unfilled remainder a {@code CANCELED} event cancels: {@code canceledQuantity} when
   * sent, else {@code quantity - filledQuantity}, else (an event without the 017 fields)
   * {@code remainingQuantity}.
   */
  public int canceledRemainder() {
    if (canceledQuantity != null) {
      return canceledQuantity;
    }
    if (filledQuantity != null) {
      return quantity - filledQuantity;
    }
    return remainingQuantity;
  }
}
