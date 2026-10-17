"""M3 (mcp-server/REVIEW.md): shows exactly what a backend change would do to
the agent-visible tool surface, before it's committed as the new snapshot —
the review gate the whole "cached by default" design exists to create. Usable
as a CI check (exit 1 on any change, forcing a human to look and re-commit the
snapshot deliberately) or by hand while reviewing a refresh.

Compares two OpenAPI documents' operations: added, removed, and any operation
whose method, required scopes, or Idempotency-Key requirement changed (the
three things policy.py's filtering and annotations actually key off). Ignores
everything else (descriptions, schemas, …) — those don't change what an agent
can do or see, only how a tool is described, so they're not this script's job.

    uv run python scripts/diff_openapi_tools.py openapi/snapshot.json new.json
    uv run python scripts/diff_openapi_tools.py openapi/snapshot.json --live
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from ecom_mcp.config import load_config
from ecom_mcp.openapi_source import fetch_live_spec

_REQUIRED_SCOPES_EXTENSION = "x-required-scopes"
_UNSCOPED_OK_EXTENSION = "x-mcp-unscoped-ok"


def _operations(spec: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """operationId -> {method, path, scopes, needs_idempotency_key}."""
    operations: dict[str, dict[str, Any]] = {}
    for path, methods in spec.get("paths", {}).items():
        for method, operation in methods.items():
            if method.upper() not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
                continue
            operation_id = operation.get("operationId")
            if not operation_id:
                continue
            scopes = operation.get(_REQUIRED_SCOPES_EXTENSION) or []
            needs_idempotency_key = any(
                parameter.get("in") == "header"
                and parameter.get("name", "").lower() == "idempotency-key"
                for parameter in operation.get("parameters", [])
            )
            operations[operation_id] = {
                "method": method.upper(),
                "path": path,
                "scopes": sorted(scopes),
                # G1: visibility to an agent depends on this just as much as on scopes.
                "unscoped_ok": bool(operation.get(_UNSCOPED_OK_EXTENSION)),
                "needs_idempotency_key": needs_idempotency_key,
            }
    return operations


def diff(old_spec: dict[str, Any], new_spec: dict[str, Any]) -> list[str]:
    old_ops = _operations(old_spec)
    new_ops = _operations(new_spec)
    lines: list[str] = []

    for operation_id in sorted(set(new_ops) - set(old_ops)):
        op = new_ops[operation_id]
        lines.append(f"+ {operation_id} ({op['method']} {op['path']}, scopes={op['scopes']})")

    for operation_id in sorted(set(old_ops) - set(new_ops)):
        op = old_ops[operation_id]
        lines.append(f"- {operation_id} ({op['method']} {op['path']}, scopes={op['scopes']})")

    for operation_id in sorted(set(old_ops) & set(new_ops)):
        old_op, new_op = old_ops[operation_id], new_ops[operation_id]
        if old_op != new_op:
            lines.append(f"~ {operation_id}: {old_op} -> {new_op}")

    return lines


def main() -> None:
    args = sys.argv[1:]
    if not args:
        raise SystemExit(__doc__)
    old_spec = json.loads(Path(args[0]).read_text())

    if len(args) > 1 and args[1] == "--live":
        new_spec = fetch_live_spec(load_config())
    elif len(args) > 1:
        new_spec = json.loads(Path(args[1]).read_text())
    else:
        raise SystemExit("Give a second file, or --live to fetch from the running backend.")

    changes = diff(old_spec, new_spec)
    if not changes:
        print("No change to the agent-visible tool surface.")
        return

    print(f"{len(changes)} change(s) to the agent-visible tool surface:")
    for line in changes:
        print(" ", line)
    sys.exit(1)


if __name__ == "__main__":
    main()
