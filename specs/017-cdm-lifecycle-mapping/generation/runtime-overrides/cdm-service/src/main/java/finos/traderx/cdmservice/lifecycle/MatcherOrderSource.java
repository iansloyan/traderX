package finos.traderx.cdmservice.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link OrderSource} backed by the 009 order matcher: {@code GET {base}/orders/{orderId}}
 * ({@code OrderController.getOrder}, an {@code OrderResponse}). 404 means the matcher does not
 * know the order; any other failure throws, so the caller records it and retries later.
 * {@link #openOrders()} reads {@code GET {base}/orders?status=open} (NEW and PARTIALLY_FILLED,
 * all accounts; {@code OrderController.listOrders}) and {@link #canceledOrders()} reads
 * {@code GET {base}/orders?status=canceled} (the listing's status filter accepts {@code open},
 * {@code all} or any {@code OrderStatus} name).
 */
@Component
public class MatcherOrderSource implements OrderSource {

  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final String baseUrl;

  public MatcherOrderSource(
      @Value("${cdm.lifecycle.order-matcher-url:http://order-matcher:18110}") String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public Optional<OrderEvent> submitted(String orderId) {
    URI uri = URI.create(baseUrl + "/orders/"
        + URLEncoder.encode(orderId, StandardCharsets.UTF_8));
    HttpResponse<byte[]> response = get(uri);
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    return Optional.of(LifecycleMessages.submittedOrder(body(uri, response)));
  }

  @Override
  public List<JsonNode> openOrders() {
    return list("open");
  }

  @Override
  public List<JsonNode> canceledOrders() {
    return list("canceled");
  }

  private List<JsonNode> list(String status) {
    URI uri = URI.create(baseUrl + "/orders?status=" + status);
    JsonNode body = body(uri, get(uri));
    if (!body.isArray()) {
      throw new IllegalStateException("order matcher sent a non-list body for " + uri);
    }
    List<JsonNode> out = new ArrayList<>();
    body.forEach(out::add);
    return out;
  }

  private HttpResponse<byte[]> get(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
        .header("Accept", "application/json").GET().build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("order matcher unreachable at " + uri + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted reading " + uri, e);
    }
  }

  private static JsonNode body(URI uri, HttpResponse<byte[]> response) {
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("order matcher answered " + response.statusCode()
          + " for " + uri);
    }
    try {
      return LifecycleMessages.JSON.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("order matcher sent unreadable JSON for " + uri, e);
    }
  }
}
