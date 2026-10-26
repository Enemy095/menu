-- Align the persisted order contract with the frozen single-client public API.
-- The removed values were untrusted client assertions. Historical mismatches are
-- deliberately preserved through server_total_minor and needs_confirmation.

ALTER TABLE orders DROP CONSTRAINT ck_orders_totals;
ALTER TABLE orders DROP COLUMN client_total_minor;
ALTER TABLE orders DROP COLUMN client_created_at;

ALTER TABLE orders ADD CONSTRAINT ck_orders_server_total
    CHECK (server_total_minor BETWEEN 0 AND 1000000000000);
