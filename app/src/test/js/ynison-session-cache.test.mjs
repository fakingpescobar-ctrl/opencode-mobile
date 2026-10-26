// Offline tests for the two things that decide whether music survives a second call:
// the session cache (one dial per device id, reused) and the doubling backoff (a retry
// after the announced interval re-arms the ban, so the wait has to grow).
//
// WHY THIS FILE EXISTS
// Measured 26.10.2026: a fresh device id was allowed exactly one session open, and the
// second open a few minutes later collected "stay away 3600s" (ynison 500140000). The
// phone's own id had been banned the same way. So the number of dials is the defect, and
// the fix has two halves that must both hold:
//
//   1. openSession returns the live session instead of dialling again;
//   2. when a ban does arrive, the next attempt is pushed out further than the last one,
//      because retrying exactly at the deadline is what kept the ban alive for a day.
//
// Run:  node app/src/test/js/ynison-session-cache.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

function slice(from, to) {
  const src = readFileSync(SOURCE, "utf8");
  const a = src.indexOf(from);
  const b = src.indexOf(to);
  if (a < 0) throw new Error(`якорь не найден: ${from}`);
  if (b < 0) throw new Error(`якорь не найден: ${to}`);
  if (b <= a) throw new Error(`якорь ${to} идёт раньше ${from}`);
  return src.slice(a, b);
}

// The ban block holds goAwayError / throwIfGoAwayFrame / clearGoAway, the retry block holds
// the cache and openSession. They are one decision, so they are stitched together here.
const BAN_BLOCK = slice("function errorDetail(", "async function openSessionOnce(seed = null)");
const CACHE_BLOCK = slice("const PERMANENT_OPEN_ERRORS", "// ---- the handoff ---");

const NESTED_503 = {
  error: {
    details: { "ynison-error-code": "500140000", "ynison-go-away-for-seconds": "3600" },
    http_code: 503,
    message: "Ynison service is temporary unavailable.",
  },
};

function load({ savedBan = null, failWith = null } = {}) {
  let opens = 0;
  const openSessionOnce = async () => {
    opens++;
    if (failWith) throw failWith(opens);
    return { ws: { closed: false, close() { this.closed = true; } }, frames: [] };
  };
  const waits = [];
  const wait = async (ms) => { waits.push(ms); };
  const trace = () => {};
  const goAwayFile = "/data/files/ynison/ynison-goaway.json";
  const writes = [];
  const readFileSync = (path) => {
    if (path !== goAwayFile || savedBan === null) throw new Error("ENOENT");
    return JSON.stringify(savedBan);
  };
  const writeFileSync = (path, data) => { writes.push(JSON.parse(data)); };

  const factory = new Function(
    "openSessionOnce", "wait", "trace", "OPEN_ATTEMPTS",
    "STATE_CACHE", "readFileSync", "writeFileSync",
    `${BAN_BLOCK}\n${CACHE_BLOCK}\n return { openSession, goAwayError, clearGoAway };`,
  );
  const api = factory(
    openSessionOnce, wait, trace, 3,
    "/data/files/ynison/ynison-state.json", readFileSync, writeFileSync,
  );
  return { api, opens: () => opens, writes };
}

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failures.push({ name, err });
    console.log(`  FAIL ${name}\n         ${err.message}`);
  }
}

function ok(cond, what) {
  if (!cond) throw new Error(what);
}
function eq(actual, expected, what) {
  if (actual !== expected) {
    throw new Error(`${what}\n         ожидалось: ${JSON.stringify(expected)}\n         получено:  ${JSON.stringify(actual)}`);
  }
}

console.log("\nсессия: один заход на устройство, а не на каждый вызов");

await test("второй вызов берёт тот же сокет, а не открывает новый", async () => {
  const { api, opens } = load();
  const first = await api.openSession();
  const second = await api.openSession();
  eq(opens(), 1, "второй вызов снова пошёл на Ynison - это второй заход под тем же device id");
  ok(first === second, "вернулся другой объект сессии");
  ok(first.frames === second.frames, "кадры перестали быть общими - опрос увидит пустоту");
});

await test("два вызова разом - один заход, а не два", async () => {
  // The agent can ask for two things in one batch; two concurrent dials are exactly the
  // pattern that earns the throttle.
  const { api, opens } = load();
  const [a, b] = await Promise.all([api.openSession(), api.openSession()]);
  eq(opens(), 1, "параллельные вызовы открыли два сокета");
  ok(a === b, "вернулись разные сессии");
});

await test("кадры копятся в живой сессии, их видит следующий вызов", async () => {
  const { api } = load();
  const session = await api.openSession();
  session.frames.push({ player_state: { status: { paused: false } } });
  const again = await api.openSession();
  eq(again.frames.length, 1, "кадры потерялись при переиспользовании");
});

await test("сессия старше TTL переоткрывается", async () => {
  const { api, opens } = load();
  const session = await api.openSession();
  session.openedAt = Date.now() - 11 * 60 * 1000;
  await api.openSession();
  eq(opens(), 2, "просроченная сессия выдана снова - сервер её уже мог закрыть");
});

await test("сокет, закрытый сервером, не выдаётся повторно", async () => {
  const { api, opens } = load();
  const session = await api.openSession();
  session.ws.closed = true;
  const next = await api.openSession();
  eq(opens(), 2, "мёртвый сокет отдан как живой");
  ok(next.ws !== session.ws, "вернулся тот же мёртвый сокет");
});

await test("кадр бана на живой сессии ловится до записи", async () => {
  const { api, opens } = load();
  const session = await api.openSession();
  session.frames.push(NESTED_503);
  let thrown = null;
  try { await api.openSession(); } catch (e) { thrown = e; }
  ok(thrown !== null, "бан на живом сокете не распознан - запись уйдёт в пустоту");
  eq(thrown.permanent, true, "бан на живом сокете ретраится");
  eq(opens(), 1, "лишний заход под забаненным id");
});

console.log("\nбэкофф: каждый следующий бан отодвигает попытку дальше");

await test("первый бан - ровно серверный час", async () => {
  const { api, writes } = load();
  const before = Date.now();
  api.goAwayError(NESTED_503);
  eq(writes.length, 1, "дедлайн не сохранён");
  eq(writes[0].attempts, 1, "бан не посчитан");
  const wait = writes[0].until - before;
  ok(wait >= 3_600_000 && wait <= 3_601_000, `первый бан ждёт не час: ${wait} мс`);
});

await test("второй бан (после истёкшего первого) - вдвое дольше", async () => {
  // The file on disk is the memory: a process restart must not reset the count, or every
  // restart would walk back into the hourly re-arm cycle that kept music dead for a day.
  const { api, writes } = load({ savedBan: { until: Date.now() - 1000, attempts: 1 } });
  const before = Date.now();
  api.goAwayError(NESTED_503);
  const wait = writes[0].until - before;
  eq(writes[0].attempts, 2, "счётчик бан-подряд не вырос");
  ok(wait >= 7_200_000 && wait <= 7_201_000, `второй бан не удвоился: ${wait} мс`);
});

await test("третий бан упирается в потолок, а не растёт бесконечно", async () => {
  const { api, writes } = load({ savedBan: { until: Date.now() - 1000, attempts: 6 } });
  const before = Date.now();
  api.goAwayError(NESTED_503);
  const wait = writes[0].until - before;
  ok(wait <= 12 * 60 * 60 * 1000 + 1000, `потолок не работает: ${wait} мс`);
});

await test("два кадра одного захода не считаются двумя банами", async () => {
  // The redirect frame and the state frame can both carry the ban; counting both would
  // double the wait for a single connection.
  const { api, writes } = load();
  const before = Date.now();
  api.goAwayError(NESTED_503);
  api.goAwayError(NESTED_503);
  const last = writes[writes.length - 1];
  eq(last.attempts, 1, "один заход посчитан как два бана");
  ok(last.until - before <= 3_601_000, `дедлайн удвоился на втором кадре одного захода: ${last.until - before} мс`);
});

await test("успешная сессия обнуляет счётчик", async () => {
  const { api, writes } = load({ savedBan: { until: Date.now() - 1000, attempts: 3 } });
  await api.openSession();
  const reset = writes[writes.length - 1];
  eq(reset.attempts, 0, "счётчик бан-подряд не обнулён после успешного захода");
  eq(reset.until, 0, "дедлайн остался в прошлом - следующий бан снова будет часовым");
});

console.log(`\n${failures.length ? "ПРОВАЛЕНО" : "зелёное"}: ${passed} ок, ${failures.length} сломалось\n`);
process.exit(failures.length ? 1 : 0);
