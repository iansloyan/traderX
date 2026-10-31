-- Test copy of the 009 trades table that cdm-service's seed-trade backfill reads (Ruling R28),
-- with the same columns and checks as postgres-database-replacement/postgres-init/initialSchema.sql.
-- Empty: the backfill tests insert (and remove) their own rows.
CREATE TABLE trades (
  id VARCHAR(50) PRIMARY KEY,
  accountid INTEGER REFERENCES accounts(id),
  created TIMESTAMP,
  updated TIMESTAMP,
  security VARCHAR(15),
  side VARCHAR(10) CHECK (side in ('Buy', 'Sell')),
  quantity INTEGER CHECK (quantity > 0),
  price DECIMAL(18,3),
  state VARCHAR(20) CHECK (state in ('New', 'Processing', 'Settled', 'Cancelled')),
  orderid VARCHAR(32) NULL
);
