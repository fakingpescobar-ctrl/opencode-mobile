#requires -Version 5.1
# Download + extract sherpa-onnx Parakeet TDT 0.6B v3 int8 model for on-device STT bench.
# ASCII-only output (console-safe). Resume supported. Live progress via curl progress bar.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$dir  = Join-Path $env:TEMP 'opencode\parakeet'
$name = 'sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8'
$tar  = Join-Path $dir "$name.tar.bz2"
$url  = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$name.tar.bz2"

New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host "=== Parakeet TDT 0.6B v3 (int8) ===" -ForegroundColor Cyan
Write-Host "Dir:  $dir"
Write-Host "URL:  $url"
Write-Host ""

if (Test-Path $tar) {
    $have = (Get-Item $tar).Length
    Write-Host ("Resume: already have {0:N0} bytes" -f $have) -ForegroundColor Yellow
} else {
    Write-Host "Starting download (487 MB)..." -ForegroundColor Yellow
}

& curl.exe --ssl-no-revoke -L -C - --progress-bar -o $tar $url
$curlExit = $LASTEXITCODE

if ($curlExit -ne 0) {
    Write-Host ("curl FAILED, exit {0}" -f $curlExit) -ForegroundColor Red
    exit $curlExit
}

$size = (Get-Item $tar).Length
Write-Host ""
Write-Host ("Downloaded: {0:N0} bytes" -f $size) -ForegroundColor Green

$outDir = Join-Path $dir $name
if (-not (Test-Path $outDir)) {
    Write-Host "Extracting (bzip2)..." -ForegroundColor Yellow
    & tar.exe -xjf $tar -C $dir
    if ($LASTEXITCODE -ne 0) {
        Write-Host "tar failed, trying 7z..." -ForegroundColor Yellow
        & 7z.exe x -y -o"$dir" $tar | Out-Null
    }
}

Write-Host ""
Write-Host "=== Files ===" -ForegroundColor Cyan
if (Test-Path $outDir) {
    Get-ChildItem $outDir | ForEach-Object {
        Write-Host ("  {0,-28} {1,12:N0} b" -f $_.Name, $_.Length)
    }
} else {
    Write-Host "  (extract dir not found: $outDir)" -ForegroundColor Red
}

Write-Host ""
Write-Host "DONE. Window stays 120s." -ForegroundColor Green
Start-Sleep -Seconds 120
