# Public tunnel: cloudflared -> opencode serve 127.0.0.1:4099
# Usage: pwsh -NoExit -File start-public-tunnel.ps1
$log = Join-Path $env:TEMP "cloudflared-public.log"
Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  CLOUDFLARE TUNNEL -> opencode serve :4099" -ForegroundColor Cyan
Write-Host "  (HA: 4 edge connections, HTTP/2)" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host ""

& "C:\Program Files (x86)\cloudflared\cloudflared.exe" tunnel `
    --url http://127.0.0.1:4099 `
    --protocol http2 `
    --ha-connections 4 `
    --retries 1000 `
    --no-autoupdate `
    --loglevel info *> $log

Write-Host "Tunnel exited. Log: $log"
Read-Host "Press Enter to close"