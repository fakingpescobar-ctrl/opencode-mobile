# conductor-run.ps1 - запуск сессии-исполнителя (opencode run, headless) на локальной Qwen3.5-9B
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$dir = "C:\Projects\ocmobile\tools\mobile-bridge"
$prompt = Get-Content -Raw -LiteralPath (Join-Path $dir "conductor-exec-task.md")

Write-Output "[conductor] starting opencode run (conductor) at $(Get-Date -Format 'HH:mm:ss')"

& opencode run --agent conductor -m llama-local/qwen3.5-9b-coder --auto --dir $dir --title "conductor-health" $prompt 2>&1 |
    Tee-Object -FilePath (Join-Path $dir "conductor-run.log") -Append

Write-Output "[conductor] finished at $(Get-Date -Format 'HH:mm:ss')"