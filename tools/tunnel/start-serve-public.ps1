# Public opencode serve: bind 0.0.0.0:4099 (no Tailscale dependency)
# Usage: pwsh -NoExit -File start-serve-public.ps1
#
# Пароль НЕ хранится в репозитории. Задайте его в окружении перед запуском:
#   $env:OPENCODE_SERVER_PASSWORD = "<свой пароль>"
$env:OPENCODE_SERVER_USERNAME = "opencode"
if (-not $env:OPENCODE_SERVER_PASSWORD) {
    Write-Error "OPENCODE_SERVER_PASSWORD не задан. Сервер откажется стартовать без пароля."
    exit 1
}
Set-Location "C:\"
Write-Host "opencode serve on 0.0.0.0:4099" -ForegroundColor Cyan
opencode serve --hostname 0.0.0.0 --port 4099 --print-logs --log-level INFO