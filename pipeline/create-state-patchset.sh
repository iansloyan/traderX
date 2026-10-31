#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${ROOT}/generated}"
STATE_ID="${1:-}"
PARENT_STATE_ID="${2:-}"
TARGET_PATH_ARG="${3:-${GENERATED_ROOT}/code/target-generated}"

if [[ "${TARGET_PATH_ARG}" = /* ]]; then
  TARGET_PATH="${TARGET_PATH_ARG}"
else
  TARGET_PATH="${ROOT}/${TARGET_PATH_ARG}"
fi

if [[ -z "${STATE_ID}" ]]; then
  echo "usage: bash pipeline/create-state-patchset.sh <state-id> [parent-state-id] [target-path]"
  echo "example: bash pipeline/create-state-patchset.sh 006-messaging-nats-replacement"
  echo "example: bash pipeline/create-state-patchset.sh 002-edge-proxy-uncontainerized 001-baseline-uncontainerized-parity generated/code/components"
  echo "env: TRADERX_GENERATED_ROOT=/abs/path/to/generated"
  echo "env: TRADERX_PATCHSET_CHILD_FROM_TARGET=1  capture the current target tree as the child"
  echo "     (instead of regenerating the child state); use after editing generated output in place"
  exit 1
fi

if [[ -z "${PARENT_STATE_ID}" ]]; then
  if ! command -v jq >/dev/null 2>&1; then
    echo "[fail] jq is required when parent-state-id is omitted"
    exit 1
  fi

  PARENT_STATE_ID="$(
    jq -r --arg state "${STATE_ID}" '.states[] | select(.id == $state) | .previous[0] // empty' \
      "${ROOT}/catalog/state-catalog.json"
  )"
fi

if [[ -z "${PARENT_STATE_ID}" ]]; then
  echo "[fail] unable to resolve parent state for ${STATE_ID}"
  exit 1
fi

PATCH_DIR="${ROOT}/specs/${STATE_ID}/generation/patches"
PATCH_FILE="${PATCH_DIR}/0001-state-overlay.patch"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PARENT_SNAPSHOT="${TMP_DIR}/parent"
CHILD_SNAPSHOT="${TMP_DIR}/child"
DIFF_REPO="${TMP_DIR}/diff-repo"
RSYNC_EXCLUDES=(
  "--exclude=.git"
  "--exclude=.DS_Store"
  "--exclude=node_modules"
  "--exclude=node_modules/**"
  "--exclude=.gradle"
  "--exclude=.gradle/**"
  "--exclude=build"
  "--exclude=build/**"
  "--exclude=target"
  "--exclude=target/**"
  "--exclude=bin"
  "--exclude=bin/**"
  "--exclude=obj"
  "--exclude=obj/**"
  "--exclude=dist"
  "--exclude=dist/**"
  "--exclude=coverage"
  "--exclude=coverage/**"
  "--exclude=yarn.lock"
  "--exclude=pnpm-lock.yaml"
  "--exclude=gradlew"
  "--exclude=gradlew.bat"
  "--exclude=gradle/wrapper/gradle-wrapper.jar"
  "--exclude=gradle/wrapper/gradle-wrapper.properties"
  "--exclude=gradle/wrapper/**"
  "--exclude=.angular"
  "--exclude=*.log"
)

# Optional state-specific capture excludes (rsync filter patterns, one per
# line). Use for paths that the state owns outside the patch set, e.g. a new
# component copied in by the state render script from the state pack.
STATE_EXCLUDES_FILE="${ROOT}/specs/${STATE_ID}/generation/patch-capture-excludes.txt"
if [[ -f "${STATE_EXCLUDES_FILE}" ]]; then
  echo "[info] applying state capture excludes: ${STATE_EXCLUDES_FILE}"
  RSYNC_EXCLUDES+=("--exclude-from=${STATE_EXCLUDES_FILE}")
fi

# By default the child snapshot is produced by regenerating the child state.
# With TRADERX_PATCHSET_CHILD_FROM_TARGET=1 the current target tree (e.g. a
# generated tree edited in place) is snapshotted as the child before the parent
# is regenerated. The target tree must have been generated for STATE_ID (checked
# via ci/state-metadata.json). After a successful capture the child state is
# regenerated from the new patch, so the tree is left holding child output and
# repeated captures are safe.
CHILD_FROM_TARGET="${TRADERX_PATCHSET_CHILD_FROM_TARGET:-0}"

if [[ "${CHILD_FROM_TARGET}" == "1" ]]; then
  if [[ ! -d "${TARGET_PATH}" ]]; then
    echo "[fail] target path does not exist for child capture: ${TARGET_PATH}"
    exit 1
  fi
  TARGET_STATE_METADATA="${TARGET_PATH}/ci/state-metadata.json"
  TARGET_STATE_ID=""
  if [[ -f "${TARGET_STATE_METADATA}" ]]; then
    TARGET_STATE_ID="$(jq -r '.stateId // empty' "${TARGET_STATE_METADATA}" 2>/dev/null || true)"
  fi
  if [[ "${TARGET_STATE_ID}" != "${STATE_ID}" ]]; then
    echo "[fail] target tree is not a ${STATE_ID} tree (ci/state-metadata.json stateId='${TARGET_STATE_ID:-<missing>}')"
    echo "[hint] capturing it would overwrite the ${STATE_ID} patch with a partial or empty diff"
    echo "[hint] run: bash pipeline/generate-state.sh ${STATE_ID}  (then re-apply your edits and capture)"
    exit 1
  fi
  echo "[info] capturing current target tree as child state ${STATE_ID}: ${TARGET_PATH}"
  mkdir -p "${CHILD_SNAPSHOT}"
  rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${TARGET_PATH}/" "${CHILD_SNAPSHOT}/"
fi

echo "[info] generating parent state ${PARENT_STATE_ID}"
bash "${ROOT}/pipeline/generate-state.sh" "${PARENT_STATE_ID}"
if [[ ! -d "${TARGET_PATH}" ]]; then
  echo "[fail] target path does not exist after parent generation: ${TARGET_PATH}"
  exit 1
fi
mkdir -p "${PARENT_SNAPSHOT}"
rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${TARGET_PATH}/" "${PARENT_SNAPSHOT}/"

if [[ "${CHILD_FROM_TARGET}" != "1" ]]; then
  echo "[info] generating child state ${STATE_ID}"
  bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"
  if [[ ! -d "${TARGET_PATH}" ]]; then
    echo "[fail] target path does not exist after child generation: ${TARGET_PATH}"
    exit 1
  fi
  mkdir -p "${CHILD_SNAPSHOT}"
  rsync -a --delete "${RSYNC_EXCLUDES[@]}" "${TARGET_PATH}/" "${CHILD_SNAPSHOT}/"
fi

rm -rf "${DIFF_REPO}"
mkdir -p "${DIFF_REPO}"
rsync -a --delete "${PARENT_SNAPSHOT}/" "${DIFF_REPO}/"

git -C "${DIFF_REPO}" init -q
git -C "${DIFF_REPO}" add -A
git -C "${DIFF_REPO}" commit --allow-empty -qm "parent-${PARENT_STATE_ID}"

rsync -a --delete --exclude='.git' "${CHILD_SNAPSHOT}/" "${DIFF_REPO}/"
git -C "${DIFF_REPO}" add -A

mkdir -p "${PATCH_DIR}"
# Write to a temp file first so an empty diff never overwrites an existing patch.
NEW_PATCH_FILE="${TMP_DIR}/0001-state-overlay.patch"
if git -C "${DIFF_REPO}" diff --cached --quiet; then
  : > "${NEW_PATCH_FILE}"
  echo "[warn] no differences detected between ${PARENT_STATE_ID} and ${STATE_ID}"
else
  git -C "${DIFF_REPO}" diff --cached --binary > "${NEW_PATCH_FILE}"
fi

if [[ ! -s "${NEW_PATCH_FILE}" ]]; then
  echo "[fail] empty patch produced; left existing patch file untouched: ${PATCH_FILE}"
  exit 1
fi
PREVIOUS_PATCH_FILE="${TMP_DIR}/previous-0001-state-overlay.patch"
HAD_PREVIOUS_PATCH=0
if [[ -f "${PATCH_FILE}" ]]; then
  cp "${PATCH_FILE}" "${PREVIOUS_PATCH_FILE}"
  HAD_PREVIOUS_PATCH=1
fi
cp "${NEW_PATCH_FILE}" "${PATCH_FILE}"

echo "[done] wrote patch set for ${STATE_ID}: ${PATCH_FILE}"
if [[ "${CHILD_FROM_TARGET}" == "1" ]]; then
  echo "[info] regenerating child state ${STATE_ID} from the captured patch"
  if ! bash "${ROOT}/pipeline/generate-state.sh" "${STATE_ID}"; then
    # Keep the previous (known-good) patch in place, park the rejected one, and
    # preserve the captured child snapshot so in-place edits are not lost.
    cp "${NEW_PATCH_FILE}" "${PATCH_FILE}.rejected"
    if (( HAD_PREVIOUS_PATCH == 1 )); then
      cp "${PREVIOUS_PATCH_FILE}" "${PATCH_FILE}"
    else
      rm -f "${PATCH_FILE}"
    fi
    trap - EXIT
    echo "[fail] regeneration of ${STATE_ID} from the captured patch failed"
    echo "[hint] previous patch restored: ${PATCH_FILE}"
    echo "[hint] captured patch kept for inspection: ${PATCH_FILE}.rejected"
    echo "[hint] captured child snapshot (your edits) preserved at: ${CHILD_SNAPSHOT}"
    exit 1
  fi
  echo "[done] target tree holds regenerated ${STATE_ID} output"
fi
