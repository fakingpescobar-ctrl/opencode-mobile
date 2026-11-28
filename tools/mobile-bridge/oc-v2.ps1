# Мост к opencode v2 на телефоне через adb forward tcp:4098 -> tcp:4096.
# Старый bridge.mjs рассчитан на v1 (/session/... без /api и без Basic auth):
# на живом сервере 2.4.0 эти пути отдают SPA-HTML, поэтому нужен этот клиент.
#
# Примеры:
#   pwsh -File tools/mobile-bridge/oc-v2.ps1 ping
#   pwsh -File tools/mobile-bridge/oc-v2.ps1 sessions -Limit 5
#   pwsh -File tools/mobile-bridge/oc-v2.ps1 history -Limit 5
#   pwsh -File tools/mobile-bridge/oc-v2.ps1 ask "Привет, это проверка канала"
#   pwsh -File tools/mobile-bridge/oc-v2.ps1 abort

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('ping', 'sessions', 'active', 'history', 'ask', 'abort')]
    [string]$Command,

    [string]$Session,
    [string[]]$Text,
    [int]$Limit = 8,
    [int]$TimeoutSec = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

. (Join-Path $PSScriptRoot '..\adb-auth-common.ps1')

$adb = $script:OcAdb
$stateFile = Join-Path $env:TEMP 'opencode-mobile-active-session.txt'
$base = 'http://127.0.0.1:4098/api'

function Ensure-Forward {
    $list = (& $adb forward --list) -join "`n"
    if ($list -notmatch 'tcp:4098') {
        & $adb forward tcp:4098 tcp:4096 | Out-Null
    }
}

function Invoke-OcApi {
    param([string]$Path, [string]$Method = 'GET', $Body = $null, [int]$Timeout = 60)
    $headers = Get-OpencodeAuthHeader
    $params = @{ Uri = "$base$Path"; Method = $Method; Headers = $headers; TimeoutSec = $Timeout }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json'
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress))
    }
    return Invoke-RestMethod @params
}

function Get-SessionId {
    if ($Session) { return $Session }
    if (Test-Path -LiteralPath $stateFile) {
        $saved = (Get-Content -LiteralPath $stateFile -Raw).Trim()
        if ($saved) { return $saved }
    }
    $sessions = Invoke-OcApi '/session'
    $latest = $sessions.data | Sort-Object { $_.time.updated } -Descending | Select-Object -First 1
    if (-not $latest) { throw 'Нет ни одной сессии; сначала открой её в приложении' }
    return $latest.id
}

function Get-Prop {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $p = $Object.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Show-Text {
    param($Messages, [int]$Count = 8)
    $items = @()
    if ($null -ne (Get-Prop $Messages 'data')) { $items = @((Get-Prop $Messages 'data')) }
    elseif ($null -ne (Get-Prop $Messages 'content')) { $items = @($Messages) }
    # v2 отдаёт сообщения новыми к старым — берём первые, а не последние.
    foreach ($m in ($items | Select-Object -First $Count)) {
        $role = Get-Prop $m 'role'
        if (-not $role) { $role = Get-Prop $m 'type' }
        if (-not $role) { $role = 'message' }
        $parts = Get-Prop $m 'content'
        if (-not $parts) { $parts = Get-Prop $m 'parts' }
        $parts = @($parts)
        $text = ($parts | Where-Object { (Get-Prop $_ 'type') -eq 'text' -and (Get-Prop $_ 'text') } | ForEach-Object { Get-Prop $_ 'text' }) -join "`n"
        if (-not $text) { $text = [string](Get-Prop $m 'text') }
        $tools = ($parts | Where-Object { (Get-Prop $_ 'type') -eq 'tool' -and (Get-Prop $_ 'name') } | ForEach-Object { Get-Prop $_ 'name' }) -join ','
        if ($text -or $tools) {
            "[$role] $text"
            if ($tools) { "    tools: $tools" }
        }
    }
}

Ensure-Forward

switch ($Command) {
    'ping' {
        $info = Invoke-OcApi '/info'
        "opencode $($info.version), pid $($info.pid), forward tcp:4098 -> tcp:4096"
        "active session: $(Get-SessionId)"
    }
    'sessions' {
        $s = Invoke-OcApi '/session'
        foreach ($x in ($s.data | Sort-Object { $_.time.updated } -Descending | Select-Object -First $Limit)) {
            $t = [DateTimeOffset]::FromUnixTimeMilliseconds($x.time.updated).ToString('MM-dd HH:mm:ss')
            "$($x.id)  $t  $($x.outcome)  $($x.title)"
        }
    }
    'active' {
        if ($Session) {
            Set-Content -LiteralPath $stateFile -Value $Session -NoNewline
            "active session: $Session"
        } else {
            "active session: $(Get-SessionId)"
        }
    }
    'history' {
        $id = Get-SessionId
        $m = Invoke-OcApi "/session/$id/message"
        "session: $id"
        Show-Text -Messages $m -Count $Limit
    }
    'ask' {
        if (-not $Text -or -not ($Text -join ' ').Trim()) { throw 'ask: нужен текст вопроса' }
        $id = Get-SessionId
        Set-Content -LiteralPath $stateFile -Value $id -NoNewline
        $prompt = @{ text = ($Text -join ' ') }
        "session: $id"
        "prompt: $($prompt.text)"
        # POST только ставит сообщение в очередь: ответ генерируется асинхронно.
        $before = Invoke-OcApi "/session/$id/message"
        $beforeId = if ($before.data.Count) { $before.data[0].id } else { '' }
        $null = Invoke-OcApi "/session/$id/prompt" -Method POST -Body $prompt -Timeout $TimeoutSec
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
        $m = $null
        while ([DateTime]::UtcNow -lt $deadline) {
            $m = Invoke-OcApi "/session/$id/message"
            $fresh = @($m.data | Where-Object { $_.id -ne $beforeId -and (Get-Prop $_ 'type') -eq 'assistant' }) | Select-Object -First 1
            if ($fresh) { break }
            Start-Sleep -Seconds 2
        }
        if (-not $m) { throw 'Не удалось дочитать ответ агента' }
        Show-Text -Messages $m -Count 4
    }
    'abort' {
        $id = Get-SessionId
        $r = Invoke-OcApi "/session/$id/interrupt" -Method POST
        "abort: $id"
        if ($r) { $r | ConvertTo-Json -Depth 3 -Compress }
    }
}
