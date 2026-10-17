"""Saves a fresh live snapshot of the backend's /v3/api-docs to
ECOM_OPENAPI_CACHE_FILE (or the path given as the first argument) — the
committed, reviewed document ECOM_OPENAPI_SOURCE=cached (the default) serves
tools from. Always fetches live, regardless of ECOM_OPENAPI_SOURCE.

Review the diff before committing (scripts/diff_openapi_tools.py), per
mcp-server/REVIEW.md's M3 — a backend change must never silently change what
an agent can do with no review gate.

    uv run python scripts/refresh_openapi_cache.py [path]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from ecom_mcp.config import load_config
from ecom_mcp.openapi_source import fetch_live_spec


def main() -> None:
    config = load_config()
    path = sys.argv[1] if len(sys.argv) > 1 else config.openapi_cache_file
    if not path:
        raise SystemExit(
            "Give a path, or set ECOM_OPENAPI_CACHE_FILE first: "
            "uv run python scripts/refresh_openapi_cache.py openapi/snapshot.json"
        )

    spec = fetch_live_spec(config)
    Path(path).write_text(json.dumps(spec, indent=2))
    print(f"Saved {len(spec.get('paths', {}))} paths to {path}")
    print("Review the diff before committing: uv run python scripts/diff_openapi_tools.py")


if __name__ == "__main__":
    main()
