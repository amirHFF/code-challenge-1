CREATE TABLE IF NOT EXISTS accounts (
                                        account_id VARCHAR(255) PRIMARY KEY,
    balance    BIGINT NOT NULL CHECK (balance >= 0),     -- last line of defence for the "no negative balance" invariant
    version    BIGINT NOT NULL DEFAULT 0                 -- bumped on every change; versions the Redis write-through entry
    );

-- for databases created before the version column existed
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS transactions (
                                            transaction_id  VARCHAR(255) PRIMARY KEY,            -- the unique constraint IS the idempotency guarantee
    fingerprint     TEXT        NOT NULL,
    status          VARCHAR(32) NOT NULL,                -- SUCCESS | INSUFFICIENT_FUNDS | BALANCE_OVERFLOW
    failure_message TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
    );
