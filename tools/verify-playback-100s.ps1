# One shot: play one track and prove the phone keeps playing for 100+ seconds.
#
# WHY THIS SCRIPT EXISTS
# A Ynison dial while the token is throttled re-arms the hour, so "check whether music works"
# by hand is exactly the way to never find out. Measured 26.10.2026: the same token was
# refused at 02:56, 03:58, 05:54 and 06:12, each time with a fresh 3600s. So the check has to
# be: refuse to dial until the deadline, then dial ONCE, then measure the REAL MediaSession
# (dumpsys) instead of asking Ynison for its own echo.
#
#   pwsh -File tools\verify-playback-100s.ps1              # refuses while the ban runs
#   pwsh -File tools\verify-playback-100s.ps1 -Query "Not Afraid" -Artist Eminem
#   pwsh -File tools\verify-playback-100s.ps1 -Seconds 130 -Force
#
# Exit codes: 0 = position grew while PLAYING, 1 = still banned / no playback, 2 = setup broken.
param(
    [string]$Query = "Not Afraid",
    [string]$Artist = "Eminem",
    [int]$Seconds = 110,
    [int]$EverySec = 10,
    [switch]$Force,
    [string]$Serial = "3B15AX00M9E00000",
    [int]$Port = 4297
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.opencode.mobile.debug"
$goaway = "/storage/emulated/0/Documents/OpencodeTerminal/ynison-goaway.json"

function Adb([string]$cmd) { & $adb -s $Serial shell $cmd 2>&1 }

# --- 1. the deadline first: a dial before it costs another hour --------------------
$raw = (Adb "cat $goaway" | Out-String).Trim()
if ($raw) {
    try {
        $until = [DateTimeOffset]::FromUnixTimeMilliseconds([int64](($raw | ConvertFrom-Json).until)).LocalDateTime
    } catch {
        $until = $null
    }
    if ($until -and $until -gt (Get-Date)) {
        $left = [int][Math]::Ceiling(($until - (Get-Date)).TotalMinutes)
        Write-Host ("still throttled until {0:yyyy-MM-dd HH:mm:ss} ({1} min left) - not dialling" -f $until, $left)
        if (-not $Force) { exit 1 }
        Write-Host "-Force: dialling anyway, the interval will be re-armed"
    }
}

# --- 2. token for the local music MCP, read from its own process ------------------
$line = (Adb "ps -A -o PID,ARGS 2>/dev/null | grep ynison.js | grep -v grep" | Out-String).Trim()
if (-not $line) { Write-Host "music MCP is not running on the device"; exit 2 }
$mcpPid = [int](($line -split '\s+')[0])
$envRaw = (Adb "run-as $pkg cat /proc/$mcpPid/environ" | Out-String)
$token = $null
foreach ($kv in ($envRaw -split "[`0`n]")) {
    if ($kv -match '^MCP_YNISON_TOKEN=(.+)$') { $token = $Matches[1] }
}
if (-not $token) { Write-Host "MCP_YNISON_TOKEN not found in the music MCP env"; exit 2 }

& $adb -s $Serial forward "tcp:$Port" "tcp:4200" | Out-Null
$url = "http://127.0.0.1:$Port/mcp"
$headers = @{
    "Content-Type"  = "application/json"
    "Accept"        = "application/json, text/event-stream"
    "Authorization" = "Bearer $token"
}

function Call-Music([string]$name, $args) {
    $body = @{ jsonrpc = "2.0"; id = 1; method = "tools/call"; params = @{ name = $name; arguments = $args } } |
        ConvertTo-Json -Depth 8 -Compress
    $res = Invoke-WebRequest -Uri $url -Method POST -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 240
    $text = $res.Content
    $dataLine = ($text -split "`n" | Where-Object { $_ -like "data: *" } | Select-Object -First 1)
    $json = if ($dataLine) { $dataLine.Substring(6) | ConvertFrom-Json } else { $text | ConvertFrom-Json }
    $out = ($json.result.content | ForEach-Object { $_.text }) -join " "
    return @{ text = $out; isError = [bool]$json.result.isError }
}

# --- 3. ONE dial: ask for the track -----------------------------------------------
Write-Host "dialling once: `"$Query`" / $Artist"
$started = Get-Date
$play = Call-Music "music_play_query" @{ query = $Query; artist = $Artist }
Write-Host ("music_play_query -> {0} ({1:N1}s)" -f $play.text, ((Get-Date) - $started).TotalSeconds)
if ($play.isError) { exit 1 }

# --- 4. measure the REAL MediaSession, not Ynison's echo --------------------------
function Get-Session {
    $dump = (Adb "dumpsys media_session" | Out-String)
    $block = ($dump -split "package=") | Where-Object { $_ -like "ru.yandex.music*" } | Select-Object -First 1
    if (-not $block) { return $null }
    $state = if ($block -match 'state=PlaybackState \{state=(\w+)\((\d+)\), position=(\d+)') {
        @{ name = $Matches[1]; code = [int]$Matches[2]; position = [int]$Matches[3] }
    } else { $null }
    $title = if ($block -match 'metadata: size=\d+, description=(.+?), null') { $Matches[1] } else { "" }
    return @{ state = $state; title = $title }
}

$first = $null
$last = $null
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $s = Get-Session
    $sec = [int]((Get-Date) - $started).TotalSeconds
    if ($s -and $s.state) {
        Write-Host ("[+{0,3}s] {1,-8} position={2,6:N1}s  {3}" -f $sec, $s.state.name, ($s.state.position / 1000), $s.title)
        if (-not $first) { $first = $s }
        $last = $s
    } else {
        Write-Host ("[+{0,3}s] no media session" -f $sec)
    }
    Start-Sleep -Seconds $EverySec
}

if (-not $first -or -not $last) { Write-Host "no MediaSession for ru.yandex.music - playback never started"; exit 1 }
$grown = ($last.state.position - $first.state.position) / 1000
$elapsed = ($Seconds - $EverySec)
Write-Host ("first: {0} at {1:N1}s, last: {2} at {3:N1}s, grew {4:N1}s over ~{5}s" -f `
    $first.state.name, ($first.state.position / 1000), $last.state.name, ($last.state.position / 1000), $grown, $elapsed)
if ($last.state.code -eq 3 -and $grown -ge ($elapsed * 0.8)) {
    Write-Host "PASS: PLAYING and the position tracked real time"
    exit 0
}
Write-Host "FAIL: state=$($last.state.name), position grew ${grown}s"
exit 1
