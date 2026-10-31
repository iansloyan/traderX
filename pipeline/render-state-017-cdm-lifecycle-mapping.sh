#!/usr/bin/env bash
set -euo pipefail

# State 017 render step. Runs after the parent (009) output has been generated
# and the 017 patch set applied.
#
# Ownership split:
# - Changes to existing (inherited) components and the state compose directory
#   `cdm-lifecycle-mapping/` are owned by generation/patches/0001-state-overlay.patch.
# - The new `cdm-service/` component is owned by
#   generation/runtime-overrides/cdm-service/ (single source of truth) and is
#   copied in here. It is excluded from patch capture via
#   generation/patch-capture-excludes.txt.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="017-cdm-lifecycle-mapping"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
TARGET_ROOT="${GENERATED_ROOT}/code/target-generated"
STATE_DIR="${TARGET_ROOT}/cdm-lifecycle-mapping"
COMPOSE_FILE="${STATE_DIR}/docker-compose.yml"
CDM_SERVICE_SOURCE_DIR="${ROOT}/specs/${STATE_ID}/generation/runtime-overrides/cdm-service"
CDM_SERVICE_TARGET_DIR="${TARGET_ROOT}/cdm-service"
INGRESS_FILE="${TARGET_ROOT}/ingress/nginx.traderx.conf.template"
PROMETHEUS_FILE="${STATE_DIR}/observability/prometheus/prometheus.yml"

# Parent renders (007/008/009) add the Grafana/Prometheus ingress routes only at
# generation depth 1, so they are missing when 009 is generated nested under
# 017. Re-apply them here (same logic as render-state-009) to keep 009 runtime
# parity. Idempotent.
ensure_observability_ingress_routes() {
  local ingress_file="$1"
  local tmp_file
  if rg -q "location /grafana/" "${ingress_file}" && rg -q "location /prometheus/" "${ingress_file}"; then
    return 0
  fi

  tmp_file="$(mktemp)"
  awk '
    !added && $0 ~ /^[[:space:]]*location \/ \{/ {
      print "    location = /grafana {"
      print "        return 301 /grafana/;"
      print "    }"
      print ""
      print "    location /grafana/ {"
      print "        proxy_pass http://grafana:3000;"
      print "        proxy_http_version 1.1;"
      print "        proxy_set_header Host $host;"
      print "        proxy_set_header X-Forwarded-Host $host;"
      print "        proxy_set_header X-Forwarded-Server $host;"
      print "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"
      print "        proxy_set_header X-Forwarded-Proto $scheme;"
      print "        proxy_set_header X-Forwarded-Prefix /grafana;"
      print "        proxy_set_header Upgrade $http_upgrade;"
      print "        proxy_set_header Connection \"upgrade\";"
      print "    }"
      print ""
      print "    location = /prometheus {"
      print "        return 301 /prometheus/;"
      print "    }"
      print ""
      print "    location /prometheus/ {"
      print "        proxy_pass http://prometheus:9090;"
      print "        proxy_http_version 1.1;"
      print "        proxy_set_header Host $host;"
      print "        proxy_set_header X-Forwarded-Host $host;"
      print "        proxy_set_header X-Forwarded-Server $host;"
      print "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;"
      print "        proxy_set_header X-Forwarded-Proto $scheme;"
      print "        proxy_set_header X-Forwarded-Prefix /prometheus;"
      print "    }"
      print ""
      added = 1
    }
    { print }
  ' "${ingress_file}" > "${tmp_file}"
  mv "${tmp_file}" "${ingress_file}"
}

# Ingress route for cdm-service, same style as 009's /order-matcher/ route. The
# CDM_SERVICE_URL value comes from the ingress service env in the 017 compose
# file. Inserted before the observability routes (or `location /`). Idempotent.
ensure_cdm_service_ingress_route() {
  local ingress_file="$1"
  local tmp_file
  if rg -q "location /cdm-service/" "${ingress_file}"; then
    return 0
  fi

  tmp_file="$(mktemp)"
  awk '
    !added && $0 ~ /^[[:space:]]*location (\/grafana\/|= \/grafana|\/ \{)/ {
      print "    location /cdm-service/ {"
      print "        proxy_pass ${CDM_SERVICE_URL};"
      print "    }"
      print ""
      added = 1
    }
    { print }
  ' "${ingress_file}" > "${tmp_file}"
  mv "${tmp_file}" "${ingress_file}"
  rg -q "location /cdm-service/" "${ingress_file}" || {
    echo "[fail] could not add the location /cdm-service/ route to ${ingress_file} (no insertion anchor found)"
    exit 1
  }
}

# Prometheus: scrape cdm-service actuator metrics in the shared Spring job (so the
# provisioned Spring dashboards include it) and probe its readiness endpoint.
# Idempotent.
ensure_cdm_service_prometheus_scrape() {
  local prometheus_file="$1"
  [[ -f "${prometheus_file}" ]] || {
    echo "[fail] missing prometheus config: ${prometheus_file}"
    exit 1
  }
  if ! rg -q '"cdm-service:18120"' "${prometheus_file}"; then
    perl -0pi -e 's/(  - job_name: traderx-spring-boot-actuator\n    metrics_path: \/actuator\/prometheus\n    static_configs:\n      - targets: \[[^\]]*?)\]/$1, "cdm-service:18120"]/s' "${prometheus_file}"
  fi
  if ! rg -q 'http://cdm-service:18120/health' "${prometheus_file}"; then
    perl -0pi -e 's/(          - http:\/\/order-matcher:18110\/health\n)/$1          - http:\/\/cdm-service:18120\/health\n/' "${prometheus_file}"
  fi
  rg -q '"cdm-service:18120"' "${prometheus_file}" || {
    echo "[fail] could not add cdm-service to the traderx-spring-boot-actuator scrape job in ${prometheus_file}"
    exit 1
  }
  rg -q 'http://cdm-service:18120/health' "${prometheus_file}" || {
    echo "[fail] could not add the cdm-service /health blackbox probe to ${prometheus_file}"
    exit 1
  }
}

[[ -f "${COMPOSE_FILE}" ]] || {
  echo "[fail] missing state 017 compose file (expected from patch set): ${COMPOSE_FILE}"
  exit 1
}

if ! rg -q '^name:\s*traderx-state-017\s*$' "${COMPOSE_FILE}"; then
  echo "[fail] ${COMPOSE_FILE} must declare compose project name traderx-state-017"
  exit 1
fi

[[ -f "${INGRESS_FILE}" ]] || {
  echo "[fail] missing ingress template: ${INGRESS_FILE}"
  exit 1
}
GEN_DEPTH="${TRADERX_GENERATION_DEPTH:-1}"
if [[ "${GEN_DEPTH}" == "1" ]]; then
  ensure_observability_ingress_routes "${INGRESS_FILE}"
else
  echo "[info] nested generation depth=${GEN_DEPTH}; skipping ingress observability route mutation"
fi
ensure_cdm_service_ingress_route "${INGRESS_FILE}"
ensure_cdm_service_prometheus_scrape "${PROMETHEUS_FILE}"

# Copy cdm-service from the state pack. Until the service source exists
# (the directory only holds .gitkeep), this is a no-op.
rm -rf "${CDM_SERVICE_TARGET_DIR}"
if [[ -d "${CDM_SERVICE_SOURCE_DIR}" ]] \
  && [[ -n "$(find "${CDM_SERVICE_SOURCE_DIR}" -mindepth 1 ! -name '.gitkeep' -print -quit)" ]]; then
  mkdir -p "${CDM_SERVICE_TARGET_DIR}"
  rsync -a --exclude='.gitkeep' --exclude='/build/' --exclude='/.gradle/' "${CDM_SERVICE_SOURCE_DIR}/" "${CDM_SERVICE_TARGET_DIR}/"
  echo "[info] copied cdm-service from ${CDM_SERVICE_SOURCE_DIR}"
else
  echo "[info] no cdm-service source in ${CDM_SERVICE_SOURCE_DIR} yet; skipping copy"
fi

echo "[done] rendered state 017 CDM lifecycle mapping into ${TARGET_ROOT}"
