# Tasks: 017-cdm-lifecycle-mapping

Each task was reviewed before the next started; rulings that changed the plan are recorded in `research.md` and
`contracts/cdm-mapping.md`.

- [x] T01701 Scaffold the state pack, catalog entry (lineage role `optional`), generation hook, render
  script and patch-capture guard; bootstrap the `cdm-lifecycle-mapping/` compose directory from 009.
- [x] T01702 cdm-service skeleton on `cdm-java` 7.8.0 (Guice 6 pin, separate Jackson 2 CDM mapper,
  `/health`).
- [x] T01703 Versioned CDM document store (`cdm_documents`: surrogate id, SHA-256 store key, per-ref
  write lock, idempotent `put`/`putNext`) (FR-01721, NFR-01702).
- [x] T01704 CDM security master and demo ISIN/FIGI scheme; reference-data serves projections with
  `cdmRef` (FR-01706, FR-01707).
- [x] T01705 CDM party master (accounts, users, venue) owning the FK-anchor rows; account-service
  projects from CDM (FR-01701, FR-01703, FR-01704).
- [x] T01706 people-service projects persons from the CDM person master; identifier-only persons stay
  out of the directory (FR-01702, FR-01705).
- [x] T01707 Link fills to orders (`orderId`), publish `/trades/booked` and `/orders/rejected`
  (FR-01709, FR-01713).
- [x] T01708 CDM validation (type, cardinality, data rules) and `Qualify_Execution`; re-validate stored
  masters (NFR-01705).
- [x] T01709 Workflow and execution mappers with golden examples in `contracts/examples/`; fills as
  chained execution steps (FR-01708 to FR-01716).
- [x] T01710 Lifecycle adapter: NATS → CDM, parking, recovery from the matcher, failure recording,
  `cdm.*` publishing; matcher `filledQuantity`/`canceledQuantity` (FR-01710, FR-01723, NFR-01701 to
  NFR-01703).
- [x] T01711 Read APIs: resolve, order lineage, trade, positions as `PortfolioState`, position
  projection; seed-trade backfill (FR-01720, FR-01721).
- [x] T01712 Runtime wiring: compose service, ingress route, Prometheus scrape and probe, start/status/
  stop scripts with `--reset-data` and stale-volume detection; matcher seeds `NEW` orders and
  cdm-service backfills open orders (FR-01724, FR-01725, NFR-01704, NFR-01709).
- [x] T01713 Scenario tests (a)–(g) in `scripts/test-state-017-cdm-lifecycle-mapping.sh`; partial
  force-fill; trade state mapped into CDM; trade lineage and diagnostics endpoints (FR-01717, FR-01719).
- [x] T01714 CDM view in the TraderX UI (`/cdm`, order and trade lineage, blotter links) (FR-01722).
- [x] T01714b `EXECUTED` position state for all live trades; backfill of order fills missed while
  cdm-service was down (FR-01715, FR-01725).
- [x] T01715 Mapping specification, contract delta, state docs, OpenAPI copies for the API explorer; quality gates; clean-clone reproducibility check.
- [x] T01718 Learning contract (`learning-contract.json`) for the incremental learning journey,
  replacing the state-owned learning guide; phased CDM adoption path and further CDM states (mapping section 13).
- [ ] T01716 (On merge) Publish the generated snapshot branch and GHCR images. Proposing `cdmRef` on
  the baseline contracts is left to a further state.
- [ ] T01719 (Further states on 017) More comprehensive use of CDM: other products, other lifecycles,
  CDM deeper in the operational flow; see `contracts/cdm-mapping.md` section 13.1.
- [ ] T01717 (Follow-on state on 014) FDC3 contexts carrying CDM identifiers and objects.
