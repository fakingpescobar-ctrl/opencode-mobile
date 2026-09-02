#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen_icons.py — генерит tools/icons-preview.html с реальными SVG иконками.

Версия 2: батч-javap (одним процессом на все классы) — быстро.
Аргументы:
  gen_icons.py <dir_cp> <list_of_classes.txt> <out.html>
где dir_cp — корень, classes.txt — пути к Kt.class (repo-relative).
"""
import re, sys, os, subprocess

JAVAP = r"C:\Program Files\Android\Android Studio\jbr\bin\javap.exe"

CMDS = {
    "moveTo": ("M", 2), "moveToRelative": ("m", 2),
    "lineTo": ("L", 2), "lineToRelative": ("l", 2),
    "horizontalLineTo": ("H", 1), "horizontalLineToRelative": ("h", 1),
    "verticalLineTo": ("V", 1), "verticalLineToRelative": ("v", 1),
    "curveTo": ("C", 6), "curveToRelative": ("c", 6),
    "reflectiveCurveTo": ("S", 4), "reflectiveCurveToRelative": ("s", 4),
    "quadTo": ("Q", 4), "quadToRelative": ("q", 4),
    "reflectiveQuadTo": ("T", 2), "reflectiveQuadToRelative": ("t", 2),
    "arcTo": ("A", 7), "arcToRelative": ("a", 7),
    "close": ("Z", 0),
}
FLOAT_RE = re.compile(r"float\s+(-?[\d.]+)f?$")
INVOKE_RE = re.compile(r"Method\s+androidx/compose/ui/graphics/vector/PathBuilder\.(\w+):")
PREV_RE = re.compile(r"Compiled from \"(.+)\"")

def parse_path(out):
    floats, d = [], []
    for line in out.splitlines():
        s = line.strip()
        m = FLOAT_RE.search(s)
        if m:
            floats.append(m.group(1)); continue
        m = INVOKE_RE.search(s)
        if m:
            cmd = m.group(1)
            if cmd in CMDS:
                letter, n = CMDS[cmd]
                if n == 0:
                    d.append(letter)
                elif len(floats) >= n:
                    d.append(letter + " " + " ".join(floats[-n:])); del floats[-n:]
    return " ".join(d).strip()

def main():
    dir_cp, lst, out_file = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(lst, encoding="utf-8") as f:
        rels = [l.strip() for l in f if l.strip() and l.strip().endswith("Kt.class")]
    classes = [os.path.join(dir_cp, r.replace("/", os.sep)) for r in rels]

    # батч: несколькими вызовами по 200 класс-файлов (одна команда javap)
    BATCH = 200
    chunks = [classes[i:i+BATCH] for i in range(0, len(classes), BATCH)]
    full = []
    for ch in chunks:
        r = subprocess.run([JAVAP, "-p", "-c", "-constants"] + ch,
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        full.append("\nCompiled from '_\\nBA=======SEG\n" if False else r.stdout)
    blob = "\n".join(full)

    # разделить по заголовку класса: каждый начинает "public final class ...filled.<Name>Kt {"
    matcher = re.compile(r"final class androidx\.compose\.material\.icons\.filled\.([A-Za-z0-9_]+)Kt\s*\{")
    ms = list(matcher.finditer(blob))
    paths = {}
    for idx, m in enumerate(ms):
        name = m.group(1)  # "Palette", "10k"
        seg_code = blob[m.start():ms[idx+1].start() if idx+1 < len(ms) else len(blob)]
        paths[name] = parse_path(seg_code)
    # соотнести kt-имя -> репозиторий класс
    name2rel = {}
    for r in rels:
        name2rel[os.path.basename(r)[:-6].lstrip("_")] = r  # "PaletteKt" -> Palette
    fav_names = {"Palette","Colorize","ColorLens","FormatColorText","FormatColorFill",
                 "FormatPaint","InvertColors","Gradient","BorderColor"}

    cells, fav = [], []
    for kt, p in paths.items():
        name = kt  # реальное имя иконки
        svg = ("<svg viewBox=\"0 0 24 24\" width=\"44\" height=\"44\" "
               "aria-label=\"%s\"><path d=\"%s\" fill=\"currentColor\" fill-rule=\"evenodd\"/></svg>") % (name, p)
        ic = "<i class=\"ic\">%s</i>" % svg
        is_fav = name in fav_names
        cellf = " fav" if is_fav else ""
        cells.append("<div class=\"cell%s\" data-name=\"%s\">%s<span>&lt;Filled.%s&gt;</span></div>" % (cellf, name, ic, name))
        if is_fav:
            fav.append("<div class=\"cell fav\" data-name=\"%s\">%s<span>&lt;Filled.%s&gt;</span></div>" % (name, ic, name))

    _fav = "".join(fav); _all = "".join(cells); _n = len(cells)
    html = """<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<title>Material Icons - opencode-mobile</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0d1117;color:#c9d1d9;font-family:system-ui,-apple-system,sans-serif;padding:16px}
h1{font-size:20px;margin-bottom:4px;color:#e6edf3}
h2{font-size:14px;color:#8b949e;margin:18px 0 8px;font-weight:600}
p.note{font-size:12px;color:#8b949e;margin-bottom:8px}
input{width:100%;padding:10px 14px;border-radius:8px;border:1px solid #30363d;background:#161b22;color:#c9d1d9;font-size:14px;margin:10px 0 6px}
input:focus{outline:none;border-color:#58a6ff}
#count{font-size:12px;color:#8b949e;margin-bottom:8px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px}
.cell{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px 6px;text-align:center;display:flex;flex-direction:column;gap:6px;align-items:center;justify-content:center;min-height:96px}
.cell span{font-size:10px;color:#8b949e;word-break:break-all;max-width:100%}
.cell svg path{fill:currentColor}
.cell .ic{color:#e6edf3}
.cell.fav{background:#1c2533;border-color:#58a6ff66}
.cell.fav svg path{fill:#79c0ff}
.hide{display:none!important}
</style></head><body>
<h1>Material Icons - доступные в opencode-mobile</h1>
<p class="note">Реальные SVG-векторы из <code>material-icons-extended</code>. Рендер офлайн (без интернета).</p>
<input id="q" type="text" placeholder="Фильтр: palette, color, text, paint">
<div id="count">Показано: <span id="vis">@@N@@</span></div>
<h2>На выбор для «цвета текста»</h2>
<div class="grid" id="fav">@@FAV@@</div>
<h2>Все @@N@@ иконки</h2>
<div class="grid" id="all">@@ALL@@</div>
<script>
const q=document.getElementById('q');let t=null;
q.addEventListener('input',()=>{clearTimeout(t);t=setTimeout(filter,120)});
function filter(){const v=q.value.trim().toLowerCase();let n=0;
document.querySelectorAll('#all .cell').forEach(c=>{
 const ok=!v||c.getAttribute('data-name').toLowerCase().includes(v);
 c.classList.toggle('hide',!ok);if(ok)n++});
document.getElementById('vis').textContent=n;}
</script></body></html>"""
    html = html.replace("@@FAV@@", _fav).replace("@@ALL@@", _all).replace("@@N@@", str(_n))
    with open(out_file, "w", encoding="utf-8") as f:
        f.write(html)
    print("OK:", out_file, len(cells), "иконок,", os.path.getsize(out_file)//1024, "KB")

if __name__ == "__main__":
    main()