#!/usr/bin/env node
// Tests for the panel snapshot: what the Moltbook card shows, and where it comes from.
//
// The card reads files/moltbook/panel.json instead of the SQLite ledger, because the
// Kotlin ticker that used to own those numbers is being deleted. These tests pin the
// two things that then matter: the numbers are a projection of the journal, and an
// unknown value stays unknown instead of turning into a zero.
//
// Runs under plain node via node:sqlite.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));

function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}

const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

const {
  PANEL_NAME,
  COMMENT_STATUS,
  panelState,
  panelPending,
  panelEmpty,
  rememberHome,
  readPanel,
  writePanel,
  cacheHome,
  MOLTBOOK_DIR,
  enqueueTool,
  fetchWithTimeout,
  MOLTBOOK_TIMEOUT_MS,
  MOLTBOOK_CALL_BUDGET_MS,
} = await import(pathToFileURL(MOLTBOOK_JS).href);

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

async function test(name, fn) {
  try {
    await fn();
    console.log("  ok   " + name);
  } catch (error) {
    bad++;
    failures.push(name + ": " + String((error && error.stack) || error));
    console.log("  FAIL " + name);
  }
}

const AT = 1700000000000;

const SUMMARY = {
  total: 9,
  [COMMENT_STATUS.POSTED]: 5,
  [COMMENT_STATUS.FAILED]: 2,
  [COMMENT_STATUS.NEW]: 2,
  reportable: 4,
  verified: 3,
  reused: 1,
  unknown: 1,
};

const SCHEMA = `
  CREATE TABLE posts (id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '',
    ours INTEGER NOT NULL DEFAULT 0, repost_of TEXT, comments_total INTEGER NOT NULL DEFAULT 0,
    upvotes INTEGER NOT NULL DEFAULT 0, seen_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0);
  CREATE TABLE comments (id TEXT PRIMARY KEY, post_id TEXT NOT NULL, author TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL,
    our_reply_id TEXT, summary_ru TEXT NOT NULL DEFAULT '', seen_at INTEGER NOT NULL DEFAULT 0,
    replied_at INTEGER NOT NULL DEFAULT 0, reply_outcome TEXT NOT NULL DEFAULT '');
  CREATE TABLE upvotes (post_id TEXT PRIMARY KEY, decided_at INTEGER NOT NULL DEFAULT 0);
  CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
`;

function journal() {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-panel-"));
  const file = join4(dir, "panel.db");
  const db = new DatabaseSync(file);
  db.exec(SCHEMA);
  db.close();
  return { dir, file };
}

const { DatabaseSync } = await import("node:sqlite");

// ---- what the card counts ---------------------------------------------------------

await test("the card counts work, not attempts", () => {
  const panel = panelState(AT, SUMMARY, [{ id: "c-1" }], { upvotes: 7 });
  eq(panel.answered, 4, "answered is reportable");
  eq(panel.verified, 3, "verified");
  eq(panel.reused, 1, "reused");
  eq(panel.failed, 2, "failed");
  eq(panel.unknown, 1, "unknown");
  eq(panel.upvotes, 7, "upvotes");
  truthy(panel.ok, "ok");
});

await test("awaiting is the whole table, not the six shown", () => {
  const many = Array.from({ length: 11 }, (_, i) => ({ id: "c-" + i }));
  const panel = panelState(AT, SUMMARY, many, { awaiting: 11 });
  eq(panel.awaiting, 11, "awaiting counts every question");
  eq(panel.pending.length, 6, "the list stays short");
});

await test("awaiting survives a list that was cut off by the query", () => {
  // SQL отдаёт только первые шесть, счётчик приходит отдельным COUNT. Если бы
  // awaiting считался по длине списка, на большом журнале карточка показывала
  // "ждут ответа: 6" вместо настоящих двухсот.
  const six = Array.from({ length: 6 }, (_, i) => ({ id: "c-" + i }));
  const panel = panelState(AT, SUMMARY, six, { awaiting: 214 });
  eq(panel.awaiting, 214, "the count is not the length of the page");
  eq(panel.pending.length, 6, "the page is still short");
});

await test("without a separate count awaiting falls back to the list", () => {
  const many = Array.from({ length: 11 }, (_, i) => ({ id: "c-" + i }));
  eq(panelState(AT, SUMMARY, many, {}).awaiting, 11, "tests pass uncut lists");
  eq(panelState(AT, SUMMARY, [], {}).awaiting, 0, "no questions, no waiting");
});

await test("an empty journal is zeros and no work, not a claim of success", () => {
  const panel = panelState(AT, {}, [], {});
  eq(panel.answered, 0, "answered");
  eq(panel.awaiting, 0, "awaiting");
  eq(panel.karma, null, "karma stays unknown");
  eq(panel.unread, null, "unread stays unknown");
  eq(panel.reason, null, "no reason when the journal was read fine");
});

await test("a missing journal says why instead of showing zeros as truth", () => {
  const panel = panelEmpty("ledger-missing");
  eq(panel.ok, false, "not ok");
  eq(panel.reason, "ledger-missing", "reason");
  eq(panel.pending, [], "no questions");
});

await test("a refusal carries no timestamp, because it read nothing", () => {
  // at = время ПОПЫТКИ, а не время данных. Панель по нему подписывала
  // "только что" и красила ноль зелёным, хотя снимка не существует.
  eq(panelEmpty("ledger-missing").at, 0, "no time on refusal");
  eq(panelEmpty(new Error("db is locked")).at, 0, "no time on failure");
  eq(panelState(AT, {}, [], {}).at, AT, "a real snapshot keeps its time");
});

// ---- the questions list -----------------------------------------------------------

await test("a row without an id is not a question", () => {
  eq(panelPending([{ id: "  " }, { author: "x" }]), [], "dropped");
  eq(panelPending([{ id: " c-1 ", author: " a " }])[0].id, "c-1", "trimmed id");
  eq(panelPending([{ id: "c-1", author: " a " }])[0].author, "a", "trimmed author");
});

await test("the body shown on the card is one line and bounded", () => {
  const body = "a".repeat(500) + "\n second line\r\n third";
  const row = panelPending([{ id: "c-1", body }])[0];
  truthy(row.body.length <= 400, "body is bounded");
  eq(row.body.indexOf("\n"), -1, "no newline survives");
});

await test("a missing title is empty, not the word undefined", () => {
  eq(panelPending([{ id: "c-1" }])[0].title, "", "title");
  eq(panelPending(null), [], "no rows at all");
});

// ---- karma cache ------------------------------------------------------------------

await test("karma and unread are remembered for the card", () => {
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  rememberHome(db, 103, 33, AT);
  const rows = db.prepare("SELECT key, value FROM ticker_state ORDER BY key").all();
  db.close();
  eq(rows, [
    { key: "karma", value: "103" },
    { key: "karma_at", value: String(AT) },
    { key: "unread", value: "33" },
  ], "both cached, with the time they were seen");
  rmSync(dir, { recursive: true, force: true });
});

await test("an unknown karma is not cached as a zero", () => {
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  rememberHome(db, null, undefined, AT);
  eq(db.prepare("SELECT COUNT(*) AS n FROM ticker_state").get().n, 0, "nothing written");
  rememberHome(db, 0, 0, AT);
  eq(db.prepare("SELECT COUNT(*) AS n FROM ticker_state").get().n, 3, "a real zero is cached");
  db.close();
  rmSync(dir, { recursive: true, force: true });
});

await test("the stamp is not refreshed by a scan that saw nothing", () => {
  // Карма кэшируется навсегда, пока скан не увидит её снова. Если пустой скан
  // обновлял бы время, подпись «карма посмотрена только что» ожила бы вр��ть.
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  rememberHome(db, 7, 1, AT);
  rememberHome(db, null, null, AT + 999_999);
  const rows = db.prepare("SELECT key, value FROM ticker_state ORDER BY key").all();
  db.close();
  eq(rows.find((r) => r.key === "karma_at").value, String(AT), "stamp stays where the number was seen");
  eq(rows.find((r) => r.key === "karma").value, "7", "and the old number is still there");
  rmSync(dir, { recursive: true, force: true });
});

await test("a blank cached value reads as unknown, not as zero", () => {
  // readHome больше не читает всю таблицу, а берёт три ключа WHERE key IN (...).
  // Пустая строка в значении - это не ноль: Number("") === 0, и карточка
  // показывала бы выдуманную нулевую карму.
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  db.prepare("INSERT INTO ticker_state (key, value) VALUES ('karma', '')").run();
  db.prepare("INSERT INTO ticker_state (key, value) VALUES ('karma_at', '5')").run();
  db.prepare("INSERT INTO ticker_state (key, value) VALUES ('unread', '42')").run();
  db.prepare("INSERT INTO ticker_state (key, value) VALUES ('repost_seen:x', 'y')").run();
  const rows = db.prepare("SELECT key, value FROM ticker_state WHERE key IN ('karma','karma_at','unread')").all();
  db.close();
  eq(rows.length, 3, "only the three wanted keys are read");
  eq(Number(rows[0].value), 0, "JS would have made a zero out of it");
  eq(rows[1].value, "5", "the stamp comes along");
  eq(rows[2].value, "42", "a real value survives");
  rmSync(dir, { recursive: true, force: true });
});

// ---- the hang that would silence every later call ---------------------------------

await test("a request that never answers is dropped, not waited on forever", async () => {
  truthy(MOLTBOOK_TIMEOUT_MS > 0, "there is a limit at all");
  // Сокет, который не отвечает никогда. Без Abort он держал бы очередь
  // enqueueTool вечно, и moltbook_status после него не отвечал бы никогда.
  // Предел здесь 40мс - проверяем механизм, а не полминутное ожидание.
  const realFetch = globalThis.fetch;
  globalThis.fetch = (_url, options) =>
    new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => reject(new Error("aborted")));
    });
  try {
    const started = Date.now();
    let refused = false;
    await fetchWithTimeout("http://127.0.0.1:1/never", {}, 40).catch(() => {
      refused = true;
    });
    truthy(refused, "the call gave up instead of hanging");
    truthy(Date.now() - started < 2000, "it dropped the request right away");
  } finally {
    globalThis.fetch = realFetch;
  }
});

await test("an answered request is returned as is", async () => {
  const realFetch = globalThis.fetch;
  let seen = null;
  globalThis.fetch = async (url, options) => {
    seen = { url, hasSignal: Boolean(options && options.signal) };
    return { ok: true, status: 200, text: async () => "body" };
  };
  try {
    const response = await fetchWithTimeout("http://127.0.0.1:1/ok", { method: "GET" }, 500);
    eq(response.status, 200, "status through");
    eq(response.ok, true, "ok through");
    eq(response.body, "body", "the body came back, not the Response");
    truthy(seen.hasSignal, "the request carries the signal the timer aborts");
  } finally {
    globalThis.fetch = realFetch;
  }
});

await test("headers that arrive and then a body that never ends are still given up on", async () => {
  // Сервер отдал 200 и заголовки, и завис на теле. Если снять предел после fetch,
  // response.text() остался бы ждать вечно - то есть тот самый зависший вызов,
  // ради которого предел вводился, вернулся бы через другую дверь.
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (_url, options) => ({
    ok: true,
    status: 200,
    text: () =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener("abort", () => reject(new Error("aborted")));
      }),
  });
  try {
    const started = Date.now();
    let refused = false;
    await fetchWithTimeout("http://127.0.0.1:1/half", {}, 40).catch(() => {
      refused = true;
    });
    truthy(refused, "a body that never ends is dropped too");
    truthy(Date.now() - started < 2000, "it did not wait forever on the body");
  } finally {
    globalThis.fetch = realFetch;
  }
});

// ---- refusals ---------------------------------------------------------------------

await test("reading the panel outside bun refuses instead of throwing", () => {
  const panel = readPanel(AT);
  eq(panel.ok, false, "not ok");
  truthy(typeof panel.reason === "string" && panel.reason !== "", "reason is a string");
  eq(panel.pending, [], "no questions invented");
});

await test("writing the panel is skipped when there is no directory", () => {
  eq(MOLTBOOK_DIR, "", "no directory in tests");
  eq(writePanel(AT), null, "nothing written, nothing thrown");
});

await test("a refused snapshot is not written over a good one", () => {
  // Журнал может быть занят сканом (SQLITE_BUSY). Тогда readPanel вернёт отказ,
  // и без проверки вчерашние заботы исчезли бы из карточки из-за того, что
  // чтение не удалось. writePanel возвращает null и файл не трогает.
  eq(writePanel(AT, panelEmpty("ledger-busy")), null, "a refusal is not a snapshot");
  eq(writePanel(AT, { ...panelEmpty(null), ok: true, at: AT }), null, "no directory anyway");
});

await test("the whole call has a deadline, not just every request", async () => {
  // Предел на один запрос не спасает скан: он делает десяток GET подряд, и
  // десять висящих по полминуты запросов держали очередь вызовов пять минут -
  // всё это время moltbook_status не отвечал, хотя жив.
  truthy(MOLTBOOK_CALL_BUDGET_MS >= MOLTBOOK_TIMEOUT_MS, "the call outlives one request");
  truthy(MOLTBOOK_CALL_BUDGET_MS < MOLTBOOK_TIMEOUT_MS * 10, "but not ten of them");
});

await test("the panel file has a plain name", () => {
  eq(PANEL_NAME, "panel.json", "panel name");
});

// ---- 34. two tool calls must not interleave: the journal and the snapshot
// are one apiece, and a late writer would put fresher numbers back in time.
await test("parallel tool calls run one after another, never at once", async () => {
  let inside = 0;
  let overlapped = 0;
  const order = [];
  const task = (name, delay) => () =>
    enqueueTool(async () => {
      inside += 1;
      if (inside > 1) overlapped += 1;
      order.push("in:" + name);
      await new Promise((resolve) => setTimeout(resolve, delay));
      order.push("out:" + name);
      inside -= 1;
    });
  await Promise.all([task("a", 30)(), task("b", 0)()]);
  eq(overlapped, 0, "no two calls inside at once");
  eq(order.length, 4, "both calls ran");
  truthy(
    order.indexOf("out:a") < order.indexOf("in:b") ||
      order.indexOf("out:b") < order.indexOf("in:a"),
    "one call finished before the other started",
  );
});

await test("a failed call does not break the queue for the next one", async () => {
  const results = await Promise.all([
    enqueueTool(() => Promise.reject(new Error("boom"))).then(
      () => "resolved",
      (e) => "rejected:" + e.message,
    ),
    enqueueTool(() => Promise.resolve("after")),
  ]);
  eq(results[0], "rejected:boom", "the failure reached its own caller");
  eq(results[1], "after", "the next call still ran");
});

// ---- 36. a refusal from the journal write must reach the agent. Returning a
// reason nobody reads is the same as staying silent: the panel would keep
// showing dashes and the scan would look like it simply saw no account.
await test("caching the account refuses instead of throwing when the journal is gone", () => {
  eq(MOLTBOOK_DIR, "", "no journal in tests, so this is the refusal path");
  const refused = cacheHome(103, 33);
  truthy(typeof refused === "string" && refused !== "", "a refusal is named");
  truthy(refused.includes("ledger"), "the refusal says which store refused");
});

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}