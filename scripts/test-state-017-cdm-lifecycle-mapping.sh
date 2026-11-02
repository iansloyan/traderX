#!/usr/bin/env bash
set -euo pipefail

# State 017 (CDM Lifecycle, Party and Security Model) scenario validation.
#
# Drives a running 017 stack through its ingress REST APIs only and checks that every TraderX
# lifecycle event ends up as valid FINOS CDM objects in cdm-service:
#   (a) direct trade            cdm:direct workflow proposed -> accepted, trade state first, OffFacility,
#                               the facing subsidiary (Party1) against the client (Party2), no venue
#   (b) order fills completely  venue approval, two fill workflows (OnVenue), executedQuantity = quantity
#   (c) order fills partly (force-fill 750), then is cancelled; (c') a full force-fill;
#       (c'') an order cancelled with no fills
#   (d) rejected order          400 from the matcher, rejected step + approval false in /cdm/rejections
#   (e) round trips             account, person, stock, trade, position (trades sum to quantity)
#   (g) cdm-service outage      order placed, filled twice and cancelled while cdm-service misses it;
#       the startup backfill rebuilds the same lineage shape and fill numbers (needs docker)
#   (h) facing subsidiaries     three TraderX subsidiaries; account-service shows each account's;
#                               switching it changes the dealer (Party1) of later trades only
#   (f) validation              nothing INVALID or NOT_QUALIFIED, no BusinessEvent rows, no failure rows
#
# Prerequisites: bash, curl, jq, and a running 017 stack
# (./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data). (g) also needs the
# docker CLI to stop/start cdm-service and pause/unpause trade-processor in compose project
# ${COMPOSE_PROJECT_NAME:-traderx-state-017}; set TRADERX_CDM_SKIP_OUTAGE=1 to skip it.
# Every CDM assertion polls for up to TRADERX_CDM_POLL_TIMEOUT_SECONDS (default 15) because
# cdm-service builds CDM objects asynchronously from NATS events.
# Prints one [pass]/[fail] line per assertion and exits non-zero if any assertion fails.
# See specs/017-cdm-lifecycle-mapping/tests/smoke/README.md.

INGRESS_URL="http://localhost:8080"
ACCOUNT_ID="${TRADERX_TEST_ACCOUNT_ID:-22214}"
TICKER="${TRADERX_TEST_TICKER:-IBM}"
PERSON_LOGON_ID="${TRADERX_TEST_LOGON_ID:-user01}"
POLL_TIMEOUT_SECONDS="${TRADERX_CDM_POLL_TIMEOUT_SECONDS:-15}"
# The startup backfill (seed trades, open matcher orders) retries with up to 30 s backoff while
# the matcher starts, so the preflight waits longer for it than for a single assertion.
BACKFILL_TIMEOUT_SECONDS="${TRADERX_CDM_BACKFILL_TIMEOUT_SECONDS:-120}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${TRADERX_GENERATED_ROOT:-${REPO_ROOT}/generated}"
STACK_PROJECT="${COMPOSE_PROJECT_NAME:-traderx-state-017}"
SKIP_OUTAGE="${TRADERX_CDM_SKIP_OUTAGE:-0}"

usage() {
  cat <<EOF_USAGE
usage: $(basename "${BASH_SOURCE[0]}") [INGRESS_URL]

Runs the state 017 CDM lifecycle scenarios against a running stack (default ${INGRESS_URL}).
Environment: TRADERX_TEST_ACCOUNT_ID (${ACCOUNT_ID}), TRADERX_TEST_TICKER (${TICKER}),
TRADERX_TEST_LOGON_ID (${PERSON_LOGON_ID}), TRADERX_CDM_POLL_TIMEOUT_SECONDS (${POLL_TIMEOUT_SECONDS}),
TRADERX_CDM_BACKFILL_TIMEOUT_SECONDS (${BACKFILL_TIMEOUT_SECONDS}),
COMPOSE_PROJECT_NAME (${STACK_PROJECT}, for (g)), TRADERX_CDM_SKIP_OUTAGE (${SKIP_OUTAGE}; 1 skips (g)).
EOF_USAGE
}

while (( "$#" )); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ "${INGRESS_URL}" == "http://localhost:8080" ]]; then
        INGRESS_URL="${1%/}"
      else
        echo "[error] unknown argument: $1"
        usage
        exit 1
      fi
      ;;
  esac
  shift
done

if [[ "${TRADERX_LOCAL_RUNTIME_SCRIPT:-0}" != "1" ]]; then
  LOCAL_RUNTIME_SCRIPT="${GENERATED_ROOT}/code/target-generated/scripts/$(basename "${BASH_SOURCE[0]}")"
  if [[ -x "${LOCAL_RUNTIME_SCRIPT}" ]]; then
    exec "${LOCAL_RUNTIME_SCRIPT}" "${INGRESS_URL}"
  fi
fi

for tool in curl jq; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "[error] ${tool} command not found"
    exit 1
  fi
done

CDM="${INGRESS_URL}/cdm-service"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
PASSED=0
FAILED=0
STATUS=""
BODY=""

# --- helpers -----------------------------------------------------------------------------------

pass() {
  PASSED=$((PASSED + 1))
  echo "[pass] $1"
}

fail() {
  FAILED=$((FAILED + 1))
  echo "[fail] $1"
  if [[ -n "${2:-}" ]]; then
    echo "       $2"
  fi
}

# fetch METHOD URL [JSON]: sets STATUS (HTTP code, 000 if unreachable) and BODY; the response
# headers are kept for header().
fetch() {
  local method="$1" url="$2" data="${3:-}"
  local args=(-sS --max-time 10 -o "${WORK_DIR}/body" -D "${WORK_DIR}/headers" -w '%{http_code}' -X "${method}")
  if [[ -n "${data}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${data}")
  fi
  : >"${WORK_DIR}/headers"
  STATUS="$(curl "${args[@]}" "${url}" 2>/dev/null)" || STATUS="000"
  BODY="$(cat "${WORK_DIR}/body" 2>/dev/null || true)"
}

# header NAME: the value of response header NAME (case-insensitive) of the last fetch, or nothing.
header() {
  awk -v name="$(tr '[:upper:]' '[:lower:]' <<<"$1")" -F': ' \
    'tolower($1) == name { sub(/\r$/, "", $2); print $2; exit }' "${WORK_DIR}/headers" 2>/dev/null || true
}

# expect_header LABEL URL HEADER VALUE: polls GET URL until it answers 200 with HEADER = VALUE.
expect_header() {
  local label="$1" url="$2" name="$3" value="$4" got=""
  local deadline=$((SECONDS + POLL_TIMEOUT_SECONDS))
  while :; do
    fetch GET "${url}"
    got="$(header "${name}")"
    if [[ "${STATUS}" == "200" && "${got}" == "${value}" ]]; then
      pass "${label}"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      break
    fi
    sleep 0.5
  done
  fail "${label}" "after ${POLL_TIMEOUT_SECONDS}s: HTTP ${STATUS} from ${url}: ${name}='${got}', expected '${value}'"
  return 1
}

# jq_true JSON FILTER [jq args...]: true if FILTER evaluates to true on JSON (false on bad JSON).
jq_true() {
  local json="$1" filter="$2"
  shift 2
  jq -e "$@" "${filter}" >/dev/null 2>&1 <<<"${json}"
}

# jq_out JSON FILTER [jq args...]: FILTER's compact output, or nothing on bad JSON/filter error.
jq_out() {
  local json="$1" filter="$2"
  shift 2
  jq -c "$@" "${filter}" 2>/dev/null <<<"${json}" || true
}

# expect LABEL URL FILTER [jq args...]: polls GET URL until it answers 200 with a body for
# which FILTER is true, for up to POLL_TIMEOUT_SECONDS. Prints [pass]/[fail]; leaves the last
# response in STATUS/BODY.
expect() {
  local label="$1" url="$2" filter="$3"
  shift 3
  local deadline=$((SECONDS + POLL_TIMEOUT_SECONDS))
  while :; do
    fetch GET "${url}"
    if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" "${filter}" "$@"; then
      pass "${label}"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      break
    fi
    sleep 0.5
  done
  fail "${label}" "after ${POLL_TIMEOUT_SECONDS}s: HTTP ${STATUS} from ${url}: $(head -c 600 <<<"${BODY}")"
  return 1
}

# expect_equal LABEL URL DERIVE EXPECTED [jq args...]: polls GET URL until DERIVE (jq over the
# body) equals the JSON EXPECTED. Key order and number formatting (57.500 vs 57.5) do not
# matter. On failure prints both sides.
expect_equal() {
  local label="$1" url="$2" derive="$3" expected="$4"
  shift 4
  local deadline=$((SECONDS + POLL_TIMEOUT_SECONDS)) derived=""
  while :; do
    fetch GET "${url}"
    if [[ "${STATUS}" == "200" ]]; then
      derived="$(jq_out "${BODY}" "${derive}" "$@")"
      if [[ -n "${derived}" ]] \
        && jq -n -e --argjson a "${expected}" --argjson b "${derived}" '$a == $b' >/dev/null 2>&1; then
        pass "${label}"
        return 0
      fi
    fi
    if (( SECONDS >= deadline )); then
      break
    fi
    sleep 0.5
  done
  fail "${label}" "after ${POLL_TIMEOUT_SECONDS}s (HTTP ${STATUS} from ${url}): expected $(jq_out "${expected}" '.' -S) got ${derived:-<nothing>}"
  return 1
}

# market_price: the ticker's current price (a JSON number), or nothing.
market_price() {
  fetch GET "${INGRESS_URL}/price-publisher/prices/${TICKER}"
  if [[ "${STATUS}" == "200" ]]; then
    jq_out "${BODY}" 'if (.price | type) == "number" then .price else empty end'
  fi
}

# limit_at FACTOR: the current market price times FACTOR, to 3 decimals (nothing if no price).
limit_at() {
  local p
  p="$(market_price)"
  if [[ -n "${p}" ]]; then
    jq -n --argjson p "${p}" --argjson f "$1" '($p * $f * 1000 | round) / 1000'
  fi
}

# create_order QUANTITY LIMIT: POST a Buy order; sets ORDER_ID (empty on failure) and leaves
# the response in STATUS/BODY (so it is not called in a subshell).
ORDER_ID=""
create_order() {
  ORDER_ID=""
  if [[ -z "$2" ]]; then
    STATUS="000"
    BODY="no market price for ${TICKER} to set a limit from"
    return
  fi
  fetch POST "${INGRESS_URL}/order-matcher/orders" \
    "$(jq -n -c --argjson a "${ACCOUNT_ID}" --arg s "${TICKER}" --argjson q "$1" --argjson l "$2" \
      '{accountId: $a, security: $s, side: "Buy", quantity: $q, limitPrice: $l}')"
  if [[ "${STATUS}" == "201" ]]; then
    ORDER_ID="$(jq_out "${BODY}" '.orderId // empty' -r)"
  fi
}

lineage_url() {
  echo "${CDM}/cdm/orders/$1/lineage"
}

VENUE_REF="cdm:party:traderx-matching-engine"
# Order lineage (GET /cdm/orders/{id}/lineage):
# The order's own workflow is proposed -> approved: the venue's approval (WorkflowStep.approval,
# approved true, by the venue party), sent OnVenue with its meaning stated.
ORDER_APPROVED='[.proposal.versions[].stepType] == ["proposed", "approved"]
  and .proposal.approval.approved == true and .proposal.approval.party == $venue
  and .proposal.approval.stepStoreGlobalKey == .proposal.versions[1].storeGlobalKey
  and .proposal.executionType == "OnVenue" and (.proposal.executionTypeMeaning | type == "string" and length > 0)'
# Every version VALID, stored, and each version (and the cancel, if any) links to the one before.
VERSIONS_VALID_AND_CHAINED='.proposal.versions as $v | ($v | length) > 1
  and all($v[]; .validation.status == "VALID" and (.storeGlobalKey | type == "string"))
  and $v[0].previousStoreGlobalKey == null
  and all(range(1; $v | length); $v[.].previousStoreGlobalKey == $v[. - 1].storeGlobalKey)
  and (.cancel == null or .cancel.previousStoreGlobalKey == $v[-1].storeGlobalKey)'
# Each fill is its own workflow: a VALID fill-proposed step (action New, no previous step,
# identified by the order id and the fill (trade) id, R42), then a VALID fill-accepted step
# linked to it (VALID includes Qualify_Execution on its BusinessEvent) whose event's after is the
# fill's trade state, which is VALID too; OnVenue with its meaning. Fills are numbered 1..n in
# booking order and filledSoFar is the running total.
FILLS_VALID='(.fills | length) > 0
  and [.fills[].fillNumber] == [range(1; (.fills | length) + 1)]
  and [.fills[].filledSoFar] == [foreach .fills[].quantity as $q (0; . + $q)]
  and all(.fills[];
    .executionType == "OnVenue" and (.executionTypeMeaning | type == "string" and length > 0)
    and .proposed.stepType == "fill-proposed" and .proposed.validation.status == "VALID"
    and .proposed.previousStoreGlobalKey == null and .proposed.object.action == "New"
    and (.proposed.object | has("previousWorkflowStep") | not)
    and (([.proposed.object.eventIdentifier[].assignedIdentifier[].identifier
           | {(.meta.scheme): .value}] | add) as $ids
         | $ids["https://traderx.finos.org/order-id"] == $order
           and $ids["https://traderx.finos.org/fill-id"] == .tradeId)
    and .accepted.stepType == "fill-accepted" and .accepted.validation.status == "VALID"
    and .accepted.previousStoreGlobalKey == .proposed.storeGlobalKey
    and .accepted.object.workflowState.workflowStatus == "Accepted"
    and (.accepted.object.businessEvent.after | length) == 1
    and .tradeState.cdmRef == ("cdm:trade:" + .tradeId) and .tradeState.validation.status == "VALID"
    and .tradeState.positionState == "Executed")'
# The cancel is a Correct of the proposal with workflowStatus Cancelled, VALID.
CANCELLED='.cancel.stepType == "cancel" and .cancel.validation.status == "VALID"
  and .cancel.object.workflowState.workflowStatus == "Cancelled"'
# Lineage reports failures (array) and pending (object with a trades array and a cancel field),
# and they are empty.
NOTHING_FAILED_OR_PENDING='(.failures | type) == "array" and (.failures | length) == 0
  and (.pending | type) == "object" and (.pending.trades | type) == "array"
  and (.pending.trades | length) == 0 and (.pending | has("cancel")) and .pending.cancel == null'
# ISO-8601 instant (optional fraction) -> epoch milliseconds.
DEF_MS='def ms: sub("Z$"; "") | split(".") as $p
  | ((($p[0] + "Z") | fromdateiso8601) * 1000) + ((($p[1] // "") + "000")[0:3] | tonumber);'

# --- preflight ---------------------------------------------------------------------------------

echo "[info] ingress=${INGRESS_URL} account=${ACCOUNT_ID} ticker=${TICKER} poll timeout=${POLL_TIMEOUT_SECONDS}s"
echo "[check] preflight"
expect "cdm-service is healthy through the ingress" "${CDM}/health" '.status == "ok"' || {
  echo "[error] cdm-service is not ready at ${CDM}/health; start the stack with"
  echo "        ./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh"
  exit 1
}
price="$(market_price)"
if [[ -z "${price}" ]]; then
  fail "market price for ${TICKER}" "HTTP ${STATUS} from ${INGRESS_URL}/price-publisher/prices/${TICKER}: $(head -c 300 <<<"${BODY}")"
  echo "[error] cannot set order limits without a price; is price-publisher running?"
  exit 1
fi
pass "market price for ${TICKER} is ${price}"

# Wait for the startup backfill so its objects (and any failures) are in before the baseline.
POLL_TIMEOUT_SECONDS_SAVED="${POLL_TIMEOUT_SECONDS}"
POLL_TIMEOUT_SECONDS="${BACKFILL_TIMEOUT_SECONDS}"
expect "cdm-service startup backfill is complete" "${CDM}/cdm/validation-summary" \
  '.backfill.complete == true and (.failures.total | type) == "number"' || {
  echo "[error] /cdm/validation-summary does not report a completed backfill"
  exit 1
}
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS_SAVED}"
FAILURES_BEFORE="$(jq_out "${BODY}" '.failures.total')"
if [[ "${FAILURES_BEFORE}" == "0" ]]; then
  pass "no lifecycle failure rows at the start of the run"
else
  fail "no lifecycle failure rows at the start of the run" \
    "${FAILURES_BEFORE} rows; most recent: $(jq_out "${BODY}" '.failures.recent')"
fi

# expect_order LABEL ORDER_ID FILTER [jq args...]: expect on the order's lineage, with $order
# (the order id) and $venue (the venue party's cdmRef) bound for FILTER.
expect_order() {
  local label="$1" order_id="$2" filter="$3"
  shift 3
  expect "${label}" "$(lineage_url "${order_id}")" "${filter}" \
    --arg order "${order_id}" --arg venue "${VENUE_REF}" "$@"
}

# check_fill_trades SCENARIO ORDER_ID: every fill of the order (from the last lineage response in
# BODY) is a trade whose record leads with its VALID TradeState, is produced by that fill of that
# order (same fill number and label as the lineage), is OnVenue, and resolves by cdmRef to the
# TradeState itself (no BusinessEvent stored under the trade ref).
check_fill_trades() {
  local scenario="$1" order_id="$2" fills fill trade_id
  fills="$(jq_out "${BODY}" '.fills[] | {tradeId, fillNumber, label}')"
  if [[ -z "${fills}" ]]; then
    fail "${scenario} fills listed in the lineage" "$(head -c 300 <<<"${BODY}")"
    return
  fi
  while IFS= read -r fill; do
    trade_id="$(jq_out "${fill}" '.tradeId' -r)"
    expect "${scenario} trade ${trade_id}: TradeState first, produced by $(jq_out "${fill}" '.label' -r) of ${order_id}, OnVenue" \
      "${CDM}/cdm/trades/${trade_id}" \
      '(keys_unsorted[0:2] == ["cdmRef", "tradeState"])
       and .tradeState.cdmType == "cdm.event.common.TradeState" and .tradeState.validation.status == "VALID"
       and .producedBy.kind == "fill" and .producedBy.orderId == $order
       and .producedBy.cdmRef == ("cdm:order:" + $order)
       and .producedBy.fillNumber == $fill.fillNumber and .producedBy.label == $fill.label
       and .executionType == "OnVenue" and (.executionTypeMeaning | type == "string" and length > 0)' \
      --arg order "${order_id}" --argjson fill "${fill}"
    expect_header "${scenario} trade ${trade_id}: cdm:trade resolves to the TradeState (no BusinessEvent under the trade ref)" \
      "${CDM}/cdm/resolve/cdm:trade:${trade_id}" "X-CDM-Type" "cdm.event.common.TradeState"
  done <<<"${fills}"
}

# --- (a) direct trade --------------------------------------------------------------------------

DIRECT_TRADE_ID=""
scenario_a() {
  echo "[check] (a) direct trade: buy 100 ${TICKER} for account ${ACCOUNT_ID}"
  fetch GET "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}"
  local before
  before="$(jq_out "${BODY}" '[.[].id]')"
  if [[ "${STATUS}" != "200" || -z "${before}" ]]; then
    fail "(a) position-service lists the account's trades" "HTTP ${STATUS}: $(head -c 300 <<<"${BODY}")"
    return
  fi
  fetch POST "${INGRESS_URL}/trade-service/trade/" \
    "$(jq -n -c --argjson a "${ACCOUNT_ID}" --arg s "${TICKER}" \
      '{accountId: $a, security: $s, side: "Buy", quantity: 100}')"
  if [[ "${STATUS}" != "200" ]]; then
    fail "(a) trade-service accepts the trade" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  pass "(a) trade-service accepts the trade"
  # trade-processor assigns the id; find the new 100-share buy among the account's trades.
  local new_trades='[.[] | select((.id as $id | $before | any(. == $id) | not)
      and .security == $s and .side == "Buy" and .quantity == 100)]'
  expect "(a) trade booked by trade-processor" "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}" \
    "${new_trades} | length > 0" --argjson before "${before}" --arg s "${TICKER}" || return
  DIRECT_TRADE_ID="$(jq_out "${BODY}" "${new_trades}[0].id" -r --argjson before "${before}" --arg s "${TICKER}")"
  echo "[info] (a) tradeId=${DIRECT_TRADE_ID}"
  local url="${CDM}/cdm/trades/${DIRECT_TRADE_ID}/lineage"
  expect "(a) trade lineage leads with the trade state, produced by cdm:direct:${DIRECT_TRADE_ID}" "${url}" \
    '(keys_unsorted[0:3] == ["cdmRef", "tradeId", "tradeState"])
     and .tradeState.cdmRef == ("cdm:trade:" + $id) and .producedBy.kind == "direct"
     and .producedBy.cdmRef == ("cdm:direct:" + $id) and .producedBy.orderId == null' \
    --arg id "${DIRECT_TRADE_ID}" || return
  expect "(a) direct workflow is proposed -> accepted, both VALID and chained" "${url}" \
    '.producedBy as $p | $p.proposed.stepType == "proposed" and $p.accepted.stepType == "accepted"
     and $p.proposed.validation.status == "VALID" and $p.accepted.validation.status == "VALID"
     and $p.proposed.previousStoreGlobalKey == null
     and $p.accepted.previousStoreGlobalKey == $p.proposed.storeGlobalKey
     and (($p.accepted.object.businessEvent.after // []) | length) == 1'
  expect "(a) trade state VALID, Executed, 100 Buy" "${url}" \
    '.tradeState.validation.status == "VALID" and .tradeState.positionState == "Executed"
     and .tradeState.quantity == 100 and .tradeState.side == "Buy"'
  expect "(a) executionType OffFacility with its meaning" "${url}" \
    '.executionType == "OffFacility" and (.executionTypeMeaning | type == "string" and length > 0)'
  expect "(a) the facing subsidiary is Party1 (dealer), the client Party2, and there is no venue" \
    "${CDM}/cdm/resolve/cdm:trade:${DIRECT_TRADE_ID}" \
    '.trade as $t
     | ([$t.counterparty[] | {(.role): .partyReference.externalReference}] | add)
       == {"Party1": $dealer, "Party2": ($acct | tostring)}
     and ([$t.partyRole[] | select(.role == "ExecutionFacility")] | length) == 0
     and ($t.executionDetails.executionVenue == null)' \
    --arg dealer "$(facing_entity "${ACCOUNT_ID}")" --argjson acct "${ACCOUNT_ID}"
  expect "(a) /cdm/trades: TradeState first, producedBy kind direct, OffFacility" \
    "${CDM}/cdm/trades/${DIRECT_TRADE_ID}" \
    '(keys_unsorted[0:2] == ["cdmRef", "tradeState"])
     and .tradeState.cdmType == "cdm.event.common.TradeState" and .tradeState.validation.status == "VALID"
     and .producedBy.kind == "direct" and .producedBy.cdmRef == ("cdm:direct:" + $id)
     and .executionType == "OffFacility"' --arg id "${DIRECT_TRADE_ID}"
  expect "(a) trade state resolves by cdmRef" "${CDM}/cdm/resolve/cdm:trade:${DIRECT_TRADE_ID}" \
    '.trade.tradeLot[0].priceQuantity[0].quantity.value.value == 100'
  if [[ "$(header X-CDM-Type)" == "cdm.event.common.TradeState" ]]; then
    pass "(a) cdm:trade:${DIRECT_TRADE_ID} is the TradeState (no BusinessEvent under the trade ref)"
  else
    fail "(a) cdm:trade:${DIRECT_TRADE_ID} is the TradeState" "X-CDM-Type '$(header X-CDM-Type)'"
  fi
  expect "(a) cdm:direct:${DIRECT_TRADE_ID} resolves to its CDM Workflow of two steps" \
    "${CDM}/cdm/resolve/cdm:direct:${DIRECT_TRADE_ID}" '(.steps | length) == 2'
  if [[ "$(header X-CDM-Type)" == "cdm.event.workflow.Workflow" && "$(header X-CDM-Validation)" == "VALID" ]]; then
    pass "(a) cdm:direct resolve headers: Workflow, VALID"
  else
    fail "(a) cdm:direct resolve headers: Workflow, VALID" \
      "X-CDM-Type '$(header X-CDM-Type)', X-CDM-Validation '$(header X-CDM-Validation)'"
  fi
}

# --- (b) order fills completely ----------------------------------------------------------------

FILLED_ORDER_ID=""
scenario_b() {
  # Limit 50% above market: in the money on every tick. Matcher fill policy: remaining >= 1000
  # fills half (rounded up), remaining < 1000 fills in full, so 1500 fills 750 then 750.
  echo "[check] (b) order of 1500 ${TICKER} with a limit above market fills completely"
  local limit order_id
  limit="$(limit_at 1.5)"
  create_order 1500 "${limit}"
  order_id="${ORDER_ID}"
  if [[ -z "${order_id}" ]]; then
    fail "(b) order accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  FILLED_ORDER_ID="${order_id}"
  if jq_true "${BODY}" '.cdmRef == ("cdm:order:" + .orderId)'; then
    pass "(b) order accepted by the matcher with cdmRef cdm:order:${order_id} (limit ${limit})"
  else
    fail "(b) order response carries cdmRef" "${BODY}"
  fi
  expect "(b) matcher reports FILLED" "${INGRESS_URL}/order-matcher/orders/${order_id}" \
    '.status == "FILLED" and .filledQuantity == 1500'
  expect_order "(b) lineage status filled with two fills" "${order_id}" \
    '.status == "filled" and (.fills | length) == 2 and (.fills | all(.accepted != null))' || return
  expect_order "(b) order proposal approved by the venue" "${order_id}" "${ORDER_APPROVED}"
  expect_order "(b) fills 750 and 750: Partial fill 1, Final fill 2" "${order_id}" \
    '[.fills[] | [.quantity, .label]] == [[750, "Partial fill 1"], [750, "Final fill 2"]]'
  expect_order "(b) executedQuantity 1500, canceledQuantity 0, remainingQuantity 0, no cancel" "${order_id}" \
    '.executedQuantity == 1500 and .remainingQuantity == 0 and .canceledQuantity == 0 and .cancel == null'
  expect_order "(b) order versions VALID and chained" "${order_id}" "${VERSIONS_VALID_AND_CHAINED}"
  expect_order "(b) each fill a VALID proposed -> accepted workflow with its trade state, OnVenue" \
    "${order_id}" "${FILLS_VALID}"
  expect_order "(b) no failures or pending events" "${order_id}" "${NOTHING_FAILED_OR_PENDING}"
  check_fill_trades "(b)" "${order_id}"
  expect_order "(b) dealer (Party1) and client (Party2) are the counterparties; the venue is only the ExecutionFacility" \
    "${order_id}" \
    '.parties.dealer.cdmRef == ("cdm:party:" + $dealer) and .parties.dealer.role == "Party1"
     and .parties.account.accountId == $acct and .parties.account.role == "Party2"
     and .parties.venue.cdmRef == "cdm:party:traderx-matching-engine"
     and .parties.venue.role == "ExecutionFacility"' \
    --arg dealer "$(facing_entity "${ACCOUNT_ID}")" --argjson acct "${ACCOUNT_ID}"
  expect "(b) cdm:order resolves to the order's own latest version (approved)" \
    "${CDM}/cdm/resolve/cdm:order:${order_id}" \
    '.approval[0].approved == true and .approval[0].party.externalReference == "traderx-matching-engine" and (has("businessEvent") | not)'
}

# --- (c) order fills partly, then is cancelled -------------------------------------------------

PARTIAL_CANCEL_ORDER_ID=""

# The key of the TraderX subsidiary servicing an account (its CDM account.servicingParty).
facing_entity() {
  curl -sS "${CDM}/cdm/parties/$1" | jq -r '.account.servicingParty.externalReference // empty'
}
scenario_c() {
  # Limit 50% below market: never auto-fills, so the only fill is the admin force-fill of 750
  # (the 017 matcher's optional force-fill quantity, Ruling R36). The cancel then cancels the
  # remaining 750. Deterministic: nothing else can fill the order in between.
  echo "[check] (c) order of 1500 ${TICKER} out of the money: force-fill 750, then cancel"
  local order_id
  create_order 1500 "$(limit_at 0.5)"
  order_id="${ORDER_ID}"
  if [[ -z "${order_id}" ]]; then
    fail "(c) order accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  PARTIAL_CANCEL_ORDER_ID="${order_id}"
  expect_order "(c) ${order_id} accepted (venue approval) and not filled" "${order_id}" \
    ".status == \"accepted\" and .fills == [] and .cancel == null and (${ORDER_APPROVED})" || return
  fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/force-fill?quantity=750"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" \
    '.status == "PARTIALLY_FILLED" and .filledQuantity == 750 and .remainingQuantity == 750'; then
    pass "(c) force-fill ?quantity=750: PARTIALLY_FILLED, 750 remaining"
  else
    fail "(c) force-fill ?quantity=750: PARTIALLY_FILLED, 750 remaining" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  # Cancel only once the fill is in CDM, so the cancel follows the fill.
  expect_order "(c) lineage partially-filled: Partial fill 1 of 750, accepted" "${order_id}" \
    '.status == "partially-filled" and [.fills[] | [.fillNumber, .label, .quantity]] == [[1, "Partial fill 1", 750]]
     and .fills[0].accepted.validation.status == "VALID"' || return
  fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/cancel"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" \
    '.status == "CANCELED" and .filledQuantity == 750 and .canceledQuantity == 750'; then
    pass "(c) cancel: CANCELED, filledQuantity 750, canceledQuantity 750"
  else
    fail "(c) cancel: CANCELED, filledQuantity 750, canceledQuantity 750" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  expect_order "(c) lineage status canceled, cancel Cancelled and VALID" "${order_id}" \
    ".status == \"canceled\" and (${CANCELLED})" || return
  expect_order "(c) the fill stands: Partial fill 1 of 750" "${order_id}" \
    '[.fills[] | [.fillNumber, .label, .quantity, .filledSoFar]] == [[1, "Partial fill 1", 750, 750]]'
  expect_order "(c) executedQuantity 750, canceledQuantity 750, remainingQuantity 0" "${order_id}" \
    '.executedQuantity == 750 and .canceledQuantity == 750 and .remainingQuantity == 0 and .quantityWarning == null'
  expect_order "(c) order versions VALID, cancel chained to the approval" "${order_id}" \
    "(${VERSIONS_VALID_AND_CHAINED}) and (${ORDER_APPROVED})"
  expect_order "(c) the fill is a VALID proposed -> accepted workflow with its trade state, OnVenue" \
    "${order_id}" "${FILLS_VALID}"
  expect_order "(c) no failures or pending events" "${order_id}" "${NOTHING_FAILED_OR_PENDING}"
  check_fill_trades "(c)" "${order_id}"
  expect "(c) cdm:order resolves to the order's own latest version (the cancel)" \
    "${CDM}/cdm/resolve/cdm:order:${order_id}" '.workflowState.workflowStatus == "Cancelled"'
}

FULL_FORCE_FILL_ORDER_ID=""
scenario_c_force_fill() {
  # (c') Admin force-fill without a quantity (the 009 behaviour): an order far out of the money
  # never auto-fills; force-fill executes its whole remainder at once.
  echo "[check] (c') order of 600 ${TICKER} out of the money, then a full admin force-fill"
  local order_id
  create_order 600 "$(limit_at 0.5)"
  order_id="${ORDER_ID}"
  if [[ -z "${order_id}" ]]; then
    fail "(c') order accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  FULL_FORCE_FILL_ORDER_ID="${order_id}"
  expect_order "(c') lineage accepted before the force-fill" "${order_id}" \
    ".status == \"accepted\" and .fills == [] and (${ORDER_APPROVED})" || return
  fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/force-fill"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" '.status == "FILLED"'; then
    pass "(c') force-fill accepted: ${order_id} FILLED"
  else
    fail "(c') force-fill accepted" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  expect_order "(c') one fill of 600, Full fill, status filled" "${order_id}" \
    '.status == "filled" and [.fills[] | [.label, .quantity]] == [["Full fill", 600]] and .remainingQuantity == 0'
  expect_order "(c') versions, fill workflow and trade state VALID" "${order_id}" \
    "(${VERSIONS_VALID_AND_CHAINED}) and (${FILLS_VALID})"
  expect_order "(c') no failures or pending events" "${order_id}" "${NOTHING_FAILED_OR_PENDING}"
}

ZERO_FILL_CANCEL_ORDER_ID=""
scenario_c_zero_fill_cancel() {
  # (c'') An order cancelled before any fill: the cancel corrects the approved proposal; the whole
  # instruction is cancelled.
  echo "[check] (c'') order of 400 ${TICKER} out of the money, cancelled with no fills"
  local order_id
  create_order 400 "$(limit_at 0.5)"
  order_id="${ORDER_ID}"
  if [[ -z "${order_id}" ]]; then
    fail "(c'') order accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  ZERO_FILL_CANCEL_ORDER_ID="${order_id}"
  expect_order "(c'') lineage accepted (venue approval) before the cancel" "${order_id}" \
    ".status == \"accepted\" and .fills == [] and (${ORDER_APPROVED})" || return
  fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/cancel"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" \
    '.status == "CANCELED" and .filledQuantity == 0 and .canceledQuantity == 400'; then
    pass "(c'') cancel: CANCELED, filledQuantity 0, canceledQuantity 400"
  else
    fail "(c'') cancel: CANCELED, filledQuantity 0, canceledQuantity 400" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  expect_order "(c'') lineage canceled with no fills, cancel Cancelled and VALID" "${order_id}" \
    ".status == \"canceled\" and .fills == [] and (${CANCELLED})" || return
  expect_order "(c'') executedQuantity 0, canceledQuantity 400, remainingQuantity 0" "${order_id}" \
    '.executedQuantity == 0 and .canceledQuantity == 400 and .remainingQuantity == 0 and .quantityWarning == null'
  expect_order "(c'') order versions VALID, cancel chained to the approval" "${order_id}" \
    "${VERSIONS_VALID_AND_CHAINED}"
  expect_order "(c'') no failures or pending events" "${order_id}" "${NOTHING_FAILED_OR_PENDING}"
}

# --- (d) rejected order ------------------------------------------------------------------------

REJECTION_ID=""
scenario_d() {
  echo "[check] (d) rejected order: quantity 0"
  local rejections="${CDM}/cdm/rejections?accountId=${ACCOUNT_ID}" before limit
  fetch GET "${rejections}"
  before="$(jq_out "${BODY}" '.count | numbers')"
  if [[ "${STATUS}" != "200" || -z "${before}" ]]; then
    fail "(d) ${rejections} answers with a count" "HTTP ${STATUS}: ${BODY}"
    return
  fi
  limit="$(limit_at 1)"
  fetch POST "${INGRESS_URL}/order-matcher/orders" \
    "$(jq -n -c --argjson a "${ACCOUNT_ID}" --arg s "${TICKER}" --argjson l "${limit:-100}" \
      '{accountId: $a, security: $s, side: "Buy", quantity: 0, limitPrice: $l}')"
  if [[ "${STATUS}" == "400" ]]; then
    pass "(d) matcher answers 400 to quantity 0"
  else
    fail "(d) matcher answers 400 to quantity 0" "HTTP ${STATUS}: ${BODY}"
  fi
  expect "(d) a new rejection is listed for account ${ACCOUNT_ID}" "${rejections}" \
    '.count == ($before + 1) and (.rejections[-1] | .accountId == $a and .quantity == 0)' \
    --argjson before "${before}" --argjson a "${ACCOUNT_ID}" || return
  local rejection_id
  rejection_id="$(jq_out "${BODY}" '.rejections[-1].rejectionId' -r)"
  REJECTION_ID="${rejection_id}"
  echo "[info] (d) rejectionId=${rejection_id}"
  if jq_true "${BODY}" '.rejections[-1] | .validation.status == "VALID" and .reason == "invalid order payload"'; then
    pass "(d) rejected step VALID with the matcher's reason"
  else
    fail "(d) rejected step VALID with the matcher's reason" "$(jq_out "${BODY}" '.rejections[-1]')"
  fi
  expect_order "(d) lineage of the rejection is proposed -> rejected, no fills, no cancel" "${rejection_id}" \
    '.status == "rejected" and [.proposal.versions[].stepType] == ["proposed", "rejected"]
     and .fills == [] and .cancel == null and .remainingQuantity == 0'
  expect_order "(d) venue approval false with the matcher's reason" "${rejection_id}" \
    '.proposal.approval.approved == false and .proposal.approval.party == $venue
     and .proposal.approval.rejectedReason == "invalid order payload"
     and .proposal.approval.stepStoreGlobalKey == .proposal.versions[1].storeGlobalKey
     and .proposal.versions[1].validation.status == "VALID"'
}

# --- (e) round trips ---------------------------------------------------------------------------

# roundtrip LABEL ORIGINAL_JSON CDMREF DERIVE [PROJECTION_URL [PROJECTION_FILTER]]
# Resolves CDMREF to its full CDM object, re-projects it with DERIVE (jq over the CDM object;
# $ref is the cdmRef, $account the test account id) and compares with ORIGINAL_JSON (what the
# TraderX service returned). If PROJECTION_URL is given, cdm-service's own projection
# (optionally narrowed by PROJECTION_FILTER) must equal ORIGINAL_JSON too. Both are polled.
roundtrip() {
  local label="$1" original="$2" ref="$3" derive="$4" projection_url="${5:-}" projection_filter="${6:-.}"
  expect_equal "(e) ${label}: ${ref} resolved and re-projected equals the TraderX projection" \
    "${CDM}/cdm/resolve/${ref}" "${derive}" "${original}" --arg ref "${ref}" --argjson account "${ACCOUNT_ID}"
  if [[ -n "${projection_url}" ]]; then
    expect_equal "(e) ${label}: cdm-service projection equals the TraderX projection" \
      "${projection_url}" "${projection_filter}" "${original}"
  fi
}

# get_original LABEL URL FILTER [jq args...]: sets ORIGINAL to FILTER over the TraderX
# service's response (must be a non-null object carrying what FILTER selects), else [fail].
ORIGINAL=""
get_original() {
  local label="$1" url="$2" filter="$3"
  shift 3
  ORIGINAL=""
  fetch GET "${url}"
  if [[ "${STATUS}" == "200" ]]; then
    ORIGINAL="$(jq_out "${BODY}" "${filter} | objects" "$@")"
  fi
  if [[ -z "${ORIGINAL}" ]]; then
    fail "(e) ${label}" "HTTP ${STATUS} from ${url}: $(head -c 300 <<<"${BODY}")"
    return 1
  fi
}

scenario_e() {
  echo "[check] (e) round trips: TraderX projection -> cdmRef -> CDM object -> projection"
  local ref

  if get_original "account-service returns account ${ACCOUNT_ID}" \
    "${INGRESS_URL}/account-service/account/${ACCOUNT_ID}" '.'; then
    ref="$(jq_out "${ORIGINAL}" '.cdmRef // empty' -r)"
    # The servicing subsidiary is a reference on the party (account.servicingParty); its name is
    # joined from that subsidiary's own CDM Party.
    local servicing_name
    servicing_name="$(curl -sS "${CDM}/cdm/parties/$(facing_entity "${ACCOUNT_ID}")" | jq -c '.name.value')"
    roundtrip "account ${ACCOUNT_ID}" "${ORIGINAL}" "${ref:-cdm:party:${ACCOUNT_ID}}" \
      '{id: (.partyId[] | select(.identifier.meta.scheme == "https://traderx.finos.org/account-id") | .identifier.value | tonumber),
        displayName: .name.value, cdmRef: $ref,
        servicingPartyRef: ("cdm:party:" + .account.servicingParty.externalReference),
        servicingPartyName: '"${servicing_name:-null}"'}' \
      "${CDM}/projections/accounts/${ACCOUNT_ID}"
  fi

  if get_original "people-service returns ${PERSON_LOGON_ID}" \
    "${INGRESS_URL}/people-service/People/GetPerson?LogonId=${PERSON_LOGON_ID}" '.'; then
    ref="$(jq_out "${ORIGINAL}" '.cdmRef // empty' -r)"
    roundtrip "person ${PERSON_LOGON_ID}" "${ORIGINAL}" "${ref:-cdm:person:${PERSON_LOGON_ID}}" \
      'def id($scheme): [.personId[].value.identifier | select(.meta.scheme == $scheme) | .value][0];
       {logonId: id("https://traderx.finos.org/logon-id"),
        fullName: ([.firstName, .surname] | map(select(. != null)) | join(" ")),
        email: .contactInformation.email[0],
        employeeId: [.personId[].value | select(.identifierType == "EMPL") | .identifier.value][0],
        department: id("https://traderx.finos.org/department"),
        photoUrl: id("https://traderx.finos.org/photo-url"),
        cdmRef: $ref}' \
      "${CDM}/projections/people/${PERSON_LOGON_ID}"
  fi

  if get_original "reference-data returns ${TICKER}" \
    "${INGRESS_URL}/reference-data/stocks/${TICKER}" '.'; then
    ref="$(jq_out "${ORIGINAL}" '.cdmRef // empty' -r)"
    roundtrip "stock ${TICKER}" "${ORIGINAL}" "${ref:-cdm:security:${TICKER}}" \
      'def id($type): [.identifier[] | select(.identifierType == $type) | .identifier.value][0];
       {ticker: id("ExchangeCode"), companyName: id("Name"), cdmRef: $ref}' \
      "${CDM}/projections/stocks/${TICKER}"
  fi

  # Trade: position-service's trade row carries the derived cdmRef (cdm:trade:<tradeId>, the
  # deterministic 017 format). Compared: id, accountId, security, side, quantity, price, cdmRef,
  # and created against the timestamp of the direct workflow's accepted (execution) step and the
  # trade date. state is compared through the documented mapping (Rulings R35, R37): TraderX has
  # no settlement process, so New, Processing and Settled are all CDM positionState Executed and
  # Cancelled is Cancelled; the TraderX row's state is mapped to its CDM value before the
  # comparison (the "Settled" label itself is not kept). Not compared: `updated`
  # (position-service row bookkeeping, no CDM field).
  if [[ -z "${DIRECT_TRADE_ID}" ]]; then
    fail "(e) trade round trip" "no direct trade from (a) to check"
  elif get_original "position-service lists trade ${DIRECT_TRADE_ID}" \
    "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}" \
    '[.[] | select(.id == $id)][0]' --arg id "${DIRECT_TRADE_ID}"; then
    local trade_row="${ORIGINAL}"
    ref="$(jq_out "${trade_row}" '.cdmRef // empty' -r)"
    roundtrip "trade ${DIRECT_TRADE_ID}" \
      "$(jq_out "${trade_row}" '{id, accountId, security, side, quantity, price, cdmRef,
          state: ({"New": "Executed", "Processing": "Executed", "Settled": "Executed",
                   "Cancelled": "Cancelled"}[.state])}')" \
      "${ref:-cdm:trade:${DIRECT_TRADE_ID}}" \
      '.trade as $t
       | ($account | tostring) as $acct
       | {id: [$t.tradeIdentifier[].assignedIdentifier[].identifier
               | select(.meta.scheme == "https://traderx.finos.org/trade-id") | .value][0],
          accountId: ($t.counterparty[] | select(.role == "Party2") | .partyReference.externalReference | tonumber),
          security: [$t | .. | objects | select(.identifierType? == "ExchangeCode") | .identifier.value][0],
          side: (if any($t.partyRole[]; .role == "Buyer" and .partyReference.externalReference == $acct)
                 then "Buy" else "Sell" end),
          quantity: $t.tradeLot[0].priceQuantity[0].quantity.value.value,
          price: $t.tradeLot[0].priceQuantity[0].price[0].value.value,
          state: .state.positionState,
          cdmRef: $ref}'
    local created
    created="$(jq_out "${trade_row}" '.created' -r)"
    expect_equal "(e) trade ${DIRECT_TRADE_ID}: created equals the accepted (execution) step timestamp" \
      "${CDM}/cdm/trades/${DIRECT_TRADE_ID}/lineage" \
      "${DEF_MS} [.producedBy.accepted.timestamp | ms][0]" \
      "$(jq -n --arg c "${created}" "${DEF_MS} \$c | ms")"
    expect_equal "(e) trade ${DIRECT_TRADE_ID}: created date equals the CDM trade date" \
      "${CDM}/cdm/resolve/cdm:trade:${DIRECT_TRADE_ID}" '.trade.tradeDate.value' \
      "$(jq -n --arg c "${created}" '$c[0:10]')"
  fi

  # Position: compared accountId, security, quantity (CDM quantity signed by payerReceiver:
  # receiver Party2, the client, = long), averageCostBasis and cdmRef. accountId is not inside the CDM
  # Position object (it is the account of its PortfolioState); the derivation injects the test
  # account id ($account), and the cdmRef (cdm:position:<account>:<ticker>) ties it to the
  # account. Not compared: `updated` (position-service row-write time; the CDM side has the
  # trade's execution time, about a millisecond earlier).
  if get_original "position-service returns a ${TICKER} position for ${ACCOUNT_ID}" \
    "${INGRESS_URL}/position-service/positions/${ACCOUNT_ID}" \
    '[.[] | select(.security == $s) | del(.updated)][0]' --arg s "${TICKER}"; then
    ref="$(jq_out "${ORIGINAL}" '.cdmRef // empty' -r)"
    roundtrip "position ${ACCOUNT_ID}/${TICKER}" "${ORIGINAL}" "${ref:-cdm:position:${ACCOUNT_ID}:${TICKER}}" \
      '([.. | objects | select(has("payerReceiver")) | .payerReceiver][0]) as $pr
       | {accountId: $account,
          security: [.. | objects | select(.identifierType? == "ExchangeCode") | .identifier.value][0],
          quantity: (.priceQuantity[0].quantity.value.value * (if $pr.receiver == "Party2" then 1 else -1 end)),
          averageCostBasis: .priceQuantity[0].price[0].value.value,
          cdmRef: $ref}' \
      "${CDM}/projections/positions/${ACCOUNT_ID}" \
      "[.[] | select(.security == \"${TICKER}\") | del(.updated)][0]"
    expect "(e) account ${ACCOUNT_ID} CDM portfolio state VALID" "${CDM}/cdm/positions/${ACCOUNT_ID}" \
      '.validation.status == "VALID" and (.skipped | type) == "array" and (.skipped | length) == 0'
    expect "(e) account ${ACCOUNT_ID} CDM positions list their trades, summing to each quantity" \
      "${CDM}/cdm/positions/${ACCOUNT_ID}" \
      '(.positions | length) > 0 and all(.positions[];
         (.trades | length) > 0 and ([.trades[].signedQuantity] | add) == .quantity
         and all(.trades[]; .cdmRef == ("cdm:trade:" + .tradeId)))
       and any(.positions[]; .security == $s and any(.trades[]; .tradeId == $direct))' \
      --arg s "${TICKER}" --arg direct "${DIRECT_TRADE_ID}"
  fi
}

# --- (g) cdm-service outage --------------------------------------------------------------------

# The structure of an order lineage, without ids, keys, quantities or fill count: what a live
# order and an order recovered by the startup backfill must share.
LINEAGE_SHAPE='{status, keys: keys_unsorted, proposalKeys: (.proposal | keys_unsorted),
  versions: [.proposal.versions[] | {stepType, status: .validation.status}],
  approved: .proposal.approval.approved, executionType: .proposal.executionType,
  fills: ([.fills[] | {keys: keys_unsorted, executionType,
    proposed: [.proposed.stepType, .proposed.validation.status, .proposed.object.action],
    accepted: [.accepted.stepType, .accepted.validation.status, .accepted.object.workflowState.workflowStatus],
    tradeState: [.tradeState.validation.status, .tradeState.positionState]}] | unique),
  cancel: (.cancel | if . == null then null
    else [.stepType, .validation.status, .object.workflowState.workflowStatus] end),
  failures, pending}'

OUTAGE_ORDER_ID=""
OUTAGE_ZERO_FILL_ORDER_ID=""
CDM_CONTAINER=""
TRADE_PROCESSOR_CONTAINER=""

# compose_container SERVICE: id of the running container of SERVICE in the stack's compose project.
compose_container() {
  docker ps -q --filter "label=com.docker.compose.project=${STACK_PROJECT}" \
    --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -n 1
}

# restore_stack: cdm-service running, trade-processor unpaused (idempotent; used by the EXIT trap).
restore_stack() {
  if [[ -n "${TRADE_PROCESSOR_CONTAINER}" ]]; then
    docker unpause "${TRADE_PROCESSOR_CONTAINER}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${CDM_CONTAINER}" ]]; then
    docker start "${CDM_CONTAINER}" >/dev/null 2>&1 || true
  fi
}

# cdm_service_down LABEL / cdm_service_up LABEL: stop / start cdm-service; up waits until it is
# healthy through the ingress and its startup backfill is complete.
cdm_service_down() {
  if docker stop "${CDM_CONTAINER}" >/dev/null 2>&1; then
    pass "(g) $1: cdm-service stopped"
  else
    fail "(g) $1: cdm-service stopped" "docker stop ${CDM_CONTAINER} failed"
    return 1
  fi
}
cdm_service_up() {
  if ! docker start "${CDM_CONTAINER}" >/dev/null 2>&1; then
    fail "(g) $1: cdm-service started" "docker start ${CDM_CONTAINER} failed"
    return 1
  fi
  local saved="${POLL_TIMEOUT_SECONDS}" rc=0
  POLL_TIMEOUT_SECONDS="${BACKFILL_TIMEOUT_SECONDS}"
  expect "(g) $1: cdm-service started, healthy through the ingress" "${CDM}/health" '.status == "ok"' \
    && expect "(g) $1: startup backfill complete" "${CDM}/cdm/validation-summary" '.backfill.complete == true' \
    || rc=1
  POLL_TIMEOUT_SECONDS="${saved}"
  return "${rc}"
}

scenario_g() {
  # In 017 trade-service checks the security with reference-data, whose security master is
  # cdm-service, so no trade can be submitted while cdm-service is down. A fill is therefore
  # submitted while cdm-service is up but with trade-processor paused (the trade order waits in
  # NATS), and booked once cdm-service is down: cdm-service misses the booked fill, as it would
  # in a real outage. Everything about the order reaches CDM through the startup backfill:
  #   outage 1: order placed (open order backfill);
  #   then, trade-processor paused: two partial force-fills submitted;
  #   outage 2: the fills are booked (missed fill backfill), the order is cancelled (missed
  #   cancel backfill), and a second order is placed and cancelled with no fills (R41).
  echo "[check] (g) cdm-service outage: order placed, filled twice and cancelled while cdm-service misses it"
  if [[ "${SKIP_OUTAGE}" == "1" ]]; then
    echo "[info] (g) skipped (TRADERX_CDM_SKIP_OUTAGE=1)"
    return
  fi
  if ! command -v docker >/dev/null 2>&1; then
    fail "(g) docker CLI available to stop cdm-service" "set TRADERX_CDM_SKIP_OUTAGE=1 to skip (g)"
    return
  fi
  CDM_CONTAINER="$(compose_container cdm-service)"
  TRADE_PROCESSOR_CONTAINER="$(compose_container trade-processor)"
  if [[ -z "${CDM_CONTAINER}" || -z "${TRADE_PROCESSOR_CONTAINER}" ]]; then
    fail "(g) running cdm-service and trade-processor containers in compose project ${STACK_PROJECT}" \
      "set COMPOSE_PROJECT_NAME, or TRADERX_CDM_SKIP_OUTAGE=1 to skip (g)"
    return
  fi
  local limit order_id="" zero_id="" q
  limit="$(limit_at 0.5)"
  if [[ -z "${limit}" ]]; then
    fail "(g) market price for the limit" "no price for ${TICKER}"
    return
  fi
  # From here on the stack is restored whatever happens.
  trap 'restore_stack; rm -rf "${WORK_DIR}"' EXIT

  # Outage 1: the order is placed.
  cdm_service_down "outage 1" || return
  create_order 1000 "${limit}"
  order_id="${ORDER_ID}"
  if [[ -n "${order_id}" ]]; then
    pass "(g) outage 1: order of 1000 accepted by the matcher: ${order_id}"
  else
    fail "(g) outage 1: order accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
  fi
  cdm_service_up "outage 1" || return
  [[ -n "${order_id}" ]] || return
  expect_order "(g) open order backfilled: proposal approved, no fills" "${order_id}" \
    ".status == \"accepted\" and .fills == [] and (${ORDER_APPROVED})" || return

  # Two partial fills submitted with trade-processor paused, so they are not booked yet.
  local before
  fetch GET "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}"
  before="$(jq_out "${BODY}" '[.[].id]')"
  if docker pause "${TRADE_PROCESSOR_CONTAINER}" >/dev/null 2>&1; then
    pass "(g) trade-processor paused"
  else
    fail "(g) trade-processor paused" "docker pause ${TRADE_PROCESSOR_CONTAINER} failed"
    return
  fi
  for q in 300 200; do
    fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/force-fill?quantity=${q}"
    if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" '.status == "PARTIALLY_FILLED"'; then
      pass "(g) force-fill ?quantity=${q} submitted: PARTIALLY_FILLED"
    else
      fail "(g) force-fill ?quantity=${q} submitted" "HTTP ${STATUS}: ${BODY}"
    fi
  done

  # Outage 2: the fills are booked, the order is cancelled, a second order comes and goes.
  cdm_service_down "outage 2" || return
  docker unpause "${TRADE_PROCESSOR_CONTAINER}" >/dev/null 2>&1 || true
  pass "(g) outage 2: trade-processor resumed"
  expect "(g) outage 2: both fills booked by trade-processor (cdm-service down)" \
    "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}" \
    '[.[] | select((.id as $id | $before | index($id) | not) and .security == $s and .side == "Buy")
      | .quantity] | sort == [200, 300]' --argjson before "${before:-[]}" --arg s "${TICKER}"
  fetch POST "${INGRESS_URL}/order-matcher/orders/${order_id}/cancel"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" \
    '.status == "CANCELED" and .filledQuantity == 500 and .canceledQuantity == 500'; then
    pass "(g) outage 2: cancel: CANCELED, filledQuantity 500, canceledQuantity 500"
  else
    fail "(g) outage 2: cancel: CANCELED, filledQuantity 500, canceledQuantity 500" "HTTP ${STATUS}: ${BODY}"
  fi
  create_order 250 "${limit}"
  zero_id="${ORDER_ID}"
  if [[ -n "${zero_id}" ]]; then
    fetch POST "${INGRESS_URL}/order-matcher/orders/${zero_id}/cancel"
    if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" '.status == "CANCELED" and .filledQuantity == 0'; then
      pass "(g) outage 2: order of 250 placed and cancelled with no fills: ${zero_id}"
    else
      fail "(g) outage 2: order of 250 cancelled" "HTTP ${STATUS}: ${BODY}"
      zero_id=""
    fi
  else
    fail "(g) outage 2: order of 250 accepted by the matcher" "HTTP ${STATUS}: ${BODY}"
  fi
  cdm_service_up "outage 2" || return
  trap 'rm -rf "${WORK_DIR}"' EXIT

  OUTAGE_ORDER_ID="${order_id}"
  expect_order "(g) backfilled lineage canceled, approved, cancel Cancelled" "${order_id}" \
    ".status == \"canceled\" and (${ORDER_APPROVED}) and (${CANCELLED})" || return
  expect_order "(g) backfilled fills 1 and 2 (300 and 200, Partial fill 1 and 2)" "${order_id}" \
    '[.fills[] | [.fillNumber, .label]] == [[1, "Partial fill 1"], [2, "Partial fill 2"]]
     and ([.fills[].quantity] | sort) == [200, 300] and .fills[-1].filledSoFar == 500'
  # Fill numbers follow booking order: the order's trades by booking time (as epoch
  # milliseconds: timestamps have variable precision, so not as strings), then trade id.
  local ids booked
  ids="$(jq_out "${BODY}" '[.fills[].tradeId]')"
  fetch GET "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}"
  booked="$(jq_out "${BODY}" "${DEF_MS} [.[] | select(.id as \$id | \$ids | index(\$id))] | sort_by((.created | ms), .id) | map(.id)" \
    --argjson ids "${ids:-[]}")"
  expect_order "(g) fill numbers follow the booking order of the order's trades" "${order_id}" \
    '($booked | length) == 2 and [.fills[].tradeId] == $booked' --argjson booked "${booked:-null}"
  expect_order "(g) quantities: executed 500, canceled 500, remaining 0" "${order_id}" \
    '.executedQuantity == 500 and .canceledQuantity == 500 and .remainingQuantity == 0 and .quantityWarning == null'
  expect_order "(g) backfilled versions VALID and chained" "${order_id}" "${VERSIONS_VALID_AND_CHAINED}"
  expect_order "(g) backfilled fills VALID proposed -> accepted workflows, OnVenue" "${order_id}" "${FILLS_VALID}"
  expect_order "(g) no failures or pending events" "${order_id}" "${NOTHING_FAILED_OR_PENDING}"
  check_fill_trades "(g)" "${order_id}"
  if [[ -n "${PARTIAL_CANCEL_ORDER_ID}" ]]; then
    fetch GET "$(lineage_url "${PARTIAL_CANCEL_ORDER_ID}")"
    expect_equal "(g) backfilled lineage has the same shape as the live (c) order ${PARTIAL_CANCEL_ORDER_ID}" \
      "$(lineage_url "${order_id}")" "${LINEAGE_SHAPE}" "$(jq_out "${BODY}" "${LINEAGE_SHAPE}")"
  fi
  if [[ -n "${zero_id}" ]]; then
    OUTAGE_ZERO_FILL_ORDER_ID="${zero_id}"
    expect_order "(g) order placed and cancelled in the outage: canceled, no fills, cancel Cancelled" "${zero_id}" \
      ".status == \"canceled\" and .fills == [] and (${CANCELLED}) and .canceledQuantity == 250
       and .executedQuantity == 0 and (${VERSIONS_VALID_AND_CHAINED})"
    if [[ -n "${ZERO_FILL_CANCEL_ORDER_ID}" ]]; then
      fetch GET "$(lineage_url "${ZERO_FILL_CANCEL_ORDER_ID}")"
      expect_equal "(g) its lineage has the same shape as the live (c'') order ${ZERO_FILL_CANCEL_ORDER_ID}" \
        "$(lineage_url "${zero_id}")" "${LINEAGE_SHAPE}" "$(jq_out "${BODY}" "${LINEAGE_SHAPE}")"
    fi
  fi
}

# --- (f) validation ----------------------------------------------------------------------------

scenario_f() {
  echo "[check] (f) validation summary"
  local url="${CDM}/cdm/validation-summary"
  expect "(f) no parked (pending) trades or cancels" "${url}" '.pending.trades == 0 and .pending.cancels == 0'
  # byStatus always carries every status (zero counts included); the guard makes a missing key a
  # failure rather than a vacuous pass (null == 0 is false in jq, but say so explicitly).
  expect "(f) no stored CDM object is INVALID" "${url}" \
    '.objects.total > 0 and (.objects.byStatus | has("INVALID") and has("NOT_QUALIFIED"))
     and .objects.byStatus.INVALID == 0'
  expect "(f) no stored CDM object is NOT_QUALIFIED (Qualify_Execution)" "${url}" \
    '(.objects.byStatus | has("INVALID") and has("NOT_QUALIFIED")) and .objects.byStatus.NOT_QUALIFIED == 0'
  expect "(f) no BusinessEvent stored on its own (only inside accepted steps)" "${url}" \
    '(.objects.byType | has("cdm.event.common.BusinessEvent") | not)
     and (.objects.byType | has("cdm.event.common.TradeState"))'
  expect "(f) no lifecycle failure rows created during the run (before ${FAILURES_BEFORE:-?})" "${url}" \
    '.failures.total == $before' --argjson before "${FAILURES_BEFORE:-null}"
  if [[ "${STATUS}" == "200" ]]; then
    echo "[info] (f) $(jq_out "${BODY}" '{objects: .objects.byStatus, objectsTotal: .objects.total, latest: .latest.byStatus, failures: .failures.total, pending, backfill}')"
    echo "[info] (f) objects by type: $(jq_out "${BODY}" '.objects.byType')"
    if ! jq_true "${BODY}" '.notValid | length == 0'; then
      echo "[info] (f) not valid: $(jq_out "${BODY}" '[.notValid[] | {cdmRef, cdmType, status, messages}]')"
    fi
    if ! jq_true "${BODY}" '.failures.total == 0'; then
      echo "[info] (f) recent failures: $(jq_out "${BODY}" '.failures.recent')"
    fi
  fi
}

# --- (h) facing subsidiaries -------------------------------------------------------------------

scenario_h() {
  echo "[check] (h) TraderX subsidiaries face clients; switching changes later trades only"
  expect "(h) three subsidiaries: NYC, LDN, TKY" "${CDM}/cdm/entities" \
    '[.[].meta.externalKey] == ["traderx-nyc", "traderx-ldn", "traderx-tky"]'
  expect "(h) account ${ACCOUNT_ID} is a CDM Client account serviced by a subsidiary" \
    "${CDM}/cdm/parties/${ACCOUNT_ID}" \
    '.account.accountNumber.value == ($a | tostring) and .account.accountType.value == "Client"
     and (.account.servicingParty.externalReference | startswith("traderx-"))' --argjson a "${ACCOUNT_ID}" || return
  local original target
  original="$(facing_entity "${ACCOUNT_ID}")"
  expect "(h) account-service shows the account's servicing subsidiary (Account tab)" \
    "${INGRESS_URL}/account-service/account/${ACCOUNT_ID}" \
    '.servicingPartyRef == ("cdm:party:" + $e) and (.servicingPartyName | startswith("TraderX "))' \
    --arg e "${original}"
  target="traderx-tky"
  [[ "${original}" == "${target}" ]] && target="traderx-ldn"
  # An order placed before the switch, out of the money so only the force-fill below fills it.
  local open_order=""
  create_order 200 "$(limit_at 0.5)"
  open_order="${ORDER_ID}"
  fetch PUT "${CDM}/cdm/parties/${ACCOUNT_ID}/servicing-party" "$(jq -n -c --arg e "${target}" '{entity: $e}')"
  if [[ "${STATUS}" == "200" ]] && jq_true "${BODY}" '.account.servicingParty.externalReference == $e' --arg e "${target}"; then
    pass "(h) servicing party switched ${original} -> ${target}"
  else
    fail "(h) servicing party switch" "HTTP ${STATUS}: $(head -c 300 <<<"${BODY}")"
    return
  fi
  expect "(h) account-service reflects the switch" "${INGRESS_URL}/account-service/account/${ACCOUNT_ID}" \
    '.servicingPartyRef == ("cdm:party:" + $e)' --arg e "${target}"
  if [[ -n "${open_order}" ]]; then
    fetch POST "${INGRESS_URL}/order-matcher/orders/${open_order}/force-fill?quantity=200"
    expect_order "(h) an order placed before the switch keeps ${original} as dealer on its fill" "${open_order}" \
      '.parties.dealer.cdmRef == ("cdm:party:" + $e) and (.fills | length) == 1
       and .fills[0].tradeState.validation.status == "VALID" and (.failures | length) == 0' \
      --arg e "${original}"
    if [[ -n "$(jq_out "${BODY}" '.fills[0].tradeId // empty' -r)" ]]; then
      expect "(h) that fill's trade state faces ${original} as Party1" \
        "${CDM}/cdm/resolve/cdm:trade:$(jq_out "${BODY}" '.fills[0].tradeId' -r)" \
        '[.trade.counterparty[] | select(.role == "Party1") | .partyReference.externalReference][0] == $e' \
        --arg e "${original}"
    fi
  else
    fail "(h) order placed before the switch" "HTTP ${STATUS}: ${BODY}"
  fi
  fetch PUT "${CDM}/cdm/parties/${ACCOUNT_ID}/servicing-party" '{"entity":"traderx-par"}'
  if [[ "${STATUS}" == "400" ]]; then
    pass "(h) an unknown subsidiary is rejected with 400"
  else
    fail "(h) an unknown subsidiary is rejected with 400" "HTTP ${STATUS}"
  fi
  fetch GET "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}"
  local before
  before="$(jq_out "${BODY}" '[.[].id]')"
  fetch POST "${INGRESS_URL}/trade-service/trade/" \
    "$(jq -n -c --argjson a "${ACCOUNT_ID}" --arg s "${TICKER}" \
      '{accountId: $a, security: $s, side: "Sell", quantity: 7}')"
  local new_trades='[.[] | select((.id as $id | $before | any(. == $id) | not)
      and .security == $s and .side == "Sell" and .quantity == 7)]'
  if expect "(h) a trade after the switch is booked" "${INGRESS_URL}/position-service/trades/${ACCOUNT_ID}" \
    "${new_trades} | length > 0" --argjson before "${before}" --arg s "${TICKER}"; then
    local trade_id
    trade_id="$(jq_out "${BODY}" "${new_trades}[0].id" -r --argjson before "${before}" --arg s "${TICKER}")"
    expect "(h) the new trade faces ${target} as Party1 and Buyer" "${CDM}/cdm/resolve/cdm:trade:${trade_id}" \
      '.trade as $t
       | ([$t.counterparty[] | select(.role == "Party1") | .partyReference.externalReference][0] == $e)
       and any($t.partyRole[]; .role == "Buyer" and .partyReference.externalReference == $e)
       and .state.positionState == "Executed"' --arg e "${target}"
  fi
  if [[ -n "${DIRECT_TRADE_ID:-}" ]]; then
    expect "(h) the earlier trade ${DIRECT_TRADE_ID} still faces ${original}" \
      "${CDM}/cdm/resolve/cdm:trade:${DIRECT_TRADE_ID}" \
      '[.trade.counterparty[] | select(.role == "Party1") | .partyReference.externalReference][0] == $e' \
      --arg e "${original}"
  fi
  fetch PUT "${CDM}/cdm/parties/${ACCOUNT_ID}/servicing-party" "$(jq -n -c --arg e "${original}" '{entity: $e}')"
  if [[ "${STATUS}" == "200" ]]; then
    pass "(h) servicing party restored to ${original}"
  else
    fail "(h) servicing party restored to ${original}" "HTTP ${STATUS}"
  fi
}

# Example ids from this run, for looking at them in the CDM tab (/cdm/order/<id>, /cdm/trade/<id>).
print_examples() {
  echo "[info] examples: direct trade=${DIRECT_TRADE_ID:-?} filled order=${FILLED_ORDER_ID:-?}" \
    "partial fill then cancel=${PARTIAL_CANCEL_ORDER_ID:-?} full force-fill=${FULL_FORCE_FILL_ORDER_ID:-?}" \
    "cancelled with no fills=${ZERO_FILL_CANCEL_ORDER_ID:-?} rejected request=${REJECTION_ID:-?}" \
    "outage order=${OUTAGE_ORDER_ID:-?} outage zero-fill cancel=${OUTAGE_ZERO_FILL_ORDER_ID:-?}"
}

# Each scenario records its own [pass]/[fail] lines and carries on after a failure.
scenario_a || true
scenario_b || true
scenario_c || true
scenario_c_force_fill || true
scenario_c_zero_fill_cancel || true
scenario_d || true
scenario_e || true
scenario_g || true
scenario_h || true
scenario_f || true
print_examples

echo "[summary] ${PASSED} passed, ${FAILED} failed"
if (( FAILED > 0 )); then
  echo "[error] state 017 CDM lifecycle scenarios failed"
  exit 1
fi
echo "[done] state 017 CDM lifecycle scenarios passed"
