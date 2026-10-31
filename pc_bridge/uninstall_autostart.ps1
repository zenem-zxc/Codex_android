$ErrorActionPreference = "Stop"
Unregister-ScheduledTask -TaskName "Codex Android Bridge" -Confirm:$false -ErrorAction SilentlyContinue
Write-Host "Removed scheduled task: Codex Android Bridge"
