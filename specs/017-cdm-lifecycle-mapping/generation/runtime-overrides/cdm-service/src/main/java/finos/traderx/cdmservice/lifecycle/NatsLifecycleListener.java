package finos.traderx.cdmservice.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.cdmservice.master.CdmMaster;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Connects the {@link LifecycleAdapter} to NATS (jnats, as 009's services do): consumes
 * {@code /orders}, {@code /orders/rejected} and {@code /trades/booked}, whose messages are
 * 009's {@code NatsEnvelope} JSON ({@code topic, payload, date, from, type}), and publishes
 * the adapter's stored CDM objects on {@code cdm.*} subjects over the same connection.
 *
 * <p>Started only when {@code cdm.lifecycle.nats.enabled=true} (the default at runtime, off in
 * tests). It subscribes only once every CDM master is ready; the connection is made in the
 * background and retried forever, before and after the first connect, so cdm-service starts
 * whether or not the broker is up and survives broker restarts. It also runs the adapter's
 * {@link LifecycleAdapter#sweep} of parked events every {@value #SWEEP_SECONDS}s. One
 * dispatcher thread handles every message in arrival order, so the events of
 * one order are processed one at a time. Nothing a message does can escape
 * {@link #handle}: undecodable messages and adapter errors are recorded as failures.
 */
@Component
@ConditionalOnProperty(name = "cdm.lifecycle.nats.enabled", havingValue = "true")
public class NatsLifecycleListener implements CdmPublisher, SmartLifecycle {

  public static final String ORDERS = "/orders";
  public static final String REJECTED_ORDERS = "/orders/rejected";
  public static final String BOOKED_TRADES = "/trades/booked";

  static final int SWEEP_SECONDS = 30;

  private static final Logger log = LoggerFactory.getLogger(NatsLifecycleListener.class);

  private final LifecycleAdapter adapter;
  private final LifecycleFailures failures;
  private final List<CdmMaster> masters;
  private final String address;
  private volatile ScheduledExecutorService scheduler;
  private volatile boolean waitingLogged;
  private volatile Connection connection;
  private volatile Dispatcher dispatcher;
  private volatile boolean running;

  public NatsLifecycleListener(LifecycleAdapter adapter, LifecycleFailures failures,
      List<CdmMaster> masters,
      @Value("${cdm.lifecycle.nats.address:nats://nats-broker:4222}") String address) {
    this.adapter = adapter;
    this.failures = failures;
    this.masters = List.copyOf(masters);
    this.address = address;
  }

  // --- consuming -----------------------------------------------------------------------------

  /** Decodes one message and hands it to the adapter. Never throws. */
  public void handle(String subject, byte[] data) {
    if (!ORDERS.equals(subject) && !REJECTED_ORDERS.equals(subject)
        && !BOOKED_TRADES.equals(subject)) {
      log.warn("Ignoring message on unexpected subject {}", subject);
      return;
    }
    JsonNode payload;
    Object event;
    try {
      payload = LifecycleMessages.envelopePayload(data);
      event = switch (subject) {
        case ORDERS -> LifecycleMessages.orderEvent(payload);
        case REJECTED_ORDERS -> LifecycleMessages.rejectedOrder(payload);
        default -> LifecycleMessages.bookedTrade(payload);
      };
    } catch (RuntimeException e) {
      failures.record(null, "decode", subject + ": " + LifecycleFailures.describe(e),
          raw(data));
      return;
    }
    try {
      switch (event) {
        case OrderEvent order -> adapter.onOrder(order);
        case RejectedOrder rejected -> adapter.onRejected(rejected);
        case BookedTrade trade -> adapter.onBookedTrade(trade);
        default -> throw new IllegalStateException("unexpected event " + event);
      }
    } catch (RuntimeException e) {
      // The adapter never throws; this is a last line of defence for the dispatcher thread.
      failures.record(null, "dispatch", subject + ": " + LifecycleFailures.describe(e),
          raw(data));
    }
  }

  private static String raw(byte[] data) {
    return data == null ? null : new String(data, StandardCharsets.UTF_8);
  }

  // --- publishing ----------------------------------------------------------------------------

  @Override
  public void publish(String subject, byte[] payload) {
    Connection current = connection;
    if (current == null || current.getStatus() == Connection.Status.CLOSED) {
      throw new IllegalStateException("NATS connection to " + address + " is not open");
    }
    // While reconnecting, jnats buffers the message and sends it on reconnect.
    current.publish(subject, payload);
  }

  // --- connection lifecycle ------------------------------------------------------------------

  /**
   * Starts in the background: waits until every CDM master is ready (Ruling R26: no event is
   * mapped against a half-seeded master), then connects. The connect is retried forever, and
   * the subscriptions are made on the first {@code CONNECTED} or {@code RECONNECTED} event
   * (with reconnect-on-connect, jnats reports a first connect that needed retries as
   * {@code RECONNECTED}). Once subscribed, {@link LifecycleAdapter#sweep} runs every
   * {@value #SWEEP_SECONDS}s.
   */
  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "cdm-lifecycle");
      t.setDaemon(true);
      return t;
    });
    this.scheduler = scheduler;
    scheduler.execute(this::connectWhenMastersReady);
  }

  private void connectWhenMastersReady() {
    if (!running) {
      return;
    }
    if (!masters.stream().allMatch(CdmMaster::isReady)) {
      if (!waitingLogged) {
        log.info("CDM lifecycle waits for the CDM masters before subscribing to NATS");
        waitingLogged = true;
      }
      scheduler.schedule(this::connectWhenMastersReady, 1, TimeUnit.SECONDS);
      return;
    }
    Options options = new Options.Builder()
        .server(address)
        .connectionName("cdm-service")
        .maxReconnects(-1)
        .reconnectWait(Duration.ofSeconds(2))
        .connectionTimeout(Duration.ofSeconds(5))
        .connectionListener(this::connectionEvent)
        .build();
    try {
      // Returns at once; connects (and keeps retrying) in the background.
      Nats.connectAsynchronously(options, true);
      log.info("Connecting to NATS at {} for the CDM lifecycle", address);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  void connectionEvent(Connection conn, ConnectionListener.Events event) {
    log.info("NATS {} ({})", event, address);
    if (event == ConnectionListener.Events.CONNECTED
        || event == ConnectionListener.Events.RECONNECTED) {
      subscribe(conn);
    }
  }

  private synchronized void subscribe(Connection conn) {
    if (!running) {
      closeQuietly(conn); // stopped before the background connect completed
      return;
    }
    connection = conn;
    if (dispatcher != null) {
      return; // subscriptions are restored by jnats on reconnect
    }
    Dispatcher d = conn.createDispatcher(msg -> handle(msg.getSubject(), msg.getData()));
    d.subscribe(ORDERS);
    d.subscribe(REJECTED_ORDERS);
    d.subscribe(BOOKED_TRADES);
    dispatcher = d;
    log.info("CDM lifecycle subscribed to {}, {}, {}", ORDERS, REJECTED_ORDERS, BOOKED_TRADES);
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.scheduleWithFixedDelay(this::sweep, SWEEP_SECONDS, SWEEP_SECONDS,
          TimeUnit.SECONDS);
    }
  }

  private void sweep() {
    try {
      adapter.sweep();
    } catch (RuntimeException e) {
      log.error("CDM lifecycle sweep failed", e); // the adapter never throws; defensive only
    }
  }

  /** True once subscribed to the TraderX subjects. */
  public boolean isSubscribed() {
    return dispatcher != null;
  }

  @Override
  public synchronized void stop() {
    running = false;
    ScheduledExecutorService current = scheduler;
    scheduler = null;
    if (current != null) {
      current.shutdownNow();
    }
    Connection conn = connection;
    connection = null;
    dispatcher = null;
    if (conn != null) {
      closeQuietly(conn);
    }
  }

  private static void closeQuietly(Connection conn) {
    try {
      conn.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
