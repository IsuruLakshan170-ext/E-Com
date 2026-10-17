"""Gets the OpenAPI document that FastMCP generates every tool from.

M3 (mcp-server/REVIEW.md): `ECOM_OPENAPI_SOURCE=cached` (the default) reads a
committed, human-reviewed snapshot — a live backend change must never silently
change what an agent can do with no review gate, and production doesn't need
`/v3/api-docs` reachable at all this way. `ECOM_OPENAPI_SOURCE=live` fetches it
straight from the running backend instead, for local development only (see
scripts/refresh_openapi_cache.py to turn a live fetch into a new, reviewable
snapshot, and scripts/diff_openapi_tools.py to see exactly what changed before
committing it).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx2

from .config import Config


def get_openapi_spec(config: Config) -> dict[str, Any]:
    if config.openapi_source == "cached":
        return _read_cache(config)
    return _fetch_live(config)


def fetch_live_spec(config: Config) -> dict[str, Any]:
    """Always live, regardless of ECOM_OPENAPI_SOURCE — used by the refresh/diff
    scripts, which need the real live document even when the server itself is
    configured to run against the cached one.
    """
    return _fetch_live(config)


def _fetch_live(config: Config) -> dict[str, Any]:
    url = f"{config.api_base_url}{config.openapi_path}"
    try:
        response = httpx2.get(url, timeout=config.request_timeout_seconds)
        response.raise_for_status()
        return response.json()
    except Exception as error:
        raise RuntimeError(
            f"Could not fetch the OpenAPI document from {url}: {error}. Start the "
            "backend with API_DOCS_ENABLED=true (backend/SPEC.md, 'Run')."
        ) from error


def _read_cache(config: Config) -> dict[str, Any]:
    # config.py already requires openapi_cache_file whenever openapi_source is
    # "cached", so this is reachable only via a Config built by hand (tests).
    if not config.openapi_cache_file:
        raise RuntimeError("ECOM_OPENAPI_SOURCE=cached but no openapi_cache_file is set.")
    path = Path(config.openapi_cache_file)
    if not path.is_file():
        raise RuntimeError(
            f"{path} does not exist. Generate it once the backend is running: "
            "uv run python scripts/refresh_openapi_cache.py"
        )
    return json.loads(path.read_text())
