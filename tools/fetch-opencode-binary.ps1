<#
.SYNOPSIS
    Ставит бинарь opencode в app/src/main/jniLibs/arm64-v8a/libopencode.so.

.DESCRIPTION
    Бинарь opencode не лежит в git: 195 МБ в каждом клоне и копии ничего
    не добавляют. Но если его просто не положить, сборка проходит успешно и
    APK собирается БЕЗ сервера - приложение стартует, а opencode не
    запускается, и причина неочевидна. Этот скрипт закрывает дыру.

    Откуда берётся: npm-пакет @opencode/cli-linux-arm64-musl. GitHub-релизов
    у opencode 2.x нет вообще, официальный установщик тоже ничего не качает -
    он рассчитывает на Alpine-хост. Единственный источник - registry.npmjs.org.

    Версия берётся из gradle.properties (opencode.binaryVersion), то есть
    версию меняют в одном месте, и тот же файл читает app/build.gradle.kts
    для BuildConfig.OPENCODE_VERSION.

    Musl-рантайм (libc_musl.so, libstdcxx.so, libgcc_s.so) и libldmusl.so
    этот скрипт НЕ качает: они идут из Alpine v3.22 и из musl-tools, а не
    из npm. Наличие ВСЕХ нативов проверяет build.ps1 через -Check, чтобы
    недостающее было видно сразу, а не на устройстве.

.PARAMETER Check
    Только проверить, ничего не скачивая. Код возврата 1, если бинарь
    отсутствует или это не запрошенная версия.

.PARAMETER Force
    Скачать заново, даже если рядом уже лежит бинарь нужной версии.

.EXAMPLE
    pwsh -File tools\fetch-opencode-binary.ps1
    pwsh -File tools\fetch-opencode-binary.ps1 -Check
    pwsh -File tools\fetch-opencode-binary.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$JniDir = Join-Path $RepoRoot 'app/src/main/jniLibs/arm64-v8a'
$Target = Join-Path $JniDir 'libopencode.so'
$Stamp = Join-Path $JniDir 'opencode-binary.json'

# Бинарь не статический: musl-версия opencode линкуется против Alpine-овского
# GCC-рантайма. Если в файле нет этих строк, это либо windows-x64, либо v1 -
# и он упадёт на устройстве с «Error loading shared library libstdc++.so.6».
$MuslMarkers = @('libstdc++.so.6', 'libgcc_s.so.1')

function Get-OpencodeVersion {
    $props = Join-Path $RepoRoot 'gradle.properties'
    if (-not (Test-Path -LiteralPath $props)) {
        throw "net gradle.properties: $props"
    }
    $line = Select-String -LiteralPath $props -Pattern '^opencode\.binaryVersion=(.+)$' |
        Select-Object -First 1
    if (-not $line) { throw "v gradle.properties net stroki opencode.binaryVersion" }
    return $line.Matches[0].Groups[1].Value.Trim()
}

function Get-Stamp {
    if (Test-Path -LiteralPath $Stamp) {
        try { return Get-Content -LiteralPath $Stamp -Raw | ConvertFrom-Json } catch { return $null }
    }
    return $null
}

function Test-Binary {
    # Возвращает $null, если всё в порядке, иначе - текст проблемы.
    $version = Get-OpencodeVersion
    if (-not (Test-Path -LiteralPath $Target)) {
        return "net libopencode.so ($JniDir). Skript ne zapuskalsya: pwsh -File tools\fetch-opencode-binary.ps1"
    }
    $st = Get-Stamp
    if (-not $st) { return "net stamp-a $Stamp, a sam binayr est - neizvestno kakoy on versii" }
    if ($st.version -ne $version) {
        return "v jniLibs lezhit $($st.version), a v gradle.properties prositsya $version. Progoni -Force"
    }
    $size = (Get-Item -LiteralPath $Target).Length
    if ($size -ne [long]$st.bytes) {
        return "razmer binarya $size ne sovpal shtampom $($st.bytes) - fail poryan ili skopirovan"
    }
    # Дёшево проверяем, что это musl-сборка, а не windows-x64 или v1.
    # Сканируем кусками и останавливаемся, как только нашли все маркеры: они
    # лежат в DT_NEEDED, то есть рядом с началом файла, но не обязательно в
    # первом мегабайте - libgcc_s.so.1 в бинаре 195 МБ нашёлся между 1 и 8 МБ.
    $head = New-Object byte[] 4
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    $fs = [IO.File]::OpenRead($Target)
    try {
        if ($fs.Read($head, 0, 4) -lt 4) { return "libopencode.so pust ili obryvan" }
        if ($head[0] -ne 0x7F -or $head[1] -ne 0x45 -or $head[2] -ne 0x4C -or $head[3] -ne 0x46) {
            return "libopencode.so ne ELF - skopirovano chto-to ne to"
        }
        $chunk = New-Object byte[] 8388608
        while ($seen.Count -lt $MuslMarkers.Count) {
            $n = $fs.Read($chunk, 0, $chunk.Length)
            if ($n -le 0) { break }
            $s = [Text.Encoding]::ASCII.GetString($chunk, 0, $n)
            foreach ($m in $MuslMarkers) { if ($s.Contains($m)) { [void]$seen.Add($m) } }
        }
    } finally { $fs.Dispose() }
    foreach ($m in $MuslMarkers) {
        if (-not $seen.Contains($m)) { return "net markera '$m': eto ne musl-sborka opencode 2.x" }
    }
    return $null
}

$version = Get-OpencodeVersion

if ($Check) {
    $problem = Test-Binary
    if ($problem) {
        Write-Host "NE OK: $problem" -ForegroundColor Red
        exit 1
    }
    Write-Host "OK: libopencode.so $version na meste" -ForegroundColor Green
    exit 0
}

$existing = Test-Binary
if (-not $existing -and -not $Force) {
    Write-Host "OK: libopencode.so uzhe $version" -ForegroundColor Green
    exit 0
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("opencode-bin-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    $url = "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-$version.tgz"
    $tgz = Join-Path $tmp 'pkg.tgz'
    Write-Host "kachyayu $url"
    Invoke-WebRequest -Uri $url -OutFile $tgz -UseBasicParsing

    # tar.exe есть в Windows 10+; распаковываем во временную папку, а не поверх jniLibs.
    $pkg = Join-Path $tmp 'x'
    New-Item -ItemType Directory -Path $pkg -Force | Out-Null
    & tar.exe -xzf $tgz -C $pkg
    if ($LASTEXITCODE -ne 0) { throw "raspakovka ne udalas (tar exit $LASTEXITCODE)" }

    $bin = Join-Path $pkg 'package/bin/opencode'
    if (-not (Test-Path -LiteralPath $bin)) { throw "v tarball net package/bin/opencode" }

    $bytes = (Get-Item -LiteralPath $bin).Length
    if ($bytes -lt 50MB) { throw "podozritelno malenkij binayr: $bytes bayt" }

    New-Item -ItemType Directory -Path $JniDir -Force | Out-Null
    Copy-Item -LiteralPath $bin -Destination $Target -Force

    $sha = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash.ToLower()
    $json = [ordered]@{
        version = $version
        package = "@opencode/cli-linux-arm64-musl@$version"
        bytes   = $bytes
        sha256  = $sha
        source  = $url
    } | ConvertTo-Json
    [IO.File]::WriteAllText($Stamp, $json + "`n", (New-Object Text.UTF8Encoding $false))

    # После записи проверяем тем же кодом, что и -Check: иначе скрипт радостно
    # доложит об успехе, а положить могло не то.
    $problem = Test-Binary
    if ($problem) { throw "posle ukopki: $problem" }

    Write-Host "gotovo: libopencode.so $version, $([math]::Round($bytes/1MB,1)) MB" -ForegroundColor Green
    Write-Host "sha256=$sha"
    Write-Host ""
    Write-Host "Ostalos proverit', chto ryadom lezhat ostalnye nativy (oni tozhe v .gitignore):" -ForegroundColor Yellow
    Write-Host "  libldmusl.so libc_musl.so libstdcxx.so libgcc_s.so libbun-musl.so"
    Write-Host "  i sherpa/onnxruntime, yesli nuzhna lokalnaya reč.  build.ps1 -Task debug soobshchit o proguse."
}
finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
