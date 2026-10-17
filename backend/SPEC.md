# E-Com Backend — SPEC (Phases 1, 2, 2.1, 2.2 and the frontend review's backend items)

A secure e-commerce REST backend with an ERP back office, in **Java 21 / Spring Boot 3.3 / PostgreSQL**.
It is built to be driven both by people (a future web UI) and by a **separate MCP server**
that exposes these endpoints as tools to AI agents.

## Phases

| Phase | Scope | Status |
|------|-------|--------|
| **1** | Auth, accounts, scoped API keys, catalog, cart, checkout/orders, admin order flow, audit log | Code written |
| **2** | ERP back office: 10 features (see below) | Code written |
| **2.1** | Fixes for every issue in [REVIEW.md](REVIEW.md) except H1's toolchain install (see §8 there) | Code written; unit tests written |
| **2.2** | Review change requests R1–R10 (REVIEW.md §9–10): lock order, lock errors, late payments, exact inventory value, login ceiling, cart lock, idempotent adjustments, audit throttling, SoD policy | Code written; tests pass |
| **F1-BE** | Backend items from the frontend review ([../frontend/REVIEW.md](../frontend/REVIEW.md)): BE-1 finance payment queue, BE-2 order-number search, BE-3 names on purchasing/inventory documents, BE-4 `behind-proxy` profile | Code written; tests pass |
| 3 | ~~Install toolchain, first build, Testcontainers tests~~ (done 2026-10-14); payment-provider webhook, supplier bills / AP payments | Next |
| 4 | MCP server (separate project) calling this API with a scoped API key | Code written ([../mcp-server](../mcp-server)) |
| **4.1** | Agent guardrails (REVIEW.md §5.3): per-key spending limits, human-in-the-loop approval, "what did my agent do"; review round 2 fixes (REVIEW.md §12–13) for approval state outliving a cancelled/expired order | Code written; `mvn verify` not run yet (see NOTES.md) |
| **4.2** | Product photos (2026-10-16): optional `imageUrl` on products, same-origin paths only (V8, CHECK fixed in V9); demo photos in `frontend/public/products/` | Code written; `mvn verify` 60 unit + 47 IT pass |
| 5 | Storefront: variants, shipping + tracking, emails, password reset, discounts; step-up login auth (email code / CAPTCHA); Redis rate limiting | Later |

> **Built and tested 2026-10-14** (JDK 21, Maven 3.10): `mvn test` → 54/54 unit tests; `mvn verify` → plus 39/39
> Testcontainers integration tests against PostgreSQL 16 (needs Docker). With Colima set
> `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
> Testcontainers is pinned to 1.21.4 because Docker Engine 29 refuses the API version of Boot 3.3.5's default 1.19.8.

## Run (once you choose to install the toolchain)

```bash
cp .env.example .env            # fill in DB_PASSWORD and JWT_SECRET (openssl rand -base64 48)
docker compose up -d postgres   # Postgres on 127.0.0.1:5432
set -a; source .env; set +a
mvn spring-boot:run             # Flyway applies V1, V2, V3 on first start
mvn test                        # unit tests
# API docs (set API_DOCS_ENABLED=true): http://localhost:8080/swagger-ui.html  ·  /v3/api-docs
```

Or fully in containers: `docker compose --profile app up --build`. CI: `.github/workflows/backend-ci.yml`.
The app **refuses to start** without `DB_PASSWORD` or with a `JWT_SECRET` shorter than 32 bytes.

**Behind the frontend's nginx proxy (BE-4):** start with `SPRING_PROFILES_ACTIVE=behind-proxy` and
`TRUSTED_PROXIES` = a regex of the proxies' own addresses (e.g. `172\.28\.0\.10`). Only when port 8080 is reachable
solely through that proxy. The profile refuses to start without `TRUSTED_PROXIES` (see Security model).

## Package layout

Business modules depend on each other in one direction only (no cycles):
`catalog ← inventory ← purchasing`, `catalog/cart/inventory/tax ← order ← finance ← returns`.
Finance learns about stock, receipts, shipments and cancellations through **domain events** published
inside the same transaction, so accounting still commits or rolls back with the business change.

```
com.ecom
├── config      AppProperties, OpenAPI definition, admin bootstrap
├── common      error model, request id, Money, paging, hashing, idempotency, document numbers, job locks
├── security    filter chain, JWT, API-key filter, rate limiters, token-state check, SYSTEM actor, scopes
├── user        register/login/refresh/logout, login throttle, profile, password, user admin
├── apikey      scoped personal API keys (the MCP server's credential)
├── audit       append-only audit log + searchable admin endpoint
├── catalog     categories, products (on hand / reserved / available)
├── tax         tax rates per destination country
├── cart        per-user server-side cart + tax quote
├── inventory   stock ledger, reservations, adjustments, valuation
├── purchasing  suppliers, purchase orders, goods receipts, reorder policy + suggestions
├── order       checkout, order lifecycle, expiry job, order events
├── finance     payments, invoices, refunds/credit notes, general ledger, reports, accounting listener
└── returns     RMA: request → approve → receive → refund
```

## Phase 2 — ERP features

| # | Feature | Key rules |
|---|---------|-----------|
| 1 | **Staff roles and user admin** | Roles `CUSTOMER_SERVICE`, `INVENTORY_MANAGER`, `PURCHASING_MANAGER`, `FINANCE_MANAGER` (+ `ADMIN`). Role/enable changes need an interactive admin session, never self, never the last admin; they revoke all sessions and invalidate access tokens immediately |
| 2 | **Suppliers** | Unique code, payment terms, soft delete |
| 3 | **Inventory ledger** | On-hand changes ONLY through `InventoryService`; each writes an immutable `stock_movements` row. Moving-average unit cost; cost never appears in the public catalog |
| 4 | **Reorder points** | Per-product reorder point, quantity and preferred supplier. Suggestions use **available** stock + open POs (incl. drafts); one call turns them into draft POs per active supplier |
| 5 | **Purchase orders** | DRAFT → SUBMITTED → APPROVED → PARTIALLY_RECEIVED → RECEIVED (or CANCELLED before any receipt). **Creator ≠ approver ≠ receiver** |
| 6 | **Goods receipts** | Partial deliveries; over-receipt rejected (code + DB CHECK); PO row locked; `Idempotency-Key` required |
| 7 | **Tax rates** | Per destination country (basis points), applied once on the subtotal; `quoteCart` previews it |
| 8 | **Payments and invoices** | `recordPayment` is the ONLY way to PAID (zero-total orders settle automatically); amount must equal the total; one payment per order; sequential invoice numbers; recorder ≠ customer |
| 9 | **Returns and refunds** | Delivered orders, within 30 days, never more than bought. **Approver, receiver and refunder are three different people** (admins included), none of them the customer. Paid-order cancellation refunds automatically |
| 10 | **General ledger and reports** | Double-entry, posted automatically; unbalanced entries abort the transaction; ledgers **and the audit log** are append-only (DB triggers). Trial balance, sales summary, inventory valuation |

### Stock model

`available = on hand − reserved`. Checkout **reserves**; shipment **consumes** (on hand drops, `SALE` movement);
cancel or payment expiry **releases**. Adjustments can never remove reserved units. Unpaid orders expire after
`app.orders.payment-timeout` (30 min) via `OrderExpiryJob` (one order per transaction), and each customer may hold
at most `app.orders.max-open-unpaid-per-user` (3) unpaid orders.

**Exact value pool (R4).** Each product keeps the exact value of its on-hand units (`stock_value_minor`). Inbound
units add their cost; outbound units take their proportional share (rounded half-up), and the last unit out takes
whatever is left. Every stock movement stores the value it moved, and that same amount is posted to GL 1200, so
**GL 1200 Inventory == Σ product stock values** at all times. COGS is the value that left at **shipment** (stored on
the order line); a return restocks exactly its share of that shipped cost. `cost_minor` is just the rounded average.

### Late payments (R3)

`recordPayment` locks the order row first, so the expiry job (`FOR UPDATE SKIP LOCKED`) never expires an order whose
payment is being recorded. If the order already **expired**: when every line can be re-reserved (product still sold,
stock available) the order becomes PAID again (`APPLIED_AFTER_REINSTATEMENT`, audit `ORDER_REINSTATED_AFTER_LATE_PAYMENT`);
otherwise the payment is recorded and refunded in full straight away (`REFUNDED_ORDER_EXPIRED`, credit note, audit
`ORDER_EXPIRED_PAYMENT_REFUNDED`). **R11:** a payment for an order cancelled by the customer or staff (or a legacy
cancellation without a reason) is recorded and refunded in full in the same transaction (`REFUNDED_ORDER_CANCELLED`,
credit note, audit `ORDER_CANCELLED_PAYMENT_REFUNDED`); a cancelled order is never reinstated. Paid, shipped or
delivered orders reject a second payment (one payment per order).

### Lock order (R1)

One row-lock order everywhere: **cart → order → return request → products (sorted by id)**. Purchase-order receipts
lock the PO, then products. Lock waits are capped by `lock_timeout = 5s`; deadlock losers and timeouts are returned as
`409 CONFLICT` with `retryable: true` (R2).

### Automatic ledger postings

| Event | Debit | Credit |
|-------|-------|--------|
| Product created with opening stock | 1200 Inventory | 3000 Opening equity |
| Stock adjustment (+ / −) | 1200 Inventory / 5100 Adjustments | 5100 Adjustments / 1200 Inventory |
| Goods receipt | 1200 Inventory | 2000 Accounts payable |
| Payment recorded | 1000 Cash (total) | **2200 Customer deposits** (subtotal) + 2100 Tax payable (tax) |
| Payment for an expired order whose stock is gone, or for a cancelled order (R11) | the payment row above, then immediately the "refund before shipment" row below (net zero) | |
| Order shipped | 2200 Customer deposits + 5000 COGS (value that left at shipment) | 4000 Sales revenue + 1200 Inventory |
| Customer return received | 1200 Inventory (share of shipped cost) | 5000 COGS |
| Refund before shipment (paid order cancelled) | 2200 Customer deposits (net) + 2100 Tax payable | 1000 Cash |
| Refund after shipment (return) | 4100 Sales returns (net) + 2100 Tax payable | 1000 Cash |

Revenue is recognised at shipment, and ledger inventory always equals physical on-hand stock × cost.

## Security model

| Area | What is done |
|------|--------------|
| Passwords | BCrypt (cost 12); min 12 chars, max 72 bytes; never equal to the email |
| Login | Every failure (unknown email, wrong password, disabled, throttled) returns the same 401 after similar BCrypt work. Failures are throttled per **(account, IP)** for 15 min after 5 tries, so nobody can lock another person out and nothing reveals whether an account exists. **Per-account ceiling (R5):** after 20 failures in an hour across all IPs, only IPs that logged into that account in the last 30 days may try (step-up hook for CAPTCHA / email code later) |
| Access tokens | HS256 JWT, 15 min, algorithm pinned, `iss`/`aud`/`exp` validated, scopes in `scope`, token version in `tv`. A token is rejected once the account is disabled or its token version is bumped (password change, role change, disable, revoke) — within ~15 s, not 15 min |
| Refresh tokens | Opaque 256-bit, stored as SHA-256, single use. Reuse revokes all sessions, except a 30 s grace window after normal rotation (two tabs refreshing at once) |
| API keys | `ek_` + 256-bit, SHA-256 at rest, shown once, scoped, expiring (≤ 365 d), max 10 active. Effective scopes = key scopes ∩ owner's current role. **Revoked automatically on password change and on "revoke all credentials"**. Optional per-key spending guardrails: max order value, max orders/day (hard caps), require-approval-above (soft cap, see "Agent guardrails") |
| Account actions | Password change, API-key management, role/enable changes and credential revocation need a real user session, never an API key |
| Authorization | Deny-by-default URL rules + `@PreAuthorize` on scopes; separation of duties per person via the `app.sod.mode` policy (below); other users' resources return 404 |
| Input | Bean Validation; unknown JSON fields rejected; enum-whitelisted sorts; escaped LIKE; JPA parameter binding only |
| Money and stock | Integer minor units; ISO decimal places per currency; server-side prices; `expectedTotalMinor` guards against price changes; `SELECT … FOR UPDATE` in id order; `@Version` optimistic locking |
| Abuse | Per-IP flood guard (600/min, 10/min on `POST /auth/*`) **and per-user / per-API-key limits (120/min)** — the MCP server shares one IP, so each agent key gets its own allowance (in-memory; Redis before scale-out) |
| Transport and headers | Stateless, strict CORS, HSTS, CSP, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`; Swagger UI off by default |
| Client IP behind a proxy (BE-4) | Default: the socket address only, so `X-Forwarded-For` is ignored. Profile `behind-proxy`: Tomcat's RemoteIpValve reads the client IP from `X-Forwarded-For`, but only for connections from `TRUSTED_PROXIES` (no default: trusting whole private ranges would let a client on that network plant a fake IP, since nginx appends to the header it receives) |
| Errors | RFC 7807 `problem+json`; no stack traces or SQL; generic 500 |
| Audit | Business and security events incl. **denied (403) attempts, rejected API keys, rate-limit hits**: at most one row per caller/IP per minute, with `occurrences=N` in the details (R8); `SOD_OVERRIDDEN` for every small-team override; immutable (DB trigger); searchable by action, actor, actor type, API key, target and date range |
| Secrets | Env vars only; no default DB password or JWT secret; first admin via `BOOTSTRAP_ADMIN_*` only; a disabled SYSTEM account (no password) is the actor for jobs and future webhooks |

## Scopes

| Scope | CUSTOMER | CUSTOMER_SERVICE | INVENTORY_MGR | PURCHASING_MGR | FINANCE_MGR | ADMIN |
|-------|:-:|:-:|:-:|:-:|:-:|:-:|
| `profile:read` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `cart:read`, `cart:write`, `orders:read`, `orders:write` | ✓ | | | | | ✓ |
| `orders:admin`, `returns:manage` | | ✓ | | | | ✓ |
| `inventory:read` | | | ✓ | ✓ | ✓ | ✓ |
| `inventory:write` (adjust, receive goods, receive returns) | | | ✓ | | | ✓ |
| `purchasing:read` | | | ✓ | ✓ | ✓ | ✓ |
| `purchasing:write`, `purchasing:approve` | | | | ✓ | | ✓ |
| `finance:read`, `finance:write` | | | | | ✓ | ✓ |
| `audit:read` | | | | | ✓ | ✓ |
| `catalog:write`, `users:admin` | | | | | | ✓ |

ADMIN holds every scope, but separation-of-duties rules apply **per person**, not per role.

## Separation-of-duties policy (R10)

`app.sod.mode` (env `SOD_MODE`): **`strict`** (default) or **`small-team`**.

| Step | Rule | strict | small-team |
|---|---|---|---|
| `approvePurchaseOrder` | approver ≠ PO creator | blocked (`SELF_APPROVAL_NOT_ALLOWED`) | allowed with `sodOverrideReason` |
| `receivePurchaseOrder` | receiver ≠ PO creator and approver | blocked | allowed with `sodOverrideReason` |
| `receiveReturn` | receiver ≠ return approver | blocked | allowed with `sodOverrideReason` |
| `refundReturn` | refunder ≠ return approver and receiver | blocked | allowed with `sodOverrideReason` |
| `recordPayment` | recorder ≠ the order's customer | **always blocked** | **always blocked** |
| `approveReturn` / `rejectReturn` / `receiveReturn` / `refundReturn` | staff ≠ the return's customer | **always blocked** (code + DB CHECK) | **always blocked** |

In small-team mode a conflicting step without a reason fails with `403 SEGREGATION_OF_DUTIES`
(`overrideAllowed: true`, message says to add a reason). With a non-blank reason (max 300 chars) the step goes ahead
and is audited as `SOD_OVERRIDDEN` (actor, step, rule, reason). **Review overrides:**
`GET /api/v1/admin/audit-events?action=SOD_OVERRIDDEN` (scope `audit:read`). V4 dropped the DB CHECKs small-team
mode legitimately bypasses (`chk_po_approver_differs`, `chk_return_receiver_differs`); strict mode enforces them in
code. The own-return CHECK (`chk_return_staff_not_customer`) stays.

## MCP-readiness contract (what the MCP server can rely on)

1. **Credential**: a user creates an API key (`createApiKey`) with only the scopes the agent needs; the MCP server sends `X-API-Key`. The agent acts *as that user*, never with more power, and has its own rate limit.
2. **Discovery**: `/v3/api-docs` (enable with `API_DOCS_ENABLED=true` for the MCP build). Every endpoint has a stable `operationId` = MCP tool name. `whoAmI` returns effective scopes. Each operation that needs one also carries an `x-required-scopes` extension (`OpenApiConfig.scopeExtensionOperationCustomizer`, read straight from that endpoint's own `@PreAuthorize` — never a second list), so a client can show an agent only the tools its key can actually use instead of exposing all ~90 and letting most 403 (mcp-server/REVIEW.md, M2).
3. **Quote, then confirm**: `quoteCart?countryCode=` returns the exact total; `placeOrder` must send it as `expectedTotalMinor`, otherwise `409 PRICE_CHANGED` with the new total. A natural point to ask the human.
4. **Safe retries**: `placeOrder`, `createPurchaseOrder`, `receivePurchaseOrder`, `requestReturn` and `adjustStock` require an `Idempotency-Key` (`createApiKey` accepts one). Same key + same body → original result (`200`, `Idempotent-Replayed: true`); same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
5. **Errors agents can reason about**: `code` (stable enum), `retryable`, `requestId`, plus structured extras (`available`, `actualTotalMinor`, `allowedNextStatuses`, `invalidScopes`, …).
6. **Next steps are explicit**: orders carry `allowedActions`, `allowedNextStatuses`, `expiresAt`; POs carry `allowedActions`; the cart has `readyForCheckout` and per-line `issue`.
7. **Money is unambiguous**: `{ "amountMinor": 1999, "currency": "USD", "formatted": "19.99" }`.
8. **Traceability**: `X-Request-Id` is echoed and stored; `listAuditEvents?apiKeyId=` shows everything one agent key did.
9. **Names come with ids** (BE-3): purchase-order lines, goods-receipt lines, stock movements and reorder suggestions carry
   `sku` / `productName` / `supplierName`, so a tool never needs one lookup per row. Orders can be found by `orderNumber` (BE-2).
10. **Session/credential operations are never meant to be agent-callable**, scope or no scope: `register`, `login`,
    `refreshToken`, `logout`, `changeMyPassword`, API-key management, and the user-admin role/status/credential
    endpoints have no `x-required-scopes` extension to filter by (they're gated by actor type, not a scope — see
    the Security model table), so an MCP client excludes them by operationId explicitly instead
    (mcp-server/src/ecom_mcp/policy.py).

## Agent guardrails (Phase 4.1, REVIEW.md §5.3)

Scopes decide *what* an agent key may do; these decide *how much*. Set once, at `createApiKey` time, per key —
to change the deal, revoke the key and issue another (`null` = no limit of that kind, so an existing key's
behaviour never changes under it):

- **`maxOrderValueMinor`** — a hard cap. `placeOrder` above it throws `422 AGENT_LIMIT_EXCEEDED` before any stock
  is reserved.
- **`maxOrdersPerDay`** — a hard cap on orders placed by that key in a rolling 24 hours, same error code.
- **`requireApprovalAboveMinor`** — a soft cap. The order is placed and its stock reserved as normal, but
  `approvalState` is `PENDING` and `recordPayment` refuses it (`409 INVALID_STATE_TRANSITION`) until the key's
  owner calls `approveAgentOrder` or `rejectAgentOrder` — **both user-JWT only**, the same discipline as
  `changeMyPassword`: an agent approving the order it just placed would defeat the whole mechanism, so these two
  (plus `listOrdersAwaitingApproval` and `getApiKeyActivity`) are on the MCP server's `EXCLUDED_OPERATIONS` list
  regardless of scope. Rejecting cancels the order through the ordinary cancel path (stock released, books
  unaffected) — no special-cased rollback logic.

A hard-cap refusal is audited as `AGENT_LIMIT_BLOCKED` against the key, in its own transaction
(`AuditService.recordIndependently`, `REQUIRES_NEW`) — it has to commit independently of `checkout()`'s own
rollback, or the audit row would be rolled back right along with the order that was refused and never actually
persist. `getApiKeyActivity` ("what did my agent do?") reads exactly these rows, scoped to one key the caller
owns — the same `listAuditEvents` data admins see, but self-service and ownership-checked instead of `audit:read`.

**Approval state never outlives the order** (review round 2, REVIEW.md §12–13): `CustomerOrder.cancel()` — the one
place every cancellation path (customer, staff, expiry) already converges — reconciles a `PENDING` approval to
`EXPIRED` (cancelled by expiry) or `REJECTED` (cancelled any other way) the moment it cancels the order, so a
cancelled/expired order can never stay stuck in the approval queue, be "approved" after the fact, or make
`recordPayment` refuse a legitimate late payment that should instead follow the R3/R11 rules below.

## API (v1)

| Method | Path | operationId | Auth / scope |
|-------|------|-------------|--------------|
| POST | /api/v1/auth/register · /login · /refresh · /logout | register / login / refreshToken / logout | public |
| GET | /api/v1/auth/whoami | whoAmI | any authenticated |
| GET | /api/v1/users/me | getMyProfile | profile:read |
| PUT | /api/v1/users/me/password | changeMyPassword | user JWT only |
| POST | /api/v1/users/me/revoke-all-credentials | revokeAllMyCredentials | user JWT only |
| POST / GET | /api/v1/api-keys (create: optional `maxOrderValueMinor`/`maxOrdersPerDay`/`requireApprovalAboveMinor`) | createApiKey / listApiKeys | user JWT only |
| DELETE | /api/v1/api-keys/{id} | revokeApiKey | user JWT only |
| GET | /api/v1/api-keys/{id}/activity | getApiKeyActivity | user JWT only |
| GET | /api/v1/products[/{id}] | searchProducts / getProduct | public |
| POST / PUT / DELETE | /api/v1/products[/{id}] | createProduct / updateProduct / deactivateProduct | catalog:write |
| GET | /api/v1/categories[/{id}] | listCategories / getCategory | public |
| POST / PUT / DELETE | /api/v1/categories[/{id}] | createCategory / updateCategory / deleteCategory | catalog:write |
| GET | /api/v1/cart | getCart | cart:read |
| GET | /api/v1/cart/quote?countryCode= | quoteCart | cart:read |
| POST | /api/v1/cart/items | addCartItem | cart:write |
| PUT / DELETE | /api/v1/cart/items/{productId} | setCartItemQuantity / removeCartItem | cart:write |
| DELETE | /api/v1/cart | clearCart | cart:write |
| POST | /api/v1/orders (`Idempotency-Key`, body incl. `expectedTotalMinor`) | placeOrder | orders:write |
| GET | /api/v1/orders[/{id}] | listMyOrders / getMyOrder | orders:read |
| POST | /api/v1/orders/{id}/cancel | cancelMyOrder | orders:write |
| GET | /api/v1/orders/{id}/invoice | getMyOrderInvoice | orders:read |
| GET | /api/v1/orders/awaiting-approval | listOrdersAwaitingApproval | user JWT only |
| POST | /api/v1/orders/{id}/approve · /reject | approveAgentOrder / rejectAgentOrder | user JWT only |
| POST | /api/v1/orders/{orderId}/returns (`Idempotency-Key`) | requestReturn | orders:write |
| GET | /api/v1/returns[/{id}] | listMyReturns / getMyReturn | orders:read |
| GET | /api/v1/admin/orders[/{id}] (`?status`, `?orderNumber` exact and case-insensitive) | adminListOrders / adminGetOrder | orders:admin |
| PATCH | /api/v1/admin/orders/{id}/status | adminUpdateOrderStatus (SHIPPED, DELIVERED, CANCELLED) | orders:admin |
| GET | /api/v1/admin/returns[/{id}] | adminListReturns / adminGetReturn | returns:manage, inventory:write or finance:write |
| POST | /api/v1/admin/returns/{id}/approve · /reject | approveReturn / rejectReturn | returns:manage |
| POST | /api/v1/admin/returns/{id}/receive (optional body `sodOverrideReason`) | receiveReturn | inventory:write |
| POST | /api/v1/admin/returns/{id}/refund (optional body `sodOverrideReason`) | refundReturn | finance:write |
| GET | /api/v1/admin/roles | listRoles | users:admin |
| GET | /api/v1/admin/users[/{id}] | adminListUsers / adminGetUser | users:admin |
| PATCH | /api/v1/admin/users/{id}/role · /status | adminChangeUserRole / adminSetUserEnabled | users:admin + user JWT |
| POST | /api/v1/admin/users/{id}/revoke-credentials | adminRevokeUserCredentials | users:admin + user JWT |
| GET | /api/v1/admin/audit-events | listAuditEvents (action, actorId, actorType, apiKeyId, targetType, targetId, from, to); `?action=SOD_OVERRIDDEN` = review separation-of-duties overrides | audit:read |
| GET | /api/v1/inventory/products/{id} | getInventoryItem | inventory:read |
| POST | /api/v1/inventory/products/{id}/adjustments (`Idempotency-Key`) | adjustStock | inventory:write |
| PUT | /api/v1/inventory/products/{id}/reorder-policy | setReorderPolicy | inventory:write |
| GET | /api/v1/inventory/movements | listStockMovements | inventory:read |
| GET | /api/v1/inventory/reorder-suggestions (paged) | listReorderSuggestions | inventory:read |
| GET | /api/v1/inventory/valuation (paged) | getInventoryValuation | inventory:read |
| GET / POST / PUT / DELETE | /api/v1/purchasing/suppliers[/{id}] | searchSuppliers / getSupplier / createSupplier / updateSupplier / deactivateSupplier | purchasing:read / write |
| GET | /api/v1/purchasing/purchase-orders[/{id}] | listPurchaseOrders / getPurchaseOrder | purchasing:read |
| POST | /api/v1/purchasing/purchase-orders (`Idempotency-Key`) | createPurchaseOrder | purchasing:write |
| PUT | …/purchase-orders/{id} | updateDraftPurchaseOrder | purchasing:write |
| POST | …/purchase-orders/from-reorder-suggestions | createPurchaseOrdersFromReorderSuggestions | purchasing:write |
| POST | …/purchase-orders/{id}/submit · /cancel | submitPurchaseOrder / cancelPurchaseOrder | purchasing:write |
| POST | …/purchase-orders/{id}/approve (optional body `sodOverrideReason`) | approvePurchaseOrder | purchasing:approve |
| POST | …/purchase-orders/{id}/receipts (`Idempotency-Key`; body may carry `sodOverrideReason`) | receivePurchaseOrder | inventory:write |
| GET | …/purchase-orders/{id}/receipts | listGoodsReceipts | purchasing:read |
| GET | /api/v1/finance/orders (`?status` default PENDING_PAYMENT; `?orderNumber` finds one order in any status). Returns number, customer id/name/email, total, `expiresAt`, `paidAt`: no address or items | financeListOrders | finance:read |
| POST | /api/v1/finance/orders/{orderId}/payments (response `outcome`: APPLIED / APPLIED_AFTER_REINSTATEMENT / REFUNDED_ORDER_EXPIRED / REFUNDED_ORDER_CANCELLED) | recordPayment | finance:write |
| GET | /api/v1/finance/payments · /invoices[/{id}] · /refunds | listPayments / listInvoices / getInvoice / listRefunds | finance:read |
| GET / PUT | /api/v1/finance/tax-rates[/{countryCode}] | listTaxRates / upsertTaxRate | finance:read / write |
| GET | /api/v1/finance/ledger/accounts · /ledger/journal-entries | listLedgerAccounts / listJournalEntries | finance:read |
| GET | /api/v1/finance/reports/trial-balance · /sales-summary?from&to | getTrialBalance / getSalesSummary | finance:read |
| GET | /actuator/health | — | public |

### Order lifecycle

`PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`; `CANCELLED` from `PENDING_PAYMENT` (customer, staff, or
automatic expiry) or `PAID` (staff only, automatic full refund). `PAID` is reached only through
`recordPayment`, or immediately for zero-total orders; a payment-provider webhook will call the same
service as the SYSTEM actor.

### Breaking changes in 2.1 (for any client written against Phase 2)

- `placeOrder` body requires `expectedTotalMinor` (use `quoteCart`).
- `createPurchaseOrder` and `requestReturn` require an `Idempotency-Key` header.
- `listReorderSuggestions` and `getInventoryValuation` are paged.
- Login never returns `ACCOUNT_LOCKED` any more; throttled attempts get the normal `INVALID_CREDENTIALS`.
- New errors: `PRICE_CHANGED`, `SEGREGATION_OF_DUTIES`; product `availableQuantity` now excludes reserved units.

### Breaking changes in 2.2

- `adjustStock` requires an `Idempotency-Key` header; its response is unchanged (200, `Idempotent-Replayed: true` on replay).
- `PaymentResponse` gains `outcome`, `refundId`, `creditNoteNumber`; `OrderResponse` gains `cancelReason`;
  stock movements gain `value`; `GoodsReceiptRequest` gains optional `sodOverrideReason`.
- `recordPayment` on an **expired** order no longer fails: it reinstates the order or records-and-refunds (see Late payments).
- R11: `recordPayment` on an order **cancelled by the customer or staff** no longer fails with 409 either: it records
  and refunds the payment (`REFUNDED_ORDER_CANCELLED`). V5 extends the `payments.outcome` CHECK.
- Audit rows for `ACCESS_DENIED`, `API_KEY_REJECTED`, `RATE_LIMITED` are now at most one per caller/IP per minute,
  with `occurrences=N` in `details`.
- New config: `app.sod.mode`, `app.security.login-throttle.account-max-failures` / `account-window`, Hikari
  `connection-init-sql: SET lock_timeout = '5s'`.

### Changes from the frontend review (additive, nothing breaks)

- New `financeListOrders` (BE-1); new `orderNumber` filter on `adminListOrders` (BE-2). An `orderNumber` must match
  `^[A-Za-z0-9-]{1,32}$`, otherwise `400 VALIDATION_FAILED`.
- New response fields (BE-3): `PurchaseOrderResponse.supplierName`; `sku` + `productName` on purchase-order lines,
  goods-receipt lines and stock movements (hidden products included); `ReorderSuggestion.preferredSupplierName`
  (null without a preferred supplier). Names are loaded in one query per page, never one per row.
- New profile `behind-proxy` with required `TRUSTED_PROXIES` (BE-4).
