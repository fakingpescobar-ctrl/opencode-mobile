# run_stt_bench.ps1 - STT device bench with live progress.
# ASCII-only on purpose: keeps rendering clean in Windows PowerShell 5.1 consoles.
#
#   pwsh -ExecutionPolicy Bypass -File run_stt_bench.ps1 -Start
#   pwsh -ExecutionPolicy Bypass -File run_stt_bench.ps1          (re-attach)
#
# Ctrl+C stops ONLY this monitor. The gradle run keeps going in the background.
# Re-running the script re-attaches to the already running job.
#
# WHY logcat and not the gradle log: the test prints its rows with android.util.Log
# (tag STTBENCH), so they land in the device ring buffer, NOT in gradle stdout.
# An earlier version of this script grepped gradle output for BENCH_ROW and
# therefore showed 0 rows for the whole 20-minute run. Gradle output is still
# tailed, but only for failures/assertions.

param(
    [switch]$Start,
    [int]$ExpectedRows = 10
)

$ErrorActionPreference = 'Continue'

$Repo   = 'C:\Projects\opencode-mobile'
$LogDir = Join-Path $Repo 'build\stt-bench-logs'
$Log    = Join-Path $LogDir 'bench.log'
$ErrLog = Join-Path $LogDir 'bench.err.log'
$Adb    = 'C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$Flag   = '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
$Runner = 'org.opencode.mobile.BenchSttTest'
$Pkg    = 'org.opencode.mobile.debug'

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
if (-not (Test-Path $Adb))    { Write-Host "  adb not found: $Adb" -ForegroundColor Red; exit 1 }

# gradlew.bat dies instantly with exit 9009 ("JAVA_HOME is not set and no 'java'
# command could be found") when the monitor is launched from a shell that never
# had JAVA_HOME exported - which is exactly what happens via Start-Process.
# Resolve it here so the script is self-contained.
if (-not $env:JAVA_HOME) {
    $jd = @(
        'C:\Program Files\Android\Android Studio\jbr',
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if ($jd) { $env:JAVA_HOME = $jd }
}
if (-not $env:JAVA_HOME) {
    Write-Host '  JAVA_HOME not set and no Android Studio JBR found - gradlew will fail.' -ForegroundColor Red
    exit 1
}

# Tags the test logs to: STTBENCH = rows, CHUNKED = segmentation, plus the
# engine's own tags so an error surfaces in the monitor, not just in a log file.
$LcTags = 'STTBENCH:V NcnnWhisper:V CHUNKED:V'

function Find-RunningBench {
    # Must match ONLY the gradle wrapper. A bare '*connectedDebugAndroidTest*'
    # substring search also matches any unrelated shell whose command line merely
    # mentions the task (e.g. the monitoring shell that greps for it), which made
    # the monitor believe a run was already in flight and skip starting it.
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -eq 'cmd.exe' -and
            $_.CommandLine -and
            $_.CommandLine -like '*gradlew*' -and
            $_.CommandLine -like '*connectedDebugAndroidTest*'
        } |
        Select-Object -First 1
}

$running = Find-RunningBench

if ($Start -and -not $running) {
    Write-Host ''
    Write-Host '  Starting STT bench. Progress will appear here.' -ForegroundColor Cyan
    Write-Host ''

    # A leftover `libopencode.so serve --port 4096` from a previous session kept
    # ~42% CPU on this device and skewed every measurement. Kill it first.
    & $Adb shell "am force-stop $Pkg" 2>&1 | Out-Null
    Start-Sleep -Seconds 2

    foreach ($f in @($Log, $ErrLog)) { if (Test-Path $f) { Clear-Content $f } }
    & $Adb logcat -c 2>&1 | Out-Null

    $gradle = Join-Path $Repo 'gradlew.bat'
    $gargs = @(
        ':app:connectedDebugAndroidTest', $Flag,
        "-Pandroid.testInstrumentationRunnerArguments.class=$Runner"
    )
    Start-Process -FilePath $gradle -ArgumentList $gargs -WorkingDirectory $Repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $Log -RedirectStandardError $ErrLog |
        Out-Null

    Write-Host '  gradle started. leaveApks is ON (APK and models survive).' -ForegroundColor Green
    Write-Host ''
} elseif ($running) {
    Write-Host ''
    Write-Host "  Re-attached to running gradle (PID $($running.ProcessId))." -ForegroundColor Yellow
    Write-Host ''
}

Clear-Host
Write-Host ''
Write-Host '  ================= STT DEVICE BENCH =================' -ForegroundColor Cyan
Write-Host '  matrix: 5 wavs x {int8,fp32}, interleaved, 3 timed runs after warmup' -ForegroundColor DarkGray
Write-Host '  + auto-language via service, chunked long.wav, lazy short clip' -ForegroundColor DarkGray
Write-Host '  leaveApks ON - models and APK are kept after the run' -ForegroundColor DarkGray
Write-Host '  --------------------------------------------------------' -ForegroundColor DarkGray
Write-Host ''
Write-Host '  config   wav          median_ms   enc_ms   WER' -ForegroundColor White
Write-Host '  --------------------------------------------------------' -ForegroundColor DarkGray

$seen      = @{}
$langSeen  = @{}
$seenLog   = New-Object 'System.Collections.Generic.HashSet[string]'
$startTime = Get-Date
$failed    = $false
$done      = $false
$asserts   = @()

function Format-Enc($f) {
    # row = config,wav,ms1,ms2,ms3,median,fbank,enc,dec,steps,wer,text
    if ($f.Count -gt 7) { return $f[7] }
    return '?'
}

# Rows come from the DEVICE logcat, polled with `-d` rather than streamed into a
# file: a redirected `adb logcat` block-buffers its stdout (~4KB) when stdout is
# not a TTY, so a streaming reader sees nothing until the process exits. Polling
# the ring buffer has no such problem, and the `-s` filter means only our tags
# come back. Lines are deduped by exact text, so re-reading the buffer is cheap.
while ($true) {
    $dump = @(& $Adb logcat -d -v time -s $LcTags 2>$null)
    foreach ($ln in $dump) {
        $key = $ln.Trim()
        if (-not $seenLog.Add($key)) { continue }
        if ($ln -match 'BENCH_ROW\s+(.*)$') {
            $f = $Matches[1] -split ','
            if ($f.Count -ge 6) {
                $seen["$($f[0])|$($f[1])"] = $true
                $wer = if ($f.Count -gt 10) { $f[10] } else { '' }
                Write-Host ("  {0,-8} {1,-11} {2,9}   {3,8}   {4,6}" -f `
                    $f[0], $f[1], $f[5], (Format-Enc $f), $wer) -ForegroundColor Green
            }
        }
        elseif ($ln -match 'LANG_ROW\s+(.*)$') {
            $f = $Matches[1] -split ','
            if ($f.Count -ge 5) {
                $langSeen[$f[0]] = $true
                $color = if ($f[3] -eq 'ru') { 'Yellow' } else { 'Green' }
                Write-Host ("  AUTO-LANG {0,-8} {1,6}s -> {2} ({3})" -f `
                    $f[0], $f[2], $f[3], $f[4]) -ForegroundColor $color
            }
        }
        elseif ($ln -match 'CHUNK.*segments=') {
            Write-Host ("  {0}" -f $key.Substring([Math]::Min(22, $key.Length))) -ForegroundColor DarkYellow
        }
        elseif ($ln -match 'AssertionError') {
            $asserts += $key
            Write-Host ("  !! ASSERT: {0}" -f $key) -ForegroundColor Red
            $failed = $true
        }
    }

    $alive = Find-RunningBench
    $elapsed = [int]((Get-Date) - $startTime).TotalSeconds

    # JUnit does not guarantee method order, so gradle exiting is the only
    # reliable "all four tests finished" signal. Require a grace period so the
    # first second of a slow install does not read as "finished".
    if (-not $alive -and $elapsed -gt 60) {
        Start-Sleep -Seconds 3
        $done = $true
        break
    }

    $total = $ExpectedRows + 1
    $got   = $seen.Count
    $bar   = '#' * [Math]::Min(40, [int](($got / $total) * 40))
    $eta   = if ($got -gt 0) { [int](($elapsed / $got) * ($total - $got)) } else { 0 }
    Write-Host ("  {0,-42} {1}/{2} rows  elapsed {3}s  ETA ~{4}s" -f `
        $bar, $got, $total, $elapsed, $eta) -ForegroundColor DarkCyan
    Start-Sleep -Seconds 5
}

# Authoritative numbers: the CSV the test wrote on the device, not the log tail.
Write-Host ''
Write-Host '  --------------------------------------------------------' -ForegroundColor DarkGray
Write-Host '  CSV on device (files/bench/stt-bench.csv):' -ForegroundColor White
$csv = & $Adb shell "run-as $Pkg cat files/bench/stt-bench.csv" 2>&1
if ($csv) { $csv | ForEach-Object { Write-Host ("    {0}" -f $_) -ForegroundColor Gray } }
else { Write-Host '    (unreadable - model dir missing or test never reached writeCsv)' -ForegroundColor DarkRed }

Write-Host ''
if ($failed) {
    Write-Host '  RESULT: FAILED' -ForegroundColor Red
    if ($asserts.Count -gt 0) {
        Write-Host ''
        Write-Host '  Assertions:' -ForegroundColor Red
        $asserts | Select-Object -Last 8 | ForEach-Object { Write-Host ("    {0}" -f $_) -ForegroundColor Red }
    }
    Write-Host ''
    Write-Host '  gradle failures:' -ForegroundColor Red
    Get-Content $Log -ErrorAction SilentlyContinue |
        Select-String -Pattern 'FAILED|FAILURE:|AssertionError|Tests run' |
        Select-Object -Last 10 | ForEach-Object { Write-Host ("    {0}" -f $_.Line.Trim()) -ForegroundColor Red }
} else {
    Write-Host '  RESULT: all rows, no assertion failures' -ForegroundColor Green
    if ($langSeen.Count -eq 0) {
        Write-Host '  (no LANG_ROW seen - auto-language test may not have run)' -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host "  gradle log: $Log" -ForegroundColor DarkGray
Write-Host ''
Start-Sleep -Seconds 600
