# Build debug APK (portable, ASCII-only log). Обёртка над build.ps1:
# корень и JDK опредляются автоматически, лог — build-assemble.log.
& "$PSScriptRoot\build.ps1" -Task debug -Log (Join-Path $PSScriptRoot 'build-assemble.log')
exit $LASTEXITCODE