# NOTES — decisions and known gaps

## Decisions (Phase 1)
- **Spring Boot + Spring Security resource server**: battle-tested JWT validation instead of a hand-rolled one.
- **HS256 symmetric JWT**: one issuer, one verifier. Switch to RS256 + JWKS if other services must verify tokens.
- **Separate credential types**: humans use JWT + refresh; machines (MCP) use scoped, expiring API keys that act *as* their owner.
- **Server-side cart**; **pessimistic row locks at checkout**; **UUID ids**; **Flyway owns the schema**.

## Decisions (Phase 2 — ERP)
- **Modular monolith, one transaction per business action**: an operational change and its stock-ledger and
  general-ledger rows commit or roll back together.
- **Segregation of duties** as a security control; **append-only ledgers** protected by DB triggers.
- **Sequential accounting numbers** (invoices, credit notes, journal entries); random operational numbers.
- **Moving-average cost**, snapshotted on each order line; cost is back-office only.

## Decisions (Phase 2.1 — review fixes, see REVIEW.md §8)
- **Reserve at checkout, consume at shipment, release on cancel/expiry.** Ledger inventory always equals physical stock.
- **Deferred revenue**: payments are customer deposits until shipment.
- **Domain events, synchronous, same transaction** (Spring `@EventListener`) instead of a transactional outbox: no
  extra infrastructure, and accounting can't drift. An outbox becomes worthwhile once emails/webhooks need async delivery.
- **SoD per person, not per role**, so the all-scopes ADMIN can't bypass it.
- **Throttle (account, IP) instead of locking accounts**; same generic error for every login failure.
- **Token version claim** for near-immediate revocation of JWTs, with a 15 s cache instead of a DB hit per request.
- **SYSTEM actor** is a real (disabled, passwordless) user row, so FKs like `payments.recorded_by` stay NOT NULL.

## Decisions (Phase 2.2 — review change requests, see REVIEW.md §9–10)
- **One lock order**: cart → order → return → products sorted by id; expiry claims one order per transaction.
- **Exact inventory value pool** instead of value = stock × rounded average: the only way GL 1200 can equal the
  stock valuation to the cent. Rounding goes into which units carry the odd cent, never into a drift.
- **Late payments**: re-reserve if possible, otherwise record + refund (money received is always booked).
- **Payments for cancelled orders (R11, Isuru's decision)**: always recorded and refunded in the same transaction;
  a customer- or staff-cancelled order is never reinstated.
- **Per-account login ceiling with trusted IPs** rather than a lockout, so an attack can't lock the owner out.
- **SoD policy flag** (Isuru's product decision): strict by default; small-team mode allows overrides with an
  audited reason; conflict-of-interest rules (own order's payment, own return) are never relaxed.

## Decisions (frontend review, backend items BE-1–BE-4, see ../frontend/REVIEW.md)
- **BE-1 payment queue lives in finance** (`FinanceOrderQueries`, a small read-only service next to `FinanceService`):
  finance already depends on order. It returns only what matching money needs (number, customer, total, deadline):
  no address or items, which stay behind `orders:admin`. Customer names come from a `UserSummary` projection
  (id, email, name only: never the password hash, role or token state), one query per page.
- **BE-2 exact match, not LIKE**: order numbers are generated upper-case, so the search trims and upper-cases what was
  typed and uses the unique index. Without a status filter the finance list defaults to PENDING_PAYMENT, but an
  order number finds the order in any status (late payment for an expired order, "did my payment arrive?").
  `OrderService.search` is shared by the admin list and finance.
- **BE-3 names are looked up live, not snapshotted**: `ProductRepository.labelsById` loads sku + name for a whole page
  in one query (chunks of 500 ids), hidden products included. Purchasing documents show the product's current name;
  customer orders keep their snapshot as before. `ReorderSuggestion.preferredSupplierName` was added too, so the UI
  could drop its last per-id lookup (inventory cannot show supplier names: purchasing depends on inventory).
- **BE-4 opt-in profile with a required trusted list**: `behind-proxy` turns on Tomcat's RemoteIpValve with
  `internal-proxies = ${TRUSTED_PROXIES}` and no default. Trusting Tomcat's default private ranges would also trust
  clients on those networks, and nginx appends to whatever `X-Forwarded-For` it receives, so such a client could plant
  a fake IP. Without the profile, the socket address is used as before.

## Accepted as-is (agreed with review session e-com-f1)
- H4: rate limiter, login throttle (incl. trusted IPs) and token-state cache are **in-memory per instance**;
  Redis before more than one replica. A restart forgets throttles and trusted IPs.
- M10: Postgres **advisory lock** instead of ShedLock for cleanup jobs (no extra dependency).
- M12: **synchronous same-transaction events** are intentional: the coupling keeps the books consistent. An outbox
  comes with async consumers (emails, webhooks).
- The idempotency fingerprint is the request DTO's JSON: a DTO change across a deploy makes in-flight retries 409
  `IDEMPOTENCY_KEY_REUSED`.
- V3 restored stock for unshipped orders without writing stock-movement rows, and orders PAID under the pre-V3 model
  booked revenue at payment and would book it again at shipment. No real data exists; V3 is not edited (checksum),
  V4 carries an honest note. Fix data before migrating a real database.

## Decisions (MCP server review, see mcp-server/REVIEW.md)
- **`x-required-scopes` extension, generated from the same `@PreAuthorize` that enforces it**
  (`ScopeExtensionOperationCustomizer`, its own file — split out of `OpenApiConfig`, which stays pure API metadata),
  not a separately maintained list. Requested by the MCP review (M2) so an agent only sees tools its key can use.
  Endpoints with no scope annotation at all (session/credential management, gated by actor type instead) carry no
  extension and are excluded by the MCP server on operationId, independent of this mechanism (M1) — some of them
  (role changes, credential revocation) should never be agent-callable even for a key broad enough to technically
  pass a scope check.
- **`x-mcp-unscoped-ok` extension (review round 2, G1)**: the same customizer additionally marks `whoAmI`,
  `searchProducts`, `getProduct`, `listCategories`, `getCategory` — operations with no scope that are genuinely safe
  for any agent — so the MCP server can deny-by-default (G1) without hand-maintaining its own copy of that list.
- **`@PreAuthorize` parsing fails loudly on an unsupported shape (G2)**, rather than silently publishing wrong or no
  scopes: only `hasAuthority('SCOPE_x')` / `hasAnyAuthority('SCOPE_a', 'SCOPE_b', ...)` are understood today, which
  is every real `@PreAuthorize` in the codebase as of this writing. `ScopeExtensionOperationCustomizerTest` covers
  both accepted forms and five rejected ones. `ScopeExtensionCoverageTest` reflects over all 18 controllers (no
  Spring context needed) and fails if any `@Operation` ends up with no scope, no unscoped-ok marking, and isn't on
  its known 9-item "genuinely gated by actor type" list — add a new controller or endpoint, and this test is what
  catches an accidentally-unscoped one before it ships.

## Decisions (Phase 4.1 — agent guardrails, REVIEW.md §5.3, 2026-10-14)
- **Not a new order status.** A guarded order sits in `PENDING_PAYMENT` as always; only `approvalState` (a nullable
  column, `null` for every order a human placed or that needed no approval) gates `recordPayment`. Expiry,
  cancellation and stock release all keep working completely unchanged — they never needed to know approval exists.
- **Limits live on the key, not the role or a new policy table.** `max_order_value_minor` /
  `max_orders_per_day` / `require_approval_above_minor` are three nullable columns on `api_keys` (V6), set once at
  `createApiKey` and never updated — the key already *is* the unit of delegation (backend/SPEC.md), so changing the
  deal means revoking it and issuing another, the same way scopes already work.
- **`AuditService.recordIndependently` (`REQUIRES_NEW`), not `record`, for `AGENT_LIMIT_BLOCKED`.** Caught while
  writing this: `SodPolicy.enforce()` never audits its own blocked case, for exactly the reason that mattered here —
  a plain `audit.record(...)` called right before throwing, inside `checkout()`'s own `@Transactional` method,
  would be rolled back along with everything else and never actually persist. Unlike `SodPolicy`'s blocked case
  (left unaudited), this one IS worth logging (same category as `RATE_LIMITED`/`ACCESS_DENIED`, which already do),
  so a second transaction was the right fix rather than skipping the audit row. `REQUIRES_NEW` only works through a
  real Spring proxy, so it lives on the injected `AuditService` bean, not as a self-invoked method on the caller.
- **`getApiKeyActivity` checks ownership in the service, not just the controller** — `findByIdAndUserId`, so a
  caller can never read another person's key's activity even though the underlying `listAuditEvents` is otherwise
  `audit:read`/admin-only.
- Tests: `AgentOrderGuardrailsIT` (hard caps refuse before reservation; soft cap blocks payment then approve/reject
  both work; the activity feed's ownership check). Added `asApiKey`/`apiKeyFor` to `IntegrationTestBase` — the
  existing `as()` helper only ever simulates a JWT user session, never `ActorType.API_KEY`, which this whole
  feature keys off.
- **Not compiled or run yet.** This landed after the install hold was re-imposed for `mcp-server/`; out of caution
  I did not run `mvn compile`/`mvn verify` on the backend either, even though it was already fully installed and
  verified earlier the same day — static checks only (bracket balance, every import resolves, constructor arity
  matches). `mvn verify` is the next step once that's confirmed with Isuru.

## Decisions (Phase 4.1 review round 2 — A1/A2, backend/REVIEW.md §12–13, 2026-10-14)
- **Fixed at the one choke point, not at each call site.** `CustomerOrder.cancel()` is where customer cancellation,
  staff cancellation and expiry all already converge (confirmed by reading every call site before touching
  anything) — reconciling `approvalState` there, instead of in `cancelForUser`/`expireIfOverdue`/the admin-status
  path separately, means a *future* cancellation path can't reintroduce A1 by forgetting to repeat the fix.
- **A real regression the review caught, not a style nitpick:** before this, a late payment on an order that
  expired while awaiting approval was refused with "waiting for approval" instead of following R3's reinstatement
  rule — silently worse behaviour than before the guardrails feature existed for that one case.
- **A2's trigger path was checked, not assumed live.** `requireApprovalAboveMinor` is `@PositiveOrZero`, so a
  genuinely zero-total order can never need approval via checkout today. Built and tested the fix anyway (one
  `if` on `approveAgentOrder`) as closing the state machine for any future path that could reach it, and said so
  explicitly in the review resolution rather than silently building it without comment either way.
- **One accepted quirk:** `reinstateAsPaid()` doesn't reset `approvalState`, so a reinstated order can show
  `approvalState: EXPIRED` next to `status: PAID`. Harmless (nothing reads `approvalState` once status has moved
  past `PENDING_PAYMENT`) and flagged rather than fixed with a third migration for a cosmetic-only field.

## Decisions (Phase 4.2 — product photos, 2026-10-16, user request, no review yet)
- `imageUrl` is a **same-origin path** (`/products/yoga-mat.jpg`), never an external URL: both CSPs allow
  `img-src 'self'` only, and loosening them for hotlinked images wasn't worth it (user chose "download into the app").
  Rule lives in three places that must agree: `CatalogDtos.IMAGE_URL_REGEX`, `frontend/src/lib/validation.ts`
  `IMAGE_PATH_PATTERN`, and the DB CHECK `chk_products_image_url` (no `//host`, no `..`).
- `updateProduct` is a full PUT, so leaving `imageUrl` out clears the photo, same as `description`. An MCP agent
  calling `updateProduct` must send it back (it's in `getProduct`'s response).
- **V8's CHECK was broken** (`{1,299}`: Postgres caps regex repeat counts at 255, so every non-null write raised
  "invalid regular expression" → mapped to a 409). Fixed forward in V9 (length bound left to `VARCHAR(300)`),
  V8 not edited. Worth remembering: a Java-valid regex is not automatically a Postgres-valid one.
- Testcontainers + Colima needs `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, or every `*IT` errors with "no valid Docker environment".

## Known gaps / to do next
- **First real build (2026-10-14):** main code compiled first time; fixes needed were test-side only: five ambiguous
  `assertThat(tx.execute(...))` calls in `RepositoryQueriesIT` (typed locals now) and Testcontainers 1.19.8 → 1.21.4
  (Docker 29 minimum API 1.44). Result: 54 unit + 39 integration tests pass. Not yet run as an app (smoke test next).
- Concurrency tests are
  probabilistic regression guards (they detect deadlocks/overselling when they happen, not prove absence).
- R8 throttled audit rows count suppressed events since the previous row; events after the last row of a burst are
  only counted in memory.
- R5 ceiling has no real step-up yet (CAPTCHA / emailed code need an email service): untrusted IPs are simply refused
  while the account is under attack. **Phase 5:** step-up auth (email code / CAPTCHA) once email infra exists.
- `lock_timeout = 5s` is set on every pooled connection (Hikari `connection-init-sql`), so it also applies to Flyway's
  migrations. A large migration on a busy database may need `spring.flyway.init-sqls: SET lock_timeout = '...'`
  (or a maintenance window) to raise it.
- Small-team mode weakens internal control by design; someone must review `SOD_OVERRIDDEN` audit events.
- Rate limiting, login throttle and token-state cache are in-memory per instance → Redis before scaling out.
- Accounts payable grows with receipts, but there are no supplier bills/payments yet.
- No partial payments and no payment-provider webhook (the SYSTEM actor is ready for it).
- Partially received POs can't be closed short; single warehouse; single store currency.
- Audit log is immutable and kept forever → plan monthly partitioning / archiving.
- Platform modules (security, user, apikey, audit) still reference each other; business modules are acyclic.
- Behind the nginx proxy, run with the `behind-proxy` profile and `TRUSTED_PROXIES` (BE-4) so client IPs (rate
  limits, throttle, audit) are right. Never expose port 8080 directly while it is on.
- No email verification or password reset; registration reveals whether an email exists (409).
