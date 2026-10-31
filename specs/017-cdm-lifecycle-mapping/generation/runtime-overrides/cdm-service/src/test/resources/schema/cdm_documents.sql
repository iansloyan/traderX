-- Test copy of the cdm_documents DDL. It must stay identical to the CREATE statements that
-- postgres-database-replacement/postgres-init/initialSchema.sql applies at runtime (captured in
-- the state 017 patch). Hibernate validates the entity against it (ddl-auto=validate).
CREATE TABLE cdm_documents (
  id BIGSERIAL PRIMARY KEY,
  global_key VARCHAR(128) NOT NULL,
  cdm_ref VARCHAR(160) NOT NULL,
  cdm_type VARCHAR(128) NOT NULL,
  payload TEXT NOT NULL,
  previous_global_key VARCHAR(128) NULL,
  validation_status VARCHAR(16) NOT NULL,
  validation_messages TEXT NULL,
  created_at TIMESTAMP NOT NULL,
  produced_by_ref VARCHAR(160) NULL,
  produced_by_global_key VARCHAR(128) NULL
);
CREATE INDEX idx_cdm_documents_global_key ON cdm_documents(global_key);
CREATE INDEX idx_cdm_documents_ref ON cdm_documents(cdm_ref);
