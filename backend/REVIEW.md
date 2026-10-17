# E-Com Backend — Architecture & Product Review

**Reviewed:** 2026-10-13 · **Scope:** everything under `backend/` (≈8,100 lines of Java, 2 Flyway migrations, SPEC.md, NOTES.md)
**Lenses:** software architect (correctness, security, scalability, maintainability) and product specialist (what a real shop, its staff and AI agents need)

---

## 1. Executive summary

- **The design is well above average.** Money is handled in whole cents, stock is protected against overselling, the accounting ledgers can't be edited after the fact (database triggers block it), and the API is shaped for AI agents (stable error codes, "what can I do next" hints, idempotent checkout).
- **But nothing has been proven yet.** The code has **never been compiled or run**, and there are **zero automated tests** (`src/test` doesn't exist, and Maven isn't installed on this machine). Every guarantee in SPEC.md is still a promise, not a fact.
- **6 high-priority issues** need fixing before real users touch it. The two biggest:
  1. **Unpaid orders hold stock forever.** Anyone (or a misbehaving AI agent) can "buy" the whole catalogue, never pay, and block real customers.
  2. **Changing your password doesn't kill API keys.** If an attacker got in and created a key, they keep access after the victim resets their password.
- **Product-wise it's a strong back office, but a thin storefront.** No product images or variants, no shipping/tracking, no emails, no discounts, no real payment provider yet.
- **Recommended next step:** Phase 0 — install the toolchain, get it compiling, and write the money-path tests. Then fix the high-priority list. (Details in §7.)

### Scorecard

| Area | Score | One-line verdict |
|---|:-:|---|
| Domain modelling | 9/10 | Order-to-cash, procure-to-stock and returns are modelled like a real ERP |
| Security design | 8/10 | Strong foundations; a few real gaps (§3) |
| Data integrity | 8/10 | Row locks, DB checks, append-only ledgers; a couple of race conditions |
| AI-agent readiness (MCP) | 7/10 | Great error contract; missing guardrails and idempotency on some writes |
| Operability | 3/10 | No metrics, no scheduled jobs, no CI, no app container |
| Test coverage | 0/10 | None |
| Storefront features | 4/10 | Basic browse/cart/checkout only |

---

## 2. What's genuinely good (keep these)

| Strength | Why it matters |
|---|---|
| Money stored as whole minor units (cents), never floats | No rounding bugs on totals, tax or refunds |
| `SELECT … FOR UPDATE` on products, always in id order | Two shoppers can't both buy the last item, and the locks can't deadlock |
| Append-only ledgers enforced by **DB triggers**, not just code | Even a bad migration or SQL injection can't rewrite financial or stock history |
| Segregation of duties in code **and** a DB `CHECK` (PO creator ≠ approver) | A real internal-fraud control, not just a UI rule |
| Refresh-token rotation with reuse detection; hashed tokens and API keys | A stolen database dump doesn't give anyone working credentials |
| API key scopes = key scopes ∩ owner's *current* role | Demoting or disabling a user instantly shrinks every key they made |
| RFC 7807 errors with stable `code` + `retryable` | An AI agent can decide "retry or give up" without parsing English |
| `allowedActions` / `allowedNextStatuses` / `readyForCheckout` in responses | Agents and UIs don't have to re-implement business rules |
| Flyway owns the schema, Hibernate only validates; `open-in-view: false`; unknown JSON fields rejected | Avoids three classic Spring Boot footguns |

---

## 3. Issues found

Severity guide: **High** = fix before any real user · **Medium** = fix before scaling or going public · **Low** = tidy-up.

### 3.1 High

| # | Issue | Where | Business impact | Fix |
|---|---|---|---|---|
| H1 | **Never compiled, never run, no tests.** | whole project; NOTES.md "Known gaps" | All guarantees (no overselling, idempotency, balanced ledger) are unverified. First build will likely surface bugs. | Phase 0 (see §7). |
| H2 | **Unpaid orders reserve stock forever.** Checkout lowers stock straight away; nothing ever cancels a `PENDING_PAYMENT` order. There are no scheduled jobs anywhere in the codebase. Zero-total orders can't be paid at all (payments must be > 0), so they're stuck permanently. | [OrderService.java:87](src/main/java/com/ecom/order/OrderService.java#L87), [FinanceService.java:68](src/main/java/com/ecom/finance/FinanceService.java#L68) | One user or one buggy agent can empty the shop's available stock without paying — a cheap denial-of-service attack on sales. | Add a payment deadline (e.g. 30 min) and a scheduled job that auto-cancels and restores stock. Cap open unpaid orders per user. Allow zero-total orders to go straight to PAID. |
| H3 | **Password change doesn't revoke API keys.** It revokes refresh tokens only. | [AuthService.java:144](src/main/java/com/ecom/user/AuthService.java#L144) | Attacker logs in with a stolen password, mints an API key, victim resets password → **attacker still has access** for up to 365 days. | On password change, revoke all API keys (or offer "sign out everywhere incl. keys"). Also add an admin "revoke all credentials" action. |
| H4 | **Rate limiting is by IP address only.** | [RateLimitFilter.java:50](src/main/java/com/ecom/security/RateLimitFilter.java#L50) | The whole plan is an MCP server calling on behalf of many users — that's **one IP**, so all agent users share 120 requests/min. Meanwhile one abusive API key isn't limited separately. | Key buckets by authenticated principal (user / API key id) *and* IP. Move to Redis (Bucket4j) before running more than one instance. |
| H5 | **Anyone can lock any customer out**, and the lock reveals the account exists. 5 bad passwords → 15-min lockout; the `ACCOUNT_LOCKED` reply differs from the generic "invalid email or password". | [AuthService.java:87](src/main/java/com/ecom/user/AuthService.java#L87) | Competitors or trolls can lock out customers at will; and it undoes the careful anti-enumeration design elsewhere. | Throttle per account+IP instead of hard lockout (or progressive delays / CAPTCHA). Return the same generic error when locked. |
| H6 | **ADMIN has every scope, so segregation of duties doesn't hold for admins.** An admin can approve, receive *and* refund the same return, record payments, and adjust stock. | [Role.java:18](src/main/java/com/ecom/user/Role.java#L18) | NOTES.md says "no single role does all three" — true for staff roles, false for ADMIN. An auditor would flag this. | Make ADMIN about *users and configuration*, not transactions; or enforce "different person per step" in code (like the PO approver rule) regardless of role. |

### 3.2 Medium

| # | Issue | Where | Impact | Fix |
|---|---|---|---|---|
| M1 | **Price can change between "add to cart" and "pay" silently.** Checkout uses the current price with no check against what the buyer saw. | [OrderService.java:122](src/main/java/com/ecom/order/OrderService.java#L122) | Especially risky for agents: agent tells the human "$40 total", price changes, order is placed at $55. | Accept an `expectedTotalMinor` on checkout; reject with `PRICE_CHANGED` if it differs. |
| M2 | **Revenue booked at payment, cost at shipment, stock reduced at checkout** — three different moments. | [FinanceService.java:68](src/main/java/com/ecom/finance/FinanceService.java#L68), [OrderService.java:203](src/main/java/com/ecom/order/OrderService.java#L203) | Books show revenue for goods not yet shipped (accounting standards IFRS 15 / ASC 606 recognise revenue on delivery/shipment), and ledger inventory ≠ physical stock (already noted in NOTES.md). | At payment post to a **Customer deposits** liability; move to Revenue at shipment. Separate `on_hand` from `reserved` stock instead of decrementing at checkout. |
| M3 | **Two return requests sent at the same time can both succeed for the same items.** The "already returned" check doesn't lock the order. | [ReturnService.java:77-89](src/main/java/com/ecom/returns/ReturnService.java#L77-L89) | If both get approved and received: double restock and double cost reversal → phantom inventory. | Lock the order row (`PESSIMISTIC_WRITE`) at the start of `request()`. |
| M4 | **Two browser tabs refreshing at once = "token theft" = logged out everywhere.** | [AuthService.java:116](src/main/java/com/ecom/user/AuthService.java#L116) | Annoying for normal users, and will look like random logouts in support tickets. | Short grace window (e.g. 10–30 s) where the just-rotated token returns the same new pair. |
| M5 | **Several write operations aren't idempotent** (safe to retry). Only `placeOrder` and goods receipts take `Idempotency-Key`. `createPurchaseOrder`, `requestReturn` and `createApiKey` don't. | [PurchasingService.java:81](src/main/java/com/ecom/purchasing/PurchasingService.java#L81) | An agent that times out and retries creates **duplicate purchase orders**. | Add `Idempotency-Key` to every create/write an agent might call; one shared idempotency table keyed by (user, operation, key). |
| M6 | **Goods receipt doesn't lock the purchase order**, and PO lines have no version column. It's currently safe only by accident (lines happen to load after the product locks). | [PurchasingService.java:153](src/main/java/com/ecom/purchasing/PurchasingService.java#L153) | A small refactor could silently re-open a lost-update bug (received qty wrong, AP double-counted). | Lock the PO row explicitly at the start of `receive()`. |
| M7 | **Access tokens ignore a user being disabled or demoted for up to 15 min** (API keys *do* re-check). | [JwtConfig.java](src/main/java/com/ecom/security/JwtConfig.java) | A fired admin keeps full power for 15 minutes. | Add a per-user `token_version` claim checked against a small cache, or a deny-list for disabled users. |
| M8 | **The audit log can be edited or deleted** — the ledgers have immutability triggers, `audit_events` doesn't. | [V1__init.sql:129](src/main/resources/db/migration/V1__init.sql#L129), [V2__erp.sql:251-255](src/main/resources/db/migration/V2__erp.sql#L251-L255) | Someone with DB access can cover their tracks. | Add the same `forbid_ledger_mutation` trigger to `audit_events`. |
| M9 | **Audit gaps.** Denied (403) attempts, invalid API keys and rate-limit hits aren't recorded. Search allows only one filter at a time; no date range or target filter. | [AuditService.java](src/main/java/com/ecom/audit/AuditService.java) | Hard to investigate abuse — exactly what you need once agents are calling the API. | Audit security events from the filters; add combined filters + date range. |
| M10 | **Tables that grow forever:** `refresh_tokens`, `idempotency_keys`, `audit_events`. No cleanup jobs exist. | schema | Slow queries and storage cost over time. | Scheduled cleanup (with ShedLock so only one instance runs it); audit → archive/partition by month. |
| M11 | **N+1 queries** (one extra DB call per row) in `listInvoices` and `listPayments`; valuation and reorder suggestions load every product into memory. | [FinanceService.java:153-162](src/main/java/com/ecom/finance/FinanceService.java#L153-L162) | Fine at 100 orders, slow at 100,000. | Join-fetch or batch-load; paginate the reports. |
| M12 | **Modules call each other in a circle.** `order` → `finance` → `order` (FinanceService takes `CustomerOrder`, OrderService calls FinanceService). | order/, finance/ | Hard to split later, hard to test in isolation. | Adopt Spring Modulith; publish domain events (`OrderPaid`, `OrderShipped`, `ReturnReceived`) and let finance listen. |

### 3.3 Low

| # | Issue | Where | Fix |
|---|---|---|---|
| L1 | SPEC.md lists `POST /products/{id}/stock-adjustments` (`adjustProductStock`) — it doesn't exist in code. Package layout in SPEC omits all Phase-2 packages. Since MCP tool names come from operationIds, docs drift matters. | [SPEC.md:104](SPEC.md#L104) | Remove the row; generate the endpoint table from `/v3/api-docs` in CI. |
| L2 | Stale comment: "PAID is set by an admin". | [OrderStatus.java:7](src/main/java/com/ecom/order/OrderStatus.java#L7) | Update. |
| L3 | Checkout idempotency fingerprint covers only the shipping address, not the cart. Same key + changed cart quietly returns the old order. | [OrderService.java:89](src/main/java/com/ecom/order/OrderService.java#L89) | Include cart lines in the hash, or document the behaviour. |
| L4 | "Create POs from reorder suggestions" fails the whole batch if one preferred supplier is inactive. | [PurchasingService.java:214](src/main/java/com/ecom/purchasing/PurchasingService.java#L214) | Skip and report that supplier, like products without a supplier. |
| L5 | Swagger UI is on by default. | [application.yml:40](src/main/resources/application.yml#L40) | Default to `false`; turn on for dev only. |
| L6 | `recordPayment` requires a logged-in user (`recorded_by NOT NULL`). A payment webhook (Phase 3) has no user. | [FinanceService.java:83](src/main/java/com/ecom/finance/FinanceService.java#L83) | Introduce a SYSTEM actor id now so Phase 3 isn't a migration. |
| L7 | Money formatting assumes 2 decimals; breaks for JPY (0) or KWD (3). Already noted. | [Money.java](src/main/java/com/ecom/common/web/Money.java) | Use `java.util.Currency#getDefaultFractionDigits`. |
| L8 | Only Postgres is containerised; there's no Dockerfile for the app, no CI pipeline. | docker-compose.yml | Add Dockerfile + GitHub Actions (build, test, OpenAPI diff). |

---

## 4. Architecture enhancements

1. **Test pyramid first.** Testcontainers (a real Postgres in Docker during tests) for: concurrent checkout of the last item, idempotent replay, PO self-approval blocked, trial balance always `balanced == true` after a random mix of operations (a "property test"). Add ArchUnit/Spring Modulith tests to stop modules tangling.
2. **Domain events + transactional outbox.** Today everything happens in one big transaction, which is great for consistency. Events (`OrderPlaced`, `OrderPaid`, `OrderShipped`) written to an outbox table in the same transaction unlock emails, webhooks, search indexing and agent notifications without breaking that consistency.
3. **Stock reservation model.** `available = on_hand − reserved`. Checkout reserves, shipment consumes, expiry/cancel releases. Fixes H2 and the ledger mismatch in M2 together.
4. **Scheduler infrastructure.** `@EnableScheduling` + ShedLock for order expiry, token/idempotency cleanup, low-stock alerts.
5. **Observability.** Only `/actuator/health` is exposed. Add Prometheus metrics (Micrometer), OpenTelemetry tracing (the request-id plumbing is already there), and business metrics: orders/min, payment failures, stock-outs.
6. **API polish for clients.** Expose the `@Version` as `ETag` and accept `If-Match` so UIs and agents get clean "someone else changed this" errors; cursor pagination for large lists.
7. **Per-principal rate limits in Redis** (see H4).
8. **Asymmetric JWT (RS256 + JWKS)** once the MCP server or any other service needs to verify tokens itself — already noted in NOTES.md.

---

## 5. Product gaps & new features

### 5.1 Must-have before launch (customers will notice immediately)

| Feature | Why |
|---|---|
| **Real payment provider** (e.g. Stripe) + webhook | Today a finance user types in payments manually. Planned for Phase 3. |
| **Order auto-expiry** | See H2. |
| **Shipping**: methods, rates, carrier + **tracking number** on SHIPPED | Customers' #1 post-purchase question is "where's my order?". `SHIPPED` currently carries nothing. |
| **Transactional emails**: order confirmation, shipped, refund issued; email verification; password reset | No emails exist at all. Password reset is planned for Phase 5 — it should be much earlier, it's a support-ticket generator. |
| **Profile & address book** | Customers can't change their name, and must type a full address every order. |
| **Product images and variants** (size/colour) | A product is a flat SKU with text only. Hard to sell clothing, shoes, etc. |
| **Discounts / coupon codes** | Standard expectation; also needed for marketing. |

### 5.2 Growth features

- **Better search**: Postgres full-text (`tsvector`), filters by price/in-stock, category facets. Currently it's a `LIKE` on name/SKU.
- **Reviews & ratings, wishlist, recently viewed.**
- **Abandoned-cart reminders** — carts are server-side already, so this is cheap to add once emails exist.
- **Exchanges and partial shipments** — returns currently mean "refund only", and an order ships all-or-nothing.
- **Back-orders / pre-orders** and **low-stock alerts** to purchasing.
- **Business dashboard**: average order value, top sellers, **gross margin per product** — the data is already there (cost is snapshotted on every order line), it just isn't reported.
- **Back office (Phase 3 already plans)**: supplier bills, AP payments, 3-way match (PO ↔ goods receipt ↔ supplier invoice), multi-warehouse.

### 5.3 AI-agent (MCP) differentiators — this is where the project can stand out

| Feature | What it does |
|---|---|
| **Spending guardrails per API key** | Max order value, max orders per day, allowed categories. A key handed to an agent can't run up a $5,000 bill. |
| **Quote → confirm two-step for write tools** | `quoteCart` returns a short-lived confirmation token tied to the exact total; `placeOrder` requires it. Fixes M1 and gives a natural human-approval point. |
| **Human-in-the-loop approval** | Agent-placed orders above a threshold wait in `PENDING_APPROVAL` until the human taps "approve". |
| **"What did my agent do?" view** | Customer-facing activity feed filtered by `apiKeyId` — the audit data already captures it. |
| **Idempotency on every agent-callable write** | See M5; agents retry a lot. |
| **Webhooks / event stream** | Lets an agent react to "order shipped" instead of polling. |
| **Natural-language product search** | Embeddings over the catalogue for "a waterproof jacket under $100". Fits the RAG practice track. |

---

## 6. Risk matrix (top items)

| Risk | Likelihood | Impact | Covered by |
|---|:-:|:-:|---|
| First build/run surfaces many bugs | High | High | H1 |
| Stock hoarding by unpaid orders | Medium | High | H2 |
| Account persistence after password reset | Low | High | H3 |
| MCP traffic throttled as a single client | High (once MCP exists) | Medium | H4 |
| Customer lockout abuse | Medium | Medium | H5 |
| Audit finding on admin SoD / editable audit log | Medium | Medium | H6, M8 |
| Agent places order at wrong price | Medium | Medium | M1 |

---

## 7. Recommended roadmap

Following the project's one-phase-at-a-time approach:

| Phase | Goal | Contents | Exit criteria |
|---|---|---|---|
| **0 — Make it real** | Prove what exists | Install JDK 21 + Maven; compile; fix build errors; start against Postgres; Testcontainers tests for checkout concurrency, idempotency, SoD, trial balance; GitHub Actions CI | Green build, ≥ 1 test per money path, app boots and Flyway applies V1+V2 |
| **1 — Harden** | Close the high-risk gaps | H2–H6, M3, M6, M8; stale docs (L1, L2) | Each fix has a regression test |
| **2 — Get paid** | Real money flow | Stripe + webhook (SYSTEM actor, L6), order expiry job, deferred revenue (M2), stock reservation model, transactional emails, password reset | End-to-end test: browse → pay via Stripe test mode → ship → return → refund, ledger balanced |
| **3 — Storefront** | Something customers want to use | Images/variants, shipping + tracking, address book, discounts, full-text search | — |
| **4 — Agent-ready** | MCP server | Per-key guardrails, quote→confirm, idempotency everywhere (M5), per-principal rate limits (H4), activity view, webhooks | Agent can shop safely with a capped key |
| **5 — Scale & insight** | Run it for real | Outbox/events, Redis, metrics/tracing, dashboards, cleanup jobs, multi-warehouse, AP | — |

---

## 8. Resolution log (Phase 2.1, 2026-10-13)

Fixed in code only. **Nothing was installed, compiled or run** (by request), so "fixed" means implemented
and statically checked (syntax, imports, module cycles), not yet proven by a build.

| # | Status | What changed |
|---|---|---|
| H1 | ⚠️ Partly | 8 unit-test classes added under `src/test` (stock reservation and costing, tax rounding, money formatting, order rules, PO rules, login throttle, rate limiter, ledger balancing). Dockerfile + GitHub Actions CI added. **Not done:** installing JDK/Maven, compiling, running tests, Testcontainers. Needs your go-ahead to install |
| H2 | ✅ | Reservation model (`products.reserved`, available = on hand − reserved); unpaid orders get `expiresAt` (30 min) and `OrderExpiryJob` cancels them every minute (`FOR UPDATE SKIP LOCKED`, multi-instance safe); max 3 open unpaid orders per user; zero-total orders settle straight to PAID with a zero invoice |
| H3 | ✅ | Password change, `revokeAllMyCredentials` and new admin `adminRevokeUserCredentials` revoke refresh tokens, **all API keys** (via `CredentialsRevokedEvent`) and live access tokens (token version) |
| H4 | ✅ | `IpRateLimitFilter` (pre-auth flood guard) + `PrincipalRateLimitFilter` (per user / per API key, after auth). Still in-memory, so Redis is needed before more than one instance |
| H5 | ✅ | Account lockout removed (V3 drops its columns). `LoginThrottle` blocks only the (account, IP) pair; every failure returns the same generic 401 |
| H6 | ✅ | SoD per person, regardless of role: return approver ≠ receiver ≠ refunder ≠ customer (code + DB CHECKs); PO receiver ≠ creator/approver; payment recorder ≠ order owner |
| M1 | ✅ | `placeOrder` requires `expectedTotalMinor`; mismatch → `409 PRICE_CHANGED` with the actual total. `quoteCart` documented as the confirm step |
| M2 | ✅ | Payment credits **2200 Customer deposits**; revenue + COGS posted at shipment; stock leaves on hand at shipment, so ledger inventory = physical stock |
| M3 | ✅ | `requestReturn` locks the order row (`lockByIdAndUserId`) before the already-returned check |
| M4 | ✅ | Refresh tokens store `revoke_reason`; a ROTATED token reused within 30 s gets a fresh pair instead of a theft alarm |
| M5 | ✅ | Shared `idempotency_records` table + `IdempotencyService`; required on `placeOrder`, `createPurchaseOrder`, `requestReturn` (+ receipts, which already had it); optional on `createApiKey` (replay omits the secret) |
| M6 | ✅ | `receivePurchaseOrder` locks the PO row first (`lockById`), then products |
| M7 | ✅ | `tv` claim + `TokenStateFilter` + 15 s `UserTokenStateCache` (evicted after commit): disabled/demoted users lose access within seconds |
| M8 | ✅ | `audit_events` gets the same immutability trigger as the ledgers |
| M9 | ✅ | Audited: 403s (scope and SoD), rejected API keys, rate-limit hits (≤ 1/min per bucket). Search: any combination of action, actor, actor type, API key, target, date range |
| M10 | ✅ | Hourly cleanup of expired refresh tokens (7-day forensic window) and idempotency records (30 days), guarded by a Postgres advisory lock. Audit rows are kept (immutable); archiving/partitioning is still to do |
| M11 | ✅ | `listInvoices` / `listPayments` batch-load (no N+1); valuation and reorder suggestions are paged, with totals from one aggregate query |
| M12 | ✅ | Business modules are now acyclic. Tax got its own package; reorder logic moved to purchasing; catalog, inventory, purchasing and order publish events and `finance.AccountingListener` posts the entries in the same transaction. Platform modules (security/user/apikey/audit) still reference each other; that's a possible follow-up, not in this report |
| L1 | ✅ | SPEC.md rewritten from the code (stale `adjustProductStock` row removed, all packages listed, new endpoints and breaking changes listed). Generating the table from `/v3/api-docs` in CI needs a running build |
| L2 | ✅ | `OrderStatus` comment updated |
| L3 | ✅ | Checkout fingerprint = full request incl. `expectedTotalMinor`, so a changed price can't silently replay the old order. The cart itself can't be in the hash because checkout empties it |
| L4 | ✅ | Inactive suppliers are skipped and returned in `skippedInactiveSupplierIds` |
| L5 | ✅ | Swagger/OpenAPI off by default (`API_DOCS_ENABLED=false`) |
| L6 | ✅ | Seeded, disabled SYSTEM user + `SystemActor`; the expiry job runs as SYSTEM, and a payment webhook can do the same |
| L7 | ✅ | `Money` uses `Currency.getDefaultFractionDigits()` |
| L8 | ✅ | `Dockerfile` (multi-stage, non-root, healthcheck), compose `app` profile, `.github/workflows/backend-ci.yml` |

**Not addressed (new features, not defects):** §5 product gaps (payment provider, shipping and tracking,
emails, address book, images and variants, discounts) and §5.3 agent extras beyond quote→confirm and
idempotency (per-key spending limits, human approval, activity view, webhooks, semantic search).

---

## 9. Senior review of Phase 2.1 fixes (2026-10-13, review session e-com-f1)

Reviewed the code behind the Resolution log, not just the log. **Overall: strong work — most fixes are approved.**
Four need changes before Phase 0 sign-off, and H1 (nothing built or run) is still the blocker.

### Verdict per finding

| # | Verdict | Notes |
|---|---|---|
| H1 | ❌ **Still open** | 28 unit tests on pure logic only (money, tax, throttle, status rules). No Testcontainers, so locking, migrations, idempotency, DB CHECKs and event listeners are untested. `.github/workflows/backend-ci.yml` exists, but `~/E-Com` is **not a git repo**, so CI has never run. |
| H2 | 🔶 **Changes requested** | Reservation model and expiry are right. Fix R1, R2, R3 below. |
| H3 | ✅ Approved | API keys + token version revoked on password change / revoke-all. |
| H4 | ✅ Approved | Per-IP pre-auth + per-principal post-auth buckets. Redis still needed before more than one instance (already noted). |
| H5 | ✅ Approved, follow-up | Per (account, IP) throttle + generic errors is right. Follow-up R5: no per-account ceiling across IPs. |
| H6 | ✅ Approved | Per-person SoD in code + DB CHECKs. |
| M1 | ✅ Approved | `expectedTotalMinor` + `PRICE_CHANGED`. **Breaking change** — every client must quote first. |
| M2 | 🔶 **Changes requested** | Deposits → revenue at shipment is correct. Cost basis drifts (R4). |
| M3, M4, M6, M8, M9, M10, M11 | ✅ Approved | — |
| M5 | ✅ Approved | Shared idempotency table; concurrent duplicate → 409 retryable → retry replays. |
| M7 | ✅ Approved | Known harmless race: a reload just before commit can cache the old version for up to 15 s (bounded by TTL). |
| M12 | ✅ Approved | Synchronous same-transaction listeners keep the books consistent. |
| L1–L8 | ✅ Approved | V3 says "data-safe", but PAID orders from the old model would book revenue twice at shipment. Harmless because no real data exists — fix the comment or the migration before anyone runs V2 on real data. |

### Change requests

| ID | Severity | Problem | Fix |
|---|---|---|---|
| **R1** | High | **Deadlock risk in order expiry.** `expireOverdue` locks up to 100 orders, then each order's products, all in one transaction. Product locks across the batch are not in one global order, so it can deadlock with checkout (which locks products sorted by id). Also, customer cancel locks products → order, while expiry locks order → products. | Expire **one order per transaction** (or collect all product ids for the batch and lock them once, sorted). Use one lock order everywhere: **order row first, then products sorted by id**. |
| **R2** | High | **Lock errors become 500s.** `GlobalExceptionHandler` maps optimistic-lock and integrity errors to 409 but not `PessimisticLockingFailureException` / `CannotAcquireLockException` (deadlock loser, lock timeout). | Map them to `409 CONFLICT, retryable: true`. |
| **R3** | High (Phase 3 blocker) | **Payment vs expiry race and late payments.** `recordPayment` loads the order without a row lock, so `SKIP LOCKED` in the expiry job doesn't actually skip "orders a payment is updating" (comment is wrong); the loser gets a 409 and then "not awaiting payment". Once Stripe webhooks arrive, a customer can be charged for an order that just expired. | `recordPayment` locks the order row first. Define the product rule for late payments: re-reserve stock if available, otherwise auto-refund, plus an `ORDER_EXPIRED_PAYMENT_REFUNDED` audit event. |
| **R4** | Medium | **Inventory value drifts between the ledger and the stock records.** COGS at shipment uses the cost snapshotted at *checkout* (`OrderItem.unitCostMinor`); the SALE movement uses the moving-average cost at *shipment*. A goods receipt in between changes the average, so GL Inventory ≠ Σ stock × cost. | Take COGS from the cost at shipment (the SALE movement's `unitCostMinor`), store it on the order item, and use the same cost when a return is received. Add a test: GL account 1200 == inventory valuation after receipt → checkout → receipt → ship. |
| **R5** | Medium | Credential stuffing from many IPs against **one** account is unlimited (throttle key is account+IP only). | Add a per-account soft ceiling across IPs (e.g. 20 failures/hour → step-up such as CAPTCHA or email code), still with the generic error. |
| **R6** | Low | `maxOpenUnpaidPerUser` is a count-then-insert, so concurrent checkouts can go over the cap. | Lock the user's cart row at the start of checkout (also removes the stale-cart path). |

### Phase 0 exit criteria (unchanged, now more important)

1. `git init` (repo-local identity) so CI can run.
2. Install JDK 21 + Maven; `mvn verify` green.
3. Testcontainers integration tests: concurrent last-item checkout; expiry vs payment vs cancel (R1–R3); idempotent replay + concurrent duplicate; every SoD rule incl. DB CHECKs; ledger always balanced and GL inventory == valuation (R4); V1→V3 migration on a seeded DB.

### Handover decisions (2026-10-13)

- Implementer found a **startup bug** (`LoginThrottle` has two constructors, so Spring can't create it) → fixed under R5.
- Filter order: use the deterministic anchors (after `BearerTokenAuthenticationFilter`, before `AuthorizationFilter`).
- Added to the batch: **R7** `adjustStock` idempotency (a retry currently double-adjusts) · **R8** throttle ACCESS_DENIED / API_KEY_REJECTED audit writes (the audit table is immutable, so floods are permanent) · **R9** evict the token cache when a user is re-enabled.
- Accepted as known gaps: in-memory limits, advisory-lock jobs, synchronous events, fingerprint drift across deploys, V3 assumptions (no real data).
- **Open product decision:** strict three-person SoD can't be used by a 1–2 person shop.
- **Decided (Isuru): SoD policy flag → R10.** `app.sod.mode = strict` (default) | `small-team`. In small-team mode one person may do several steps if they give a reason, audited as `SOD_OVERRIDDEN`. Hard blocks in every mode: staff processing their own return, and recording payment for their own order. Done in a new V4 migration.

---

## 10. Phase 2.2 resolution (implementer session, 2026-10-13)

Code only. **Nothing installed, built, run or committed** (no git). Tests below are source files that have never run.

| # | Status | What changed | Tests (written, not run) |
|---|---|---|---|
| R1 | ✅ | One lock order everywhere: cart → order → return → products sorted (`order/LockOrder` documents it). Customer cancel, staff cancel, ship, recordPayment lock the order row first (`lockById`, `lockByIdAndUserId`). Expiry: candidates read without locks, then **one order per transaction** via `claimForExpiry` (`FOR UPDATE SKIP LOCKED`); a failing order is logged and retried next run instead of blocking the rest. Return receive locks return → products; refund locks order → return | `OrderLockingIT.expiryCancellationAndCheckoutNeverDeadlock` |
| R2 | ✅ | `GlobalExceptionHandler.handleLockConflict` maps `PessimisticLockingFailureException`, `CannotAcquireLockException`, JPA `PessimisticLockException` / `LockTimeoutException` / `OptimisticLockException` to `409 CONFLICT, retryable: true`. Added Hikari `connection-init-sql: SET lock_timeout = '5s'` so waits end in that error instead of hanging | `GlobalExceptionHandlerTest`; `OrderLockingIT.aLockWaitLongerThanTheLockTimeoutFailsAsARetryableLockError` |
| R3 | ✅ | `recordPayment` locks the order row first (the SKIP LOCKED comment is now true). `orders.cancel_reason` (V4); expired orders: re-reserve all lines if possible → PAID (`APPLIED_AFTER_REINSTATEMENT`, audit `ORDER_REINSTATED_AFTER_LATE_PAYMENT`), else record + full refund (`REFUNDED_ORDER_EXPIRED`, credit note, audit `ORDER_EXPIRED_PAYMENT_REFUNDED`). `payments.outcome` stored; `PaymentResponse` gains outcome/refund fields. Customer/staff-cancelled orders still reject payments | `LatePaymentIT` (5 cases) |
| R4 | ✅ differently | The literal fix (cost at shipment × qty with a rounded average) can't make GL 1200 == Σ stock × cost hold: rounding drifts by up to stock/2 cents per product. Implemented an **exact value pool** (`products.stock_value_minor`, `stock_movements.value_minor`): every movement's value is exactly its GL 1200 posting; COGS = value that left at shipment (`order_items.shipped_cost_minor`); returns restock their share of it (`return_lines.restock_value_minor`, last return takes the remainder) | `ProductStockTest`, `ReturnRestockValueTest`; `InventoryValuationIT` (GL 1200 == valuation after receipt → checkout → receipt → ship → return → refund, trial balance balanced) |
| R5 | ✅ | Per-account ceiling across IPs (20 failures/hour, configurable): afterwards only IPs that logged into that account in the last 30 days may try; same generic 401; audited once as `LOGIN_ACCOUNT_CEILING_REACHED`. Counters keyed by email whether or not the account exists. **Also fixed (a):** `LoginThrottle` startup failure (`@Autowired` on the props constructor) | `LoginThrottleTest`; `LoginCeilingIT` |
| R6 | ✅ | Checkout locks the user's cart row first, then does the replay check, cap check and cart read, so the cap is race-free, the cart can't be stale, and concurrent duplicates **replay** instead of failing | `OrderLockingIT` (duplicate replay, different keys, cap) |
| R7 | ✅ | `adjustStock` requires `Idempotency-Key` (shared `IdempotencyService`); product row locked before the replay check, so concurrent duplicates replay | `StockAdjustmentIdempotencyIT` |
| R8 | ✅ | `AuditService.recordThrottled` (+ `AuditThrottle`): `ACCESS_DENIED` (per caller), `API_KEY_REJECTED` (per IP) and `RATE_LIMITED` (per bucket) write at most one row/min with `occurrences=N` | `AuditThrottleTest`; `RepositoryQueriesIT.throttledSecurityAuditsWriteOneRowPerMinute` |
| R9 | ✅ | Re-enabling a user evicts the token-state cache after commit | `TokenStateCacheIT` |
| R10 | ✅ | `app.sod.mode` strict (default) / small-team via `SodPolicy`: conflicting steps (PO self-approval, PO receive by creator/approver, return receive by approver, refund by approver/receiver) need a `sodOverrideReason` in small-team mode and are audited `SOD_OVERRIDDEN`; without one → 403 with `overrideAllowed: true`. Hard in every mode: own order's payment, own return. V4 drops `chk_po_approver_differs` and `chk_return_receiver_differs`, keeps `chk_return_staff_not_customer`; V3 untouched. SPEC documents the policy and the `?action=SOD_OVERRIDDEN` review path | `SodPolicyTest`; `SodStrictIT`, `SodSmallTeamIT` |
| R11 | ✅ | Isuru's decision: `recordPayment` on an order CANCELLED by the customer or staff (or legacy NULL reason) records the payment as `REFUNDED_ORDER_CANCELLED` and refunds it in full in the same transaction (credit note, audit `ORDER_CANCELLED_PAYMENT_REFUNDED`); the order stays CANCELLED and is never reinstated. The outcome choice is one pure function (`FinanceService.selectOutcome`). V5 replaces the `payments.outcome` CHECK (old one found by definition, not a guessed name); V4 untouched. NOTES.md: Flyway inherits the 5 s `lock_timeout`; step-up auth listed for Phase 5 | `PaymentOutcomeSelectionTest`; `CancelledOrderPaymentIT` (customer, staff, legacy NULL, paid-then-cancelled duplicate) |
| (b) | ✅ | Filters anchored to built-ins only: `TokenStateFilter` after `BearerTokenAuthenticationFilter`, `PrincipalRateLimitFilter` before `AuthorizationFilter` | — (boot-time) |
| (c) | ✅ | Explicit `cast(... as Long)` in `stockTotals` and `outstandingByProduct`; `AuthService.revokeEverything()` public; every boot-time-typed query exercised | `RepositoryQueriesIT` |

pom.xml: added `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:postgresql` (test scope,
Boot-managed versions) and `maven-failsafe-plugin` (`*IT` run in `mvn verify`). Declared only, not downloaded.

**Still open:** H1 (build + run). The Phase 0 exit criteria in §9 stand, except git: none was run here (on hold per Isuru).

## 11. Senior review of Phase 2.2 (2026-10-13, e-com-f1)

**Verdict: approved.** I read the code for the riskiest parts: V4 migration, lock order, expiry, late payment, value pool.

- **R1** ✅ One lock order everywhere (cart → order → return → products sorted by id); expiry runs one order per transaction and uses `SKIP LOCKED`.
- **R2** ✅ Lock failures → 409 retryable; `lock_timeout = 5s` (also applies to Flyway — noted as a known gap).
- **R3** ✅ `recordPayment` locks the order first; expired orders are reinstated if stock allows, otherwise refunded automatically.
- **R4** ✅ (done differently, better) An exact value pool per product, so GL 1200 == Σ stock value with no rounding drift.
- **R5** ✅ Per-account ceiling across IPs; real step-up (email/CAPTCHA) deferred to Phase 5.
- **R6–R10** ✅
- **Decided (Isuru):** payments for customer- or staff-cancelled orders are **refunded automatically** → **R11** (V5 migration, outcome `REFUNDED_ORDER_CANCELLED`). A cancelled order is never reinstated.
- **Still blocking:** H1. Nothing has been compiled or run. The 10 integration tests and the query checks are unverified until the install/git hold lifts.
- **R11 reviewed (e-com-f1): ✅ approved.** V5 finds the inline CHECK by its definition and replaces it (V4 untouched). Outcome selection is one pure function, and cancelled orders are never reinstated. The payment and its full refund (Dr Deposits) post in one transaction, so the books stay balanced.
  **Phase 3 note:** a *second* payment on the same order is still rejected with 409. With card webhooks that money exists at the provider, so the webhook handler must refund it there (provider-side refund + audit).
- **Phase 2 code work is closed.** Next is Phase 0 / H1 (git, toolchain, `mvn verify`, run the 11 integration tests) — **on hold until Isuru lifts it.**

## 12. Review of Phase 4.1 agent guardrails (e-com-fe, 2026-10-14)

> Guardrails review from e-com-fe: the design is good (no new order status, payment blocked while PENDING, reject
> via the normal cancel path, REQUIRES_NEW audit is a real catch). Two fixes, and one product question that is
> Isuru's call.
>
> **A1 (Medium)** Approval state outlives the order. An agent order awaiting approval still expires after 30 min (or
> gets cancelled), but approval_state stays PENDING, so:
> - it stays in the owner's approval queue;
> - approveAgentOrder still accepts it (sets APPROVED on a CANCELLED order);
> - recordPayment then refuses it as "waiting for approval" instead of following the R3/R11 late-payment rules.
>
> Fix: when an order is cancelled or expires while approval is PENDING, set approval_state to EXPIRED (cancelled by
> expiry) or REJECTED (cancelled by the customer/staff). That needs a V7 that extends the CHECK, not an edit to V6.
> approve/reject must also require status PENDING_PAYMENT; filter the queue on PENDING_PAYMENT too; and run the
> isAwaitingApproval check in recordPayment only for PENDING_PAYMENT orders.
>
> **A2 (Low)** A zero-total order that needs approval is never settled: approval doesn't trigger the zero-total PAID
> path, and payments must be > 0. On approve, if total == 0, settle it the same way checkout does (PAID +
> ZeroTotalOrderSettled).
>
> **A3 (product question, waiting for Isuru; don't build yet)** A human gets only the 30-minute payment window to
> approve. I'll come back with his decision.
>
> Add ITs for A1 and A2 (sources only while the hold applies). Code only.

## 13. Resolution of A1/A2 (e-com-11, 2026-10-14)

| ID | Resolution | Files |
|---|---|---|
| **A1** | `CustomerOrder.cancel(reason, now)` — the one method every cancellation path (customer, staff, expiry) already funnels through — now reconciles `approvalState` away from `PENDING` whenever it cancels an order that was still awaiting approval: `EXPIRED` for `CancelReason.EXPIRED`, `REJECTED` otherwise. An explicit human reject (`decideApproval` called first) is left untouched, since `approvalState` is no longer `PENDING` by the time `cancel()` runs. New `ApprovalState.EXPIRED`; V7 extends V6's `CHECK` by definition (V6 untouched, same pattern as V5). Defence in depth per the review: `lockAwaitingApproval` now also requires `status == PENDING_PAYMENT`; `listAwaitingApproval`'s query filters on it too (new `findByUserIdAndApprovalStateAndStatus`); `recordPayment`'s approval guard only fires `when status == PENDING_PAYMENT`, so a cancelled/expired order falls through to the existing R3/R11 late-payment logic instead of being refused. | `order/ApprovalState.java`, `CustomerOrder.java`, `OrderRepository.java`, `OrderService.java`, `finance/FinanceService.java`, `resources/db/migration/V7__approval_state_expiry.sql` |
| **A2** | `approveAgentOrder` now settles a zero-total order (PAID + `ZeroTotalOrderSettled`) the same way checkout's own H2 path does, instead of leaving it `APPROVED` but permanently unpayable (`recordPayment` requires a positive amount, so there was no other way to settle it). Checked first whether this is actually reachable: `requireApprovalAboveMinor` is `@PositiveOrZero`, so `0 > requireApprovalAboveMinor` can never be true for a non-negative threshold — a genuinely free order cannot trigger approval via checkout today. Said so rather than silently treating it as a live bug; built and tested it anyway as closing the state machine, since the fix is one `if` and the cost of leaving the gap is a future change (e.g. a negative-threshold edge case, or approval triggered a different way later) silently reintroducing exactly this. | `order/OrderService.java` |
| **A3** | Not built — waiting on Isuru, as asked. | — |

**Tests** (`AgentOrderApprovalReconciliationIT`, sources only — `mvn verify` not run, see NOTES.md): an order that
expires while `PENDING` reconciles to `EXPIRED`, drops out of the queue, refuses `approveAgentOrder`, and still
reinstates on a late payment (`APPLIED_AFTER_REINSTATEMENT`) — the exact regression A1 describes; a customer
cancelling their own pending-approval order (a real, allowed path neither of us had called out explicitly)
reconciles to `REJECTED`; approving a zero-total order (forced at the row level, since the trigger path itself is
unreachable per the A2 analysis above) settles it to `PAID`.

**One accepted quirk, not a third fix:** `reinstateAsPaid()` doesn't reset `approvalState`, so an order that expired
`PENDING` and was later reinstated by a late payment keeps showing `approvalState: EXPIRED` even though it ended up
`PAID`. Harmless — nothing reads `approvalState` once `status` has moved past `PENDING_PAYMENT` (`isAwaitingApproval()`
and `lockAwaitingApproval`'s guard both already gate on that) — just a historical field, flagged rather than silently
left unexamined.
