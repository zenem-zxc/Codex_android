# Windows bridge

## Run manually

```powershell
python .\pc_bridge\bridge.py --host 127.0.0.1 --port 8765
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
