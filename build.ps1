<#
.SYNOPSIS
    Portable-сборка opencode-mobile (ASCII-only вывод).
    Не зависит от C:\Projects и конкретного JDK: корень = папка скрипта,
    JDK ищется автоматически (Android Studio JBR -> JAVA_HOME -> PATH).

    Лог всегда пишется в файл (по умолчанию build.log рядом со скриптом),
    в консоль возвращаются только последние строки и вердикт EXIT.

.PARAMETER Task
    verify   : ktlint + detekt + compileDebugKotlin (быстрая проверка)
    debug    : assembleDebug
    release  : assembleRelease
    native   : assembleDebug c полной сборкой нативной части
               (требует whisper.cpp + VulkanSDK, см. -WhisperCppDir/-VulkanSdkDir)

.PARAMETER WhisperCppDir
    Путь к внешнему git-клону whisper.cpp (передаётся в gradle как
    -PwhisperCppDir). Если не задан — берётся дефолт gradle-конфигурации.

.PARAMETER VulkanSdkDir
    Путь к Vulkan SDK LunarG (-PvulkanSdkDir). Если не задан — берётся
    дефолт gradle-конфигурации.

.PARAMETER SkipNative
    Не собирать нативку whisperlib (сборка из prebuilt jniLibs).

.EXAMPLE
    .\build.ps1 -Task verify
    .\build.ps1 -Task native -WhisperCppDir C:\src\whisper.cpp
    .\build.ps1 -Task release -SkipNative
#>
param(
    [ValidateSet('verify', 'debug', 'release', 'native')]
    [string]$Task = 'debug',
    [string]$WhisperCppDir = '',
    [string]$VulkanSdkDir = '',
    [switch]$SkipNative,
    [string]$Log = ''
)

$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
if (-not $Log) { $Log = Join-Path $Root 'build.log' }
$gradlew = Join-Path $Root 'gradlew.bat'

# ---- JDK: Android Studio JBR > JAVA_HOME > java в PATH ----
$jbr = Join-Path ${env:ProgramFiles} 'Android\Android Studio\jbr\bin\java.exe'
$java = $null
if (Test-Path $jbr) {
    $java = Split-Path (Split-Path $jbr -Parent) -Parent
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = $env:JAVA_HOME
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $java = ''   # java из PATH: gradlew сам найдёт
} else {
    throw 'JDK not found: Android Studio JBR / JAVA_HOME / java in PATH (see gradle.properties.example)'
}
if ($java) {
    $env:JAVA_HOME = $java
    $env:PATH = "$java\bin;$env:PATH"
} else {
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
}

# ---- VS-окружение для host-инструмента vulkan-shaders-gen (только нативка) ----
# ggml компилирует shaders-gen MSVC-ом (host-toolchain.cmake): нужно НЕ иметь
# VS-консоль открытой — скрипт сам находит vcvars64.bat и активирует его.
function Find-VcVars {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $vsDir = & $vswhere -latest -products * `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
            -property installationPath 2>$null
        if ($vsDir) {
            $cv = Join-Path $vsDir 'VC\Auxiliary\Build\vcvars64.bat'
            if (Test-Path $cv) { return $cv }
        }
    }
    return ''
}
function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + $a.Replace('"', '\"') + '"' }
    return $a
}
$vcvars = ''
if (-not $SkipNative -and ($Task -eq 'debug' -or $Task -eq 'release' -or $Task -eq 'native')) {
    $vcvars = Find-VcVars
    if (-not $vcvars) {
        throw 'Visual Studio Build Tools (VC++) ne naideny: nuzhny dlya vulkan-shaders-gen (host MSVC). Ustanovite VS s "Desktop development with C++"'
    }
}

# ---- Gradle-задачи и флаги ----
$gradleArgs = @()
switch ($Task) {
    'verify'  { $gradleArgs += @(':app:ktlintCheck', ':app:detekt', ':app:compileDebugKotlin') }
    'debug'   { $gradleArgs += ':app:assembleDebug' }
    'release' { $gradleArgs += ':app:assembleRelease' }
    'native'  { $gradleArgs += ':app:assembleDebug' }
}
if ($SkipNative) { $gradleArgs += '-PskipNativeBuild=true' }
if ($WhisperCppDir) { $gradleArgs += "-PwhisperCppDir=$WhisperCppDir" }
if ($VulkanSdkDir) { $gradleArgs += "-PvulkanSdkDir=$VulkanSdkDir" }
$gradleArgs += '--console=plain'

# ---- Запуск (весь вывод в лог, в консоль — хвост + вердикт) ----
Remove-Item $Log -ErrorAction SilentlyContinue
"== $Task @ $(Get-Date -Format o)" | Out-File $Log

$quotedArgs = ($gradleArgs | ForEach-Object { Quote-Arg $_ }) -join ' '
$invoke = "call `"$gradlew`" $quotedArgs"
if ($vcvars) { $invoke = "call `"$vcvars`" && $invoke" }

cmd.exe /d /c $invoke 2>&1 | Tee-Object -FilePath $Log | Select-Object -Last 12
$exitCode = $LASTEXITCODE
'EXIT: ' + $exitCode | Out-File $Log -Append

if ($exitCode -ne 0) {
    Write-Error "FAILED: $Task (exit $exitCode). Polnyi log: $Log"
    exit 1
}
"OK: $Task (log: $Log)"
exit 0