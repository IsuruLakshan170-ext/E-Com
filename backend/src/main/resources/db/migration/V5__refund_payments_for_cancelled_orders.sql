-- R11 (Isuru's decision): a payment that arrives for an order cancelled by the customer or staff (or a legacy
-- cancellation without a reason) is recorded and refunded in full in the same transaction. The order stays
-- CANCELLED. Extends the allowed payment outcomes; V4 is not edited.

-- V4 declared the outcome CHECK inline, so Postgres named it automatically; drop it by definition, not by a
-- guessed name. (The other CHECKs on payments are about amount_minor and method.)
DO $$
DECLARE
    old_check TEXT;
BEGIN
    SELECT conname INTO old_check
    FROM pg_constraint
    WHERE conrelid = 'payments'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%outcome%';
    IF old_check IS NOT NULL THEN
        EXECUTE format('ALTER TABLE payments DROP CONSTRAINT %I', old_check);
    END IF;
END $$;

ALTER TABLE payments ADD CONSTRAINT chk_payments_outcome CHECK (outcome IN
    ('APPLIED', 'APPLIED_AFTER_REINSTATEMENT', 'REFUNDED_ORDER_EXPIRED', 'REFUNDED_ORDER_CANCELLED'));
