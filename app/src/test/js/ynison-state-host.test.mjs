// Offline tests for the address the Ynison state socket connects to.
//
// WHY THIS FILE EXISTS
// Measured 26.10.2026: `music_play_query` hung 97.7s and died as "state open timeout,
// buffered 0B". The redirect socket worked end to end (101, payload, ticket, close); the state
// socket received nothing at all. The cause was the address: the app resolves
// `ynison.music.yandex.ru` (frontend) and passes that IP in as RESOLVED_YNISON_IP, while the
// state socket presents the shard's Host/SNI. The frontend accepts the TLS connection and the
// request and then never answers.
//
// Same Host, path and token from a PC: frontend 77.88.21.101 - 0 bytes in 6s; shard
// 77.88.48.156 - 101 Switching Protocols in 0.16s. So the shard is resolved in JS
// (Bun does resolve names on Android: on the device `dns.lookup(shard)` gives 77.88.48.156).
//
// A silent socket cannot be asserted on, so what is pinned here is the address itself: the
// resolver returns the shard's IPv4, its failure is a named error instead of a wait, and the
// state socket takes its address from the resolver rather than from the app's frontend IP.
//
// Run:  node app/src/test/js/ynison-state-host.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

const SHARD = "k3t3i6qhsbzjthgl.ynison.music.yandex.ru";
const SHARD_IP = "77.88.48.156";

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

const RESOLVE_BLOCK = slice("async function resolveStateHost(", "\n// YNISON_DUMP=<file> records every frame sent");

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

/** Runs the real resolveStateHost with a scripted resolver and captures what it asked for. */
async function run(host, lookup) {
  const lines = [];
  const dns = { lookup };
  const trace = (...a) => lines.push(a.join(" "));
  const factory = new Function("dns", "trace", `${RESOLVE_BLOCK}\n return resolveStateHost;`);
  const resolveStateHost = factory(dns, trace);
  try {
    return { address: await resolveStateHost(host), lines, error: null };
  } catch (e) {
    return { address: null, lines, error: e };
  }
}

console.log("\nадрес state-сокета - адрес шарда, а не фронтенда");

await test("шард резолвится в IPv4 и этот адрес доходит до сокета", async () => {
  let asked = null;
  const { address, error, lines } = await run(SHARD, async (host, options) => {
    asked = { host, options };
    return { address: SHARD_IP, family: 4 };
  });
  eq(error, null, "резолв шарда упал");
  eq(address, SHARD_IP, "адрес шарда не дошёл до сокета");
  eq(asked.host, SHARD, "резолвили не тот хост, который назвал редирект");
  eq(asked.options.family, 4, "не просили IPv4: на устройстве IPv6 не поднимется");
  ok(lines.some((l) => l.includes(SHARD_IP)), "в трассе нет результата резолва - разбор снова будет гаданием");
});

await test("отказ резолва - именованная ошибка, а не молчащий сокет", async () => {
  const { error } = await run(SHARD, async () => {
    const e = new Error("getaddrinfo ENOTFOUND");
    e.code = "ENOTFOUND";
    throw e;
  });
  ok(error, "ошибка потерялась - вызов снова уйдёт в 10-секундный таймаут");
  ok(error.message.includes(SHARD), `в ошибке нет хоста: ${error.message}`);
  ok(error.message.includes("ENOTFOUND"), `в ошибке нет кода резолва: ${error.message}`);
});

await test("адрес state-сокета берётся из резолва, а не из IP приложения", () => {
  const body = slice(
    "const ws = new YnisonWs({\n    path: `/${STATE_SERVICE}`,",
    "const frames = [];",
  );
  ok(
    body.includes("await resolveStateHost(redirect.host)"),
    "state-сокет больше не резолвит шард - вернётся тишина фронтенда",
  );
  ok(
    !body.includes("RESOLVED_YNISON_IP"),
    "state-сокет снова смотрит на IP фронтенда - это ровно те 10 секунд и 0 байт",
  );
});

if (failed) {
  console.log(`\nкрасное: ${failed}`);
  process.exit(1);
}
console.log("\nзелёное: всё ок");
