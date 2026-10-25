# Замер холодного старта Яндекс Музыки через музыкальный MCP.
#
# Что делает: убивает Яндекс.Музыку, засекает вызов music_play_query, проверяет,
# поднялось ли приложение, и добивает статусом плеера. Всё печатает таблицей.
# Вывод намеренно ASCII: кириллица в консоли рендерится в cp866-мусор.
#
#   pwsh -File tools\measure-cold-start.ps1 [-Query "Eminem"] [-SkipKill]
#
# Требует: adb, запущенное приложение, живой Ynison (вне бана).

param(
    [string]$Query = "Eminem",
    [switch]$SkipKill,
    [string]$Serial = "3B15AX00M9E00000"
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.opencode.mobile.debug"
$musicPkg = "ru.yandex.music"

function Adb([string]$cmd) { & $adb -s $Serial shell $cmd 2>&1 }

function Get-McpToken([string]$name) {
    # pgrep -f матчит сам себя и соседние процессы, поэтому первый pid часто уже мёртв
    # (на замере 26.10.2026 это дало пустой токен и "no closing quote" вместо ответа).
    # Берём первый pid, из которого переменная реально читается.
    $pids = (Adb "pgrep -f 'serve --port 4096'" | Out-String) -split "\s+" | Where-Object { $_ }
    if (-not $pids) { throw "serve is not running - start the app first" }
    foreach ($serve in $pids) {
        $line = (Adb "run-as $pkg sh -c 'tr `"\0`" `"\n`" < /proc/$serve/environ | grep ^${name}='" | Out-String).Trim()
        if ($line -like "${name}=*") { return ($line -replace "^${name}=", "").Trim() }
    }
    throw "$name not found in any serve process environment"
}

function Call-MusicTool([string]$tool, [hashtable]$toolArgs, [string]$token) {
    $body = @{
        jsonrpc = "2.0"
        id      = 1
        method  = "tools/call"
        params  = @{ name = $tool; arguments = $toolArgs }
    } | ConvertTo-Json -Depth 6 -Compress
    # Тело кладём файлом: кавычки JSON не выживают двойной проход через adb shell.
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    try {
        & $adb -s $Serial push $tmp /data/local/tmp/req.json 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "adb push failed with exit code $LASTEXITCODE" }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    Adb "run-as $pkg sh -c 'cp /data/local/tmp/req.json files/req.json'" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "copy of the request body failed with exit code $LASTEXITCODE" }

    $curl = 'run-as ' + $pkg + ' sh -c ''cd files && curl -s -m 200 -X POST http://127.0.0.1:4200/mcp ' +
            '-H "Authorization: Bearer ' + $token + '" ' +
            '-H "Accept: application/json, text/event-stream" ' +
            '-H "Content-Type: application/json" --data-binary @req.json'''
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $raw = Adb $curl
    $sw.Stop()
    $text = ($raw | Out-String).Trim()
    $parsed = $null
    try { $parsed = $text | ConvertFrom-Json } catch { }
    # Три исхода, и путать их нельзя: ответ tool'а, отказ JSON-RPC (нет result, есть
    # error) и мусор вместо JSON. Раньше отказ JSON-RPC печатался как успех.
    $isError = $true
    if ($parsed) { $isError = if ($null -ne $parsed.error) { $true } else { [bool]$parsed.result.isError } }
    $shown = if ($parsed -and $parsed.result.content) {
        ($parsed.result.content | ForEach-Object { $_.text }) -join " "
    } elseif ($parsed -and $null -ne $parsed.error) {
        "JSON-RPC error: " + ($parsed.error | ConvertTo-Json -Depth 5 -Compress)
    } else {
        $text
    }
    return [pscustomobject]@{
        Seconds = [Math]::Round($sw.Elapsed.TotalSeconds, 2)
        Text    = $shown
        IsError = $isError
    }
}

$token = Get-McpToken "MCP_YNISON_TOKEN"
if (-not $token) { throw "MCP_YNISON_TOKEN not found in serve environment" }

if (-not $SkipKill) {
    Adb "am force-stop $musicPkg" | Out-Null
    Start-Sleep -Seconds 2
    $running = (Adb "pidof $musicPkg" | Out-String).Trim()
    "music app after force-stop: $(if ($running) { 'STILL RUNNING' } else { 'stopped' })"
} else {
    "music app: left as is (-SkipKill)"
}

"`n--- music_play_query `"$Query`" ---"
$play = Call-MusicTool "music_play_query" @{ query = $Query } $token
"seconds: $($play.Seconds)"
"isError: $($play.IsError)"
"text:    $($play.Text)"

$musicPid = (Adb "pidof $musicPkg" | Out-String).Trim()
"`nmusic app after call: $(if ($musicPid) { "running (pid $musicPid)" } else { 'not running' })"

"`n--- music_status ---"
$status = Call-MusicTool "music_status" @{} $token
"seconds: $($status.Seconds)"
"isError: $($status.IsError)"
"text:    $($status.Text)"
