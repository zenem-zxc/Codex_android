# Windows bridge

## Build the Windows executable

From the repository root:

```powershell
python -m venv .venv-build
.\.venv-build\Scripts\python.exe -m pip install --upgrade pyinstaller
powershell -ExecutionPolicy Bypass `
  -File .\pc_bridge\build_bridge.ps1 -Clean
```

The build script automatically uses `.venv-build` when it exists. PyInstaller
5.12 or newer is required for the installed Python 3.11 runtime.

Output:

```text
dist\CodexBridge.exe
```

Python is only required during the build. The resulting executable contains
the bridge runtime; Codex itself is still required on the Windows PC.

If startup fails with a missing `ipaddress` module, upgrade PyInstaller to
version 5.12 or newer and rebuild. Older versions can omit a Python 3.11.4+
bootstrap dependency even when the build completes.

## Run manually

After building:

```powershell
.\dist\CodexBridge.exe --host 127.0.0.1 --port 8765
```

The bridge starts `codex app-server --stdio` only after an authenticated
client connects. It forwards App Server JSONL messages in both directions:
requests, responses, notifications, and server approval requests. The
`bridge/upload` control method is handled by the bridge itself. The bridge
does not change `config.toml`, `auth.json`, or provider selection, but an
authenticated client can request operations that modify files or history.

## Install at logon

```powershell
powershell -ExecutionPolicy Bypass -File .\pc_bridge\install_autostart.ps1
```

For a phone on the same LAN, bind to the PC's private address or `0.0.0.0`
and allow only the chosen TCP port in Windows Firewall. For internet access,
prefer a private VPN such as Tailscale or ZeroTier. Do not port-forward this
raw TCP port to the public internet.

The client protocol is:

1. connect TCP;
2. send `codex-bridge <token>\n`;
3. receive `{"ok":true,...}\n`;
4. exchange App Server JSON-RPC lines unchanged;
5. the Android client sends `initialize`, waits for its response, then uses
   the normal App Server protocol.

One client is allowed at a time in this MVP, preventing two phones from
driving the same Codex session concurrently.

## Upload control method

The bridge accepts an additional authenticated control request:

```json
{
  "id": 10,
  "method": "bridge/upload",
  "params": {
    "fileName": "photo.jpg",
    "mimeType": "image/jpeg",
    "dataBase64": "..."
  }
}
```

Files are written to `%USERPROFILE%\.codex\bridge-uploads\<thread-id>\<model-hash>`,
with generated names and a 10 MiB limit. The response includes the absolute host path and
SHA-256. This method is not forwarded to Codex. The Android client may use the
returned path as a `localImage` input; document input still needs a separate
protocol integration. Uploads are not automatically cleaned up. Treat them as
sensitive files; token authentication grants access to the full App Server
protocol, not just uploads. The TCP transport does not provide TLS.

For larger files clients use `bridge/upload/start` (`fileName`, byte `size`),
then `bridge/upload/chunk` (`uploadId`, `dataBase64`) with chunks of at most
256 KiB, followed by `bridge/upload/finish` (`uploadId`). The stream limit is
1 GiB per file. `bridge/upload/cancel` discards an incomplete upload; the
bridge also removes incomplete uploads on disconnect. Finished uploads remain
on disk until manually removed. An image path can be sent as `localImage`;
other file paths are provided as text and their use depends on Codex access.
Streamed uploads must include `threadId`; the optional selected model is
hashed into the second directory level, preventing a shared attachment
directory across sessions/models.
