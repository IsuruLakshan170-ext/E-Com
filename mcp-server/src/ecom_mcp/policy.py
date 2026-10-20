"""What tools exist, and how they're labelled — kept separate from server.py's
job of wiring everything together. Covers mcp-server/REVIEW.md items M1, M2, M4.
"""

from __future__ import annotations

from typing import Any

import httpx2
from fastmcp.server.providers.openapi import MCPType
from fastmcp.utilities.openapi import HTTPRoute
from mcp_types import ToolAnnotations

from .config import Config

#: M1: operations with no scope to filter by (gated by actor type, not a backend
#: scope — see backend/SPEC.md's Security model table), but that must never be
#: agent-callable regardless. Session/credential management first; then
#: user-admin actions that stay excluded even for a key broad enough to
#: technically hold `users:admin` (role changes and credential revocation are
#: judged too dangerous for any automated caller, not just an under-scoped one).
EXCLUDED_OPERATIONS: frozenset[str] = frozenset(
    {
        "register",
        "login",
        "refreshToken",
        "logout",
        "changeMyPassword",
        "revokeAllMyCredentials",
        "createApiKey",
        "listApiKeys",
        "revokeApiKey",
        "adminChangeUserRole",
        "adminSetUserEnabled",
        "adminRevokeUserCredentials",
        # The human's side of the agent guardrails (backend/REVIEW.md §5.3). These already have no published
        # scope, so G1's deny-by-default would exclude them anyway — named here too because the reason matters:
        # an agent approving the order it just placed, or reading its own activity feed, defeats the mechanism.
        "getApiKeyActivity",
        "listOrdersAwaitingApproval",
        "approveAgentOrder",
        "rejectAgentOrder",
    }
)

_REQUIRED_SCOPES_EXTENSION = "x-required-scopes"
#: G1 (code review of M1/M2): deny by default. An operation reaches this far only if it has
#: x-required-scopes (checked below) or carries this extension — published by the SAME backend
#: customizer that publishes x-required-scopes (ScopeExtensionOperationCustomizer), for the small,
#: explicit set of operations that are genuinely safe for any agent regardless of scope (public
#: catalog reads, the identity check). No separate Python-side "allowed unscoped" list to drift
#: from the backend's: a future unscoped endpoint that forgets this (or a scope) is excluded by
#: default rather than silently exposed.
_UNSCOPED_OK_EXTENSION = "x-mcp-unscoped-ok"


def fetch_effective_scopes(config: Config) -> set[str]:
    """M2: the agent's actual scopes (`key scopes ∩ owner's role`, backend/SPEC.md),
    read once at startup via the same X-API-Key this server uses for every other
    call. Raises clearly if the key is rejected, same discipline as a missing
    ECOM_API_KEY: better to fail loudly at startup than build a tool list for the
    wrong agent.
    """
    url = f"{config.api_base_url}/api/v1/auth/whoami"
    response = httpx2.get(
        url, headers={"X-API-Key": config.api_key}, timeout=config.request_timeout_seconds
    )
    if response.status_code == 401:
        raise RuntimeError(
            f"The backend rejected ECOM_API_KEY when calling {url} (401). The key may be "
            "wrong, expired, or revoked — create a new one (see SPEC.md, 'Getting an API "
            "key for an agent')."
        )
    response.raise_for_status()
    scopes = response.json().get("scopes")
    if not isinstance(scopes, list):
        raise RuntimeError(f"whoAmI at {url} returned no 'scopes' list: {response.text}")
    return set(scopes)


def build_route_map_fn(effective_scopes: set[str] | None):
    """Combines M1 (explicit exclusion) and M2 (scope-based filtering) into the
    one `route_map_fn` FastMCP calls per operation — and, per the code review's
    G1, denies by default rather than keeping whatever this function doesn't
    recognize. A route becomes a Tool only by returning None; everything else
    must be excluded explicitly, including the "nothing published" case.

    `effective_scopes=None` is the shared-server (http) case: there is no single
    key at build time, so the scope check is skipped here and applied per caller
    instead (see `is_visible_to` and remote.ScopeFilterMiddleware). M1 and G1
    still apply in full - an operation that is excluded, or that has neither
    extension, never becomes a tool for anyone.
    """

    def route_map_fn(route: HTTPRoute, mcp_type: MCPType) -> MCPType | None:
        if route.operation_id in EXCLUDED_OPERATIONS:
            return MCPType.EXCLUDE

        required = route.extensions.get(_REQUIRED_SCOPES_EXTENSION)
        if required:
            # hasAnyAuthority is an OR of scopes (one real case today: the admin
            # returns list, gated by returns:manage OR inventory:write OR
            # finance:write) — holding any one of them is enough to keep the tool.
            if effective_scopes is None:
                return None
            return None if set(required) & effective_scopes else MCPType.EXCLUDE

        # No x-required-scopes: keep ONLY if the backend explicitly marked this
        # operation as unscoped-safe. A future endpoint that forgets a scope
        # check (or is gated by actor type, like a new session/credential
        # operation that isn't on EXCLUDED_OPERATIONS yet) is excluded, not
        # silently exposed — the whole point of G1.
        if route.extensions.get(_UNSCOPED_OK_EXTENSION):
            return None
        return MCPType.EXCLUDE

    return route_map_fn


def operation_requirements(spec: dict[str, Any]) -> dict[str, frozenset[str] | None]:
    """operationId -> the scopes it needs (any one is enough), or None when the backend
    marked it safe for any caller. Operations with neither extension are left out: they
    are never tools (G1), so they never need a per-caller answer.

    Read straight from the same OpenAPI document the tools are generated from, so the
    per-caller filter cannot drift from the tool list.
    """
    requirements: dict[str, frozenset[str] | None] = {}
    for path_item in spec.get("paths", {}).values():
        for operation in path_item.values():
            if not isinstance(operation, dict) or "operationId" not in operation:
                continue
            operation_id = operation["operationId"]
            if operation_id in EXCLUDED_OPERATIONS:
                continue
            required = operation.get(_REQUIRED_SCOPES_EXTENSION)
            if required:
                requirements[operation_id] = frozenset(required)
            elif operation.get(_UNSCOPED_OK_EXTENSION):
                requirements[operation_id] = None
    return requirements


def is_visible_to(
    operation_id: str,
    requirements: dict[str, frozenset[str] | None],
    scopes: set[str],
) -> bool:
    """The per-caller version of M2: same rule as `build_route_map_fn`, one caller's scopes."""
    if operation_id not in requirements:
        return False
    required = requirements[operation_id]
    return required is None or bool(required & scopes)


def annotate_component(route: HTTPRoute, component: Any) -> None:
    """M4: standard MCP tool hints, so a client can ask the human before a write
    without this server hand-maintaining a list of which operations are
    destructive. Derived straight from the route, not duplicated per tool:
      - readOnlyHint: GET requests never change state.
      - destructiveHint: anything else does.
      - idempotentHint: GETs (naturally idempotent), plus any write that
        declares the Idempotency-Key header (the backend's own retry-safety
        signal — see backend/SPEC.md's "Safe retries").
    """
    if not hasattr(component, "annotations"):
        return  # defensive: every route is mapped to a Tool, never a Resource

    read_only = route.method == "GET"
    has_idempotency_key = any(
        parameter.location == "header" and parameter.name.lower() == "idempotency-key"
        for parameter in route.parameters
    )
    component.annotations = ToolAnnotations(
        readOnlyHint=read_only,
        destructiveHint=not read_only,
        idempotentHint=read_only or has_idempotency_key,
    )
