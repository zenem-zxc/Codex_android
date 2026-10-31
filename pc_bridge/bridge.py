"""Transparent Codex App Server bridge for Windows.

The bridge deliberately does not implement an RPC allowlist. Every
newline-delimited JSON-RPC request, response, and notification is forwarded
unchanged between one authenticated client and a local `codex app-server`.
That preserves the complete App Server surface, including approval requests.

This first version uses a bearer token over a TCP connection. Put the port
behind a private VPN (for example Tailscale/ZeroTier) before exposing it
outside the home LAN. TLS is the next hardening step.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import signal
import shutil
import subprocess
import sys
from pathlib import Path


MAX_LINE = 16 * 1024 * 1024


def resolve_codex(value: str) -> str:
    """Resolve codex.exe for interactive shells and Task Scheduler alike."""
    candidate = Path(value).expanduser()
    if candidate.is_file():
        return str(candidate)
    if candidate.is_absolute() or candidate.parent != Path("."):
        raise FileNotFoundError(f"Specified Codex executable does not exist: {candidate}")

    found = shutil.which(value) or shutil.which("codex.exe")
    if found:
        return found

    user_profile = os.environ.get("USERPROFILE", "")
    extension_root = Path(user_profile) / ".vscode" / "extensions"
    matches = sorted(
        extension_root.glob("openai.chatgpt-*/bin/windows-x86_64/codex.exe"),
        reverse=True,
    )
    if matches:
        return str(matches[0])

    raise FileNotFoundError(
        "codex.exe was not found. Start bridge with "
        '--codex "C:\\path\\to\\codex.exe" or set CODEX_EXE.'
    )


def load_token(path: Path) -> str:
    token = path.read_text(encoding="utf-8").strip() if path.exists() else ""
    if not token:
        token = secrets.token_urlsafe(32)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(token + "\n", encoding="utf-8")
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    return token


async def write_line(writer: asyncio.StreamWriter, value: str) -> None:
    writer.write(value.encode("utf-8") + b"\n")
    await writer.drain()


async def pipe_stream(
    source: asyncio.StreamReader,
    destination: asyncio.StreamWriter,
) -> None:
    while True:
        line = await source.readline()
        if not line:
            return
        if len(line) > MAX_LINE:
            raise ValueError("JSON-RPC message is too large")
        destination.write(line)
        await destination.drain()


class Bridge:
    def __init__(self, codex: str, token: str) -> None:
        self.codex = codex
        self.token = token
        self.active: asyncio.Task[None] | None = None

    async def handle(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        peer = writer.get_extra_info("peername")
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=15)
            if line.decode("utf-8", "replace").strip() != f"codex-bridge {self.token}":
                await write_line(writer, json.dumps({"error": "unauthorized"}))
                return
            if self.active and not self.active.done():
                await write_line(writer, json.dumps({"error": "busy"}))
                return

            await write_line(writer, json.dumps({"ok": True, "protocol": "app-server-jsonl"}))
            self.active = asyncio.current_task()
            await self.run_session(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, BrokenPipeError):
            pass
        except Exception as exc:
            print(f"bridge client {peer} failed: {exc}", file=sys.stderr)
        finally:
            if self.active is asyncio.current_task():
                self.active = None
            writer.close()
            await writer.wait_closed()

    async def run_session(
        self, client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter
    ) -> None:
        # No config, provider, auth, or workspace overrides are supplied.
        # app-server therefore uses the user's existing Codex configuration.
        env = os.environ.copy()
        if not env.get("HOME"):
            env["HOME"] = env.get("USERPROFILE", "")
        if not env.get("CODEX_HOME"):
            env["CODEX_HOME"] = os.path.join(env["HOME"], ".codex")
        proc = await asyncio.create_subprocess_exec(
            self.codex,
            "app-server",
            "--stdio",
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            limit=MAX_LINE + 1024,
        )
        assert proc.stdin and proc.stdout and proc.stderr
        server_writer = proc.stdin

        async def forward_client() -> None:
            await pipe_stream(client_reader, server_writer)
            server_writer.close()
            await server_writer.wait_closed()

        async def forward_server() -> None:
            await pipe_stream(proc.stdout, client_writer)

        async def log_stderr() -> None:
            while line := await proc.stderr.readline():
                print("[codex] " + line.decode("utf-8", "replace").rstrip(), file=sys.stderr)

        tasks = [
            asyncio.create_task(forward_client()),
            asyncio.create_task(forward_server()),
            asyncio.create_task(log_stderr()),
        ]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        if proc.returncode is None:
            proc.terminate()
        await proc.wait()


async def serve(host: str, port: int, codex: str, token_file: Path) -> None:
    token = load_token(token_file)
    resolved_codex = resolve_codex(codex)
    print(f"Codex executable: {resolved_codex}", flush=True)
    bridge = Bridge(resolved_codex, token)
    server = await asyncio.start_server(bridge.handle, host, port, limit=MAX_LINE + 1024)
    addresses = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"Codex bridge listening on {addresses}", flush=True)
    print(f"Token file: {token_file}", flush=True)
    async with server:
        await server.serve_forever()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.environ.get("CODEX_BRIDGE_HOST", "100.114.88.98"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CODEX_BRIDGE_PORT", "8765")))
    parser.add_argument("--token-file", type=Path, default=Path.home() / ".codex" / "bridge.token")
    parser.add_argument("--codex", default=os.environ.get("CODEX_EXE", "codex.exe"))
    args = parser.parse_args()
    try:
        asyncio.run(serve(args.host, args.port, args.codex, args.token_file))
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
