<#
.SYNOPSIS
    Ставит ВЕСЬ комплект нативов в app/src/main/jniLibs/arm64-v8a/.

.DESCRIPTION
    Нативы не лежат в git: 301 МБ в каждом клоне и копии ничего не
    добавляют. Но если их просто не положить, сборка проходит успешно
    и APK собирается БЕЗ сервера - приложение стартует, а opencode не
    запускается, и причина неочевидна. Этот скрипт закрывает дыру.

    Восемь файлов, четыре публичных источника:

    libopencode.so       npm @opencode/cli-linux-arm64-musl
                         GitHub-релизов у opencode 2.x нет вообще,
                         официальный установщик тоже ничего не качает -
                         он рассчитывает на Alpine-хост.
    libbun-musl.so       npm @oven/bun-linux-aarch64-musl. Это не
                         библиотека: ELF типа ET_EXEC, то есть сам
                         bun, просто переименованный. Нужен приложению
                         как JS-рантайм для Retention и harness-скриптов.
    libc_musl.so         Alpine v3.21 musl (ld-musl-aarch64.so.1)
    libldmusl.so         тот же файл под вторым именем, побайтово
                         идентичен - загрузчик и libc это одно и то же
    libstdcxx.so         Alpine v3.21 libstdc++
    libgcc_s.so          Alpine v3.21 libgcc
                         Бинарь opencode НЕ статический, он линкуется
                         против musl-овского GCC-рантайма. Родные
                         библиотеки Android не подходят: они сами
                         собраны против bionic.
libsherpa-onnx-jni.so  sherpa-onnx, статическая сборка aar;
                          в tar.bz2 лежит другая, на 4.7 МБ, и она
                          НЕ подходит.

    libonnxruntime.so НЕ входит: onnxruntime влинкован статически в
    libsherpa-onnx-jni.so (llvm-readelf -d не показывает его в NEEDED),
    а грузит его только System.loadLibrary, которого в приложении нет.
    Это был мёртвый вес на 22 МБ в каждом APK.

    Версии закреплены пинами с sha256: подмена версии молча ломает
    рантайм, а размер совпадает у разных сборок (например musl 1.2.5-r11
    и -r12 весят одинаково, но различаются хешем), поэтому проверяется
    именно хеш.

    Версия opencode берётся из gradle.properties (opencode.binaryVersion) -
    то есть версию меняют в одном месте, и тот же файл читает
    app/build.gradle.kts для BuildConfig.OPENCODE_VERSION.

.PARAMETER Check
    Только проверить, ничего не скачивая. Код возврата 1, если
    какого-то файла нет или его хеш не совпадает с пином.

.PARAMETER Force
    Скачать заново, даже если рядом уже лежит комплект нужных версий.

.PARAMETER SkipTts
    Не трогать ТОЛЬКО libsherpa-onnx-jni.so - это 24 МБ офлайн-озвучки.
    Остальные файлы, включая libbun-musl.so (JS-рантайм для
    Retention и harness-скриптов), флаг не затрагивает: без bun
    приложение собирается, но harness на устройстве не запустится.

.EXAMPLE
    pwsh -File tools\fetch-opencode-binary.ps1
    pwsh -File tools\fetch-opencode-binary.ps1 -Check
    pwsh -File tools\fetch-opencode-binary.ps1 -Force
    pwsh -File tools\fetch-opencode-binary.ps1 -SkipTts
#>
[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Force,
    [switch]$SkipTts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$JniDir = Join-Path $RepoRoot 'app/src/main/jniLibs/arm64-v8a'
$Stamp = Join-Path $JniDir 'natives.json'

# Бинарь opencode не статический: musl-версия линкуется против Alpine-овского
# GCC-рантайма. Если в файле нет этих строк, это либо windows-x64, либо v1 -
# и он упадёт на устройстве с «Error loading shared library libstdc++.so.6».
$MuslMarkers = @('libstdc++.so.6', 'libgcc_s.so.1')

$Alpine = 'https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64'
$Sherpa = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8'

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

function Get-Pins {
    $opencode = Get-OpencodeVersion
    # kind: tar -x<z|j>f для gzip/bzip2, zip для aar. member - путь внутри.
    return @(
        @{
            name    = 'libopencode.so'
            from    = "@opencode/cli-linux-arm64-musl@$opencode"
            url     = "https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-$opencode.tgz"
            kind    = 'tar'
            member  = 'package/bin/opencode'
            bytes   = 204999888
            sha256  = '8880b9ce7a6c458eee641b4a22fd1cccd3373bb0f7c4ba6ee3fd4b32ef20fc62'
        }
        @{
            name    = 'libbun-musl.so'
            from    = '@oven/bun-linux-aarch64-musl@1.4.0'
            url     = 'https://registry.npmjs.org/@oven/bun-linux-aarch64-musl/-/bun-linux-aarch64-musl-1.4.0.tgz'
            kind    = 'tar'
            member  = 'package/bin/bun'
            bytes   = 73604864
            sha256  = '1d1810e9a442bbd09a07962ef4efa3105a989c71033eedec0bd1b0bdd59958a3'
        }
        @{
            name    = 'libc_musl.so'
            from    = 'alpine v3.21 musl 1.2.5-r11'
            url     = "$Alpine/musl-1.2.5-r11.apk"
            kind    = 'tar'
            member  = 'lib/ld-musl-aarch64.so.1'
            bytes   = 723480
            sha256  = '44770ff3960d040e6023ff77b809def2e4123db85e064c311f4cfa45a05959f4'
            alsoAs  = 'libldmusl.so'
        }
        @{
            name    = 'libstdcxx.so'
            from    = 'alpine v3.21 libstdc++ 14.2.0-r4'
            url     = "$Alpine/libstdc%2B%2B-14.2.0-r4.apk"
            kind    = 'tar'
            member  = 'usr/lib/libstdc++.so.6.0.33'
            bytes   = 2754992
            sha256  = '1f1a71be4319632f743507ef7c6eaebaccad8d6f277055cf2c6b16b544f9f418'
        }
        @{
            name    = 'libgcc_s.so'
            from    = 'alpine v3.21 libgcc 14.2.0-r4'
            url     = "$Alpine/libgcc-14.2.0-r4.apk"
            kind    = 'tar'
            member  = 'usr/lib/libgcc_s.so.1'
            bytes   = 133008
            sha256  = '69c45f16064b7b61b9a3d64c37395d13368a8fe719745d49e9508ec650fea971'
        }
        # libonnxruntime.so сюда НЕ входит и больше не качается: в
        # libsherpa-onnx-jni.so из aar onnxruntime влинкован статически
        # (llvm-readelf -d показывает ни одной записи NEEDED про
        # onnxruntime), а System.loadLibrary("onnxruntime") в приложении
        # не вызывает никто. 22 МБ мёртвого веса в каждом APK.
        @{
            name    = 'libsherpa-onnx-jni.so'
            from    = 'sherpa-onnx 1.13.8 static-link-onnxruntime aar'
            url     = "$Sherpa/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
            kind    = 'zip'
            member  = 'jni/arm64-v8a/libsherpa-onnx-jni.so'
            bytes   = 24169352
            sha256  = 'b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8'
            tts     = $true
        }
    )
}

function Get-Sha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
}

function Test-Pin {
    # Возвращает $null, если файл на месте и хеш совпадает, иначе - текст проблемы.
    param([hashtable]$pin)
    $path = Join-Path $JniDir $pin.name
    if (-not (Test-Path -LiteralPath $path)) {
        return "net $($pin.name) v $JniDir"
    }
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -ne [long]$pin.bytes) {
        return "$($pin.name): razmer $size, a ozhidalsya $($pin.bytes)"
    }
    $sha = Get-Sha256 $path
    if ($sha -ne $pin.sha256) {
        return "$($pin.name): sha256 $sha, a pin $($pin.sha256)"
    }
    return $null
}

function Test-OpencodeMarkers {
    # Дёшево проверяем, что это musl-сборка, а не windows-x64 или v1.
    # Сканируем кусками и останавливаемся, как только нашли все маркеры: они
    # лежат в DT_NEEDED, то есть рядом с началом файла, но не обязательно в
    # первом мегабайте - libgcc_s.so.1 в бинаре 195 МБ нашёлся между 1 и 8 МБ.
    $target = Join-Path $JniDir 'libopencode.so'
    $head = New-Object byte[] 4
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    $fs = [IO.File]::OpenRead($target)
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
        if (-not $seen.Contains($m)) { return "net markera '$m' v libopencode.so: eto ne musl-sborka opencode 2.x" }
    }
    return $null
}

function Get-Problems {
    param([object[]]$pins)
    $problems = @()
    foreach ($pin in $pins) {
        if ($SkipTts -and $pin.ContainsKey('tts')) { continue }
        $bad = Test-Pin $pin
        if ($bad) { $problems += $bad }
        if ($pin.ContainsKey('alsoAs')) {
            $other = Test-Pin @{ name = $pin['alsoAs']; bytes = $pin.bytes; sha256 = $pin.sha256 }
            if ($other) { $problems += $other }
        }
    }
    # Маркеры проверяем только если сам бинарь уже на месте: при первом
    # запуске его нет, и OpenRead на отсутствующем файле роняет скрипт
    # раньше, чем он успевает что-то скачать.
    if (Test-Path -LiteralPath (Join-Path $JniDir 'libopencode.so')) {
        $markers = Test-OpencodeMarkers
        if ($markers) { $problems += $markers }
    }
    return $problems
}

function Save-Stamp {
    param([object[]]$pins)
    $entries = foreach ($pin in $pins) {
        if ($SkipTts -and $pin.ContainsKey('tts')) { continue }
        $path = Join-Path $JniDir $pin.name
        [ordered]@{
            name   = $pin.name
            from   = $pin.from
            url    = $pin.url
            bytes  = (Get-Item -LiteralPath $path).Length
            sha256 = Get-Sha256 $path
        }
    }
    $json = [ordered]@{
        generator = 'tools/fetch-opencode-binary.ps1'
        opencode  = Get-OpencodeVersion
        files     = @($entries)
    } | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText($Stamp, $json + "`n", (New-Object Text.UTF8Encoding $false))
}

$pins = Get-Pins

if ($Check) {
    $problems = Get-Problems $pins
    if ($problems) {
        Write-Host "NE OK:" -ForegroundColor Red
        foreach ($p in $problems) { Write-Host "  - $p" }
        Write-Host ""
        Write-Host "Skript ne zapuskalsya: pwsh -File tools\fetch-opencode-binary.ps1" -ForegroundColor Yellow
        exit 1
    }
    $fileCount = 0
foreach ($p in $pins) {
    $fileCount += 1
    if ($p.ContainsKey('alsoAs')) { $fileCount += 1 }
}
Write-Host "OK: nativy na meste ($($pins.Count) pinov, $fileCount faylov)" -ForegroundColor Green
    exit 0
}

if (-not $Force) {
    $problems = Get-Problems $pins
    if (-not $problems) {
        Write-Host "OK: nativy uzhe na meste, nichego ne kachayu" -ForegroundColor Green
        exit 0
    }
}

New-Item -ItemType Directory -Path $JniDir -Force | Out-Null

# Один архив может дать несколько файлов (musl - два имени), поэтому
# группируем по url: качаем и распаковываем каждый источник один раз.
$groups = $pins | Where-Object { -not ($SkipTts -and $_.ContainsKey('tts')) } |
    Group-Object -Property url | Sort-Object { $_.Group[0].bytes }

foreach ($group in $groups) {
    $first = $group.Group[0]
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("natives-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        $arc = Join-Path $tmp 'archive'
        Write-Host "kachyayu $($first.url)"
        Invoke-WebRequest -Uri $first.url -OutFile $arc -UseBasicParsing

        foreach ($pin in $group.Group) {
            $out = Join-Path $tmp ([Guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $out -Force | Out-Null
            # Извлекаем ТОЛЬКО нужный файл: в apk рядом лежат симлинки
            # (libstdc++.so.6 -> .6.0.33), а Windows их всё равно не создаст.
            if ($pin.kind -eq 'zip') {
                & tar.exe -xf $arc -C $out $pin.member
            } else {
                & tar.exe -xzf $arc -C $out $pin.member 2>$null
                if ($LASTEXITCODE -ne 0) {
                    & tar.exe -xjf $arc -C $out $pin.member
                }
            }
            if ($LASTEXITCODE -ne 0) { throw "raspakovka $($pin.member) ne udalas (tar exit $LASTEXITCODE)" }

            $src = Join-Path $out $pin.member
            if (-not (Test-Path -LiteralPath $src)) { throw "v arhive net $($pin.member)" }
            $size = (Get-Item -LiteralPath $src).Length
            if ($size -ne [long]$pin.bytes) {
                throw "$($pin.member): razmer $size, a pin $($pin.bytes). Skachali ne to?"
            }
            $sha = Get-Sha256 $src
            if ($sha -ne $pin.sha256) {
                throw "$($pin.member): sha256 $sha, a pin $($pin.sha256). Skachali ne to?"
            }
            $dest = Join-Path $JniDir $pin.name
            Copy-Item -LiteralPath $src -Destination $dest -Force
            Write-Host ("  -> {0} {1} MB" -f $pin.name, [math]::Round($size / 1MB, 1))
            if ($pin.ContainsKey('alsoAs')) {
                Copy-Item -LiteralPath $src -Destination (Join-Path $JniDir $pin['alsoAs']) -Force
                Write-Host ("  -> {0} (to zhe samyy fail, vtoroe imya)" -f $pin['alsoAs'])
            }
        }
    }
    finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Проверяем тем же кодом, что и -Check: иначе скрипт радостно доложит об
# успехе, а положить могло не то.
$problems = Get-Problems $pins
if ($problems) {
    Write-Host "posle ukopki:" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" }
    exit 1
}

Save-Stamp $pins
$total = (Get-ChildItem -LiteralPath $JniDir -Filter '*.so' |
    Measure-Object -Property Length -Sum).Sum
Write-Host ""
Write-Host "gotovo: $($pins.Count) nativov, $([math]::Round($total / 1MB, 1)) MB v $JniDir" -ForegroundColor Green
Write-Host "stamp: $Stamp"
