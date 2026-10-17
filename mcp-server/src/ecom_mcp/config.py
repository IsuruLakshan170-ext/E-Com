"""Configuration, loaded from the environment (and an optional local .env).

Mirrors the backend's own discipline (backend/SPEC.md, "Secrets: env vars only;
no default DB password or JWT secret"): this server refuses to start without a
real API key, the same way the backend refuses to start without DB_PASSWORD.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

# No-op if there is no .env file (e.g. in production, where the MCP client's
# own config — Claude Desktop's mcpServers block, a container, etc. — sets
# these directly).
load_dotenv()

_DEFAULT_BASE_URL = "http://localhost:8080"
_DEFAULT_OPENAPI_PATH = "/v3/api-docs"
_DEFAULT_TIMEOUT_SECONDS = 30.0
_DEFAULT_SERVER_NAME = "E-Com"
_DEFAULT_OPENAPI_SOURCE = "cached"
_VALID_OPENAPI_SOURCES = frozenset({"cached", "live"})
_PLACEHOLDER_KEYS = frozenset({"", "ek_replace-with-a-real-scoped-key"})


@dataclass(frozen=True)
class Config:
    api_base_url: str
    api_key: str
    openapi_path: str
    request_timeout_seconds: float
    openapi_cache_file: str | None
    openapi_source: str
    server_name: str


def load_config() -> Config:
    api_base_url = os.environ.get("ECOM_API_BASE_URL", _DEFAULT_BASE_URL).rstrip("/")

    api_key = os.environ.get("ECOM_API_KEY", "")
    if api_key in _PLACEHOLDER_KEYS:
        raise RuntimeError(
            "ECOM_API_KEY is not set. Create a scoped API key for this agent "
            "(createApiKey — see mcp-server/.env.example for the exact steps) "
            "and set ECOM_API_KEY to its raw value, shown once at creation "
            "time. Never put a human user's password here: this server "
            "authenticates only via X-API-Key, like every other MCP client."
        )

    timeout_raw = os.environ.get(
        "ECOM_REQUEST_TIMEOUT_SECONDS", str(_DEFAULT_TIMEOUT_SECONDS)
    )
    try:
        request_timeout_seconds = float(timeout_raw)
    except ValueError as exc:
        raise RuntimeError(
            f"ECOM_REQUEST_TIMEOUT_SECONDS must be a number, got {timeout_raw!r}"
        ) from exc

    openapi_source = os.environ.get("ECOM_OPENAPI_SOURCE", _DEFAULT_OPENAPI_SOURCE)
    if openapi_source not in _VALID_OPENAPI_SOURCES:
        raise RuntimeError(
            f"ECOM_OPENAPI_SOURCE must be one of {sorted(_VALID_OPENAPI_SOURCES)}, "
            f"got {openapi_source!r}"
        )

    openapi_cache_file = os.environ.get("ECOM_OPENAPI_CACHE_FILE") or None
    # M3: the reviewed, committed snapshot is the default source — a live backend
    # change must never silently change what an agent can do with no review gate
    # (mcp-server/REVIEW.md). "live" is for dev/refreshing the snapshot only.
    if openapi_source == "cached" and not openapi_cache_file:
        raise RuntimeError(
            "ECOM_OPENAPI_SOURCE=cached (the default) needs ECOM_OPENAPI_CACHE_FILE "
            "set to a committed, reviewed snapshot of /v3/api-docs. Generate one with "
            "scripts/refresh_openapi_cache.py once the backend is running, or set "
            "ECOM_OPENAPI_SOURCE=live for local development against a live backend "
            "(never in production — see SPEC.md, 'Where the tool list comes from')."
        )

    return Config(
        api_base_url=api_base_url,
        api_key=api_key,
        openapi_path=os.environ.get("ECOM_OPENAPI_PATH", _DEFAULT_OPENAPI_PATH),
        request_timeout_seconds=request_timeout_seconds,
        openapi_cache_file=openapi_cache_file,
        openapi_source=openapi_source,
        server_name=os.environ.get("ECOM_MCP_SERVER_NAME", _DEFAULT_SERVER_NAME),
    )
