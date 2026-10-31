-- Test copy of the lifecycle adapter tables (Task 10). It must stay identical to the CREATE
-- statements that postgres-database-replacement/postgres-init/initialSchema.sql applies at
-- runtime (captured in the state 017 patch). Hibernate validates the entities against it.
CREATE TABLE cdm_pending_trades (
  trade_id VARCHAR(100) PRIMARY KEY,
  order_id VARCHAR(100) NOT NULL,
  payload TEXT NOT NULL,
  received_at TIMESTAMP NOT NULL,
  reported_at TIMESTAMP NULL
);
CREATE INDEX idx_cdm_pending_trades_order ON cdm_pending_trades(order_id);
CREATE TABLE cdm_pending_cancels (
  order_id VARCHAR(100) PRIMARY KEY,
  payload TEXT NOT NULL,
  received_at TIMESTAMP NOT NULL,
  reported_at TIMESTAMP NULL
);
CREATE TABLE cdm_lifecycle_failures (
  id BIGSERIAL PRIMARY KEY,
  cdm_ref VARCHAR(160) NULL,
  stage VARCHAR(32) NOT NULL,
  message TEXT NOT NULL,
  payload TEXT NULL,
  failed_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_cdm_lifecycle_failures_ref ON cdm_lifecycle_failures(cdm_ref);
