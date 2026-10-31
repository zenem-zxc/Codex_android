param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

$BuildPython = Join-Path $Root ".venv-build\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $BuildPython)) {
    $BuildPython = (Get-Command python.exe -ErrorAction Stop).Source
}
$EntryPoint = Join-Path $Root "pc_bridge\bridge.py"

if ($Clean) {
    Write-Host "Using PyInstaller's own --clean option; existing build and dist files are preserved."
}

& $BuildPython -c "import importlib.metadata as m, sys; v=tuple(map(int, m.version('pyinstaller').split('.')[:2])); print('PyInstaller', m.version('pyinstaller')); sys.exit(0 if v >= (5, 12) else 1)"
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller 5.12 or newer is required. Run: python -m pip install --upgrade pyinstaller"
}

& $BuildPython -m PyInstaller `
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
