"""Transparent Codex App Server bridge for Windows.

The bridge deliberately does not implement an RPC allowlist. Apart from the
bridge/upload control method, newline-delimited JSON-RPC requests, responses,
and notifications are forwarded between an authenticated client and Codex.

This first version uses a bearer token over a TCP connection. Put the port
behind a private VPN (for example Tailscale/ZeroTier) before exposing it
outside the home LAN. TLS is the next hardening step.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import binascii
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
import uuid
import re
import unicodedata
from pathlib import Path


MAX_LINE = 16 * 1024 * 1024
# Base64 expands by 4/3; keep the entire JSON request below MAX_LINE.
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
MAX_STREAM_UPLOAD_BYTES = 1024 * 1024 * 1024
MAX_CHUNK_BYTES = 256 * 1024
MIME_SUFFIXES = {
    "application/pdf": ".pdf",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation": ".pptx",
    "application/vnd.ms-powerpoint": ".ppt",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
    "application/msword": ".doc",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
    "application/vnd.ms-excel": ".xls",
    "text/plain": ".txt",
    "text/csv": ".csv",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "application/zip": ".zip",
}


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
    def __init__(self, codex: str, token: str, upload_dir: Path | None = None) -> None:
        self.codex = codex
        self.token = token
        self.active: asyncio.Task[None] | None = None
        self.upload_dir = upload_dir or Path.home() / ".codex" / "bridge-uploads"

    def scoped_upload_dir(self, thread_id: str, model: str | None) -> Path:
        """Keep attachments isolated by Codex thread and selected model."""
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", thread_id):
            raise ValueError("invalid threadId")
        if model is not None and (not isinstance(model, str) or len(model) > 512):
            raise ValueError("invalid model")
        model_key = model or "session-default"
        model_key = unicodedata.normalize("NFKC", model_key)
        digest = hashlib.sha256(model_key.encode("utf-8")).hexdigest()[:24]
        return self.upload_dir / thread_id / digest

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
            await self.close_writer(writer)

    @staticmethod
    async def close_writer(writer: asyncio.StreamWriter) -> None:
        """Close a Windows proactor stream without surfacing peer resets."""
        try:
            writer.close()
            await writer.wait_closed()
        except (ConnectionError, asyncio.IncompleteReadError, OSError):
            # The peer may have disappeared while the socket was closing.
            pass

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

        uploads = {}

        async def forward_client() -> None:
            try:
                while True:
                    line = await client_reader.readline()
                    if not line:
                        break
                    if len(line) > MAX_LINE:
                        raise ValueError("JSON-RPC message is too large")
                    try:
                        message = json.loads(line)
                    except json.JSONDecodeError:
                        server_writer.write(line)
                        await server_writer.drain()
                        continue
                    if isinstance(message, dict) and message.get("method") == "bridge/upload":
                        response = await self.handle_upload(message)
                        await write_line(client_writer, json.dumps(response, ensure_ascii=False))
                        continue
                    if isinstance(message, dict) and isinstance(message.get("method"), str) and message["method"].startswith("bridge/upload/"):
                        response = self.handle_stream_upload(message, uploads)
                        await write_line(client_writer, json.dumps(response, ensure_ascii=False))
                        continue
                    server_writer.write(line)
                    await server_writer.drain()
                server_writer.close()
                await server_writer.wait_closed()
            finally:
                for upload in uploads.values():
                    upload["file"].close()
                    upload["path"].unlink(missing_ok=True)

        async def forward_server() -> None:
            await pipe_stream(proc.stdout, client_writer)

        async def log_stderr() -> None:
            while line := await proc.stderr.readline():
                print("[codex] " + line.decode("utf-8", "replace").rstrip(), file=sys.stderr)

        client_task = asyncio.create_task(forward_client(), name="client-to-codex")
        server_task = asyncio.create_task(forward_server(), name="codex-to-client")
        stderr_task = asyncio.create_task(log_stderr(), name="codex-stderr")
        tasks = (client_task, server_task, stderr_task)
        try:
            # EOF on stderr alone does not mean the app-server session ended.
            done, _ = await asyncio.wait(
                (client_task, server_task), return_when=asyncio.FIRST_COMPLETED
            )
            for task in done:
                if not task.cancelled():
                    error = task.exception()
                    if error is not None:
                        print(
                            f"bridge {task.get_name()} failed: "
                            f"{type(error).__name__}: {error}",
                            file=sys.stderr,
                            flush=True,
                        )
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if proc.returncode is None:
                try:
                    proc.terminate()
                except ProcessLookupError:
                    pass
            await proc.wait()

    def handle_stream_upload(self, message: dict, uploads: dict) -> dict:
        request_id = message.get("id")
        try:
            if not isinstance(request_id, (str, int)) or isinstance(request_id, bool):
                raise ValueError("upload requires a request id")
            params = message.get("params")
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            method = message["method"]
            if method == "bridge/upload/start":
                size = params.get("size")
                if isinstance(size, bool) or not isinstance(size, int) or not 0 < size <= MAX_STREAM_UPLOAD_BYTES:
                    raise ValueError("size must be between 1 byte and 1 GiB")
                name = params.get("fileName")
                if not isinstance(name, str) or len(name) > 255 or not name or any(ord(c) < 32 for c in name):
                    raise ValueError("invalid fileName")
                name = name.replace("\\", "/").rsplit("/", 1)[-1]
                thread_id = params.get("threadId")
                if not isinstance(thread_id, str) or not thread_id:
                    raise ValueError("threadId is required for streamed uploads")
                upload_root = self.scoped_upload_dir(thread_id, params.get("model"))
                mime_type = str(params.get("mimeType", "")).lower().split(";", 1)[0]
                suffix = Path(name).suffix.lower()
                if not re.fullmatch(r"\.[a-z0-9]{1,10}", suffix):
                    suffix = MIME_SUFFIXES.get(mime_type, ".bin")
                if len(uploads) >= 4:
                    raise ValueError("too many active uploads")
                upload_root.mkdir(parents=True, exist_ok=True)
                upload_id = uuid.uuid4().hex
                path = upload_root / (upload_id + suffix + ".part")
                uploads[upload_id] = {"file": path.open("xb"), "path": path, "name": name,
                                      "size": size, "received": 0, "hash": hashlib.sha256(), "suffix": suffix,
                                      "mimeType": mime_type or "application/octet-stream",
                                      "threadId": thread_id}
                result = {"uploadId": upload_id, "chunkSize": MAX_CHUNK_BYTES}
            else:
                upload_id = params.get("uploadId")
                if not isinstance(upload_id, str) or upload_id not in uploads:
                    raise ValueError("unknown uploadId")
                upload = uploads[upload_id]
                if method == "bridge/upload/chunk":
                    encoded = params.get("dataBase64")
                    if not isinstance(encoded, str) or len(encoded) > ((MAX_CHUNK_BYTES + 2) // 3) * 4:
                        raise ValueError("invalid chunk")
                    raw = base64.b64decode(encoded, validate=True)
                    if not raw or len(raw) > MAX_CHUNK_BYTES or upload["received"] + len(raw) > upload["size"]:
                        raise ValueError("chunk exceeds declared size")
                    upload["file"].write(raw)
                    upload["hash"].update(raw)
                    upload["received"] += len(raw)
                    result = {"received": upload["received"]}
                elif method == "bridge/upload/finish":
                    if upload["received"] != upload["size"]:
                        raise ValueError("upload incomplete")
                    upload["file"].close()
                    target = upload["path"].with_suffix("")
                    target = target.with_suffix(upload["suffix"])
                    upload["path"].rename(target)
                    del uploads[upload_id]
                    result = {"path": str(target.resolve()), "fileName": upload["name"],
                              "size": upload["size"], "sha256": upload["hash"].hexdigest(),
                              "mimeType": upload.get("mimeType", "application/octet-stream")}
                elif method == "bridge/upload/cancel":
                    upload["file"].close()
                    upload["path"].unlink(missing_ok=True)
                    del uploads[upload_id]
                    result = {"cancelled": True}
                else:
                    raise ValueError("unknown upload method")
            return {"id": request_id, "result": result}
        except (ValueError, OSError, binascii.Error) as error:
            return {"id": request_id, "error": {"code": -32602, "message": str(error)}}

    async def handle_upload(self, message: dict) -> dict:
        request_id = message.get("id")
        try:
            if not isinstance(request_id, (int, str)) or isinstance(request_id, bool):
                raise ValueError("upload requires a request id")
            params = message.get("params") or {}
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            encoded = params["dataBase64"]
            if not isinstance(encoded, str):
                raise ValueError("dataBase64 must be a string")
            if len(encoded) > ((MAX_UPLOAD_BYTES + 2) // 3) * 4:
                raise ValueError(f"upload exceeds {MAX_UPLOAD_BYTES} bytes")
            raw = base64.b64decode(encoded, validate=True)
            if not raw:
                raise ValueError("empty upload")
            if len(raw) > MAX_UPLOAD_BYTES:
                raise ValueError(f"upload exceeds {MAX_UPLOAD_BYTES} bytes")
            original = str(params.get("fileName", "attachment.bin")).replace("\\", "/").rsplit("/", 1)[-1]
            if not original or len(original) > 255 or any(ord(c) < 32 for c in original):
                raise ValueError("invalid fileName")
            mime_type = str(params.get("mimeType", "")).lower().split(";", 1)[0]
            suffix = Path(original).suffix.lower()
            if not re.fullmatch(r"\.[a-z0-9]{1,10}", suffix):
                suffix = MIME_SUFFIXES.get(mime_type, ".bin")
            safe_name = f"{uuid.uuid4().hex}{suffix}"
            self.upload_dir.mkdir(parents=True, exist_ok=True)
            target = self.upload_dir / safe_name
            with target.open("xb") as output:
                try:
                    output.write(raw)
                except Exception:
                    output.close()
                    target.unlink(missing_ok=True)
                    raise
            return {
                "id": request_id,
                "result": {
                    "path": str(target.resolve()),
                    "fileName": original,
                    "mimeType": mime_type or "application/octet-stream",
                    "size": len(raw),
                    "sha256": hashlib.sha256(raw).hexdigest(),
                },
            }
        except (KeyError, ValueError, binascii.Error, OSError) as error:
            return {
                "id": request_id,
                "error": {"code": -32602, "message": str(error)},
            }


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
