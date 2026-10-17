"""M6 (mcp-server/REVIEW.md): the backend's problem+json as real JSON in a tool
error, so an agent can `json.loads()` out `code`/`retryable`/`requestId` reliably,
instead of FastMCP's default Python-dict-repr formatting
(`"HTTP error 409: Conflict - {'code': 'PRICE_CHANGED', 'retryable': True, ...}"`).

There is no public hook for this in fastmcp 4.1.0 — `mcp_component_fn` only lets
you mutate an already-built component's attributes, not its request-handling
behaviour. So this replaces the module-level helper FastMCP's own OpenAPITool.run()
calls on every non-2xx response. It is the one piece of this server that reaches
into a private (leading-underscore) part of fastmcp's implementation, and the
only one guarded this way: `apply()` checks the function still exists before
touching it, so a fastmcp upgrade that removes or renames it fails loudly at
startup instead of silently reverting to the old formatting.
"""

from __future__ import annotations

import json

import httpx2
import fastmcp.server.providers.openapi.components as _openapi_components


def _clean_raise_for_status(response: httpx2.Response) -> None:
    if 200 <= response.status_code < 300:
        return

    try:
        body_text = json.dumps(response.json())
    except (json.JSONDecodeError, ValueError):
        body_text = response.text

    message = f"HTTP {response.status_code}"
    if body_text:
        message += f": {body_text}"
    elif response.reason_phrase:
        message += f": {response.reason_phrase}"

    raise httpx2.HTTPStatusError(message, request=response.request, response=response)


def apply() -> None:
    if not hasattr(_openapi_components, "_raise_for_status"):
        raise RuntimeError(
            "fastmcp.server.providers.openapi.components._raise_for_status no longer "
            "exists — this fastmcp version changed the internals errors.py's M6 patch "
            "relies on. Re-check NOTES.md, 'M6: clean JSON errors', against the "
            "installed version and update or remove this patch."
        )
    _openapi_components._raise_for_status = _clean_raise_for_status
