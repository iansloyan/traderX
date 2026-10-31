#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${REPO_ROOT}/generated}"

if [[ "${TRADERX_LOCAL_RUNTIME_SCRIPT:-0}" != "1" ]]; then
  LOCAL_RUNTIME_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/$(basename "${BASH_SOURCE[0]}")"
  if [[ -x "${LOCAL_RUNTIME_SCRIPT}" ]]; then
    exec "${LOCAL_RUNTIME_SCRIPT}" "$@"
  fi
fi
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-traderx-state-017}"
COMPOSE_FILE="${GENERATED_ROOT}/code/target-generated/cdm-lifecycle-mapping/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1; then
  echo "[error] docker command not found"
  exit 1
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "[info] compose file not found; nothing to stop: ${COMPOSE_FILE}"
  exit 0
fi

# Never let an inherited COMPOSE_PROJECT_NAME take down a project that is not a state 017 runtime.
if [[ "${COMPOSE_PROJECT_NAME}" != traderx-state-017* ]]; then
  echo "[error] refusing to stop compose project ${COMPOSE_PROJECT_NAME}: not a traderx-state-017 project"
  echo "[hint] unset COMPOSE_PROJECT_NAME (default traderx-state-017) and rerun"
  exit 1
fi

docker compose -f "${COMPOSE_FILE}" --project-name "${COMPOSE_PROJECT_NAME}" down --remove-orphans
echo "[done] state 017 CDM lifecycle runtime stopped"
