"""Shared-server (http) mode: one MCP server, many callers, each with their own API key.

In stdio mode the server is launched by one person and holds that person's key. Here it is a
standalone web server that holds NO key; every request brings the caller's key in the
`X-API-Key` header, and everything the server does on that request is done as that caller:

  - ApiKeyGate          turns away a request with no key / a bad key, before any MCP work happens
  - CallerDirectory     asks the backend "who is this key, what may it do?" (cached briefly)
  - ScopeFilterMiddleware  gives each caller only the tools their own scopes allow (M2, per caller)
  - PerCallerKeyAuth    stamps the calling person's key on the backend request a tool call makes

Nothing here is ever shared between callers except the (key-free) list of all possible tools.
"""

from __future__ import annotations

import hashlib
import time
from collections.abc import Sequence

import httpx2
from fastmcp.exceptions import ToolError
from fastmcp.server.dependencies import get_http_headers
from fastmcp.server.middleware import Middleware
from starlette.datastructures import Headers
from starlette.responses import JSONResponse

from . import policy
from .config import Config

KEY_HEADER = "x-api-key"
#: How long one key's answer from /auth/whoami is reused. The backend still re-checks the key on
#: every real call; this only bounds how stale the *tool list* and the front-door check can be.
SCOPE_CACHE_TTL_SECONDS = 30.0
_MAX_CACHED_KEYS = 1024


class InvalidKey(Exception):
    """The backend rejected this key (wrong, expired, revoked, or its owner was disabled)."""


class BackendUnavailable(Exception):
    """The backend could not be reached or answered something unexpected."""


def caller_key() -> str | None:
    """The API key sent on the request currently being handled, or None."""
    return (get_http_headers().get(KEY_HEADER) or "").strip() or None


class CallerDirectory:
    """key -> that key's effective scopes, via the backend's own whoAmI, cached for a short time.

    Cache entries are keyed by a hash of the key, never the key itself.
    """

    def __init__(self, config: Config, ttl: float = SCOPE_CACHE_TTL_SECONDS, clock=time.monotonic):
        self._client = httpx2.AsyncClient(
            base_url=config.api_base_url, timeout=config.request_timeout_seconds
        )
        self._ttl = ttl
        self._clock = clock
        self._cache: dict[str, tuple[float, frozenset[str]]] = {}

    async def scopes_for(self, raw_key: str) -> set[str]:
        slot = hashlib.sha256(raw_key.encode()).hexdigest()
        now = self._clock()
        hit = self._cache.get(slot)
        if hit and now - hit[0] < self._ttl:
            return set(hit[1])

        try:
            response = await self._client.get("/api/v1/auth/whoami", headers={"X-API-Key": raw_key})
        except httpx2.HTTPError as error:
            raise BackendUnavailable(f"could not reach the backend: {error}") from error
        if response.status_code == 401:
            self._cache.pop(slot, None)
            raise InvalidKey()
        if response.status_code != 200:
            raise BackendUnavailable(f"backend answered {response.status_code} to whoAmI")
        scopes = response.json().get("scopes")
        if not isinstance(scopes, list):
            raise BackendUnavailable("whoAmI returned no 'scopes' list")

        if len(self._cache) >= _MAX_CACHED_KEYS:
            self._cache = {k: v for k, v in self._cache.items() if now - v[0] < self._ttl}
            if len(self._cache) >= _MAX_CACHED_KEYS:
                self._cache.clear()
        self._cache[slot] = (now, frozenset(scopes))
        return set(scopes)


class PerCallerKeyAuth(httpx2.Auth):
    """Attaches the key of whoever is calling *right now* to the backend request.

    There is deliberately no fallback key: if a request somehow carries none, it fails rather
    than quietly running as somebody else.
    """

    def auth_flow(self, request):
        key = caller_key()
        if key is None:
            raise RuntimeError("no X-API-Key on the request that triggered this tool call")
        request.headers["X-API-Key"] = key
        yield request


class ApiKeyGate:
    """ASGI front door: no valid key, no entry. Runs before FastMCP sees the request."""

    def __init__(self, app, directory: CallerDirectory):
        self.app = app
        self.directory = directory

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        key = (Headers(scope=scope).get(KEY_HEADER) or "").strip()
        if not key:
            await self._reject(scope, receive, send, 401, "Send your API key in the X-API-Key header")
            return
        try:
            await self.directory.scopes_for(key)
        except InvalidKey:
            await self._reject(scope, receive, send, 401, "API key is invalid, expired or revoked")
            return
        except BackendUnavailable:
            await self._reject(scope, receive, send, 503, "The E-Com backend is not reachable")
            return
        await self.app(scope, receive, send)

    @staticmethod
    async def _reject(scope, receive, send, status: int, message: str):
        response = JSONResponse({"error": message}, status_code=status)
        await response(scope, receive, send)


class ScopeFilterMiddleware(Middleware):
    """Per-caller M2: each caller sees (and may call) only the tools their own scopes allow."""

    def __init__(self, directory: CallerDirectory, requirements: dict[str, frozenset[str] | None]):
        self._directory = directory
        self._requirements = requirements

    async def _scopes(self) -> set[str]:
        key = caller_key()
        if key is None:
            raise ToolError("No API key was sent with this request")
        try:
            return await self._directory.scopes_for(key)
        except InvalidKey as error:
            raise ToolError("API key is invalid, expired or revoked") from error
        except BackendUnavailable as error:
            raise ToolError("The E-Com backend is not reachable") from error

    async def on_list_tools(self, context, call_next):
        tools: Sequence = await call_next(context)
        scopes = await self._scopes()
        return [t for t in tools if policy.is_visible_to(t.name, self._requirements, scopes)]

    async def on_call_tool(self, context, call_next):
        scopes = await self._scopes()
        if not policy.is_visible_to(context.message.name, self._requirements, scopes):
            # Same answer as a tool that does not exist: never confirm what you are not shown.
            raise ToolError(f"Unknown tool: {context.message.name}")
        return await call_next(context)
