<#
.SYNOPSIS
    Dumps the uiautomator hierarchy once per header panel and leaves the files on the device.

.DESCRIPTION
    The mobile agent can read files from /sdcard but cannot tap or dump from its own shell,
    so tap + dump lives here. For each panel: force-stop, launch, tap the icon, dump.

    The panel panels are mutually exclusive by construction (activePanel: String?), so the
    regression signal is exactly this: every dump must contain ONE panel_* tag. Zero means
    the panel did not open, two means the stack bug from before 7d3d647 is back.

.PARAMETER Panel
    Panels to capture: none, settings, stt, mcp, color, font. Default: all five.

.PARAMETER LaunchWaitSec
    Seconds to wait after launch before the first tap. Default 9.

.PARAMETER DumpWaitSec
    Seconds to wait after a tap before dumping. Default 3.

.EXAMPLE
    pwsh -ExecutionPolicy Bypass -File tools\dump-header-panels.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('none', 'settings', 'stt', 'mcp', 'color', 'font')]
    [string[]] $Panel = @('none', 'settings', 'stt', 'mcp', 'color', 'font'),
    [int] $LaunchWaitSec = 9,
    [int] $DumpWaitSec = 3,
    [string] $Adb,
    [string] $Package = 'org.opencode.mobile.debug'
)

$ErrorActionPreference = 'Stop'

if (-not $Adb) {
    # ANDROID_HOME is often unset on Windows, so concat instead of Join-Path on a
    # possibly-null env var - Join-Path throws on $null and kills the whole script.
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\AppData\Local\Android\Sdk") |
        Where-Object { $_ }
    $candidates = $roots | ForEach-Object { "$_\platform-tools\adb.exe" } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -Unique
    if (-not $candidates) { throw 'adb.exe not found. Pass -Adb <path>.' }
    # Not $candidates[0]: a one-element pipeline result is an unwrapped string,
    # so [0] would silently yield the first CHARACTER of the path ('C:\... ' -> 'C').
    $Adb = ($candidates | Select-Object -First 1)
}

# icon x-centers measured from the 1272x2772 px OPPO build (see AGENTS.md).
# y = 219 is the header icon row center.
$HeaderY = 219
$TapX = @{
    'none'     = $null
    'settings' = 880
    'stt'      = 971
    'mcp'      = 535
    'color'    = 698
    'font'     = 789
}

function Invoke-Adb {
    # Not $Args: that name collides with the automatic $args variable, so @Args
    # splatted as empty and adb ran bare (printing its help) instead of the command.
    param([string[]] $AdbArgs)
    $out = & $Adb @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed: $out" }
    return $out
}

function Get-ResourceIds {
    param([string] $DeviceFile)
    (Get-Bounds -DeviceFile $DeviceFile).Keys
}

# Bounds per non-android resource-id, first occurrence wins. Bounds come from the
# dump itself rather than LocalDensity, so what we assert on is what the agent reads.
function Get-Bounds {
    param([string] $DeviceFile)
    $local = Join-Path ([System.IO.Path]::GetTempPath()) ("d_" + [System.IO.Path]::GetFileName($DeviceFile))
    Invoke-Adb @('pull', $DeviceFile, $local) | Out-Null
    [xml] $xml = Get-Content -LiteralPath $local -Raw
    $bounds = [ordered] @{}
    foreach ($n in $xml.SelectNodes('//node')) {
        $id = $n.GetAttribute('resource-id')
        if (-not $id -or $id -match '^android:' -or $bounds.Contains($id)) { continue }
        $m = [regex]::Match($n.bounds, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
        if (-not $m.Success) { continue }
        $bounds[$id] = [pscustomobject]@{
            Left = [int]$m.Groups[1].Value; Top = [int]$m.Groups[2].Value
            Right = [int]$m.Groups[3].Value; Bottom = [int]$m.Groups[4].Value
            Width = [int]$m.Groups[3].Value - [int]$m.Groups[1].Value
            Height = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
        }
    }
    $bounds
}

Write-Host "adb:    $Adb"
Write-Host "device: $((Invoke-Adb @('shell', 'getprop', 'ro.product.model')) -join '')"
Write-Host ''

foreach ($p in $Panel) {
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Invoke-Adb @('shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds $LaunchWaitSec

    $tap = $TapX[$p]
    if ($null -ne $tap) {
        Invoke-Adb @('shell', 'input', 'tap', $tap, $HeaderY) | Out-Null
        Start-Sleep -Seconds $DumpWaitSec
    }

    $deviceFile = "/sdcard/d_$p.xml"
    Invoke-Adb @('shell', 'uiautomator', 'dump', $deviceFile) | Out-Null

    $ids = @(Get-ResourceIds -DeviceFile $deviceFile)
    $panels = @($ids | Where-Object { $_ -like 'panel_*' })
    $bars = @($ids | Where-Object { $_ -in @('ctx_gauge', 'zen_meter') })
    # break in every clause: PowerShell switch runs ALL matching clauses unless
    # broken out of, so 'none' used to match both the baseline and the 'none' arm.
    $verdict = switch ($true) {
        ($p -eq 'none' -and $panels.Count -eq 0) { 'OK baseline (no panel, as expected)'; break }
        ($p -eq 'none') { 'FAIL unexpected panel: ' + ($panels -join ','); break }
        ($panels.Count -eq 1) { 'OK one panel'; break }
        ($panels.Count -eq 0) { 'FAIL panel did not open'; break }
        default { 'FAIL stack: ' + ($panels -join ',') }
    }

    # Geometry: the panels live BELOW the gauges, not below the header. The gauge
    # stack sits between them, so the panel top must be zen_meter bottom + the
    # declared 8.dp gap (= 28px at 3.5 px/dp), never the header bottom + 28.
    $geom = ''
    if ($panels.Count -eq 1) {
        $bounds = Get-Bounds -DeviceFile $deviceFile
        $zenBottom = $bounds['zen_meter'].Top + $bounds['zen_meter'].Height
        $panelTop = $bounds[$panels[0]].Top
        $gap = $panelTop - $zenBottom
        $geom = if ($gap -ge 0) { "gap=${gap}px OK" } else { "gap=${gap}px FAIL panel overlaps gauges" }
    }

    # @() around the args: the -f operator flattens arrays, so a bare $ids would
    # shift every later column and print System.Object[].
    Write-Host ("{0,-9} -> {1,-40} [{2}] {3}" -f @($p, ($ids -join ','), $verdict, $geom))
}

Write-Host ''
Write-Host 'files left on device for the agent:'
foreach ($p in $Panel) { Write-Host "  /sdcard/d_$p.xml" }