-- Test copy of the 009 account tables that cdm-service's party master seeds from (same columns
-- as postgres-database-replacement/postgres-init/initialSchema.sql), plus accounts_seq, from
-- which new account ids are drawn. The rows (2 accounts, 3 account users) are a small subset of the 009 seed.
CREATE TABLE accounts (
  id INTEGER PRIMARY KEY,
  displayname VARCHAR(50)
);
CREATE TABLE accountusers (
  accountid INTEGER NOT NULL,
  username VARCHAR(15) NOT NULL,
  PRIMARY KEY (accountid, username),
  FOREIGN KEY (accountid) REFERENCES accounts(id)
);
CREATE SEQUENCE accounts_seq START WITH 65000 INCREMENT BY 1;

INSERT INTO accounts (id, displayname) VALUES (22214, 'Test Account 20');
INSERT INTO accounts (id, displayname) VALUES (52355, 'Big Corporate Fund');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user01');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user03');
INSERT INTO accountusers (accountid, username) VALUES (52355, 'user09');
