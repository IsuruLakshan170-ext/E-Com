# MCP Server NOTES — decisions, verification trail, known gaps

## Status: code written 2026-10-14; install held again, specifically for this component

Backend and frontend installs were allowed earlier the same day ("all the hold installation things now you can
install relevant to this project"). Mid-way through starting this new component, Isuru said **"don't install
anything until i telling"** — a fresh hold, scoped to `mcp-server/` specifically, not a reversal of the
backend/frontend work already verified. Nothing here has been installed (`uv sync`), run, or git-committed. The
one thing I did do without asking again: read-only `curl`/GitHub-API lookups against PyPI and the public
`PrefectHQ/fastmcp` / `pydantic/httpx2` repos, to write against the real current API instead of guessing from
training data — no local installs, no execution of fetched code.

## Why this needed real verification, not memory

`fastmcp` turned out to be at **4.1.0** — a couple of major versions past what I remembered (the "2.x" API with
`from_openapi`, `RouteMap`, header-param-as-tool-argument behaviour I recalled is from an earlier, differently
shaped library). Writing this from memory would have risked confidently-wrong imports. Instead, for every claim in
SPEC.md's "How tool calls behave" section, I read the actual installed-version source on GitHub at tag `v4.1.0`:

- `fastmcp_slim/fastmcp/server/providers/openapi/routing.py` — confirms `DEFAULT_ROUTE_MAPPINGS = [RouteMap(mcp_type=MCPType.TOOL)]`, i.e. every route is already a Tool by default in this version; no `route_maps` override needed.
- `fastmcp_slim/fastmcp/server/providers/openapi/provider.py` + `.../server.py` — confirms `FastMCP.from_openapi(openapi_spec, client, name, route_maps, route_map_fn, mcp_component_fn, mcp_names, tags, validate_output, **settings)`'s exact signature, and that `instructions=` flows through `**settings` into the `FastMCP` constructor.
- `fastmcp_slim/fastmcp/server/providers/openapi/components.py` — confirms how `OpenAPITool.run()` builds the request through the configured client (client defaults merge with the tool call's own headers) and exactly what happens on a non-2xx response (`_raise_for_status` appends `response.json()`'s dict repr to the raised error, so the backend's `code`/`retryable`/`requestId` genuinely reach the agent, just not as clean JSON — `errors.py` replaces this with real JSON, see "M6: clean JSON errors" below).
- `fastmcp_slim/fastmcp/utilities/openapi/models.py` — confirms `HTTPRoute.operation_id`, `.method`, `.parameters` (each a `ParameterInfo` with `.name`/`.location`, `location` a plain string literal `"path"|"query"|"header"|"cookie"`, not an enum), and `.extensions` (any OpenAPI `x-*` operation field, verbatim) — everything `policy.py`'s `route_map_fn` and `mcp_component_fn` key off.
- `springdoc-openapi` (`org.springdoc.core.customizers.OperationCustomizer`, checked at the `springdoc.version` the backend actually pins, `v2.6.0`) — confirms the `Operation customize(Operation, HandlerMethod)` functional-interface shape the backend's new `x-required-scopes` customizer implements.
- `tests/server/providers/openapi/test_parameter_collisions.py` — confirms a declared header parameter (no name collision with another location) becomes a tool argument under its exact literal name, which is why a backend `@RequestHeader("Idempotency-Key")` parameter needs no special-casing here.
- `fastmcp_slim/fastmcp/client/mixins/tools.py` — confirms the `Client.call_tool(name, arguments)` / `list_tools()` shape `scripts/verify_wiring.py` uses.
- `pydantic/httpx2`'s `src/httpx2/httpx2/_transports/mock.py` + `__init__.py` — confirms `httpx2.MockTransport` and `httpx2.AsyncClient` are both top-level exports, with the same constructor shape (`base_url=`, `headers=`, `timeout=`, `transport=`) as classic `httpx`.

One assumption I did **not** verify from source (reasonable confidence only, from the docs' explicit mention of a
"HTTPX2 timeout guide" and matching usage shown there): `httpx2.get(url, timeout=...)` accepts the same keyword
arguments as classic `httpx.get()`. `openapi_source.py` relies on this. Low risk, but it's the one place to look
first if `uv run python -m ecom_mcp.server` fails in a way `verify_wiring.py` didn't catch (that script never calls
the bare `httpx2.get` shortcut, only `AsyncClient`).

## Decisions

- **Agent guardrails' human-only operations are excluded the same way M1's are** (backend/SPEC.md "Agent
  guardrails", 2026-10-14): `getApiKeyActivity`, `listOrdersAwaitingApproval`, `approveAgentOrder`,
  `rejectAgentOrder` are user-JWT only on the backend (no `@PreAuthorize` scope, checked by actor type), so they'd
  already be denied by G1's deny-by-default with no extra code — named explicitly in `EXCLUDED_OPERATIONS` anyway,
  because the *reason* matters and is worth stating: an agent approving the order it just placed, or reading its
  own activity feed, defeats the entire mechanism, the same logic as the three admin role/credential operations.
- **G1: generated extension, not a hand-maintained Python list** (REVIEW.md §4). The reviewer's literal suggestion
  was a Python-side `UNSCOPED_ALLOWED_OPERATIONS` set; I extended the existing `x-required-scopes` mechanism instead
  (a second extension, `x-mcp-unscoped-ok`, published by the same backend customizer) so the "what's safe without a
  scope" answer still has exactly one source of truth, matching why `x-required-scopes` itself exists. Mentioned
  explicitly to the reviewer rather than silently substituted, since it goes beyond what was literally asked.
- **G3 (run `mvn verify` / start the backend now) was not acted on.** The reviewer asserted this was already
  authorized ("backend installs are already allowed, only mcp-server/ is held"). That may well be a correct reading
  of Isuru's instructions, but I decided not to act on a peer's assertion of what's authorized for an action this
  size (starting the backend as a live process) — see REVIEW.md §4 and the message to Isuru this same turn.

- **Generate, don't hand-write.** `FastMCP.from_openapi()` against the backend's OpenAPI document means there is
  exactly one place that defines "what the backend can do" — the backend itself. This was the design backend/SPEC.md
  committed to from the start ("Every endpoint has a stable operationId = MCP tool name"); implementing it any other
  way (a hand-maintained tool list) would have meant two sources of truth that drift.
- **~~Expose everything; let the backend's scopes gate it~~ — superseded by the architect review (REVIEW.md), M1 +
  M2.** My first cut left every operation visible, reasoning that the backend's own 403s were enough of a gate. The
  review correctly pushed back: a 403 still means the MCP server *tried* session/credential operations it should
  never attempt in the first place (M1 — these have no scope to gate them, since they're checked by actor type, not
  `@PreAuthorize`), and exposing ~90 tools to an LLM when most would 403 is a real selection-quality cost, not just a
  security one (M2). Scope filtering now happens before the agent ever sees the list, not after a failed call.
- **No login flow in this server.** It only ever sends `X-API-Key`. Provisioning the key is a one-time human action
  (SPEC.md, "Getting an API key for an agent"), deliberately outside this server's job — mixing "acquire a
  credential" and "use a credential" into one process is exactly what the backend's API-key model exists to avoid.
  (M1 now also makes this true in a second sense: the key-management operations themselves aren't exposed as tools.)
- **Idempotency-Key is the agent's job, not auto-injected here.** I considered a client-side hook that fills in a
  fresh UUID whenever a tool call omits it, but that would mean this server silently deciding when "the same
  action" starts and ends — a judgement only the calling agent can actually make (it knows whether this call is a
  fresh action or a retry of one it already attempted). `INSTRUCTIONS` in `server.py` states the rule instead of
  automating it away.
- **Exact version pins** (`fastmcp==4.1.0`, `httpx2==2.13.1`, `python-dotenv==1.2.4`), matching the project's general
  convention (the frontend pins exact versions too) and checked against PyPI on 2026-10-14, not guessed.
- **`uv` over a venv + pip.** It was already on this machine (not installed by me) and can fetch a compatible Python
  itself (`fastmcp`/`httpx2` need `>=3.10`; the system `python3` is 3.9.6), so `uv sync` is the one command that
  gets both right without a separate "install Python" step.

## M6: clean JSON errors (resolved; one fragility accepted on purpose)

A tool-call error used to reach the agent as something like
`"HTTP error 409: Conflict - {'code': 'PRICE_CHANGED', 'retryable': True, 'requestId': '...', ...}"` — the real
problem+json fields were present, but as a Python dict repr (single-quoted, `True`/`False` capitalised), not
parseable JSON. `errors.apply()` now replaces `fastmcp.server.providers.openapi.components._raise_for_status` with
a version that formats the body as real JSON instead (`"HTTP 409: {\"code\": \"PRICE_CHANGED\", ...}"`), so an agent
can `json.loads()` it after stripping the `"HTTP <status>: "` prefix.

**First real run (2026-10-16):** the agent actually receives `"Error calling tool '<name>': HTTP 409: {...}"`.
FastMCP's tool layer adds that outer label on top of our message, which reading the source didn't predict. The JSON
is still clean, so the right parse is "take everything after the first `HTTP <status>:`", not "strip a leading
prefix". `verify_wiring.py` now checks it that way.

This is the one place this server reaches into a **private** (leading-underscore) part of fastmcp's implementation
— there is no public hook for overriding a generated tool's error formatting in fastmcp 4.1.0 (`mcp_component_fn`
only mutates an already-built component's attributes, not its request-handling code). Accepted deliberately because
the alternative — a custom `Tool` subclass replacing `OpenAPITool.run()` entirely — would mean stepping outside the
pure "generated, not hand-written" design for every single mutating endpoint, a much bigger and more fragile change
than patching one well-named private helper. `errors.apply()` checks the helper still exists before touching it, so
a future fastmcp upgrade that renames or removes it fails loudly at startup (`RuntimeError`) instead of silently
reverting to the old dict-repr formatting — re-check this against whatever version is actually installed on any
fastmcp upgrade.

## What still needs a real run (none of this has happened yet)

1. `uv sync` — confirms the pins actually resolve and that `httpx2.get(..., timeout=...)` (the one unverified
   assumption, see above) behaves as expected.
2. Backend: `mvn compile` after `OpenApiConfig.java`'s new `OperationCustomizer` bean — it only reuses an
   already-declared dependency (`springdoc-openapi-starter-webmvc-ui`, already in pom.xml before this change), so no
   new install, but it's never been compiled. Then start the backend with `API_DOCS_ENABLED=true` and check a
   sample operation in `/v3/api-docs` actually carries `x-required-scopes` with the right value — e.g.
   `recordPayment` should show `["finance:write"]`.
3. `uv run python scripts/verify_wiring.py` — the offline check; should pass with no backend, no real key, no
   network, since it only talks to a mock transport. Covers M1/M2/M4/M6 now, not just the original three checks. If
   it fails, the error message names exactly which assumption broke and where to fix it.
4. Generate the first real snapshot: `ECOM_OPENAPI_SOURCE=live uv run python scripts/refresh_openapi_cache.py
   openapi/snapshot.json` against the backend from step 2, then eyeball it — this is also the first real test of M2's
   scope-filtering logic against the real `x-required-scopes` values, not the toy ones in `verify_wiring.py`.
5. `uv run python -m ecom_mcp.server` with a real `ECOM_API_KEY` (SPEC.md, "Getting an API key for an agent") — the
   actual live run; none of this has been run end-to-end before.
6. A real agent conversation exercising a few tools end-to-end (at minimum: `whoAmI`, `searchProducts`,
   `quoteCart` → `placeOrder`, and one error path like `PRICE_CHANGED`) — the only way to see whether M6's JSON
   formatting is actually easy for an agent to use in practice, and whether M4's annotations actually change how a
   real MCP client behaves before a write.

## Known gaps / next

- **M5 dependency: no per-key spending guardrails on the backend yet.** `backend/REVIEW.md` §5.3 lists "max order
  value, max orders per day, allowed categories" per API key as a proposed "AI-agent (MCP) differentiator" —
  confirmed in that file, not invented here — but it isn't built. Least-privilege scopes (M2) and the exclusions
  (M1) are real limits on *what* an agent can call; nothing today limits *how much* an agent with legitimate
  `orders:write` can spend in one session. Flagged here as a backend dependency, not something this server can
  substitute for client-side.
- **Transport:** `mcp.run()` with no arguments uses stdio, right for a local client (Claude Desktop/Code) spawning
  this as a subprocess. A networked deployment (the MCP server reachable by multiple agents/users) needs FastMCP's
  HTTP transport instead, plus per-caller auth at the MCP layer itself (today, whoever can start this process has
  whatever `ECOM_API_KEY` is configured) — out of scope until there's an actual networked use case.
- **No tests beyond `verify_wiring.py`.** A real pytest suite (mocking the full backend OpenAPI shape, not just two
  toy operations) would catch more; not written yet to keep this phase's dependency surface minimal for a first cut.
- **`validate_output=True` (the FastMCP default)** means tool results are validated against the backend's declared
  response schema. If the backend ever returns a shape its own OpenAPI doc doesn't quite match, tool calls could
  fail that would otherwise succeed. Not tuned for this yet — revisit if that happens in practice.
- No containerization / process-manager config for running this continuously (it's currently "spawned by the MCP
  client on demand," which is normal for stdio but not for the eventual networked case above).


## Shared-server (http) mode (added 2026-10-20, on Isuru's request to simulate a hosted MCP server)

Added `ECOM_MCP_TRANSPORT=http` and `src/ecom_mcp/remote.py`; SPEC.md, "Two ways to run it", has the design. Verified by
running the backend (:8080) and this server (:8000) as separate processes and connecting as two callers with different
keys: no key / fake key -> 401; key A (profile+cart+orders read) saw 13 tools, key B (profile only) saw 6, and B calling
`listMyOrders` got "Unknown tool". `scripts/verify_wiring.py` (stdio path) still passes. The two callers were two keys of
the same owner, so they differ by `apiKeyId` in `whoAmI`, not by user.

Not covered: no automated test for `remote.py` yet (the check above was a manual two-client run); no TLS; the
in-memory scope cache is per process, so several instances would each cache separately.
