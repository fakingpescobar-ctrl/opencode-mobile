# Ground-truth probe for Yandex Music on the device.
#
# IMPORTANT: this deliberately does NOT trust ynison's server-side state.
# The ynison server only echoes back the writes WE sent, so `paused=false` from
# ynison proves nothing about whether the phone actually makes sound.
# Ground truth = the real android MediaSession state + real audio focus.

$ErrorActionPreference = 'SilentlyContinue'
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$PKG = "ru.yandex.music"
$LOG = "/data/data/org.opencode.mobile.debug/files/opencode.log"

function Get-LocalSession {
    $ms = & $adb shell "dumpsys media_session 2>/dev/null"
    if (-not $ms) { return $null }
    $i = ($ms | Select-String "package=$PKG" | Select-Object -First 1).LineNumber
    if (-not $i) { return $null }
    $seg = $ms[$i..([Math]::Min($i + 30, $ms.Count - 1))]
    $st = $seg | Select-String "state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+)" | Select-Object -First 1
    $de = $seg | Select-String "description=([^,]*)" | Select-Object -First 1
    $ac = $seg | Select-String "active=(\w+)" | Select-Object -First 1
    [pscustomobject]@{
        State = $st.Matches.Groups[1].Value
        Pos   = [int]$st.Matches.Groups[2].Value
        Track = $de.Matches.Groups[1].Value
        Active = $ac.Matches.Groups[1].Value
    }
}

function Get-Foreground {
    $r = & $adb shell "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity'"
    if ("$r" -match '([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+') { $Matches[1] } else { '?' }
}

# Pull the tail of the runtime log to the PC and grep locally: avoids all
# adb/sh quoting traps that silently produce empty output.
function Get-YnisonTail([int]$bytes = 120000, [string]$pattern = 'already online|genuinely absent|launching ru\.yandex\.music|device_id') {
    $raw = & $adb shell "run-as org.opencode.mobile.debug cat $LOG" 2>$null
    if (-not $raw) { return @() }
    $text = ($raw -join "`n")
    if ($text.Length -gt $bytes) { $text = $text.Substring($text.Length - $bytes) }
    return ($text -split "`n" | Select-String -Pattern $pattern | Select-Object -Last 6)
}

function Show-State([string]$label) {
    $s = Get-LocalSession
    if (-not $s) {
        Write-Host ("{0,-9} НЕТ MediaSession ({1} pid={2})" -f $label, $PKG, ((& $adb shell "pidof $PKG") -join ""))
        return
    }
    Write-Host ("{0,-9} {1,-8} pos={2,-7} active={3,-5} track='{4}'  fg={5}" -f `
        $label, $s.State, $s.Pos, $s.Active, $s.Track, (Get-Foreground))
}

Write-Host "=== ground truth ==="
Show-State "now"
Write-Host "=== ynison device view (tail) ==="
Get-YnisonTail | ForEach-Object { Write-Host ("   " + ($_.ToString().Trim())) }
