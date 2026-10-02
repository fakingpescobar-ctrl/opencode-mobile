// verify.mjs — проверка геометрии выпадающих панелей шапки по дампам uiautomator.
//
// Зачем он существует
// -------------------
// Дашпер (tools/dump-header-panels.ps1) снимает d_*.xml на sdcard: force-stop,
// launch, тап по иконке, дамп. Он собирает ДАННЫЕ, но не выносит вердикт —
// вердикт выносится здесь, отдельно от снятия, чтобы падение прибора и падение
// проверки не выглядели одним и тем же событием.
//
// Проверяется ровно одно свойство, и проверяется строго: панели взаимоисключающие.
// Регресс-тест на стек ловит баг из коммита 15e4467, когда Mic гасил Settings,
// а Settings не гасил Mic, и обе панели складывались по высоте вместе с хедером.
// Инвариант с тех пор стал структурным (одно activePanel вместо пяти флагов),
// и этот скрипт — сторож, который заметит, если он снова разойдётся.
//
// Правило базовой линии — и почему оно по имени файла
// ----------------------------------------------------
// В d_none.xml ожидается НОЛЬ panel_*, в остальных РОВНО ОДИН. Это разные
// ожидания, и их нельзя сводить к одному правилу.
//
// Дамп НЕ различает «панель не открылась» и «панель открываться не должна».
// Оба состояния дают дерево без единого panel_*, и по содержимому XML они
// неотличимы — отличить можно только по тому, какую иконку нажимали, а это
// известно лишь из имени файла. Поэтому базовая линия объявляется именем
// (none), и это не формальность: иначе проверка «ровно один panel_*» валила бы
// базовую линию, а проверка «ноль или один» молча пропускала бы панель, которая
// не открылась. Обе ошибки уже ловились по отдельности.
//
// Чего он НЕ делает
// -----------------
// Не проверяет lean (40/60). Полоса — один узел Canvas без потомков, в дереве
// доступности от деления ширины не остаётся ни одного следа, и проверить его
// дампом физически нечем. Проверяется попиксельно по скриншоту, вне дампа.
//
// Не проверяет пересечения иконок шапки с гейджами. a11y-границы иконок
// раздуты до минимальной touch target 48dp (в дампе 91x168 px при реальном
// боксе иконки 22dp), центрированы на y тапа и заведомо выходят за пределы
// своей строки. Такое сравнение всегда даёт ложный overlap примерно на 43px.
// Иконки в этом скрипте не читаются вообще: они нужны дамперу как координаты
// тапа, и только.
//
// Не тапает и не снимает скриншоты: uiautomator dump и screencap из шелла
// приложения падают («Killed» / «Capturing failed»). Всё, что здесь читается,
// снято дампером.
//
// Граница доверия
// ---------------
// Вход — файлы на sdcard, их можно подделать. Поэтому скрипт ПЕЧАТАЕТ измеренные
// величины (панель, зазор, число panel_*) рядом с вердиктом, а не только сам
// вердикт: подделка видна по несовпадению чисел, а не по отсутствию строки
// FAIL. Молчаливый PASS на подделанном дампе — единственный способ узнать о
// подмене слишком поздно.
//
// Запуск (LOOP.md:59-89)
// -----------------------
// Bun не в PATH, запускается musl-загрузчиком, и путь к APK меняется при каждой
// переустановке — зашивать его нельзя:
//
//   pid=$(toybox pidof libldmusl.so | tr ' ' '\n' | head -1)
//   D=$(dirname "$(toybox grep -m1 'libldmusl\.so' /proc/$pid/maps | tr -s ' ' | cut -d' ' -f6-)")
//   "$D/libldmusl.so" "$D/libbun-musl.so" \
//     /storage/emulated/0/Documents/OpencodeTerminal/tools/verify.mjs
//
// Плотность экрана передаётся --density и печатается только справочно: в вердикт
// не входит и по умолчанию не зашита (LOOP.md, правило 4). Проверки идут в px.

import { readdirSync, readFileSync } from "node:fs";

const SDCARD = "/sdcard";
// On the phone the dumps sit in /sdcard. On the PC they are pulled to a local folder,
// so the directory is overridable instead of hard-coded - one file, both sides.
const wantDir = process.argv.find((a) => a.startsWith("--dir="));
const DUMP_DIR = wantDir ? wantDir.split("=")[1] : SDCARD;
const PANEL = /^panel_(mcp|color|font|settings|stt)$/;
const BASELINE = /(^|_)none\./;

// testTagsAsResourceId выставлен на корне оверлея, поэтому testTag приезжает
// в дамп как resource-id. Пока флага нет, эти теги не появятся вовсе, и скрипт
// честно ругнётся на отсутствие гейджей, а не молча ничего не проверит.
const wantDensity = process.argv.find((a) => a.startsWith("--density="));
const density = wantDensity ? parseFloat(wantDensity.split("=")[1]) : 3.5;
const args = process.argv.slice(2).filter((a) => !a.startsWith("--"));

function targets() {
  if (args.length) return args;
  return readdirSync(DUMP_DIR)
    .filter((f) => /^d_.*\.xml$/.test(f))
    .sort()
    .map((f) => `${DUMP_DIR}/${f}`);
}

function nodes(file) {
  let xml;
  try {
    xml = readFileSync(file, "utf8");
  } catch {
    // Дампера мог не отработать для этой иконки. Молча пропустить нельзя:
    // отсутствующий файл выглядел бы как «проверять нечего» и тянул бы
    // перезапись прошлого d_*.xml, из-за чего сбой выглядел бы успехом.
    console.log(`FAIL  ${file} — файла нет (дампер не отработал?)`);
    process.exit(2);
  }
  const out = [];
  const re = /<node\b([^>]*?)\/?>/g;
  let m;
  while ((m = re.exec(xml))) {
    const a = m[1];
    const g = (k) => a.match(new RegExp(`${k}="([^"]*)"`))?.[1] ?? "";
    const b = g("bounds").match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;
    out.push({
      id: g("resource-id"),
      x1: +b[1],
      y1: +b[2],
      x2: +b[3],
      y2: +b[4],
      w: +b[3] - +b[1],
      h: +b[4] - +b[2],
    });
  }
  return out;
}

const dp = (px) => (px / density).toFixed(2);
let failed = 0;
const files = targets();

if (!files.length) {
  console.log("FAIL  нет ни одного дампа: положить d_*.xml на /sdcard");
  process.exit(2);
}

for (const file of files) {
  const base = file.split("/").pop();
  const isBaseline = BASELINE.test(base);
  const all = nodes(file);
  const panels = all.filter((n) => PANEL.test(n.id));
  const ctx = all.find((n) => n.id === "ctx_gauge");
  const zen = all.find((n) => n.id === "zen_meter");
  const lines = [];
  let bad = 0;
  const say = (ok, text) => {
    if (!ok) bad++;
    lines.push(`  ${ok ? "PASS" : "FAIL"}  ${text}`);
  };

  say(!!ctx, `ctx_gauge найден${ctx ? ` ${ctx.w}x${ctx.h} px` : " — теги не в дампе, проверь testTagsAsResourceId на корне"}`);
  say(!!zen, `zen_meter найден${zen ? ` ${zen.w}x${zen.h} px` : ""}`);

  const expect = isBaseline ? 0 : 1;
  say(
    panels.length === expect,
    `panel_* = ${panels.length}, ожидалось ${expect}` + (isBaseline ? " (базовая линия)" : ""),
  );

  if (panels.length) {
    for (const p of panels) {
      lines.push(`  ${p.id}: x=${p.x1} y=${p.y1} ${p.w}x${p.h} bottom=${p.y2}`);
    }
    const p = panels[0];
    if (zen) {
      const gap = p.y1 - zen.y2;
      lines.push(`  измерено: panel_top=${p.y1} zen_bottom=${zen.y2} gap=${gap}px (${dp(gap)}dp при ${density}px/dp)`);
      say(gap >= 0, `зазор до панели не отрицательный (${gap}px)`);
      if (gap === 28) lines.push("  PASS  зазор 28px = 8dp при 3.5px/dp, как в коде (padding top = 8.dp)");
    }
    if (ctx) say(p.y1 >= ctx.y2, `панель не наезжает на ctx_gauge (gap=${p.y1 - ctx.y2}px)`);
  }

  if (ctx && zen) {
    say(ctx.w === zen.w, `полосы одной ширины (${ctx.w} / ${zen.w}px)`);
    say(
      ctx.h === zen.h,
      `полосы одной высоты (${ctx.h} / ${zen.h}px = ${dp(ctx.h)}dp при ${density}px/dp)`,
    );
  }

  console.log(`### ${base}${bad ? "  — ЕСТЬ FAIL" : "  — PASS"}`);
  console.log(lines.join("\n"));
  console.log();
  failed += bad;
}

console.log(
  failed ? `ИТОГ: FAIL (провалено ${failed}) — файлов ${files.length}` : `ИТОГ: PASS — файлов ${files.length}, плотность ${density}px/dp (справочно)`,
);
process.exit(failed ? 1 : 0);
