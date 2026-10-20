#!/usr/bin/env node
// Заголовок Idempotency-Key на запись.
//
// Поддержка на стороне сервера НЕ подтверждена: заголовок отправляется, но
// обещать, что повтор не создаст второй комментарий, нельзя. Тесты проверяют
// ровно то, что мы контролируем: ключ детерминирован и уходит в запросе.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}

const { postRaw, deleteRaw } = await import(pathToFileURL(MOLTBOOK_JS).href);

let ok = 0;
let bad = 0;
const failures = [];

function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok++;
  } else {
    bad++;
    failures.push(
      (label || "eq") + ": actual=" + JSON.stringify(actual) + " expected=" + JSON.stringify(expected),
    );
  }
}

function truthy(value, label) {
  if (value) ok++;
  else {
    bad++;
    failures.push("not true: " + label);
  }
}

async function test(name, body) {
  const before = bad;
  try {
    await body();
  } catch (error) {
    bad++;
    failures.push(name + ": threw " + String((error && error.message) || error));
  }
  if (bad === before) console.log("  ok   " + name);
}

const realFetch = globalThis.fetch;
const seen = [];
function stubFetch() {
  seen.length = 0;
  globalThis.fetch = async (url, init) => {
    seen.push({ url, init });
    return new Response(JSON.stringify({ success: true }), { status: 200 });
  };
}

await test("a POST carries the caller's idempotency key", async () => {
  stubFetch();
  await postRaw("K", "/api/v1/posts/p-1/comments", { content: "hi" }, "root-abc");
  eq(seen.length, 1);
  eq(seen[0].init.headers["Idempotency-Key"], "root-abc");
});

await test("a POST without a key sends no such header", async () => {
  stubFetch();
  await postRaw("K", "/api/v1/posts/p-1/comments", { content: "hi" });
  truthy(!("Idempotency-Key" in seen[0].init.headers), "no header invented");
});

await test("a DELETE carries its key too", async () => {
  stubFetch();
  await deleteRaw("K", "/api/v1/comments/c-1", "delete-c-1");
  eq(seen[0].init.headers["Idempotency-Key"], "delete-c-1");
});

await test("a failed request does not lose the attempt", async () => {
  seen.length = 0;
  globalThis.fetch = async (url, init) => {
    seen.push({ url, init });
    throw new Error("boom");
  };
  const result = await deleteRaw("K", "/api/v1/comments/c-1", "delete-c-1");
  eq(result.ok, false, "the failure is reported");
  eq(result.status, 0, "and has no status");
});

globalThis.fetch = realFetch;

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}
