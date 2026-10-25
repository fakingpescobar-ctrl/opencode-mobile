// Offline tests for what happens when Ynison bans the device id.
//
// WHY THIS FILE EXISTS
// A ban arrives as a payload, not as a closed socket:
//
//   {"ynison-error-code":"400090001","ynison-go-away-for-seconds":"3600"}
//
// Two failure modes follow from not handling it, and both are silent:
//
// 1. The payload has no host/session_id, so the pre-existing check reports
//    "ynison refused the token". An agent believes the token expired and re-logs-in a token
//    that is perfectly valid, which changes nothing and costs a full OAuth round trip.
// 2. The open loop retries three times at 500ms. Every retry is another session from the
//    device id currently being penalised, so retrying does not merely fail - it extends the
//    ban that is already in progress.
//
// So the tests here pin three things: the ban is recognised, it is not misreported as an auth
// problem, and it is attempted exactly once.
//
// Run:  node app/src/test/js/ynison-goaway.test.mjs
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

// goAwayError sits above openSessionOnce; the retry loop sits below it. Both are needed to
// test the decision, so both are pulled out and stitched together here. The slice starts at
// errorDetail because goAwayError reads the code through it, and the deadline helpers
// (throwIfBanned / throwIfGoAwayFrame) live in between and are part of the same decision.
const BAN_BLOCK = slice("function errorDetail(", "async function openSessionOnce(seed = null)");
const RETRY_BLOCK = slice("const PERMANENT_OPEN_ERRORS", "// ---- the handoff ---");

/** Runs the real goAwayError and openSession against a scripted openSessionOnce. */
function load({ failWith, attempts = 3 }) {
  let opens = 0;
  const openSessionOnce = async () => {
    opens++;
    if (failWith) throw failWith(opens);
    return { ws: {}, frames: [] };
  };
  const waits = [];
  const wait = async (ms) => { waits.push(ms); };
  const trace = () => {};

  const factory = new Function(
    "openSessionOnce", "wait", "trace", "OPEN_ATTEMPTS",
    `${BAN_BLOCK}\n${RETRY_BLOCK}\n return { openSession, goAwayError, throwIfBanned, throwIfGoAwayFrame };`,
  );
  return { api: factory(openSessionOnce, wait, trace, attempts), opens: () => opens, waits };
}

const GO_AWAY = { "ynison-error-code": "400090001", "ynison-go-away-for-seconds": "3600" };

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    // The await is load-bearing: without it the catch runs before the async body has.
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
  if (actual !== expected) throw new Error(`${what}\n         ожидалось: ${JSON.stringify(expected)}\n         получено:  ${JSON.stringify(actual)}`);
}

console.log("\ngo-away 400090001: распознавание и решение ретраить");

await test("обычный ответ без кода - это не бан", () => {
  const { api } = load({ failWith: null });
  eq(api.goAwayError({ host: "h", session_id: 1 }), null, "нормальный redirect принят за бан");
  eq(api.goAwayError(null), null, "null принят за бан");
  eq(api.goAwayError({}), null, "пустой объект принят за бан");
});

await test("чужой код ошибки - это не бан", () => {
  const { api } = load({ failWith: null });
  eq(api.goAwayError({ "ynison-error-code": "400010001" }), null, "чужой код принят за бан");
});

await test("бан помечен permanent и несёт секунды", () => {
  const { api } = load({ failWith: null });
  const e = api.goAwayError(GO_AWAY);
  ok(e !== null, "бан не распознан");
  eq(e.permanent, true, "бан не помечен permanent");
  eq(e.goAwaySeconds, 3600, "секунды не разобраны");
  ok(String(e.message).includes("3600"), `в тексте нет интервала: ${e && e.message}`);
});

await test("бан НЕ выглядит как проблема токена", () => {
  // This is the point of the whole thing. If the message reads like an auth failure, an agent
  // re-logs-in a working token and the ban survives the round trip untouched.
  const { api } = load({ failWith: null });
  const m = String(api.goAwayError(GO_AWAY).message);
  // Claims that would send an agent to the OAuth screen. Note "re-login" is not in this list:
  // what matters is the claim, not the word - the message is expected to say "do not re-login".
  for (const forbidden of ["refused the token", "is not set", "token expired", "re-authenticate"]) {
    ok(!m.toLowerCase().includes(forbidden), `в тексте бана есть «${forbidden}», агент уйдёт переавторизовывать: ${m}`);
  }
  ok(/token is valid/i.test(m), `в тексте нет прямого «токен в порядке»: ${m}`);
  ok(/do not re-login/i.test(m), `в тексте нет прямого запрета переавторизации: ${m}`);
});

await test("openSession делает ОДНУ попытку на бан и не ретраит", () => {
  const { api, opens, waits } = load({ failWith: () => api.goAwayError(GO_AWAY) });
  return api.openSession().then(
    () => { throw new Error("ожидался выброс, а openSession вернулся"); },
    (e) => {
      eq(opens(), 1, "бан отретраен - это добавляет соединений, которые его и вызвали");
      eq(waits.length, 0, "перед баном была пауза - её быть не должно");
      ok(String(e.message).includes("400090001"), "потерялся исходный код бана");
    },
  );
});

await test("обычная сетевая ошибка по-прежнему ретраится", () => {
  // The guard must not disable retries generally - that would turn a blip into a dead session.
  const { api, opens } = load({ failWith: () => new Error("state open timeout") });
  return api.openSession().then(
    () => { throw new Error("ожидался выброс"); },
    (e) => {
      eq(opens(), 3, "сетевая ошибка перестала ретраиться - правка слишком широкая");
      eq(String(e.message), "state open timeout", "подменили исходную ошибку");
    },
  );
});

await test("ошибка токена - одна попытка, как и раньше", () => {
  const { api, opens } = load({ failWith: () => new Error("ynison refused the token (no host/session in redirect)") });
  return api.openSession().then(
    () => { throw new Error("ожидался выброс"); },
    () => { eq(opens(), 1, "ошибку токена начали ретраить"); },
  );
});

console.log("\nвложенная форма 503: код и интервал под error.details");

// The shape the live server sends now (CPH2747, 25.10.2026). Both the code and the interval
// are one level deeper than the flat form, which is exactly why it went unnoticed: the frame
// had no top-level "ynison-error-code", so it was kept as an ordinary state frame and the call
// failed 18s later as "no player_state after handshake".
const NESTED_503 = {
  error: {
    details: { "ynison-error-code": "500140000", "ynison-go-away-for-seconds": "3600" },
    grpc_code: 14,
    http_code: 503,
    http_status: "Service Unavailable",
    message: "Ynison service is temporary unavailable.",
  },
};

await test("вложенный 503 распознан как бан, а не как обычный кадр", () => {
  const { api } = load({ failWith: null });
  const e = api.goAwayError(NESTED_503);
  ok(e !== null, "вложенная форма не распознана - вызов снова умрёт на no player_state");
  eq(e.permanent, true, "вложенный бан ретраится, хотя сервер просил подождать");
  eq(e.goAwaySeconds, 3600, "секунды из error.details не разобраны");
  ok(String(e.message).includes("3600"), `в тексте нет интервала: ${e.message}`);
  ok(String(e.message).includes("500140000"), `в тексте нет кода сервера: ${e.message}`);
  ok(
    String(e.message).includes("Ynison service is temporary unavailable."),
    `в тексте нет причины от сервера: ${e.message}`,
  );
});

await test("вложенная форма тоже не выглядит как проблема токена", () => {
  const { api } = load({ failWith: null });
  const m = String(api.goAwayError(NESTED_503).message);
  for (const forbidden of ["refused the token", "is not set", "token expired", "re-authenticate"]) {
    ok(!m.toLowerCase().includes(forbidden), `в тексте бана есть «${forbidden}»: ${m}`);
  }
  ok(/token is valid/i.test(m), `в тексте нет «токен в порядке»: ${m}`);
});

await test("вложенная ошибка без интервала - не бан, обычная ошибка ретраится", () => {
  const { api, opens } = load({ failWith: () => new Error("state open timeout") });
  const plain503 = { error: { details: { "ynison-error-code": "500140000" }, http_code: 503 } };
  eq(api.goAwayError(plain503), null, "503 без интервала принят за бан и выключил ретраи");
  return api.openSession().then(
    () => { throw new Error("ожидался выброс"); },
    () => { eq(opens(), 3, "обычная 503 перестала ретраиться"); },
  );
});

await test("после бана следующий вызов не открывает сокет", () => {
  // The point of the deadline: a ban is per device id, so every call that connects anyway is
  // another connection from the id being penalised. The clock is a parameter, so this does not
  // wait an hour.
  const { api, opens } = load({ failWith: () => api.goAwayError(NESTED_503) });
  const t0 = Date.now();
  return api.openSession().then(
    () => { throw new Error("ожидался выброс"); },
    (e) => {
      eq(e.goAwaySeconds, 3600, "первый вызов не запомнил интервал");
      let thrown = null;
      try { api.throwIfBanned(t0 + 60_000); } catch (err) { thrown = err; }
      ok(thrown !== null, "внутри интервала сокет снова открылся");
      eq(thrown.permanent, true, "бан внутри интервала снова ретраится");
      ok(String(thrown.message).includes("3540"), `остаток посчитан неверно: ${thrown.message}`);
      // After the interval the ban lifts by itself - a stuck flag would kill music until restart.
      api.throwIfBanned(t0 + 3_700_000);
      eq(opens(), 1, "сокет открывался внутри бана");
    },
  );
});

await test("кадр бана после handshake выбрасывается, а не остаётся состоянием", () => {
  const { api } = load({ failWith: null });
  let closed = false;
  const ws = { close: () => { closed = true; } };
  let thrown = null;
  try { api.throwIfGoAwayFrame([{ player_state: {} }, NESTED_503], ws); } catch (err) { thrown = err; }
  ok(thrown !== null, "кадр бана после handshake не пойман - снова no player_state");
  eq(closed, true, "сокет не закрыт: бан продолжает считаться по живому соединению");
  eq(thrown.goAwaySeconds, 3600, "интервал потерялся на пути через кадры");
});

await test("обычный кадр состояния не принимается за бан", () => {
  const { api } = load({ failWith: null });
  const ws = { close: () => { throw new Error("сокет закрыли на нормальном кадре"); } };
  api.throwIfGoAwayFrame([{ player_state: { status: { paused: false } } }], ws);
});

await test("проверка стоит после handshake, а не только до него", () => {
  // The helper being correct is not enough: the ban frame arrives as the answer to the announce,
  // which happens after the pre-handshake check has already run on an empty list. Pinning the
  // call site is the only way to test it offline - driving openSessionOnce would need a live
  // Ynison socket, and that is exactly the thing that is unavailable when a ban is running.
  const afterHandshake = slice("handshake done", "return { ws, frames }");
  ok(
    afterHandshake.includes("throwIfGoAwayFrame(frames, ws)"),
    "после handshake нет проверки бана - вызов снова умрёт на no player_state",
  );
});

console.log(`\n${failures.length ? "ПРОВАЛЕНО" : "зелёное"}: ${passed} ок, ${failures.length} сломалось\n`);
process.exit(failures.length ? 1 : 0);
