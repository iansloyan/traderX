# Design: CDM-Native Lifecycle, Party and Security Model for TraderX

- Date: 2026-10-29
- Origin: [finos/traderX#379 — Hack Day: CDM-Aligned Trade/Order Lifecycle Mapping](https://github.com/finos/traderX/issues/379)
- Proposed state pack: `specs/017-cdm-lifecycle-mapping` (parent `009-order-management-matcher`)
- Status: Design — source-of-truth split approved; awaiting full doc review

## 1. Intent and Why

TraderX models trades, orders, accounts and securities with simple, TraderX-specific shapes (a ticker
string, an integer `accountId`, an `Order` with a status enum). Issue #379 asks for alignment with the
FINOS Common Domain Model (CDM) to reduce ambiguity and improve ecosystem interoperability.

This design goes further than a translation layer: **CDM becomes the system of record underneath
TraderX** for parties, securities and the order/trade lifecycle, and the simple shapes that existing
services expect become **lossless, keyed projections** of the CDM records. CDM's reference and lineage
features are what make the projections lossless — every simple value carries a key back to its CDM
source, and every lifecycle event references the one before it.

Delivery sequence:

1. Build it as a new, fully reproducible TraderX state on a fork, so **anyone who clones the fork can
   generate, start and test it** with the same script conventions as every other state.
2. Use that as the community demonstration (Hack Day / FINOS community).
3. Only then propose upstream PRs — including any minor changes to existing TraderX payloads (e.g. the
   optional reference field) that the enhancement needs.

## 2. Goals

- G1. CDM `Party` is the party model: accounts, people (traders) and the execution venue are CDM records.
- G2. CDM `Security` is the instrument model, with multiple identifiers (ticker, ISIN, FIGI at minimum).
- G3. Orders are CDM `ExecutionInstruction`s carried through CDM workflow (`WorkflowStep`) states:
  proposed → accepted / rejected, with fills as partial executions and cancel as a workflow action.
- G4. Fills and direct trades produce CDM `BusinessEvent`s and `TradeState`s with a CDM-6-style cash
  security product (`SettlementPayout` on a `Security`, price/quantity in the trade lot — verify against
  the pinned CDM release).
- G5. Every simple projection served to existing services carries a CDM reference so nothing is lost; a
  round trip simple → CDM (via key) → simple is the identity.
- G6. Full lineage is navigable: trade state → executions → accepted instruction → proposal → parties and
  security.
- G7. Reproducible by anyone from a fork: generate/start/status/stop/test scripts, seeded data, smoke and
  validation tests, quickstart and learning guide.
- G8. Designed for a follow-on state on top of `014-fdc3-intent-interoperability` that publishes CDM
  identifiers and objects through FDC3 (not built in this pass).

## 3. Non-Goals

- Not modelling the TraderX matcher's auto-fill policy (half-fill rule, tick cadence) in CDM — it is
  venue behaviour, documented as such.
- Not settlement, clearing, confirmations, allocations, or regulatory reporting events.
- Not real reference data: ISINs/FIGIs are seeded demo values (clearly marked), not a licensed feed.
- Not the FDC3/Kubernetes integration (that is the follow-on state, G8).
- Not changing how state `009` and earlier behave.

## 4. Anti-Goals (failure even if it "works")

- A projection that silently drops data — any field not carried in a simple shape must be recoverable via
  its CDM reference, and any intentional omission must be listed in the spec.
- Breaking existing APIs, topics or UI flows, or breaking earlier states.
- Hand-rolled "CDM-looking" JSON that does not validate against the real CDM model/schema.
- A demo that only runs on the author's machine.
- Hand-editing generated output instead of following the spec-first, patch-set generation model.

## 5. Constraints

- Follow `AGENTS.md` and the SpecKit state-pack contract: `spec.md` (FR/NFR + scenarios), `plan.md`,
  `tasks.md`, `requirements/*-delta.md`, `contracts/contract-delta.md`, `data-model.md`, `research.md`,
  `quickstart.md`, `system/**` (architecture model + runtime topology), `generation/generation-hook.md`
  with `patches/0001-state-overlay.patch`, `tests/smoke/README.md`, catalog entry and learning doc with
  required front-matter. Pass the repo quality gates (`validate-frontmatter`, SpecKit gates, spec
  coverage, learning parity).
- Patch-set overlay model: generate parent `009`, apply this pack's patches, capture with
  `pipeline/create-state-patchset.sh 017-cdm-lifecycle-mapping 009-order-management-matcher`.
- Runtime is Docker Compose (inherited from `009`); prerequisites must not grow beyond Docker +
  `docker compose` (plus whatever the inherited state already needs).
- Additive only: existing REST payloads, NATS subjects and UI paths keep working unchanged. New fields
  on existing payloads must be optional.
- Uses the existing stack: Java 21 / Spring Boot for new backend code (versions per
  `catalog/dependency-version-targets.json`), Postgres (from `005`), NATS (from `006`), Angular UI,
  observability conventions from `007`/`009`.
- State ID `017` (015 is taken by the docs portal pack). Builder confirms numbering convention.

## 6. Decided Design

### 6.1 Architecture shape

- **CDM store**: CDM objects persisted as JSON in Postgres (one table per CDM root type or a generic
  keyed document table), indexed by CDM `globalKey` and by TraderX simple ID.
- **CDM service** (new Java/Spring Boot component using FINOS `cdm-java`): owns the CDM store; serves
  full CDM objects and lineage; resolves simple IDs ↔ CDM references; validates objects with CDM's
  validators.
- **Lifecycle adapter** (inside the CDM service, or its own component — builder's call): subscribes to
  the existing order and trade events (order topics published by the matcher, `trades.*`), builds the
  CDM workflow steps / business events / trade states, persists them, and publishes CDM payloads on new
  NATS subjects (e.g. a `cdm.*` namespace). Existing subjects are untouched.
- **Projections**: existing services (account-service, people-service, reference-data, trade/order/
  position APIs) continue to serve their simple shapes, now derived from the CDM records, each with an
  added optional reference field (working name `cdmRef`).

### 6.1a Source of truth (decided by the owner)

- **Parties and securities: CDM is authoritative.** The CDM service owns party, person and security
  records. account-service, people-service and reference-data serve their existing simple shapes as
  projections read from the CDM service instead of their own tables/CSV; creating accounts or adding
  users to accounts goes through the CDM service. Seed data initialises it on startup.
- **Orders and trades: existing tables stay the operational record.** The CDM lifecycle (workflow steps,
  business events, trade states) is built from events shortly after, so a CDM failure never blocks
  trading. CDM keys are derived deterministically from TraderX IDs (e.g. `externalKey` = order/trade ID),
  so a `cdmRef` returned in a synchronous response is valid immediately and resolves once the adapter has
  processed the event. CDM validation failures are logged, counted in metrics and surfaced in the CDM
  view — they do not reject the TraderX operation.
- Making CDM authoritative for orders and trades too is out of scope for this state.

### 6.2 Party model (CDM `Party` underneath)

- Each TraderX account → a CDM `Party` (party identifier with a TraderX scheme, name). It takes the
  buyer/seller (and payer/receiver) role on instructions and trades according to side.
- Each person in people-service → a CDM `NaturalPerson` related to the account party (e.g. trader role),
  mirroring today's account-user entitlements.
- The order matcher → a CDM party representing the execution venue ("TraderX Matching Engine"), used as
  the counterparty on fills and as the execution venue in `ExecutionDetails`.
- Simple projections keep today's shapes (e.g. account `{id, displayName}`, person `{logonId, fullName}`)
  plus `cdmRef`.

### 6.3 Security model (CDM `Security` underneath)

- Each ticker in reference data → a CDM `Security` (`securityType = Equity`) with identifiers: ticker,
  ISIN, FIGI (seeded demo values derived from existing reference data, marked as demo data), plus
  name/exchange where available.
- Ticker-only services keep receiving the ticker; the projection carries `cdmRef`.
- This is the groundwork for retiring `TD-01402` (ticker-centric symbology) in the FDC3 follow-on: the
  standard `fdc3.instrument` `id` map already accepts ticker/ISIN/FIGI.

### 6.4 Order → CDM workflow mapping

| TraderX event | CDM representation |
|---|---|
| Order submitted | `WorkflowStep` with a proposed event carrying an `ExecutionInstruction` (security, quantity, limit price, buyer/seller from side, account party) |
| Order accepted (`NEW`) | `WorkflowStep` accepting the proposal, referencing the previous step |
| Order rejected (`REJECTED`) | `WorkflowStep` rejecting the proposal, with reason |
| Partial fill (`PARTIALLY_FILLED`) | `BusinessEvent` with an execution for the fill quantity at the fill price against the accepted instruction → new `TradeState` per fill |
| Final fill (`FILLED`) | Last execution; cumulative executed quantity = instruction quantity |
| Cancel (`CANCELED`) | `WorkflowStep` with cancel action on the open remainder; prior executions untouched |
| Direct trade (trade ticket, no order) | Instruction proposed + accepted + executed in full in one flow — same output shape as the order path |

- Remaining quantity is derived (instruction quantity − Σ executions), never stored independently in CDM.
- The cancel mapping is the least certain; research must confirm the idiomatic CDM 6 representation and
  record the decision in `research.md`.

### 6.5 Trade / position representation

- Each execution produces a CDM `Trade`/`TradeState` with a cash-security product (`SettlementPayout`
  on the `Security`), trade lot price/quantity (shares; price per share in currency), counterparties
  (account party vs venue party), and `ExecutionDetails` (venue, execution type).
- Positions: an aggregate CDM view per account+security derived from trade states (exact CDM type —
  e.g. portfolio/position state — chosen during research). The existing position-service shape is a
  projection with `cdmRef`.

### 6.6 Lineage and lossless projections

- Every CDM object has a `globalKey` and an `externalKey` (the TraderX ID where one exists).
- Workflow steps reference their previous step; business events reference the `before` trade state;
  trade states reference the instruction/execution they came from.
- `cdmRef` on every projection resolves to the full CDM object through the CDM service.
- Round-trip tests: for every projection, simple → resolve `cdmRef` → re-project = original simple
  shape. The spec lists, per projection, which CDM fields are not carried in the simple shape.

### 6.7 What a fork user sees and runs

- `bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping` then
  `./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh` (plus status/stop/test scripts).
- Seed data loads on startup: CDM parties and securities derived from existing reference data.
- A **CDM view** in the TraderX UI: select an order or trade to see its CDM JSON and its lineage chain
  (proposal → acceptance → fills → trade states) with parties and security resolved.
- CDM REST endpoints for raw JSON (e.g. `/cdm/trades/{id}`, `/cdm/orders/{id}/lineage`,
  `/cdm/parties/{id}`, `/cdm/securities/{id}` — final paths are the builder's call, documented in
  `contracts/contract-delta.md`).
- Test script runs the sample scenarios (direct trade; order partial → full fill; order partial → cancel;
  rejected order), validates output with CDM validators, and runs the round-trip checks.
- Learning guide in `docs/learning/` (with front-matter) explaining the mapping, lineage and gaps; it
  doubles as the community write-up.

### 6.8 Mapping spec deliverable

`specs/017-cdm-lifecycle-mapping/contracts/cdm-mapping.md`: every lifecycle event, party and security
mapping with a full CDM JSON example, translation rules, and a **semantic gaps** section. Known gaps to
start from:

- Matcher auto-fill policy is venue behaviour, not CDM semantics.
- Seeded ISIN/FIGI values are demo data.
- Cancel-of-remainder representation (pending research).
- TraderX has no settlement/currency detail today — defaults (e.g. a single currency, T+N settlement
  date) must be stated explicitly in the spec.

### 6.9 Migration strategy (non-breaking adoption)

The original design called this phase 0 (this state), phase 1 (upstream PR) and phase 2 (FDC3).
It is superseded by
[`contracts/cdm-mapping.md` section 13](../contracts/cdm-mapping.md#13-phased-cdm-adoption): 017
demonstrates a phased CDM adoption path, and further states (other products, other lifecycles, CDM
deeper in the operational flow, FDC3 on `014`) can build on it.

## 7. Left to the Builder

- Pinned CDM / `cdm-java` version (latest stable compatible with Java 21 and the repo's Spring Boot) and
  confirming CDM 6+ type names used above (`SettlementPayout`, `ExecutionInstruction`, workflow
  actions, position type).
- Whether the lifecycle adapter is part of the CDM service or a separate component.
- Postgres table design for the CDM store.
- Exact REST paths, NATS subject names, and the `cdmRef` field format (key vs URL-like reference).
- How projections are derived in each existing service (call CDM service vs shared library vs DB view).
- UI design of the CDM view, following existing Angular patterns.
- Observability additions (metrics for CDM events built/validated/failed), following `009` conventions.
- Demo ISIN/FIGI seed values and their source/marking.

## 8. Calls Made by Claude (not confirmed by the owner)

- State ID `017-cdm-lifecycle-mapping`, parent `009`.
- Java/Spring Boot + `cdm-java`; CDM JSON in Postgres; new `cdm.*` NATS subjects.
- A UI "CDM view" as the primary visible demo surface.
- The matcher modelled as a venue party acting as counterparty.
- Direct trades modelled as instruction-proposed-accepted-executed in one flow.
- **Fill ↔ trade linkage**: in `009` the matcher posts `{security, quantity, accountId, side}` to
  trade-service with no order reference, so fills and direct trades are indistinguishable downstream.
  State `017` adds an optional `orderId` to the trade submission and trade record/event (the owner allowed
  minor TraderX changes, to be proposed upstream later). The adapter builds fill events from the order
  path and skips the "direct trade" flow for trades carrying an `orderId`, so nothing is double-counted.
- **Execution price**: the CDM trade lot price is the price trade-service books (the `TradeState` must match
  the booked trade). The matcher's `lastExecutionPrice` is kept on the execution as the observed market
  price; any difference is listed as a semantic gap.
- **Rejected orders**: `009` never persists a `REJECTED` order (invalid creates return 400). State `017`
  records a rejected `WorkflowStep` for create requests that fail validation, via a hook in the order-create
  path, so the rejected scenario is real. Proposed + accepted are both emitted from one TraderX create —
  TraderX accepts synchronously.
- **Validation bar**: CDM objects must pass cdm-java type/cardinality validation and data-rule conditions,
  and events/products must qualify (Execution event, cash-equity product). Events are built with CDM's own
  functions where they exist rather than assembled by hand. A qualification that cannot be met is a
  documented gap, not a silent pass.
- **Publishing scope**: the fork demo is run-from-source (generate + start scripts). Inherited GHCR image
  publishing, deploy bundles and snapshot branches are not required for the fork demo; they come back in
  scope with the upstream PR.
