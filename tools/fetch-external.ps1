<#
.SYNOPSIS
    Ставит внешние исходники для сборки whisper-нативки.

.DESCRIPTION
    Модуль whisperlib собирается из исходников, и три его зависимости
    в репозитории не лежат. Без них gradle падает на
    :whisperlib:configureCMakeRelease, и причина неочевидна: все три -
    молчаливые предпосылки, а сам модуль - маленькая часть проекта.

    whisper.cpp   Внешний репозиторий, собирается из своего исходника.
                  Версия закреплена файлом
                  whisperlib/src/main/jni/whisper/WHISPER_PIN - это тот
                  же пин, который проверяет build.gradle, поэтому скрипт
                  и сборка всегда согласованы. Путь по умолчанию - тот
                  же, что ожидает build.gradle.

    third_party/ncnn
                  CMakeLists собирает отдельную библиотеку ncnnwhisper
                  через add_subdirectory(third_party/ncnn) и без неё
                  whisper не собирается вообще - это не опциональный
                  "движок получше", а вторая половина. Клон с
                  --recurse-submodules обязателен: сабмодуль glslang
                  нужен для SPIR-V, без него падает конфигурация cmake.
                  Размер 100-200 МБ, поэтому шаг долгий и скрипт
                  терпим к обрывам (ретраи на DPI).

    VulkanSDK     Ставится LunarG-инсталлятором, скачать его скриптом
                  нельзя - это GUI-установщик с лицензионным соглашением.
                  Скрипт только проверяет наличие и говорит, что делать,
                  если его нет.

.EXAMPLE
    pwsh -File tools\fetch-external.ps1
    pwsh -File tools\fetch-external.ps1 -Check
    pwsh -File tools\fetch-external.ps1 -Force
    pwsh -File tools\fetch-external.ps1 -WhisperCppDir D:\src\whisper.cpp
#>
[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Force,
    [switch]$SkipVulkan,
    [string]$WhisperCppDir
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$PinFile = Join-Path $RepoRoot 'whisperlib/src/main/jni/whisper/WHISPER_PIN'
$NcnnDir = Join-Path $RepoRoot 'third_party/ncnn'
$NcnnUrl = 'https://github.com/Tencent/ncnn.git'
$WhisperUrl = 'https://github.com/ggerganov/whisper.cpp.git'
# Те же дефолты, что в whisperlib/build.gradle (строки 10-11).
if (-not $WhisperCppDir) { $WhisperCppDir = 'C:/Projects/whisper.cpp' }
$VulkanSdkDir = 'C:/VulkanSDK/1.4.357.0'
# Плану ncnn-whisper-plan.md шаг 2.1 обещает ретраи на DPI.
$CloneAttempts = 3

function Get-PinnedWhisper {
    if (-not (Test-Path -LiteralPath $PinFile)) {
        throw "net fayla pina: $PinFile"
    }
    $sha = (Get-Content -LiteralPath $PinFile -Raw).Trim()
    if ($sha -notmatch '^[0-9a-f]{40}$') {
        throw "v pine ne sha40: '$sha'"
    }
    return $sha
}

function Invoke-Retry {
    # git clone на 100-200 МБ регулярно рвётся по дороге. Без ретраев
    # человек видит ошибку и думает, что сеть или репозиторий сломаны.
    param([string]$What, [scriptblock]$Action)
    for ($i = 1; $i -le $CloneAttempts; $i++) {
        if ($i -gt 1) {
            Write-Host "  popytka $i iz $CloneAttempts cherez 5 s..."
            Start-Sleep -Seconds 5
        }
        try {
            & $Action
            return $true
        }
        catch {
            Write-Host "  ne udalos: $($_.Exception.Message)"
        }
    }
    Write-Host "  $What ne udalos za $CloneAttempts popytok" -ForegroundColor Red
    return $false
}

function Test-Whisper {
    $dir = $WhisperCppDir.Replace('\', '/')
    if (-not (Test-Path -LiteralPath (Join-Path $dir '.git'))) {
        return "net whisper.cpp v $dir"
    }
    $head = git -C $dir rev-parse HEAD 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $head) {
        return "whisper.cpp v $dir ne git-repozitoriy"
    }
    $pinned = Get-PinnedWhisper
    if ($head.Trim() -ne $pinned) {
        return "HEAD whisper.cpp = $($head.Trim().Substring(0, 7)), a v WHISPER_PIN zapin $($pinned.Substring(0, 7)). Programma ego otkazhivatsya tak zhe"
    }
    # ggml лежит рядом с исходником whisper.cpp; если его нет, cmake
    # дойдёт до configureCMake и упадёт уже там, с менее понятным текстом.
    if (-not (Test-Path -LiteralPath (Join-Path $dir 'ggml'))) {
        return "v whisper.cpp net kataloga ggml - klon okazalsya nepolnym"
    }
    return $null
}

function Test-Ncnn {
    if (-not (Test-Path -LiteralPath (Join-Path $NcnnDir '.git'))) {
        return "net ncnn v $NcnnDir"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $NcnnDir 'src'))) {
        return "v ncnn net kataloga src - klon nepolnyy"
    }
    # Сабмодуль проверяем по содержимому каталога, а не через
    # git submodule status: статус недоступен на свежем клоне, а дешевле
    # просто убедиться, что каталог не пустой.
    $sub = Join-Path $NcnnDir 'glslang'
    if (-not (Test-Path -LiteralPath $sub)) {
        return "net podmoduleya glslang v ncnn. Ego nuzhno dlya SPIR-V, a klon sdelayetsya s --recurse-submodules"
    }
    if (-not (Get-ChildItem -LiteralPath $sub -Force | Select-Object -First 1)) {
        return "katalog glslang pustoy - podmodule ne kachan"
    }
    return $null
}

function Test-Vulkan {
    if ($SkipVulkan) { return $null }
    if (-not (Test-Path -LiteralPath $VulkanSdkDir)) {
        return "net VulkanSDK v $VulkanSdkDir. Eto GUI-installer LunarG, skript ego ne kachaet"
    }
    return $null
}

function Get-Problems {
    $problems = @()
    foreach ($one in @(
            { Test-Whisper },
            { Test-Ncnn },
            { Test-Vulkan })) {
        $bad = & $one
        if ($bad) { $problems += $bad }
    }
    return $problems
}

function Install-Whisper {
    $pinned = Get-PinnedWhisper
    if (Test-Path -LiteralPath (Join-Path ($WhisperCppDir.Replace('\', '/')) '.git')) {
        # Репозиторий уже есть, но не на том коммите: checkout дешевле
        # повторного клона в 200 МБ.
        return Invoke-Retry 'checkout whisper.cpp' {
            git -C $WhisperCppDir fetch --depth 1 origin $pinned
            if ($LASTEXITCODE -ne 0) { throw "git fetch exit $LASTEXITCODE" }
            git -C $WhisperCppDir checkout $pinned
            if ($LASTEXITCODE -ne 0) { throw "git checkout exit $LASTEXITCODE" }
        }
    }
    $parent = Split-Path -Parent $WhisperCppDir
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    return Invoke-Retry 'klon whisper.cpp' {
        git clone --depth 1 $WhisperUrl $WhisperCppDir
        if ($LASTEXITCODE -ne 0) { throw "git clone exit $LASTEXITCODE" }
        # --depth 1 без конкретного ref клонирует ветку по умолчанию,
        # а пин - произвольный старый коммит, поэтому дофетчим ровно его.
        git -C $WhisperCppDir fetch --depth 1 origin $pinned
        if ($LASTEXITCODE -ne 0) { throw "git fetch exit $LASTEXITCODE" }
        git -C $WhisperCppDir checkout $pinned
        if ($LASTEXITCODE -ne 0) { throw "git checkout exit $LASTEXITCODE" }
    }
}

function Install-Ncnn {
    return Invoke-Retry 'klon ncnn' {
        if (Test-Path -LiteralPath $NcnnDir) {
            # Каталог есть, но битый или без сабмодуля. Перекачать
            # дешевле, чем разбираться, что именно не так.
            Remove-Item -LiteralPath $NcnnDir -Recurse -Force
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $NcnnDir) -Force | Out-Null
        git clone --recurse-submodules --depth 1 $NcnnUrl $NcnnDir
        if ($LASTEXITCODE -ne 0) { throw "git clone exit $LASTEXITCODE" }
    }
}

if ($Check) {
    $problems = Get-Problems
    if ($problems) {
        Write-Host "NE OK: ne vse chasti dlya sborki whisper:"
        foreach ($p in $problems) { Write-Host "  - $p" }
        Write-Host ""
        Write-Host "Skript ne zapuskalsya: pwsh -File tools\fetch-external.ps1"
        exit 1
    }
    Write-Host "OK: whisper.cpp, ncnn i VulkanSDK na meste"
    exit 0
}

if (-not $Force) {
    $problems = Get-Problems
    if (-not $problems) {
        Write-Host "OK: vse uzhe na meste, nichego ne kachayu"
        exit 0
    }
    foreach ($p in $problems) { Write-Host "beru: $p" }
}

if (-not (git --version 2>$null)) { throw 'git ne founden v PATH' }

Write-Host "1/2 whisper.cpp $(Get-PinnedWhisper) -> $WhisperCppDir"
if (Test-Whisper) {
    if (-not (Install-Whisper)) { exit 1 }
}

Write-Host "2/2 ncnn -> third_party/ncnn (s podmodulyami, 100-200 MB, dolgo)"
if (Test-Ncnn) {
    if (-not (Install-Ncnn)) { exit 1 }
}

# Проверяем тем же кодом, что и -Check: иначе скрипт радостно доложит об
# успехе, а положил бы не то.
$problems = Get-Problems
if ($problems) {
    Write-Host ""
    Write-Host "posle ukopki:" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    exit 1
}

Write-Host ""
Write-Host "gotovo: whisper.cpp, ncnn i VulkanSDK na meste" -ForegroundColor Green
Write-Host "teper mozhno sobirat native: .\build.ps1 -Task debug" -ForegroundColor Yellow
Write-Host "Inache -Task debug -SkipNative (golovnyy APK, bez STT) is tozhe rabotaet."
