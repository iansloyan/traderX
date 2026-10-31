package finos.traderx.cdmservice.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Decoding of the 009 {@code NatsEnvelope} messages ({@code topic, payload, date, from, type}),
 * as the order matcher and trade processor publish them with their Jackson 3 publisher
 * (non-null fields only, dates as ISO-8601 text, {@code Trade.orderId} always present).
 */
class NatsLifecycleListenerTest {

  private LifecycleAdapter adapter;
  private LifecycleFailures failures;
  private NatsLifecycleListener listener;

  @BeforeEach
  void setUp() {
    adapter = mock(LifecycleAdapter.class);
    failures = mock(LifecycleFailures.class);
    listener = new NatsLifecycleListener(adapter, failures, List.of(() -> true),
        "nats://nats-broker:4222");
  }

  private void deliver(String subject, String json) {
    listener.handle(subject, json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void decodesOrderEnvelope() {
    deliver("/orders", """
        {"topic":"/orders","payload":{"orderId":"ord-013-0101","accountId":22214,\
        "security":"IBM","side":"Buy","quantity":1500,"remainingQuantity":750,\
        "limitPrice":180.000,"status":"PARTIALLY_FILLED","createdAt":"2026-10-29T14:30:00Z",\
        "updatedAt":"2026-10-29T14:30:05.123456Z","lastExecutionPrice":179.500,\
        "lastFillQuantity":750,"marketPrice":179.42,"cdmRef":"cdm:order:ord-013-0101"},\
        "date":"2026-10-29T14:30:05.130+00:00","from":"order-matcher","type":"OrderResponse"}""");

    ArgumentCaptor<OrderEvent> order = ArgumentCaptor.forClass(OrderEvent.class);
    verify(adapter).onOrder(order.capture());
    assertThat(order.getValue()).isEqualTo(new OrderEvent("ord-013-0101", 22214, "IBM", "Buy",
        1500, 750, new BigDecimal("180.000"), "PARTIALLY_FILLED",
        Instant.parse("2026-10-29T14:30:05.123456Z"), new BigDecimal("179.500"), 750));
  }

  @Test
  void decodesRejectedOrderEnvelope() {
    deliver("/orders/rejected", """
        {"topic":"/orders/rejected","payload":{"rejectionId":"rej-6f1c","accountId":22214,\
        "security":"IBM","side":"Buy","quantity":null,"limitPrice":null,\
        "reason":"quantity must be positive","rejectedAt":"2026-10-29T14:29:00Z"},\
        "date":"2026-10-29T14:29:00.010+00:00","from":"order-matcher","type":"RejectedOrder"}""");

    ArgumentCaptor<RejectedOrder> rejected = ArgumentCaptor.forClass(RejectedOrder.class);
    verify(adapter).onRejected(rejected.capture());
    assertThat(rejected.getValue()).isEqualTo(new RejectedOrder("rej-6f1c", 22214, "IBM", "Buy",
        null, null, "quantity must be positive", Instant.parse("2026-10-29T14:29:00Z")));
  }

  @Test
  void decodesBookedTradeEnvelope() {
    deliver("/trades/booked", """
        {"topic":"/trades/booked","payload":{"id":"trd-017-0001","accountId":22214,\
        "security":"IBM","side":"Buy","state":"Settled","quantity":750,"price":179.5,\
        "updated":"2026-10-29T14:30:05.000+00:00","created":"2026-10-29T14:30:05.000+00:00",\
        "orderId":"ord-013-0101","cdmRef":"cdm:trade:trd-017-0001"},\
        "date":"2026-10-29T14:30:05.200+00:00","from":"trade-processor","type":"Trade"}""");
    deliver("/trades/booked", """
        {"topic":"/trades/booked","payload":{"id":"trd-017-0100","accountId":22214,\
        "security":"IBM","side":"Sell","state":"Settled","quantity":200,"price":181.25,\
        "updated":1793458800000,"created":1793458800000,"orderId":null},\
        "date":1793458800000,"from":"trade-processor","type":"Trade"}""");

    ArgumentCaptor<BookedTrade> trade = ArgumentCaptor.forClass(BookedTrade.class);
    verify(adapter, org.mockito.Mockito.times(2)).onBookedTrade(trade.capture());
    assertThat(trade.getAllValues().get(0)).isEqualTo(new BookedTrade("trd-017-0001",
        "ord-013-0101", 22214, "IBM", "Buy", 750, new BigDecimal("179.5"),
        Instant.parse("2026-10-29T14:30:05Z"), "Settled"));
    assertThat(trade.getAllValues().get(1)).isEqualTo(new BookedTrade("trd-017-0100", null,
        22214, "IBM", "Sell", 200, new BigDecimal("181.25"), Instant.ofEpochMilli(1793458800000L),
        "Settled"));
  }

  @Test
  void undecodableMessageIsRecordedNotThrown() {
    assertThatCode(() -> deliver("/trades/booked", "{not json")).doesNotThrowAnyException();
    assertThatCode(() -> deliver("/orders", """
        {"topic":"/orders","payload":{"orderId":"ord-1"},"type":"OrderResponse"}"""))
        .doesNotThrowAnyException();
    assertThatCode(() -> deliver("/orders", """
        {"topic":"/orders","type":"OrderResponse"}""")).doesNotThrowAnyException();

    verify(failures, org.mockito.Mockito.times(3))
        .record(any(), eq("decode"), anyString(), anyString());
    verify(adapter, never()).onOrder(any());
    verify(adapter, never()).onBookedTrade(any());
  }

  @Test
  void adapterExceptionNeverEscapesTheHandler() {
    org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(adapter)
        .onRejected(any());
    assertThatCode(() -> deliver("/orders/rejected", """
        {"payload":{"rejectionId":"rej-1","reason":"x","rejectedAt":"2026-10-29T14:29:00Z"},\
        "type":"RejectedOrder"}""")).doesNotThrowAnyException();
    verify(failures).record(any(), eq("dispatch"), anyString(), anyString());
  }

  @Test
  void otherSubjectsAreIgnored() {
    deliver("/accounts/22214/orders", "{}");
    verifyNoInteractions(adapter);
  }

  /**
   * With reconnect-on-connect, a first connect that needed retries (cdm-service up before the
   * broker) is reported as RECONNECTED, never CONNECTED: the listener must subscribe on it.
   */
  @Test
  void subscribesOnReconnectedWhenTheFirstConnectNeededRetries() {
    AtomicInteger readinessChecks = new AtomicInteger();
    NatsLifecycleListener gated = new NatsLifecycleListener(adapter, failures,
        List.of(() -> readinessChecks.incrementAndGet() < 0), "nats://nowhere:4222");
    Connection connection = mock(Connection.class);
    Dispatcher dispatcher = mock(Dispatcher.class);
    when(connection.createDispatcher(any(MessageHandler.class))).thenReturn(dispatcher);
    gated.start();
    try {
      gated.connectionEvent(connection, ConnectionListener.Events.RECONNECTED);
      assertThat(gated.isSubscribed()).isTrue();

      // Later reconnects (and a stray CONNECTED) reuse the dispatcher: jnats resubscribes.
      gated.connectionEvent(connection, ConnectionListener.Events.RECONNECTED);
      gated.connectionEvent(connection, ConnectionListener.Events.CONNECTED);
      verify(connection, times(1)).createDispatcher(any(MessageHandler.class));
      verify(dispatcher).subscribe("/orders");
      verify(dispatcher).subscribe("/orders/rejected");
      verify(dispatcher).subscribe("/trades/booked");
    } finally {
      gated.stop();
    }
  }

  @Test
  void doesNotConnectUntilEveryMasterIsReady() throws Exception {
    AtomicInteger readinessChecks = new AtomicInteger();
    NatsLifecycleListener gated = new NatsLifecycleListener(adapter, failures,
        List.of(() -> true, () -> readinessChecks.incrementAndGet() < 0), "nats://nowhere:4222");
    gated.start();
    try {
      Thread.sleep(1500);
      assertThat(readinessChecks.get()).isGreaterThanOrEqualTo(2); // polled, never ready
      assertThat(gated.isSubscribed()).isFalse();
      assertThat(gated.isRunning()).isTrue();
    } finally {
      gated.stop();
    }
    assertThat(gated.isRunning()).isFalse();
  }
}
