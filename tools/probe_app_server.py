"""Read-only smoke probe for the local Codex App Server.

The probe intentionally uses the installed `codex` executable and does not
modify config.toml, auth files, sessions, or provider settings.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from typing import Any


def rpc(proc: subprocess.Popen[str], request_id: int, method: str, params: Any) -> dict[str, Any]:
    message = {"id": request_id, "method": method, "params": params}
    assert proc.stdin is not None
    proc.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    proc.stdin.flush()

    assert proc.stdout is not None
    for raw in proc.stdout:
        if not raw.strip():
            continue
        response = json.loads(raw)
        # Notifications can arrive before the response. Keep them visible but
        # do not confuse them with the request result.
        if response.get("id") == request_id:
            return response
        print(json.dumps({"notification": response}, ensure_ascii=False))
    raise RuntimeError(f"app-server exited before replying to {method}")


def main() -> int:
    codex = shutil.which("codex")
    if not codex:
        print("codex executable was not found on PATH", file=sys.stderr)
        return 2

    env = os.environ.copy()
    # Codex's Windows launcher expects a discoverable home directory. Some
    # editor/automation hosts omit HOME even though USERPROFILE is present.
    if not env.get("HOME"):
        env["HOME"] = env.get("USERPROFILE", "")
    if not env.get("CODEX_HOME"):
        env["CODEX_HOME"] = os.path.join(env["HOME"], ".codex")
    # A client identity is required by initialize. No credentials are passed.
    proc = subprocess.Popen(
        [codex, "app-server", "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    try:
        initialized = rpc(
            proc,
            1,
            "initialize",
            {
                "clientInfo": {
                    "name": "codex-android-probe",
                    "title": "Codex Android read-only probe",
                    "version": "0.1.0",
                }
            },
        )
        print(json.dumps({"initialize": initialized}, ensure_ascii=False, indent=2))

        listed = rpc(
            proc,
            2,
            "thread/list",
            {
                "archived": False,
                "limit": 20,
                "sortDirection": "desc",
                "useStateDbOnly": True,
            },
        )
        print(json.dumps({"thread/list": listed}, ensure_ascii=False, indent=2))
        return 0
    finally:
        if proc.stdin:
            proc.stdin.close()
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
        stderr = proc.stderr.read() if proc.stderr else ""
        if stderr.strip():
            print(json.dumps({"stderr": stderr[-4000:]}, ensure_ascii=False))


if __name__ == "__main__":
    raise SystemExit(main())
