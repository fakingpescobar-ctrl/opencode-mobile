// Offline tests for how many Ynison sockets one operation costs.
//
// WHY THIS FILE EXISTS
// Ynison hands out an error like this:
//
//   {"ynison-error-code":"400090001","ynison-go-away-for-seconds":"3600"}
//
// and then the PHONE disappears from the device list. Nothing in the code looks like a
// failure: the play call returned, the queue was written, the status call answered. The
// session just stops existing for an hour.
//
// The cause is visible in waitForState: it opened a fresh session on every poll iteration.
// The device id is stable, so one play call produced up to three connections under the same
// id within seconds - one for the command, up to VERIFY_ATTEMPTS for verification. Yandex
// reads that as the same device reconnecting and bans it, and the phone pays for it, since
// the phone is the device that holds the active player role.
//
// Counting sockets is the only part of this that is testable without a phone, so it is what
// gets asserted. The reconnect-throttling policy is a separate concern with its own test.
//
// Run:  node app/src/test/js/ynison-session.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

const BLOCK_START = "async function waitForState(";
const BLOCK_END = "// Retries only what a retry can fix.";

function loadBlock() {
  const src = readFileSync(SOURCE, "utf8");
  const from = src.indexOf(BLOCK_START);
  const to = src.indexOf(BLOCK_END);
  if (from < 0) throw new Error(`якорь не найден: ${BLOCK_START} - функция переехала, тест надо поправить`);
  if (to < 0) throw new Error(`якорь не найден: ${BLOCK_END} - функция переехала, тест надо поправить`);
  if (to <= from) throw new Error("BLOCK_END идёт раньше BLOCK_START - anchors crossed");
  return src.slice(from, to);
}

/**
 * Runs the real waitForState against a fake session and reports how it behaved.
 * `onWait(n)` is called before each poll iteration with the 1-based attempt number, so a
 * test can make a frame appear on the second read the way a real echo arrives late.
 */
async function runWaitForState({ attempts, expect, onWait }) {
  let opens = 0;
  let closes = 0;
  const frames = [];
  const wait = async () => {
    if (onWait) onWait(frames);
  };
  const openSession = async () => {
    opens++;
    return { ws: { close: () => { closes++; } }, frames };
  };
  const readState = (f) => (f.length ? f[f.length - 1].player_state : null);
  const trace = () => {};

  const factory = new Function(
    "openSession", "wait", "readState", "trace", "VERIFY_MS", "VERIFY_ATTEMPTS",
    `${loadBlock()}\n return waitForState;`,
  );
  const waitForState = factory(openSession, wait, readState, trace, 0, attempts);
  // An exception from the caller's predicate is supposed to escape - waitForState closes the
  // socket in a finally and then lets it through. The test needs to see what happened on the
  // way out, so the error is captured rather than swallowed.
  let error = null;
  let result = null;
  try {
    result = await waitForState(expect, attempts);
  } catch (e) {
    error = e;
  }
  return { result, opens, closes, error };
}

const never = () => false;
const always = () => true;
const pushState = (frames) => frames.push({ player_state: { title: "Party Up", index: 0 } });

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    // The await is load-bearing. Without it an async body has not run when the catch is
    // evaluated, so every test would be counted as passed no matter what it did.
    await fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failures.push({ name, err });
    console.log(`  FAIL ${name}\n         ${err.message}`);
  }
}

function eq(actual, expected, what) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${what}\n         ожидалось: ${JSON.stringify(expected)}\n         получено:  ${JSON.stringify(actual)}`);
  }
}
function ok(cond, what) {
  if (!cond) throw new Error(what);
}

console.log("\nwaitForState: сколько сокетов стоит одна операция");

await test("состояние не пришло - ОДИН сокет на весь опрос, а не по одному на попытку", async () => {
  const r = await runWaitForState({ attempts: 3, expect: never });
  eq(r.opens, 1, "каждая попытка открывает своё подключение - это и вызывает бан");
  eq(r.closes, 1, "сокет не закрыт");
  eq(r.result, null, "ожидался null");
});

await test("состояние пришло сразу - сокет тоже один и закрыт", async () => {
  const r = await runWaitForState({ attempts: 3, expect: always, onWait: pushState });
  eq(r.opens, 1, "лишнее подключение");
  eq(r.closes, 1, "утечка сокета");
  ok(r.result !== null, "состояние не прочитано");
});

await test("эхо приходит поздно - второй опрос всё ещё на том же сокете", async () => {
  // The regression this guards: reading frames late is the whole reason the poll exists,
  // and it used to be implemented by reconnecting. A late frame must still be found.
  let seen = 0;
  const r = await runWaitForState({
    attempts: 3,
    expect: (s) => s?.title === "Party Up",
    onWait: (frames) => { seen++; if (seen === 2) pushState(frames); },
  });
  eq(r.opens, 1, "на втором опросе открыт новый сокет");
  eq(r.closes, 1, "сокет не закрыт");
  ok(r.result !== null, "позднее эхо не нашлось - семантика опроса сломана");
});

await test("исключение в проверке не оставляет сокет висеть", async () => {
  const r = await runWaitForState({
    attempts: 2,
    expect: () => { throw new Error("проверка упала"); },
    onWait: pushState,
  });
  eq(r.opens, 1, "лишнее подключение");
  eq(r.closes, 1, "сокет остался открытым при исключении");
  ok(r.error !== null, "исключение проглочено - вызывающий не узнает, что проверка не прошла");
});

console.log(`\n${failures.length ? "ПРОВАЛЕНО" : "зелёное"}: ${passed} ок, ${failures.length} сломалось\n`);
process.exit(failures.length ? 1 : 0);
