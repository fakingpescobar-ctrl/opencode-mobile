#!/usr/bin/env bun
// signals-cli.js - прочитать свои логи и напечатать повторяющиеся отказы.
//
// Зачем отдельная обёртка: сам signals.js - чистая функция над массивом строк.
// Пока он был один, воспользоваться им можно было только написав скрипт, а
// контур, который нельзя запустить одной командой, не запускается.
//
// Код выхода - часть контракта, а не украшение:
//   0 - лог прочитан, сигналы напечатаны (в том числе когда их нет)
//   2 - лог не прочитан: файла нет, это каталог, не хватило прав
// Различать эти два состоя обязательно. «Сигналов нет» и «лог не открылся»
// при одном коде выхода означали бы, что агент решит: ошибок нет.
//
// Ничего не записывает и никуда не сохраняет. Что именно считать достойным
// памяти - решение агента, а не инструмента: инструмент, который сам решает,
// что запомнить, однажды запомнит ерунду и закрепит её авторитетом.

import { statSync, openSync, readSync, closeSync } from "node:fs";
import path from "node:path";
import { extractSignals } from "./signals.js";

// Хвост, а не весь файл: лог растёт вечно, а свежие отказы в конце. Читать
// 300 МБ ради одной строчки - значит сожрать память телефона.
const DEFAULT_MAX_BYTES = 4 * 1024 * 1024;

const USAGE = "signals-cli.js <лог> [лог...] [--min-count N] [--max N] [--known id,id] [--text] [--max-bytes N]";

function fail(message) {
  console.error("signals-cli: " + message);
  process.exit(2);
}

function parseArgs(argv) {
  const paths = [];
  const options = { minCount: undefined, max: undefined, known: [], text: false, maxBytes: DEFAULT_MAX_BYTES };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--text") options.text = true;
    else if (arg === "--known") options.known = (argv[++i] || "").split(",").filter(Boolean);
    else if (arg === "--min-count") options.minCount = Number(argv[++i]);
    else if (arg === "--max") options.max = Number(argv[++i]);
    else if (arg === "--max-bytes") options.maxBytes = Number(argv[++i]);
    else if (arg.startsWith("-")) fail("неизвестный ключ " + arg + "\n" + USAGE);
    else paths.push(arg);
  }
  if (!paths.length) fail("не указан ни один лог\n" + USAGE);
  return { paths, options };
}

/**
 * Читает хвост файла и возвращает строки.
 *
 * При обрезании первая строка может оказаться огрызком - началом настоящей
 * строки, чья голова осталась за пределом окна. Такой огрызок нельзя
 * сравнивать с целыми строками: он породил бы подпись, которой в логе нет.
 * Поэтому он отбрасывается, а факт обрезания попадает в ответ.
 */
export function readLogTail(logPath, maxBytes) {
  let size;
  try {
    const stat = statSync(logPath);
    if (stat.isDirectory()) return { lines: [], truncated: false, bytes: 0, isDirectory: true };
    size = stat.size;
  } catch (e) {
    return { error: "не удалось прочитать " + logPath + ": " + (e && e.code ? e.code : e.message) };
  }

  const truncated = size > maxBytes;
  const from = truncated ? size - maxBytes : 0;
  const length = size - from;
  const buffer = Buffer.alloc(length);
  const fd = openSync(logPath, "r");
  try {
    readSync(fd, buffer, 0, length, from);
  } finally {
    closeSync(fd);
  }

  let text = buffer.toString("utf8");
  if (truncated) {
    const firstBreak = text.indexOf("\n");
    text = firstBreak === -1 ? "" : text.slice(firstBreak + 1);
  }
  // Хвост "\n" - это конец последней строки, а не ещё одна строка. Без снятия
  // пустой файл выглядел бы как файл из одной строки, и вердикт «лог чист»
  // прошёл бы там, где правильный ответ - «лог пуст».
  const lines = text.split(/\r?\n/);
  if (lines.length && lines[lines.length - 1] === "") lines.pop();
  return { lines, truncated, bytes: length, isDirectory: false };
}

export function analyseLog(logPath, options) {
  const tail = readLogTail(logPath, options.maxBytes);
  if (tail.error) return { path: logPath, error: tail.error };
  if (tail.isDirectory) return { path: logPath, error: logPath + " - это каталог, а не лог" };

  const result = extractSignals(tail.lines, {
    minCount: options.minCount,
    maxSignals: options.max,
    known: options.known,
  });
  // Обрезанный лог показывается иначе: отсутствие отказа в первых мегабайтах
  // ничего не значит, если их не смотрели.
  if (tail.truncated) {
    result.verdict += ` Внимание: лог больше ${options.maxBytes} байт, читался только хвост.`;
  }
  return { path: logPath, bytes: tail.bytes, truncated: tail.truncated, ...result };
}

function printText(results) {
  for (const r of results) {
    if (r.error) { console.log(r.path + ": " + r.error); continue; }
    console.log("== " + r.path + " (" + r.scanned_lines + " строк" +
      (r.truncated ? ", читался хвост " + r.bytes + " байт" : "") + ")");
    console.log(r.verdict);
    for (const s of r.signals) {
      console.log("  [" + s.count + "x] " + s.signature + (s.already_known ? "  (уже известен)" : ""));
      console.log("        строки " + s.first_line + "-" + s.last_line + "  " + s.fingerprint);
      console.log("        " + s.example.slice(0, 200));
    }
    console.log("");
  }
}

const invokedDirectly = process.argv[1] &&
  path.basename(process.argv[1]).startsWith("signals-cli");
if (invokedDirectly) {
  const { paths, options } = parseArgs(process.argv.slice(2));
  const results = paths.map(p => analyseLog(p, options));
  const broken = results.filter(r => r.error);
  // Ошибка идёт и в полезную нагрузку, и в stderr: тот, кто разбирает JSON,
  // увидит её в поле error, а тот, кто смотрит в консоль, - в stderr. Молча
  // упавший лог в полезной нагрузке легко пропустить, а «ошибок нет» из-за
  // непрочитанного файла - худший из возможных выводов.
  // В текстовом режиме ошибка уже напечатана в теле вывода, второй раз её
  // дублировать незачем.
  if (!options.text) for (const r of broken) console.error("signals-cli: " + r.error);
  if (options.text) printText(results);
  else console.log(JSON.stringify({ logs: results }, null, 2));
  // Инструмент отработал - даже если отказов не нашлось. Непрочитанный лог -
  // это его собственная поломка, и она не должна выглядеть как «ошибок нет».
  process.exit(broken.length === results.length ? 2 : 0);
}