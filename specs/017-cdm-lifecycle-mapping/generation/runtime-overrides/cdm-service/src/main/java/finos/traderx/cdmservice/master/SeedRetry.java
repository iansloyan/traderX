package finos.traderx.cdmservice.master;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a master's seed attempt now and, until one succeeds, again on a daemon thread with
 * doubling backoff (for example 2s, 4s, ... capped at 30s). An attempt that throws counts as a
 * failure. {@link #stop()} ends the retries (call it from the master's {@code @PreDestroy}).
 */
public final class SeedRetry {

  private static final Logger log = LoggerFactory.getLogger(SeedRetry.class);

  private final String name;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final BooleanSupplier attempt;
  private final ScheduledExecutorService scheduler;

  public SeedRetry(String name, Duration initialDelay, Duration maxDelay, BooleanSupplier attempt) {
    this.name = name;
    this.initialDelay = initialDelay;
    this.maxDelay = maxDelay;
    this.attempt = attempt;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, name + "-seed");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** Makes the first attempt on the calling thread; schedules retries if it fails. */
  public void start() {
    run(1, initialDelay);
  }

  public void stop() {
    scheduler.shutdownNow();
  }

  private void run(int number, Duration delay) {
    boolean done;
    try {
      done = attempt.getAsBoolean();
    } catch (RuntimeException e) {
      log.error("CDM {} seeding attempt {} failed", name, number, e);
      done = false;
    }
    if (done || scheduler.isShutdown()) {
      return;
    }
    log.warn("CDM {} not ready after attempt {}; retrying in {} ms", name, number,
        delay.toMillis());
    Duration doubled = delay.multipliedBy(2);
    Duration next = doubled.compareTo(maxDelay) > 0 ? maxDelay : doubled;
    scheduler.schedule(() -> run(number + 1, next), delay.toMillis(), TimeUnit.MILLISECONDS);
  }
}
