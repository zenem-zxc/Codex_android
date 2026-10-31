"""Small command-line client for the Windows Codex bridge.

Examples:
  python tools/remote_client.py --token-file PATH --list
  python tools/remote_client.py --token-file PATH --new --message "Hello"
  python tools/remote_client.py --token-file PATH --thread-id ID --message "Hello"
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
from typing import Any


class Client:
    def __init__(self, host: str, port: int, token: str) -> None:
        self.sock = socket.create_connection((host, port), timeout=20)
        self.stream = self.sock.makefile("rwb")
        self.stream.write(f"codex-bridge {token}\n".encode())
        self.stream.flush()
        hello = json.loads(self.stream.readline())
        if hello.get("ok") is not True:
            raise RuntimeError(hello)
        self.next_id = 1

    def request(self, method: str, params: dict[str, Any], timeout: float = 120) -> tuple[dict, list[dict]]:
        request_id = self.next_id
        self.next_id += 1
        self.sock.settimeout(timeout)
        self.stream.write((json.dumps({"id": request_id, "method": method, "params": params}) + "\n").encode())
        self.stream.flush()
        notifications = []
        while True:
            raw = self.stream.readline()
            if not raw:
                raise RuntimeError(f"bridge closed while waiting for {method}")
            message = json.loads(raw)
            if "id" not in message:
                notifications.append(message)
                continue
            if "method" in message:
                raise RuntimeError(
                    f"App Server requires an interactive response for {message['method']}; "
                    "this client cannot handle approvals. Connection closed."
                )
            if message["id"] != request_id:
                raise RuntimeError(f"unexpected response: {message}")
            if "error" in message:
                raise RuntimeError(json.dumps(message["error"], ensure_ascii=False))
            return message, notifications

    def close(self) -> None:
        self.stream.close()
        self.sock.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--list", action="store_true", help="list recent Codex threads")
    parser.add_argument("--thread-id")
    parser.add_argument("--new", action="store_true", help="create a persistent thread")
    parser.add_argument("--message")
    parser.add_argument("--cwd", default=".")
    args = parser.parse_args()
    if args.new and args.thread_id:
        parser.error("--new and --thread-id cannot be combined")
    if args.message and not (args.new or args.thread_id):
        parser.error("--message requires --thread-id or --new")

    client = Client(args.host, args.port, open(args.token_file, encoding="utf-8").read().strip())
    try:
        client.request(
            "initialize",
            {"clientInfo": {"name": "codex-android-remote-client", "version": "0.1.0"}},
        )
        if args.list or (not args.thread_id and not args.new):
            result, _ = client.request(
                "thread/list",
                {"archived": False, "limit": 30, "useStateDbOnly": True},
            )
            for thread in result["result"]["data"]:
                print(f"{thread['id']} | {thread.get('name') or thread.get('preview', '')[:100]}")
            if not args.message and not args.new:
                return 0

        thread_id = args.thread_id
        if args.new:
            result, _ = client.request(
                "thread/start",
                {
                    "cwd": args.cwd,
                    "sandbox": "read-only",
                    "approvalPolicy": "never",
                    "ephemeral": False,
                },
            )
            thread_id = result["result"]["thread"]["id"]
            print(f"NEW_THREAD={thread_id}")
        if not args.message:
            return 0
        if args.thread_id:
            client.request("thread/resume", {"threadId": thread_id, "excludeTurns": True})

        result, events = client.request(
            "turn/start",
            {
                "threadId": thread_id,
                "input": [{"type": "text", "text": args.message}],
            },
        )
        turn_id = result["result"]["turn"]["id"]
        print(f"TURN={turn_id}")
        while True:
            if events:
                event = events.pop(0)
            else:
                raw = client.stream.readline()
                if not raw:
                    raise RuntimeError("bridge closed before the turn completed")
                event = json.loads(raw)
            if "id" in event and "method" in event:
                raise RuntimeError(
                    f"App Server requires an interactive response for {event['method']}; "
                    "this client cannot handle approvals. Connection closed."
                )
            if event.get("method") == "item/agentMessage/delta":
                delta = event.get("params", {}).get("delta", "")
                print(delta, end="", flush=True)
            if event.get("method") == "turn/completed":
                turn = event.get("params", {}).get("turn", {})
                if turn.get("id") != turn_id:
                    continue
                status = turn.get("status")
                print(f"\nSTATUS={status}")
                if status != "completed":
                    raise RuntimeError(event)
                break
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        raise SystemExit(1)
