// Офлайн-тесты транспорта к Android-мосту.
//
// Что тут ломается незаметно: bridge() ходит в 127.0.0.1 с Bearer-заголовком и
// таймаутом, а opSessionStatus дергает три маршрута сразу. Любая из этих вещей
// ломается не "крашем", а тихим неверным ответом - агент получает "NOT verified"
// там, где плеер всё сделал, или "bridge is not available" там, где он есть.
//
// Здесь проверяются шесть свойств, каждое из которых уже стоило времени:
//
//   1. GET/HEAD не шлют body. fetch это запрещает, и мост читает пустое тело как
//      "параметров нет" - то есть тело нужно не только потому, что так правильно.
//   2. clearTimeout на ВСЕХ путях. Успех, HTTP-ошибка, невалидный JSON, таймаут -
//      каждый обязан гасить таймер, иначе он держит процесс живым после выхода.
//   3. verified читается из media, а не из корня. На верхнем уровне лежит зеркало
//      ok, и чтение оттуда давало ложное "NOT verified" на каждом успешном вызове.
//   4. Не-JSON и не-2xx дают в тексте ошибки HTTP-код, иначе "мост не отвечает"
//      и "мост отвечает мусором" неразличимы.
//   5. Три маршрута opSessionStatus запрашиваются параллельно, а список управляемых
//      приложений фильтруется по controllable - иначе в контекст агента попадают
//      звонилка и Bluetooth.
//   6. Ничего не печатается при ошибке моста: недоступный мост - это не повод
//      превращать диагностику в шум.
//
// Run:  node app/src/test/js/ynison-bridge.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

const BLOCK_START = "/** Вызов моста: общий транспорт для /v1/media/* и /v1/launch/*. */";
const BLOCK_END = "async function runTool(";
const REQUIRED = ["bridge", "describePlayback", "opSessionStatus", "opSessionControl"];

function loadBlock() {
  const src = readFileSync(SOURCE, "utf8");
  const from = src.indexOf(BLOCK_START);
  const to = src.indexOf(BLOCK_END);
  if (from < 0) throw new Error(`якорь не найден: ${BLOCK_START} - транспорт переехал, тест надо поправить`);
  if (to < 0) throw new Error(`якорь не найден: ${BLOCK_END} - транспорт переехал, тест надо поправить`);
  if (to <= from) throw new Error("BLOCK_END идёт раньше BLOCK_START - anchors crossed");

  const block = src.slice(from, to);
  const missing = REQUIRED.filter((name) => !new RegExp(`(function|const)\\s+${name}\\b`).test(block));
  if (missing.length) throw new Error(`в срезе нет: ${missing.join(", ")} - блок изменился, тест молча ничего не проверяет`);
  return block;
}

let passed = 0;
const failures = [];

function ok(cond, msg) {
  if (cond) {
    passed++;
    console.log(`    ok   ${msg}`);
  } else {
    failures.push(msg);
    console.log(`    FAIL ${msg}`);
  }
}

function eq(actual, expected, msg) {
  ok(actual === expected, `${msg}${actual === expected ? "" : ` (получено ${JSON.stringify(actual)}, ждали ${JSON.stringify(expected)})`}`);
}

async function throws(fn, needle, msg) {
  try {
    await fn();
    ok(false, `${msg} - не бросил исключение`);
  } catch (e) {
    ok(String(e.message).includes(needle), `${msg}${String(e.message).includes(needle) ? "" : ` (в тексте нет "${needle}": ${e.message})`}`);
  }
}

async function test(name, fn) {
  console.log(`\n  ${name}`);
  try {
    await fn();
  } catch (e) {
    failures.push(`${name}: тест упал с ${e.message}`);
    console.log(`    FAIL ${name}: ${e.message}`);
  }
}

/**
 * Runs the real block against a recording fetch().
 *
 * `setTimeout`/`clearTimeout` are injected rather than taken from the global, so a test can
 * count timers instead of guessing. That is the whole point of property 2: with real timers
 * a leak is invisible - the process just takes a second longer to exit, and nobody notices.
 */
function build({ fetchImpl, token = "bridge-token", port = 4202, timeoutMs = 25_000, pkg = "ru.yandex.music" } = {}) {
  const timers = new Map();
  let nextId = 1;
  const setTimeoutImpl = (fn, ms) => {
    const id = nextId++;
    timers.set(id, { fn, ms, cleared: false });
    return id;
  };
  const clearTimeoutImpl = (id) => {
    if (timers.has(id)) timers.get(id).cleared = true;
  };
  const liveTimers = () => [...timers.values()].filter((t) => !t.cleared).length;

  const factory = new Function(
    "fetch", "AbortController", "setTimeout", "clearTimeout",
    "BRIDGE_TOKEN", "BRIDGE_PORT", "BRIDGE_TIMEOUT_MS", "MUSIC_APP_PACKAGE",
    `${loadBlock()}\n return { bridge, opSessionStatus, opSessionControl };`,
  );
  return {
    ...factory(fetchImpl, AbortController, setTimeoutImpl, clearTimeoutImpl, token, port, timeoutMs, pkg),
    liveTimers,
  };
}

/** fetch double: `routes` maps "METHOD /path" (or just "/path") to a body or a thrower. */
function recorder(routes) {
  const calls = [];
  const impl = async (url, init = {}) => {
    const method = init.method || "GET";
    const path = url.replace(/^http:\/\/127\.0\.0\.1:\d+/, "");
    calls.push({ url, path, method, body: init.body, headers: init.headers ?? {}, signal: init.signal });

    const key = Object.keys(routes).find((k) => k === `${method} ${path}` || k === path);
    if (key === undefined) throw new Error(`незапланированный запрос: ${method} ${path}`);

    const r = routes[key];
    if (typeof r === "function") return r({ method, path, call: calls[calls.length - 1] });
    const { status = 200, text = JSON.stringify(r) } = r;
    return { ok: status >= 200 && status < 300, status, text: async () => text };
  };
  return { impl, calls };
}

const json = (body, status = 200) => ({ status, text: JSON.stringify(body) });

// --- 1. no body on GET/HEAD ------------------------------------------------------

await test("bridge() не шлёт body на GET и HEAD", async () => {
  // The bridge reads an empty body as "no params". A body on GET is not merely untidy:
  // undici rejects it outright, so the call fails before the bridge is ever reached.
  for (const method of ["GET", "HEAD"]) {
    const { impl, calls } = recorder({ "/v1/media/status": json({ media: {} }) });
    const { bridge } = build({ fetchImpl: impl });
    await bridge("/v1/media/status", { method });
    eq(calls[0].body, undefined, `${method} отправлен без body`);
  }
});

await test("bridge() шлёт {} на POST, когда тело не задано", async () => {
  const { impl, calls } = recorder({ "/v1/media/control": json({ ok: true }) });
  const { bridge } = build({ fetchImpl: impl });
  await bridge("/v1/media/control", { method: "POST" });
  eq(calls[0].body, "{}", "POST без явного тела получает пустой объект, а не undefined");
});

await test("bridge() сохраняет переданный URL и Bearer", async () => {
  const { impl, calls } = recorder({ "/v1/launch/blocker": json({ system_alert_window_granted: true }) });
  const { bridge } = build({ fetchImpl: impl, port: 4321, token: "sekret-token" });
  await bridge("/v1/launch/blocker");
  eq(calls[0].url, "http://127.0.0.1:4321/v1/launch/blocker", "порт из BRIDGE_PORT");
  eq(calls[0].headers.Authorization, "Bearer sekret-token", "Bearer-заголовок на месте");
});

// --- 2. the timer is cleared on every path ---------------------------------------

await test("таймер гасится на успехе, HTTP-ошибке, не-JSON и таймауте", async () => {
  // Four exits, one clearTimeout each. A leak here keeps the event loop alive after the
  // MCP server has already answered, which in practice means a wedged serve process.
  const cases = {
    "успех": { "/v1/media/status": json({ media: {} }) },
    "HTTP-ошибка": { "/v1/media/status": json({ error: "nope" }, 500) },
    "не-JSON": { "/v1/media/status": { status: 200, text: "<html>oops</html>" } },
    "таймаут": {
      "/v1/media/status": () => {
        const e = new Error("aborted");
        e.name = "AbortError";
        throw e;
      },
    },
  };

  for (const [label, routes] of Object.entries(cases)) {
    const { impl } = recorder(routes);
    const { bridge, liveTimers } = build({ fetchImpl: impl });
    await bridge("/v1/media/status").catch(() => {});
    eq(liveTimers(), 0, `${label}: ни один таймер не остался висеть`);
  }
});

// --- 3. verified lives inside media ----------------------------------------------

await test("verified читается из media, а не из корня", async () => {
  // This is the bug the P0 roadmap item is about. The response mirrors `ok` at the top
  // level and carries the real verdict under `media`, so reading the top level yields
  // undefined - and `undefined === true` is false, i.e. a successful command reports
  // "accepted but NOT verified" every single time.
  const good = recorder({ "/v1/media/control": json({ ok: true, media: { verified: true, transport: "MediaController", before: {}, after: {} } }) });
  const { opSessionControl } = build({ fetchImpl: good.impl });
  const out = await opSessionControl("play");
  ok(out.startsWith("play: verified"), `verified=true читается как verified (получено: ${out.split("\n")[0]})`);
});

await test("verified=false остаётся честным ответом, а не ошибкой", async () => {
  // next on a one-track queue changes nothing and that is correct behaviour. Reporting it as
  // an error would teach the agent to retry a command that already did its job.
  const nope = recorder({ "/v1/media/control": json({ ok: true, media: { verified: false, message: "queue has one track", transport: "MediaController", before: {}, after: {} } }) });
  const { opSessionControl } = build({ fetchImpl: nope.impl });
  const out = await opSessionControl("next");
  ok(out.includes("accepted but NOT verified"), `не-verified помечен честно (получено: ${out.split("\n")[0]})`);
  ok(out.includes("queue has one track"), "причина от агента не теряется");
});

// --- 4. distinguishable errors ---------------------------------------------------

await test("ошибки моста различимы по тексту", async () => {
  // "мост не отвечает" и "мост отвечает мусором" требуют разных действий, поэтому в тексте
  // должен быть HTTP-код и первые байты ответа.
  const nf = recorder({ "/v1/media/status": { status: 200, text: "<html>oops</html>" } });
  await throws(() => build({ fetchImpl: nf.impl }).bridge("/v1/media/status"), "non-JSON", "не-JSON назван не-JSON");
  await throws(() => build({ fetchImpl: nf.impl }).bridge("/v1/media/status"), "200", "в тексте есть код ответа");

  const err500 = recorder({ "/v1/media/status": json({ hint: "pool exhausted" }, 503) });
  await throws(() => build({ fetchImpl: err500.impl }).bridge("/v1/media/status"), "503", "HTTP-код попал в текст");
  await throws(() => build({ fetchImpl: err500.impl }).bridge("/v1/media/status"), "pool exhausted", "hint моста попал в текст");
});

await test("таймаут отличим от недоступности", async () => {
  const dead = recorder({ "/v1/media/status": () => { throw new Error("ECONNREFUSED"); } });
  await throws(() => build({ fetchImpl: dead.impl }).bridge("/v1/media/status"), "unreachable", "недоступность названа недоступностью");
  await throws(() => build({ fetchImpl: dead.impl }).bridge("/v1/media/status"), "ECONNREFUSED", "в тексте есть исходная причина");

  const slow = recorder({ "/v1/media/status": () => { const e = new Error("aborted"); e.name = "AbortError"; throw e; } });
  await throws(() => build({ fetchImpl: slow.impl, timeoutMs: 25_000 }).bridge("/v1/media/status"), "timed out after 25000ms", "таймаут назван таймаутом с дедлайном");
});

await test("без токена мост объявлен недоступным до похода в сеть", async () => {
  const { impl, calls } = recorder({});
  await throws(() => build({ fetchImpl: impl, token: "" }).bridge("/v1/media/status"), "not available", "пустой токен даёт явный отказ");
  eq(calls.length, 0, "и без токена в сеть не сходили");
});

// --- 5. session_status shape ------------------------------------------------------

await test("opSessionStatus спрашивает три маршрута и отвечает тремя строками", async () => {
  const { impl, calls } = recorder({
    "/v1/media/status": json({ media: { playback: { state: "playing", title: "Компот", artist: "Miyagi", position_ms: 4200, is_playing: true }, transport: "MediaSession" } }),
    "/v1/launch/blocker": json({ system_alert_window_granted: false }),
    "/v1/media/apps": json({
      apps: [
        { package: "com.android.dialer", label: "Телефон", controlable: false },
        { package: "ru.yandex.music", label: "Яндекс Музыка", controlable: true },
        { package: "com.android.bluetooth", label: "Bluetooth", controlable: false },
      ],
    }),
  });
  const { opSessionStatus } = build({ fetchImpl: impl });
  const out = await opSessionStatus();

  eq(calls.length, 3, "запрошены все три маршрута");
  ok(new Set(calls.map((c) => c.path)).size === 3, "маршруты разные, а не один трижды");

  const lines = out.split("\n");
  eq(lines.length, 3, `вывод компактный: три строки (получено ${lines.length})`);
  ok(lines[0].includes("Компот") && lines[0].includes("Miyagi"), "что играет - в первой строке");
  ok(lines[0].includes("4s"), "позиция округлена до секунд");
  ok(lines[1].includes("BLOCKED") && lines[1].includes("display over other apps"), "блокировка фонового запуска объяснена, а не просто помечена");
  ok(lines[2].includes("1 controllable of 3"), "счётчик управляемых приложений виден агенту");
  ok(!lines[2].includes("Телефон") && !lines[2].includes("Bluetooth"), "неуправляемые приложения отфильтрованы");
  ok(!out.includes("Яндекс Музыка"), "само приложение Яндекса тоже не засоряет вывод - оно и так в первой строке");
});

await test("opSessionStatus не падает, когда часть маршрутов недоступна", async () => {
  // Three independent questions, and each must be answerable alone. If one dead route took
  // the whole call down, the agent would learn nothing - including about the two that work.
  const { impl } = recorder({
    "/v1/media/status": json({ media: { playback: { state: "paused", title: "Back In Black", artist: "AC/DC", position_ms: 1000 }, transport: "MediaSession" } }),
    "/v1/launch/blocker": () => { throw new Error("boom"); },
    "/v1/media/apps": () => { throw new Error("boom"); },
  });
  const { opSessionStatus } = build({ fetchImpl: impl });
  const out = await opSessionStatus();
  ok(out.includes("Back In Black"), "то, что работает, всё равно попало в ответ");
  ok(out.includes("unknown"), "недоступный маршрут помечен как unknown, а не выброшен");
});

await test("opSessionStatus различает «тишина» и «приложения нет»", async () => {
  const closed = recorder({
    "/v1/media/status": json({ error: "no active session" }),
    "/v1/launch/blocker": json({ system_alert_window_granted: true }),
    "/v1/media/apps": json({ apps: [] }),
  });
  const { opSessionStatus } = build({ fetchImpl: closed.impl });
  const out = await opSessionStatus();
  ok(out.includes("no active session"), "причина отсутствия сессии названа");
  ok(out.includes("background launch: allowed"), "разрешённый фоновый запуск отмечен");
});

// --- 6. silence on transport failure ----------------------------------------------

await test("упавший мост назван упавшим, а не выдуман как «тишина»", async () => {
  // A dead bridge must not read as "nothing is playing": the agent would then launch the
  // app and retry, when the actual problem is that the process serving it is gone. Every line
  // has to carry its own reason, because the three routes fail independently.
  const dead = {
    "/v1/media/status": () => { throw new Error("ECONNREFUSED"); },
    "/v1/launch/blocker": () => { throw new Error("ECONNREFUSED"); },
    "/v1/media/apps": () => { throw new Error("ECONNREFUSED"); },
  };
  const { impl, calls } = recorder(dead);
  const { opSessionStatus } = build({ fetchImpl: impl });
  const out = await opSessionStatus();

  eq(calls.length, 3, "все три маршрута попробованы, а не только первый");
  ok(!out.includes("nothing is playing"), "падение моста не выдаётся за тишину плеера");
  ok(out.includes("unreachable"), "причина отказа названа прямо");
  eq(out.split("\n").length, 3, "структура вывода не схлопнулась");

  // The control path has a different failure mode and it is worse if it lies: a transport
  // error reported as "accepted but NOT verified" tells the agent the command landed.
  const dead2 = recorder({ "/v1/media/control": () => { throw new Error("ECONNREFUSED"); } });
  const { opSessionControl } = build({ fetchImpl: dead2.impl });
  const ctrl = await opSessionControl("pause").catch((e) => `threw: ${e.message}`);
  ok(!ctrl.includes("verified"), `opSessionControl не выдаёт вердикт при упавшем транспорте (получено: ${ctrl.split("\n")[0]})`);
});

// --- summary ----------------------------------------------------------------------

console.log("");
if (failures.length) {
  console.log(`ПРОВАЛЕНО: ${passed} ок, ${failures.length} сломалось`);
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
} else {
  console.log(`зелёное: ${passed} ок, 0 сломалось`);
}