# E-Com Frontend — SPEC (Phase F1, with the review changes F1–F5 / BE-1–BE-4)

Storefront and back office for the E-Com backend, in **React 18 + TypeScript (strict) + Vite**. One single-page app
serves customers (shop, cart, checkout, orders, returns, API keys) and staff (sales, catalog, inventory, purchasing,
finance, administration). What each person sees follows the **scopes** the backend grants their role.

> **Verified 2026-10-14:** `npm install` (created `package-lock.json`), `npm run typecheck` (strict, 0 errors),
> `npm test` (42/42) and `npm run build` (308 kB JS / 91 kB gzip; `index.html` has no inline script or style, so the
> strict CSP holds). Not yet run against the live backend (smoke test pending).

## Run

```bash
cd frontend
npm install            # first time; commit the generated package-lock.json, then CI can use npm ci
npm run dev            # http://localhost:3000, /api proxied to the backend on :8080
npm run typecheck      # tsc --noEmit (strict)
npm test               # Vitest unit + component tests
npm run build          # production bundle in dist/
docker build -t ecom-frontend .   # nginx (unprivileged) serving dist/ and proxying /api to http://backend:8080
```

Port 3000 is already in the backend's CORS allow-list, but the dev proxy and nginx make every call **same-origin**, so
CORS is never needed in practice.

## Structure

```
src/
├── api/          client.ts (fetch, auth header, cross-tab refresh under a Web Lock + one 401 retry, idempotency header),
│                 endpoints.ts (one typed function per backend operation), types.ts (DTO mirrors), errors.ts
├── auth/         tokenStore (one session per browser in localStorage, synced across tabs), AuthContext, route guards,
│                 SessionUnavailable (offline / server down: still signed in, retry)
├── cart/         CartContext (header badge, add/update/remove)
├── hooks/        useAsync (abortable loads), useAction, useIdempotencyKey, useDebounced, useDocumentTitle
├── lib/          format (exact money parsing/formatting), scopes (+ back-office nav), validation (mirrors backend rules)
├── components/
│   ├── ui/       Button, Field, Alert/ProblemAlert, DataTable, Pagination, Badge, Modal, Dialogs (confirm/prompt), Toasts…
│   ├── layout/   AppShell (header, nav, cart badge), BackofficeLayout (scope-filtered sidebar)
│   └── domain/   ProductPicker, SupplierPicker (type-ahead), OrderSummary, AddressForm, InvoiceView, ReturnDetails,
│                 sod (small-team override helper)
├── pages/        store/, account/, backoffice/{catalog,inventory,purchasing,sales,finance,admin}/, ErrorPages
└── styles/       global.css (tokens, light/dark, responsive, print)
```

## Screens by role

| Area | Screens | Needs |
|---|---|---|
| Shop (public) | Catalog (search, category, sort, paging), product page; product photos from `public/products/` (letter tile when none; credits in `CREDITS.md` there) | — |
| Customer | Cart, checkout (address → quote → confirm), orders, order detail (cancel, invoice, return), invoice (printable), returns | cart / orders scopes |
| Account (any user) | Profile, change password, sign out everywhere, API keys (create with chosen scopes, secret shown once, revoke) | signed in (JWT) |
| Back office: dashboard | Live counts: unpaid/paid orders, returns to review/receive, products to reorder, POs to approve/receive, ledger balanced | any staff scope |
| Sales | Orders (filter by status, find by order number, ship/deliver/cancel, record payment shortcut), returns desk (approve/reject → receive → refund) | orders:admin; returns:manage / inventory:write / finance:write |
| Catalog | Products (search incl. hidden, create with opening stock + unit cost, edit, hide, image path), categories | catalog:write |
| Inventory | Stock (on hand / reserved / available / value, adjust with reason, reorder policy), stock ledger, reorder suggestions (+ create draft POs), valuation | inventory:read / write |
| Purchasing | Suppliers, purchase orders (draft → submit → approve → receive in parts), deliveries | purchasing:* (receive: inventory:write) |
| Finance | Record payment: pick from the queue of orders awaiting payment or find the order number the customer quoted (any status, so late payments too), then see the outcome; payments, invoices, refunds, tax rates, journal, trial balance, sales summary | finance:read / write |
| Admin | Users (role, enable/disable, revoke all credentials), audit log (filters + quick filters incl. SoD overrides) | users:admin, audit:read |

Navigation hides what a role cannot use; **the backend enforces every scope again** (the UI is never the control).

## How the UI uses the backend contract

- **Quote → confirm (M1):** checkout gets `quoteCart` for the delivery country, shows the exact total, and sends it as
  `expectedTotalMinor`. On `409 PRICE_CHANGED` it re-quotes and asks the person to confirm the new total.
- **Idempotency keys (M5/R7):** one key per user intention (checkout confirmation, PO creation, delivery booking, stock
  adjustment, return request, API key creation). Retrying after a timeout reuses the key, so nothing happens twice; the
  key is renewed when the request body changes or after success. A replayed checkout simply lands on the same
  order; delivery booking and stock adjustment say "already booked / already applied" when the server replays.
- **Separation of duties (R10):** in small-team mode the backend answers `403 SEGREGATION_OF_DUTIES` with
  `overrideAllowed: true`; the UI asks for a reason and retries with `sodOverrideReason` (approve PO, receive PO,
  receive return, refund return). Hard blocks (own order's payment, own return) are shown as errors, never prompted.
- **Errors:** every screen renders problem+json through `ProblemAlert`: message, field errors, "temporary, try again
  in N s" for retryable codes, and the request id as a support reference. Code branches on `code`, never on text.
- **Money:** amounts are exact integer minor units. Typed prices are parsed with string arithmetic (no floats); display
  uses `Intl` with the currency's decimal places (USD 2, JPY 0, KWD 3).
- **Sessions (F1–F3):** one session per browser, shared by every tab (localStorage), so a new or duplicated tab is
  already signed in. Refreshes run one at a time across tabs under a Web Lock (`navigator.locks`); a tab that waited
  re-reads the session and adopts the new pair instead of rotating again, because the backend treats a refresh token
  used twice as theft. Sign-in, sign-out and expiry reach every tab through the `storage` event, with the reason.
  A 401 triggers one refresh (or adoption) and a retry, then a fresh `whoami` so the menus follow role changes.
  Only a 400/401 from `/auth/refresh` ends the session; on 429, 5xx or no network the session is kept, `Retry-After`
  is honoured and the person sees "can't reach the store, you're still signed in" with a retry (it also reconnects by
  itself when the browser comes back online). Password change and "sign out everywhere" end the session everywhere.
- **Names, not ids (BE-3):** purchase orders, deliveries, the stock ledger and reorder suggestions show product and
  supplier names straight from the API responses (no lookup per row). The supplier picker is a type-ahead over
  `searchSuppliers?q=` (F5).

## Security

| Area | What is done |
|---|---|
| XSS | React escaping only; no `dangerouslySetInnerHTML`, `innerHTML`, `eval`; strict CSP in production (`script-src 'self'`, no inline scripts/styles, `connect-src 'self'`) |
| Tokens | One session per browser in localStorage (same XSS exposure as sessionStorage; only the lifetime differs). Refresh under a cross-tab Web Lock, so a refresh token is never used twice; a stale refresh result can never overwrite a newer sign-in. Requests use `credentials: 'omit'` and `cache: 'no-store'`. An httpOnly-cookie refresh (BFF) is planned for the deployment phase |
| CSRF | Not applicable: bearer tokens only, no cookies |
| Redirects | `?next=` accepts same-site relative paths only, without whitespace or control characters (URL parsers turn `/<TAB>/host` into `//host`) |
| Clickjacking & headers | nginx: `frame-ancestors 'none'`, X-Frame-Options DENY, nosniff, no-referrer, Permissions-Policy, COOP, HSTS |
| Tracing | nginx forwards a caller's `X-Request-Id` only if it matches the backend's own rule (`^[A-Za-z0-9._-]{8,64}$`), else its own id |
| Client IP | The backend runs with the `behind-proxy` profile and `TRUSTED_PROXIES` = this nginx's address (BE-4); port 8080 is never published |
| Input | Client-side mirrors of backend rules for feedback only (SKU, codes, password policy, country codes, amounts); the server stays authoritative |
| Third parties | None at runtime: no CDNs, fonts, analytics or trackers |
| Container | nginx-unprivileged (non-root), `server_tokens off`, 1 MB body limit, SPA shell `no-store`, hashed assets immutable |

## Tests (42, all passing)

`src/**/*.test.ts(x)`, run with Vitest (jsdom): money parsing/formatting, validation (open-redirect guard incl.
control characters, password policy), scope-based navigation per role, API error parsing, the HTTP client (refresh +
retry and fresh scopes after a 401, single-flight refresh, session end only on 400/401, session kept on 503 / offline,
`Retry-After` on 429, Idempotency-Key and replay header, network errors), **tabs** (`auth/tabs.test.ts`: a new or
duplicated tab joins the session; two tabs refreshing at once rotate only once; a 401 in a second tab adopts instead of
reusing; a duplicated tab later refreshes with the newest token; sign-out and sign-in reach other tabs; a late refresh
result never overwrites or ends a newer sign-in), the small-team override helper, and `ProblemAlert`.
CI: `.github/workflows/frontend-ci.yml` (typecheck, tests, build, Docker build).
