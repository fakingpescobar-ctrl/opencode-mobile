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

.PARAMETER Task
    verify  — ktlint + detekt + compileDebugKotlin + юнит-тесты JVM
    jstest  — офлайн-тесты JS-ассетов MCP (node, без сети и без gradle)
    utest   — только юнит-тесты JVM (testDebugUnitTest), для быстрого локального
              прогона без ktlint/detekt.

    Почему тесты в verify, а не только в utest: сломанный test-исходник НЕ роняет
    сборку APK (сборка идёт отдельной задачей :app:assembleDebug и от verify не
    зависит) — но и не роняет verify, если тесты в него не включены. Из-за этого
    244 теста молча не компилировались месяцами: сломанный код никто не видел.
    Комментарий тут не защита, зависимость — защита. Проверено: verify просто
    список задач, отдельной «сборки через verify» не существует.

.EXAMPLE
    .\build.ps1 -Task verify
    .\build.ps1 -Task jstest
    .\build.ps1 -Task utest
    .\build.ps1 -Task native -WhisperCppDir C:\src\whisper.cpp
    .\build.ps1 -Task release -SkipNative
    .\build.ps1 -Task binary          # polozhit binayr opencode v jniLibs
#>
param(
    [ValidateSet('verify', 'jstest', 'utest', 'debug', 'release', 'native', 'binary')]
    [string]$Task = 'debug',
    [string]$WhisperCppDir = '',
    [string]$VulkanSdkDir = '',
    [switch]$SkipNative,
    [switch]$SkipBinary,
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

# ---- Binayr opencode: est li on voobsche ----
# app/src/main/jniLibs v .gitignore, binayr opencode 2.x lezhit tolko tam
# (195 MB v kazhdoy kopii, v etom smysla net). Obertka pri etom bessilna:
# assembleDebug prosto ne naidot faila i sobiraet APK BEZ servera. Prilozhenie
# posle ustanovki startuet, opencode ne zapuskaetsya, i prichina neochevidna.
# Poetomu pered debug/release proverka zhestkaya i s ponyatnoy frazoy.
$fetchBinary = Join-Path $Root 'tools\fetch-opencode-binary.ps1'
if ($Task -eq 'binary') {
    & pwsh -ExecutionPolicy Bypass -File $fetchBinary
    exit $LASTEXITCODE
}
if (-not $SkipBinary -and ($Task -eq 'debug' -or $Task -eq 'release')) {
    $problems = @()
    & pwsh -ExecutionPolicy Bypass -File $fetchBinary -Check | Out-Null
    if ($LASTEXITCODE -ne 0) { $problems += 'libopencode.so ne na meste ili ne ta versiya' }
    $jni = Join-Path $Root 'app\src\main\jniLibs\arm64-v8a'
    foreach ($so in @('libldmusl.so', 'libc_musl.so', 'libstdcxx.so', 'libgcc_s.so', 'libbun-musl.so')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jni $so))) { $problems += "net $so" }
    }
    if ($problems.Count -gt 0) {
        Write-Host ''
        Write-Host "SBORKA OSTOROZHENA: ne polnyy komplekt nativnyh bibliotek:" -ForegroundColor Red
        foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
        Write-Host ''
        Write-Host 'Binayr opencode kachaetsya odnoy komandoy:' -ForegroundColor Yellow
        Write-Host '  pwsh -File tools\fetch-opencode-binary.ps1'
        Write-Host 'Ostalnoe (musl-ranTAjm libstdcxx.so/libgcc_s.so, libldmusl.so, libbun-musl.so)'
        Write-Host 'beretsya iz Alpine v3.22 i musl-tools - skript ikh ne kachaet.'
        Write-Host 'Esli nativy na vashey mashine uzhe est, no sbori ne hochet nikh videt:'
        Write-Host '  .\build.ps1 -Task debug -SkipBinary'
        exit 1
    }
}

# ---- JS-тесты ассетов MCP: node, без gradle и без сети ----
# Отдельная задача, а не часть verify: verify - это гейт, который держит зелёным КАЖДый
# коммит, и втягивать туда node значит сделать его зависимым от того, установлен ли node на
# машине сборки. Здесь node обязателен - иначе тест, который нечем запустить, тихо исчезает.
if ($Task -eq 'jstest') {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        Write-Host 'jstest: node не найден в PATH - тесты JS пропущены' -ForegroundColor Red
        exit 1
    }
    $jsTests = Get-ChildItem (Join-Path $Root 'app\src\test\js') -Filter '*.test.mjs' -ErrorAction SilentlyContinue
    if (-not $jsTests) {
        Write-Host 'jstest: тестов не найдено' -ForegroundColor Red
        exit 1
    }
    $failed = @()
    foreach ($t in $jsTests) {
        Write-Host "== $($t.Name) @ $(Get-Date -Format o)"
        & $node.Source $t.FullName
        if ($LASTEXITCODE -ne 0) { $failed += $t.Name }
    }
    if ($failed) {
        Write-Host "jstest: сломалось -> $($failed -join ', ')" -ForegroundColor Red
        exit 1
    }
    Write-Host 'OK: jstest' -ForegroundColor Green
    exit 0
}

# ---- Gradle-задачи и флаги ----
$gradleArgs = @()
switch ($Task) {
    'verify'  { $gradleArgs += @(':app:ktlintCheck', ':app:detekt', ':app:compileDebugKotlin', ':app:testDebugUnitTest') }
    'utest'   { $gradleArgs += ':app:testDebugUnitTest' }
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