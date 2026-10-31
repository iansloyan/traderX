# Non-Functional Delta: 017-cdm-lifecycle-mapping

Parent state: `009-order-management-matcher`

Requirement ids refer to `spec.md`.

## Runtime / Operations

- One new container, `cdm-service` (port 18120, ingress `/cdm-service/`), on the shared Postgres and NATS; compose project `traderx-state-017`, data volume `traderx-state-017_postgres_state_017_data` (NFR-01708).
- account-service, people-service and reference-data start only after cdm-service is healthy; `/health` is 200 only once the CDM masters are seeded (NFR-01704).
- `--reset-data` on the start script recreates the data volume; a volume from an older build is detected and the script asks for it.
- Same host ports as 009, so only one of the two runs at a time.

## Security / Compliance

- No new secrets. ISIN and FIGI values are synthetic demo identifiers, marked by scheme and in the UI, never presented as real reference data.

## Performance / Scalability

- The CDM lifecycle is built asynchronously from NATS; order and trade latency is unchanged (NFR-01701).
- cdm-service assumes a single instance per state (in-process per-ref write lock).
- Positions are derived on read from the account's trade states.

## Reliability / Observability

- Failures in the CDM lifecycle mirroring never block orders or trades; they are logged, counted and stored for the CDM view (NFR-01701). Master data is different: accounts, people and reference data depend on cdm-service being available (gap G8 in `contracts/cdm-mapping.md`).
- Event handling is idempotent and order-tolerant, with recovery from the matcher and a periodic sweep of parked events (NFR-01702, NFR-01703).
- New metrics `traderx_cdm_objects_total{type,validation}`, `traderx_cdm_failures_total{stage}`, `traderx_cdm_pending_trades`, `traderx_cdm_pending_cancels`; Prometheus scrape and blackbox probe for cdm-service (NFR-01709).
