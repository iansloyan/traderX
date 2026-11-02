# Contract Delta: 017-cdm-lifecycle-mapping

Parent state: `009-order-management-matcher`

Everything in this state is **additive**. Every 009 REST path, NATS subject, payload field and UI
route keeps its 009 behaviour. New fields on existing payloads are optional: a 009 client that ignores
them works unchanged. The CDM content behind the new endpoints is specified in
[`cdm-mapping.md`](./cdm-mapping.md).

OpenAPI copies served by the API explorer (`/api/docs`) for this state are in
`contracts/<service>/openapi.yaml` (see "API explorer contracts" below).

## `cdmRef` format

`cdm:<kind>:<externalKey>`, kind one of `party`, `person`, `security`, `order`, `trade`, `direct`,
`position`.

| Projection | `cdmRef` | Example |
|---|---|---|
| Account | `cdm:party:<accountId>` | `cdm:party:22214` |
| Account user | `cdm:person:<username>` | `cdm:person:user01` |
| Person | `cdm:person:<logonId>` | `cdm:person:user01` |
| Stock | `cdm:security:<ticker>` | `cdm:security:IBM` |
| Order (matcher `OrderResponse`) | `cdm:order:<orderId>` | `cdm:order:ord-013-0001` |
| Trade (trade-processor `Trade`, position-service trade rows) | `cdm:trade:<tradeId>` | `cdm:trade:7f3c…` |
| Direct trade's workflow (no projection; returned as `producedBy.cdmRef` of a direct trade) | `cdm:direct:<tradeId>` | `cdm:direct:7f3c…` |
| Position | `cdm:position:<accountId>:<ticker>` | `cdm:position:22214:IBM` |

`cdm:direct` is a cdm-service lookup address for the trade ticket's proposed → accepted workflow,
not a CDM concept; it keeps `cdm:trade:<tradeId>` holding only the `TradeState` for every trade.

The ref is derived from the TraderX id, so it is valid in a synchronous response even before
cdm-service has stored the object. It resolves at `GET /cdm-service/cdm/resolve/{cdmRef}`; until the
object exists the answer is 404 `{"cdmRef": "...", "status": "pending"}`.

## REST: new service `cdm-service`

Port `18120`, ingress prefix `/cdm-service/` (e.g. `http://localhost:8080/cdm-service/health`).
CDM objects are returned in their Rosetta JSON form (as written by cdm-java's `RosettaObjectMapper`).
Responses that return one stored CDM object carry the headers `X-CDM-Type` (Java class of the CDM
type), `X-CDM-Validation` (`VALID`, `INVALID` or `NOT_QUALIFIED`) and `X-CDM-Global-Key` (the store
key, see `cdm-mapping.md` section 8.2).

### Health

| Method and path | Answer |
|---|---|
| `GET /health` | 200 `{"status":"ok","service":"cdm-service","cdmVersion":"7.8.0"}` once the security and party masters are seeded; 503 `{"status":"starting"}` before (Ruling R18). The compose healthcheck uses it. |
| `GET /actuator/prometheus` | Prometheus metrics, including `traderx_cdm_objects_total{type,validation}`, `traderx_cdm_failures_total{stage}`, `traderx_cdm_pending_trades`, `traderx_cdm_pending_cancels`. |

### CDM objects and lineage

| Method and path | Answer |
|---|---|
| `GET /cdm/resolve/{cdmRef}` | The CDM object a ref points at (table in `cdm-mapping.md` section 8.1): for `cdm:order:<id>` the order's own latest version (the cancel, else the latest proposal version; never a fill step), for `cdm:trade:<id>` the `TradeState`, for `cdm:direct:<id>` a CDM `Workflow` of the direct trade's two steps built on read (no `X-CDM-Global-Key`; `X-CDM-Validation` = worst step status). 404 pending when unknown, 400 when malformed. An order ref also returns `Link: </cdm/orders/{orderId}/lineage>; rel="lineage"`, a direct ref `Link: </cdm/trades/{tradeId}/lineage>; rel="lineage"`. |
| `GET /cdm/securities/{ticker}` | CDM `Security`; 404 when unknown. |
| `GET /cdm/parties/{accountId}` | CDM `Party` of the account, with its persons resolved from the person master and its CDM `account` (`accountType` `Client`, `servicingParty` = the facing TraderX subsidiary); `traderx-nyc`, `traderx-ldn` and `traderx-tky` return the subsidiary parties; `traderx-matching-engine` returns the venue party; 404 when unknown. |
| `GET /cdm/entities` | The TraderX subsidiaries that can face a client, as CDM `Party` objects, in seed order. |
| `PUT /cdm/parties/{accountId}/servicing-party` | Body `{"entity": "traderx-ldn"}`: makes that subsidiary service the account (a new version of the account party). Answers the updated party; 400 for an unknown subsidiary, 404 for an unknown account, 503 before the party master is ready. Later orders and trades of the account face the new subsidiary; earlier ones keep theirs. |
| `GET /cdm/orders/{orderId}/lineage` | `{cdmRef, orderId, status, instructionQuantity, executedQuantity, canceledQuantity, remainingQuantity, remainingQuantityFormula, quantityWarning, proposal, fills[], cancel, parties, security, failures[], pending: {trades[], cancel}}`. `parties` = `{account, dealer, venue}`: the client (`role` `Party2`, with `accountId`), the facing subsidiary (`role` `Party1`) and the venue (`role` `ExecutionFacility`), each with `cdmRef` and `name`. `proposal` = `{executionType, executionTypeMeaning, versions[], approval}`: `versions` are the order's own steps in write order (`proposed`, then `approved`, or `rejected` for a rejection id); `approval` = `{approved, party, timestamp, rejectedReason, stepStoreGlobalKey}` from the latest version carrying a `WorkflowStepApproval`, or null. Each `fills[i]` = `{fillNumber, label, tradeId, quantity, price, executedAt, filledSoFar, executionType, executionTypeMeaning, proposed, accepted, tradeState}`, numbered at read time in booking order; `label` is "Partial fill N", "Final fill N" or "Full fill"; `accepted` is null until stored; `tradeState` = `{cdmRef, storeGlobalKey, cdmGlobalKey, positionState, validation}` or null. `cancel` is the `Cancelled` correction step or null; `canceledQuantity` is the matcher's, as the cancel records it. Every step has `stepType` (`proposed`, `approved`, `rejected`, `cancel`, `fill-proposed`, `fill-accepted`), `storeGlobalKey`, `cdmGlobalKey`, `previousStoreGlobalKey`, `cdmType`, `validation`, `createdAt`, `timestamp` and the stored CDM `object`. Also works for a rejection id. 404 pending when unknown. |
| `GET /cdm/trades/{tradeId}` | `{cdmRef, tradeState, producedBy, executionType, executionTypeMeaning}`: the stored `TradeState` first (both keys, `cdmType`, `previousStoreGlobalKey`, validation, `createdAt`, object), then `producedBy` = `{cdmRef, storeGlobalKey, kind, orderId, fillNumber, label, lineage}` with `kind` `fill` (the accepted fill step under `cdm:order:<orderId>`) or `direct` (`cdm:direct:<tradeId>`), or null when no workflow is linked. `executionType` is `OnVenue` or `Electronic`. 404 pending until the trade state is stored. |
| `GET /cdm/trades/{tradeId}/lineage` | `{cdmRef, tradeId, tradeState, executionType, executionTypeMeaning, producedBy}`. `tradeState` is a summary (`cdmRef`, both keys, `positionState`, validation, `tradeId`, `previousStoreGlobalKey`, `quantity`, `price`, `side` (the client's), `account` (the client, `Party2`), `dealer` (the facing TraderX subsidiary, `Party1`), `venue` (the execution facility; null on a direct trade), `security`); `producedBy` is as above plus the producing workflow's `proposed` and `accepted` steps (`stepType` `fill-proposed`/`fill-accepted` for a fill, `proposed`/`accepted` for a direct trade). 404 pending until the trade state is stored. |
| `GET /cdm/positions/{accountId}` | `{cdmRef, cdmType, storeGlobalKey, cdmGlobalKey, validation, object, initialState, trades[], skipped[], positions[]}`: the account's CDM `PortfolioState`, derived on read, with each contributing trade (`cdmRef`, both keys, `tradeCdmGlobalKey`, `executionStepCdmGlobalKey`, `security`, `signedQuantity`, `price`, `executedAt`, validation). `positions[i]` = `{cdmRef, security, quantity, trades[]}`, where `trades[]` = `{tradeId, cdmRef, signedQuantity, price, side, executedAt}` in execution order, summing to `quantity`. 404 pending when the account has no CDM trades or is unknown (Ruling R30); 400 for a non-numeric id. |
| `executionTypeMeaning` | Shown next to every `executionType`: fill or trade `OnVenue` "Executed on the TraderX matching engine (the venue, execution facility)"; order proposal `OnVenue` "Sent to the TraderX matching engine (the venue, execution facility)"; `Electronic` "Booked bilaterally between the TraderX subsidiary and the client from the trade ticket, with no venue". CDM's own definitions are quoted in `cdm-mapping.md` section 6. |

### Diagnostics

| Method and path | Answer |
|---|---|
| `GET /cdm/rejections[?accountId=]` | `{accountId, rejections[]}`: rejected order requests, oldest first, each with `rejectionId`, `cdmRef`, `accountId`, `quantity`, `reason` (the venue approval's `rejectedReason`, else the rejected step's comment), `rejectedAt`, `storeGlobalKey`, `cdmGlobalKey`, `validation` and a `lineage` link. |
| `GET /cdm/validation-summary` | Validation counts by status over every stored object version (`objects`) and over the latest version per ref and type (`latest`); the first 50 not-valid objects (`notValid`); `failures.total` and the 50 most recent `failures.recent`; `pending.trades`, `pending.cancels`; startup backfill progress `backfill.directTrades`, `backfill.openOrders`, `backfill.orderFills`, `backfill.canceledOrders` (`running`, `done` or `disabled`) and `backfill.complete`. |

### Projections (used by the existing services)

| Method and path | Answer |
|---|---|
| `GET /projections/stocks`, `GET /projections/stocks/{ticker}` | `StockProjection {ticker, companyName, cdmRef}`; 404 unknown; 503 until the security master is seeded. |
| `GET /projections/accounts`, `GET /projections/accounts/{id}` | `AccountProjection {id, displayName, cdmRef}`; 404 unknown. |
| `POST /projections/accounts` | Body `{id?, displayName}`. No id (or id ≤ 0): new id from `accounts_seq`, never overwriting a taken id (409 after 20 taken ids in a row). With an id: upsert. 400 when `displayName` is over 50 characters. |
| `PUT /projections/accounts/{id}` | Body `{displayName}`; upsert. |
| `GET /projections/accountusers[?accountId=]` | `AccountUserProjection {accountId, username, cdmRef}`. |
| `POST /projections/accountusers` | Body `{accountId, username}`; 404 unknown account; 400 username blank or over 15 characters. An unknown username becomes an identifier-only person. |
| `GET /projections/people[?search=][&employeeId=]` | `PersonProjection {logonId, fullName, email, employeeId, department, photoUrl, cdmRef}` for directory persons only (Ruling R20). |
| `GET /projections/people/{logonId}` | One directory person; 404 when unknown or identifier-only. |
| `GET /projections/positions/{accountId}` | The 009 position-service shape plus `cdmRef`, computed from CDM. |

Party and people projections answer 503 until the party master is seeded, and 503 when a store write
fails. Client errors stay 400, 404 and 409.

**Access control.** The projection write endpoints (`POST /projections/accounts`,
`PUT /projections/accounts/{id}`, `POST /projections/accountusers`) are reachable through the ingress
(`/cdm-service/projections/...`) without authentication. This is the same posture as 009's
account-service (`POST/PUT /account/`, `POST/PUT /accountuser/` are unauthenticated there too): TraderX
is a demo with no identity layer. Anyone who can reach the ingress can create or rename accounts and
add account users, directly or through account-service. A deployment beyond a local demo should keep
these paths internal (drop them from the ingress) or put an authenticating proxy in front of both
services.

## REST: changed existing services

All changes are additive. "Optional" means the field may be absent or null, and 009 clients may ignore
it.

| Service | Path(s) | Change |
|---|---|---|
| account-service | `GET/POST/PUT /account/`, `GET /account/{id}`, `GET/POST/PUT /accountuser/`, `GET /accountuser/{id}` | Served from cdm-service (`/projections/accounts`, `/projections/accountusers`). `Account` and `AccountUser` gain optional `cdmRef`. New error answers: 400 (cdm-service rejected the input, e.g. `displayName` over 50 or `username` over 15 characters), 409 (account id conflict), 503 (cdm-service down or not ready). 009's 404 bodies are unchanged. |
| people-service | `GET /People/GetPerson`, `GET /People/GetMatchingPeople`, `GET /People/ValidatePerson` | Served from cdm-service (`/projections/people`). `Person` gains optional `cdmRef`. New 503 when cdm-service is unreachable, not ready or answers garbage. Identifier-only persons are 404, as unknown persons were in 009. |
| reference-data | `GET /stocks`, `GET /stocks/{ticker}` | Served from cdm-service (`/projections/stocks`). `Stock` gains optional `cdmRef`. New 503 when cdm-service is unavailable. Supported-ticker and max-ticker filters unchanged. |
| position-service | `GET /positions/{accountId}`, `GET /positions/` | `Position` gains optional, derived `cdmRef` (`cdm:position:<accountId>:<security>`; absent when either part is null). `Trade` rows (`GET /trades/{accountId}`, `GET /trades/`) gain optional, derived `cdmRef` (`cdm:trade:<id>`; absent when the id is null). |
| trade-service | `POST /trade/` | `TradeOrder` gains optional `orderId` (the matcher's order id on fills; absent for direct trades), passed through to `/trades`. |
| order-matcher | every `OrderResponse` (REST and `/orders`, `/accounts/<id>/orders` events) | Gains optional `filledQuantity` (shares executed; on `CANCELED` the pre-cancel filled amount), `canceledQuantity` (only on `CANCELED`: the remainder at the cancel) and `cdmRef` (`cdm:order:<orderId>`). |
| order-matcher | `POST /orders/{orderId}/force-fill` | Optional `quantity` as a query parameter or JSON body `{"quantity": n}`. Absent: the whole remainder is filled, as in 009. Present: must be > 0 and ≤ the remaining quantity of an open order, else 400; given twice with different values, 400. |
| order-matcher | `POST /orders` (invalid request) | Still answers 400 `invalid order payload`; now also publishes the request on `/orders/rejected` (best effort; a publish failure never changes the 400). |
| order-matcher | startup seed | New property `order.matcher.seed-statuses` (env `ORDER_MATCHER_SEED_STATUSES`): comma-separated statuses of the demo orders to seed, case-insensitive; blank = all (009 behaviour); unknown status fails startup. 017 compose sets `NEW`. |
| trade-processor | `Trade` (REST and events) | Gains `orderId` (always present on the wire, null for direct trades) and derived `cdmRef` (`cdm:trade:<id>`, not persisted). |

## Event contract changes (NATS)

The full subject map, including the unchanged 009 subjects, is `../system/messaging-subject-map.md`.

| Subject | Producer → consumer | Payload |
|---|---|---|
| `/trades` (changed) | trade-service → trade-processor | `TradeOrder` plus optional `orderId`. |
| `/accounts/<accountId>/trades` (changed) | trade-processor → UI | `Trade` plus `orderId` and `cdmRef`. |
| `/orders`, `/accounts/<accountId>/orders` (changed) | order-matcher → UI, cdm-service | `OrderResponse` plus `filledQuantity`, `canceledQuantity`, `cdmRef`. |
| **`/trades/booked`** (new) | trade-processor → cdm-service | The committed `Trade` (same JSON as `/accounts/<id>/trades`), published after the booking transaction commits, on one global subject. Best effort: a publish failure never fails the booking. |
| **`/orders/rejected`** (new) | order-matcher → cdm-service | `RejectedOrder {rejectionId ("rej-<uuid>"), accountId, security, side, quantity, limitPrice, reason, rejectedAt}`; every field is always present (null when the request lacked it). |
| **`cdm.workflow`** (new) | cdm-service → any CDM consumer | Each stored `WorkflowStep`: order proposals and their approved versions, rejected steps, cancels, fill proposed and accepted steps, direct-trade steps. |
| **`cdm.events`** (new) | cdm-service → any CDM consumer | The execution `BusinessEvent` taken from each newly stored accepted step (fill or direct trade), published right after that step's `cdm.workflow` message. The event is not stored on its own. |
| **`cdm.tradestate`** (new) | cdm-service → any CDM consumer | Each stored `TradeState`. |

Payload of the three `cdm.*` subjects:

```json
{
  "cdmRef": "cdm:order:ord-013-0001",
  "globalKey": "<storeGlobalKey: SHA-256 of the canonical JSON>",
  "cdmType": "cdm.event.workflow.WorkflowStep",
  "validation": { "status": "VALID", "messages": [] },
  "object": { "...": "the stored CDM object, verbatim" }
}
```

`globalKey` here is the **store** key, not the Rune `meta.globalKey` (which is inside `object.meta`).
On `cdm.events` the `globalKey` is the SHA-256 of the event's canonical JSON, computed as the store
computes keys, the `validation` is the event's (`Qualify_Execution` included), and the payload also
carries `stepGlobalKey`: the store key of the accepted step the event was taken from.
A message is published only after the object is stored, and the `object` is the stored JSON verbatim
(`LifecycleAdapterTest.publishedObjectIsTheStoredJsonVerbatim`).

cdm-service subscribes to `/orders`, `/orders/rejected` and `/trades/booked` only after every CDM
master is ready, and resubscribes after a NATS reconnect.

## Schema changes (shared Postgres)

| Table | Change |
|---|---|
| `trades` | New column `orderid VARCHAR(32) NULL` (null for direct trades). |
| `orderbook` (order-matcher) | New column `canceledquantity INTEGER NULL`. |
| `cdm_documents` (new) | `id BIGSERIAL PK` (write order), `global_key VARCHAR(128)` (SHA-256 store key, indexed, not unique), `cdm_ref VARCHAR(160)` (indexed), `cdm_type VARCHAR(128)`, `payload TEXT` (Rosetta JSON), `previous_global_key VARCHAR(128) NULL` (the step this one follows in its workflow, `cdm-mapping.md` section 8.3), `validation_status VARCHAR(16)`, `validation_messages TEXT NULL`, `created_at TIMESTAMP`, `produced_by_ref VARCHAR(160) NULL` and `produced_by_global_key VARCHAR(128) NULL` (on a `TradeState`: the accepted step that produced it, section 8.4). One row per stored object. |
| `cdm_pending_trades` (new) | `trade_id PK`, `order_id` (indexed), `payload`, `received_at`, `reported_at`: booked trades parked until their order's approved proposal is stored. |
| `cdm_pending_cancels` (new) | `order_id PK`, `payload`, `received_at`, `reported_at`: `CANCELED` events parked until the order's fills are stored. |
| `cdm_lifecycle_failures` (new) | `id BIGSERIAL PK`, `cdm_ref` (indexed), `stage`, `message`, `payload`, `failed_at`: events not fully turned into stored CDM. |

The schema is created when the 017 data volume is first initialised. `accounts` and `accountusers`
keep their 009 shape and are now written only by cdm-service's party master, as foreign-key anchors.
A volume created by an older build (including one written before the fill-instruction amendment, which
lacks the `produced_by_*` columns) is detected by the start script, which asks for `--reset-data`.
First-build data is not migrated (Ruling R57).

## UI

- New routes: `/cdm` (account's orders, trades, positions and rejections with validation status;
  each position lists the trade states that sum to it), `/cdm/order/<orderId>[?fill=N]` (a tree: the
  order proposal with the venue's approval badge, its versions, the fills nested beneath it, each with
  its proposed and accepted steps and its trade state inline, then the cancel; `?fill=N` highlights a
  fill) and `/cdm/trade/<tradeId>` (the trade state first, then "Produced by: fill N of order …" or
  "Direct trade" with its two steps). Party and security cards, execution type with its meaning, and
  raw CDM JSON on both. New **CDM** header tab, and a **CDM** link per row in the order and trade
  blotters.
- The base href changed from `.` to `/` (`index.html` and the `--base-href` of the `package.json`
  build and start scripts), so assets load on deep routes such as `/cdm/order/<id>`. The app is served
  from the ingress root, as before.
- Consequence of the base href change: the 017 UI must be served from the root of its origin. It
  cannot be hosted under a sub-path (for example `https://host/traderx/`), because asset URLs now
  resolve against `/`. With `.`, deep links such as `/cdm/order/<id>` opened directly (bookmark, reload,
  link from the API explorer) resolved assets against `/cdm/order/` and failed to load; `/` is what makes
  those deep links work. 009's UI was not sub-path deployable either (its environment URLs are
  root-relative), so no supported deployment is lost.
- New environment entry `cdmServiceUrl` (`//<host>/cdm-service`).

## Environment variables

| Variable | Component | Default | Meaning |
|---|---|---|---|
| `CDM_SERVICE_URL` | account-service, people-service, reference-data, ingress | `http://cdm-service:18120` | Where the projections come from (ingress: the `/cdm-service/` upstream). |
| `CDM_SERVICE_TIMEOUT_MS` | account-service | `5000` | Timeout of calls to cdm-service. |
| `CDM_SERVICE_PORT` | cdm-service | `18120` | HTTP port. |
| `DATABASE_PG_HOST`, `DATABASE_PG_PORT`, `DATABASE_NAME`, `DATABASE_DBUSER`, `DATABASE_DBPASS` | cdm-service | `localhost`, `5432`, `traderx` ×3 | Shared Postgres. |
| `NATS_BROKER_HOST` / `NATS_ADDRESS` | cdm-service | `nats-broker` / `nats://<host>:4222` | NATS broker. |
| `CDM_LIFECYCLE_NATS_ENABLED` | cdm-service | `true` | Subscribe to the TraderX subjects and publish `cdm.*`. |
| `CDM_LIFECYCLE_BACKFILL_ENABLED` | cdm-service | `true` | Startup import of seed trades, open orders, missed fills and missed cancels. |
| `ORDER_MATCHER_URL` | cdm-service | `http://order-matcher:18110` | Read-back of orders whose `NEW` event was missed (Ruling R26); startup listing of open and canceled orders (`GET /orders?status=open`, `?status=canceled`). |
| `TZ`, `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` | cdm-service | `UTC` (compose) | Zone-less `trades.created` values are read as UTC. |
| `ORDER_MATCHER_SEED_STATUSES` | order-matcher | blank (all); 017 compose `NEW` | Statuses of the seeded demo orders. |

## API explorer contracts

The API explorer at `/api/docs` serves an OpenAPI contract per service. For each service,
`pipeline/install-generated-api-explorer.sh` now prefers `specs/<stateId>/contracts/<service>/openapi.yaml`
when that file exists, and otherwise falls back to the catalog's `contractPath` (the 001 baseline). This
state provides:

- `contracts/account-service/openapi.yaml`, `contracts/people-service/openapi.yaml`,
  `contracts/reference-data/openapi.yaml`, `contracts/position-service/openapi.yaml`,
  `contracts/trade-service/openapi.yaml`, `contracts/trade-processor/openapi.yaml`: the baseline
  contracts plus the additive fields and error answers above (Ruling R17);
- `contracts/cdm-service/openapi.yaml`: the new service (catalog entry `cdm-service`, only present
  where the generated tree has a `cdm-service/` directory).

States without a `contracts/<service>/openapi.yaml` of their own are unaffected.

## Compatibility notes

- 009 and earlier states are not changed: every change is in this pack's patch, its runtime overrides,
  or pipeline code that only acts when a 017 file or directory exists.
- A 009 client that ignores unknown fields sees the same payloads.
- New failure mode: account, people and reference data depend on cdm-service (CDM is authoritative for
  them), so they answer 503 while it is down, and a trade submitted then fails with 500 at trade-service
  (Ruling R15, gap G8 in `cdm-mapping.md`). Compose starts them only after cdm-service is healthy.
- Order and trade flows never wait for CDM. CDM objects appear asynchronously, usually within a second.
