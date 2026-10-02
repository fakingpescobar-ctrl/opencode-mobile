# verify-panels.ps1: the full check in one command.
#
# Dump and verify are deliberately separate steps (dump-header-panels.ps1 writes
# d_*.xml to the device, verify.mjs reads them) because the mobile agent can read
# /sdcard but cannot dump. This wrapper is the PC-side path: dump, pull, verify.
# What the agent runs by hand from his side maps to exactly these three steps.
[CmdletBinding()]
param(
    [string] $Adb = '',
    [int] $LaunchWaitSec = 9,
    [int] $DumpWaitSec = 3
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $Adb) {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\AppData\Local\Android\Sdk") | Where-Object { $_ }
    $Adb = $roots | ForEach-Object { "$_\platform-tools\adb.exe" } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $Adb) { throw 'adb.exe not found' }
}

$dumpDir = Join-Path $root 'build\dumps'
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
Get-ChildItem -LiteralPath $dumpDir -Filter 'd_*.xml' -ErrorAction SilentlyContinue | Remove-Item -Force

# 1. dump on the device
& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'dump-header-panels.ps1') `
    -Adb $Adb -LaunchWaitSec $LaunchWaitSec -DumpWaitSec $DumpWaitSec
if ($LASTEXITCODE -ne 0) { throw "dump-header-panels.ps1 failed with $LASTEXITCODE" }

# 2. pull the dumps the verifier needs
$panels = @('none', 'settings', 'stt', 'mcp', 'color', 'font')
foreach ($p in $panels) {
    $remote = "/sdcard/d_$p.xml"
    & $Adb pull $remote (Join-Path $dumpDir "d_$p.xml") 2>&1 | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $dumpDir "d_$p.xml"))) { throw "missing dump: $remote" }
}

# 3. verify
& node (Join-Path $PSScriptRoot 'verify.mjs') "--dir=$dumpDir"
$code = $LASTEXITCODE
"verify.mjs exit=$code, dumps in $dumpDir"
exit $code