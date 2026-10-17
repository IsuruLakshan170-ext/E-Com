# E-Com Frontend — Architecture & Product Review

**Reviewed:** 2026-10-13 by e-com-f1 (senior architect / product lead) · **Built by:** e-com-36
**Scope:** `frontend/` (React 18 + Vite + TypeScript strict, ~7.4k lines, 104 files). Code only: never installed, built or run.

## 1. Verdict

**Approved with changes.** It's a well-structured, dependency-light SPA that uses the backend contract correctly.
Two session-handling issues must be fixed before anyone uses it (F1, F2). The other items are small.

## 2. What I checked and found correct

| Area | Result |
|---|---|
| Request bodies vs Java records | ✅ Field-by-field match for PurchaseOrder(+Line), GoodsReceipt, StockAdjustment, Create/UpdateProduct, CreateApiKey, CreateReturn, RecordPayment, Checkout, ReorderPolicy, Supplier, TaxRate, ChangePassword, SodOverride, ReturnDecision. Approve/reject send `{note}` only and receive/refund send `{sodOverrideReason}` only, matching the controllers. |
| Checkout | ✅ quote → confirm exact total → `placeOrder` with `expectedTotalMinor`; one idempotency key per confirmation, reused on network retry; `PRICE_CHANGED` re-quotes with a new key. A retry after an unseen success can't create a second order (the cart is already empty). |
| Money | ✅ Integer minor units; typed input parsed with string arithmetic; display via `Intl` with per-currency digits. |
| SoD override | ✅ Prompts only when `overrideAllowed === true` (matches `SodPolicy`); hard blocks are shown as errors. |
| Scope → navigation | ✅ Matches the backend's `@PreAuthorize` rules (returns desk = returns:manage / inventory:write / finance:write; inactive products = catalog:write). |
| Refresh single-flight + one 401 retry | ✅ Correct *within one tab* (see F1 for across tabs). Retrying after a 401 is safe because the request never ran. |
| Open-redirect guard | ✅ Rejects absolute, `//host` and `/\host` (hardening in F4). |
| nginx / CSP | ✅ Strict CSP with no inline code, nosniff, XFO, COOP, HSTS, no-store shell, immutable assets. API docs and actuator are not proxied. |

## 3. Change requests

| ID | Severity | Problem | Fix |
|---|---|---|---|
| **F1** | **High** | **Tabs.** (a) "Duplicate tab" copies `sessionStorage`, so two tabs hold the *same* refresh token and refresh independently. Once the backend's 30 s grace window has passed, that looks like token theft → **the user is signed out everywhere**. (b) "Open in new tab" does *not* copy `sessionStorage`, so every new tab asks staff to sign in again, which is painful in a back office where people open orders in tabs. | One session per browser, shared by all tabs: keep the refresh token in `localStorage` (same XSS exposure as `sessionStorage`; only the lifetime differs, and CSP plus no-innerHTML remain the defence), do refreshes under a **Web Lock** (`navigator.locks.request('ecom-refresh', …)`) so only one tab rotates at a time, have the other tabs re-read the new token after the lock, and broadcast sign-out (BroadcastChannel / `storage` event). Add tests for the duplicate-tab and two-tabs-refresh cases. |
| **F2** | **Medium** | **Transient errors sign people out.** `doRefresh` ends the session on *any* non-OK response (429 rate-limited, 500, 503). On reload, an offline refresh also falls through to `tokenStore.clear()`. | End the session only on 401/400 from `/auth/refresh`. On 429/5xx/network keep the session and surface a retryable error (honour `Retry-After`). |
| **F3** | Low | Scopes are read from `whoami` once. After a role change (token version bump → refresh → new scopes) the navigation stays stale until reload. | Re-load `whoami` after every successful refresh triggered by a 401. |
| **F4** | Low | `safeNextPath` accepts control characters (`/\t/evil.com` normalises to `//evil.com` in some URL parsers). nginx overwrites the caller's `X-Request-Id`. | Reject any `next` containing whitespace or control characters. In nginx, forward a valid client `X-Request-Id` (the backend validates it) and fall back to `$request_id`. |
| **F5** | Low | The supplier picker loads only the first 100 active suppliers. | Type-ahead that calls `searchSuppliers` with `q`. |

## 4. Backend gaps raised by the implementer — decisions

Backend code changes, code-only like before:

| ID | Decision | Change |
|---|---|---|
| **BE-1** | ✅ Do | `GET /api/v1/finance/orders?status=PENDING_PAYMENT` (finance:read) with order number, customer, total and expiresAt. Without it, finance can't find what to record a payment for. |
| **BE-2** | ✅ Do | Staff search by order number (`orderNumber` query param on `adminListOrders` and the new finance list). Customer service's first question is "what's your order number?". |
| **BE-3** | ✅ Do | Add `sku` + `productName` to PO lines, goods-receipt lines and stock movements, and `supplierName` to PO responses. This also fixes the inactive-product-name gap (#4) for inventory and purchasing users. |
| **BE-4** | ✅ Do (config) | Behind nginx: `server.forward-headers-strategy=native` **plus an explicit trusted-proxy list** (`server.tomcat.remoteip.internal-proxies`), documented as "only when the backend is reachable solely via the proxy". |
| Gap 6 — httpOnly refresh cookie / BFF | ⏸ Defer | The right end state before a public launch. It belongs with the deployment phase (needs a cookie path, SameSite and CSRF design). F1 is the interim fix. |
| Gap 7 — payment provider | ⏸ Phase 3 | Already on the roadmap. |

## 5. Known gaps accepted

No package-lock (needs the first `npm install`, on hold) · no Playwright E2E · no product images · English only ·
no full accessibility audit yet. All stay in NOTES.md.

## 6. Exit criteria (when the install hold lifts)

`npm install` → commit the lockfile → `npm run typecheck` (expect a few strict-mode fixes) → `npm test` → `npm run build` →
smoke-test against the running backend: register, buy, pay (finance user), ship, return, refund, plus the duplicate-tab test from F1.

## 7. Resolution (e-com-36, 2026-10-13)

Code only, as before: nothing installed, built, run or committed. All changes passed the static checks (frontend: imports
resolve, named imports exported, JSX components and hooks imported, no unused locals; backend: balanced syntax, no
unused imports, every project type imported, constructor arity matches every record).

| ID | Resolution | Files | Tests (written, not run) |
|---|---|---|---|
| **F1** | One session per browser in localStorage: `{ sid, accessToken, accessExpiresAt, refreshToken, after401 }`; `sid` identifies one sign-in, so tabs can tell a rotation from a new sign-in. Refresh under the `ecom-refresh` Web Lock; after the lock the tab re-reads storage and **adopts** a pair another tab already rotated (proactive: if it is fresh; after a 401: if it differs from the rejected token). Writes are conditional on the same sign-in, so a late refresh can never overwrite or end a newer sign-in. Sign-in / sign-out / expiry reach other tabs through the `storage` event; a tombstone `{ ended: reason }` carries the reason ("You signed out in another tab"). Without Web Locks: per-tab single-flight plus the backend's 30 s grace | `auth/tokenStore.ts`, `api/client.ts`, `auth/AuthContext.tsx` | `auth/tabs.test.ts`: new/duplicated tab joins the session; two tabs refreshing at once → one rotation; 401 in tab B adopts tab A's pair; a duplicated tab later refreshes with the newest token; sign-out and sign-in propagate; late success / late refusal never touch a newer sign-in |
| **F2** | Only 400/401 from `/auth/refresh` end the session. 429/5xx/network/timeout (15 s) throw a retryable `ApiError` and keep the session; `Retry-After` pauses refresh attempts (cleared on success). A request that needed the refresh fails with that error instead of being sent unsigned. Boot/resume no longer clears anything: new status `unavailable` with "still signed in" + Try again + Sign out, the header shows Reconnect, and it retries itself on the `online` event | `api/client.ts`, `auth/AuthContext.tsx`, `auth/SessionUnavailable.tsx`, `auth/guards.tsx`, `components/layout/AppShell.tsx` | `api/client.test.ts`: ends on 401 and 400; kept on 503; kept offline (request not sent); 429 + Retry-After pauses, then retries |
| **F3** | After a 401 is fixed by a refresh or an adoption, the client emits `scopes-stale` and the AuthProvider re-reads `whoami` (one reload at a time, so a misbehaving server can't make it loop). Other tabs re-read when the shared record says the rotation answered a 401 | `api/client.ts`, `auth/tokenStore.ts`, `auth/AuthContext.tsx` | `api/client.test.ts`: 401 → refresh → retry → `scopes-stale` |
| **F4** | `safeNextPath` rejects any whitespace or control character (`\s`, C0, DEL, C1). nginx `map`: forwards the caller's `X-Request-Id` only if it matches the backend's `^[A-Za-z0-9._-]{8,64}$`, else `$request_id` | `lib/validation.ts`, `nginx/default.conf` | `lib/validation.test.ts`: tab, newline, CR, space, NUL, NBSP, U+2028; percent-encoded paths still accepted |
| **F5** | `SupplierPicker`: debounced type-ahead over `searchSuppliers?q=` (first 8 shown before typing); loads the current supplier's name once when the value came from the server; Change / Clear. `Field group` renders a labelled `role="group"` for composite controls (a `<label>` may hold one control only) | `components/domain/SupplierPicker.tsx` (replaces `SupplierSelect.tsx`), `components/ui/Field.tsx`, `pages/backoffice/inventory/StockPage.tsx`, `pages/backoffice/purchasing/PurchaseOrderFormPage.tsx` | — (UI) |
| **BE-1** | `GET /api/v1/finance/orders` = `financeListOrders` (finance:read) in a small read-only `FinanceOrderQueries`: number, status, cancel reason, customer id/email/name (a `UserSummary` projection, one query per page), total, `expiresAt`, `paidAt`. No address or items. Status defaults to PENDING_PAYMENT unless an order number is given. Wired into Record payment (queue → pick → form with what will happen for expired/cancelled/paid orders) and the dashboard's Finance card | `finance/FinanceOrderQueries.java`, `FinanceController.java`, `FinanceDtos.java`, `user/UserSummary.java`, `UserRepository.java`; UI `RecordPaymentPage.tsx`, `DashboardPage.tsx`, `AdminOrderPage.tsx` (`?orderNumber=` shortcut) | `it/BackOfficeLookupsIT`: queue shows the unpaid order with customer, total, deadline |
| **BE-2** | `orderNumber` on `adminListOrders` and `financeListOrders`: trimmed, upper-cased, exact match on the unique index (`@Pattern ^[A-Za-z0-9-]{1,32}$`). `OrderService.search` is shared by both lists | `order/OrderNumbers.java`, `OrderRepository.java`, `OrderService.java`, `AdminOrderController.java`; UI `AdminOrdersPage.tsx`, `RecordPaymentPage.tsx` | `it/BackOfficeLookupsIT`: lower-case number finds a PAID order in finance and admin; status filters still apply |
| **BE-3** | `sku` + `productName` on PO lines, goods-receipt lines and stock movements; `supplierName` on PO responses; plus `preferredSupplierName` on reorder suggestions (small extension so the UI could drop its last lookup). One batched query per page via `ProductRepository.labelsById` (chunks of 500), hidden products included. UI per-id lookups removed (`names.ts` deleted) | `catalog/ProductLabel.java`, `ProductRepository.java`, `purchasing/PurchasingDtos.java`, `PurchasingService.java`, `ReplenishmentService.java`, `inventory/InventoryDtos.java`, `InventoryService.java`; UI `api/types.ts`, `PurchaseOrderPage.tsx`, `PurchaseOrdersPage.tsx`, `PurchaseOrderFormPage.tsx`, `MovementsPage.tsx`, `ReorderSuggestionsPage.tsx` | `it/BackOfficeLookupsIT`: names on PO, receipt and movements after the product is hidden; supplier name on suggestions |
| **BE-4** | Opt-in profile `behind-proxy`: `server.forward-headers-strategy: native` + `server.tomcat.remoteip.internal-proxies: ${TRUSTED_PROXIES}` with **no default** (startup fails until set), because trusting Tomcat's private-range default would also trust clients on those networks and nginx appends to the `X-Forwarded-For` it receives. Documented in backend SPEC (Run, Security model) and NOTES, and in the nginx comment | `resources/application-behind-proxy.yml`, `common/web/ClientIp.java` (comment), `nginx/default.conf` | — (config) |
| Gap 6 / Gap 7 | Kept as deferred in `frontend/NOTES.md` (httpOnly cookie / BFF → deployment phase; payment provider → Phase 3) | `NOTES.md` | — |

**Small extras found on the way (please glance at them):**
- `PurchaseOrderFormPage`: editing the expected date or notes, or removing a line, now renews the idempotency key like
  every other body change (before, a retry after such an edit could hit `IDEMPOTENCY_KEY_REUSED`).
- Pressing Enter in a picker's search box no longer submits the surrounding form (product picker on the PO form).
- Cart and checkout show the load error with "Try again" instead of spinning forever when the cart can't be loaded.
- Removed four unused imports in `FinanceDtos.java` / `FinanceController.java` (no behaviour change).
- Docs: `frontend/SPEC.md`, `frontend/NOTES.md`, `backend/SPEC.md` (F1-BE phase row, API table, Security model,
  MCP contract point 9, "Changes from the frontend review"), `backend/NOTES.md`.

## 8. Round 2 verdict (e-com-f1, 2026-10-14)

**Approved.** Cross-tab session (F1), error handling (F2) and BE-1–BE-4 reviewed in code. All four judgment calls accepted.
**New — F6 (Medium, decided by Isuru):** a "Keep me signed in on this device" checkbox, **off by default**. When off, the session
ends 30 minutes after the last open tab closes (lastSeen heartbeat + server logout). **Staff always get the 30-minute rule.**
Optional: after a network error or timeout during a refresh, auto-retry once within ~5 s (keeps it inside the backend's 30 s grace window).
