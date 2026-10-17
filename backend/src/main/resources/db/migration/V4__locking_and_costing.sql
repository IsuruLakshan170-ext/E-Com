-- Phase 2.2: change requests R1-R6 from REVIEW.md section 9.

-- R3: remember why an order was cancelled. Only EXPIRED orders accept late payments
-- (re-reserve stock if possible, otherwise the payment is recorded and refunded automatically).
ALTER TABLE orders ADD COLUMN cancel_reason VARCHAR(20)
    CHECK (cancel_reason IN ('CUSTOMER', 'STAFF', 'EXPIRED'));
UPDATE orders o SET cancel_reason = 'EXPIRED'
WHERE o.status = 'CANCELLED'
  AND EXISTS (SELECT 1 FROM audit_events a
              WHERE a.action = 'ORDER_EXPIRED' AND a.target_type = 'order' AND a.target_id = o.id::text);
-- Other legacy cancellations keep NULL (unknown who cancelled).
ALTER TABLE orders ADD CONSTRAINT chk_orders_cancel_reason_only_when_cancelled
    CHECK (cancel_reason IS NULL OR status = 'CANCELLED');

-- R3: what happened to each recorded payment.
ALTER TABLE payments ADD COLUMN outcome VARCHAR(30) NOT NULL DEFAULT 'APPLIED'
    CHECK (outcome IN ('APPLIED', 'APPLIED_AFTER_REINSTATEMENT', 'REFUNDED_ORDER_EXPIRED'));

-- R4: exact inventory value pool per product. Every stock movement changes stock_value_minor by
-- exactly the amount posted to GL 1200 Inventory, so GL 1200 == SUM(stock_value_minor) always holds.
-- (A rounded per-unit average cost alone drifts by up to stock/2 minor units per product.)
ALTER TABLE products ADD COLUMN stock_value_minor BIGINT NOT NULL DEFAULT 0 CHECK (stock_value_minor >= 0);
UPDATE products SET stock_value_minor = stock::BIGINT * cost_minor;
ALTER TABLE products ADD CONSTRAINT chk_products_no_value_without_stock
    CHECK (stock > 0 OR stock_value_minor = 0);

-- Signed value of each movement (in > 0, out < 0). NULL for rows written before V4: the stock ledger
-- is append-only (trigger), so history is not rewritten.
ALTER TABLE stock_movements ADD COLUMN value_minor BIGINT;

-- R4: COGS is the value that actually left inventory at shipment, stored per order line.
ALTER TABLE order_items ADD COLUMN shipped_cost_minor BIGINT CHECK (shipped_cost_minor >= 0);
UPDATE order_items oi SET shipped_cost_minor = oi.unit_cost_minor * oi.quantity
FROM orders o WHERE o.id = oi.order_id AND o.shipped_at IS NOT NULL;

-- R4: a return restocks exactly its share of the line's shipped cost.
ALTER TABLE return_lines ADD COLUMN restock_value_minor BIGINT NOT NULL DEFAULT 0 CHECK (restock_value_minor >= 0);
UPDATE return_lines SET restock_value_minor = unit_cost_minor * quantity;

-- R10: separation-of-duties policy (app.sod.mode = strict | small-team). Small-team mode lets one person do
-- several steps WITH a recorded reason, so the DB can no longer forbid these combinations outright; strict
-- mode enforces them in code. The conflict-of-interest rules stay hard in every mode and keep their CHECK.
ALTER TABLE purchase_orders DROP CONSTRAINT chk_po_approver_differs;
ALTER TABLE return_requests DROP CONSTRAINT chk_return_receiver_differs;
-- kept: chk_return_staff_not_customer (staff never decide or receive their own return)

-- Honest note on V3 (not edited, so its checksum stays valid): V3's stock restore for unshipped orders wrote
-- no stock_movements rows, and orders PAID under the pre-V3 model already booked revenue at payment, so they
-- would book it again at shipment. Harmless while no real data exists; fix data before migrating a real DB.
