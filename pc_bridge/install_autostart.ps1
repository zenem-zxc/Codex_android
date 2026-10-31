param(
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8765
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Codex = (Get-Command codex.exe).Source
$Executable = Join-Path $Root "dist\CodexBridge.exe"
$TaskName = "Codex Android Bridge"
$TokenFile = Join-Path $env:USERPROFILE ".codex\bridge.token"

if (-not (Test-Path -LiteralPath $Executable)) {
    throw "CodexBridge.exe not found. Run .\pc_bridge\build_bridge.ps1 first."
}

# Store the token in the user's Codex directory; never print its value.
New-Item -ItemType Directory -Force -Path (Split-Path $TokenFile) | Out-Null
$args = "--host `"$HostAddress`" --port $Port --token-file `"$TokenFile`" --codex `"$Codex`""
$action = New-ScheduledTaskAction -Execute $Executable -Argument $args -WorkingDirectory $Root
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Days 3650)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
  -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host "Installed and started: $TaskName"
Write-Host "Host: $HostAddress  Port: $Port"
Write-Host "Token file: $TokenFile"
Write-Host "The token is not printed. Transfer it to the Android app through a secure channel."
