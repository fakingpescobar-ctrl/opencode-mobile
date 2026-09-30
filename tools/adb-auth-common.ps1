# Общая утилита: пароль opencode serve для Basic auth к локальному REST-мосту.
#
# Пароль НЕ лежит в файлах (в префсах, шифруется AES-256/GCM + AndroidKeyStore) и
# НЕ зашивается в скрипты. Он есть только в переменных окружения дочернего
# процесса `opencode serve`. Приложение и serve делят UID, поэтому читаем /proc
# через run-as. Значение каждый раз своё - полагаться на фиксированное нельзя.
#
# Три ловушки, все проверены на устройстве (OnePlus CPH2747, ColorOS):
#  1) $pid - зарезервированная автопеременная PowerShell (PID текущего процесса,
#     только для чтения). Присваивание в неё роняет скрипт.
#  2) pidof НЕ годится: отдаёт PID не того процесса (app_process/наследник zygote),
#     в его environ только zygote-переменные и никакого OPENCODE_SERVER_PASSWORD.
#     Читаемый /proc/<pid>/environ с правильным владельцем - не гарантия нужного процесса.
#  3) Цикл с 'for' через `adb shell <строка>` ломается о квотирование: PowerShell
#     съедает кавычки, удалённый shell видит команду разорванной. Поэтому скан
#     уходит на устройство ФАЙЛОМ.
# Скан обязательно идёт под run-as, иначе hidepid не даст прочитать environ соседей.

$script:OcAdb = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:OcPkg = "org.opencode.mobile.debug"

# Возвращает пароль сервера либо $null, если serve не запущен / переменной нет.
function Get-OpencodeServePassword {
    $scannerLocal = Join-Path $env:TEMP ("oc_find_pw_{0}.sh" -f [Guid]::NewGuid().ToString('N'))
    $scannerRemote = "/data/local/tmp/oc_find_pw.sh"
    try {
        @'
for p in /proc/[0-9]*; do
  if grep -qa OPENCODE_SERVER_PASSWORD "$p/environ" 2>/dev/null; then
    echo "FOUND ${p#/proc/}"
    break
  fi
done
'@ | Set-Content -LiteralPath $scannerLocal -NoNewline

        & $script:OcAdb push $scannerLocal $scannerRemote 2>&1 | Out-Null
        & $script:OcAdb shell "chmod 755 $scannerRemote" 2>&1 | Out-Null

        $hit = & $script:OcAdb shell "run-as $script:OcPkg sh $scannerRemote" 2>&1 |
            Select-String 'FOUND (\d+)' | Select-Object -First 1
        if (-not $hit) { return $null }

        $servePid = $hit.Matches[0].Groups[1].Value
        $environ = (& $script:OcAdb shell "run-as $script:OcPkg cat /proc/$servePid/environ" 2>$null) -join ''
        $var = $environ -split "`0" |
            Where-Object { $_ -like 'OPENCODE_SERVER_PASSWORD=*' } |
            Select-Object -First 1
        if (-not $var) { return $null }
        return ($var -split '=', 2)[1].Trim()
    } finally {
        if (Test-Path $scannerLocal) { Remove-Item -LiteralPath $scannerLocal -ErrorAction SilentlyContinue }
        & $script:OcAdb shell "rm -f $scannerRemote" 2>&1 | Out-Null
    }
}

# Заголовки для Invoke-RestMethod к локальному REST opencode. Бросает, если пароль не найден.
function Get-OpencodeAuthHeader {
    $pw = Get-OpencodeServePassword
    if (-not $pw) {
        throw "OPENCODE_SERVER_PASSWORD не найден в окружении opencode serve (приложение запущено?)"
    }
    return @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$pw")) }
}