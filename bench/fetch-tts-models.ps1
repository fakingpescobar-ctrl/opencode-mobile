$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$dst = "C:\Projects\opencode-mobile\bench\models"
New-Item -ItemType Directory -Force -Path $dst | Out-Null

$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
$files = @(
  @{ u = "$base/v1.13.8/sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2"; f = "android-bin.tar.bz2" },
  @{ u = "$base/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"; f = "supertonic3-int8.tar.bz2" },
  @{ u = "$base/tts-models/vits-piper-ru_RU-ruslan-medium-int8.tar.bz2"; f = "piper-ruslan-int8.tar.bz2" }
)

foreach ($x in $files) {
  $out = Join-Path $dst $x.f
  if (Test-Path $out) {
    $have = (Get-Item $out).Length
    Write-Host "SKIP $($x.f) already $have bytes"
    continue
  }
  Write-Host "GET $($x.f) ..."
  $sw = [Diagnostics.Stopwatch]::StartNew()
  try {
    Invoke-WebRequest -Uri $x.u -OutFile $out -TimeoutSec 900 -UseBasicParsing
    $sw.Stop()
    $mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
    Write-Host ("OK   {0}  {1} MB in {2}s" -f $x.f, $mb, [math]::Round($sw.Elapsed.TotalSeconds))
  } catch {
    Write-Host ("FAIL {0}  {1}" -f $x.f, $_.Exception.Message)
  }
}

foreach ($x in $files) {
  $arc = Join-Path $dst $x.f
  if (-not (Test-Path $arc)) { continue }
  $name = [IO.Path]::GetFileNameWithoutExtension($x.f)
  $dir = Join-Path $dst $name
  if (Test-Path $dir) { Write-Host "SKIP extract $name"; continue }
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Write-Host "EXTRACT $name ..."
  & tar -xjf $arc -C $dir
  if ($LASTEXITCODE -ne 0) { Write-Host "EXTRACT FAIL $name code=$LASTEXITCODE" }
  else { Write-Host "EXTRACT OK $name" }
}

Write-Host "ALL DONE"
exit 0
