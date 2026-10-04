param([string]$Dir = "C:\Projects\opencode-mobile\bench\results")
$rows = @()
Get-ChildItem $Dir -Filter "r_t*.txt" | ForEach-Object {
  Get-Content $_.FullName | ForEach-Object {
    if ($_ -match "^(short|long)\tt(\d+)\ts(\d+)\t(\d+)\t(\d+)\t(\d+)\t\S+\trc=(\d+)\tRTF:([\d.]+)/([\d.]+) = ([\d.]+)") {
      $rows += [pscustomobject]@{
        label = $Matches[1]; threads = [int]$Matches[2]; sid = [int]$Matches[3]; run = [int]$Matches[4]
        wall_ms = [int]$Matches[5]; wav_bytes = [int]$Matches[6]; rc = [int]$Matches[7]
        synth_s = [double]$Matches[8]; audio_s = [double]$Matches[9]; rtf = [double]$Matches[10]
      }
    }
  }
}
if (-not $rows) { Write-Host "no rows"; exit 1 }

function Median([double[]]$v) { $s = $v | Sort-Object; $n = $s.Count; if ($n % 2) { $s[[int]($n/2)] } else { ($s[$n/2 - 1] + $s[$n/2]) / 2 } }

# wav bytes -> audio seconds: 24kHz 16-bit mono = 48000 B/s
Write-Host "label`tthreads`tsid`twall_med_ms`tsynth_med_s`taudio_med_s`trtf_med`trtf_worst`tper_sentence_synth_s`tn"
$rows | Group-Object label, threads, sid | ForEach-Object {
  $g = $_.Group
  $wall = Median ($g | ForEach-Object { [double]$_.wall_ms })
  $syn = Median ($g | ForEach-Object { $_.synth_s })
  $aud = Median ($g | ForEach-Object { $_.audio_s })
  $rtf = Median ($g | ForEach-Object { $_.rtf })
  $worst = ($g | ForEach-Object { $_.rtf } | Measure-Object -Maximum).Maximum
  # short.txt = 2 sentences, long.txt = ~14 sentences
  $nSent = if ($g[0].label -eq "short") { 2 } else { 14 }
  $per = [math]::Round($syn / $nSent, 2)
  "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}`t{8}" -f $g[0].label, $g[0].threads, $g[0].sid,
    [int]$wall, $syn, $aud, $rtf, $worst, $per, $g.Count
}
$fail = $rows | Where-Object { $_.rc -ne 0 }
Write-Host ""
Write-Host "runs=$($rows.Count) failures=$($fail.Count)"
