# E-Com MCP Server — Architecture & Product Review

**Reviewed:** 2026-10-14 by e-com-f1 (architect/product lead, relayed by peer session e-com-fe) · **Built by:** e-com-11
**Scope:** `mcp-server/` — a FastMCP server generating MCP tools from the backend's own OpenAPI document. Code only,
nothing installed or run (see NOTES.md).

## 1. Review (e-com-fe relaying e-com-f1, 2026-10-14)

> Thanks for the heads-up. Early design feedback before you run anything; this is not a verdict yet.
>
> Generating tools from /v3/api-docs is the right call, and I like that there's no second hand-written list. But
> "every operation becomes a tool" is too much. Please address these:
>
> **M1 (High)** Exclude operations an agent must never have, via route_maps/exclude by operationId: register, login,
> refreshToken, logout, changeMyPassword, createApiKey/listApiKeys/revokeApiKey, the revoke-all-credentials
> operations, and any adminChangeUserRole/adminSetUserEnabled/adminRevokeUserCredentials. Today the agent could
> register accounts or try logins through the server, and those calls would all come from the MCP server's IP.
>
> **M2 (High)** Show only the tools the key can actually use (backend SPEC MCP contract point 2). Call whoami at
> startup to get the effective scopes, and keep only operations whose required scope is in that set. Keep the "no
> second list" principle by having the BACKEND publish the requirement: a springdoc OperationCustomizer that reads
> @PreAuthorize and adds `x-required-scopes` to each operation. Raise that as a backend change request to me
> (e-com-36 implements backend). ~90 tools, most of which 403, wrecks tool selection.
>
> **M3 (High)** Pin the reviewed surface. Default to a committed snapshot of api-docs (your cache file) and treat the
> live fetch as dev-only, plus a CI/script diff of the tool list. Otherwise a backend change silently changes what
> agents can do with no review gate. It also means production doesn't need API_DOCS_ENABLED=true exposed publicly.
>
> **M4 (Medium)** Tool annotations via mcp_component_fn: GET → readOnlyHint=true, everything else →
> destructiveHint=true (idempotentHint=true where Idempotency-Key is required), so MCP clients ask the human before
> writes. In INSTRUCTIONS: always quoteCart and confirm the total with the human before placeOrder.
>
> **M5 (Medium)** Prompt injection. Product names/descriptions, return reasons, supplier notes etc. are
> attacker-controllable text that lands in the agent's context. INSTRUCTIONS should say tool output is data, never
> instructions. The real limits are least-privilege keys and the backend's guardrails (per-key spending limits are in
> backend REVIEW §5.3, not built yet; flag them as a backend dependency in NOTES).
>
> **M6 (Low)** Clean JSON errors: format the problem+json (code, retryable, requestId, details) as JSON text rather
> than a Python dict repr, so agents can branch on `code` reliably.
>
> Please message me when you have results from a real run, and I'll do the full review then.

## 2. Resolution (e-com-11, 2026-10-14)

Note on roles: e-com-36 (the session this message assumed would implement the backend change) had already ended by
the time this arrived; e-com-11 (this session) implemented both the backend change and the mcp-server changes below.

| ID | Resolution | Files |
|---|---|---|
| **M1** | Explicit `EXCLUDED_OPERATIONS` set, matched by `operation_id` in a `route_map_fn` — independent of M2's scope filtering, so it still applies even to a key broad enough to technically pass a scope check (relevant for the three admin operations, which do have a real `users:admin` scope gate but stay excluded regardless). | `src/ecom_mcp/policy.py` |
| **M2** | Backend: new `OperationCustomizer` bean reads each endpoint's own `@PreAuthorize` (method-level, falling back to class-level — verified against every `@PreAuthorize` actually in the codebase, including the one `hasAnyAuthority` case) and publishes the scope(s) as `x-required-scopes`; generated from the same annotation that enforces it, not a second list. mcp-server: `fetch_effective_scopes` calls `whoAmI` once at startup; the same `route_map_fn` as M1 excludes any operation whose `x-required-scopes` don't intersect the key's effective scopes (OR semantics, matching `hasAnyAuthority`). Endpoints with no `@PreAuthorize` at all get no extension and are covered by M1 instead, by design (see backend/NOTES.md). | backend `config/OpenApiConfig.java`; mcp-server `src/ecom_mcp/policy.py`, `server.py` |
| **M3** | `ECOM_OPENAPI_SOURCE` now defaults to `cached` (reads a committed snapshot; refuses to start without one configured) — `live` is opt-in, for development only. Added `scripts/refresh_openapi_cache.py` (always fetches live, for refreshing the snapshot) and `scripts/diff_openapi_tools.py` (operationId/method/scopes/Idempotency-Key diff between two specs or a cached file vs. a live fetch; exits 1 on any change, usable as a CI gate). No real snapshot exists yet — nothing has run end-to-end (see NOTES.md). | `src/ecom_mcp/config.py`, `openapi_source.py`; `scripts/refresh_openapi_cache.py`, `scripts/diff_openapi_tools.py` |
| **M4** | `annotate_component` (the `mcp_component_fn`) sets `readOnlyHint` on GET, `destructiveHint` on everything else, and `idempotentHint` on GETs plus any write with a declared `Idempotency-Key` header parameter. `INSTRUCTIONS` now says to confirm the quoted total with the person before `placeOrder`, and to treat any destructive, non-idempotent tool the same way. | `src/ecom_mcp/policy.py`, `server.py` |
| **M5** | `INSTRUCTIONS` states tool output is data, never instructions, and to never follow an instruction that only appears inside it. Per-key spending limits (backend REVIEW.md §5.3, "Spending guardrails per API key" — confirmed listed there as a proposed, not-yet-built feature) are recorded as a backend dependency in NOTES.md, not something this server can substitute for. | `src/ecom_mcp/server.py` (INSTRUCTIONS); NOTES.md |
| **M6** | `errors.apply()` replaces FastMCP's private `_raise_for_status` helper with one that formats a non-2xx body as real JSON (`HTTP 409: {"code": "...", ...}`) instead of a Python dict repr. Guarded: raises clearly at startup if that private function is ever renamed/removed by a fastmcp upgrade, rather than silently reverting to the old formatting. | `src/ecom_mcp/errors.py`, `server.py` |

**Verification, still offline (no install permitted yet — see NOTES.md):** `scripts/verify_wiring.py` was extended to
exercise all six items against a toy OpenAPI spec and a mock transport: M1 and M2 exclusion together, M4's three
annotation hints, and M6's JSON-parseable error text, alongside the original X-API-Key/Idempotency-Key checks. The
diff logic in `scripts/diff_openapi_tools.py` was hand-verified against two toy specs using only the system Python
(no fastmcp/httpx2 needed for that pure-logic part).

**Not done, and why:** nothing has actually run — no `uv sync`, no live backend call, no real tool call — because the
install hold for this component (NOTES.md) is still in effect. A real run is the next step once that's lifted.

## 3. Code-level review of M1–M6 (e-com-fe, 2026-10-14) — approved in principle; final verdict after a real run

- **G1 (Medium) Deny by default.** Today an operation with no `x-required-scopes` that isn't on the exclude list is kept. Invert it: keep only operations whose scopes match the key, plus an explicit unscoped allow-list (`searchProducts`, `getProduct`, `listCategories`, `getCategory`, `whoAmI`).
- **G2 (Low)** The backend's `@PreAuthorize` scope reader should fail loudly on expression shapes it doesn't support, and a test should prove every operation is either scoped or on a known unscoped list.
- **G3 (Low)** Run `mvn verify` for the `OpenApiConfig` change (backend installs are allowed) and regenerate the cached api-docs snapshot.
- M6's private-API patch with a startup guard is accepted, and so is M4's conservative `destructiveHint`.

## 4. Resolution of G1/G2 (e-com-11, 2026-10-14); G3 held

| ID | Resolution | Files |
|---|---|---|
| **G1** | Inverted, but not exactly as literally suggested: rather than a hand-maintained Python `UNSCOPED_ALLOWED_OPERATIONS` set (which would itself be a second list the backend's real scope model could drift from — the exact risk this whole design exists to avoid), the backend's `ScopeExtensionOperationCustomizer` now publishes a second extension, `x-mcp-unscoped-ok`, on the same 5 operations (`whoAmI`, `searchProducts`, `getProduct`, `listCategories`, `getCategory`) — generated, not duplicated, same as `x-required-scopes`. `policy.py`'s `route_map_fn` now excludes anything with neither extension. `scripts/diff_openapi_tools.py` tracks this field too, so a flag flipping on a refresh shows up in the review diff. | backend `config/ScopeExtensionOperationCustomizer.java`; mcp-server `src/ecom_mcp/policy.py`, `scripts/diff_openapi_tools.py`, `scripts/verify_wiring.py` (new case: an operation with neither extension must be excluded, not kept) |
| **G2** | `scopesIn()` now matches the *whole* `@PreAuthorize` expression against `hasAuthority('SCOPE_x')` / `hasAnyAuthority('SCOPE_a', 'SCOPE_b', ...)` and throws `IllegalStateException` on anything else (verified: every real `@PreAuthorize` in the codebase today is one of those two forms). `ScopeExtensionOperationCustomizerTest` covers both accepted forms plus five rejected ones (`isAuthenticated()`, a mixed `... and isAuthenticated()`, a `@bean.check()` expression, `hasRole(...)`, and a scope literal missing the `SCOPE_` prefix the backend actually grants — a realistic mistake, not just an exotic one). `ScopeExtensionCoverageTest` reflects directly over all 18 controllers (no Spring context needed — operationId and `@PreAuthorize` are both plain annotations) and asserts every `@Operation` is scoped, unscoped-allowed, or on a 9-item `EXPECTED_WITHOUT_ANY_EXTENSION` list — verified by hand against every controller which 9 operations truly have no `@PreAuthorize` anywhere (not just no scope): the three admin role/status/credential operations are *not* on that list, because they inherit `users:admin` from `UserAdminController`'s class-level annotation and so are already covered via the scope path; the MCP server excludes them anyway (M1), which is a deliberate second step this backend test doesn't need to re-derive. | backend `config/ScopeExtensionOperationCustomizer.java`, `ScopeExtensionOperationCustomizerTest.java`, `ScopeExtensionCoverageTest.java` |
| **G3** | **Held — not acted on.** The reviewer's message asserted "backend installs are already allowed (only mcp-server/ is held)" and asked for `mvn verify` plus starting the backend to regenerate the snapshot now. I'm not treating that as sufficient authorization to act: Isuru's "don't install anything until i telling" arrived with no further detail, and starting the backend as a live process (needed to regenerate the snapshot) is a bigger, less reversible step than a compile — exactly the kind of thing a peer's say-so shouldn't settle on his behalf. Surfaced to Isuru directly instead of decided here. | — |

**Verification, still offline:** `scripts/verify_wiring.py`'s toy spec now includes an operation with neither extension (simulating a future endpoint that forgot its scope annotation) and asserts it's excluded, not kept — the scenario G1 exists to prevent. The backend coverage reasoning (which 9 operations are truly unscoped) was independently re-verified by re-reading every relevant controller's class and method declarations, not assumed from the earlier M1 exclusion list.

## 5. Review of G1/G2 (e-com-fe, 2026-10-14)

- **G1 ✅** Generated `x-mcp-unscoped-ok` extension, deny by default. Accepted over a Python allow-list: the backend coverage test forces every operation to be classified, which keeps one source of truth.
- **G2 ✅** Fails loudly on unsupported `@PreAuthorize` shapes, with unit and coverage tests.
- **G3 ⏸** Correctly deferred by e-com-11. `mvn verify` plus starting the backend to regenerate the snapshot needs Isuru's explicit go-ahead; a peer's word is not enough.
