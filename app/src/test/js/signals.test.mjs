#!/usr/bin/env node
// Проверки извлечения собственных ошибок из лога.
//
// Что тут важно: модуль не имеет права молчать там, где молчание читается как
// «всё хорошо». Поэтому половина проверок - про то, что результат отличает
// «сигналов нет» от «никто не смотрел».

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SIGNALS_PATH = process.env.SIGNALS_JS_PATH ||
  path.resolve(HERE, "../../main/assets/harness/signals.js");

const { extractSignals, fingerprint } = await import(pathToFileURL(SIGNALS_PATH).href);

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

const eq = (actual, expected, what) =>
  assert.deepStrictEqual(actual, expected, what);

function okIf(condition, message) {
  if (!condition) throw new Error(message);
}

// --- повторяющийся отказ ------------------------------------------------------

await test("одинаковые строки собираются в один сигнал", () => {
  const log = [
    "[mcp] memory started on 127.0.0.1:4199",
    "[ynison] attempt 1 failed: state open timeout",
    "[ynison] attempt 2 failed: state open timeout",
    "[ynison] attempt 3 failed: state open timeout",
  ];
  const r = extractSignals(log);
  eq(r.error_lines, 3, "строк с признаками ошибки");
  eq(r.signals.length, 1, "сигналов");
  eq(r.signals[0].count, 3, "сколько раз повторилось");
});

await test("номер попытки не делает каждый отказ отдельным", () => {
  // Регрессия на враньё умолчанием: если различать «attempt 1» и «attempt 3»,
  // каждая попытка станет единичным сигналом, не дотянет до порога, и
  // закономерность исчезнет из выдачи целиком.
  const log = [];
  for (let i = 1; i <= 9; i++) log.push("[ynison] attempt " + i + " failed: state open timeout");
  const r = extractSignals(log);
  eq(r.signals.length, 1, "один сигнал на девять попыток");
  eq(r.signals[0].count, 9, "все девять попыток в одном сигнале");
});

await test("длительности и rid не мешают опознать один и тот же отказ", () => {
  const log = [
    "[ynison] attempt 1 failed: not online in ~36s rid=81fe1388b443b55c",
    "[ynison] attempt 2 failed: not online in ~72s rid=93aa20bb11c2ee10",
  ];
  const r = extractSignals(log);
  eq(r.signals.length, 1, "сигналов");
  eq(r.signals[0].count, 2, "повторов");
  okIf(!/81fe1388/.test(r.signals[0].signature), "в подписи остался случайный rid");
});

await test("конверт с JSON сводится к его message", () => {
  const log = [
    '[ynison] state frame keys: error {"error":{"grpc_code":3,"http_code":400,' +
      '"message":"Empty playable list is restricted"},"rid":"ab12cd34"}',
    '[ynison] state frame keys: error {"error":{"grpc_code":3,"http_code":400,' +
      '"message":"Empty playable list is restricted"},"rid":"ff99aa11"}',
  ];
  const r = extractSignals(log);
  eq(r.signals.length, 1, "сигналов");
  okIf(/Empty playable list is restricted/.test(r.signals[0].signature),
    "подпись потеряла причину: " + r.signals[0].signature);
  okIf(!/ab12cd34/.test(r.signals[0].signature), "в подписи остался rid");
});

await test("разные причины не сливаются в одну", () => {
  const log = [
    "[ynison] open attempt 0 failed: state open timeout",
    "[ynison] open attempt 0 failed: state open timeout",
    "[ynison] open attempt 0 failed: redirect timeout",
    "[ynison] open attempt 0 failed: redirect timeout",
  ];
  const r = extractSignals(log);
  eq(r.signals.length, 2, "две разные причины остались двумя сигналами");
});

// --- порог и тишина -----------------------------------------------------------

await test("единичный отказ не становится сигналом, но виден в счётчиках", () => {
  const log = ["[a] ok", "[b] failed once: state open timeout", "[c] ok"];
  const r = extractSignals(log);
  eq(r.signals.length, 0, "сигналов");
  eq(r.error_lines, 1, "но строка-кандидат посчитана");
  eq(r.suppressed_groups, 1, "и попала в подавленные");
  eq(r.dropped, 0, "и не потеряна по дороге");
});

await test("чистый лог отвечает явно, а не молчит", () => {
  // Главная проверка анти-вранья: 9000 строк без ошибок и пустой файл -
  // это разные ответы, и агент обязан их различать.
  const clean = new Array(9000).fill("[mcp] memory MCP http on 127.0.0.1:4199");
  const r = extractSignals(clean);
  eq(r.signals.length, 0, "сигналов");
  eq(r.scanned_lines, 9000, "сколько строк просмотрено");
  eq(r.error_lines, 0, "строк с ошибками");
  okIf(/чист/i.test(r.verdict) && /9000/.test(r.verdict), "вердикт должен называть объём: " + r.verdict);

  const empty = extractSignals([]);
  okIf(!/чист/i.test(empty.verdict), "пустой файл не должен объявляться чистым логом: " + empty.verdict);
  eq(empty.scanned_lines, 0, "пустой файл: строк нет");
});

await test("ошибки без повтора не объявляются чистым логом", () => {
  const r = extractSignals(["[x] failed: something", "[y] ok"]);
  okIf(!/чист/i.test(r.verdict), "одна ошибка - это не чистый лог: " + r.verdict);
  okIf(/1/.test(r.verdict), "вердикт должен называть число строк-кандидатов: " + r.verdict);
});

// --- стабильность -------------------------------------------------------------

await test("порядок выдачи не прыгает между запусками", () => {
  // c повторяется трижды, a дважды: проверка «чаще - значит важнее» должна
  // иметь предмет. При равном счёте решает более ранняя встреча.
  const log = [
    "[a] failed: state open timeout",
    "[b] failed: state open timeout",
    "[c] failed: redirect timeout",
    "[c] failed: redirect timeout",
    "[c] failed: redirect timeout",
    "[a] failed: state open timeout",
  ];
  const first = extractSignals(log);
  const second = extractSignals(log);
  eq(first.signals.map(s => s.fingerprint), second.signals.map(s => s.fingerprint), "порядок");
  eq(first.signals[0].signature, "[c] failed: redirect timeout", "самый частый первым");
  eq(first.signals[1].signature, "[a] failed: state open timeout", "второй по частоте");
});

await test("цифры в причине сливаются, но доказательство остаётся настоящим", () => {
  // Известная и осознанная плата за группировку: цифры маскируются, иначе
  // «attempt 1» и «attempt 3» перестают быть одним отказом. Плата в том, что
  // разные коды сливаются в один сигнал. Поэтому настоящая строка обязана
  // сохраняться в example - иначе агент не увидит, какой именно код.
  const log = [];
  for (const code of [400, 404, 400, 404]) log.push("[api] failed: http error " + code);
  const r = extractSignals(log);
  eq(r.signals.length, 1, "сигналов");
  eq(r.signals[0].count, 4, "повторов");
  okIf(/http error N/.test(r.signals[0].signature),
    "подпись должна быть обезличенной: " + r.signals[0].signature);
  okIf(/http error 400/.test(r.signals[0].example),
    "в доказательстве цифры настоящие: " + r.signals[0].example);
});

await test("лимит сигналов объявляет, что было отброшено", () => {
  // Различители - буквы, а не цифры: иначе все шесть отказов слились бы в
  // один сигнал (см. тест выше про маскирование цифр).
  const log = [];
  for (let i = 0; i < 6; i++) for (let n = 0; n <= i; n++) log.push("[svc-" + "abcdef"[i] + "] failed: cause " + "abcdef"[i]);
  const r = extractSignals(log, { maxSignals: 2 });
  eq(r.signals.length, 2, "отдано по лимиту");
  // Шесть групп по счётчикам 1..6, порог 2 отсекает «a» - остаётся пять,
  // из них показываются два.
  eq(r.dropped, 3, "и сказано, сколько не показано");
});

await test("уже известный отпечаток помечен, но не выброшен", () => {
  const log = ["[a] failed: state open timeout", "[a] failed: state open timeout"];
  const known = fingerprint("[a] failed: state open timeout");
  const r = extractSignals(log, { known: [known] });
  eq(r.signals.length, 1, "сигнал на месте");
  eq(r.signals[0].already_known, true, "помечен как известный");
});

// --- живой лог ----------------------------------------------------------------

await test("настоящий кусок лога устройства даёт тот же отказ, что и глазами", () => {
  // Выдержка из files/opencode.log устройства. Задача теста - не «найти хоть
  // что-то», а совпасть с тем, что видно при чтении глазами: Yandex Music не
  // выходит в сеть, 10 раз.
  const fixture = [
    '[ynison] open attempt 0 failed: state open timeout',
    '[ynison] attempt 1 failed: Yandex Music was started but did not come online in ~36s (signed out, or waiting on a first-run screen?)',
    '[ynison] attempt 2 failed: Yandex Music was started but did not come online in ~36s (signed out, or waiting on a first-run screen?)',
    '[ynison] attempt 3 failed: Yandex Music was started but did not come online in ~36s (signed out, or waiting on a first-run screen?)',
    '[ynison] open attempt 1 failed: redirect timeout',
    '[ynison] rx 4096B 817e43aa7b0a20202270',
    '[ynison] state frame keys: error {"error":{"details":{"ynison-error-code":"4a5f22","ynison-backoff-millis":"0:0:1:2"},"grpc_code":3,"http_code":400,"http_status":"Bad Request","message":"Empty playable list is restricted"},"rid":"4bebd3c7"}',
    '[ynison] open attempt 2 failed: state open timeout',
    '[ynison] announced server state back',
    'opencode server listening on http://127.0.0.1:4096',
  ];
  const r = extractSignals(fixture, { minCount: 2 });
  eq(r.error_lines, 7, "строк-кандидатов");
  const top = r.signals[0];
  okIf(/Yandex Music/.test(top.signature), "самый частый отказ - не запуск Yandex: " + top.signature);
  eq(top.count, 3, "три попытки в одном сигнале");
  // «redirect timeout» и «Empty playable list» встречаются по одному разу -
  // это не закономерность, и в сигналы они попасть не должны.
  eq(r.signals.length, 2, "сигналов");
  eq(r.suppressed_groups, 2, "единичных групп");
  okIf(!r.signals.some(s => /announced server state back/.test(s.signature)),
    "нормальная строка не должна попасть в сигналы");
  okIf(!r.signals.some(s => /rx 4096B/.test(s.signature)),
    "hex-дамп не должен попасть в сигналы");
});

// --- границы ------------------------------------------------------------------

await test("на входе не массив - падаем сразу, а не молча", () => {
  let threw = null;
  try { extractSignals("не массив"); } catch (e) { threw = e; }
  okIf(threw, "extractSignals принял строку вместо массива");
  okIf(/массив/i.test(threw.message), "сообщение должно объяснять, что не так: " + threw.message);
});

await test("пустые строки и undefined не ломают разбор", () => {
  const r = extractSignals(["", null, undefined, "  ", "[a] failed: boom", "[a] failed: boom"]);
  eq(r.signals.length, 1, "сигналов");
  eq(r.signals[0].count, 2, "повторов");
  eq(r.error_lines, 2, "кандидатов");
});

// --- итог ---------------------------------------------------------------------

console.log("");
if (failures.length) {
  console.log("ПРОВАЛЕНО: " + passed + " ок, " + failures.length + " сломалось");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
}
console.log("зелёное: " + passed + " ок, 0 сломалось");