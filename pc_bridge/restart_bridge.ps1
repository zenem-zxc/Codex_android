param(
    [string]$HostAddress = "100.114.88.98",
    [ValidateRange(1, 65535)]
    [int]$Port = 8765,
    [string]$CodexPath = "",
    [string]$TokenFile = (Join-Path $env:USERPROFILE ".codex\bridge.token")
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Executable = [System.IO.Path]::GetFullPath((Join-Path $Root "dist\CodexBridge.exe"))
$BuildScript = Join-Path $PSScriptRoot "build_bridge.ps1"

if ($CodexPath -and -not (Test-Path -LiteralPath $CodexPath -PathType Leaf)) {
    throw "Codex executable not found: $CodexPath"
}

# Match the exact executable path, not every process named CodexBridge.
$Candidates = @(Get-Process -Name "CodexBridge" -ErrorAction SilentlyContinue)
$Owned = @()
foreach ($Process in $Candidates) {
    try {
        $ProcessPath = $Process.Path
        if (-not $ProcessPath) {
            throw "Executable path is unavailable for process $($Process.Id); refusing to stop it."
        }
        if ([string]::Equals(
            [System.IO.Path]::GetFullPath($ProcessPath),
            $Executable,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            $Owned += $Process
        }
    } catch {
        throw "Cannot identify CodexBridge process $($Process.Id): $_"
    }
}

foreach ($Process in $Owned) {
    Write-Host "Stopping bridge process $($Process.Id)..."
    Stop-Process -Id $Process.Id -ErrorAction Stop
}
foreach ($Process in $Owned) {
    try {
        Wait-Process -Id $Process.Id -Timeout 15 -ErrorAction Stop
    } catch [Microsoft.PowerShell.Commands.ProcessCommandException] {
        # The process may have exited before Wait-Process looked it up.
        if (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue) { throw }
    }
}

Write-Host "Building bridge..."
& $BuildScript -Clean
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "Build did not produce $Executable"
}

$ProcessArgs = @(
    "--host", "`"$HostAddress`"",
    "--port", "$Port",
    "--token-file", "`"$TokenFile`""
)
if ($CodexPath) {
    $ProcessArgs += @("--codex", "`"$CodexPath`"")
}

Write-Host "Starting bridge..."
$Bridge = Start-Process -FilePath $Executable -ArgumentList $ProcessArgs `
    -WorkingDirectory $Root -WindowStyle Hidden -PassThru

# Check both process liveness and port ownership; an unrelated listener must
# not be mistaken for a successful launch.
$Started = $false
for ($Attempt = 0; $Attempt -lt 30; $Attempt++) {
    Start-Sleep -Milliseconds 500
    $Bridge.Refresh()
    if ($Bridge.HasExited) {
        throw "Bridge process exited with code $($Bridge.ExitCode). Check the address, port, and Codex path."
    }
    $Listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $Bridge.Id -and $_.LocalAddress -eq $HostAddress }
    if ($Listener) {
        $Started = $true
        break
    }
}
if (-not $Started) {
    throw "Bridge process $($Bridge.Id) started, but listening on ${HostAddress}:$Port was not confirmed. The process was left running for inspection."
}
Write-Host "Bridge is listening at ${HostAddress}:$Port (PID $($Bridge.Id))."
