-- Phase 2.1: fixes from REVIEW.md (H2-H6, M1-M12, L1-L8).
-- No environment has applied V1/V2 with real data yet, but every step below is still data-safe.

-- H5: replace hard account lockout with per (account, IP) throttling in the app -> drop lockout columns.
-- M7: per-user token version; bumping it invalidates every access token already issued.
ALTER TABLE users DROP COLUMN failed_login_attempts;
ALTER TABLE users DROP COLUMN locked_until;
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- L6: a SYSTEM actor for scheduled jobs and (Phase 3) payment webhooks. Disabled + unusable password:
-- it can never log in or own API keys. Role CUSTOMER so it never counts as an admin.
INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at, updated_at, version, token_version)
VALUES ('00000000-0000-0000-0000-000000000001', 'system@ecom.internal', '!', 'System', 'CUSTOMER', FALSE,
        now(), now(), 0, 0);

-- M4: remember WHY a refresh token was revoked (rotation gets a short grace window; theft does not).
ALTER TABLE refresh_tokens ADD COLUMN revoke_reason VARCHAR(20);
UPDATE refresh_tokens SET revoke_reason = 'ROTATED' WHERE revoked_at IS NOT NULL;

-- H2 + M2: stock reservation model. available = stock (on hand) - reserved.
-- Checkout reserves; shipment consumes; cancel/expiry releases.
ALTER TABLE products ADD COLUMN reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0);
-- Unshipped orders placed under the old model already reduced on-hand stock: turn that into a reservation.
UPDATE products p
SET stock = p.stock + x.qty, reserved = p.reserved + x.qty
FROM (SELECT oi.product_id, SUM(oi.quantity) AS qty
      FROM order_items oi JOIN orders o ON o.id = oi.order_id
      WHERE o.status IN ('PENDING_PAYMENT', 'PAID')
      GROUP BY oi.product_id) x
WHERE p.id = x.product_id;
ALTER TABLE products ADD CONSTRAINT chk_products_reserved_le_stock CHECK (reserved <= stock);

-- H2: unpaid orders expire.
ALTER TABLE orders ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE orders SET expires_at = created_at + INTERVAL '30 minutes' WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_orders_unpaid_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_orders_user_status ON orders (user_id, status);

-- M2: revenue is deferred until shipment.
INSERT INTO ledger_accounts (code, name, type) VALUES ('2200', 'Customer deposits (deferred revenue)', 'LIABILITY');

-- M5: one idempotency table for every retry-safe write, keyed by (user, operation, key).
CREATE TABLE idempotency_records (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    operation     VARCHAR(50)  NOT NULL,
    idem_key      VARCHAR(100) NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(50)  NOT NULL,
    resource_id   UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (user_id, operation, idem_key)
);
CREATE INDEX idx_idempotency_records_created ON idempotency_records (created_at);
INSERT INTO idempotency_records (id, user_id, operation, idem_key, request_hash, resource_type, resource_id, created_at)
SELECT id, user_id, 'placeOrder', idem_key, request_hash, 'order', order_id, created_at FROM idempotency_keys;
DROP TABLE idempotency_keys;

-- H6: returns need different people for approve / receive / refund, regardless of role.
ALTER TABLE return_requests ADD COLUMN received_by UUID REFERENCES users (id);
ALTER TABLE return_requests ADD COLUMN received_at TIMESTAMPTZ;
ALTER TABLE return_requests ADD CONSTRAINT chk_return_receiver_differs
    CHECK (received_by IS NULL OR decided_by IS NULL OR received_by <> decided_by);
ALTER TABLE return_requests ADD CONSTRAINT chk_return_staff_not_customer
    CHECK ((decided_by IS NULL OR decided_by <> user_id) AND (received_by IS NULL OR received_by <> user_id));

-- M8: the audit log is append-only too.
CREATE TRIGGER trg_audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id);
CREATE INDEX idx_audit_events_api_key ON audit_events (api_key_id) WHERE api_key_id IS NOT NULL;

-- M10: support cleanup jobs.
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
