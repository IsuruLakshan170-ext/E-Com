-- Agent guardrails (backend/REVIEW.md §5.3, "Spending guardrails per API key" + "Human-in-the-loop approval").
-- An agent key can hold orders:write and still not be able to run up an unbounded bill: each key carries its own
-- limits, and anything above its approval threshold waits for a human before it can be paid.
--
-- All three limits are NULL by default = no limit, so every key that already exists keeps behaving exactly as
-- before. Limits are set at creation time and never updated (the key is the unit of delegation: to change the
-- deal, revoke the key and issue another), hence no updated_at here.
ALTER TABLE api_keys
    ADD COLUMN max_order_value_minor        BIGINT CHECK (max_order_value_minor >= 0),
    ADD COLUMN max_orders_per_day           INT    CHECK (max_orders_per_day >= 0),
    ADD COLUMN require_approval_above_minor BIGINT CHECK (require_approval_above_minor >= 0);

-- Which key placed an order (NULL = placed by a human session), and where its approval stands.
-- Deliberately NOT a new order status: the order sits in PENDING_PAYMENT as usual, so expiry, cancellation,
-- stock release and the whole fulfilment state machine keep working unchanged — only payment is blocked while
-- approval is PENDING. A rejected order is cancelled through the existing cancel path.
ALTER TABLE orders
    ADD COLUMN placed_by_api_key_id UUID REFERENCES api_keys (id),
    ADD COLUMN approval_state       VARCHAR(20) CHECK (approval_state IN ('PENDING', 'APPROVED', 'REJECTED')),
    ADD COLUMN approval_decided_at  TIMESTAMPTZ,
    ADD COLUMN approval_decided_by  UUID REFERENCES users (id);

-- The human's approval queue: "my orders waiting for me", newest first.
CREATE INDEX idx_orders_awaiting_approval ON orders (user_id, created_at DESC) WHERE approval_state = 'PENDING';

-- Counting a key's orders in the last 24h for the per-day cap.
CREATE INDEX idx_orders_api_key_created ON orders (placed_by_api_key_id, created_at) WHERE placed_by_api_key_id IS NOT NULL;
