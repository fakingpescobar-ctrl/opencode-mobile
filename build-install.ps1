# Build debug APK (ASCII-only log)
$log = 'C:\Projects\opencode-mobile\build-assemble.log'
Remove-Item $log -ErrorAction SilentlyContinue
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& 'C:\Projects\opencode-mobile\gradlew.bat' :app:assembleDebug --console=plain *> $log
'EXIT: ' + $LASTEXITCODE | Out-File $log -Append