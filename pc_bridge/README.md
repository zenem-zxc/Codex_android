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
client connects. It forwards every JSONL message unchanged in both directions:
requests, responses, notifications, and server approval requests. It does not
change `config.toml`, `auth.json`, provider selection, workspace, or session
history.

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
