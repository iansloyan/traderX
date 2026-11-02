# Smoke Tests: 017-cdm-lifecycle-mapping

- Primary smoke script: `scripts/test-state-017-cdm-lifecycle-mapping.sh`
- Inherited runtime checks: `scripts/test-state-009-order-management-matcher.sh` (run with
  `COMPOSE_PROJECT_NAME=traderx-state-017`; see `quickstart.md`)

## Run it

Prerequisites: `bash`, `curl`, `jq`, and a running 017 stack.

```bash
./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data   # fresh data (required once after the fill-instruction amendment)
./scripts/test-state-017-cdm-lifecycle-mapping.sh                            # default http://localhost:8080
./scripts/test-state-017-cdm-lifecycle-mapping.sh http://my-host:8080        # another ingress
./scripts/stop-state-017-cdm-lifecycle-mapping-generated.sh
```

The script uses only the ingress REST APIs: `/trade-service/`, `/order-matcher/`,
`/position-service/`, `/account-service/`, `/people-service/`, `/reference-data/`,
`/price-publisher/` and `/cdm-service/`, plus the docker CLI for (g), which stops and starts
cdm-service and pauses trade-processor. It prints one `[pass]` or `[fail]` line per assertion,
then a `[summary]`, and exits non-zero if any assertion failed. cdm-service builds CDM objects
asynchronously from NATS events, so every CDM assertion polls for up to 15 s.

The script can run again on the same stack. Each run adds orders and trades for the test
account, and every assertion only looks at the objects that run created. Use `--reset-data` for
a clean baseline. A volume written before the fill-instruction amendment (stored execution
`BusinessEvent`s, no fill workflows) must be reset: the start script refuses it.

Optional environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `TRADERX_TEST_ACCOUNT_ID` | `22214` | Account that trades and orders |
| `TRADERX_TEST_TICKER` | `IBM` | Ticker. It must have a price stream. |
| `TRADERX_TEST_LOGON_ID` | `user01` | Person for the round trip |
| `TRADERX_CDM_POLL_TIMEOUT_SECONDS` | `15` | Poll timeout per assertion |
| `TRADERX_CDM_BACKFILL_TIMEOUT_SECONDS` | `120` | How long the preflight, and (g) after each restart, wait for cdm-service's startup backfill |
| `COMPOSE_PROJECT_NAME` | `traderx-state-017` | Compose project whose cdm-service and trade-processor containers (g) stops and pauses |
| `TRADERX_CDM_SKIP_OUTAGE` | `0` | `1` skips (g) (no docker CLI, or a stack not run by compose) |

Before the scenarios, the preflight waits until `/cdm-service/cdm/validation-summary` reports
`backfill.complete` (the seeded trades, open matcher orders and any missed order fills are in CDM). It then records
the lifecycle failure row count, and fails if there already are failure rows (it lists the
most recent ones).

## What each scenario proves

Limits are set from the live price (`/price-publisher/prices/<ticker>`). The matcher's fill
policy decides the quantities: if the remaining quantity is below 1000 it fills in full,
otherwise it fills half, rounded up.

| Scenario | Drives | Proves |
|---|---|---|
| **(a) Direct trade** | `POST /trade-service/trade/`: Buy 100 IBM for 22214 | `GET /cdm-service/cdm/trades/{tradeId}/lineage` leads with the trade state (`cdmRef`, `tradeId`, `tradeState`, …): `VALID`, `Executed`, 100 Buy. It is `producedBy` the direct workflow `cdm:direct:<tradeId>` (kind `direct`): `proposed → accepted`, both `VALID`, the accepted step linked to the proposal and carrying the execution `BusinessEvent` whose `after` is the trade state. `executionType` is `OffFacility`, with its meaning: the account's subsidiary is `Party1`, the client `Party2`, and there is no venue (no `ExecutionFacility`, no `executionVenue`). `GET /cdm/trades/{tradeId}` also leads with `tradeState`; `cdm:trade:<tradeId>` resolves to the `TradeState` (`X-CDM-Type`), and `cdm:direct:<tradeId>` to a CDM `Workflow` of the two steps (`X-CDM-Validation: VALID`). |
| **(b) Order fills completely** | Order 1500 with the limit 50% above market | The matcher auto-fills 750 and then 750. The order's own workflow is `proposed → approved`: `proposal.approval.approved` true, by the venue party, sent `OnVenue`. `fills` are "Partial fill 1" and "Final fill 2" (750 each, `filledSoFar` 750 and 1500). Each fill is its own workflow: a `fill-proposed` step (action `New`, no previous step, identified by order id and fill (trade) id) and a `fill-accepted` step linked to it, both `VALID` (which includes `Qualify_Execution`), with the fill's `VALID` `Executed` trade state, `OnVenue`. Status `filled`, `executedQuantity` 1500, `canceledQuantity` 0, `remainingQuantity` 0, no `cancel`. Each fill's `GET /cdm/trades/{tradeId}` leads with its `TradeState` and is `producedBy` (kind `fill`) that fill of that order, with the same number and label. The lineage's `parties` are the dealer (the account's subsidiary, `Party1`), the client (`Party2`) and the venue (`ExecutionFacility`). `cdm:order:<id>` resolves to the order's own latest version (the approved proposal, not a fill step). No failures, no parked events. |
| **(c) Partial fill, then cancel** | Order 1500 with the limit 50% below market (never auto-fills), `POST /order-matcher/orders/{id}/force-fill?quantity=750`, then cancel | Before the fill: `accepted` with the venue approval and no fills. After it: `partially-filled`, "Partial fill 1" of 750 with a `VALID` accepted step. After the cancel: status `canceled`; `cancel` is a `VALID` step with `workflowState.workflowStatus` `Cancelled`, linked to the approved version; the fill stands. `executedQuantity` 750, `canceledQuantity` 750, `remainingQuantity` 0. `cdm:order:<id>` resolves to the cancel. Deterministic: nothing else can fill the order. |
| **(c') Full admin force-fill** | Order 600 with the limit 50% below market, then force-fill without a quantity | The 009 behaviour is unchanged: force-fill executes the whole remainder as one fill, labelled "Full fill", `VALID`. |
| **(c'') Cancel with no fills** | Order 400 with the limit 50% below market, then cancel | Status `canceled`, no fills, a `VALID` `Cancelled` cancel linked to the approval; `executedQuantity` 0, `canceledQuantity` 400, `remainingQuantity` 0. |
| **(d) Rejected order** | `POST /order-matcher/orders` with `quantity: 0` | The matcher answers 400. A `rejected` workflow step (`VALID`, with the matcher's reason) appears in `GET /cdm-service/cdm/rejections?accountId=22214`. Its lineage is `proposed → rejected` with no fills and no cancel, and `proposal.approval` is `approved: false` by the venue with `rejectedReason` "invalid order payload". |
| **(e) Round trips** | Account 22214, person `user01`, stock IBM, the trade from (a), and the 22214/IBM position | For each one: fetch the existing TraderX projection, resolve its `cdmRef` with `/cdm-service/cdm/resolve/…`, re-project the full CDM object with `jq`, and compare with the original (polled). Key order and number formatting do not matter. Where cdm-service has a projection endpoint (`/projections/accounts|people|stocks|positions`), that must match too. For the trade, `state` is compared with `TradeState.state.positionState` through the documented mapping (New, Processing, Settled → `Executed`; Cancelled → `Cancelled`), and `created` with the timestamp of the direct workflow's accepted step (to the millisecond) and the CDM trade date. The account's CDM `PortfolioState` is `VALID`, and every position's `trades` sum (signed) to its quantity; the IBM position lists the trade from (a). Only the compared fields (below) are claimed lossless. |
| **(g) cdm-service outage** | docker: stop cdm-service, place an order of 1000, start; pause trade-processor, force-fill 300 and 200; stop cdm-service, resume trade-processor (the fills are booked), cancel, place and cancel an order of 250; start | Everything reaches CDM through the startup backfill (open orders, missed fills, missed cancels, R41). The recovered order lineage is `canceled`, approved, "Partial fill 1" and "Partial fill 2" numbered in booking order (trade time, then trade id), `executedQuantity` 500, `canceledQuantity` 500, everything `VALID` and chained, each trade `producedBy` its fill. Its shape (keys, step types, statuses, approval, cancel, per-fill shape) equals the live (c) order's; the 250 order's equals the live (c'') order's. |
| **(h) Facing subsidiaries** | `GET /cdm-service/cdm/entities`; `PUT /cdm-service/cdm/parties/22214/servicing-party` to another subsidiary; a direct sell of 7 IBM; switch back | Three subsidiaries (NYC, LDN, TKY). Account 22214's party is a CDM `Client` account with a `servicingParty`. account-service's `GET /account/22214` (the Account tab) shows it as `servicingPartyRef`/`servicingPartyName`, and shows the switch. After the switch, the new trade has the new subsidiary as `Party1` and `Buyer`; the trade from (a) keeps the old one, and an order placed before the switch and force-filled after it keeps the old subsidiary as dealer on its fill and the fill's trade state. An unknown subsidiary is rejected with 400. The original subsidiary is restored at the end. |
| **(f) Validation** | `GET /cdm-service/cdm/validation-summary` | No stored CDM object is `INVALID` or `NOT_QUALIFIED` (both counts must be present and 0; rejected requests such as quantity 0 validate too). No `BusinessEvent` is stored on its own (only inside accepted steps; trade refs hold `TradeState`s). No trades or cancels are parked, and the run created no lifecycle failure rows. Runs last, after (g). |

The run ends with an `[info] examples:` line naming the direct trade, the orders of (b), (c), (c'), (c''), (g) and the rejected request, to look at in the CDM tab (`/cdm/order/<id>`, `/cdm/trade/<id>`).

### Notes and deviations

- **Unknown ticker or account is not a rejection.** The matcher checks only the payload's shape
  (account, security, side, quantity and limit present and positive), so an order for an unknown
  ticker or account is accepted (201) and cdm-service records it as a lifecycle failure (visible
  in `/cdm/validation-summary` under `failures`), not as a rejection. The script does not drive it.
- **(g) cannot book a fill while cdm-service is down.** In 017 trade-service checks the
  security with reference-data, whose security master is cdm-service, so a force-fill submitted
  during an outage fails (502). (g) therefore submits the fills while cdm-service is up, with
  trade-processor paused so they wait in NATS, and lets trade-processor book them after
  cdm-service is stopped: cdm-service misses the booked fills exactly as in an outage.
- **(c) uses a partial force-fill.** 009's force-fill always fills the whole remainder. State
  017 adds an optional `quantity` to `POST /order-matcher/orders/{orderId}/force-fill` (query
  parameter or JSON body; it must be greater than 0 and at most the remaining quantity, else
  400; absent, the whole remainder is filled as before). The brief's "limit just in the money"
  became a limit out of the money so the matcher cannot fill the rest first.
- **What (e) compares, per object** (these fields are lossless through CDM):
  - Account: `id`, `displayName`, `cdmRef`.
  - Person: `logonId`, `fullName` (CDM `firstName` + `surname`), `email`, `employeeId`,
    `department`, `photoUrl`, `cdmRef`.
  - Stock: `ticker`, `companyName`, `cdmRef`.
  - Trade: `id`, `accountId`, `security`, `side`, `quantity`, `price`, `created`, and `state`
    through the mapping below.
  - Position: `security`, `quantity`, `averageCostBasis`, `cdmRef`, and `accountId`. The
    account id is not a field of the CDM `Position` (it is the account of the portfolio state
    the position belongs to), so the jq re-projection injects the test account id; `cdmRef`
    (`cdm:position:<account>:<ticker>`) is what ties the position to the account.
- **Not lossless** (also listed in `contracts/cdm-mapping.md` section 12):
  - Trade `state` (Rulings R35, R37): CDM records `positionState` `EXECUTED` for TraderX
    `New`, `Processing` and `Settled`, because TraderX has no settlement process
    (trade-processor flips New → Processing → Settled in one transaction, with no transfer or
    settlement event). TraderX's "Settled" label is therefore not kept, and the three cannot be
    told apart on the way back. `Cancelled` ↔ `CANCELLED` round-trips. trade-processor
    publishes booked trades as `Settled`; the live run checks it arrives as `Executed`.
  - Trade `updated`: position-service row bookkeeping; no CDM field.
  - Position `updated`: in position-service it is when the row was written; the CDM side has
    the last trade's execution time, about a millisecond earlier.
  - Trade `cdmRef`: position-service trade rows have no `cdmRef` field, so the ref
    `cdm:trade:<tradeId>` is derived from the trade id.
- **Scope of (f).** Positions are derived when they are read, not stored, so the validation
  summary does not count them. (e) checks the account's portfolio state separately.

## cdm-service endpoints used only here

- `GET /cdm/rejections[?accountId=]`: the rejected order requests, oldest first. Each entry has
  its `rejectionId`, `cdmRef`, `accountId`, `quantity`, `reason`, `rejectedAt`, both global
  keys, `validation` and `lineage` link.
- `GET /cdm/validation-summary`: validation counts by status in two forms:
  - `objects`: every distinct stored object version, each with its latest result;
  - `latest`: the latest version per ref and type.
  It also returns the first 50 objects that are not valid (`notValid`), `failures.total` with
  the 50 most recent failure rows (`failures.recent`), `pending.trades` / `pending.cancels`,
  and the startup backfill's progress (`backfill.directTrades` / `backfill.openOrders` /
  `backfill.orderFills` / `backfill.canceledOrders`: `running`, `done` or `disabled`;
  `backfill.complete`). The `orderFills` pass imports order fills booked while cdm-service was
  down (Ruling R31); the `canceledOrders` pass imports cancels it missed, including orders created
  and canceled entirely during the outage (Ruling R41).
- `GET /cdm/trades/{tradeId}/lineage`: the trade state first (summary with quantity, price,
  side, account, venue, security), then `executionType`/`executionTypeMeaning`, then `producedBy`:
  the fill of an order (kind `fill`, `orderId`, `fillNumber`, `label`) or the direct workflow
  `cdm:direct:<tradeId>` (kind `direct`), with its `proposed` and `accepted` steps; `null` if no
  producer is stored. `GET /cdm/trades/{tradeId}` has the same order with the full stored
  `tradeState` and no steps.
- `GET /cdm/resolve/cdm:direct:{tradeId}`: the direct workflow as a CDM `Workflow`.
- order-matcher `POST /orders/{orderId}/force-fill` with an optional `quantity` (see (c)).
