package finos.traderx.cdmservice.lifecycle;

import finos.traderx.cdmservice.metrics.CdmMetrics;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Records a lifecycle failure three ways (Ruling R24): a log line, the metric
 * {@code traderx_cdm_failures_total{stage}}, and a row in {@code cdm_lifecycle_failures} that
 * the CDM view can show. Never throws: a failure to record is logged.
 */
@Component
public class LifecycleFailures {

  private static final Logger log = LoggerFactory.getLogger(LifecycleFailures.class);
  private static final int MAX_MESSAGE = 4000;

  private final LifecycleFailureRepository repository;
  private final CdmMetrics metrics;
  private final Clock clock;

  @Autowired
  public LifecycleFailures(LifecycleFailureRepository repository, CdmMetrics metrics) {
    this(repository, metrics, Clock.systemUTC());
  }

  public LifecycleFailures(LifecycleFailureRepository repository, CdmMetrics metrics,
      Clock clock) {
    this.repository = repository;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * @param cdmRef  the ref the event belongs to, or {@code null} if unknown
   * @param stage   where it failed
   * @param message what went wrong
   * @param payload the event as received or decoded, or {@code null}
   */
  public void record(String cdmRef, String stage, String message, String payload) {
    String text = message == null || message.isBlank() ? "(no message)" : message;
    if (text.length() > MAX_MESSAGE) {
      text = text.substring(0, MAX_MESSAGE);
    }
    log.warn("CDM lifecycle failure ref={} stage={}: {} payload={}", cdmRef, stage, text,
        payload);
    try {
      metrics.failure(stage);
    } catch (RuntimeException e) {
      log.error("Cannot count CDM lifecycle failure", e);
    }
    try {
      repository.save(new LifecycleFailure(cdmRef, stage, text, payload,
          clock.instant().truncatedTo(ChronoUnit.MICROS)));
    } catch (RuntimeException e) {
      log.error("Cannot store CDM lifecycle failure ref={} stage={}", cdmRef, stage, e);
    }
  }

  /** {@link #record} with the exception's class and message as the message. */
  public void record(String cdmRef, String stage, Throwable error, String payload) {
    record(cdmRef, stage, describe(error), payload);
  }

  static String describe(Throwable error) {
    if (error == null) {
      return null;
    }
    Throwable cause = error instanceof LifecycleStageException && error.getCause() != null
        ? error.getCause() : error;
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
