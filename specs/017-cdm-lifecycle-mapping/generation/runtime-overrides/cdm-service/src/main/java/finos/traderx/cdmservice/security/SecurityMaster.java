package finos.traderx.cdmservice.security;

import cdm.base.staticdata.asset.common.Security;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.master.CdmMaster;
import finos.traderx.cdmservice.master.SeedRetry;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.master.Revalidation;
import finos.traderx.cdmservice.validation.CdmValidator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The authoritative security master: one CDM {@link Security} per ticker, stored in
 * {@link CdmStore} under {@code cdm:security:<ticker>}.
 *
 * <p>On {@link ApplicationReadyEvent} it seeds every ticker of {@link SecuritySeed} that has no
 * stored Security yet, and loads the stored version of the others, into an in-memory index in
 * seed order. The store stays the record; the index is a read view of what this single
 * cdm-service instance has stored.
 *
 * <p><b>Readiness is all or nothing.</b> The index is published and {@link #isReady()} becomes
 * true only when every seed ticker was stored or loaded. If any ticker fails (for example the
 * database is unavailable at startup), the master stays not ready, so projections answer 503
 * rather than a partial list, and seeding is retried with bounded exponential backoff until it
 * succeeds. Retries are safe: tickers already stored are loaded, not written again.
 *
 * <p>Every new Security is stored with its {@link CdmValidator} result. A loaded Security that
 * still carries the placeholder result of an earlier build is re-validated
 * ({@link Revalidation}, Ruling R16). Validation status is recorded, never a readiness gate.
 */
@Service
public class SecurityMaster implements CdmMaster {

  private static final Logger log = LoggerFactory.getLogger(SecurityMaster.class);
  static final Duration DEFAULT_INITIAL_RETRY = Duration.ofSeconds(2);
  static final Duration DEFAULT_MAX_RETRY = Duration.ofSeconds(30);

  private final CdmStore store;
  private final ObjectMapper cdmObjectMapper;
  private final CdmValidator validator;
  private final SeedRetry seedRetry;

  private volatile Map<String, Security> byTicker = Map.of();
  private volatile boolean ready;

  @Autowired
  public SecurityMaster(CdmStore store, @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper,
      CdmValidator validator) {
    this(store, cdmObjectMapper, validator, DEFAULT_INITIAL_RETRY, DEFAULT_MAX_RETRY);
  }

  SecurityMaster(CdmStore store, ObjectMapper cdmObjectMapper,
      CdmValidator validator, Duration initialRetry, Duration maxRetry) {
    this.store = store;
    this.cdmObjectMapper = cdmObjectMapper;
    this.validator = validator;
    this.seedRetry = new SeedRetry("security-master", initialRetry, maxRetry, this::seedIfEmpty);
  }

  /** Seeds now; on failure schedules retries (backoff from 2s, capped at 30s) until ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    seedRetry.start();
  }

  @PreDestroy
  void stopRetries() {
    seedRetry.stop();
  }

  /**
   * Stores a Security for each seed ticker that has none and loads the others. A failing ticker
   * is logged and does not stop the others, but then the index is not published and the master
   * stays (or becomes) not ready.
   *
   * @return true when every seed ticker is indexed and the master is ready
   */
  public synchronized boolean seedIfEmpty() {
    Map<String, Security> index = new LinkedHashMap<>();
    int created = 0;
    int failed = 0;
    for (SecuritySeed.SeedStock seed : SecuritySeed.load()) {
      try {
        Optional<StoredCdm> stored =
            store.latest(SecurityMapper.cdmRef(seed.ticker()), Security.class);
        if (stored.isPresent()) {
          Security security = read(stored.get());
          Revalidation.ifPlaceholder(store, validator, stored.get(), security);
          index.put(seed.ticker(), security);
          continue;
        }
        Security security = SecurityMapper.toCdm(seed.ticker(), seed.companyName());
        store.put(SecurityMapper.cdmRef(seed.ticker()), security, null,
            validator.validate(security));
        index.put(seed.ticker(), security);
        created++;
      } catch (RuntimeException e) {
        failed++;
        log.error("Cannot seed CDM security {}", seed.ticker(), e);
      }
    }
    if (failed > 0) {
      log.warn("CDM security master incomplete: {} of {} securities failed; staying not ready",
          failed, failed + index.size());
      return false;
    }
    byTicker = Collections.unmodifiableMap(index);
    ready = true;
    log.info("CDM security master ready: {} securities ({} newly seeded)", index.size(), created);
    return true;
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  /** All indexed securities in seed order (empty until {@link #isReady()}). */
  public List<Security> all() {
    return List.copyOf(byTicker.values());
  }

  /**
   * The Security for {@code ticker} (exact, case-sensitive match, as reference-data has always
   * matched). Once ready this reads only the index, so it agrees with {@link #all()}. Before
   * that it reads the store, for internal callers that run while seeding is still retried.
   */
  public Optional<Security> findByTicker(String ticker) {
    if (ticker == null || ticker.isBlank()) {
      return Optional.empty();
    }
    if (ready) {
      return Optional.ofNullable(byTicker.get(ticker));
    }
    return readStored(ticker);
  }

  private Optional<Security> readStored(String ticker) {
    return store.latest(SecurityMapper.cdmRef(ticker), Security.class).map(this::read);
  }

  private Security read(StoredCdm stored) {
    try {
      return cdmObjectMapper.readValue(stored.json(), Security.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored Security " + stored.ref() + " is unreadable", e);
    }
  }
}
