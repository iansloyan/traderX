package finos.traderx.cdmservice.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link MatcherOrderSource} against a stub matcher over HTTP. */
class MatcherOrderSourceTest {

  private HttpServer server;

  private String serve(int status, String body, AtomicReference<String> requested)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requested.set(exchange.getRequestURI().toString());
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void openOrdersReadsTheMatchersOpenListing() throws Exception {
    AtomicReference<String> requested = new AtomicReference<>();
    String base = serve(200, "[{\"orderId\":\"ord-013-0001\",\"accountId\":22214,"
        + "\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":1800,\"limitPrice\":187.25,"
        + "\"status\":\"NEW\",\"createdAt\":\"2026-10-29T09:00:00Z\"}]", requested);

    List<JsonNode> open = new MatcherOrderSource(base).openOrders();

    assertThat(requested.get()).isEqualTo("/orders?status=open");
    assertThat(open).hasSize(1);
    OrderEvent order = LifecycleMessages.submittedOrder(open.get(0));
    assertThat(order.orderId()).isEqualTo("ord-013-0001");
    assertThat(order.status()).isEqualTo("NEW");
  }

  @Test
  void openOrdersThrowsWhenTheMatcherFails() throws Exception {
    String base = serve(503, "{}", new AtomicReference<>());

    assertThatThrownBy(() -> new MatcherOrderSource(base).openOrders())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
  }

  @Test
  void canceledOrdersReadsTheMatchersCanceledListing() throws Exception {
    AtomicReference<String> requested = new AtomicReference<>();
    String base = serve(200, "[{\"orderId\":\"ord-013-0002\",\"accountId\":22214,"
        + "\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":1500,"
        + "\"remainingQuantity\":0,\"filledQuantity\":0,\"canceledQuantity\":1500,"
        + "\"limitPrice\":180.0,\"status\":\"CANCELED\","
        + "\"createdAt\":\"2026-10-29T09:00:00Z\",\"updatedAt\":\"2026-10-29T09:01:00Z\"}]",
        requested);

    List<JsonNode> canceled = new MatcherOrderSource(base).canceledOrders();

    assertThat(requested.get()).isEqualTo("/orders?status=canceled");
    assertThat(canceled).hasSize(1);
    OrderEvent order = LifecycleMessages.orderEvent(canceled.get(0));
    assertThat(order.status()).isEqualTo("CANCELED");
    assertThat(order.canceledRemainder()).isEqualTo(1500);
  }
}
