package finos.traderx.cdmservice.lifecycle;

import cdm.event.common.TradeState;
import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.cdmservice.master.CdmMaster;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmStore;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backfills trades booked without cdm-service seeing them (Ruling R28): every row of the shared
 * 009 {@code trades} table with no {@code orderid} (a direct trade) whose ref
 * {@code cdm:trade:<id>} has no stored {@link TradeState} is sent through the lifecycle adapter's
 * direct-trade path, exactly as a {@code /trades/booked} event would be: its workflow under
 * {@code cdm:direct:<id>}, its trade state under {@code cdm:trade:<id>}. Every pass goes through
 * the adapter's live paths, so a backfill stores the same documents (same global keys and links)
 * as the live events would have. This covers the 009 seed
 * rows (which were inserted by SQL and never published on NATS) and direct trades booked while
 * cdm-service was down, so CDM positions equal position-service's for those accounts.
 *
 * <ul>
 *   <li><b>Idempotent.</b> A trade with a stored trade state is not sent again; the adapter
 *       itself also skips a direct trade it already executed and resumes one whose write stopped
 *       part-way (proposal stored, or steps stored and no trade state). A restart therefore backfills nothing new.</li>
 *   <li><b>Failures</b> are recorded like live events: the adapter records mapping, validation
 *       and store failures (stage {@code direct-trade}, {@code store}, ...); a row that cannot
 *       even be read as a trade is recorded with stage {@code backfill}. Nothing throws.</li>
 *   <li><b>Not backfilled by this pass:</b> rows with an {@code orderid} (fills; see the
 *       order-fill pass below) and rows in state {@code Cancelled}.</li>
 *   <li><b>Times.</b> {@code created} is a zone-less {@code TIMESTAMP}, read as UTC (the TraderX
 *       services run in UTC).</li>
 * </ul>
 *
 * <p><b>Open matcher orders (Ruling R32).</b> A second pass reads the matcher's open orders
 * ({@link OrderSource#openOrders()}, {@code GET {ORDER_MATCHER_URL}/orders?status=open}) and, for
 * each order with no approved proposal under {@code cdm:order:<id>}, stores its proposal and
 * approved version through the adapter's {@code NEW}-order path, from the order read back with
 * {@link LifecycleMessages#submittedOrder} (the same recovery the adapter uses for a missed
 * {@code NEW} event, Ruling R26). This covers the matcher's seeded {@code NEW} orders, which are
 * inserted at matcher startup and never published. It is idempotent (an order with an approved
 * proposal is skipped, and the adapter stores only a missing version); an order that cannot be
 * read is recorded with stage {@code backfill}, mapping and store failures by the adapter.
 *
 * <p>At startup ({@code cdm.lifecycle.backfill.enabled}, default true) it runs once on a daemon
 * thread after every CDM master is ready, alongside the NATS subscription (the adapter's per-ref
 * lock serialises it with a live event for the same trade or order). Each pass whose source
 * cannot be read (the {@code trades} table; the matcher, which in compose starts after
 * cdm-service) is retried with doubling backoff (1s to 30s) until it completes; a completed pass
 * is not repeated. A failed attempt logs one WARN line (the stack trace at DEBUG): it is
 * expected while the matcher starts.
 *
 * <p><b>Missed order fills (Ruling R31).</b> A third pass, after the open orders, sends every
 * {@code trades} row with an {@code orderid} and no CDM trade state (booked while cdm-service
 * was down) through the adapter's fill path in {@code created} order, so each fill's workflow
 * is stored under its order's ref; the adapter recovers the order (proposal and approval) from
 * the matcher when it is missing.
 *
 * <p><b>Missed cancels (Ruling R41, gap G9).</b> A fourth pass, after the fills, reads the
 * matcher's canceled orders ({@link OrderSource#canceledOrders()},
 * {@code GET {ORDER_MATCHER_URL}/orders?status=canceled}; the listing's status filter accepts
 * any order status) and, for each one whose chain under {@code cdm:order:<id>} has no cancel
 * (the Cancelled correction), sends the matcher's {@code CANCELED} response (with
 * {@code filledQuantity} and {@code canceledQuantity}) through the adapter's cancel path. An
 * order the matcher created and canceled entirely while cdm-service was down has no proposal
 * either: its proposal and approval are first stored from the same response (the {@code NEW}
 * path), then the cancel. One
 * listing covers both cases, so no per-order matcher request is needed. Idempotent (an order
 * whose chain has a cancel is skipped; the adapter drops a redelivered cancel); a cancel
 * whose fills are not all in CDM yet is parked by the adapter and retried by its sweep, like a
 * live cancel; an unreadable order is recorded with stage {@code backfill}.
 */
@Component
public class SeedTradeBackfill {

  private static final Logger log = LoggerFactory.getLogger(SeedTradeBackfill.class);
  private static final Duration MAX_DELAY = Duration.ofSeconds(30);

  /** What one pass did. */
  public record Result(int directTrades, int alreadyInCdm, int submitted, int stored) {
  }

  /** What one pass over the matcher's open orders did. */
  public record OrderResult(int openOrders, int alreadyInCdm, int submitted, int stored) {
  }

  /**
   * Progress of the startup passes, each {@code disabled}, {@code running} (not yet completed,
   * including waiting for the masters or retrying) or {@code done}.
   */
  public record Status(String directTrades, String openOrders, String orderFills,
      String canceledOrders) {

    public static final Status DISABLED =
        new Status("disabled", "disabled", "disabled", "disabled");

    /** No pass is still to run: all are done, or backfill is disabled. */
    public boolean complete() {
      return !"running".equals(directTrades) && !"running".equals(openOrders)
          && !"running".equals(orderFills) && !"running".equals(canceledOrders);
    }
  }

  /** What one pass over the matcher's canceled orders did. */
  public record CancelResult(int canceledOrders, int alreadyInCdm, int submitted, int stored) {
  }

  /** What one pass over order fills ({@code trades} rows with an {@code orderid}) did. */
  public record FillResult(int orderFills, int alreadyInCdm, int submitted, int stored) {
  }

  private record Row(String id, Integer accountId, LocalDateTime created, String security,
      String side, Integer quantity, BigDecimal price, String state, String orderId) {
  }

  private static final String TRADE_COLUMNS =
      "select id, accountid, created, security, side, quantity, price, state, orderid from trades";

  private final JdbcTemplate jdbc;
  private final CdmStore store;
  private final LifecycleAdapter adapter;
  private final LifecycleFailures failures;
  private final OrderSource orders;
  private final List<CdmMaster> masters;
  private final boolean enabled;
  private volatile ScheduledExecutorService scheduler;
  private volatile boolean tradesDone;
  private volatile boolean ordersDone;
  private volatile boolean fillsDone;
  private volatile boolean cancelsDone;

  @Autowired
  public SeedTradeBackfill(DataSource dataSource, CdmStore store, LifecycleAdapter adapter,
      LifecycleFailures failures, OrderSource orders, List<CdmMaster> masters,
      @Value("${cdm.lifecycle.backfill.enabled:true}") boolean enabled) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.store = store;
    this.adapter = adapter;
    this.failures = failures;
    this.orders = orders;
    this.masters = List.copyOf(masters);
    this.enabled = enabled;
  }

  /** Without a matcher: backfills direct trades only. */
  public SeedTradeBackfill(DataSource dataSource, CdmStore store, LifecycleAdapter adapter,
      LifecycleFailures failures, List<CdmMaster> masters, boolean enabled) {
    this(dataSource, store, adapter, failures, OrderSource.NONE, masters, enabled);
  }

  /**
   * One pass: sends every direct trade of the {@code trades} table that has no CDM trade state
   * through the adapter. Throws only if the table cannot be read (the startup runner retries).
   */
  public Result backfill() {
    List<Row> rows = rows("where orderid is null");
    Set<String> inCdm = new HashSet<>();
    store.refs(TradeState.class).forEach(ref -> inCdm.add(ref.toString()));
    int already = 0;
    int submitted = 0;
    int stored = 0;
    for (Row row : rows) {
      CdmRef ref = new CdmRef(CdmRef.Kind.TRADE, row.id());
      if (inCdm.contains(ref.toString())) {
        already++;
        continue;
      }
      if ("Cancelled".equals(row.state())) {
        log.info("Trade {} is Cancelled; not backfilled into CDM", row.id());
        continue;
      }
      BookedTrade trade;
      try {
        trade = toBookedTrade(row);
      } catch (RuntimeException e) {
        failures.record(ref.toString(), "backfill", e, String.valueOf(row));
        continue;
      }
      submitted++;
      adapter.onBookedTrade(trade); // never throws; records its own failures
      if (store.latest(ref, TradeState.class).isPresent()) {
        stored++;
      }
    }
    Result result = new Result(rows.size(), already, submitted, stored);
    log.info("CDM backfill of direct trades: {}", result);
    return result;
  }

  /**
   * One pass over the matcher's open orders: stores the proposal and approval of every open
   * order that has no approved proposal. Throws only if the matcher cannot be asked (the
   * startup runner retries).
   */
  public OrderResult backfillOpenOrders() {
    List<JsonNode> open = orders.openOrders();
    int already = 0;
    int submitted = 0;
    int stored = 0;
    for (JsonNode payload : open) {
      String orderId = payload.path("orderId").asText(null);
      CdmRef ref = orderId == null || orderId.isBlank() ? null
          : new CdmRef(CdmRef.Kind.ORDER, orderId);
      if (ref != null && adapter.hasApprovedProposal(orderId)) {
        already++;
        continue;
      }
      OrderEvent order;
      try {
        order = LifecycleMessages.submittedOrder(payload);
      } catch (RuntimeException e) {
        failures.record(ref == null ? null : ref.toString(), "backfill", e,
            String.valueOf(payload));
        continue;
      }
      submitted++;
      adapter.onOrder(order); // NEW path: stores the missing versions; never throws
      if (adapter.hasApprovedProposal(orderId)) {
        stored++;
      }
    }
    OrderResult result = new OrderResult(open.size(), already, submitted, stored);
    log.info("CDM backfill of open matcher orders: {}", result);
    return result;
  }

  /**
   * One pass over order fills (Ruling R31): every {@code trades} row with an {@code orderid}
   * and no CDM trade state (booked while cdm-service was down, so its {@code /trades/booked}
   * event was missed) goes through the adapter's fill path, in {@code created} order (so each
   * order's fills chain in booking order). The adapter recovers the order's proposal from the
   * matcher if it is missing, or parks the fill if the order cannot be read yet. Idempotent: a
   * fill with a stored trade state is skipped. Throws only if the table cannot be read.
   */
  public FillResult backfillOrderFills() {
    List<Row> rows = rows("where orderid is not null");
    Set<String> inCdm = new HashSet<>();
    store.refs(TradeState.class).forEach(ref -> inCdm.add(ref.toString()));
    int already = 0;
    int submitted = 0;
    int stored = 0;
    for (Row row : rows) {
      CdmRef ref = new CdmRef(CdmRef.Kind.TRADE, row.id());
      if (inCdm.contains(ref.toString())) {
        already++;
        continue;
      }
      BookedTrade trade;
      try {
        trade = toBookedTrade(row);
      } catch (RuntimeException e) {
        failures.record(ref.toString(), "backfill", e, String.valueOf(row));
        continue;
      }
      submitted++;
      adapter.onBookedTrade(trade); // never throws; records its own failures or parks the fill
      if (store.latest(ref, TradeState.class).isPresent()) {
        stored++;
      }
    }
    FillResult result = new FillResult(rows.size(), already, submitted, stored);
    log.info("CDM backfill of order fills: {}", result);
    return result;
  }

  /**
   * One pass over the matcher's canceled orders (Ruling R41): stores the cancel of every
   * canceled order whose chain has none (the {@code CANCELED} event was missed while
   * cdm-service was down), first storing the proposal and approval of an order that has
   * no approved proposal at all (created and canceled during the outage). Throws only if the matcher
   * cannot be asked (the startup runner retries).
   */
  public CancelResult backfillCanceledOrders() {
    List<JsonNode> canceled = orders.canceledOrders();
    int already = 0;
    int submitted = 0;
    int stored = 0;
    for (JsonNode payload : canceled) {
      String orderId = payload.path("orderId").asText(null);
      CdmRef ref = orderId == null || orderId.isBlank() ? null
          : new CdmRef(CdmRef.Kind.ORDER, orderId);
      if (ref != null && adapter.hasCancel(orderId)) {
        already++;
        continue;
      }
      OrderEvent cancel;
      OrderEvent submittedOrder;
      try {
        cancel = LifecycleMessages.orderEvent(payload);
        if (!"CANCELED".equals(cancel.status())) {
          throw new IllegalArgumentException("order " + orderId + " is " + cancel.status()
              + ", not CANCELED");
        }
        submittedOrder = adapter.hasApprovedProposal(orderId) ? null
            : LifecycleMessages.submittedOrder(payload);
      } catch (RuntimeException e) {
        failures.record(ref == null ? null : ref.toString(), "backfill", e,
            String.valueOf(payload));
        continue;
      }
      submitted++;
      if (submittedOrder != null) {
        adapter.onOrder(submittedOrder); // NEW path: proposal + approval; never throws
      }
      adapter.onOrder(cancel); // CANCELED path: stores or parks the cancel; never throws
      if (adapter.hasCancel(orderId)) {
        stored++;
      }
    }
    CancelResult result = new CancelResult(canceled.size(), already, submitted, stored);
    log.info("CDM backfill of canceled matcher orders: {}", result);
    return result;
  }

  private List<Row> rows(String where) {
    return jdbc.query(TRADE_COLUMNS + " " + where + " order by created, id",
        (rs, n) -> new Row(rs.getString("id"), (Integer) rs.getObject("accountid"),
            rs.getObject("created", LocalDateTime.class), rs.getString("security"),
            rs.getString("side"), (Integer) rs.getObject("quantity"), rs.getBigDecimal("price"),
            rs.getString("state"), rs.getString("orderid")));
  }

  private static BookedTrade toBookedTrade(Row row) {
    if (row.accountId() == null || row.created() == null || row.quantity() == null
        || row.price() == null) {
      throw new IllegalArgumentException("trade row " + row.id()
          + " lacks accountid, created, quantity or price");
    }
    Instant created = row.created().toInstant(ZoneOffset.UTC);
    return new BookedTrade(row.id(), row.orderId(), row.accountId(), row.security(), row.side(),
        row.quantity(), row.price(), created, row.state());
  }

  // --- startup runner ------------------------------------------------------------------------

  /** The startup passes' progress (for {@code /cdm/validation-summary}). */
  public Status status() {
    if (!enabled) {
      return Status.DISABLED;
    }
    return new Status(tradesDone ? "done" : "running", ordersDone ? "done" : "running",
        fillsDone ? "done" : "running", cancelsDone ? "done" : "running");
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void onApplicationReady() {
    if (!enabled || scheduler != null) {
      return;
    }
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "cdm-backfill");
      thread.setDaemon(true);
      return thread;
    });
    scheduler = executor;
    executor.execute(() -> attempt(1, Duration.ofSeconds(1)));
  }

  private void attempt(int number, Duration delay) {
    ScheduledExecutorService executor = scheduler;
    if (executor == null || executor.isShutdown()) {
      return;
    }
    if (!masters.stream().allMatch(CdmMaster::isReady)) {
      executor.schedule(() -> attempt(number, delay), 1, TimeUnit.SECONDS);
      return;
    }
    try {
      if (!tradesDone) {
        backfill();
        tradesDone = true;
      }
      if (!ordersDone) {
        backfillOpenOrders();
        ordersDone = true;
      }
      if (!fillsDone) {
        backfillOrderFills();
        fillsDone = true;
      }
      if (!cancelsDone) {
        backfillCanceledOrders();
        cancelsDone = true;
      }
      executor.shutdown();
    } catch (RuntimeException e) {
      // Expected while the matcher is still starting: one line at WARN, the stack at DEBUG.
      log.warn("CDM backfill attempt {} failed ({}); retrying in {} ms", number, e.toString(),
          delay.toMillis());
      log.debug("CDM backfill attempt {} failure", number, e);
      Duration doubled = delay.multipliedBy(2);
      Duration next = doubled.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : doubled;
      executor.schedule(() -> attempt(number + 1, next), delay.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  @PreDestroy
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
