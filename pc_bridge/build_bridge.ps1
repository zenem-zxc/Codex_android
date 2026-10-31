param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

$PyInstaller = (Get-Command pyinstaller.exe -ErrorAction Stop).Source
$EntryPoint = Join-Path $Root "pc_bridge\bridge.py"

if ($Clean) {
    Remove-Item -LiteralPath (Join-Path $Root "build") -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $Root "dist") -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $Root "CodexBridge.spec") -Force -ErrorAction SilentlyContinue
}

& $PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --name CodexBridge `
    --console `
    $EntryPoint

if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller failed with exit code $LASTEXITCODE."
}

Write-Host ""
Write-Host "Built: $Root\dist\CodexBridge.exe"
