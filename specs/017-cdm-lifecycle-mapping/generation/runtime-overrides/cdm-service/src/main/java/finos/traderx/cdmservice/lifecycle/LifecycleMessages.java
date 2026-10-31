package finos.traderx.cdmservice.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reads the TraderX event payloads the lifecycle adapter consumes, in their 009 JSON shape:
 * {@code OrderResponse} on {@code /orders}, {@code RejectedOrder} on {@code /orders/rejected}
 * and {@code Trade} on {@code /trades/booked}, each the {@code payload} of a
 * {@code NatsEnvelope}. Also writes trades and order events back in that shape, for the
 * pending tables. Times are accepted as ISO-8601 text (with {@code Z} or an offset) or epoch
 * milliseconds; prices keep the scale they were sent with.
 */
final class LifecycleMessages {

  /** A plain Jackson 2 mapper; CDM objects are never read with it. */
  static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

  private static final DateTimeFormatter COMPACT_OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z");

  private LifecycleMessages() {
  }

  /** The {@code payload} of a {@code NatsEnvelope}. */
  static JsonNode envelopePayload(byte[] data) {
    JsonNode envelope;
    try {
      envelope = JSON.readTree(data);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("message is not JSON: " + e.getMessage(), e);
    }
    if (envelope == null || !envelope.isObject()) {
      throw new IllegalArgumentException("message is not a JSON object");
    }
    JsonNode payload = envelope.get("payload");
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("envelope has no payload object");
    }
    return payload;
  }

  static OrderEvent orderEvent(JsonNode p) {
    return new OrderEvent(requiredText(p, "orderId"), requiredInt(p, "accountId"),
        text(p, "security"), text(p, "side"), requiredInt(p, "quantity"),
        requiredInt(p, "remainingQuantity"), decimal(p, "limitPrice"),
        requiredText(p, "status"), requiredInstant(p, "updatedAt", "createdAt"),
        decimal(p, "lastExecutionPrice"), integer(p, "lastFillQuantity"),
        integer(p, "filledQuantity"), integer(p, "canceledQuantity"));
  }

  /**
   * The {@code NEW} event of an order read back from the matcher ({@code GET /orders/{id}},
   * an {@code OrderResponse} in any status): its original quantity, open in full, at its
   * {@code createdAt}. Used to rebuild the proposal of an order whose {@code NEW} event the
   * adapter never saw (Ruling R26).
   */
  static OrderEvent submittedOrder(JsonNode p) {
    return new OrderEvent(requiredText(p, "orderId"), requiredInt(p, "accountId"),
        text(p, "security"), text(p, "side"), requiredInt(p, "quantity"),
        requiredInt(p, "quantity"), decimal(p, "limitPrice"), "NEW",
        requiredInstant(p, "createdAt"), null, null);
  }

  static RejectedOrder rejectedOrder(JsonNode p) {
    return new RejectedOrder(requiredText(p, "rejectionId"), integer(p, "accountId"),
        text(p, "security"), text(p, "side"), integer(p, "quantity"), decimal(p, "limitPrice"),
        text(p, "reason"), requiredInstant(p, "rejectedAt"));
  }

  static BookedTrade bookedTrade(JsonNode p) {
    BigDecimal price = decimal(p, "price");
    if (price == null) {
      throw new IllegalArgumentException("trade has no price");
    }
    return new BookedTrade(requiredText(p, "id"), text(p, "orderId"),
        requiredInt(p, "accountId"), text(p, "security"), text(p, "side"),
        requiredInt(p, "quantity"), price, requiredInstant(p, "created", "updated"),
        text(p, "state"));
  }

  /** {@code trade} in the 009 {@code Trade} JSON shape (the fields the adapter reads). */
  static String toJson(BookedTrade trade) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", trade.id());
    node.put("orderId", trade.orderId());
    node.put("accountId", trade.accountId());
    node.put("security", trade.security());
    node.put("side", trade.side());
    node.put("quantity", trade.quantity());
    node.put("price", trade.price());
    node.put("created", trade.created() == null ? null : trade.created().toString());
    node.put("state", trade.state());
    return write(node);
  }

  /** {@code order} in the 009 {@code OrderResponse} JSON shape. */
  static String toJson(OrderEvent order) {
    ObjectNode node = JSON.createObjectNode();
    node.put("orderId", order.orderId());
    node.put("accountId", order.accountId());
    node.put("security", order.security());
    node.put("side", order.side());
    node.put("quantity", order.quantity());
    node.put("remainingQuantity", order.remainingQuantity());
    node.put("limitPrice", order.limitPrice());
    node.put("status", order.status());
    node.put("updatedAt", order.updatedAt() == null ? null : order.updatedAt().toString());
    node.put("lastExecutionPrice", order.lastExecutionPrice());
    node.put("lastFillQuantity", order.lastFillQuantity());
    node.put("filledQuantity", order.filledQuantity());
    node.put("canceledQuantity", order.canceledQuantity());
    return write(node);
  }

  static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("stored payload is not JSON", e);
    }
  }

  private static String write(ObjectNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode field(JsonNode p, String name) {
    JsonNode value = p.get(name);
    return value == null || value.isNull() ? null : value;
  }

  private static String text(JsonNode p, String name) {
    JsonNode value = field(p, name);
    return value == null ? null : value.asText();
  }

  private static String requiredText(JsonNode p, String name) {
    String value = text(p, name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("payload has no " + name);
    }
    return value;
  }

  private static Integer integer(JsonNode p, String name) {
    JsonNode value = field(p, name);
    if (value == null) {
      return null;
    }
    if (value.isIntegralNumber() && value.canConvertToInt()) {
      return value.intValue();
    }
    if (value.isTextual()) {
      try {
        return Integer.valueOf(value.asText().trim());
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    throw new IllegalArgumentException(name + " is not an integer: " + value);
  }

  private static int requiredInt(JsonNode p, String name) {
    Integer value = integer(p, name);
    if (value == null) {
      throw new IllegalArgumentException("payload has no " + name);
    }
    return value;
  }

  private static BigDecimal decimal(JsonNode p, String name) {
    JsonNode value = field(p, name);
    if (value == null) {
      return null;
    }
    try {
      // The text form keeps the scale as sent (180.000 stays 180.000).
      return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " is not a number: " + value, e);
    }
  }

  /** The first of {@code names} present, as an instant. */
  private static Instant requiredInstant(JsonNode p, String... names) {
    for (String name : names) {
      JsonNode value = field(p, name);
      if (value != null) {
        return instant(name, value);
      }
    }
    throw new IllegalArgumentException("payload has no " + String.join(" or ", names));
  }

  private static Instant instant(String name, JsonNode value) {
    if (value.isIntegralNumber()) {
      return Instant.ofEpochMilli(value.longValue());
    }
    if (value.isNumber()) {
      // Jackson's WRITE_DATES_AS_TIMESTAMPS form of an Instant: seconds.nanos.
      BigDecimal seconds = value.decimalValue();
      return Instant.ofEpochSecond(seconds.longValue(),
          seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
    }
    String text = value.asText().trim();
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      // "2026-10-29T14:30:05.000+00:00" (java.util.Date as ISO text)
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      // "2026-10-29T14:30:05.000+0000"
    }
    try {
      return OffsetDateTime.parse(text, COMPACT_OFFSET).toInstant();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(name + " is not a time: " + text, e);
    }
  }
}
