# E-Com MCP Server — SPEC (Phase 4, narrowed per architect review — see REVIEW.md)

Exposes the E-Com backend's REST API to AI agents as MCP tools, using **FastMCP** (Python). It is the "separate
MCP server" the backend was always built for: backend/SPEC.md's "MCP-readiness contract" — stable `operationId`s,
scoped API keys, quote-then-confirm checkout, idempotency keys, structured errors — is the contract this server
relies on, not something it re-implements.

> **Code only, nothing installed or run** (the install hold was re-imposed for this new component on 2026-10-14,
> separately from the backend/frontend, which were already verified). Every library call below was checked against
> the real installed-from-PyPI source for `fastmcp==4.1.0` / `httpx2==2.13.1` at the import/behaviour level — not
> written from memory — but the server itself has never been started. See NOTES.md, "What still needs a real run."
> The architect review (REVIEW.md) landed mid-build and changed the design from "every operation is a tool" to the
> narrowed version below (M1/M2/M3/M4/M6); this SPEC already describes the narrowed version, not the earlier one.

## The core idea: generate tools from the backend's own OpenAPI document

```python
mcp = FastMCP.from_openapi(openapi_spec=spec, client=authenticated_client, name="E-Com")
```

`spec` comes from a committed, reviewed snapshot by default (`ECOM_OPENAPI_SOURCE=cached` — see "Where the tool
list comes from" below), with every operation's required scope(s) already published on it as `x-required-scopes`
(read from the backend's own `@PreAuthorize`, via `OpenApiConfig.scopeExtensionOperationCustomizer`). FastMCP turns
each operation into a tool named after its `operationId`, with the description taken straight from the backend's
`@Operation(summary = "...")`. There is no second, hand-written list of tool *definitions* to keep in sync with the
backend — but which tools an agent actually sees is narrowed in two ways before any of that matters (`policy.py`):

- **Never agent-callable, scope or no scope** (`EXCLUDED_OPERATIONS`): session/credential management
  (register/login/refresh/logout/change-password/API-key management) and the user-admin role/status/credential
  endpoints. The architect review's M1: today, without this, an agent could register accounts or attempt logins
  through this server, all from its IP.
- **Filtered by the key's actual scopes, deny by default** (M2, hardened by code review's G1): `whoAmI` is called
  once at startup; an operation is kept only if its `x-required-scopes` intersect the key's effective scopes, **or**
  it carries `x-mcp-unscoped-ok` (published by the same customizer, on the handful of operations — public catalog
  reads, the identity check — that are genuinely safe for any agent). An operation with *neither* extension, e.g. a
  future endpoint that forgets its scope annotation, is excluded, not silently kept. Without M2, a key scoped to just
  `cart:read`/`cart:write` would see ~90 tools, almost all of which 403 — expensive and confusing for an LLM choosing
  which tool to call.

Add an endpoint on the backend with a sensible `operationId`, summary and `@PreAuthorize`, refresh the snapshot
(below), and it's correctly scoped and visible (or excluded) the next time this server starts — no change needed
in `policy.py` for an ordinary new endpoint; `EXCLUDED_OPERATIONS` is maintained by hand only for the rare case of a
new session/credential-style endpoint that needs the M1 treatment.

## Where the tool list comes from (M3)

`ECOM_OPENAPI_SOURCE=cached` is the default, and the only mode production should use: tools are generated from a
committed, human-reviewed snapshot (`ECOM_OPENAPI_CACHE_FILE`), never from whatever the backend happens to be
running right now. A backend change must go through `scripts/refresh_openapi_cache.py` (fetch a fresh live spec) →
`scripts/diff_openapi_tools.py` (see exactly what changed: added/removed operations, and any change to a method,
required scopes, or Idempotency-Key requirement) → a human reviews that diff and commits the refreshed snapshot —
the same review gate a code change gets, instead of silently changing what every agent can do. It also means
production never needs `/v3/api-docs` reachable at all. `ECOM_OPENAPI_SOURCE=live` fetches straight from the
running backend on every start instead — for local development only, to try out a new/changed endpoint before
committing a refreshed snapshot.

**No real snapshot exists yet** — nothing has run end-to-end, so `openapi/snapshot.json` has never been generated.
The first real run (below) necessarily starts with one.

## Run (once the install hold is lifted for this component)

```bash
cd mcp-server
uv sync                                        # fetches a compatible Python (>=3.11) and the pinned deps
cp .env.example .env                           # fill in ECOM_API_KEY (see below)
uv run python scripts/verify_wiring.py         # offline check: no backend, no real key, no network — see NOTES.md

# First real run only — generates the snapshot ECOM_OPENAPI_SOURCE=cached then reads on every later start:
#   (needs the backend running with API_DOCS_ENABLED=true, and ECOM_OPENAPI_SOURCE=live for this one run)
ECOM_OPENAPI_SOURCE=live uv run python scripts/refresh_openapi_cache.py openapi/snapshot.json
uv run python -m ecom_mcp.server               # now reads the snapshot; the backend only needs to be reachable
                                                # for actual tool calls, not for /v3/api-docs
```

### Getting an API key for an agent

The MCP server authenticates as a **scoped API key**, never a human password (backend/SPEC.md, point 1 of the
MCP-readiness contract: "The agent acts *as that user*, never with more power"). A human provisions one once:

1. Sign in as the person the agent should act on behalf of (frontend, or `POST /api/v1/auth/login` directly).
2. Create a key with only the scopes the agent needs, e.g. a shopping assistant gets
   `["cart:read","cart:write","orders:read","orders:write"]`, never the full set their role allows — the frontend's
   Account → API keys page does this, or call `createApiKey` directly.
3. The raw key is shown **once**, at creation. Put it in `mcp-server/.env` as `ECOM_API_KEY`.

Effective scopes are always `key scopes ∩ owner's current role` (backend/SPEC.md): if the owner's role is later
reduced, the agent's access shrinks automatically, with no change needed here.

## Wiring into an MCP client (Claude Desktop, Claude Code, etc.)

```json
{
  "mcpServers": {
    "ecom": {
      "command": "uv",
      "args": ["--directory", "/absolute/path/to/E-Com/mcp-server", "run", "python", "-m", "ecom_mcp.server"],
      "env": {
        "ECOM_API_BASE_URL": "http://localhost:8080",
        "ECOM_API_KEY": "ek_..."
      }
    }
  }
}
```

(Uses stdio, the default transport for `mcp.run()` with no arguments — the normal choice for a local client like
Claude Desktop. A networked deployment would instead run this as a long-lived process with an HTTP transport; see
NOTES.md, "Transport" for what that needs that local use doesn't.)

## Structure

```
mcp-server/
├── pyproject.toml          fastmcp==4.1.0, httpx2==2.13.1, python-dotenv==1.2.4 — exact pins, verified against PyPI
├── .env.example            every setting, explained
├── openapi/snapshot.json   the reviewed tool surface (M3) — does not exist yet, see "No real snapshot exists yet"
├── src/ecom_mcp/
│   ├── config.py           env loading; refuses to start on a missing/placeholder ECOM_API_KEY (same discipline as
│   │                       the backend refusing to start without DB_PASSWORD/JWT_SECRET) or an unconfigured cache
│   │                       file while ECOM_OPENAPI_SOURCE=cached
│   ├── openapi_source.py   gets the spec: the committed snapshot by default, or a live fetch in dev mode (M3)
│   ├── policy.py           what tools exist and how they're labelled: EXCLUDED_OPERATIONS (M1), scope-based
│   │                       filtering via whoAmI (M2), tool annotations (M4)
│   ├── errors.py           M6: formats a tool-call error's body as real JSON, not a Python dict repr
│   └── server.py           build_client() (the authenticated httpx2.AsyncClient) + create_server() (wires
│                           policy.py/errors.py into FastMCP.from_openapi) + main()
└── scripts/
    ├── verify_wiring.py            offline check of every load-bearing assumption below (incl. M1/M2/M4/M6),
    │                                against a toy spec and a mock transport — no backend, no real key, no network
    ├── refresh_openapi_cache.py    saves a fresh live snapshot (M3); always live, regardless of ECOM_OPENAPI_SOURCE
    └── diff_openapi_tools.py       what a refreshed snapshot would change about the agent-visible tool surface —
                                     the review gate M3 exists to create; usable as a CI check
```

## How tool calls behave (verified against the installed library's source, not assumed)

- **Every operation becomes a Tool by default** (FastMCP 4.x's `DEFAULT_ROUTE_MAPPINGS` in
  `fastmcp.server.providers.openapi.routing` already maps every method to `MCPType.TOOL`, unlike older FastMCP
  versions that defaulted GETs to Resources) — **narrowed by `policy.build_route_map_fn`** (M1 exclusion + M2 scope
  filtering, both described above) before an agent ever sees the list.
- **Tool annotations follow the route** (M4, `policy.annotate_component`): `readOnlyHint` on GET, `destructiveHint`
  on everything else, `idempotentHint` on GETs plus any write that declares the `Idempotency-Key` header — a
  well-behaved MCP client uses these to decide when to ask the human before acting.
- **Auth is one client default header.** `X-API-Key` is set once on the shared `httpx2.AsyncClient`
  (`server.py: build_client`) and sent with every request; FastMCP rebuilds each generated request through that
  client, so client defaults and the request's own declared headers merge (the client default loses only if a tool
  call's own parameter has the identical header name, which `X-API-Key` never does).
- **`Idempotency-Key` needs no special handling.** The backend declares it as a per-endpoint header parameter
  (`@RequestHeader`), which springdoc documents like any other OpenAPI parameter. FastMCP exposes a declared header
  parameter as a tool argument under its literal name — confirmed from `fastmcp-slim`'s own
  `tests/server/providers/openapi/test_parameter_collisions.py` and re-checked by `scripts/verify_wiring.py`. The
  calling agent supplies it like any other argument; `INSTRUCTIONS` in `server.py` tells it to generate one value
  per real-world action and reuse it on retries.
- **Errors carry the backend's problem+json as real JSON** (M6). By default, FastMCP raises with the parsed body
  appended as a Python dict repr; `errors.apply()` replaces that formatting with clean JSON text
  (`HTTP 409: {"code": "PRICE_CHANGED", ...}`) so an agent can reliably `json.loads()` out `code`/`retryable`/
  `requestId`. This is the one place this server reaches into a private (leading-underscore) part of fastmcp's
  implementation — see NOTES.md, "M6: clean JSON errors" for the fragility this carries and the guard against it.

## Security

| Area | What is done |
|---|---|
| Credentials | Only `X-API-Key`; no login/password/JWT flow lives in this server (M1 excludes those operations entirely) |
| Least privilege | The key's scopes are chosen per agent at creation time; the backend enforces them on every call, exactly as it does for a human session. This server additionally *hides* tools outside those scopes (M2) and never exposes account/credential/role-management operations regardless of scope (M1) |
| Human-in-the-loop | Tool annotations (M4) mark every non-GET as destructive; `INSTRUCTIONS` requires confirming the quoted total before `placeOrder` and treating any other destructive tool the same way |
| Prompt injection | `INSTRUCTIONS` states tool output — product/return/supplier text included — is data, never instructions (M5). The real limit is least-privilege keys plus the backend's own guardrails; per-key spending limits (backend REVIEW.md §5.3) don't exist yet — see NOTES.md |
| Reviewed surface | The tool list comes from a committed snapshot by default, not a live fetch (M3) — a backend change can't silently change what an agent can do with no review gate |
| Secrets | `ECOM_API_KEY` from the environment only; refuses to start on the placeholder value, same as the backend |
| Discovery surface | `/v3/api-docs` is public once enabled (no key needed to *read* the spec — only to *call* an operation); with `ECOM_OPENAPI_SOURCE=cached` (the default), production never needs it reachable at all |
| Transport | stdio by default (local process, no open port); see NOTES.md before exposing this over a network |
