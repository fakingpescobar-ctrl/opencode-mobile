# Wait out the Ynison go-away ban, then run the cold-start measurement ONCE.
#
# Why this exists: every music tool call during a ban renews it for another hour,
# so the only way to close the music track is to stay completely silent until the
# deadline and then make exactly one measurement. Doing that by hand means sitting
# and watching the clock; this script does it unattended.
#
#   pwsh -File tools\verify-music-after-ban.ps1 [-MarginSec 70] [-SkipKill]
#
# Exit codes: 0 = measurement ran, 1 = prerequisite missing (app/serve/deadline).
param(
    [int]$MarginSec = 70,
    [switch]$SkipKill,
    [string]$Serial = "3B15AX00M9E00000"
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.opencode.mobile.debug"
$goaway = "/storage/emulated/0/Documents/OpencodeTerminal/ynison-goaway.json"

function Adb([string]$cmd) { & $adb -s $Serial shell $cmd 2>&1 }

function Get-BanUntil {
    $raw = (Adb "cat $goaway" | Out-String).Trim()
    if (-not $raw) { return $null }
    try {
        return [DateTimeOffset]::FromUnixTimeMilliseconds([int64](($raw | ConvertFrom-Json).until)).LocalDateTime
    } catch {
        return $null
    }
}

function Wait-Until([datetime]$target) {
    while ($true) {
        $left = ($target - (Get-Date)).TotalSeconds
        if ($left -le 0) { return }
        $chunk = [math]::Min(60, [math]::Ceiling($left))
        Write-Host ("waiting: {0:n0}s left (chunk {1}s)" -f $left, $chunk)
        Start-Sleep -Seconds $chunk
    }
}

function Ensure-App {
    if ((Adb "pidof $pkg" | Out-String).Trim()) { return }
    Write-Host "app is not running - launching"
    & $adb -s $Serial shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
    Start-Sleep -Seconds 20
}

function Ensure-Serve {
    for ($i = 0; $i -lt 12; $i++) {
        if ((Adb "pgrep -f 'serve --port 4096'" | Out-String).Trim()) { return }
        Write-Host "serve is not up yet - waiting 5s"
        Start-Sleep -Seconds 5
    }
    throw "serve did not start within 60s"
}

$until = Get-BanUntil
if ($until) {
    $target = $until.AddSeconds($MarginSec)
    Write-Host ("ban until {0:yyyy-MM-dd HH:mm:ss}, measuring at {1:HH:mm:ss}" -f $until, $target)
    Wait-Until $target
} else {
    Write-Host "no go-away file on device - measuring now"
}

Ensure-App
Ensure-Serve

$log = Join-Path (Get-Location) "measure-music.log"
$measureArgs = @("-NoProfile", "-File", "tools\measure-cold-start.ps1")
if ($SkipKill) { $measureArgs += "-SkipKill" }
& pwsh @measureArgs *> $log
$code = $LASTEXITCODE
"EXIT: $code" | Out-File $log -Append
Write-Host "measurement finished with exit $code, log: $log"
exit $code
