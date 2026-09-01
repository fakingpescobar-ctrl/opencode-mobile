@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d C:\Projects\opencode-mobile
call gradlew.bat :whisperlib:externalNativeBuildDebug :app:assembleDebug > C:\Projects\opencode-mobile\build_log.txt 2>&1