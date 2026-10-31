# Data Model: CDM Lifecycle, Party and Security Model

## Scope

Data model impact relative to `009-order-management-matcher`. The CDM object model and the mapping
rules are in `contracts/cdm-mapping.md`; the column-level schema delta is in
`contracts/contract-delta.md`.

## Source of Truth

| Data | Authority in 017 | 009 storage kept as |
|---|---|---|
| Accounts, account users | CDM `Party` (cdm-service) | `accounts`, `accountusers`: foreign-key anchors written by cdm-service's party master, never served from |
| Persons | CDM `NaturalPerson` (cdm-service) | people-service no longer holds data (its `people.json` seed moved to cdm-service) |
| Securities | CDM `Security` (cdm-service) | reference-data serves cdm-service's projection; cdm-service seeds from the same S&P 500 CSV |
| Orders | `orderbook` (order-matcher) | unchanged authority; CDM proposal versions (submitted, approved by the venue, `Cancelled` correction) and one fill workflow per fill mirror it |
| Trades | `trades` (trade-processor) | unchanged authority; one CDM `TradeState` per trade mirrors it, produced by the execution `BusinessEvent` of an accepted fill or direct-trade step |
| Positions | `positions` (trade-processor) | unchanged authority; CDM `PortfolioState` derived on read |

## Entity Changes

### Added

- `cdm_documents`: versioned store of CDM objects (one row per version). Key columns: `id` (surrogate,
  write order), `global_key` (SHA-256 of canonical JSON), `cdm_ref` (`cdm:<kind>:<key>`), `cdm_type`,
  `payload` (Rosetta JSON), `previous_global_key` (the step a row follows in its workflow), validation
  status and messages, and `produced_by_ref`/`produced_by_global_key` (on a `TradeState`: the accepted
  step that produced it).
- `cdm_pending_trades`, `cdm_pending_cancels`: events waiting for their prerequisites (the order's
  approved proposal, its fills).
- `cdm_lifecycle_failures`: events that could not be fully turned into stored CDM, for the CDM view.

What is stored under each `cdm_ref` kind:

| Ref | CDM types stored (versions) |
|---|---|
| `cdm:party:<accountId>`, `cdm:party:traderx-matching-engine` | `Party` |
| `cdm:person:<logonId>` | `NaturalPerson` |
| `cdm:security:<ticker>` | `Security` |
| `cdm:order:<orderId>` | `WorkflowStep`s: the proposal, its approved version, and the `Cancelled` correction (the order's own versions); plus each fill's proposed and accepted steps (separate workflows, linked to the order by the order id in their identifiers) |
| `cdm:order:<rejectionId>` | `WorkflowStep`: proposed, rejected (with the venue's `approval` `approved: false`) |
| `cdm:trade:<tradeId>` | the `TradeState` only, with its "produced by" reference |
| `cdm:direct:<tradeId>` | `WorkflowStep`s of a direct trade: proposed, accepted |
| `cdm:position:...` | nothing stored: the `PortfolioState` is derived on read |

### Changed

- `trades.orderid VARCHAR(32) NULL`: the matcher order a fill belongs to (null for direct trades).
- `orderbook.canceledquantity INTEGER NULL`: the remainder at cancel.
- `cdm_documents.produced_by_ref`, `cdm_documents.produced_by_global_key` (fill-instruction amendment):
  a volume without them is refused by the start script; `--reset-data` rebuilds it.
- Payload fields (all optional): `cdmRef` on Account, AccountUser, Person, Stock, OrderResponse, Trade
  (trade-processor) and Position; `orderId` on TradeOrder and Trade; `filledQuantity` and
  `canceledQuantity` on OrderResponse.

### Removed

- None.

## Compatibility Notes

- The schema is created when the data volume is first initialised. A volume from 009 or an older 017
  build lacks the new tables (or, before the fill-instruction amendment, the `produced_by_*` columns);
  the start script detects it and asks for `--reset-data`. Data is not migrated (Ruling R57).
- Upgrading `cdm-java` can change serialised CDM content and therefore the store keys; the masters then
  write new versions on the next start.

## Traceability

- Parties, persons, securities: FR-01701 to FR-01707.
- Lifecycle objects and `trades.orderid`, `orderbook.canceledquantity`: FR-01708 to FR-01716.
- `cdmRef` fields and round trips: FR-01718, FR-01719. Positions: FR-01720.
- Store idempotency and failure tables: NFR-01701 to NFR-01703.
