#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${REPO_ROOT}/generated}"
source "${REPO_ROOT}/scripts/lib/observability-runtime.sh"

if [[ "${TRADERX_LOCAL_RUNTIME_SCRIPT:-0}" != "1" ]]; then
  LOCAL_RUNTIME_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/$(basename "${BASH_SOURCE[0]}")"
  if [[ -x "${LOCAL_RUNTIME_SCRIPT}" ]]; then
    exec "${LOCAL_RUNTIME_SCRIPT}" "$@"
  fi
fi
STATE_ID="017-cdm-lifecycle-mapping"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-traderx-state-017}"
GRAFANA_PORT="${GRAFANA_PORT:-3001}"
export GRAFANA_PORT
COMPOSE_DIR="${GENERATED_ROOT}/code/target-generated/cdm-lifecycle-mapping"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"
# Named volume holding the 017 Postgres data: <project>_postgres_state_017_data.
DATA_VOLUME_KEY="postgres_state_017_data"
DATA_VOLUME_NAME="${COMPOSE_PROJECT_NAME}_${DATA_VOLUME_KEY}"
DRY_RUN=0
SKIP_BUILD=0
RESET_DATA=0

usage() {
  cat <<EOF_USAGE
usage: $(basename "${BASH_SOURCE[0]}") [--dry-run] [--skip-build] [--reset-data]

Starts the state 017 runtime (009 + cdm-service) with docker compose
(project ${COMPOSE_PROJECT_NAME}).

  --dry-run     print the compose command without running it
  --skip-build  start without rebuilding images
  --reset-data  remove the 017 Postgres data volume (${DATA_VOLUME_NAME})
                before starting. The schema (cdm_documents etc.) is only created
                when the volume is first initialised, so a volume left by an
                older 017 build must be reset. This deletes all 017 orders,
                trades and CDM documents.

State 017 uses the same host ports as state 009 (8080, 18083-18110, 4222,
9090, 3001, ...). Only one of them can run at a time: stop 009 first with
./scripts/stop-state-009-order-management-matcher-generated.sh.
The order matcher seeds only its NEW demo orders in 017 (ORDER_MATCHER_SEED_STATUSES=NEW);
cdm-service imports them as CDM proposals at startup.

Loki log ingestion: the start script sets TRADERX_PROMTAIL_DOCKER_API_VERSION to the Docker
server's API version (fallback 1.44) and compose passes it to promtail as DOCKER_API_VERSION,
but promtail 2.9.8's Docker discovery still uses API 1.42. On Docker Engines whose minimum API
version is above 1.42 Loki stays empty and no value of the variable fixes it (a newer promtail
image would); engines that still accept 1.42 are unaffected. State 009 is the same.
EOF_USAGE
}

while (( "$#" )); do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --reset-data)
      RESET_DATA=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[error] unknown argument: $1"
      echo "[hint] supported: --dry-run --skip-build --reset-data --help"
      exit 1
      ;;
  esac
  shift
done

if ! command -v docker >/dev/null 2>&1; then
  echo "[error] docker command not found"
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "[error] docker compose plugin is required"
  exit 1
fi

if [[ -z "${DOCKER_BUILDKIT:-}" ]]; then
  export DOCKER_BUILDKIT=1
  echo "[info] DOCKER_BUILDKIT not set; defaulting to 1 for Docker cache mounts"
fi

traderx_configure_observability_runtime "${STATE_ID}"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  bash "${REPO_ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
else
  echo "[info] skipping state generation for ${STATE_ID} (TRADERX_SKIP_GENERATE=1)"
fi

[[ -f "${COMPOSE_FILE}" ]] || {
  echo "[error] compose file not found: ${COMPOSE_FILE}"
  exit 1
}

traderx_print_observability_runtime_summary

compose() {
  docker compose -f "${COMPOSE_FILE}" --project-name "${COMPOSE_PROJECT_NAME}" "$@"
}

# 009 and 017 bind the same host ports; refuse to start next to a running 009.
if [[ -n "$(docker ps -q --filter label=com.docker.compose.project=traderx-state-009 2>/dev/null)" ]]; then
  echo "[error] state 009 (compose project traderx-state-009) is running and uses the same host ports"
  echo "[hint] stop it first: ./scripts/stop-state-009-order-management-matcher-generated.sh"
  exit 1
fi

if (( RESET_DATA == 1 )); then
  # Never let an inherited COMPOSE_PROJECT_NAME tear down (and delete the data of) a
  # project that is not a state 017 runtime.
  if [[ "${COMPOSE_PROJECT_NAME}" != traderx-state-017* ]]; then
    echo "[error] --reset-data only resets a traderx-state-017 project, but COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME}"
    echo "[hint] unset COMPOSE_PROJECT_NAME (default traderx-state-017) and rerun"
    exit 1
  fi
  if (( DRY_RUN == 1 )); then
    echo "[dry-run] docker compose -f ${COMPOSE_FILE} --project-name ${COMPOSE_PROJECT_NAME} down --remove-orphans"
    echo "[dry-run] docker volume rm ${DATA_VOLUME_NAME}"
  else
    echo "[info] --reset-data: removing 017 data volume ${DATA_VOLUME_NAME}"
    compose down --remove-orphans
    if docker volume inspect "${DATA_VOLUME_NAME}" >/dev/null 2>&1; then
      docker volume rm "${DATA_VOLUME_NAME}" >/dev/null
      echo "[ok] removed ${DATA_VOLUME_NAME}"
    else
      echo "[info] no ${DATA_VOLUME_NAME} volume to remove"
    fi
  fi
fi

wait_for_postgres() {
  local attempts=90
  local i
  for ((i=1; i<=attempts; i++)); do
    if compose exec -T database pg_isready -U traderx -d traderx >/dev/null 2>&1; then
      echo "[ready] postgres database"
      return 0
    fi
    sleep 2
  done
  echo "[error] timeout waiting for postgres readiness"
  return 1
}

# Postgres only runs postgres-init/initialSchema.sql when the data volume is
# first created. A volume left by 009-era or older 017 builds lacks the CDM
# tables (and trades.orderid), or, from a 017 build before the fill-instruction
# amendment, the cdm_documents produced_by_* columns; cdm-service would fail
# schema validation.
check_existing_data_volume() {
  if ! docker volume inspect "${DATA_VOLUME_NAME}" >/dev/null 2>&1; then
    echo "[info] no existing ${DATA_VOLUME_NAME}; the 017 schema will be created on first start"
    return 0
  fi
  echo "[info] found existing data volume ${DATA_VOLUME_NAME}; checking its schema"
  compose up -d database >/dev/null
  wait_for_postgres || return 1
  local marker psql_status=0 psql_err
  psql_err="$(mktemp)"
  marker="$(compose exec -T database psql -v ON_ERROR_STOP=1 -U traderx -d traderx -tAc "
    SELECT (to_regclass('public.cdm_documents') IS NOT NULL
        AND to_regclass('public.cdm_pending_trades') IS NOT NULL
        AND to_regclass('public.cdm_pending_cancels') IS NOT NULL
        AND to_regclass('public.cdm_lifecycle_failures') IS NOT NULL
        AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'cdm_documents' AND column_name = 'id')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'cdm_documents' AND column_name = 'produced_by_ref')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'cdm_documents' AND column_name = 'produced_by_global_key')
        AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'trades' AND column_name = 'orderid'))::text" 2>"${psql_err}")" || psql_status=$?
  marker="$(printf '%s' "${marker}" | tr -d '[:space:]')"
  if (( psql_status != 0 )) || [[ "${marker}" != "true" && "${marker}" != "false" ]]; then
    # The check itself failed: this says nothing about the volume, so do not suggest a reset.
    echo "[error] could not check the schema of data volume ${DATA_VOLUME_NAME} (psql exit ${psql_status}, output '${marker}')"
    sed 's/^/[error]   /' "${psql_err}"
    rm -f "${psql_err}"
    compose stop database >/dev/null 2>&1 || true
    return 1
  fi
  rm -f "${psql_err}"
  if [[ "${marker}" == "false" ]]; then
    echo "[error] data volume ${DATA_VOLUME_NAME} was created by an older build and lacks the state 017 schema"
    echo "[error] (expected tables cdm_documents, cdm_pending_trades, cdm_pending_cancels, cdm_lifecycle_failures,"
    echo "[error]  columns cdm_documents.produced_by_ref and produced_by_global_key, and column trades.orderid)"
    echo "[hint] rerun with --reset-data to remove the volume and start with a fresh database:"
    echo "[hint]   ./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data"
    compose stop database >/dev/null 2>&1 || true
    return 1
  fi
  echo "[ok] data volume schema is current"
  # The dealer/client party model (Ruling R69) did not change the schema, so data written before
  # it passes the check above. Such data has the venue as counterparty Party2 on trade states and
  # order steps; every reading of them would then mix up dealer, client and venue.
  local old_parties
  old_parties="$(compose exec -T database psql -v ON_ERROR_STOP=1 -U traderx -d traderx -tAc "
    SELECT EXISTS (SELECT 1 FROM cdm_documents
      WHERE cdm_type IN ('cdm.event.common.TradeState', 'cdm.event.workflow.WorkflowStep')
        AND jsonb_path_exists(payload::jsonb,
          '\$.** ? (@.role == \"Party2\" && @.partyReference.externalReference == \"traderx-matching-engine\")'))::text" \
    2>/dev/null | tr -d '[:space:]')" || old_parties="unknown"
  if [[ "${old_parties}" == "true" ]]; then
    echo "[error] data volume ${DATA_VOLUME_NAME} holds CDM data from a 017 build before the dealer/client"
    echo "[error] party model (the matching engine as counterparty Party2); it would be read with the wrong parties"
    echo "[hint] rerun with --reset-data to remove the volume and start with a fresh database:"
    echo "[hint]   ./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data"
    compose stop database >/dev/null 2>&1 || true
    return 1
  elif [[ "${old_parties}" != "false" ]]; then
    echo "[warn] could not check data volume ${DATA_VOLUME_NAME} for data from before the dealer/client party model"
  fi
}

if (( DRY_RUN == 1 )); then
  if (( SKIP_BUILD == 1 )); then
    echo "[dry-run] docker compose -f ${COMPOSE_FILE} --project-name ${COMPOSE_PROJECT_NAME} up -d --no-build"
  else
    echo "[dry-run] docker compose -f ${COMPOSE_FILE} --project-name ${COMPOSE_PROJECT_NAME} up -d --build"
  fi
  echo "[done] dry run complete for state 017"
  exit 0
fi

check_existing_data_volume || exit 1

if (( SKIP_BUILD == 1 )); then
  docker compose -f "${COMPOSE_FILE}" --project-name "${COMPOSE_PROJECT_NAME}" up -d --no-build
else
  docker compose -f "${COMPOSE_FILE}" --project-name "${COMPOSE_PROJECT_NAME}" up -d --build
fi

# Grafana only provisions file-based dashboards on startup.
# Restart once after compose up so updated generated dashboards are always loaded.
docker compose -f "${COMPOSE_FILE}" --project-name "${COMPOSE_PROJECT_NAME}" restart grafana >/dev/null 2>&1 || true

wait_for_http() {
  local name="$1"
  local url="$2"
  local attempts=90
  local i
  for ((i=1; i<=attempts; i++)); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "[ready] ${name} ${url}"
      return 0
    fi
    sleep 2
  done
  echo "[error] timeout waiting for ${name} at ${url}"
  return 1
}

wait_for_postgres || exit 1
wait_for_http "cdm-service" "http://localhost:18120/health" || exit 1
wait_for_http "cdm-service-ingress" "http://localhost:8080/cdm-service/health" || exit 1
wait_for_http "reference-data" "http://localhost:18085/stocks" || exit 1
wait_for_http "nats-monitor" "http://localhost:8222/varz" || exit 1
wait_for_http "price-publisher" "http://localhost:18100/health" || exit 1
wait_for_http "order-matcher-health" "http://localhost:18110/health" || exit 1
wait_for_http "ingress" "http://localhost:8080/health" || exit 1
wait_for_http "grafana" "http://localhost:${GRAFANA_PORT}/api/health" || exit 1
wait_for_http "prometheus" "http://localhost:9090/-/ready" || exit 1
wait_for_http "loki" "http://localhost:3100/ready" || exit 1
wait_for_http "tempo" "http://localhost:3200/ready" || exit 1
wait_for_http "otel-collector-health" "http://localhost:13133/" || exit 1

bash "${REPO_ROOT}/scripts/start-grafana-traderx-dashboards.sh" \
  "http://localhost:${GRAFANA_PORT}" \
  "${TRADERX_GRAFANA_ADMIN_USER}" \
  "${TRADERX_GRAFANA_ADMIN_PASSWORD}" \
  "TraderX" \
  "traderx-obs-008-order-management" || true

echo "[done] state 017 CDM lifecycle runtime started"
echo "[ui] http://localhost:8080"
echo "[api-explorer] http://localhost:8080/api/docs"
echo "[grafana] http://localhost:${GRAFANA_PORT} (local admin login)"
echo "[grafana-public] http://localhost:8080/grafana/ (anonymous Viewer dashboards)"
echo "[cdm-service] http://localhost:8080/cdm-service/health"
