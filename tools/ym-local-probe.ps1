# Run the music MCP locally under bun with a FRESH device id.
#
# Why: the phone's music MCP is banned by Ynison (500140000, stay away 3600s) while the real
# Yandex Music app on the same phone plays fine. That points at a per-device-id block, not at
# the account, the token or the network. This script reproduces the stack on the PC with a new
# id to prove or disprove it - without touching the banned id again (each attempt re-arms it).
#
#   pwsh -File tools\ym-local-probe.ps1 -Port 4301
#
# The token is read from the running MCP process on the device and never written to disk.
param(
    [int]$Port = 4301,
    [string]$DeviceId = "",
    [string]$Serial = "3B15AX00M9E00000"
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.opencode.mobile.debug"
$dir = Join-Path $env:TEMP "ym-local"

function Adb([string]$cmd) { & $adb -s $Serial shell $cmd 2>&1 }

function Get-Env([int]$procId) {
    $raw = (Adb "run-as $pkg cat /proc/$procId/environ" | Out-String)
    $map = @{}
    foreach ($kv in ($raw -split "[`0`n]")) {
        if ($kv -match '^([A-Z_0-9]+)=(.*)$') { $map[$Matches[1]] = $Matches[2] }
    }
    return $map
}

# The music MCP is the process whose argv holds ynison.js, not the first bun on the device.
$line = (Adb "ps -A -o PID,ARGS 2>/dev/null | grep ynison.js | grep -v grep" | Out-String).Trim()
if (-not $line) { throw "music MCP is not running on the device" }
$mcpPid = [int](($line -split '\s+')[0])
Write-Host "music MCP pid: $mcpPid"

$envMap = Get-Env $mcpPid
foreach ($key in @("YNISON_TOKEN", "YNISON_WS_IP", "YNISON_API_IP")) {
    if (-not $envMap.ContainsKey($key)) { throw "$key missing in the device env" }
}

if (-not $DeviceId) {
    # Same shape as the app's own id: 10 chars, letters and digits.
    $alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".ToCharArray()
    $DeviceId = -join (1..10 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })
}
Write-Host "probe device id: $DeviceId (phone uses $($envMap['YNISON_DEVICE_ID']))"

New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item "app\src\main\assets\mcp\ynison.js" (Join-Path $dir "ynison.js") -Force
$stateCache = Join-Path $dir "ynison-state.json"
Remove-Item $stateCache, (Join-Path $dir "ynison-goaway.json") -ErrorAction SilentlyContinue

$bun = "$env:USERPROFILE\.bun\bin\bun.exe"
$log = Join-Path $dir "probe.log"
$err = Join-Path $dir "probe.err"
Remove-Item $log, $err -ErrorAction SilentlyContinue

$env:YNISON_TOKEN = $envMap["YNISON_TOKEN"]
$env:YNISON_TCP_PORT = "$Port"
$env:MCP_YNISON_TOKEN = "local-probe"
$env:YNISON_DEVICE_ID = $DeviceId
$env:YNISON_WS_IP = $envMap["YNISON_WS_IP"]
if ($envMap.ContainsKey("YNISON_WS_PORT")) { $env:YNISON_WS_PORT = $envMap["YNISON_WS_PORT"] }
$env:YNISON_API_IP = $envMap["YNISON_API_IP"]
$env:YNISON_STATE_CACHE = $stateCache
$env:YNISON_TRACE_FILE = Join-Path $dir "probe-trace.txt"
$env:YNISON_DEBUG = "1"

$proc = Start-Process -FilePath $bun -ArgumentList (Join-Path $dir "ynison.js") `
    -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden -PassThru
Write-Host "bun pid: $($proc.Id), log: $log"

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect("127.0.0.1", $Port)
        $tcp.Close()
        Write-Host "listening on $Port"
        break
    } catch { }
}
if (-not $proc.HasExited) { Write-Host "started" } else { Write-Host "bun exited: $((Get-Content $err -Raw))" }
