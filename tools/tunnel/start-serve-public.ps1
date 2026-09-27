# Public opencode serve: bind 0.0.0.0:4099 (no Tailscale dependency)
# Usage: pwsh -NoExit -File start-serve-public.ps1
$env:OPENCODE_SERVER_USERNAME = "opencode"
$env:OPENCODE_SERVER_PASSWORD = "***REMOVED-server-password***"
Set-Location "C:\"
Write-Host "opencode serve on 0.0.0.0:4099" -ForegroundColor Cyan
opencode serve --hostname 0.0.0.0 --port 4099 --print-logs --log-level INFO
