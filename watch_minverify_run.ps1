# watch_minverify_run.ps1 - live monitor around run_stt_bench_minverify.ps1
# ASCII-only on purpose: keeps rendering clean in Windows PowerShell 5.1.
#
#   powershell -ExecutionPolicy Bypass -File watch_minverify_run.ps1
#   pwsh       -ExecutionPolicy Bypass -File watch_minverify_run.ps1 -SkipPush
#
# WHAT IT DOES
# The bench itself already prints a verdict table at the end, but the two long
# parts before it are silent: the native+minify build (~20 min) and the model
# push (~2.4 GB). This runs the bench in a HIDDEN child and renders progress
# here, so nothing looks hung.
#
# The hidden child is found by CommandLine, not by a pidfile, so re-running
# this script RE-ATTACHES to a run that is already going instead of starting a
# second one.
#
# Ctrl+C stops the MONITOR only. The child keeps running; re-run to re-attach.
#
# The log tail at the end is the real answer - everything printed live here is
# a lossy summary of it.

param(
    [switch]$SkipPush,
    [switch]$KeepStaging
)

$ErrorActionPreference = 'Stop'
$Repo   = 'C:\Projects\opencode-mobile'
$Runner = Join-Path $Repo 'run_stt_bench_minverify.ps1'
$LogDir = Join-Path $Repo 'build\stt-bench-logs'
$Log    = Join-Path $LogDir 'watch-run.log'
$ErrLog = Join-Path $LogDir 'watch-run.err.log'
$Adb    = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

# ---- what the run is, so the log is traceable to a commit -------------------

$head = 'unknown'
try { $head = (& git -C $Repo rev-parse --short HEAD 2>$null) -join '' } catch { }
$pkgState = @(& $Adb shell pm list packages 2>$null | Select-String 'org\.opencode\.mobile' | ForEach-Object { $_.ToString().Trim() }) -join ' '

function Say($m, $c = 'Gray') { Write-Host ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) -ForegroundColor $c }

# ---- attach to a live run, or start one ------------------------------------

$live = Get-CimInstance Win32_Process -Filter "Name='pwsh.exe' OR Name='powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*run_stt_bench_minverify.ps1*' -and $_.ProcessId -ne $PID } |
    Select-Object -First 1

if ($live) {
    Say "ATTACH: bench already running as pid $($live.ProcessId) - not starting a second one" 'Yellow'
    $procId = [int]$live.ProcessId
} else {
    if (-not (Test-Path -LiteralPath $Runner)) { Say "FATAL: $Runner not found" 'Red'; exit 1 }
    $exe = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $exe) { $exe = (Get-Command powershell -ErrorAction SilentlyContinue).Source }
    if (-not $exe) { Say 'FATAL: neither pwsh nor powershell on PATH' 'Red'; exit 1 }

    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Runner)
    if ($SkipPush)    { $argList += '-SkipPush' }
    if ($KeepStaging) { $argList += '-KeepStaging' }

    Say "START : $exe $($argList -join ' ')" 'Cyan'
    Say "LOG   : $Log" 'DarkGray'
    Say "COMMIT: $head" 'DarkGray'
    if ($pkgState) { Say "PHONE : $pkgState" 'DarkGray' } else { Say 'PHONE : no opencode package on device' 'DarkYellow' }

    $child = Start-Process -FilePath $exe -ArgumentList $argList -WorkingDirectory $Repo `
        -RedirectStandardOutput $Log -RedirectStandardError $ErrLog -WindowStyle Hidden -PassThru
    $procId = $child.Id
    Say "PID   : $procId" 'Green'
}

# ---- tail reader ------------------------------------------------------------

if (-not (Test-Path -LiteralPath $Log)) { Say 'FATAL: no log file' 'Red'; exit 1 }
$fs     = [System.IO.File]::Open($Log, 'Open', 'Read', 'ReadWrite')
$reader = New-Object System.IO.StreamReader($fs)

# On attach, start from recent history instead of replaying the whole run.
$skip = $fs.Length - 40000
if ($skip -gt 0) {
    [void]$fs.Seek($skip, 'Begin')
    [void]$reader.ReadLine()
    Say '---- attached at tail of existing log ----' 'DarkGray'
}

# ---- parsed state -----------------------------------------------------------

$stage    = 'starting'
$taskSeen = New-Object System.Collections.Generic.HashSet[string]
$tests    = New-Object System.Collections.Generic.List[string]
$crashes  = 0
$csvRows  = 0
$buildOk  = $false
$verdict  = ''
$pushPct  = 0
$lastBeat = Get-Date

function DevInfo {
    if (-not (Test-Path -LiteralPath $Adb)) { return 'adb missing' }
    $raw = (& $Adb shell dumpsys battery 2>$null) -join "`n"
    if (-not $raw) { return 'device offline' }
    $l = [regex]::Match($raw, 'level:\s*(\d+)').Groups[1].Value
    $t = [regex]::Match($raw, 'temperature:\s*(\d+)').Groups[1].Value
    $ac = if ($raw -match 'AC powered:\s*true') { 'AC' } else { 'bat' }
    if ($l -eq '' -and $t -eq '') { return 'battery unknown' }
    $deg = ''
    if ($t -ne '') { $deg = ('{0:N1}C' -f ([int]$t / 10)) }
    return "$ac ${l}% $deg"
}

function Beat($why) {
    $now = Get-Date
    if (((New-TimeSpan $lastBeat $now).TotalSeconds) -lt 20 -and $why -ne 'END') { return }
    $lastBeat = $now
    $el = [int]((New-TimeSpan $script:started, $now).TotalSeconds)
    $hh = [int]($el / 3600); $mm = [int](($el % 3600) / 60); $ss = $el % 60
    $clk = ('{0:d2}:{1:d2}:{2:d2}' -f $hh, $mm, $ss)
    $dev = DevInfo
    Write-Host ("  [ {0} ] {1,-34} tasks={2,-3} tests={3} csv={4} crashes={5} {6}" -f `
        $clk, $stage, $taskSeen.Count, $tests.Count, $csvRows, $crashes, $dev) -ForegroundColor DarkCyan
}

$script:started = Get-Date

# ---- pump -------------------------------------------------------------------

while ($true) {
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }

        if ($line -match '^==>\s*(.+?)\s*$') {
            $stage = $Matches[1]
            Say "STAGE  $stage" 'Cyan'
        }
        elseif ($line -match '^>\s*Task\s+:(\S+?)(?:\s|$)') {
            $t = $Matches[1]
            if ($taskSeen.Add($t)) {
                if ($t -match 'Native|Mini|Assemble|minify|R8|Package') { Say "  task $t" 'DarkGray' }
            }
        }
        elseif ($line -match 'BUILD SUCCESSFUL') { $buildOk = $true; Say 'BUILD SUCCESSFUL' 'Green' }
        elseif ($line -match 'BUILD FAILED|FAILURE:') { Say 'BUILD FAILED' 'Red' }
        elseif ($line -match '^\s*(\d+)\s*/\s*(\d+)\s*MB') {
            $pushPct = [int]([int]$Matches[1] * 100 / [int]$Matches[2])
        }
        elseif ($line -match '^BENCHCSV_BEGIN') { $csvRows = 0 }
        elseif ($line -match '^BENCHCSV_END')   { Say "CSV BLOCK: $csvRows rows" 'Green' }
        elseif ($line -match '^\s*config,')    { $csvRows++ }
        elseif ($line -match '^INSTRUMENTATION_STATUS:\s*test=(\S+)') {
            $tests.Add($Matches[1]) | Out-Null
            Say "TEST   $($Matches[1])" 'White'
        }
        elseif ($line -match 'Process crashed') {
            $crashes++
            Say "CRASH  Process crashed  ($crashes)" 'Red'
        }
        elseif ($line -match '^OK\s*\((\d+)\s*tests?\)') { Say "GROUP  OK ($($Matches[1]) tests)" 'Green' }
        elseif ($line -match '^RESULT:\s*(.+?)\s*$') {
            $verdict = $Matches[1]
            Say "RESULT $verdict" 'White'
        }
        elseif ($line -match 'error:|Exception|FAILED') {
            if ($line.Length -lt 200) { Say "  ! $($line.Trim())" 'Yellow' }
        }
    }

    Beat ''

    if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Seconds 2
}

Beat 'END'
$reader.Close(); $fs.Close()

# ---- report -----------------------------------------------------------------

$code = 'n/a'
if (Test-Path -LiteralPath $ErrLog) {
    $e = Get-Content -LiteralPath $ErrLog -Raw -ErrorAction SilentlyContinue
    if ($e) {
        $m = [regex]::Match($e, 'EXIT:\s*(\d+)')
        if ($m.Success) { $code = $m.Groups[1].Value }
    }
}
if ($code -eq 'n/a') { $code = "process exited (see log; script has no exit stamp)" }

$el = [int]((New-TimeSpan $script:started, (Get-Date)).TotalSeconds)
Say ("FINISHED in {0:mm}:{0:ss}" -f (New-TimeSpan -Seconds $el)) 'Cyan'
Say "  build ok    : $buildOk"
Say "  tests seen  : $($tests.Count) ($($tests -join ', '))"
Say "  csv rows    : $csvRows (want 10)"
Say "  crashes     : $crashes"
Say "  push final  : $pushPct%"
Say "  verdict     : $(if ($verdict) { $verdict } else { 'NONE - the run did not reach a verdict' })"
Say "  exit        : $code"

$verdictColor = 'Red'
if ($verdict -like 'FULL*')       { $verdictColor = 'Green' }
elseif ($verdict -like '*TRUNCATED*') { $verdictColor = 'Yellow' }
Say "  VERDICT     : $verdict" $verdictColor

if ($crashes -gt 0) {
    Say ''
    Say 'A crash means the APK on the device is STALE or the bug is still there.' 'Yellow'
    Say 'The bench installs the apk itself, so a crash right after a fresh' 'Yellow'
    Say 'build means the fix did not work - not that the build was skipped.' 'Yellow'
}

Say ''
Say '---- last 70 lines of the real log ----' 'DarkGray'
Get-Content -LiteralPath $Log -Tail 70 | ForEach-Object { Write-Host "  $_" -ForegroundColor 'Gray' }

Say ''
Say "full log: $Log"
Say "err log : $ErrLog"
Say 'Ctrl+C earlier stopped only the monitor; the bench itself kept running.' 'DarkGray'
