# Build release APK (portable, ASCII-only log). Обёртка над build.ps1:
# корень и JDK опредляются автоматически, лог — build-release.log.
& "$PSScriptRoot\build.ps1" -Task release -Log (Join-Path $PSScriptRoot 'build-release.log')
exit $LASTEXITCODE