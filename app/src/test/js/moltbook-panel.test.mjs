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
  panelBody,
  panelEmpty,
  rememberHome,
  translationKey,
  translateUrl,
  parseTranslation,
  translateText,
  translatePending,
  translationFor,
  rememberTranslation,
  TRANSLATE_ENDPOINT,
  TRANSLATE_PER_SCAN,
  TRANSLATE_BUDGET_MS,
  TRANSLATE_TIMEOUT_MS,
  TRANSLATE_TAIL_GUARD_MS,
  readPanel,
  writePanel,
  cacheHome,
  MOLTBOOK_DIR,
  enqueueTool,
  fetchWithTimeout,
  MOLTBOOK_TIMEOUT_MS,
  MOLTBOOK_CALL_BUDGET_MS,
  MOLTBOOK_BIND_RETRY_DELAYS_MS,
  isPortBusyError,
  bindHttpServer,
  parseNotifications,
  parseCreatedAt,
  writeScan,
  notificationPath,
  nextNotificationPath,
  SCAN_NOTIFICATION_PAGES,
  NOTIFICATION_KINDS,
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

await test("awaiting is the whole table, not only what the list holds", () => {
  const many = Array.from({ length: 11 }, (_, i) => ({ id: "c-" + i }));
  const panel = panelState(AT, SUMMARY, many, { awaiting: 11 });
  eq(panel.awaiting, 11, "awaiting counts every question");
  eq(panel.pending.length, 11, "the list keeps them all");
});

await test("awaiting survives a list that was cut off by the query", () => {
  // Счётчик приходит отдельным COUNT, а список - обрезанным пределом запроса.
  // Если бы awaiting считался по длине списка, на большом журнале карточка
  // показала бы "ждут ответа: 214 строк показано как 120".
  const page = Array.from({ length: 120 }, (_, i) => ({ id: "c-" + i }));
  const panel = panelState(AT, SUMMARY, page, { awaiting: 214 });
  eq(panel.awaiting, 214, "the count is not the length of the page");
  eq(panel.pending.length, 120, "the page is still the whole page");
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

await test("the body keeps its whole text, only the newlines are folded", () => {
  const long = "a".repeat(2500);
  const body = long + "\n second line\r\n third";
  const row = panelPending([{ id: "c-1", body }])[0];
  // 400 символов обрезали половину комментария: юзер открывал панель читать, а
  // читал обрывок. Теперь режется только переводчик, и то на границе карточки.
  eq(row.body.length, 2500 + " second line third".length, "body is whole");
  truthy(row.body.startsWith(long), "nothing lost at the front");
  truthy(row.body.endsWith("third"), "nothing lost at the tail");
  eq(row.body.indexOf("\n"), -1, "no newline survives");
});

await test("a body past the translator ceiling is still bounded", () => {
  const row = panelPending([{ id: "c-1", body: "b".repeat(9000) }])[0];
  truthy(row.body.length <= 4000, "body is bounded");
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

// ---- Порт занят не мгновенно. Плагин перезапускается вместе с serve, его порт
// 4201 фиксирован, и раскрытые ранее соединения уходят в TIME_WAIT: Bun.serve без
// SO_REUSEADDR падал с EADDRINUSE, и модель молча теряла инструменты moltbook.
await test("only a busy port is worth retrying", () => {
  truthy(isPortBusyError({ code: "EADDRINUSE", message: "listen failed" }), "EADDRINUSE is busy");
  truthy(isPortBusyError(new Error("Failed to start server. Is port 4201 in use?")), "text says busy");
  truthy(!isPortBusyError(new Error("Invalid port")), "anything else is our own bug");
  truthy(!isPortBusyError(undefined), "no error is not a busy port");
});

await test("the port is retried and the plugin still comes up", async () => {
  let calls = 0;
  const server = await bindHttpServer(
    { port: 4201 },
    {
      delays: [1, 1],
      create: () => {
        calls += 1;
        if (calls < 3) throw new Error("Failed to start server. Is port 4201 in use?");
        return { port: 4201, attempts: calls };
      },
    },
  );
  eq(calls, 3, "two refusals then success");
  eq(server.attempts, 3, "the server came up, not a refusal");
});

await test("a port held by a live process fails loudly instead of hanging", async () => {
  let calls = 0;
  let thrown = null;
  try {
    await bindHttpServer(
      { port: 4201 },
      {
        delays: [1, 1],
        create: () => {
          calls += 1;
          throw new Error("Failed to start server. Is port 4201 in use?");
        },
      },
    );
  } catch (error) {
    thrown = error;
  }
  eq(calls, 3, "tried once plus every delay, then gave up");
  truthy(thrown !== null, "a refusal nobody can fix is not retried forever");
  truthy(String(thrown && thrown.message).includes("4201"), "the last refusal is the one reported");
});

await test("our own bind error is not retried at all", async () => {
  let calls = 0;
  let thrown = null;
  try {
    await bindHttpServer(
      { port: 4201 },
      {
        delays: [1, 1, 1],
        create: () => {
          calls += 1;
          throw new Error("Invalid port");
        },
      },
    );
  } catch (error) {
    thrown = error;
  }
  eq(calls, 1, "one try: retrying our own bug only delays the report");
  eq(String(thrown && thrown.message), "Invalid port", "reported as is");
});

await test("the retry budget covers TIME_WAIT but is not endless", () => {
  truthy(MOLTBOOK_BIND_RETRY_DELAYS_MS.length > 0, "there is something to wait for");
  truthy(MOLTBOOK_BIND_RETRY_DELAYS_MS.length <= 6, "and it is bounded");
  const total = MOLTBOOK_BIND_RETRY_DELAYS_MS.reduce((a, b) => a + b, 0);
  truthy(total >= 3000, "waits long enough for TIME_WAIT to expire (" + total + " ms)");
  truthy(total <= 30000, "but not so long that a real conflict looks like a hang (" + total + " ms)");
});

// ---- Второй контур: наши посты. Лента отдаёт чужие, поэтому наши посты видны
// только через /notifications. Без него ours был нулём при живых постах агента.
await test("a notification about our post names the post to read", () => {
  const page = parseNotifications(
    JSON.stringify({
      notifications: [
        {
          id: "f02a0123-",
          agentId: "e3c0a083-",
          type: "post_comment",
          content: "Someone commented on your post",
          relatedPostId: "5ead528f-",
          relatedCommentId: "21f2d192-",
          isRead: false,
          createdAt: "2026-10-10T15:58:16.153Z",
          post: { id: "5ead528f-", title: "Ask: how do you implement memory" },
          comment: { id: "21f2d192-", authorId: "0f86f8a4-", parentId: null },
        },
      ],
      has_more: true,
      next_cursor: "c2",
      unread_count: 84,
    }),
  );
  truthy(page.ok, "a page of notifications is readable");
  eq(page.posts, ["5ead528f-"], "the post to read comes out of the notification");
  eq(page.titles["5ead528f-"], "Ask: how do you implement memory", "the title travels with it");
  eq(page.items[0].comment_id, "21f2d192-", "and the comment it is about");
  eq(page.nextCursor, "c2", "the cursor is what the next page hangs on");
  eq(page.unread, 84, "unread is read from the answer, not counted by us");
});

await test("a reply to our comment points at the comment, not at the post", () => {
  const page = parseNotifications(
    JSON.stringify({
      notifications: [
        {
          type: "comment_reply",
          relatedPostId: "post-1",
          relatedCommentId: "20cbd0df-",
          isRead: true,
          comment: { id: "20cbd0df-", parentId: "261a6bcb-" },
        },
      ],
    }),
  );
  // Разбор агента с устройства: relatedCommentId - это НОВЫЙ комментарий,
  // а comment.parentId - наш комментарий, на который ответили. Если спутать,
  // агент решит отвечать сам себе.
  eq(page.items[0].parent_id, "261a6bcb-", "the thing being answered is ours");
  eq(page.items[0].comment_id, "20cbd0df-", "the new comment is theirs");
});

await test("following someone is not a post to read", () => {
  const page = parseNotifications(
    JSON.stringify({
      notifications: [
        {
          type: "new_follower",
          content: "khayon started following you",
          isRead: false,
          createdAt: "2026-10-10T08:05:37.501Z",
        },
      ],
    }),
  );
  // Подписчик не несёт ни postId, ни commentId: читать нечего, и выдумывать
  // пост из пустоты нельзя - получится строка, не привязанная ни к чему.
  eq(page.items, [], "no item is invented out of a follower");
  eq(page.posts, [], "and no post to read either");
});

await test("a notification without a post id is refused, not guessed", () => {
  const page = parseNotifications(
    JSON.stringify({ notifications: [{ type: "post_comment", relatedCommentId: "c1" }] }),
  );
  eq(page.items, [], "a post id is the only thing that makes a notification usable");
  eq(page.posts, [], "nothing to read follows from it");
});

await test("garbage notifications say so instead of looking empty", () => {
  truthy(!parseNotifications("не json").ok, "not json is a refusal");
  truthy(parseNotifications("не json").reason === "notifications-not-json", "and it is named");
  truthy(!parseNotifications('{"notifications":"не список"}').ok, "a string where a list belongs is a refusal");
  truthy(!parseNotifications(null).ok, "nothing at all is a refusal, not an empty inbox");
});

await test("the first page needs no cursor and later ones do", () => {
  eq(notificationPath(null), "/api/v1/notifications", "the first page is just the address");
  eq(notificationPath(""), "/api/v1/notifications", "and an empty cursor is not a cursor");
  eq(
    notificationPath("c2"),
    "/api/v1/notifications?cursor=c2",
    "the next page hangs on what the last answer returned",
  );
});

await test("the scan stops instead of walking the same page forever", () => {
  // У этого эндпоинта limit и offset не работают, а has_more всегда true,
  // пока не прочитать. Без проверки курсор скан крутил бы одну страницу вечно.
  eq(
    nextNotificationPath(null, { nextCursor: "c2" }, 0, SCAN_NOTIFICATION_PAGES),
    "/api/v1/notifications?cursor=c2",
    "a moving cursor earns a request",
  );
  eq(nextNotificationPath("c2", { nextCursor: "c2" }, 0, 4), null, "the same cursor does not");
  eq(nextNotificationPath("c2", { nextCursor: null }, 0, 4), null, "no cursor means the end");
  eq(nextNotificationPath("c2", { nextCursor: "c3" }, 3, 4), null, "the budget is spent");
});

await test("the unread pile is bounded, not drained", () => {
  truthy(SCAN_NOTIFICATION_PAGES >= 2, "at least the newest page and one behind it");
  truthy(SCAN_NOTIFICATION_PAGES <= 8, "and not a march through all 123 unread");
  truthy(NOTIFICATION_KINDS.includes("post_comment"), "comments on our post are read");
  truthy(NOTIFICATION_KINDS.includes("comment_reply"), "replies to us are read");
  truthy(!NOTIFICATION_KINDS.includes("new_follower"), "followers are not a thread to read");
});

// ---- ours по уведомлению -----------------------------------------------------------

await test("a post we were notified about is ours, even with no name to compare", () => {
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  writeScan(
    db,
    [
      // Пост из ленты - чужой.
      { id: "p-feed", title: "чужой", author: "vina", comments_total: 3, upvotes: 1, created_at: 0, repost_of: null },
      // Пост из уведомления: автора не знаем, но post_comment по определению
      // значит «прокомментировали ваш пост». Сравнивать не с чем - /agents/{id} 404.
      { id: "p-notice", title: "наш", author: "", comments_total: 0, upvotes: 0, created_at: 0, repost_of: null },
    ],
    [],
    AT,
    ["p-notice"],
  );
  const ours = db.prepare("SELECT id FROM posts WHERE ours = 1").all().map((r) => r.id);
  const theirs = db.prepare("SELECT id FROM posts WHERE ours = 0").all().map((r) => r.id);
  db.close();
  rmSync(dir, { recursive: true, force: true });
  eq(ours, ["p-notice"], "the post we were notified about counts as ours");
  eq(theirs, ["p-feed"], "and the one from the feed does not");
});

await test("without a notification the same post stays somebody else's", () => {
  const { dir, file } = journal();
  const db = new DatabaseSync(file);
  writeScan(
    db,
    [{ id: "p-x", title: "x", author: "", comments_total: 0, upvotes: 0, created_at: 0, repost_of: null }],
    [],
    AT,
    [],
  );
  eq(db.prepare("SELECT COUNT(*) AS n FROM posts WHERE ours = 1").get().n, 0, "no name and no notice means not ours");
  db.close();
  rmSync(dir, { recursive: true, force: true });
});

await test("a date the server wrote is a time, not NaN", () => {
  // Молтбук отдаёт createdAt строкой ISO, а колонка ждёт число миллисекунд.
  // Раньше здесь стояло Number(item.created_at): имя поля не совпадало,
  // получался NaN, SQLite считал его NULL, а INSERT OR IGNORE молча
  // проглатывал нарушение NOT NULL. Треды читались, а в журнал не попадало
  // ни одного комментария - и плагин об этом не говорил.
  eq(parseCreatedAt("2026-10-10T15:58:16.153Z"), Date.parse("2026-10-10T15:58:16.153Z"), "ISO becomes milliseconds");
  eq(parseCreatedAt("2026-10-10T15:58:16.153Z"), 1791647896153, "and it is that exact instant");
  eq(parseCreatedAt(1791668296153), 1791668296153, "a number stays a number");
  eq(parseCreatedAt("1791668296153"), 1791668296153, "a numeric string is read as a number");
  eq(parseCreatedAt("вчера"), 0, "nonsense is zero, not NaN");
  eq(parseCreatedAt(undefined), 0, "and absence is zero too");
  eq(Number.isNaN(parseCreatedAt("вчера")), false, "NaN must never reach SQLite");
});

await test("a reply to our comment does not make the post ours", () => {
  // Разбор агента: comment_reply - это ответ на наш комментарий в ЧУЖОМ посте.
  // Если такой пост пометить ours=1, то ourPosts и repliesToOurs на карточке
  // начнут считать чужую работу нашей.
  const page = parseNotifications(
    JSON.stringify({
      notifications: [
        { type: "comment_reply", relatedPostId: "post-foreign", relatedCommentId: "c9" },
        { type: "post_comment", relatedPostId: "post-ours", relatedCommentId: "c1" },
      ],
    }),
  );
  eq(page.posts, ["post-foreign", "post-ours"], "оба поста надо прочитать");
  eq(page.ourPosts, ["post-ours"], "но наш только тот, где прокомментировали НАС");
});

// --- Переводы на русский ---------------------------------------------------

await test("the same text always gets the same translation key", () => {
  eq(translationKey("Recovery policies"), translationKey("  Recovery   policies \n"), "same");
  truthy(translationKey("a") !== translationKey("b"), "different texts differ");
});

await test("a short text cannot steal the cache slot of a long one", () => {
  // Хэш короткого текста вполне может совпасть с хэшем длинного; молотбук полон
  // коротких реплик, где совпадение стоило бы показать не тот перевод.
  const short = "ok";
  const long = "ok".repeat(200);
  truthy(translationKey(short) !== translationKey(long), "length is part of the key");
});

await test("the translation address carries the text, not a broken one", () => {
  const url = translateUrl("Memory & recovery?");
  truthy(url.startsWith(TRANSLATE_ENDPOINT + "?"), "endpoint");
  truthy(url.includes("tl=ru"), "target language");
  truthy(url.includes("q=Memory%20%26%20recovery%3F"), "text is encoded");
  truthy(!url.includes(" "), "no raw spaces");
});

await test("a long comment is cut for the translator, not for the card", () => {
  const url = translateUrl("z".repeat(9000));
  // Потолок переводчика РОВНО равен тому, что показывает карточка. Меньше - и
  // перевод покрывает часть тела, но подан как полный: это хуже, чем никакого.
  // Больше - переводим текст, которого юзер никогда не увидит.
  truthy(url.includes("q=" + "z".repeat(4000)), "translator gets the card's ceiling");
  truthy(!url.includes("z".repeat(4001)), "and not a char more");
});

await test("the translator answers in pieces and the pieces are joined", () => {
  // Настоящий ответ: все куски лежат в ПЕРВОМ массиве, а не каждый в своём.
  const raw = '[[["Политики восстановления","Recovery policies",null,3],[" вместо ","x",null,3]]]';
  eq(parseTranslation(raw), "Политики восстановления вместо", "joined");
  eq(parseTranslation(""), "", "empty answer");
  eq(parseTranslation("not json at all"), "", "garbage is not a translation");
  eq(parseTranslation('{"error":"quota"}'), "", "a json object is not a translation");
  eq(parseTranslation('[[]]'), "", "no chunks");
});

await test("a refused translation leaves the card with the original", async () => {
  eq(await translateText("hello", { request: async () => ({ ok: false, body: "" }) }), "", "refused");
  eq(
    await translateText("hello", {
      request: async () => {
        throw new Error("сеть легла");
      },
    }),
    "",
    "thrown",
  );
  eq(await translateText("   ", { request: async () => ({ ok: true, body: "[[[\"x\"]]]" }) }), "", "empty text is not sent");
});

await test("a translation of one text is read back for the next scan", async () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
  try {
    const text = "Durable memory beats a longer context window.";
    const ru = await translateText(text, {
      request: async () => ({ ok: true, body: '[[["Долговременная память сильнее длинного окна контекста."]]]' }),
    });
    eq(ru, "Долговременная память сильнее длинного окна контекста.", "translated");
    truthy(rememberTranslation(db, translationKey(text), ru), "remembered");
    eq(translationFor(db, translationKey(text)), ru, "read back");
    eq(rememberTranslation(db, translationKey(text), "  "), false, "пустой перевод не запоминаем");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the card shows the translation when there is one", () => {
  const body = "Stealing-worthy question, and I can only answer the easy version.";
  const row = panelPending([{ id: "c-1", body }], new Map([[translationKey(body), "Вопрос-кража."]]))[0];
  eq(row.bodyRu, "Вопрос-кража.", "translation reached the card");
  eq(row.body, body, "the original is still there");
  eq(panelPending([{ id: "c-1", body }])[0].bodyRu, "", "без перевода поле пустое, не выдуманное");
});

await test("a scan translates a bounded batch and skips what it already has", async () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  try {
    db.exec(
      "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
    );
    // Переводы лежат в ticker_state рядом с кармой: своей таблицы у них нет
    // специально, лишняя миграция схемы того не стоит.
    db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
    const stmt = db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)");
    for (let i = 0; i < 5; i += 1) stmt.run("c-" + i, "vina", "Comment number " + i, i);
    db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES ('c-own', ?, 'наш', 'NEW', 9)").run("opencodekz");

    let asked = 0;
    const result = await translatePending(db, {
      limit: 5,
      // Бюджет задан явно и заведомо больше стража: «времени нет» проверяет
      // отдельный тест, здесь оно есть.
      budgetMs: TRANSLATE_BUDGET_MS,
      request: async () => {
        asked += 1;
        return { ok: true, body: '[[["перевод"]]]' };
      },
      now: () => 1000,
    });
    eq(result.attempted, 5, "asked about five");
    eq(result.translated, 5, "translated five");
    eq(asked, 5, "the network heard five requests");
    eq(translationFor(db, translationKey("Comment number 0")), "перевод", "saved");
    eq(translationFor(db, translationKey("наш")), "", "our own comment is not translated");

    const second = await translatePending(db, {
      limit: 5,
      request: async () => {
        asked += 1;
        return { ok: true, body: '[[["перевод"]]]' };
      },
    });
    eq(second.attempted, 0, "второй круг не ходит в сеть за тем же");
    eq(asked, 5, "and asked nothing");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the budget stops the batch instead of the scan", async () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  try {
    db.exec(
      "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
    );
    // Переводы лежат в ticker_state рядом с кармой: своей таблицы у них нет
    // специально, лишняя миграция схемы того не стоит.
    db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
    const stmt = db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)");
    for (let i = 0; i < 10; i += 1) stmt.run("c-" + i, "vina", "Body " + i, i);
    let tick = 0;
    const result = await translatePending(db, {
      limit: 10,
      // Бюджет за вычетом защитного хвоста: запрос не начинается, если ему не
      // хватит времени на поход наружу. Считаем от констант, а не от
      // выдуманных чисел - страж теперь равен таймауту, и «магические» 2500
      // означали бы «не осталось ничего».
      budgetMs: TRANSLATE_TAIL_GUARD_MS + 1000,
      request: async () => ({ ok: true, body: '[[["перевод"]]]' }),
      now: () => (tick += 300),
    });
    // Время идёт на каждой проверке: за круг успевает столько, сколько влезает
    // в запас сверх стража.
    eq(result.translated, 3, "переведено до бюджета, а не всё");
    truthy(result.translated > 0 && result.translated < 10, "partial batch");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the queue reaches its tail instead of circling the oldest twenty five", async () => {
  // Настоящий баг: SELECT ... LIMIT 25 по created_at возвращал одни и те же
  // двадцать пять самых старых комментария, а переведённые из прошлого круга
  // просто пропускались - то есть с четвёртого круга очередь была мёртвой.
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  db.exec(
    "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
  );
  db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
  try {
    const stmt = db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)");
    for (let i = 0; i < 60; i += 1) stmt.run("c-" + i, "vina", "Comment number " + i, i);
    const send = async () => ({ ok: true, body: '[[["перевод"]]]' });

    const first = await translatePending(db, { limit: 25, request: send });
    eq(first.translated, 25, "первый круг взял двадцать пять");

    const second = await translatePending(db, { limit: 25, request: send });
    eq(second.translated, 25, "второй круг пошёл дальше, а не повторил первый");
    eq(second.skipped, 25, "мимо уже переведённых");

    const third = await translatePending(db, { limit: 25, request: send });
    eq(third.translated, 10, "третий круг дошёл до хвоста");
    eq(translationFor(db, translationKey("Comment number 59")), "перевод", "последний тоже переведён");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the translation budget is a sane number, not a magic one", () => {
  truthy(TRANSLATE_PER_SCAN >= 5 && TRANSLATE_PER_SCAN <= 100, "per scan");
  truthy(TRANSLATE_BUDGET_MS >= 5000 && TRANSLATE_BUDGET_MS <= 60000, "budget");
});

await test("a long comment finds its translation by the text the card shows", () => {
  // readPanel считал ключ от полного тела, а карточка показывала обрезанное на
  // 4000 символах - у длинного комментария перевод не находился никогда.
  const raw = "z".repeat(9000);
  const shown = panelPending([{ id: "c-1", body: raw }])[0];
  const cache = new Map([[translationKey(panelBody(raw)), "перевод длинного"]]);
  const row = panelPending([{ id: "c-1", body: raw }], cache)[0];
  eq(row.bodyRu, "перевод длинного", "перевод найден по показанному тексту");
  eq(row.body, shown.body, "и тело то же самое");
});

await test("a long comment is stored under the key the card will ask for", async () => {
  // Тот же разрыв со другой стороны: переводчик писал под ключ полного тела,
  // панель искала под ключ обрезанного - перевод л��г в кэш и там оставался.
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  db.exec(
    "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
  );
  db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
  try {
    const raw = "z".repeat(9000);
    db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)").run(
      "c-long",
      "vina",
      raw,
      1,
    );
    const send = async () => ({ ok: true, body: '[[["перевод длинного"]]]' });
    const result = await translatePending(db, { limit: 25, request: send });
    eq(result.translated, 1, "переведён");
    eq(
      translationFor(db, translationKey(panelBody(raw))),
      "перевод длинного",
      "лежит под тем ключом, который спросит карточка",
    );
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the tail guard is not shorter than one translation", () => {
  // Страж в две секунды при переводе на пятнадцать не защищал ровно от того,
  // от чего объявлен: запрос, начатый впритык, всё равно уходил за бюджет.
  truthy(
    TRANSLATE_TAIL_GUARD_MS >= TRANSLATE_TIMEOUT_MS,
    "guard covers a whole request",
  );
});

await test("no budget left means no translation, not a fresh default", async () => {
  // budgetMs: 0 - это НОЛЬ. Раньше он читался как "бюджет не передан" и скан
  // уходил в сотни заведомо мёртвых запросов к переводчику.
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  db.exec(
    "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
  );
  db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
  try {
    const stmt = db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)");
    for (let i = 0; i < 5; i += 1) stmt.run("c-" + i, "vina", "Body " + i, i);
    let calls = 0;
    const result = await translatePending(db, {
      limit: 25,
      budgetMs: 0,
      request: async () => {
        calls += 1;
        return { ok: true, body: '[[["перевод"]]]' };
      },
    });
    eq(calls, 0, "ни одного запроса наружу");
    eq(result.translated, 0, "и ничего не переведено");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

await test("the limit counts attempts, not only successes", async () => {
  // Отказ переводчика не должен открывать квоту: раньше неудачные попытки в
  // счёт лимита не шли, и скан делал сотни запросов вместо заявленных двадцати
  // пяти.
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-ru-"));
  const db = new DatabaseSync(join4(dir, "state.db"));
  db.exec(
    "CREATE TABLE comments (id TEXT PRIMARY KEY, author TEXT, body TEXT, status TEXT, created_at INTEGER)",
  );
  db.exec("CREATE TABLE ticker_state (key TEXT PRIMARY KEY, value TEXT)");
  try {
    const stmt = db.prepare("INSERT INTO comments (id, author, body, status, created_at) VALUES (?, ?, ?, 'NEW', ?)");
    for (let i = 0; i < 60; i += 1) stmt.run("c-" + i, "vina", "Body " + i, i);
    let calls = 0;
    const result = await translatePending(db, {
      limit: 25,
      budgetMs: TRANSLATE_BUDGET_MS,
      // Переводчик молчит: ни одного успеха, значит лимит обязан остановить
      // по числу попыток, а не ждать своего успеха.
      request: async () => {
        calls += 1;
        return { ok: false, status: 429, text: async () => "quota" };
      },
    });
    eq(calls, 25, "ровно двадцать пять попыток, не шестьдесят");
    eq(result.attempted, 25, "и столько же попыток в отчёте");
    eq(result.translated, 0, "успехов ноль");
  } finally {
    db.close();
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}
