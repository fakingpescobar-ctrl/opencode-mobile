# Public tunnel: localtunnel (direct) -> opencode serve 127.0.0.1:4099
# Auto-restart loop, watches live log for URL. Visible window.
$urlFile = Join-Path $env:TEMP "active-url.txt"
$logDir  = Join-Path $env:TEMP "tunnel-logs"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  LOCALTUNNEL -> opencode serve :4099 (auto-restart)" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

while ($true) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$stamp] starting localtunnel..." -ForegroundColor Yellow
    $log = Join-Path $logDir ("lt-" + (Get-Date -Format "HHmmss") + ".log")
    $args = @("-y", "localtunnel", "--port", "4099")
    $proc = Start-Process -FilePath "npx.cmd" -ArgumentList $args -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError ($log + ".err") -PassThru
    $url = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(70)
    while (-not $proc.HasExited -and [DateTime]::UtcNow -lt $deadline -and -not $url) {
        Start-Sleep -Milliseconds 800
        if (Test-Path $log) {
            $lines = Get-Content $log -ErrorAction SilentlyContinue
            foreach ($l in $lines) {
                if ($l -match "https://[a-z0-9\-]+\.loca\.lt") {
                    $url = $Matches[0]
                    break
                }
            }
        }
    }
    if ($url) {
        Set-Content $urlFile $url
        Write-Host "[$stamp] URL: $url" -ForegroundColor Green
        Write-Host "[$stamp] watching... (restart on exit)" -ForegroundColor DarkGreen
        # hold until process dies, then loop restarts it
        while (-not $proc.HasExited) { Start-Sleep -Seconds 2 }
        Write-Host "[$stamp] localtunnel exited. restart in 5s..." -ForegroundColor Yellow
    } else {
        Write-Host "[$stamp] no URL within 70s. tail:" -ForegroundColor Red
        if (Test-Path $log) { Get-Content $log -ErrorAction SilentlyContinue | Select-Object -Last 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
        if (Test-Path $urlFile) { Remove-Item $urlFile -Force }
        if (-not $proc.HasExited) { $proc.Kill() }
        Write-Host "[$stamp] restart in 5s..." -ForegroundColor Yellow
    }
    Start-Sleep -Seconds 5
}