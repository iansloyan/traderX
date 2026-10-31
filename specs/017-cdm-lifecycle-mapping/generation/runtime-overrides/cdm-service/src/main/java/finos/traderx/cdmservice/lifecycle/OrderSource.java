package finos.traderx.cdmservice.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * Where the lifecycle adapter reads an order it has no proposal for (Ruling R26): the order
 * matcher, which owns orders. Returns the order as its {@code NEW} event.
 */
@FunctionalInterface
public interface OrderSource {

  /** Never finds anything (tests, or no matcher configured). */
  OrderSource NONE = orderId -> Optional.empty();

  /**
   * @return the order's {@code NEW} event, or empty if the matcher does not know the order
   * @throws RuntimeException if the matcher cannot be asked (the caller records it)
   */
  Optional<OrderEvent> submitted(String orderId);

  /**
   * The matcher's open orders (NEW and PARTIALLY_FILLED), each as its raw 009
   * {@code OrderResponse} JSON, for the startup backfill (Ruling R32). The caller reads each with
   * {@link LifecycleMessages#submittedOrder} so one unreadable order is recorded on its own.
   *
   * @throws RuntimeException if the matcher cannot be asked (the caller retries)
   */
  default List<JsonNode> openOrders() {
    return List.of();
  }

  /**
   * The matcher's canceled orders (status {@code CANCELED}), each as its raw {@code OrderResponse}
   * JSON with {@code filledQuantity} and {@code canceledQuantity} (Ruling R25), for the startup
   * recovery of cancels cdm-service missed while it was down (Ruling R41, gap G9).
   *
   * @throws RuntimeException if the matcher cannot be asked (the caller retries)
   */
  default List<JsonNode> canceledOrders() {
    return List.of();
  }
}
