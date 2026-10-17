"""Offline sanity check for the assumptions server.py and policy.py/errors.py
rely on — no real backend, no real API key, no network. Run once dependencies
are installed:

    uv run python scripts/verify_wiring.py

Builds a FastMCP server from a toy OpenAPI document against an
httpx2.MockTransport, wired exactly like server.py (same route_map_fn,
mcp_component_fn, and error patch), then checks:

  1. tool names are exactly the operationIds, minus the excluded ones (M1) and
     the ones outside the toy "agent"'s scopes (M2) — server.py's whole premise,
     plus the two narrowing steps the review added;
  2. the X-API-Key client default reaches the backend on every call;
  3. a declared "Idempotency-Key" header parameter surfaces as a tool
     argument under that literal name, and is actually forwarded as that
     header;
  4. tool annotations match the route (M4): GET -> readOnlyHint, POST ->
     destructiveHint, and idempotentHint wherever Idempotency-Key is required
     or the route is a GET;
  5. a non-2xx response's error text is valid JSON after stripping the
     "HTTP <status>: " prefix (M6), not a Python dict repr.

Exits non-zero with a clear message on the first assumption that doesn't
hold, instead of a passing server that behaves subtly differently to what
NOTES.md describes.
"""

from __future__ import annotations

import asyncio
import json
import sys

import httpx2
from fastmcp import FastMCP
from fastmcp.client import Client

from ecom_mcp import errors, policy

TOY_SPEC = {
    "openapi": "3.0.0",
    "info": {"title": "Toy", "version": "1.0.0"},
    "servers": [{"url": "https://backend.example"}],
    "paths": {
        "/api/v1/auth/whoami": {
            "get": {
                "operationId": "whoAmI",
                "summary": "Who the current API key acts as",
                "x-mcp-unscoped-ok": True,  # G1: no scope, kept only because of this
                "responses": {"200": {"description": "OK"}},
            }
        },
        # G1: no scope AND not marked x-mcp-unscoped-ok — must be denied by default, not kept.
        "/api/v1/users/me": {
            "get": {
                "operationId": "getMyProfileWithNoPublishedScopeByMistake",
                "summary": "Simulates a future endpoint that forgot its scope annotation",
                "responses": {"200": {"description": "OK"}},
            }
        },
        # M1: must never appear, scope or no scope.
        "/api/v1/auth/login": {
            "post": {
                "operationId": "login",
                "summary": "Sign in",
                "responses": {"200": {"description": "OK"}},
            }
        },
        # M2: has a required scope the toy agent's key does NOT hold.
        "/api/v1/admin/orders": {
            "get": {
                "operationId": "adminListOrders",
                "summary": "List all orders (admin)",
                "x-required-scopes": ["orders:admin"],
                "responses": {"200": {"description": "OK"}},
            }
        },
        # Ordinary GET the toy agent's key DOES hold the scope for.
        "/api/v1/products": {
            "get": {
                "operationId": "searchProducts",
                "summary": "Search products",
                "x-mcp-unscoped-ok": True,  # G1: no scope, kept only because of this
                "responses": {"200": {"description": "OK"}},
            }
        },
        # Write with a declared Idempotency-Key header, scope the key holds.
        "/api/v1/purchasing/purchase-orders/{id}/receipts": {
            "post": {
                "operationId": "receivePurchaseOrder",
                "summary": "Book a delivery against a purchase order",
                "x-required-scopes": ["inventory:write"],
                "parameters": [
                    {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}},
                    {
                        "name": "Idempotency-Key",
                        "in": "header",
                        "required": True,
                        "schema": {"type": "string"},
                    },
                ],
                "requestBody": {
                    "required": True,
                    "content": {
                        "application/json": {
                            "schema": {"type": "object", "properties": {"note": {"type": "string"}}}
                        }
                    },
                },
                "responses": {
                    "200": {"description": "OK"},
                    "409": {"description": "Conflict"},
                },
            }
        },
    },
}

EFFECTIVE_SCOPES = {"inventory:write"}  # deliberately NOT orders:admin

seen_requests: list[httpx2.Request] = []


def handler(request: httpx2.Request) -> httpx2.Response:
    seen_requests.append(request)
    if request.url.path.endswith("/receipts"):
        return httpx2.Response(
            409,
            json={
                "code": "SEGREGATION_OF_DUTIES",
                "retryable": False,
                "requestId": "req-123",
                "detail": "Goods must be received by someone else",
            },
        )
    return httpx2.Response(200, json={"ok": True})


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    sys.exit(1)


async def main() -> None:
    errors.apply()  # M6, same call server.py makes

    client = httpx2.AsyncClient(
        base_url="https://backend.example",
        headers={"X-API-Key": "test-key"},
        transport=httpx2.MockTransport(handler),
    )
    mcp = FastMCP.from_openapi(
        openapi_spec=TOY_SPEC,
        client=client,
        name="toy",
        route_map_fn=policy.build_route_map_fn(EFFECTIVE_SCOPES),
        mcp_component_fn=policy.annotate_component,
    )

    async with Client(mcp) as mcp_client:
        tools = {t.name: t for t in await mcp_client.list_tools()}

        expected = {"whoAmI", "searchProducts", "receivePurchaseOrder"}
        if set(tools) != expected:
            fail(
                f"expected tools {sorted(expected)} after M1/M2/G1 filtering, got {sorted(tools)}. "
                "'login' should be excluded (M1); 'adminListOrders' should be excluded (M2, "
                "requires orders:admin which the toy agent lacks); "
                "'getMyProfileWithNoPublishedScopeByMistake' should be excluded (G1: no scope and "
                "no x-mcp-unscoped-ok must deny by default, not fall through as kept)."
            )
        print("OK: M1 exclusion, M2 scope filtering, and G1's deny-by-default all apply")

        who = tools["whoAmI"]
        if who.annotations is None or not who.annotations.readOnlyHint:
            fail(f"GET tool 'whoAmI' should have readOnlyHint=True, got {who.annotations!r}")
        receive = tools["receivePurchaseOrder"]
        if receive.annotations is None or not receive.annotations.destructiveHint:
            fail(f"POST tool should have destructiveHint=True, got {receive.annotations!r}")
        if not receive.annotations.idempotentHint:
            fail("a tool with a declared Idempotency-Key header should have idempotentHint=True")
        print("OK: M4 tool annotations (readOnlyHint / destructiveHint / idempotentHint)")

        properties = receive.input_schema.get("properties", {})
        if "Idempotency-Key" not in properties:
            fail(
                "declared header parameter 'Idempotency-Key' did not become a tool "
                f"argument under that name; actual properties: {list(properties)}"
            )
        print("OK: 'Idempotency-Key' header parameter is a tool argument")

        await mcp_client.call_tool("whoAmI", {})
        if not seen_requests or seen_requests[-1].headers.get("x-api-key") != "test-key":
            fail("X-API-Key client default header was not sent with the request")
        print("OK: X-API-Key reaches the backend on every call")

        seen_requests.clear()
        # raise_on_error=False: the mock handler 409s every /receipts call (it exists
        # to exercise M6 below), so this call errors too — we only need the request
        # that was actually SENT, captured in seen_requests regardless of the response.
        await mcp_client.call_tool(
            "receivePurchaseOrder",
            {"id": "po-1", "Idempotency-Key": "fixed-key-123", "note": "partial delivery"},
            raise_on_error=False,
        )
        sent = seen_requests[-1]
        if sent.headers.get("idempotency-key") != "fixed-key-123":
            fail(
                "the 'Idempotency-Key' tool argument was not forwarded as the "
                f"Idempotency-Key request header; headers sent: {dict(sent.headers)}"
            )
        print("OK: the Idempotency-Key tool argument is forwarded as a request header")

        result = await mcp_client.call_tool(
            "receivePurchaseOrder",
            {"id": "po-1", "Idempotency-Key": "another-key", "note": "will 409"},
            raise_on_error=False,
        )
        error_text = result.content[0].text if result.content else ""
        # FastMCP's tool layer prepends its own "Error calling tool '<name>': " label (seen on the first real run,
        # 2026-10-16); M6 only owns the "HTTP <status>: <json>" part after it.
        _, sep, m6_part = error_text.partition("HTTP 409:")
        if not sep:
            fail(f"expected M6's 'HTTP 409: ...' message, got: {error_text!r}")
        try:
            parsed = json.loads(m6_part.strip())
        except json.JSONDecodeError:
            fail(f"M6: error body is not valid JSON after the status prefix: {error_text!r}")
        if parsed.get("code") != "SEGREGATION_OF_DUTIES":
            fail(f"M6: expected code SEGREGATION_OF_DUTIES in the parsed error, got: {parsed}")
        print("OK: M6 tool-call errors carry the backend's problem+json as real JSON")

    print("\nAll wiring assumptions hold against the installed fastmcp/httpx2.")


if __name__ == "__main__":
    asyncio.run(main())
