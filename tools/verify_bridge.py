"""Integration test for the local Codex bridge.

Uses an ephemeral, read-only thread for turn/start so no existing project or
conversation is modified. Existing threads are only listed/read/resumed.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import tempfile
from typing import Any


def request(sock: socket.socket, stream, request_id: int, method: str, params: dict[str, Any], timeout: float = 60):
    stream.write((json.dumps({"id": request_id, "method": method, "params": params}) + "\n").encode())
    stream.flush()
    sock.settimeout(timeout)
    notifications = []
    while True:
        raw = stream.readline()
        if not raw:
            raise RuntimeError(f"bridge closed while waiting for {method}")
        message = json.loads(raw)
        if "id" not in message:
            notifications.append(message)
            continue
        if message["id"] != request_id:
            raise RuntimeError(f"unexpected response id: {message}")
        if "error" in message:
            raise RuntimeError(f"{method} failed: {json.dumps(message['error'], ensure_ascii=False)}")
        return message, notifications


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token-file")
    parser.add_argument("--spawn-bridge", action="store_true")
    args = parser.parse_args()

    bridge = None
    token_file = args.token_file
    if args.spawn_bridge:
        token_file = os.path.join(tempfile.gettempdir(), "codex-android-bridge-verify.token")
        try:
            os.remove(token_file)
        except FileNotFoundError:
            pass
        bridge = subprocess.Popen(
            [
                sys.executable,
                "pc_bridge/bridge.py",
                "--host", args.host,
                "--port", str(args.port),
                "--token-file", token_file,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for _ in range(50):
            if os.path.exists(token_file):
                break
            if bridge.poll() is not None:
                raise RuntimeError(bridge.stderr.read())
            import time
            time.sleep(0.1)
    if not token_file:
        raise ValueError("--token-file is required unless --spawn-bridge is used")

    try:
        token = open(token_file, encoding="utf-8").read().strip()
        sock = socket.create_connection((args.host, args.port), timeout=15)
        stream = sock.makefile("rwb")
        stream.write(f"codex-bridge {token}\n".encode())
        stream.flush()
        hello = json.loads(stream.readline())
        assert hello.get("ok") is True, hello
        print("PASS bridge authentication")

        initialize, _ = request(
            sock, stream, 1, "initialize",
            {"clientInfo": {"name": "codex-android-bridge-test", "version": "0.1.0"}},
        )
        assert initialize.get("result", {}).get("platformFamily") == "windows", initialize
        print("PASS initialize")

        listed, _ = request(
            sock, stream, 2, "thread/list",
            {"archived": False, "limit": 10, "useStateDbOnly": True},
        )
        threads = listed["result"]["data"]
        print(f"PASS thread/list ({len(threads)} threads returned)")

        if threads:
            read_id = threads[0]["id"]
            read, _ = request(sock, stream, 3, "thread/read", {"threadId": read_id, "includeTurns": True})
            assert read.get("result"), read
            turns = read["result"].get("thread", {}).get("turns", [])
            print(f"PASS thread/read ({read_id}; {len(turns)} turns returned)")

        if len(threads) > 1:
            thread_id = threads[1]["id"]
            try:
                resumed, notifications = request(
                    sock, stream, 4, "thread/resume",
                    {"threadId": thread_id, "excludeTurns": True},
                )
                assert resumed.get("result"), resumed
                print(f"PASS thread/resume ({thread_id}; {len(notifications)} notifications)")
            except RuntimeError as exc:
                if "already has an active writer" not in str(exc):
                    raise
                print("SKIP thread/resume: selected thread is active in another Codex client")

        started, _ = request(
            sock, stream, 5, "thread/start",
            {
                "ephemeral": True,
                "cwd": "D:\\Pyfiles\\Codex_android",
                "sandbox": "read-only",
                "approvalPolicy": "never",
            },
        )
        ephemeral_id = started["result"]["thread"]["id"]
        print(f"PASS thread/start ephemeral ({ephemeral_id})")

        turn, notifications = request(
            sock, stream, 6, "turn/start",
            {
                "threadId": ephemeral_id,
                "input": [{"type": "text", "text": "Reply with exactly: bridge test passed"}],
                "approvalPolicy": "never",
            },
            timeout=120,
        )
        assert turn.get("result"), turn
        print("PASS turn/start accepted")
        events = notifications
        delta_count = 0
        while True:
            if events:
                event = events.pop(0)
            else:
                raw = stream.readline()
                if not raw:
                    raise RuntimeError("bridge closed before turn/completed")
                event = json.loads(raw)
            method = event.get("method", "")
            if method == "item/agentMessage/delta":
                delta_count += 1
            if method == "turn/completed":
                result = event.get("params", {}).get("turn", {})
                status = result.get("status")
                if status != "completed":
                    raise RuntimeError(f"turn ended with status {status}: {result.get('error')}")
                print(f"PASS turn/completed (status={status}; {delta_count} agent-message deltas)")
                break
        if not delta_count:
            print("NOTE no agent-message deltas observed; streaming text remains unverified")
        print("TEST COMPLETE (see any SKIP/NOTE lines above)")
        sock.close()
        return 0
    finally:
        if bridge:
            bridge.terminate()
            bridge.wait(timeout=10)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise
