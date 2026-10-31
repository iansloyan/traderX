package finos.traderx.cdmservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * CDM lifecycle metrics, exported by the Prometheus registry as:
 *
 * <ul>
 *   <li>{@code traderx_cdm_objects_total{type,validation}}: CDM objects stored by the lifecycle
 *       adapter, by simple CDM type name ({@code WorkflowStep}, {@code BusinessEvent},
 *       {@code TradeState}) and validation status ({@code VALID}, {@code INVALID},
 *       {@code NOT_QUALIFIED}).</li>
 *   <li>{@code traderx_cdm_failures_total{stage}}: lifecycle events that could not be fully
 *       turned into stored CDM (each also logged and stored in
 *       {@code cdm_lifecycle_failures}).</li>
 *   <li>{@code traderx_cdm_pending_trades}: booked trades parked until their order's accepted
 *       step is stored.</li>
 *   <li>{@code traderx_cdm_pending_cancels}: {@code CANCELED} order events parked until the
 *       order's fills are booked (or its proposal is stored).</li>
 * </ul>
 *
 * <p>The gauges read the pending tables at each scrape and report NaN when they cannot.
 */
@Component
public class CdmMetrics {

  public static final String OBJECTS = "traderx.cdm.objects";
  public static final String FAILURES = "traderx.cdm.failures";
  public static final String PENDING_TRADES = "traderx.cdm.pending.trades";
  public static final String PENDING_CANCELS = "traderx.cdm.pending.cancels";

  private final MeterRegistry registry;

  public CdmMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** One CDM object stored with this validation status. */
  public void objectStored(String cdmType, String validationStatus) {
    Counter.builder(OBJECTS)
        .description("CDM objects stored by the lifecycle adapter")
        .tag("type", simpleName(cdmType))
        .tag("validation", validationStatus)
        .register(registry)
        .increment();
  }

  /** One lifecycle failure at {@code stage}. */
  public void failure(String stage) {
    Counter.builder(FAILURES)
        .description("Lifecycle events not fully turned into stored CDM objects")
        .tag("stage", stage)
        .register(registry)
        .increment();
  }

  /** Registers the pending-trades gauge, read from {@code count} at each scrape. */
  public void pendingTrades(Supplier<Number> count) {
    Gauge.builder(PENDING_TRADES, count)
        .description("Booked trades parked until their order's approved proposal is stored")
        .strongReference(true)
        .register(registry);
  }

  /** Registers the pending-cancels gauge, read from {@code count} at each scrape. */
  public void pendingCancels(Supplier<Number> count) {
    Gauge.builder(PENDING_CANCELS, count)
        .description("CANCELED order events parked until the order's fills are booked")
        .strongReference(true)
        .register(registry);
  }

  static String simpleName(String cdmType) {
    if (cdmType == null) {
      return "unknown";
    }
    int dot = cdmType.lastIndexOf('.');
    return dot < 0 ? cdmType : cdmType.substring(dot + 1);
  }
}
