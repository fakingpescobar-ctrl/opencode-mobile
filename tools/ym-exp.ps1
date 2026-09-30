# Run one EXP variant against a freshly (re)launched Yandex Music, and watch the
# REAL MediaSession instead of ynison's echo of our own write.
#
#   .\tools\ym-exp.ps1 -Variant nowrite
#   .\tools\ym-exp.ps1 -Variant nohandback
#
# The stall only reproduced when ensurePhone had to LAUNCH the app, so every run
# force-stops first - that reproduces the launch path on purpose.

param(
    [Parameter(Mandatory = $true)][string]$Variant,
    [string]$Prompt = "Включи трек Not Afraid Eminem. Больше ничего не делай.",
    [int]$Samples = 18,
    [int]$EverySec = 5
)

$ErrorActionPreference = 'SilentlyContinue'
$adb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$EXP = "/data/data/org.opencode.mobile.debug/files/workspace/ynison-exp.txt"
$SID = "ses_f11009c05ffegIphBMU5xrn3tr"

function Get-Fg {
    $r = & $adb shell "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity'"
    if ("$r" -match '([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+') { $Matches[1] } else { '?' }
}

# Пароль сервера НЕ зашит в скрипт (правило из AGENTS.md): он живёт в env дочернего
# opencode serve и на диске его нет - лежит зашифрованным в префсах.
#
# Две ловушки, обе проверены на устройстве:
#  1) pidof НЕ подходит. Он отдаёт pid не того процесса (напр. app_process/zygote-наследника),
#     и в его environ только zygote-переменные - пароля там нет.
#  2) Цикл с 'for' через adb shell ломается о квотирование: PowerShell съедает кавычки,
#     удалённый shell видит команду разорванной. Поэтому скан уходит ФАЙЛОМ.
# Скан идёт от имени UID приложения (run-as), иначе hidepid не даст прочитать environ соседей.
function Read-ServerPassword {
    $pkg = 'org.opencode.mobile.debug'
    $scanner = '/data/local/tmp/find_pw.sh'
    $local = Join-Path $env:TEMP 'opencode_find_pw.sh'
    @'
for p in /proc/[0-9]*; do
  if grep -qa OPENCODE_SERVER_PASSWORD $p/environ 2>/dev/null; then
    echo "FOUND ${p#/proc/}"
  fi
done
'@ | Set-Content -LiteralPath $local -NoNewline
    & $adb push $local $scanner 2>&1 | Out-Null
    $hit = & $adb shell "run-as $pkg sh $scanner" 2>$null | Select-String 'FOUND (\d+)' | Select-Object -First 1
    if (-not $hit) { return $null }
    $servePid = $hit.Matches[0].Groups[1].Value
    $environ = (& $adb shell "run-as $pkg cat /proc/$servePid/environ" 2>$null) -join ''
    $var = $environ -split "`0" | Where-Object { $_ -like 'OPENCODE_SERVER_PASSWORD=*' } | Select-Object -First 1
    if (-not $var) { return $null }
    ($var -split '=', 2)[1].Trim()
}
function Get-Session {
    $ms = & $adb shell "dumpsys media_session 2>/dev/null"
    $ix = ($ms | Select-String "package=ru.yandex.music" | Select-Object -First 1).LineNumber
    if (-not $ix) { return $null }
    $seg = $ms[$ix..([Math]::Min($ix + 30, $ms.Count - 1))]
    $st = $seg | Select-String "state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+)" | Select-Object -First 1
    $dk = $seg | Select-String "description=([^,]*)" | Select-Object -First 1
    $ac = $seg | Select-String "active=(\w+)" | Select-Object -First 1
    [pscustomobject]@{
        State  = $st.Matches.Groups[1].Value
        Pos    = [int]$st.Matches.Groups[2].Value
        Track  = $dk.Matches.Groups[1].Value
        Active = $ac.Matches.Groups[1].Value
    }
}
function Show([string]$label, [int]$t) {
    $s = Get-Session
    if (-not $s) { Write-Host ("  +{0,3}c {1,-9} НЕТ сессии  fg={2}" -f $t, $label, (Get-Fg)); return }
    Write-Host ("  +{0,3}c {1,-9} {2,-8} pos={3,-7} act={4,-5} fg={5}  '{6}'" -f `
        $t, $label, $s.State, $s.Pos, $s.Active, (Get-Fg), $s.Track)
}

Write-Host "########## VARIANT: $Variant ##########"
& $adb shell "run-as org.opencode.mobile.debug sh -c 'echo -n $Variant > $EXP; cat $EXP; echo'"

# Kill the app so ensurePhone is forced down the launch path - that is the only
# condition under which the ~65s stall reproduced.
Write-Host "force-stop ru.yandex.music (чтобы ensurePhone пошёл в launch)..."
& $adb shell am force-stop ru.yandex.music
Start-Sleep -Seconds 4
Write-Host "сессия после kill: $(if (Get-Session) { (Get-Session).State } else { 'нет' })"

& $adb forward --remove-all 2>&1 | Out-Null
& $adb forward tcp:4098 tcp:4096 | Out-Null
# Пароль НЕ зашит в скрипт (правило из AGENTS.md): он лежит в env дочернего opencode serve,
# а UID у него тот же, что и у приложения - читается через run-as. Значение каждый раз своё,
# у debug-сборок оно фиксированное, но полагаться на это нельзя.
$ServerPassword = Read-ServerPassword
if (-not $ServerPassword) { throw "OPENCODE_SERVER_PASSWORD не найден в окружении serve" }
$h = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$ServerPassword")); "Content-Type" = "application/json" }
$body = @{ parts = @(@{ type = "text"; text = $Prompt }) } | ConvertTo-Json -Depth 6

$t0 = Get-Date
$reply = Invoke-RestMethod -Method POST "http://127.0.0.1:4098/session/$SID/message" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
$secs = [int]((Get-Date) - $t0).TotalSeconds
Write-Host "агент (${secs}c): $((($reply.parts | Where-Object { $_.type -eq 'text' } | ForEach-Object { $_.text }) -join ' '))"
Write-Host "--- РЕАЛЬНО ---"
$prev = ""
for ($i = 0; $i -lt $Samples; $i++) {
    $s = Get-Session
    $cur = if ($s) { $s.State } else { "NOSESSION" }
    Show "" ([int]((Get-Date) - $t0).TotalSeconds)
    if ($cur -ne $prev -and $cur -eq "PAUSED") { Write-Host "  >>> СТОП: $prev -> PAUSED на $($s.Pos)"; break }
    $prev = $cur
    Start-Sleep -Seconds $EverySec
}
Show "итог" ([int]((Get-Date) - $t0).TotalSeconds)
Write-Host ""
Write-Host "--- что ynison писал в этом прогоне ---"
$raw = & $adb shell "run-as org.opencode.mobile.debug cat /data/data/org.opencode.mobile.debug/files/opencode.log" 2>$null
$text = $raw -join "`n"
if ($text.Length -gt 40000) { $text = $text.Substring($text.Length - 40000) }
($text -split "`n") | Select-String -Pattern "EXP |launching ru.yandex|already online|genuinely absent|wrote player_state|verify attempt|attempt . of " |
    Select-Object -Last 14 | ForEach-Object { Write-Host ("   " + $_.ToString().Trim()) }
