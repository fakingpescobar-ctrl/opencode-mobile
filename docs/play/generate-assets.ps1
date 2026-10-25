# Готовит графику для Google Play из того, что есть в репозитории.
#
# 1. Обрезает скриншоты с устройства: убирает статус-бар (141 px) и панель
#    навигации (154 px) — иначе соотношение 1272x2772 = 1:2.18 выходит за
#    лимит Play (не более 1:2). Оригиналы складываются в screenshots\raw.
# 2. Рисует иконку 512x512 из вектора ic_launcher_foreground.xml
#    (тот же глиф ">_", фон #0B0E11).
# 3. Рисует feature graphic 1024x500.
#
# Запуск: pwsh -ExecutionPolicy Bypass -File docs\play\generate-assets.ps1

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$shots = Join-Path $PSScriptRoot 'screenshots'
$raw = Join-Path $shots 'raw'
New-Item -ItemType Directory -Force -Path $raw | Out-Null

$statusBar = 141
$navBar = 154

function Get-RawShot([string]$name) {
    $rawPath = Join-Path $raw $name
    if (Test-Path $rawPath) { return $rawPath }
    $path = Join-Path $shots $name
    Move-Item -LiteralPath $path -Destination $rawPath
    return $rawPath
}

# Оригиналы уезжают в raw один раз, дальше режем всегда из них: скрипт можно
# гонять повторно, кроп не накапливается.
Get-ChildItem -LiteralPath $shots -Filter *.png -File | ForEach-Object {
    Get-RawShot $_.Name | Out-Null
}

$cropped = 0
Get-ChildItem -LiteralPath $raw -Filter *.png -File | ForEach-Object {
    $src = [System.Drawing.Image]::FromFile($_.FullName)
    try {
        $h = $src.Height - $statusBar - $navBar
        $dst = New-Object System.Drawing.Bitmap($src.Width, $h)
        $g = [System.Drawing.Graphics]::FromImage($dst)
        try {
            $g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $src.Width, $h)),
                (New-Object System.Drawing.Rectangle(0, $statusBar, $src.Width, $h)),
                [System.Drawing.GraphicsUnit]::Pixel)
        } finally { $g.Dispose() }
        $out = Join-Path $shots $_.Name
        $dst.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
        $dst.Dispose()
        $cropped++
    } finally { $src.Dispose() }
}
"cropped: $cropped"

# --- иконка 512x512 -------------------------------------------------------
$bg = [System.Drawing.ColorTranslator]::FromHtml('#0B0E11')
$fg = [System.Drawing.ColorTranslator]::FromHtml('#22C55E')

function New-Icon([int]$size, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear($bg)
        # Вектор нарисован в viewport 108x108; увеличиваем в 1.5 раза, чтобы
        # глиф занимал ~72% ширины (в адаптивной иконке он был бы мелким).
        $k = $size / 108.0 * 1.5
        $off = ($size - 108 * $k) / 2.0
        function P([double]$x, [double]$y) {
            New-Object System.Drawing.PointF([single]($off + $x * $k), [single]($off + $y * $k))
        }
        $brush = New-Object System.Drawing.SolidBrush($fg)
        try {
            $chevron = @((P 30 38), (P 44 52), (P 30 66), (P 26 62), (P 36 52), (P 26 42))
            $g.FillPolygon($brush, [System.Drawing.PointF[]]$chevron)
            $g.FillRectangle($brush, [single]($off + 48 * $k), [single]($off + 62 * $k), [single](30 * $k), [single](6 * $k))
        } finally { $brush.Dispose() }
    } finally { $g.Dispose() }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

New-Icon 512 (Join-Path $PSScriptRoot 'icon-512.png')

# --- feature graphic 1024x500 --------------------------------------------
$fw = 1024; $fh = 500
$fgBmp = New-Object System.Drawing.Bitmap($fw, $fh)
$g = [System.Drawing.Graphics]::FromImage($fgBmp)
try {
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $rect = New-Object System.Drawing.Rectangle(0, 0, $fw, $fh)
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $bg,
        [System.Drawing.ColorTranslator]::FromHtml('#101A16'), 25.0)
    $g.FillRectangle($grad, $rect)
    $grad.Dispose()

    # глиф слева
    $k = 2.4; $gx = 120.0; $gy = ($fh - 108 * $k) / 2.0
    function Q([double]$x, [double]$y) {
        New-Object System.Drawing.PointF([single]($gx + $x * $k), [single]($gy + $y * $k))
    }
    $brush = New-Object System.Drawing.SolidBrush($fg)
    try {
        $chevron = @((Q 30 38), (Q 44 52), (Q 30 66), (Q 26 62), (Q 36 52), (Q 26 42))
        $g.FillPolygon($brush, [System.Drawing.PointF[]]$chevron)
        $g.FillRectangle($brush, [single]($gx + 48 * $k), [single]($gy + 62 * $k), [single](30 * $k), [single](6 * $k))
    } finally { $brush.Dispose() }

    $titleFont = New-Object System.Drawing.Font('Segoe UI', 46, [System.Drawing.FontStyle]::Bold)
    # Подпись подбираем по ширине: правый край впритык к 1024 обрезается.
    $sub = 'ИИ-агент на телефоне: чат, голос, музыка, инструменты'
    $limit = $fw - 324 - 40
    $subFont = $null
    foreach ($size in 24, 22, 20, 18, 16) {
        $probe = New-Object System.Drawing.Font('Segoe UI', $size)
        $w = $g.MeasureString($sub, $probe).Width
        if ($w -le $limit) { $subFont = $probe; break }
        $probe.Dispose()
    }
    if ($null -eq $subFont) { throw "подпись не влезает в $limit px ни одним размером" }
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $grey = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#9CA3AF'))
    try {
        $g.DrawString('OpenCode Mobile', $titleFont, $white, 320, 150)
        $g.DrawString($sub, $subFont, $grey, 324, 232)
    } finally {
        $titleFont.Dispose(); $subFont.Dispose(); $white.Dispose(); $grey.Dispose()
    }
} finally { $g.Dispose() }
$fgBmp.Save((Join-Path $PSScriptRoot 'feature-graphic-1024x500.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$fgBmp.Dispose()

Get-ChildItem -LiteralPath $PSScriptRoot -Filter *.png -File | ForEach-Object {
    "{0} - {1:N0} KB" -f $_.Name, ($_.Length / 1KB)
}
