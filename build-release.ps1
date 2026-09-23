# Build release APK (ASCII-only log)
$log = 'C:\Projects\opencode-mobile\build-release.log'
Remove-Item $log -ErrorAction SilentlyContinue
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& 'C:\Projects\opencode-mobile\gradlew.bat' :app:assembleRelease --console=plain *> $log
'EXIT: ' + $LASTEXITCODE | Out-File $log -Append