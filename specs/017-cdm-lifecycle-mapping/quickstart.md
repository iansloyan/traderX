# Quickstart: CDM Lifecycle, Party and Security Model

Run state 017 from a clone, drive it, and see TraderX's orders and trades as FINOS CDM objects.

## Prerequisites

- Linux or macOS with Docker running and `docker compose` (v2).
- For generation: `bash`, `git`, `node`/`npm`, `jq`, `perl` and ripgrep (`rg`), as for every state.
- For the test script: `curl` and `jq`.
- Free host ports: 8080 (ingress), 18083, 18085, 18088-18093, 18100, 18110, 18120, 4222, 8222, 8081,
  3001, 3100, 3200, 9090, 9115, 4317, 4318, 8888, 8889, 13133.

## 1) Generate This State

```bash
bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping
```

This generates state 009, applies `generation/patches/0001-state-overlay.patch`, copies the new
`cdm-service` from `generation/runtime-overrides/cdm-service/`, and writes the result to
`generated/code/target-generated/` (set `TRADERX_GENERATED_ROOT` to generate elsewhere). It takes a few
minutes.

## 2) Start Runtime

```bash
./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh            # keeps existing data
./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data   # fresh database
curl -s http://localhost:8080/cdm-service/health   # {"status":"ok","service":"cdm-service","cdmVersion":"7.8.0"}
```

The first start builds the images (cdm-service takes about a minute). cdm-service reports `ok` once it
has seeded 509 securities, the 009 accounts and people, the TraderX subsidiaries (NYC, LDN, TKY)
and the venue party. account-service, people-service and reference-data start after that.

Notes:

- **Port clashes.** State 017 uses the same host ports as state 009 and the other compose states, so
  stop any other TraderX stack first (for example
  `./scripts/stop-state-009-order-management-matcher-generated.sh`). The start script refuses to start
  while compose project `traderx-state-009` is running. Any other process or compose project holding
  one of the ports above makes `docker compose up` fail with "port is already allocated".
- **Data volume.** Postgres data lives in the volume `traderx-state-017_postgres_state_017_data`, and
  the schema is created only when that volume is first initialised. If the volume was created by an
  older build (no `cdm_documents` table, or other 017 schema items missing), the start script stops
  and asks you to rerun with `--reset-data`, which deletes all 017 orders, trades and CDM documents.
  **A volume written before the fill-instruction amendment must be reset once** (it has stored execution
  `BusinessEvent`s and no fill workflows; the start script detects it by the missing
  `cdm_documents.produced_by_*` columns): `./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh --reset-data`.
- **Seed data.** The order matcher seeds only its `NEW` demo orders in 017
  (`ORDER_MATCHER_SEED_STATUSES=NEW`; blank means every status, as in 009). At startup cdm-service
  imports the 009 seed trades as direct trades, the matcher's open orders as CDM proposals, and any
  fills booked while it was down.
- **Loki on newer Docker Engines (Ruling R33).** The bundled promtail 2.9.8 discovers containers with
  Docker API 1.42. Docker Engines whose minimum API version is above 1.42 (for example a host with API 1.53,
  minimum 1.44) reject it, so Loki stays empty and the Loki checks of the inherited 009 test fail.
  There is no workaround in this state. The start script sets `TRADERX_PROMTAIL_DOCKER_API_VERSION`
  to the Docker server's API version (fallback `1.44`; set it yourself to override), and compose
  passes it to promtail as `DOCKER_API_VERSION`, but promtail's `docker_sd_configs` discovery ignores
  it: setting it to the engine's version (`1.53`) was tried and did not help. Engines that still accept
  API 1.42 (check `docker version --format '{{.Server.MinAPIVersion}}'`) are not affected. The fix is
  a newer promtail image in the shared observability baseline. State 009 has the same limitation;
  nothing else is affected.

## 3) Run the Tests

```bash
./scripts/test-state-017-cdm-lifecycle-mapping.sh                 # CDM scenarios (a)-(g)
```

It prints a `[pass]`/`[fail]` line per assertion and exits non-zero on any failure, and ends with example
order and trade ids to open in the CDM tab. What each scenario proves: `tests/smoke/README.md`. Scenario
(g) uses the docker CLI to stop and start cdm-service and pause trade-processor (an outage the startup
backfill must recover); `TRADERX_CDM_SKIP_OUTAGE=1` skips it. It can be rerun on the same stack; use
`--reset-data` on start for a clean baseline. Orders for an unknown ticker or account are accepted by the matcher and recorded by
cdm-service as lifecycle failures (visible in `/cdm-service/cdm/validation-summary`), not as rejections.

The inherited 009 checks can run against the 017 stack too:

```bash
COMPOSE_PROJECT_NAME=traderx-state-017 TRADERX_GRAFANA_ADMIN_PASSWORD=traderx-state-017 \
  ./scripts/test-state-009-order-management-matcher.sh
```

They pass except the Loki checks on Docker Engines that reject API 1.42 (see the note above). Evidence,
from a run on a fresh data volume with the 017 defaults (after Ruling R32): the command above (with
`--skip-messaging`) passed every check up to Loki, including the open-order check (2 open orders via the
ingress), the observability, message-bus, Grafana and Prometheus checks, and the create, list, cancel,
force-fill, trade/position and auto-fill journeys. It then stopped at
`[error] expected non-empty Loki ingestion in last 5m, got 0`. The checks after it were run directly:
`test-web-angular-baseline-ux-contract.sh` passed, and
`test-messaging-009-order-management-matcher.sh http://localhost:8080 <trade-service URL> 22214`
ended with `[done] state 009 messaging smoke tests passed`. Afterwards `cdm_lifecycle_failures` and
`cdm_pending_trades` were empty and every order's CDM steps were `VALID`.

## 4) Look at the CDM

Open **http://localhost:8080/cdm** (the **CDM** tab). It lists the selected account's orders, trades and
rejections with their CDM validation status. `/cdm/order/<orderId>` and `/cdm/trade/<tradeId>` show the
order as its proposal with the venue's approval, each fill nested as its own proposed → accepted workflow
with its trade state, and the cancel; a trade page leads with the trade state, then the fill or direct
workflow that produced it. Party and security cards (ISIN and FIGI labelled
"demo identifier") and the raw CDM JSON. The order and trade blotters have a **CDM** link per row.

Over REST, through the ingress:

```bash
curl -s http://localhost:8080/cdm-service/cdm/resolve/cdm:security:IBM | jq .
curl -s http://localhost:8080/cdm-service/cdm/resolve/cdm:party:22214 | jq .
curl -s http://localhost:8080/account-service/account/22214 | jq .cdmRef      # "cdm:party:22214"
curl -s http://localhost:8080/cdm-service/cdm/positions/22214 | jq '.validation, .positions'
curl -s http://localhost:8080/cdm-service/cdm/validation-summary | jq '.latest, .backfill'
```

| Endpoint (prefix `/cdm-service`) | What |
|---|---|
| `GET /cdm/orders/{orderId}/lineage` | The order: `proposal` (`versions`, venue `approval`, `executionType` + meaning), `fills` (each `fillNumber`, `label`, `filledSoFar`, `proposed`/`accepted` steps, `tradeState`), `cancel`, quantities, parties, security, failures |
| `GET /cdm/trades/{tradeId}` | The trade state first, then `producedBy` (kind `fill` with order id and fill number, or `direct`) and `executionType` (`OnVenue` / `OffFacility`) with its meaning |
| `GET /cdm/trades/{tradeId}/lineage` | The trade state summary, then the producing fill's or direct workflow's `proposed` and `accepted` steps |
| `GET /cdm/positions/{accountId}` | CDM `PortfolioState`; each of `positions[]` lists its `trades`, whose signed quantities sum to the position |
| `GET /cdm/resolve/{cdmRef}` | Any `cdmRef`; `cdm:order:<id>` is the order's own latest version (cancel, else approved/rejected proposal), `cdm:trade:<id>` the `TradeState`, `cdm:direct:<id>` the direct trade's CDM `Workflow` |
| `GET /cdm/rejections?accountId={accountId}` | Rejected order requests |
| `GET /cdm/validation-summary` | Validation counts, failures, parked events, backfill progress |

`POST /order-matcher/orders/{orderId}/force-fill` takes an optional `quantity` (query parameter or
`{"quantity": n}` body) to fill only part of the remainder. Live CDM objects are also published on the
NATS subjects `cdm.workflow`, `cdm.events` and `cdm.tradestate` (watch them in the pub/sub inspector at
`http://localhost:8080/api/docs/pubsub-inspector.html`). Metrics: `traderx_cdm_*` in Prometheus
(`http://localhost:9090`).

## 5) Status and Stop

```bash
./scripts/status-state-017-cdm-lifecycle-mapping-generated.sh
./scripts/stop-state-017-cdm-lifecycle-mapping-generated.sh
```

Stopping keeps the data volume. Use `--reset-data` on the next start to begin with an empty database.
