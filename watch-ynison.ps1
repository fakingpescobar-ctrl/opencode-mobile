<#
    watch-ynison.ps1 - ловец спонтанной смерти локальных MCP-процессов.

    Зачем он нужен. Мы выяснили, что РАНЬШЕ смерть ynison не оставляла следов: exit code
    писался только когда останавливали мы. Теперь ProcessSupervisor логирует
    "НЕОЖИДАННАЯ СМЕРТЬ" с кодом и временем жизни, но даже этого мало - буфер logcat на
    телефоне перезаписывается примерно за час, и повторить спонтанную смерть вручную не
    получится. Поэтому этот скрипт делает ровно одно: замечает смерть и СРАЗУ забирает
    всё, что ещё не стёрлось. Проверка раз в IntervalSec секунд, поэтому окно захвата -
    секунды, а не минуты.

    Что забирается в момент смерти (все буферы сразу, потому что они перезаписываются):
      logcat-all.txt     - logcat -b all: main + system + crash + events. Буфер crash
                           важен отдельно: там ANR и tombstone падений.
      logcat-supervisor.txt - только ProcessSupervisor, ради читаемости.
      trace-tail.txt     - хвост ynison-trace.log (сокеты, handshake, бан-сигналы).
      procs.txt          - снимок процессов.
      meminfo.txt        - состояние памяти: для 137 (SIGKILL) это главный улика.
      summary.txt        - выжимка: что умерло, код, сколько жил.

    Ctrl+C останавливает ТОЛЬКО монитор - ничего в приложении он не трогает.

    Запуск:
        pwsh -ExecutionPolicy Bypass -File C:\Projects\opencode-mobile\watch-ynison.ps1

    Параметры:
        -IntervalSec 5     как часто опрашивать logcat (меньше - точнее, больше шума)
        -OutDir ...        куда складывать инциденты
        -AdbPath ...       путь к adb, если он не в PATH
#>

[CmdletBinding()]
param(
    [int]$IntervalSec = 5,
    [string]$OutDir = "C:\Projects\opencode-mobile\.watch\incidents",
    [string]$AdbPath = ""
)

$ErrorActionPreference = "Continue"

# --- locate adb -------------------------------------------------------------
if (-not $AdbPath) {
    $cand = "C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $cand) {
        $AdbPath = $cand
    } else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $AdbPath = $cmd.Source } else {
            Write-Host "adb not found. Pass -AdbPath." -ForegroundColor Red
            exit 1
        }
    }
}

# --- constants pulled from the app so this script cannot drift from it -------
$TracePath = "/storage/emulated/0/Documents/OpencodeTerminal/ynison-trace.log"
$AppPkg = "org.opencode.mobile.debug"
$YnisonMarker = "ynison.js"
# The supervisor's death line is: "<name>: НЕОЖИДАННАЯ СМЕРТЬ exit=137 (...), жил 34s"
# The phrase itself is Cyrillic, and matching Cyrillic from PowerShell depends on how the
# console decoded the adb output - too fragile to rely on. Both of these tokens are pure
# ASCII and both are needed: "exit=" appears only on the death lines (the destroyForcibly
# warning from stop() has no "exit="), and the tag keeps us off unrelated app logging.
$DeathTag = "ProcessSupervisor"
$DeathToken = "exit="

# --- duplicate instance guard ----------------------------------------------
# A named mutex, not a command-line scan. Scanning CommandLine matched the launcher AND
# this process (both contain the script path), so the very first run refused to start
# because of its own parent. The mutex is the only check that means what it says: if the
# handle is taken, another watcher really is alive.
$mutex = New-Object System.Threading.Mutex($false, "Global\ynison-death-watcher")
$ownsMutex = $false
try {
    $ownsMutex = $mutex.WaitOne(0, $false)
} catch [System.Threading.AbandonedMutexException] {
    $ownsMutex = $true   # previous holder died without releasing; we now own it
}
if (-not $ownsMutex) {
    Write-Host ""
    Write-Host "A watcher is already running. Not starting a second one - two watchers" -ForegroundColor Yellow
    Write-Host "would capture the same incident twice." -ForegroundColor Yellow
    Write-Host ""
    Write-Host ("Incidents of the running watcher: {0}" -f $OutDir)
    Write-Host "Watch that window, or close it and re-run this one."
    Write-Host ""
    exit 0
}

# PID file, written next to the incidents. Two reasons: the user can see which process is
# the watcher, and it can be stopped by an exact pid. Stopping it by matching the command
# line instead is a trap: any shell that merely mentions the script path in its own
# command line (including the one trying to stop it) matches the pattern too, and force
# killing that pattern is how you kill your own tooling.
$WatchRoot = Split-Path -Parent $OutDir
$PidFile = Join-Path $WatchRoot "watcher.pid"
if (-not (Test-Path -LiteralPath $WatchRoot)) { New-Item -ItemType Directory -Path $WatchRoot -Force | Out-Null }
$PID | Out-File -FilePath $PidFile -Encoding ascii -Force

# --- helpers ----------------------------------------------------------------
function Invoke-Adb {
    param([string[]]$AdbArgs, [int]$TimeoutSec = 60)
    $out = & $AdbPath @AdbArgs 2>&1
    return $out
}

function Get-DeviceSerial {
    $devs = Invoke-Adb @("devices")
    $line = $devs | Where-Object { $_ -match "^\S+\s+device$" } | Select-Object -First 1
    if ($line) { return ($line -split "\s+")[0] }
    return $null
}

function Get-YnisonPid {
    # Single string on purpose: passing the pipeline through PowerShell pieces mangles
    # the grep. Built as one argument and quoted once.
    $cmd = "ps -A -o PID,ARGS | grep '$YnisonMarker' | grep -v grep"
    $res = Invoke-Adb @("shell", $cmd)
    foreach ($l in $res) {
        if ($l -match '^\s*(\d+)\s') { return $Matches[1] }
    }
    return $null
}

function New-IncidentDir {
    param([string]$Kind)
    $stamp = (Get-Date -Format "yyyyMMdd-HHmmss")
    $dir = Join-Path $OutDir ("{0}_{1}" -f $stamp, $Kind)
    $n = 1
    while (Test-Path -LiteralPath $dir) {
        $dir = Join-Path $OutDir ("{0}_{1}-{2}" -f $stamp, $Kind, $n)
        $n++
    }
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    return $dir
}

function Save-AdbToFile {
    param([string[]]$AdbArgs, [string]$Path)
    # Deliberately returns NOTHING. PowerShell folds every value a function prints into its
    # return value, so a `return $true` here used to be picked up by the caller: Capture-Incident
    # returned [True, ..., <dir>] and the incident path printed as "True". No caller uses a
    # result from this, so the function stays silent by construction.
    try {
        $out = & $AdbPath @AdbArgs 2>&1
        # Out-File -Encoding utf8 so Cyrillic from the device survives the round trip.
        $out | Out-File -FilePath $Path -Encoding utf8
    } catch {
        "capture failed: $($_.Exception.Message)" | Out-File -FilePath $Path -Encoding utf8
    }
}

function Capture-Incident {
    param([string[]]$DeathLines, [string]$Kind)

    $dir = New-IncidentDir -Kind $Kind
    $stamp = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")

    # Everything, every buffer, at this instant. Order matters: the supervisor lines and
    # the crash buffer are the ones that disappear first.
    Save-AdbToFile @("logcat", "-b", "all", "-d", "-v", "threadtime") (Join-Path $dir "logcat-all.txt")
    Save-AdbToFile @("logcat", "-b", "crash", "-d", "-v", "threadtime") (Join-Path $dir "logcat-crash.txt")
    Save-AdbToFile @("logcat", "-d", "-v", "epoch", "-s", "$DeathTag`:*") (Join-Path $dir "logcat-supervisor.txt")
    Save-AdbToFile @("shell", "ps -A -o PID,PPID,ARGS") (Join-Path $dir "procs.txt")
    Save-AdbToFile @("shell", "dumpsys meminfo $AppPkg") (Join-Path $dir "meminfo.txt")
    Save-AdbToFile @("shell", "tail -c 300000 '$TracePath'") (Join-Path $dir "trace-tail.txt")
    Save-AdbToFile @("shell", "getprop | grep -i -E 'lowmemory|am_kill'") (Join-Path $dir "props.txt")

    $summary = @()
    $summary += "INCIDENT KIND : $Kind"
    $summary += "CAPTURED AT   : $stamp"
    $summary += "DEVICE        : $(Get-DeviceSerial)"
    $summary += ""
    $summary += "--- death lines seen ---"
    $summary += $DeathLines
    $summary += ""
    $summary += "--- ynison pid at capture ---"
    $summary += (Get-YnisonPid)
    $summary | Out-File -FilePath (Join-Path $dir "summary.txt") -Encoding utf8

    return $dir
}

# --- main loop --------------------------------------------------------------
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
$checks = 0
$incidents = 0
$lastIncidentAt = "-"
$lastCheckAt = "-"
$ynisonState = "unknown"
$missingStreak = 0
$started = Get-Date

Write-Host ""
Write-Host "  ynison death watcher" -ForegroundColor Cyan
Write-Host "  adb    : $AdbPath" -ForegroundColor DarkGray
Write-Host "  outdir : $OutDir" -ForegroundColor DarkGray
Write-Host "  poll   : ${IntervalSec}s" -ForegroundColor DarkGray
Write-Host "  pid    : $PID  (pid file: $PidFile)" -ForegroundColor DarkGray
Write-Host "  Ctrl+C stops only this monitor." -ForegroundColor DarkGray
Write-Host ""

$serial = $null
$serialWait = 0
while (-not $serial -and $serialWait -lt 12) {
    $serial = Get-DeviceSerial
    if (-not $serial) {
        $serialWait++
        Start-Sleep -Seconds 5
    }
}
if (-not $serial) {
    Write-Host "No device online after 60s. Start the app / connect the phone and re-run." -ForegroundColor Red
    exit 1
}
Write-Host ("  device : {0}" -f $serial) -ForegroundColor Green

# Seed the seen-set with whatever is ALREADY in the buffer. Without this the watcher
# replays every death that ever happened as if it were fresh: a restart would invent an
# incident from a log line that is 40 minutes old. Only deaths from now on count.
$preExisting = 0
$seed = Invoke-Adb @("logcat", "-d", "-v", "epoch", "-s", "$DeathTag`:*")
if ($seed) {
    foreach ($l in $seed) {
        $s = "$l"
        if ($s.Contains($DeathToken)) { $null = $seen.Add($s); $preExisting++ }
    }
}
if ($preExisting -gt 0) {
    Write-Host ("  seeded : {0} earlier death line(s) in the buffer, ignored on purpose" -f $preExisting) -ForegroundColor DarkGray
}
Write-Host "  watching. Any unexpected death is captured within one poll." -ForegroundColor Green
Write-Host ""

try {
    while ($true) {
        $checks++
        $lastCheckAt = (Get-Date -Format "HH:mm:ss")

        # --- death lines from the supervisor -------------------------------
        $raw = Invoke-Adb @("logcat", "-d", "-v", "epoch", "-s", "$DeathTag`:*")
        $deathLines = @()
        if ($raw) {
            foreach ($l in $raw) {
                $s = "$l"
                if ($s.Contains($DeathToken)) {
                    if ($seen.Add($s)) { $deathLines += $s }
                }
            }
        }

        # --- process presence ----------------------------------------------
        $pidNow = Get-YnisonPid
        if ($pidNow) {
            $ynisonState = "alive pid=$pidNow"
            $missingStreak = 0
        } else {
            $ynisonState = "MISSING"
            $missingStreak++
        }

        # --- capture on unexpected death ------------------------------------
        if ($deathLines.Count -gt 0) {
            $incidents++
            $lastIncidentAt = (Get-Date -Format "HH:mm:ss")
            $dir = Capture-Incident -DeathLines $deathLines -Kind "death"
            $first = $deathLines | Select-Object -First 1
            Write-Host ""
            Write-Host ("  !! UNEXPECTED DEATH captured -> {0}" -f $dir) -ForegroundColor Red
            Write-Host ("     {0}" -f $first) -ForegroundColor Red
        }
        elseif ($missingStreak -ge 2) {
            # A different failure shape: the process is gone but the supervisor never
            # logged a death (it was stopped on purpose, or never started). Still worth
            # a capture - silence here was the original bug.
            $incidents++
            $lastIncidentAt = (Get-Date -Format "HH:mm:ss")
            $dir = Capture-Incident -DeathLines @("ynison process absent for $missingStreak polls, no death line in logcat") -Kind "missing"
            Write-Host ""
            Write-Host ("  !! ynison MISSING with no death line -> {0}" -f $dir) -ForegroundColor Yellow
            $missingStreak = 0
        }

        # --- status line ------------------------------------------------------
        $elapsed = (New-TimeSpan -Start $started -End (Get-Date)).ToString("hh\:mm\:ss")
        $stateColor = if ($ynisonState -like "alive*") { "DarkGreen" } else { "Yellow" }
        $line = "  [{0}] checks={1,-4} incidents={2,-3} last={3,-9} ynison={4}" -f `
            $elapsed, $checks, $incidents, $lastIncidentAt, $ynisonState
        Write-Host $line -ForegroundColor $stateColor

        Start-Sleep -Seconds $IntervalSec
    }
}
finally {
    $total = (New-TimeSpan -Start $started -End (Get-Date)).ToString("hh\:mm\:ss")
    Write-Host ""
    Write-Host ("Stopped after {0}, {1} checks, {2} incident(s)." -f $total, $checks, $incidents)
    if ($incidents -gt 0) {
        Write-Host ("Incidents: {0}" -f $OutDir) -ForegroundColor Cyan
        Get-ChildItem -Path $OutDir -Directory | Sort-Object Name | Select-Object -Last 5 |
            ForEach-Object { Write-Host ("  {0}" -f $_.Name) }
    }
    Write-Host "The app was not touched."

    # Release or the next run is locked out forever after any Ctrl+C.
    if ($ownsMutex) {
        try { $mutex.ReleaseMutex() } catch { }
        $mutex.Dispose()
    }
    # Only remove the pid file if it is still ours - a newer watcher may already own it.
    try {
        if ((Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue) -eq "$PID") {
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        }
    } catch { }
}
