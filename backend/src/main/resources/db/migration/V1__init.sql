-- Phase 1 schema. All money is stored in minor units (cents) as BIGINT; never floats.
-- Primary keys are random UUIDs so ids are not guessable/enumerable.

CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(100) NOT NULL,
    role                  VARCHAR(20)  NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 of the token; raw token is never stored
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE api_keys (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    prefix       VARCHAR(16)  NOT NULL,        -- first chars of the key, for display only
    key_hash     VARCHAR(64)  NOT NULL UNIQUE, -- SHA-256 of the key; raw key is never stored
    scopes       VARCHAR(500) NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_api_keys_user ON api_keys (user_id);

CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL UNIQUE,
    slug        VARCHAR(120)  NOT NULL UNIQUE,
    description VARCHAR(1000),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);

CREATE TABLE products (
    id          UUID PRIMARY KEY,
    sku         VARCHAR(64)   NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(4000),
    price_minor BIGINT        NOT NULL CHECK (price_minor >= 0),
    currency    VARCHAR(3)    NOT NULL,
    stock       INTEGER       NOT NULL CHECK (stock >= 0),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    category_id UUID REFERENCES categories (id),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_active ON products (active);

CREATE TABLE carts (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE cart_items (
    id         UUID PRIMARY KEY,
    cart_id    UUID        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id UUID        NOT NULL REFERENCES products (id),
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (cart_id, product_id)
);

CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    order_number        VARCHAR(32)  NOT NULL UNIQUE,
    user_id             UUID         NOT NULL REFERENCES users (id),
    status              VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    currency            VARCHAR(3)   NOT NULL,
    subtotal_minor      BIGINT       NOT NULL CHECK (subtotal_minor >= 0),
    total_minor         BIGINT       NOT NULL CHECK (total_minor >= 0),
    ship_recipient_name VARCHAR(100) NOT NULL,
    ship_line1          VARCHAR(200) NOT NULL,
    ship_line2          VARCHAR(200),
    ship_city           VARCHAR(100) NOT NULL,
    ship_region         VARCHAR(100),
    ship_postal_code    VARCHAR(20)  NOT NULL,
    ship_country_code   VARCHAR(2)   NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id               UUID PRIMARY KEY,
    order_id         UUID         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id       UUID         NOT NULL REFERENCES products (id),
    sku              VARCHAR(64)  NOT NULL,   -- snapshot at purchase time
    product_name     VARCHAR(200) NOT NULL,   -- snapshot at purchase time
    unit_price_minor BIGINT       NOT NULL CHECK (unit_price_minor >= 0),
    quantity         INTEGER      NOT NULL CHECK (quantity > 0),
    line_total_minor BIGINT       NOT NULL CHECK (line_total_minor >= 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE idempotency_keys (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idem_key     VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    order_id     UUID         NOT NULL REFERENCES orders (id),
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (user_id, idem_key)
);

CREATE TABLE audit_events (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL,
    actor_id    UUID,
    actor_type  VARCHAR(20)  NOT NULL,
    api_key_id  UUID,
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(50),
    target_id   VARCHAR(64),
    ip_address  VARCHAR(45),
    request_id  VARCHAR(64),
    details     VARCHAR(1000)
);
CREATE INDEX idx_audit_events_occurred ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_events_actor ON audit_events (actor_id);
