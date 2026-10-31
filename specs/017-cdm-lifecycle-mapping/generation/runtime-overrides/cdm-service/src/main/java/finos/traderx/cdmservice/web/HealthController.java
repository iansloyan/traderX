package finos.traderx.cdmservice.web;

import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import finos.traderx.cdmservice.master.CdmMaster;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness (Ruling R18): 200 {@code {"status":"ok",...}} only when every CDM master (security,
 * party) is completely seeded, else 503 {@code {"status":"starting"}}. The compose healthcheck
 * uses it, so services that read projections start after seeding.
 */
@RestController
public class HealthController {

  private final List<CdmMaster> masters;

  public HealthController(List<CdmMaster> masters) {
    this.masters = List.copyOf(masters);
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    if (!masters.stream().allMatch(CdmMaster::isReady)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "starting"));
    }
    Map<String, String> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("service", "cdm-service");
    body.put("cdmVersion", CdmRuntimeConfig.CDM_VERSION);
    return ResponseEntity.ok(body);
  }
}
