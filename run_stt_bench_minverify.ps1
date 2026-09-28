# run_stt_bench_minverify.ps1 - R8/minified device STT bench for the minVerify variant.
# ASCII-only on purpose: keeps rendering clean in Windows PowerShell 5.1 consoles.
#
#   pwsh -ExecutionPolicy Bypass -File run_stt_bench_minverify.ps1
#
# WHY THIS EXISTS, and why it does NOT use gradle at all
# ------------------------------------------------------
# minVerify is the build type that actually exercises R8 - which is the entire
# reason it exists. But `:app:connectedMinVerifyAndroidTest` cannot run the STT
# suite, for a chain of reasons that all have to be dodged at once:
#
#   1. gradle REINSTALLS the app APK before the test. On this ColorOS device
#      `adb install -r` WIPES THE APP'S DATA DIR. The models live in
#      files/models, so every reinstall deletes them.
#   2. With no models, `WhisperTranscribeService.modelsDir()` is empty, the
#      matrix/smoke/chunked tests bail out via assumeTrue, and the whole suite
#      reports a cheerful "OK (5 tests)" with nothing executed. That is the trap:
#      a green minVerify STT run is NOT evidence that anything ran.
#   3. The model source package (`org.opencode.mobile.debug`) may well be absent:
#      the debug connected task uninstalls itself and its models on exit, so
#      "copy from the debug package" is not a reliable shortcut.
#
# And the bigger one, fixed 27.09: minVerify used to set isDebuggable = true so
# that `run-as` could deliver the models. AGP disables optimization and
# obfuscation for debuggable builds, so R8 NEVER RAN - isMinifyEnabled was true
# by inheritance from release, but the APK shipped completely unobfuscated and
# gradle only warned. Every "R8 verified" claim was void.
#
# minVerify is now isDebuggable = false, so R8 is real. The cost is that `run-as`
# is gone, so this script no longer copies models into the package either: it
# stages them in /data/local/tmp, and SttModelBootstrap (androidTest) copies them
# into filesDir itself - the instrumentation runs in the app's own uid. The test
# also publishes the CSV back to /data/local/tmp, which is how the numbers below
# are read without run-as.
#
# So this script installs once, THEN delivers models, and drives the tests with
# `am instrument` so nothing reinstalls and wipes anything mid-run. It also
# VERIFIES that rows actually appeared, because "OK (5 tests)" alone cannot tell
# an executed test from a skipped one.
#
# Cost: the model payload is ~2.4 GB pushed at device speed (~40 MB/s, ~1 min).

param(
    [switch]$SkipPush,          # models already in the minVerify data dir
    [switch]$KeepStaging
)

$ErrorActionPreference = 'Stop'

$Repo     = 'C:\Projects\opencode-mobile'
$Adb      = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$Pkg      = 'org.opencode.mobile.minverify'
$TestPkg  = "$Pkg.test"
$Runner   = "$TestPkg/androidx.test.runner.AndroidJUnitRunner"
$ModelSrc = Join-Path $Repo 'tools\ncnn-int8\turbo'
$Stage    = '/data/local/tmp/ocmodels'
$Turbo    = 'ncnn-turbo'
$Fp32     = 'ncnn-bench-fp32'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Die($m)  { Write-Host "    $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $Adb)) { Die "adb not found: $Adb" }
if (-not $SkipPush -and -not (Test-Path $ModelSrc)) { Die "model source missing: $ModelSrc" }

# gradlew.bat dies instantly with exit 9009 ("JAVA_HOME is not set and no 'java'
# command could be found") when this script is launched via Start-Process, which
# does not inherit an exported JAVA_HOME. Resolve it here so the script is
# self-contained, exactly as run_stt_bench.ps1 does.
if (-not $env:JAVA_HOME) {
    $jd = @(
        'C:\Program Files\Android\Android Studio\jbr',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if ($jd) { $env:JAVA_HOME = $jd; Write-Host "    JAVA_HOME -> $jd" -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- build + install
# Order matters: the APK must exist BEFORE the models are pushed, because
# installing afterwards would wipe them again.
if (-not $SkipPush) {
    Step 'Building minVerify app + test APK (WITH the native build)'
    Push-Location $Repo
    try {
        # -PsttTestBuildType=minVerify is REQUIRED: by default AGP only generates
        # *debugAndroidTest* variants, so without it assembleMinVerifyAndroidTest
        # simply does not exist ("Task not found").
        #
        # DO NOT add -PskipNativeBuild here. That flag does not merely reuse cached
        # .so files - it skips the whole native pipeline, including the jniLibs copy
        # that feeds mergeNativeLibs, so the APK ships with NO whisper libs and every
        # STT test dies with:
        #   dlopen failed: library "libncnnwhisper.so" not found
        # (Same trap the debug flow in docs/EXPERIMENTS-STT-LATENCY.md warns about.)
        & (Join-Path $Repo 'gradlew.bat') :app:assembleMinVerify :app:assembleMinVerifyAndroidTest `
            -PsttTestBuildType=minVerify
        if ($LASTEXITCODE -ne 0) { Die "gradle failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
    Ok 'build done'

    $appApk  = Join-Path $Repo 'app\build\outputs\apk\minVerify\app-minVerify.apk'
    $testApk = Join-Path $Repo 'app\build\outputs\apk\androidTest\minVerify\app-minVerify-androidTest.apk'
    foreach ($a in @($appApk, $testApk)) { if (-not (Test-Path $a)) { Die "APK not found: $a" } }

    # Assert on the ARTIFACT, not on the flag we think we passed. A green gradle
    # build says nothing about whether the native libs made it into the APK, and
    # the resulting dlopen failure surfaces 2 minutes into a device run.
    Step 'Verifying the APK actually carries the native libs'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [System.IO.Compression.ZipFile]::OpenRead($appApk)
    try {
        $arm  = @($z.Entries | Where-Object { $_.FullName -like 'lib/arm64-v8a/*' })
        $need = @('libncnnwhisper.so', 'libggml-base.so')
        $missing = $need | Where-Object { -not ($z.Entries | Where-Object { $_.Name -eq $_ }) }
        Write-Host "    arm64 entries: $($arm.Count)" -ForegroundColor DarkGray
        if ($missing) { Die "APK is missing native lib(s): $($missing -join ', ')' - do NOT pass -PskipNativeBuild" }
        Ok 'native libs present'

        # THE ACCEPTANCE CRITERION: did R8 actually run?
        #
        # isMinifyEnabled = true in the config proves nothing. AGP silently
        # disables optimization and obfuscation for debuggable builds, so a
        # "minified" variant can ship completely unobfuscated while gradle stays
        # green and only emits a warning. That is not hypothetical - it is exactly
        # what happened here for months, and it made every "R8 verified" claim
        # worthless.
        #
        # Read the mapping file, NOT the dex. A dex was tried first and gave a
        # false negative twice over: R8 emits two-level names like "La/a;" (the
        # pattern only matched three-level), and the package-extraction regex ate
        # the whole descriptor. mapping.txt is R8's own record - if it is missing
        # or full of identity mappings, nothing was obfuscated.
    } finally { $z.Dispose() }

    Step 'Verifying R8 via the mapping file'
    $map = Join-Path $Repo 'app\build\outputs\mapping\minVerify\mapping.txt'
    if (-not (Test-Path $map)) { Die "no mapping.txt - R8 did not run (expected $map)" }
    $renames = @(Select-String -Path $map -Pattern '^\S+ -> \S+:$' | ForEach-Object { $_.Line })
    $renamed = @($renames | Where-Object {
        $p = $_ -split ' -> '
        $p[0].Trim() -ne $p[1].TrimEnd(':').Trim()
    })
    Write-Host "    mapping entries : $($renames.Count)" -ForegroundColor DarkGray
    Write-Host "    actually renamed: $($renamed.Count)" -ForegroundColor DarkGray
    if ($renamed.Count -eq 0) { Die 'mapping has no real renames - R8 did not obfuscate' }

    # The JNI class MUST keep its name: native code resolves it by string. This
    # is the rule that came from proguard-android-optimize.txt, and before
    # 27.09.2026 nobody could confirm it, because R8 was never running.
    $ncnn = @($renames | Where-Object { $_ -like '*com.whispercpp.whisper.NcnnWhisperLib ->*' })
    if (-not $ncnn) { Die 'NcnnWhisperLib is not in the mapping at all - JNI would fail' }
    if ($ncnn[0] -notlike 'com.whispercpp.whisper.NcnnWhisperLib -> com.whispercpp.whisper.NcnnWhisperLib:*') {
        Die "JNI keep rule did NOT hold: $($ncnn[0])"
    }
    Ok 'R8 verified: real renames present AND the JNI keep rule held'


    Step 'Installing APKs (this wipes the data dir - that is fine, it is empty)'
    & $Adb install -r -t $appApk  | Out-Null;  if ($LASTEXITCODE -ne 0) { Die 'app install failed' }
    & $Adb install -r -t $testApk | Out-Null;  if ($LASTEXITCODE -ne 0) { Die 'test install failed' }
    Ok 'both APKs installed'
}

# ---------------------------------------------------------------- model delivery
if (-not $SkipPush) {
    $expectedMb = [math]::Round((Get-ChildItem $ModelSrc -File | Measure-Object Length -Sum).Sum / 1MB)

    Step "Pushing models to $Stage/$Turbo (~$expectedMb MB)"
    & $Adb shell "rm -rf $Stage; mkdir -p $Stage" | Out-Null
    $job = Start-Job -ScriptBlock {
        param($adb, $src, $dst)
        # NOTE: capture output as text, and take the LAST line as the verdict.
        # Comparing the whole output array against 0 always fails.
        $out = & $adb push $src $dst 2>&1
        [pscustomobject]@{ Exit = $LASTEXITCODE; Last = ($out | Select-Object -Last 1) }
    } -ArgumentList $Adb, $ModelSrc, "$Stage/$Turbo"

    while ($job.State -eq 'Running') {
        Start-Sleep -Seconds 15
        $d = (& $Adb shell "du -sm $Stage 2>/dev/null" | Select-Object -Last 1)
        $have = 0
        if ($d -match '(\d+)') { $have = [int]$Matches[1] }
        $pct = [math]::Round(100 * $have / $expectedMb)
        Write-Host ("    {0,6} / {1} MB  ({2}%)" -f $have, $expectedMb, $pct) -ForegroundColor DarkCyan
    }
    $res = Receive-Job $job; Remove-Job $job
    Ok "push: $($res.Last)"
    if ($res.Exit -ne 0) { Die "adb push failed (exit $($res.Exit))" }

    Step 'Building the fp32 envelope (turbo minus the int8 encoder) on-device'
    # A "fp32" dir that still contains the int8 encoder would silently load int8
    # and make the int8-vs-fp32 column meaningless, so the exclusion is the point.
    & $Adb shell "rm -rf $Stage/$Fp32" | Out-Null
    & $Adb shell "cp -r $Stage/$Turbo $Stage/$Fp32" | Out-Null
    & $Adb shell "rm -f $Stage/$Fp32/*_encoder_int8.ncnn.*" | Out-Null
    $nInt8 = (& $Adb shell "ls $Stage/$Fp32 | grep -c int8").Trim()
    if ($nInt8 -ne '0') { Die "fp32 envelope still has $nInt8 int8 file(s)" }
    Ok 'fp32 envelope is clean'

    # No copy into the package here on purpose. With isDebuggable=false `run-as`
    # is gone, and copying the models into filesDir is SttModelBootstrap's job -
    # the instrumentation runs in the app's own uid and copies them itself. This
    # script only stages them somewhere readable.
    Step 'Making the staging dir readable by the app uid'
    & $Adb shell "chmod -R 777 $Stage" | Out-Null
    Ok "models staged in $Stage (test will copy them in)"
}

if (-not $SkipPush) {
    Step 'Sanity: staged model inventory'
    $cnt = (& $Adb shell "ls $Stage/$Turbo | wc -l; ls $Stage/$Fp32 | wc -l")
    Write-Host "    turbo files=$($cnt[0].Trim())  fp32 files=$($cnt[1].Trim()) (expected 16 / 14)" -ForegroundColor DarkGray
    if ([int]$cnt[0].Trim() -lt 16) { Die "staged turbo is incomplete ($($cnt[0]))" }
    Ok 'staging looks complete'
}

# ---------------------------------------------------------------- run
# ДВА ПРОЦЕССА, А НЕ ОДИН - и это не оптимизация, а вынужденная мера.
# Причина разобрана в KDoc BenchSttTest ("ПОЧЕМУ МАТРИЦА РАЗБИТА НА ДВА ТЕСТА")
# и целиком вбита в память на прогоне E: release() отдаёт модель аллокатору, но
# RSS процесса НЕ уменьшается, high-water липкий. В одном процессе int8 (1.6 ГБ)
# и fp32 (2.3 ГБ) складывались в 5.2 ГБ при доступных ~5 ГБ, и lowmemorykiller
# пришивал процесс SIGKILL прямо посреди замера. Разнесение по процессам даёт
# каждому конфигу его собственный high-water.
#
# Побочный плюс, который дороже самого разделения: CSV теперь пишутся РАЗДЕЛЬНО
# и перезаписываются после каждого замера, поэтому смерть одного прогона больше
# не обнуляет результат другого. На прогоне E общий файл остался с 0 строк.
$groups = @(
    @{
        Label  = 'int8'
        Csv    = 'stt-bench-int8.csv'
        Filter = 'org.opencode.mobile.SmokeSttTest#nativeInitAndTranscribe,' +
            'org.opencode.mobile.BenchSttTest#benchChunkedLong,' +
            'org.opencode.mobile.BenchSttTest#benchLazyShort,' +
            'org.opencode.mobile.BenchSttTest#benchAutoLanguage,' +
            'org.opencode.mobile.BenchSttTest#benchMatrixInt8'
        Expect = '~12-16 min (5 tests, всё на turbo/int8)'
    },
    @{
        Label  = 'fp32'
        Csv    = 'stt-bench-fp32.csv'
        Filter = 'org.opencode.mobile.BenchSttTest#benchMatrixFp32'
        Expect = '~8-10 min (1 test, только fp32)'
    }
)

Step 'Running the suite via am instrument (no gradle: nothing may reinstall)'
# Чистим протухшие CSV ДО прогона. Обязательно: канал публикации в /data/local/tmp
# не работает (SELinux), поэтому старый файл от ПРОШЛОГО запуска остаётся лежать
# и выглядит как результат сегодняшнего. На прогоне E так и вышло: "файл есть,
# 0 строк" - это был остаток чужого прогона, а не пустой результат. Без этой
# строки проверка снова поверит в чужой мусор.
& $Adb shell "rm -f $Stage/stt-bench-int8.csv $Stage/stt-bench-fp32.csv $Stage/stt-bench.csv" 2>&1 | Out-Null
& $Adb shell am force-stop $Pkg | Out-Null
& $Adb logcat -c | Out-Null
$logDir = Join-Path $Repo 'build\stt-bench-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$logcatLog = Join-Path $logDir 'minverify-logcat.txt'
$instLog = @{}

# logcat снимается ОДИН раз на весь скрипт, в файл, а не читается в конце через
# `logcat -d`. Причина прежняя: `-d` отдаёт кольцевой буфер, прогон занимает
# 20+ минут, и устройство за это время забивает буфер системным мусором
# (AMC/horae/msys), из-за чего ранние BENCH_ROW/publishCsv вытесняются до того,
# как их прочитают. Один поток на оба процесса здесь осознанно: по единой
# хронологии видно, чей high-water сколько стоил, а конфиг и так есть в каждой
# строке - "RSS на старте матрицы (<config>)" и префикс "BENCH_ROW <config>,".
$logcatProc = Start-Process -FilePath $Adb -ArgumentList 'logcat', '-v', 'brief' `
    -RedirectStandardOutput $logcatLog -WindowStyle Hidden -PassThru
Start-Sleep 3
$exit = 0
try {
    foreach ($g in $groups) {
        $gLog = Join-Path $logDir "minverify-instrument-$($g.Label).txt"
        $instLog[$g.Label] = $gLog
        Write-Host ''
        Write-Host "  >>> $($g.Label): $($g.Expect)" -ForegroundColor Cyan
        & $Adb shell am instrument -w -r -e class $g.Filter $Runner 2>&1 | Tee-Object -FilePath $gLog
        $code = $LASTEXITCODE
        $instLog["$($g.Label).exit"] = $code
        Write-Host "      am instrument exit: $code" -ForegroundColor DarkGray
        if ($code -ne 0) { $exit = $code }
        # Между прогонами процесс ОБЯЗАН умереть, иначе fp32 грузится поверх
        # int8 в том же адресном пространстве - ровно то, что убило прогон E.
        & $Adb shell am force-stop $Pkg | Out-Null
        Start-Sleep 5
    }
}
finally {
    Start-Sleep 2
    if ($logcatProc -and -not $logcatProc.HasExited) { Stop-Process -Id $logcatProc.Id -Force }
}

# ---------------------------------------------------------------- verify
# Вердикт раннера ("OK (N tests)") НИЧЕГО не доказывает: он печатается и для
# SKIPPED-тестов, а в двухпроцессном прогоне вдобавок относится к одному процессу
# из двух. Единственное честное свидетельство, что R8-покрытие реально
# отработало, - CSV, который тесты записали на устройство.
#
# CSV - ПЕРВИЧНЫЙ сигнал, а не logcat: буфер logcat кольцевой, устройство
# шумное (AMC/horae/msys за 20 минут), и ранние BENCH_ROW вытесняются до чтения.
# Именно поэтому прошлая ревизия скрипта назвала полностью успешный прогон FAILED.
#
# Источник CSV поменялся принципиально. Раньше скрипт делал
# `adb shell cat /data/local/tmp/ocmodels/<csv>`, и это НИКОГДА не работало:
# каталог drwxrwx--x shell:shell, контекст u:object_r:shell_data_file:s0, SELinux
# Enforcing -> у приложения (чужой uid) в "others" только --x: прочитать модель
# может, создать файл - нет (EACCES). Модели оттуда читались всегда, поэтому
# поломка была невидима, а скрипт при этом НИКОГДА не удалял старые *.csv перед
# прогоном. Отсюда и был "файл есть, 0 строк" на прогоне E: не пустой результат,
# а протухший остаток чужого запуска. Вывод "0 строк = крэш посередине" был неверен.
#
# Теперь тест печатает CSV блоком BENCHCSV_BEGIN/END в stdout `am instrument
# -w -r`, и скрипт разбирает ТОТ ЖЕ файл, куда уже пишет stdout прогона. Файл не
# кольцевой, его не вытесняет OEM-спам, и он переживает SIGKILL. Запасные
# источники: cat с телефона (сработает, только если политика SELinux изменится) и
# BENCH_ROW из logcat.
Step 'Verifying the tests really executed'
function Get-InstrumentCsv([string]$InstrumentLog, [string]$CsvName) {
    if (-not (Test-Path $InstrumentLog)) { return @() }
    $esc = [regex]::Escape($CsvName)
    $grab = $false
    $buf = New-Object System.Collections.Generic.List[string]
    $best = @()
    foreach ($ln in @(Get-Content $InstrumentLog -Encoding UTF8)) {
        if ($ln -match "BENCHCSV_BEGIN\s+$esc") { $grab = $true; $buf.Clear(); continue }
        if ($ln -match "BENCHCSV_END\s+$esc") {
            if ($grab) { $best = @($buf.ToArray()) }
            $grab = $false
            continue
        }
        if ($grab) { $buf.Add($ln) }
    }
    return @($best | Where-Object { $_ -match '^(int8|fp32),' })
}

$csvByGroup = @{}
$missing = @()
$source = @{}
foreach ($g in $groups) {
    $rows = @(Get-InstrumentCsv $instLog[$g.Label] $g.Csv)
    $src = 'instrumentation stdout'
    if ($rows.Count -eq 0) {
        $raw = @(& $Adb shell "cat $Stage/$($g.Csv)" 2>$null)
        if (@($raw | Where-Object { $_ -match 'No such file|not found' }).Count -eq 0) {
            $rows = @($raw | Where-Object { $_ -match '^(int8|fp32),' })
            if ($rows.Count -gt 0) { $src = 'adb cat (SELinux позволил)' }
        }
    }
    if ($rows.Count -eq 0 -and (Test-Path $logcatLog)) {
        $rows = @(Select-String -Path $logcatLog -Pattern "BENCH_ROW $($g.Csv)" -Encoding UTF8 |
            ForEach-Object { $_.Line -replace '.*BENCH_ROW\s+', '' } | Where-Object { $_ -match '^(int8|fp32),' })
        if ($rows.Count -gt 0) { $src = 'logcat BENCH_ROW (частично)' }
    }
    if ($rows.Count -eq 0) { $missing += $g.Label }
    $csvByGroup[$g.Label] = $rows
    $source[$g.Label] = $src
}
$dataRows = @($csvByGroup.Values | ForEach-Object { $_ })
$csvMissing = $missing.Count -gt 0

# Тот же CSV глазами теста: publishCsv пишет в logcat и при успехе, и при отказе
# (отказ - warn). Без этого строка молчала, и нулевой CSV оставался загадкой
# вместо одного grep. Сюда же - high-water каждого конфига, он печатается в
# начале матрицы и объясняет, сколько памяти стоил прогон.
$bootLines = @()
$rssLines = @()
if (Test-Path $logcatLog) {
    $bootLines = @(Select-String -Path $logcatLog -Pattern 'publishCsv|STTBOOT.*ensure' -Encoding UTF8 |
        ForEach-Object { $_.Line.Trim() })
    $rssLines = @(Select-String -Path $logcatLog -Pattern 'STTBENCH.*RSS' -Encoding UTF8 |
        ForEach-Object { $_.Line.Trim() })
}

Write-Host ''
Write-Host '  ================= minVerify (R8) STT =================' -ForegroundColor Cyan
foreach ($g in $groups) {
    $n = @($csvByGroup[$g.Label]).Count
    $col = if ($n -ge 5) { 'Green' } elseif ($n -gt 0) { 'Yellow' } else { 'Red' }
    Write-Host ("  csv {0,-5} {1,-24} {2,2}/5 rows via {3}" -f $g.Label, $g.Csv, $n, $source[$g.Label]) -ForegroundColor $col
}
Write-Host "  csv data rows : $($dataRows.Count) (expected 10)" -ForegroundColor $(if ($dataRows.Count -ge 10) { 'Green' } else { 'Red' })
Write-Host "  process exits : $(($groups | ForEach-Object { "$($_.Label)=$($instLog["$($_.Label).exit"])" }) -join '  ')" -ForegroundColor $(if ($exit -eq 0) { 'DarkGray' } else { 'Red' })
foreach ($r in $rssLines) { Write-Host "    $r" -ForegroundColor DarkGray }
foreach ($b in ($bootLines | Select-Object -First 4)) { Write-Host "    $b" -ForegroundColor DarkGray }
Write-Host "  logcat archive: $logcatLog" -ForegroundColor DarkGray
Write-Host '  --------------------------------------------' -ForegroundColor DarkGray
Write-Host '  config   wav          median_ms   enc_ms' -ForegroundColor White
foreach ($line in $dataRows) {
    $f = ($line -split ',')
    if ($f.Count -ge 8) { Write-Host ("  {0,-8} {1,-11} {2,9}   {3,8}" -f $f[0], $f[1], $f[5], $f[7]) -ForegroundColor Green }
}
Write-Host ''

# Вердикт трёхуровневый, потому что fp32 ФИЗИЧЕСКИ не влезает в этот телефон:
# прогон F показал 4761 МБ RSS в собственном свежем процессе и lowmemorykiller.
# Это не поломка теста и не повод красить прогон в красный, но и называть его
# полным нельзя - строк меньше пяти.
$nInt8 = @($csvByGroup['int8']).Count
$nFp32 = @($csvByGroup['fp32']).Count
if ($nInt8 -eq 5 -and $nFp32 -eq 5) {
    Write-Host '  RESULT: FULL - int8 5/5, fp32 5/5' -ForegroundColor Green
} elseif ($nInt8 -eq 5 -and $nFp32 -ge 1) {
    Write-Host "  RESULT: int8 FULL 5/5, fp32 TRUNCATED $nFp32/5" -ForegroundColor Yellow
    Write-Host '  (fp32 упирается в память телефона, а не в баг теста - строки выше и есть весь доступный результат)' -ForegroundColor Yellow
} else {
    Write-Host '  RESULT: FAILED / did not really run - do NOT trust a green here' -ForegroundColor Red
    $why = if ($csvMissing) { "csv missing: $($missing -join ',')" } else { "int8=$nInt8 fp32=$nFp32" }
    Write-Host "  ($why exit=$exit)" -ForegroundColor Red
}

if (-not $KeepStaging) {
    Step 'Removing staging (models stay in the package)'
    & $Adb shell "rm -rf $Stage" | Out-Null
}
Write-Host ''
foreach ($k in $instLog.Keys | Where-Object { -not $_.EndsWith('.exit') } | Sort-Object) {
    Write-Host "  instrumentation log [$k]: $logDir\minverify-instrument-$k.txt" -ForegroundColor DarkGray
}
