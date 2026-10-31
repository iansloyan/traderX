#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ID="017-cdm-lifecycle-mapping"
PARENT_STATE_ID="009-order-management-matcher"

# This file is a state hook used by pipeline/generate-state.sh. When invoked
# directly, delegate to the canonical entrypoint so post-generation installers
# (runtime harness/env wrappers/CI assets/docs refresh) run for state 017.
if (( ${TRADERX_GENERATION_DEPTH:-0} == 0 )); then
  echo "[info] delegating direct invocation to pipeline/generate-state.sh ${STATE_ID}"
  exec bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
fi

echo "[info] generating parent state ${PARENT_STATE_ID} for ${STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/apply-state-patchset.sh" "${STATE_ID}"
bash "${ROOT}/pipeline/render-state-017-cdm-lifecycle-mapping.sh"
bash "${ROOT}/pipeline/generate-state-architecture-doc.sh" "${STATE_ID}"

cat <<'EOT'
[summary] state=017-cdm-lifecycle-mapping
[summary] parent-state=009-order-management-matcher
[summary] track=functional
[summary] impacted-assets=cdm-service,cdm-lifecycle-mapping-compose
[summary] generated-path=generated/code/target-generated/cdm-lifecycle-mapping
[summary] runtime-entrypoint=./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh
EOT
