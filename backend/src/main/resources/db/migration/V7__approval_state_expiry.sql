-- Agent guardrails, review round 2, A1 (backend/REVIEW.md): an order awaiting approval that then expires must
-- not keep approval_state = PENDING forever — it stayed in the owner's queue, approveAgentOrder still accepted
-- it, and recordPayment refused a late payment with "waiting for approval" instead of following R3/R11.
-- CustomerOrder.cancel() now reconciles approval_state to EXPIRED or REJECTED whenever it cancels an order that
-- was still PENDING; this extends V6's CHECK to allow EXPIRED. V6 is not edited (checksum).

DO $$
DECLARE
    old_check TEXT;
BEGIN
    SELECT conname INTO old_check
    FROM pg_constraint
    WHERE conrelid = 'orders'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%approval_state%';
    IF old_check IS NOT NULL THEN
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', old_check);
    END IF;
END $$;

ALTER TABLE orders ADD CONSTRAINT chk_orders_approval_state
    CHECK (approval_state IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'));
