// Offline tests for the music MCP transport (SSE + POST idle timeout).
//
// WHY THIS FILE EXISTS
// Measured 26.10.2026 on the device: the serve log held 1632 lines
// "mcp transport error server=music" between 21:35 and 04:28 local, one every ~13 s,
// and zero for memory / mobile_tools / moltbook. The GET branch answered with an SSE
// stream that wrote one endpoint frame and then stayed silent forever, and Bun closes
// an idle response after 10 s by default - so opencode saw a transport failure every
// 10-13 s and reconnected. Each retry of a slow tool call (handshake, ticket, state
// socket) opened a new Ynison session, and Ynison answers those with "stay away
// 3600s": the ban was renewed on every probe and could never expire. That is also
// the "unknown caller at 22:54" from the roadmap - nobody called, the transport
// retried. memory.js already had the cure (`srv.timeout(req, 0)` plus a keepalive
// frame); ynison.js did not.
//
// Run:  node app/src/test/js/ynison-sse.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

function slice(from, to) {
  // The asset is checked out with CRLF on Windows, so a multi-line anchor would never match
  // as written. Normalising first keeps the anchors readable and platform-independent.
  const src = readFileSync(SOURCE, "utf8").replace(/\r\n/g, "\n");
  const a = src.indexOf(from);
  const b = src.indexOf(to);
  if (a < 0) throw new Error(`якорь не найден: ${from}`);
  if (b < 0) throw new Error(`якорь не найден: ${to}`);
  if (b <= a) throw new Error(`якорь ${to} идёт раньше ${from}`);
  return src.slice(a, b);
}

const POST_BLOCK = slice('if (req.method === "POST") {', 'if (req.method === "GET") {');
const GET_BLOCK = slice('if (req.method === "GET") {', 'if (req.method === "DELETE")');
const STREAM_BLOCK = slice("let closeStream = () => {};", "return new Response(stream,");

let failed = 0;

async function test(name, fn) {
  try {
    await fn();
    console.log(`  ok   ${name}`);
  } catch (e) {
    failed++;
    console.log(`  СЛОМАНО  ${name}`);
    console.log(`          ${e.message}`);
  }
}

function ok(cond, message) {
  if (!cond) throw new Error(message);
}

function eq(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message}: получено ${JSON.stringify(actual)}, ожидалось ${JSON.stringify(expected)}`);
  }
}

/** Builds the real GET stream with a fake clock and a fake request. */
function makeStream() {
  const sse = new Set();
  const timers = [];
  const cleared = [];
  let aborted = null;
  const req = {
    signal: { addEventListener: (name, fn) => { if (name === "abort") aborted = fn; } },
  };
  const factory = new Function(
    "sse",
    "url",
    "req",
    "TextEncoder",
    "setInterval",
    "clearInterval",
    `${STREAM_BLOCK}\n return stream;`,
  );
  const stream = factory(
    sse,
    { pathname: "/mcp" },
    req,
    TextEncoder,
    (fn, ms) => {
      const token = { fn, ms };
      timers.push(token);
      return token;
    },
    (token) => cleared.push(token),
  );
  return { stream, sse, timers, cleared, abort: () => aborted && aborted() };
}

const decoder = new TextDecoder();

console.log("\nтранспорт music MCP: не рвать соединение по 10-секундному idle timeout");

await test("POST снимает idle timeout: вызов музыки дольше десяти секунд", () => {
  ok(
    POST_BLOCK.includes("server.timeout(req, 0);"),
    "в ветке POST нет server.timeout(req, 0) - Bun оборвёт медленный вызов на 10 с",
  );
});

await test("GET снимает idle timeout: SSE молчит по своей природе", () => {
  ok(
    GET_BLOCK.includes("server.timeout(req, 0);"),
    "в ветке GET нет server.timeout(req, 0) - поток умрёт через 10 с",
  );
});

await test("первый кадр SSE - endpoint, как ждёт MCP-клиент", async () => {
  const { stream } = makeStream();
  const first = await stream.getReader().read();
  ok(first.value !== undefined, "поток закрылся, не отдав ни одного кадра");
  ok(decoder.decode(first.value).startsWith("event: endpoint\ndata: /mcp"), "первый кадр не endpoint");
});

await test("поток шлёт keepalive, а не молчит", async () => {
  const { stream, timers } = makeStream();
  const reader = stream.getReader();
  await reader.read();
  eq(timers.length, 1, "нет ни одного таймера keepalive");
  ok(timers[0].ms <= 15000, `keepalive реже 15 с (${timers[0].ms} мс) - Bun успеет закрыть поток`);
  timers[0].fn();
  const next = await reader.read();
  ok(next.value !== undefined, "после keepalive поток закрылся");
  ok(decoder.decode(next.value).includes("keepalive"), "кадр keepalive пустой или не тот");
});

await test("закрытие потока снимает keepalive и убирает контроллер", async () => {
  const { stream, sse, timers, cleared } = makeStream();
  eq(sse.size, 1, "контроллер не попал в набор SSE");
  await stream.cancel();
  eq(sse.size, 0, "контроллер остался в наборе после закрытия");
  eq(cleared.length, 1, "таймер keepalive не снят - утечка");
  eq(cleared[0], timers[0], "снят не тот таймер");
});

await test("уход клиента (abort) закрывает поток, а не оставляет его висеть", async () => {
  const { sse, cleared, abort } = makeStream();
  abort();
  eq(sse.size, 0, "после abort контроллер остался в наборе");
  eq(cleared.length, 1, "после abort таймер keepalive не снят");
});

console.log("");
if (failed > 0) {
  console.log(`СЛОМАНО тестов: ${failed}`);
  process.exit(1);
}
console.log("все тесты прошли");
