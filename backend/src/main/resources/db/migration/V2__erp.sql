-- Phase 2: ERP back office (staff roles, suppliers, purchasing, inventory ledger, tax,
-- payments/invoices, returns/refunds, general ledger). Money in minor units (BIGINT).

-- 1. Staff roles ---------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN
    ('CUSTOMER', 'CUSTOMER_SERVICE', 'INVENTORY_MANAGER', 'PURCHASING_MANAGER', 'FINANCE_MANAGER', 'ADMIN'));

-- 2. Suppliers -----------------------------------------------------------------
CREATE TABLE suppliers (
    id                 UUID PRIMARY KEY,
    code               VARCHAR(32)  NOT NULL UNIQUE,
    name               VARCHAR(200) NOT NULL,
    email              VARCHAR(254),
    phone              VARCHAR(40),
    address            VARCHAR(500),
    payment_terms_days INTEGER      NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);

-- 3/4. Product costing and replenishment --------------------------------------
ALTER TABLE products ADD COLUMN cost_minor            BIGINT  NOT NULL DEFAULT 0 CHECK (cost_minor >= 0);
ALTER TABLE products ADD COLUMN reorder_point         INTEGER NOT NULL DEFAULT 0 CHECK (reorder_point >= 0);
ALTER TABLE products ADD COLUMN reorder_quantity      INTEGER NOT NULL DEFAULT 0 CHECK (reorder_quantity >= 0);
ALTER TABLE products ADD COLUMN preferred_supplier_id UUID REFERENCES suppliers (id);

-- 3. Stock ledger --------------------------------------------------------------
CREATE TABLE stock_movements (
    id              UUID PRIMARY KEY,
    product_id      UUID        NOT NULL REFERENCES products (id),
    type            VARCHAR(30) NOT NULL CHECK (type IN
        ('OPENING', 'ADJUSTMENT', 'SALE', 'SALE_CANCELLED', 'PURCHASE_RECEIPT', 'CUSTOMER_RETURN')),
    quantity        INTEGER     NOT NULL CHECK (quantity <> 0),
    unit_cost_minor BIGINT      NOT NULL CHECK (unit_cost_minor >= 0),
    stock_after     INTEGER     NOT NULL CHECK (stock_after >= 0),
    reference_type  VARCHAR(50),
    reference_id    UUID,
    note            VARCHAR(300),
    actor_id        UUID,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_stock_movements_product ON stock_movements (product_id, created_at DESC);
CREATE INDEX idx_stock_movements_created ON stock_movements (created_at DESC);

-- 5. Purchase orders -----------------------------------------------------------
CREATE TABLE purchase_orders (
    id            UUID PRIMARY KEY,
    po_number     VARCHAR(32)   NOT NULL UNIQUE,
    supplier_id   UUID          NOT NULL REFERENCES suppliers (id),
    status        VARCHAR(20)   NOT NULL CHECK (status IN
        ('DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    currency      VARCHAR(3)    NOT NULL,
    total_minor   BIGINT        NOT NULL CHECK (total_minor >= 0),
    expected_date DATE,
    notes         VARCHAR(1000),
    created_by    UUID          NOT NULL REFERENCES users (id),
    approved_by   UUID REFERENCES users (id),
    approved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    -- segregation of duties, enforced in the database too
    CONSTRAINT chk_po_approver_differs CHECK (approved_by IS NULL OR approved_by <> created_by)
);
CREATE INDEX idx_purchase_orders_status ON purchase_orders (status);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id);

CREATE TABLE purchase_order_lines (
    id                UUID PRIMARY KEY,
    purchase_order_id UUID    NOT NULL REFERENCES purchase_orders (id) ON DELETE CASCADE,
    line_no           INTEGER NOT NULL,
    product_id        UUID    NOT NULL REFERENCES products (id),
    quantity_ordered  INTEGER NOT NULL CHECK (quantity_ordered > 0),
    quantity_received INTEGER NOT NULL DEFAULT 0 CHECK (quantity_received >= 0),
    unit_cost_minor   BIGINT  NOT NULL CHECK (unit_cost_minor >= 0),
    line_total_minor  BIGINT  NOT NULL CHECK (line_total_minor >= 0),
    CONSTRAINT chk_pol_no_over_receipt CHECK (quantity_received <= quantity_ordered)
);
CREATE INDEX idx_po_lines_po ON purchase_order_lines (purchase_order_id);
CREATE INDEX idx_po_lines_product ON purchase_order_lines (product_id);

-- 6. Goods receipts ------------------------------------------------------------
CREATE TABLE goods_receipts (
    id                UUID PRIMARY KEY,
    receipt_number    VARCHAR(32)  NOT NULL UNIQUE,
    purchase_order_id UUID         NOT NULL REFERENCES purchase_orders (id),
    idempotency_key   VARCHAR(100) NOT NULL,
    note              VARCHAR(500),
    received_by       UUID         NOT NULL REFERENCES users (id),
    received_at       TIMESTAMPTZ  NOT NULL,
    total_cost_minor  BIGINT       NOT NULL CHECK (total_cost_minor >= 0),
    UNIQUE (received_by, idempotency_key)
);
CREATE INDEX idx_goods_receipts_po ON goods_receipts (purchase_order_id);

CREATE TABLE goods_receipt_lines (
    id              UUID PRIMARY KEY,
    receipt_id      UUID    NOT NULL REFERENCES goods_receipts (id) ON DELETE CASCADE,
    product_id      UUID    NOT NULL REFERENCES products (id),
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    unit_cost_minor BIGINT  NOT NULL CHECK (unit_cost_minor >= 0)
);

-- 7. Tax -----------------------------------------------------------------------
CREATE TABLE tax_rates (
    id                UUID PRIMARY KEY,
    country_code      VARCHAR(2)   NOT NULL UNIQUE,
    name              VARCHAR(100) NOT NULL,
    rate_basis_points INTEGER      NOT NULL CHECK (rate_basis_points BETWEEN 0 AND 5000),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

ALTER TABLE orders ADD COLUMN tax_rate_bp        INTEGER NOT NULL DEFAULT 0 CHECK (tax_rate_bp >= 0);
ALTER TABLE orders ADD COLUMN tax_minor          BIGINT  NOT NULL DEFAULT 0 CHECK (tax_minor >= 0);
ALTER TABLE orders ADD COLUMN refunded_minor     BIGINT  NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0);
ALTER TABLE orders ADD COLUMN refunded_tax_minor BIGINT  NOT NULL DEFAULT 0 CHECK (refunded_tax_minor >= 0);
ALTER TABLE orders ADD COLUMN paid_at            TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN shipped_at         TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN delivered_at       TIMESTAMPTZ;
ALTER TABLE orders ADD CONSTRAINT chk_orders_refund_cap CHECK (refunded_minor <= total_minor);
ALTER TABLE order_items ADD COLUMN unit_cost_minor BIGINT NOT NULL DEFAULT 0 CHECK (unit_cost_minor >= 0);

-- 8. Payments and invoices -----------------------------------------------------
CREATE SEQUENCE invoice_number_seq START 1;
CREATE SEQUENCE credit_note_number_seq START 1;
CREATE SEQUENCE journal_entry_number_seq START 1;

CREATE TABLE payments (
    id           UUID PRIMARY KEY,
    order_id     UUID        NOT NULL UNIQUE REFERENCES orders (id),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     VARCHAR(3)  NOT NULL,
    method       VARCHAR(20) NOT NULL CHECK (method IN ('CARD', 'BANK_TRANSFER', 'CASH', 'OTHER')),
    reference    VARCHAR(100),
    recorded_by  UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payments_created ON payments (created_at DESC);

CREATE TABLE invoices (
    id             UUID PRIMARY KEY,
    invoice_number VARCHAR(20) NOT NULL UNIQUE,
    order_id       UUID        NOT NULL UNIQUE REFERENCES orders (id),
    user_id        UUID        NOT NULL REFERENCES users (id),
    currency       VARCHAR(3)  NOT NULL,
    subtotal_minor BIGINT      NOT NULL CHECK (subtotal_minor >= 0),
    tax_minor      BIGINT      NOT NULL CHECK (tax_minor >= 0),
    total_minor    BIGINT      NOT NULL CHECK (total_minor >= 0),
    issued_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_invoices_issued ON invoices (issued_at DESC);

-- 9. Returns and refunds -------------------------------------------------------
CREATE TABLE return_requests (
    id              UUID PRIMARY KEY,
    rma_number      VARCHAR(32)  NOT NULL UNIQUE,
    order_id        UUID         NOT NULL REFERENCES orders (id),
    user_id         UUID         NOT NULL REFERENCES users (id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'RECEIVED', 'REFUNDED')),
    reason          VARCHAR(500) NOT NULL,
    resolution_note VARCHAR(500),
    decided_by      UUID REFERENCES users (id),
    refund_id       UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_returns_order ON return_requests (order_id);
CREATE INDEX idx_returns_user ON return_requests (user_id, created_at DESC);
CREATE INDEX idx_returns_status ON return_requests (status);

CREATE TABLE return_lines (
    id               UUID PRIMARY KEY,
    return_id        UUID         NOT NULL REFERENCES return_requests (id) ON DELETE CASCADE,
    product_id       UUID         NOT NULL REFERENCES products (id),
    sku              VARCHAR(64)  NOT NULL,
    product_name     VARCHAR(200) NOT NULL,
    quantity         INTEGER      NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT       NOT NULL CHECK (unit_price_minor >= 0),
    unit_cost_minor  BIGINT       NOT NULL CHECK (unit_cost_minor >= 0)
);

CREATE TABLE refunds (
    id                 UUID PRIMARY KEY,
    credit_note_number VARCHAR(20)  NOT NULL UNIQUE,
    order_id           UUID         NOT NULL REFERENCES orders (id),
    return_id          UUID REFERENCES return_requests (id),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    tax_minor          BIGINT       NOT NULL CHECK (tax_minor >= 0 AND tax_minor <= amount_minor),
    currency           VARCHAR(3)   NOT NULL,
    reason             VARCHAR(300) NOT NULL,
    created_by         UUID REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_refunds_order ON refunds (order_id);
CREATE INDEX idx_refunds_created ON refunds (created_at DESC);
ALTER TABLE return_requests ADD CONSTRAINT fk_returns_refund FOREIGN KEY (refund_id) REFERENCES refunds (id);

-- 10. General ledger -----------------------------------------------------------
CREATE TABLE ledger_accounts (
    code VARCHAR(10)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);
INSERT INTO ledger_accounts (code, name, type) VALUES
    ('1000', 'Cash and bank',          'ASSET'),
    ('1200', 'Inventory',              'ASSET'),
    ('2000', 'Accounts payable',       'LIABILITY'),
    ('2100', 'Sales tax payable',      'LIABILITY'),
    ('3000', 'Opening balance equity', 'EQUITY'),
    ('4000', 'Sales revenue',          'REVENUE'),
    ('4100', 'Sales returns',          'REVENUE'),
    ('5000', 'Cost of goods sold',     'EXPENSE'),
    ('5100', 'Inventory adjustments',  'EXPENSE');

CREATE TABLE journal_entries (
    id           UUID PRIMARY KEY,
    entry_number VARCHAR(20)  NOT NULL UNIQUE,
    posted_at    TIMESTAMPTZ  NOT NULL,
    description  VARCHAR(300) NOT NULL,
    source_type  VARCHAR(50)  NOT NULL,
    source_id    UUID,
    created_by   UUID
);
CREATE INDEX idx_journal_entries_posted ON journal_entries (posted_at DESC);
CREATE INDEX idx_journal_entries_source ON journal_entries (source_type, source_id);

CREATE TABLE journal_lines (
    id           UUID PRIMARY KEY,
    entry_id     UUID        NOT NULL REFERENCES journal_entries (id),
    account_code VARCHAR(10) NOT NULL REFERENCES ledger_accounts (code),
    debit_minor  BIGINT      NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor BIGINT      NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    CONSTRAINT chk_journal_line_one_side CHECK ((debit_minor = 0) <> (credit_minor = 0))
);
CREATE INDEX idx_journal_lines_entry ON journal_lines (entry_id);
CREATE INDEX idx_journal_lines_account ON journal_lines (account_code);

-- The ledger is append-only: block UPDATE/DELETE at the database level, not just in code.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger tables are append-only (%)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_journal_lines_immutable BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
CREATE TRIGGER trg_stock_movements_immutable BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
