#!/usr/bin/env bun
// selfharness.js — гейт изменений для петли самообучения.
//
// Зачем он существует
// -------------------
// Петля «агент читает свой лог -> строит гипотезу -> патчит свой код ->
// проверяет» держится на одном обещании: непроверенное изменение не имеет
// права остаться. Обещание держит этот файл.
//
// Почему не `git checkout`: git на устройстве нет (проверено: `which git`
// пуст), а ставить Termux ради отката - это внешняя зависимость, которая
// сломается первая и тихо. Снапшот - это копия файла, и он не может
// отсутствовать по причинам, не связанным с тем, что мы чиним.
//
// Почему не «просто запустить тесты и посмотреть глазами»: тесты на
// устройстве гоняются (проверено - 19 из 19 зелёных на ynison-search), но
// «посмотреть» делает человек. Пока решение принимает человек, цикл не
// замкнут и петля остаётся ручным трудолюбием, выдающим себя за автоматику.
//
// Чего harness НЕ делает
// ----------------------
//  - не решает, правда ли гипотеза. Он отвечает на один вопрос: «после
//    изменения тесты зелёные?». Правдивость гипотезы проверяет человек
//    или следующий прогон.
//  - не трогает код приложения. Область - только tools/. Это граница
//    доверия из docs/SELF-IMPROVEMENT.md: цена ошибки петли равна
//    стоимости её собственного скрипта, и ни рублем дороже.
import {
  existsSync, mkdirSync, readFileSync, writeFileSync, copyFileSync, rmSync, readdirSync,
} from "node:fs";
import { join, basename } from "node:path";

const ROOT = process.env.SELFHARNESS_ROOT || "/storage/emulated/0/Documents/OpencodeTerminal";
const TOOLS = join(ROOT, "tools");
const TESTS = join(ROOT, "test", "js");
const SNAPSHOTS = join(ROOT, ".snapshots");
const REPORT = join(ROOT, "lessons", "last-gate.json");

const GREEN = "зелёное";
const RED = "ПРОВАЛЕНО";

// Признак красного в выводе. Exit code НЕ считается: наш раннер печатает
// «ПРОВАЛЕНО» и выходит с 0, потому что это не ошибка процесса, а вердикт
// тестов. Если смотреть только на код возврата, красный прогон прошёл бы
// как зелёный, и это был бы самый дорогой баг петли: агент закоммитит
// сломанное, решив что починил. Поэтому вердикт читается из текста.
const RED_MARKERS = [RED, "FAIL"];

// Признак того, что тест даже НЕ НАЧАЛСЯ. Это отдельное состояние от
// «зелёного» и от «красного».
//
// На устройстве так выглядит «Cannot find module '../tools/digest.js'»:
// тест не выполнил ни одного утверждения, потому что раскладка каталогов
// не совпала. Такой вывод не содержит ни «зелёного», ни «ПРОВАЛЕНО», и при
// разборе по вердикту он считался зелёным. Гейт рапортовал ok на
// заведомо сломанном инструменте, и правка осталась на диске - именно этот
// случай петля обязана ловить, а она его пропустила.
const NOT_RUN_MARKERS = [
  "Cannot find module",
  "cannot find module",
  "Module not found",
  "error: Cannot find",
  "syntaxerror",
  "ReferenceError",
  "TypeError",
  // Живой провал на устройстве: process.execPath там - libldmusl.so, и
  // spawnSync пытался выполнить «run» как файл. Сообщение не содержит ни
  // одного маркера красного, поэтому гейт считал его зелёным.
  "cannot load run",
  // Наш собственный маркер раннера, когда процесс не запустился вовсе.
  "НЕ ЗАПУСТИЛСЯ",
];

export function verdictOf(text) {
  const t = String(text || "");
  const notRun = NOT_RUN_MARKERS.find((m) => t.includes(m));
  if (notRun) return "НЕ ЗАПУСТИЛСЯ";
  const hit = RED_MARKERS.find((m) => t.includes(m));
  return hit ? RED : GREEN;
}

/**
 * Путь внутри tools/ или отказ.
 *
 * `..` в пути съел бы границу: `tools/../../opencode.jsonc` - это уже не
 * tools, а конфиг с токенами. Проверка идёт по нормализованной строке, а не
 * по resolve(), потому что resolve тут честно отработал бы - обходить надо
 * именно ошибочный ввод, а не «неправильно посчитанный» путь.
 */
export function assertInsideTools(relPath, toolsDir = TOOLS) {
  if (typeof relPath !== "string" || relPath.length === 0) {
    throw new Error(`путь к инструменту пуст или не строка: ${String(relPath)}`);
  }
  const abs = join(toolsDir, relPath);
  const norm = abs.replace(/\\/g, "/");
  const rootNorm = toolsDir.replace(/\\/g, "/").replace(/\/+$/, "") + "/";
  if (!norm.startsWith(rootNorm)) {
    throw new Error(`путь вне tools недопустим: ${relPath} -> ${abs}`);
  }
  return abs;
}

export function ensureDirs(root = ROOT) {
  for (const d of [join(root, "tools"), join(root, "test", "js"), join(root, ".snapshots"), join(root, "lessons")]) {
    try { mkdirSync(d, { recursive: true }); } catch (e) { /* уже есть */ }
  }
}

export function stamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

/**
 * Снять снапшот одного файла перед изменением.
 *
 * Метка времени плюс путь в имени: под одной меткой может быть патч
 * нескольких файлов, и имя не должно столкнуться.
 */
export function snapshot(relPath, label, root = ROOT) {
  // Проверка границы обязана идти против КОНКРЕТНОГО toolsDir этого root.
  // Раньше здесь стоял вызов без root, и проверка смотрела в каталог по
  // умолчанию: при gate(root=...) снимок искал несуществующий файл и падал,
  // а откатывать было бы нечего. Молчаливый откат не по тому файлу хуже
  // отсутствия отката.
  const toolsDir = join(root, "tools");
  const abs = assertInsideTools(relPath, toolsDir);
  if (!existsSync(abs)) throw new Error(`нечего снимать: ${relPath} не существует`);
  const dir = join(root, ".snapshots", label);
  mkdirSync(dir, { recursive: true });
  const dest = join(dir, basename(relPath));
  copyFileSync(abs, dest);
  return dest;
}

/**
 * Откат по метке: вернуть файлы и удалить снапшот.
 *
 * Имена в снапшоте плоские, поэтому вложенный путь не восстановится. Это
 * осознанное ограничение, а не недоработка: агент чинит плоские скрипты.
 */
export function rollback(label, root = ROOT) {
  const dir = join(root, ".snapshots", label);
  if (!existsSync(dir)) throw new Error(`нет снапшота ${label} - откатывать нечего`);
  const restored = [];
  for (const name of readdirSync(dir)) {
    const dest = join(root, "tools", name);
    copyFileSync(join(dir, name), dest);
    restored.push(dest);
  }
  rmSync(dir, { recursive: true, force: true });
  return restored;
}

/**
 * Прогнать тест-файл.
 *
 * `runner` внедрён: на устройстве это Bun.spawnSync, в тестах репозитория -
 * подставной заведомо-красный запуск. Собственный spawn здесь означал бы,
 * что проверить раннер можно только на устройстве, а это ровно тот класс
 * багов, который петля обязана ловить сама.
 */
export function runTestFile(file, runner, root = ROOT) {
  const abs = join(root, "test", "js", file);
  if (!existsSync(abs)) {
    return { ok: false, reason: `нет файла теста ${file}`, detail: "", out: "", verdict: "НЕТ ФАЙЛА" };
  }
  let out = "";
  let threw = null;
  try {
    out = runner(abs) || "";
  } catch (e) {
    threw = String(e && e.message ? e.message : e);
  }
  const verdict = threw ? "НЕ ЗАПУСТИЛСЯ" : verdictOf(out);
  const ok = verdict === GREEN;
  return {
    ok,
    reason: ok ? "" : (threw ? `запуск упал: ${threw}` : (verdict === "НЕ ЗАПУСТИЛСЯ" ? "тест не запустился" : "тесты красные")),
    detail: (out || threw).slice(-2000),
    out,
    verdict,
  };
}

/**
 * Гейт целиком: снапшот -> ПРАВКА -> тесты -> при отказе откат -> отчёт.
 *
 * `apply` вызывается между снятием снапшота и прогоном тестов, и именно в
 * этом порядке. Порядок не переставляется: снапшот после правки снял бы
 * уже сломанное, и откат вернул бы поломку.
 *
 * Раньше apply не было вовсе, и гейт физически не мог проверить изменение:
 * снапшот снимался, тесты гонялись, а патчить было негде. Тест «красный
 * гейт откатывает правку» вскрыл именно это - он клал «broken patch» на
 * диск ДО вызова gate, то есть снапшот уезжал с поломанным содержимым.
 *
 * Отчёт возвращается, а не печатается: вызывающий решает, что с ним делать.
 * Откат идёт ДО сборки отчёта, иначе читатель увидит зелёный отчёт при
 * красном коде на диске.
 */
export function gate(label, changedPaths, testFiles, options = {}) {
  const root = options.root || ROOT;
  const runner = options.runner || defaultRunner;
  ensureDirs(root);
  const snap = [];
  for (const p of changedPaths) snap.push({ path: p, snapshot: snapshot(p, label, root) });
  // Ошибка ВХОДА (newContent без файла) - это ошибка вызова, а не отказа
  // наложения: падать наружу. Ошибка наложения (apply бросил) - это отказ
  // гейта, он идёт в отчёт красным. Смешивать их нельзя: иначе агент
  // получает красный отчёт вместо исключения и может принять одно за
  // другое и продолжить петлю с испорченным состоянием.
  const hasNewContent = Object.prototype.hasOwnProperty.call(options, "newContent");
  if (hasNewContent && (!changedPaths || changedPaths.length === 0)) {
    throw new Error("newContent требует хотя бы одного файла в changedPaths");
  }
  // `applyFailed` - отдельный флаг, а не тип значения. Раньше успех и провал
  // кодировались одним полем, и успешный apply возвращал строку-имя файла,
  // которую следующая проверка читала как сообщение об ошибке. Итог: любая
  // правка через newContent откатывалась с причиной, равной имени файла.
  let applyFailed = null;
  try {
    if (typeof options.apply === "function") options.apply();
    else if (hasNewContent) {
      // Удобный путь для теста и для агента, меняющего один файл целиком.
      // Цель - тот же путь, что и снапшот, а не tools/: иначе правка ушла бы
      // мимо границы доверия, и гейт проверял бы одно, а коммитилось другое.
      writeFileSync(join(root, "tools", changedPaths[0]), options.newContent);
    }
  } catch (e) {
    applyFailed = `ПРИМЕНИТЬ НЕ УДАЛОСЬ: ${e.message}`;
  }
  const results = [];
  if (applyFailed) {
    // Правка не прошла - тесты гонять бессмысленно (они говорили бы о старом
    // коде), но гейт обязан быть красным.
    results.push({ file: "(применение)", ok: false, reason: applyFailed, detail: applyFailed, out: "" });
  } else {
    for (const f of testFiles) results.push({ file: f, ...runTestFile(f, runner, root) });
  }
  const ok = results.length > 0 && results.every((r) => r.ok);
  let restored = [];
  let failedRun = false;
  if (!ok) {
    failedRun = true;
    try {
      restored = rollback(label, root);
    } catch (e) {
      restored = [`ОТКАТ НЕ УДАЛСЯ: ${e.message}`];
    }
  }
  const report = {
    at: new Date().toISOString(),
    label,
    ok,
    changed: changedPaths,
    snapshots: snap.map((s) => s.snapshot),
    restored,
    tests: results.map((r) => ({ file: r.file, ok: r.ok, reason: r.reason })),
  };
  if (failedRun) report.rollback_failed = restored.some((r) => String(r).startsWith("ОТКАТ"));
  try {
    mkdirSync(join(root, "lessons"), { recursive: true });
    writeFileSync(join(root, "lessons", "last-gate.json"), JSON.stringify(report, null, 2));
  } catch (e) { /* отчёт не критичен для решения о коммите */ }
  return report;
}

/**
 * Настоящий раннер: исполняет тест-файл и возвращает его вывод.
 *
 * Код возврата приклеивается к выводу как явный маркер. Раньше возврата не
 * было вовсе, и на устройстве раннер писал «cannot load run» в stderr - а
 * этот текст не содержал ни «ПРОВАЛЕНО», ни «FAIL», ни «Cannot find module».
 * Гейт видел пустой вердикт и рапортовал об успехе, оставив сломанный
 * инструмент на диске. Молчаливая поломка раннера выглядела как зелёный
 * прогон - худший вид отказа в петле самоулучшения.
 *
 * На ПК `bun test.mjs`; на устройстве execPath - это libldmusl.so (загрузчик),
 * а не bun, поэтому команда собирается из переменной SELFHARNESS_BUN, если она
 * задана, иначе из общедоступного bun в PATH.
 */
function bunCommand(abs) {
  const exec = process.env.SELFHARNESS_BUN;
  if (exec) return [exec, "run", abs];
  return ["bun", "run", abs];
}

/**
 * Разобрать команду запуска из SELFHARNESS_BUN_CMD.
 *
 * На устройстве bun не лежит в PATH и не является самостоятельным бинарником:
 * libbun-musl.so запускается через libldmusl.so как загрузчик musl. Просто
 * «bun run файл» там не существует, и раннер обязан уметь получить полную
 * цепочку извне, иначе гейт на устройстве никогда не запустит тест.
 * Формат: команда с аргументами, где последний плейсхолдер %s - файл.
 */
export function parseBunCmd(spec, abs) {
  const parts = String(spec || "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return null;
  const out = [];
  for (const p of parts) out.push(p === "%s" ? abs : p);
  // Если плейсхолдера не было - файл дописывается в конец.
  if (!String(spec).includes("%s")) out.push(abs);
  return out;
}

export function defaultRunner(abs) {
  const full = parseBunCmd(process.env.SELFHARNESS_BUN_CMD, abs);
  const [cmd, ...args] = full || bunCommand(abs);
  let proc;
  try {
    proc = Bun.spawnSync([cmd, ...args], {
      stdout: "pipe",
      stderr: "pipe",
      env: { ...process.env },
    });
  } catch (e) {
    // Раннер не запустился вообще - это провал, а не пустой вывод.
    return `error: НЕ ЗАПУСТИЛСЯ: раннер не запущен (${String(e && e.message ? e.message : e)})\n`;
  }
  const dec = new TextDecoder();
  const out = dec.decode(proc.stdout || new Uint8Array());
  const err = dec.decode(proc.stderr || new Uint8Array());
  const code = proc.exitCode;
  if (code !== 0) {
    return `${out}\n${err}\nПРОВАЛЕНО: процесс завершился с кодом ${code}`;
  }
  return `${out}\n${err}`;
}

/** Диагностика контура. Агент зовёт это первым, до любой правки. */
export function doctor(root = ROOT) {
  ensureDirs(root);
  const toolsDir = join(root, "tools");
  const testsDir = join(root, "test", "js");
  let toolsWritable = false;
  try {
    const probe = join(toolsDir, ".writable-probe");
    writeFileSync(probe, "probe");
    rmSync(probe, { force: true });
    toolsWritable = true;
  } catch (e) { toolsWritable = false; }
  const reportPath = join(root, "lessons", "last-gate.json");
  let lastReport = null;
  try { lastReport = JSON.parse(readFileSync(reportPath, "utf8")); } catch (e) { lastReport = null; }
  return {
    root,
    tools: existsSync(toolsDir) ? readdirSync(toolsDir) : [],
    tests: existsSync(testsDir) ? readdirSync(testsDir).filter((f) => f.endsWith(".mjs")) : [],
    toolsWritable,
    lastReport,
  };
}

export { ROOT, TOOLS, TESTS, SNAPSHOTS, REPORT };

// Прямой запуск: `bun selfharness.js doctor`.
//
// Ветка включается по КОМАНДЕ, а не по имени файла. Раньше стояла проверка
// `argv[1].endsWith("selfharness.js")`, и на устройстве harness лежит под
// другим именем - CLI молча не сработал, напечатал ничего и вышел с 0.
// Молчаливый выход с нулём хуже падения: выглядит как успешный прогон.
const CMD = process.argv[2];
if (CMD === "doctor" || CMD === "gate") {
  if (CMD === "doctor") {
    console.log(JSON.stringify(doctor(), null, 2));
  } else {
    const args = process.argv.slice(3);
    const sep = args.indexOf("--");
    const label = args[0] || stamp();
    const changed = sep >= 0 ? args.slice(1, sep) : [];
    const tests = sep >= 0 ? args.slice(sep + 1) : [];
    const report = gate(label, changed, tests);
    console.log(JSON.stringify(report, null, 2));
    // Код возврата = вердикт. Автоматика должна уметь отличить зелёное от
    // красного, не разбирая JSON.
    process.exit(report.ok ? 0 : 1);
  }
}