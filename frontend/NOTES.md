# Frontend NOTES — decisions, backend gaps, known gaps

## Decisions (Phase 4.1 — agent guardrails, backend/REVIEW.md §5.3, 2026-10-14)
- **New pages:** `account/approvals` (approve/reject queue, `PendingApprovalsPage`), `account/api-keys/:id/activity`
  (`ApiKeyActivityPage`, reuses the audit-event shape `AuditPage` already has types for). `ApiKeysPage`'s create
  form gained three optional limit inputs (blank = no limit, matching the backend's `null`); its table gained a
  `LimitsSummary` cell and an "Activity" link per key. `MyOrderPage` shows an approval banner and
  approve/reject buttons driven by `allowedActions`, the same pattern every other order action already uses.
- **Verified for real** (not just written): `tsc --noEmit` 0 errors, all 42 existing tests still pass, production
  build still succeeds — all on the first try. No new frontend tests added: this phase has no new pure-function
  logic worth isolating (unlike `lib/validation.ts` etc.); the real coverage is the backend's own
  `AgentOrderGuardrailsIT` plus the type-check holding the API contract together end to end.

## Decisions
- **React + Vite + TypeScript**: the most common, best-documented SPA stack. Minimal dependencies:
  react, react-dom, react-router-dom (+ dev: vite, typescript, vitest, testing-library). No UI kit, CSS framework,
  state library or data-fetching library: fewer moving parts, nothing third-party at runtime, strict CSP stays easy.
- **One SPA for shop and back office**, gated by scopes from `whoami`. Simpler to deploy; the bundle is small enough.
  Split it later (or lazy-load the back office) if the storefront needs to be leaner.
- **Same origin everywhere** (Vite proxy in dev, nginx in prod), so `connect-src 'self'` and no CORS.
- **One session per browser in localStorage (F1, replaces the first per-tab sessionStorage design)**. Per-tab storage
  broke in two ways: "duplicate tab" copied the refresh token, so two tabs rotated it independently and the backend's
  theft detection signed the user out everywhere; "open in new tab" started signed out. Now:
  - the record holds `sid` (one sign-in; unchanged by refreshes) plus the token pair; a tombstone `{ ended: reason }`
    tells other tabs why a session ended;
  - refreshes run under a Web Lock (`ecom-refresh`); after the lock a tab re-reads storage and adopts a pair another
    tab already rotated (without Web Locks: per-tab single-flight plus the backend's 30 s grace);
  - writes are conditional: a refresh result is stored, and a refused refresh ends the session, only while storage
    still holds the same sign-in, so a late answer can never overwrite or end a newer sign-in made in another tab;
  - the access token is stored next to the refresh token: anything that can read one can read the other.
  XSS exposure is the same as sessionStorage (only the lifetime differs); strict CSP plus React's escaping stay the
  defence. The end state is an httpOnly refresh cookie / BFF (deferred, below).
- **Transient refresh failures keep the session (F2)**: only 400/401 from `/auth/refresh` end it. 429/5xx/offline throw a
  retryable error, `Retry-After` pauses further refresh attempts, and the AuthProvider shows a "still signed in, can't
  reach the store" state with a retry that also fires on the browser's `online` event.
- **Scopes follow role changes (F3)**: after a 401 is fixed by a refresh (or adoption), `whoami` is re-read, at most
  one reload at a time, so it can never loop. Other tabs re-read theirs when the shared record says the last rotation
  answered a 401.
- **Types hand-written from the Java records** (verified field by field); replace with types generated from
  `/v3/api-docs` (e.g. openapi-typescript) once the backend can run.

## Backend gaps found while building the UI: outcome of the review (frontend/REVIEW.md)
1. Finance could not find orders to pay → **done (BE-1)**: `financeListOrders` is the payment queue on the Record payment
   screen; the admin order page's shortcut opens it with `?orderNumber=`.
2. No staff search by order number → **done (BE-2)**: on the admin order list and the finance queue.
3. Ids without names on purchase orders, deliveries and stock movements → **done (BE-3)**; reorder suggestions also
   carry `preferredSupplierName`. The per-id lookup cache (`names.ts`) is gone.
4. Hidden product names were only visible with `catalog:write` → **done** (BE-3 includes hidden products).
5. Supplier picker limited to the first 100 → **done (F5)**: type-ahead over `searchSuppliers?q=`.
6. Tokens in JSON, not httpOnly cookies → **deferred to the deployment phase** (needs a cookie path, SameSite and CSRF
   design). F1 is the interim fix.
7. No payment provider: customers pay quoting the order number, finance records it → **Phase 3**.
8. Client IPs behind nginx → **done (BE-4)**: the backend's `behind-proxy` profile plus `TRUSTED_PROXIES`; see the
   comment in `nginx/default.conf`.

## Known gaps / next
- **Installed, type-checked, tested (42/42) and built on 2026-10-14.** The only fix needed: a navigation test expected
  finance to have no Sales section, but finance rightly sees the Returns desk (it refunds returns); the test was wrong.
  `package-lock.json` now exists (commit it when git is allowed); CI uses `npm ci`.
- npm 11 skipped esbuild's postinstall script (not in `allowScripts`); harmless here because the platform binary
  `@esbuild/darwin-arm64` comes as an optional dependency. Run `npm install-scripts approve esbuild` if a build ever
  complains.
- Not yet run against the live backend: the review's smoke test (register, buy, pay as finance, ship, return, refund,
  two-tab session) is next once the backend runs.
- No end-to-end tests (Playwright) yet: the obvious next step once both apps run, starting with the review's smoke test
  (register, buy, pay as finance, ship, return, refund) and a real two-tab session test.
- No product images or variants, address book, wishlist, discounts, emails (the backend has none either).
- Accessibility: labelled controls (composite pickers are labelled groups), focus-managed dialogs (Escape closes, focus
  returns), live-region toasts and field errors, and the only animation (the loading spinner) slows down under
  reduced motion. A full audit (axe, screen reader pass) is still to do.
- i18n: English only; numbers, dates and money already use the browser locale.
