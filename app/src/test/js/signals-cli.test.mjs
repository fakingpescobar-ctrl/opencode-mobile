#!/usr/bin/env node
// Проверки CLI сигналов: чтение лога, разбор ключей, коды выхода.
//
// Главное здесь - не «нашёл сигнал», а различение состояний. Инструмент,
// который на «лог не открылся» отвечает тем же, что на «ошибок нет»,
// однажды приведёт агента к выводу «у меня всё хорошо» из-за того, что путь
// к логу протух.

import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const SELF = fileURLToPath(import.meta.url);
const HERE = dirname(SELF);
const CLI = process.env.SIGNALS_CLI_PATH ||
  join(HERE, "..", "..", "main", "assets", "harness", "signals-cli.js");

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log("  ok   " + name);
  } catch (e) {
    failures.push(name + " :: " + (e && e.message ? e.message : String(e)));
    console.log("  FAIL " + name);
  }
}

const eq = (a, b, what) => {
  const x = JSON.stringify(a), y = JSON.stringify(b);
  if (x !== y) throw new Error(`${what}: got ${x}, want ${y}`);
};

function okIf(condition, message) {
  if (!condition) throw new Error(message);
}

// Запуск CLI тем же способом, каким его запускают на устройстве: явная
// команда из SELFHARNESS_BUN_CMD, иначе BUN, иначе bun. На ПК это bun.exe,
// на устройстве - musl-мост, и process.execPath там указывает на loader.
function bunParts() {
  const raw = process.env.SELFHARNESS_BUN_CMD || process.env.BUN || "bun";
  return raw.split(/\s+/).filter(Boolean);
}

function bunRun(scriptPath, scriptArgs) {
  const parts = bunParts();
  if (parts.some(p => p.includes("%s"))) {
    return { cmd: parts[0], args: parts.slice(1).map(p => p.replace("%s", scriptPath)).concat(scriptArgs || []) };
  }
  return { cmd: parts[0], args: [...parts.slice(1), scriptPath].concat(scriptArgs || []) };
}

const dir = mkdtempSync(join(tmpdir(), "signals-cli-"));

function runCli(args) {
  const r = bunRun(CLI, args);
  const out = spawnSync(r.cmd, r.args, { encoding: "utf8" });
  if (out.error) throw new Error("не удалось запустить CLI: " + out.error.message);
  return { code: out.status, out: out.stdout || "", err: out.stderr || "" };
}

function writeLog(name, text) {
  const p = join(dir, name);
  writeFileSync(p, text);
  return p;
}

function jsonOf(result) {
  try {
    return JSON.parse(result.out);
  } catch (e) {
    throw new Error("CLI выдал не JSON (" + result.code + "): " + result.out.slice(0, 300) + " / " + result.err.slice(0, 300));
  }
}

const FAILING_LOG = [
  "opencode server listening on http://127.0.0.1:4096",
  "[ynison] open attempt 0 failed: state open timeout",
  "[ynison] open attempt 1 failed: state open timeout",
  "[ynison] open attempt 2 failed: state open timeout",
  "[ynison] attempt 1 failed: Yandex Music did not come online in ~36s",
  "[ynison] attempt 2 failed: Yandex Music did not come online in ~72s",
  "[ynison] state socket closed by server",
].join("\n") + "\n";

// --- находит ------------------------------------------------------------------

await test("находит повторяющийся отказ в логе", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const r = runCli([p]);
  eq(r.code, 0, "код выхода");
  const report = jsonOf(r).logs[0];
  eq(report.error_lines, 5, "строк с признаками ошибки");
  eq(report.signals.length, 2, "сигналов");
  eq(report.signals[0].count, 3, "самый частый повтор");
  okIf(/Yandex Music/.test(report.signals[0].signature) === false,
    "три state open timeout должны быть самым частым");
  okIf(/state open timeout/.test(report.signals[0].signature), "подпись: " + report.signals[0].signature);
});

await test("номер попытки в разных попытках не плодит сигналы", () => {
  const p = writeLog("attempts.log", FAILING_LOG);
  const report = jsonOf(runCli([p])).logs[0];
  eq(report.signals.length, 2, "сигналов");
});

// --- различение состояний ------------------------------------------------------

await test("несуществующий лог - код 2, а не тишина об ошибках", () => {
  const r = runCli([join(dir, "нет-такого.log")]);
  eq(r.code, 2, "код выхода должен отличать «не прочитал» от «ошибок нет»");
  okIf(/не удалось прочитать/.test(r.err), "в stderr должно быть объяснение: " + r.err);
});

await test("каталог вместо лога - код 2 с внятным текстом", () => {
  const d = join(dir, "папка");
  mkdirSync(d, { recursive: true });
  const r = runCli([d]);
  eq(r.code, 2, "код выхода");
  okIf(/каталог/.test(r.err), "сообщение: " + r.err);
});

await test("чистый лог - код 0 и честный вердикт", () => {
  const p = writeLog("clean.log", new Array(50).fill("memory MCP http on 127.0.0.1:4199").join("\n"));
  const r = runCli([p]);
  eq(r.code, 0, "код выхода");
  const report = jsonOf(r).logs[0];
  eq(report.signals.length, 0, "сигналов");
  eq(report.scanned_lines, 50, "строк просмотрено");
  okIf(/чист/i.test(report.verdict), "вердикт: " + report.verdict);
});

await test("пустой лог - код 0, но вердикт не 'чист'", () => {
  const p = writeLog("empty.log", "");
  const r = runCli([p]);
  eq(r.code, 0, "пустой лог прочитан, это не поломка инструмента");
  const report = jsonOf(r).logs[0];
  okIf(!/чист/i.test(report.verdict), "пустой лог не чистый: " + report.verdict);
  okIf(/пуст/i.test(report.verdict), "пустой лог назван пустым: " + report.verdict);
});

await test("без ключей и без логов - код 2 с подсказкой", () => {
  const r = runCli([]);
  eq(r.code, 2, "код выхода");
  okIf(/не указан ни один лог/.test(r.err), "сообщение: " + r.err);
  okIf(/--min-count/.test(r.err), "в подсказке должен быть перечень ключей: " + r.err);
});

await test("неизвестный ключ - код 2, а не тихое игнорирование", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const r = runCli([p, "--что-то-неизвестное"]);
  eq(r.code, 2, "код выхода");
  okIf(/неизвестный ключ/.test(r.err), "сообщение: " + r.err);
});

// --- обрезка хвоста -------------------------------------------------------------

await test("хвост читается целиком и огрызок первой строки отбрасывается", () => {
  // Первая строка окна - хвост настоящей строки: сравнивать её не с чем, и
  // она породила бы подпись, которой в логе нет.
  const big = "A".repeat(400) + "\n" + FAILING_LOG;
  const p = writeLog("big.log", big);
  const r = runCli([p, "--max-bytes", "300"]);
  const report = jsonOf(r).logs[0];
  eq(report.truncated, true, "лог должен быть помечен обрезанным");
  okIf(/только хвост/.test(report.verdict), "вердикт должен предупредить об обрезке: " + report.verdict);
  okIf(!report.signals.some(s => /A{50,}/.test(s.signature)),
    "огрызок первой строки попал в сигналы: " + JSON.stringify(report.signals.map(s => s.signature)));
  eq(report.signals.length, 2, "сигналы из целых строк должны сохраниться");
});

await test("лог меньше окна не помечается обрезанным", () => {
  const p = writeLog("small.log", FAILING_LOG);
  const report = jsonOf(runCli([p, "--max-bytes", "10000000"])).logs[0];
  eq(report.truncated, false, "обрезан");
});

// --- ключи ---------------------------------------------------------------------

await test("--min-count меняет порог закономерности", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const strict = jsonOf(runCli([p, "--min-count", "5"])).logs[0];
  eq(strict.signals.length, 0, "при пороге 5 ни один отказ не повторяется");
  eq(strict.suppressed_groups, 2, "но оба посчитаны и подавлены");
  const loose = jsonOf(runCli([p, "--min-count", "2"])).logs[0];
  eq(loose.signals.length, 2, "при пороге 2 оба в сигналах");
});

await test("--known помечает уже известный сигнал", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const first = jsonOf(runCli([p])).logs[0];
  const fp = first.signals[0].fingerprint;
  const again = jsonOf(runCli([p, "--known", fp])).logs[0];
  eq(again.signals[0].already_known, true, "отпечаток должен быть помечен");
  eq(again.signals.length, first.signals.length, "и не выброшен");
});

await test("--text даёт читаемый вывод без JSON", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const r = runCli([p, "--text"]);
  okIf(/state open timeout/.test(r.out), "подпись должна быть в выводе: " + r.out.slice(0, 200));
  okIf(/\[3x\]/.test(r.out), "счётчик повторов: " + r.out.slice(0, 200));
  let parsed = false;
  try { JSON.parse(r.out); parsed = true; } catch (e) { /* ожидаемо */ }
  okIf(!parsed, "--text не должен выдавать JSON");
});

await test("заготовка помечается своим источником, а не чужим", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const report = jsonOf(runCli([p, "--draft", "--project", "opencode-mobile"])).logs[0];
  eq(report.drafts.length, 2, "заготовок");
  const d = report.drafts[0];
  eq(d.provenance, "own-log", "источник сигнала из своего лога");
  eq(d.project, "opencode-mobile", "проект");
  eq(d.type, "signal", "тип: решения нет, закономерность ещё не понята");
  eq(d.id, report.signals[0].fingerprint, "id заготовки совпадает с отпечатком сигнала");
  okIf(/Причина не установлена/.test(d.content), "заготовка не должна утверждать причину: " + d.content);
  okIf(/3 раз/.test(d.content), "в заготовке должен быть счётчик: " + d.content);
});

await test("id заготовки постоянен между прогонами", () => {
  // Иначе каждый прогон добавлял бы новую запись, и память забивалась бы
  // десятками одинаковых.
  const p = writeLog("fail.log", FAILING_LOG);
  const a = jsonOf(runCli([p, "--draft"])).logs[0].drafts.map(d => d.id);
  const b = jsonOf(runCli([p, "--draft"])).logs[0].drafts.map(d => d.id);
  eq(a, b, "id заготовок");
});

await test("уже известный сигнал заготовки не получает", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const first = jsonOf(runCli([p, "--draft"])).logs[0];
  const fps = first.drafts.map(d => d.id);
  const again = jsonOf(runCli([p, "--draft", "--known", fps.join(",")])).logs[0];
  eq(again.drafts.length, 0, "заготовок для уже известного быть не должно");
  eq(again.signals.length, first.signals.length, "но сам сигнал остаётся виден");
  okIf(again.signals.every(s => s.already_known === true), "все помечены как известные");
});

await test("без --draft заготовок в ответе нет вовсе", () => {
  const p = writeLog("fail.log", FAILING_LOG);
  const report = jsonOf(runCli([p])).logs[0];
  eq(report.drafts, undefined, "заготовки должны появляться только по запросу");
});

await test("несколько логов в одном запуске разбираются по отдельности", () => {
  const good = writeLog("one.log", FAILING_LOG);
  const bad = writeLog("two.log", "clean line\n");
  const report = jsonOf(runCli([good, bad, join(dir, "нет.log")])).logs;
  eq(report.length, 3, "три результата");
  okIf(!report[0].error, "первый лог прочитан");
  eq(report[1].signals.length, 0, "второй лог чист");
  okIf(!!report[2].error, "третий лог не прочитан");
});

// --- итог ---------------------------------------------------------------------

rmSync(dir, { recursive: true, force: true });

console.log("");
if (failures.length) {
  console.log("ПРОВАЛЕНО: " + passed + " ок, " + failures.length + " сломалось");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
}
console.log("зелёное: " + passed + " ок, 0 сломалось");