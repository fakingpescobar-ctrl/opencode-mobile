param(
    [string]$Model = 'small',
    [string]$Engine = 'whisper',
    [int]$Count = 12,
    [string]$Tag = 'acc1',
    [int]$WaitSeconds = 30,
    [string]$UseIds = '',
    [string]$Dataset = 'C:\Projects\MyFLOW\data\eval_wispr',
    [string]$ReportDir = "$env:TEMP\opencode"
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'org.opencode.mobile.debug'
$rcv = "$pkg/org.opencode.mobile.stt.SttBenchReceiver"
$remoteDir = '/data/local/tmp/bench'
$src = $Dataset

# --- ground truth -------------------------------------------------------
$rows = @{}
foreach ($line in [IO.File]::ReadAllLines("$src\phrases.jsonl")) {
    if (-not $line.Trim()) { continue }
    $o = $line | ConvertFrom-Json
    $truth = $o.their_edited
    if (-not $truth) { $truth = $o.their_asr }
    if (-not $truth) { continue }
    $rows[[IO.Path]::GetFileNameWithoutExtension($o.audio)] = [pscustomobject]@{
        text = $truth; sec = [double]$o.speech_seconds
    }
}
Write-Host "ground truth rows: $($rows.Count)"

# --- pick a spread of clip lengths --------------------------------------
$files = Get-ChildItem "$src\audio\*.wav" | ForEach-Object {
    $id = $_.BaseName
    if ($rows.ContainsKey($id)) {
        [pscustomobject]@{ path = $_.FullName; id = $id; sec = $rows[$id].sec }
    }
}
$buckets = @(
    @{ lo = 0.5; hi = 1.5; n = [int]($Count * 0.25) }
    @{ lo = 1.5; hi = 4.0; n = [int]($Count * 0.35) }
    @{ lo = 4.0; hi = 9.0; n = [int]($Count * 0.25) }
    @{ lo = 9.0; hi = 40.0; n = [int]($Count * 0.15) }
)
$picks = @()
if ($UseIds) {
    foreach ($id in ($UseIds -split ',')) {
        $id = $id.Trim()
        if (-not $id) { continue }
        $f = Get-ChildItem "$src\audio\$id.wav" -ErrorAction SilentlyContinue
        if ($f -and $rows.ContainsKey($id)) {
            $picks += [pscustomobject]@{ path = $f.FullName; id = $id; sec = $rows[$id].sec }
        }
    }
    Write-Host "using fixed ids: $($picks.Count)"
} else {
    foreach ($b in $buckets) {
        $pool = $files | Where-Object { $_.sec -ge $b.lo -and $_.sec -lt $b.hi } | Sort-Object { Get-Random }
        $want = [Math]::Max(1, $b.n)
        $picks += $pool | Select-Object -First $want
    }
    Write-Host "picked: $($picks.Count)"
}
Write-Host ("IDS=" + (($picks | ForEach-Object { $_.id }) -join ','))

# --- push ---------------------------------------------------------------
& $adb shell "mkdir -p $remoteDir" | Out-Null
& $adb shell "chmod 755 $remoteDir" | Out-Null
foreach ($p in $picks) { & $adb push $p.path "$remoteDir/$($p.id).wav" | Out-Null }
& $adb shell "chmod 644 $remoteDir/*.wav" | Out-Null
& $adb shell "ls -l $remoteDir" > $null

# --- helpers ------------------------------------------------------------
function Normalize([string]$s) {
    if (-not $s) { return '' }
    $t = $s.ToLowerInvariant()
    $t = [regex]::Replace($t, '[^\p{L}\p{N}\s]', ' ')
    return ($t -split '\s+' | Where-Object { $_ }) -join ' '
}

function Wer([string]$hyp, [string]$ref) {
    $h = @((Normalize $hyp) -split ' ' | Where-Object { $_ })
    $r = @((Normalize $ref) -split ' ' | Where-Object { $_ })
    if ($r.Count -eq 0) { return $null }
    $d = New-Object 'int[,]' ($r.Count + 1), ($h.Count + 1)
    for ($i = 0; $i -le $r.Count; $i++) { $d[($i), 0] = $i }
    for ($j = 0; $j -le $h.Count; $j++) { $d[0, ($j)] = $j }
    for ($i = 1; $i -le $r.Count; $i++) {
        $ri = $i - 1
        for ($j = 1; $j -le $h.Count; $j++) {
            $hj = $j - 1
            $c = if ($r[$ri] -eq $h[$hj]) { $d[$ri, $hj] } else { $d[$ri, $hj] + 1 }
            $del = $d[$ri, $j] + 1
            $ins = $d[$i, $hj] + 1
            $m = $c
            if ($del -lt $m) { $m = $del }
            if ($ins -lt $m) { $m = $ins }
            $d[$i, $j] = $m
        }
    }
    $last = $d[$r.Count, $h.Count]
    return [Math]::Round(100.0 * $last / $r.Count, 1)
}

function GrabLog() {
    $raw = & $adb logcat -d -t 4000 2>$null
    $res = @{}
    foreach ($l in $raw) {
        if ($l -match "BENCH\s+.*/([0-9a-f-]+)\.wav\s+=>\s*'(.*)'\s*$") { $res[$matches[1]] = $matches[2] }
    }
    $tim = @{}
    foreach ($l in $raw) {
        if ($l -match '(?:whisper|parakeet):.*проход (\d+) мс.*звук (\d+) мс.*rtf ([\d,]+)') {
            $tim['last'] = "pass=$($matches[1])ms audio=$($matches[2])ms rtf=$($matches[3])"
        }
    }
    return @{ text = $res; timing = $tim['last'] }
}

# --- run ----------------------------------------------------------------
& $adb logcat -c
$results = @()
$n = 0
foreach ($p in $picks) {
    $n++
    Write-Host ("[{0}/{1}] {2}  ({3}s)" -f $n, $picks.Count, $p.id.Substring(0, 8), $p.sec)
    & $adb shell "am broadcast -n $rcv -a org.opencode.mobile.STT_BENCH -e file $remoteDir/$($p.id).wav -e model $Model -e engine $Engine" | Out-Null
    Start-Sleep -Seconds $WaitSeconds
    $hyp = (GrabLog).text[$p.id]
    # Замечено: broadcast после logcat -c уходит, но ответ приходит не всегда
    # (первый клип стабильно терялся). Повторяем один раз, прежде чем считать пропажей.
    if (-not $hyp) {
        Write-Host '    retry (no log)'
        Start-Sleep -Seconds 2
        & $adb shell "am broadcast -n $rcv -a org.opencode.mobile.STT_BENCH -e file $remoteDir/$($p.id).wav -e model $Model -e engine $Engine" | Out-Null
        Start-Sleep -Seconds $WaitSeconds
        $hyp = (GrabLog).text[$p.id]
    }
    $ref = $rows[$p.id].text
    $w = if ($hyp) { Wer $hyp $ref } else { $null }
    $results += [pscustomobject]@{
        id = $p.id; sec = [math]::Round($p.sec, 1); wer = $w
        hyp = $hyp; ref = $ref; timing = (GrabLog).timing
    }
    $mark = if ($w -eq $null) { 'NO-LOG' } elseif ($w -eq 0) { 'exact' } else { "$w% WER" }
    Write-Host "    -> $mark"
}

# --- report -------------------------------------------------------------
$out = Join-Path $ReportDir "acc_$Tag.txt"
$sb = [Text.StringBuilder]::new()
[void]$sb.AppendLine("model=$Model engine=$Engine clips=$($picks.Count)")
[void]$sb.AppendLine('')
$ok = $results | Where-Object { $_.wer -ne $null }
foreach ($r in $results) {
    [void]$sb.AppendLine("--- $($r.id)  $($r.sec)s  wer=$($r.wer)")
    [void]$sb.AppendLine("REF: $($r.ref)")
    [void]$sb.AppendLine("HYP: $(if ($r.hyp) { $r.hyp } else { '<no log>' })")
    [void]$sb.AppendLine('')
}
if ($ok.Count -gt 0) {
    $mean = [math]::Round((($ok | Measure-Object -Property wer -Average).Average), 2)
    $exact = ($ok | Where-Object { $_.wer -eq 0 }).Count
    [void]$sb.AppendLine("WER mean = $mean %   exact = $exact / $($ok.Count)   missing = $($results.Count - $ok.Count)")
} else {
    [void]$sb.AppendLine('NO RESULTS')
}
[IO.File]::WriteAllText($out, $sb.ToString(), [Text.UTF8Encoding]::new($false))
Write-Host ''
Write-Host "report: $out"
if ($ok.Count -gt 0) {
    $mean = [math]::Round((($ok | Measure-Object -Property wer -Average).Average), 2)
    $exact = ($ok | Where-Object { $_.wer -eq 0 }).Count
    Write-Host "WER mean = $mean %   exact = $exact / $($ok.Count)"
}
