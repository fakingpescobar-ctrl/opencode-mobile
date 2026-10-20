#!/usr/bin/env node
// Tests for moltbook_report - the file the PC agent reads.
//
// Two halves. The pure half (window, verifier log, journal projection, rendering)
// is checked without a disk and without a network. The orchestration half runs
// against a real journal through node:sqlite with injected deps, so the SQL and
// the file write are exercised for real.
//
// Comments are deliberately ASCII: hand-guessed \uXXXX escapes produced
// semantically garbled Russian twice in this project.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { DatabaseSync } from "node:sqlite";

const HERE = dirname(fileURLToPath(import.meta.url));

function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}

const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

const mod = await import(pathToFileURL(MOLTBOOK_JS).href);
const {
  statusIs,
  countReportable,
  REPORT_NAME,
  DEFAULT_REPORT_WINDOW_MS,
  REPORT_PENDING_LIMIT,
  COMMENT_STATUS,
  REPLY_OUTCOME,
  reportWindow,
  parseVerifierLog,
  projectWork,
  pendingQuestions,
  countPending,
  livenessProof,
  deferredDeadline,
  DEFERRED_DEADLINE_MS,
  renderReport,
  writeReport,
  moltbookReport,
} = mod;

// Schema copied from MoltbookLedger.kt onCreate, same as in moltbook-scan.test.mjs.
const SCHEMA = `
  CREATE TABLE comments (
    id TEXT PRIMARY KEY, post_id TEXT NOT NULL, author TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL, our_reply_id TEXT, summary_ru TEXT NOT NULL DEFAULT '',
    seen_at INTEGER NOT NULL, replied_at INTEGER NOT NULL DEFAULT 0,
    reply_outcome TEXT NOT NULL DEFAULT ''
  );
`;

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
const OLD = AT - 10 * 86_400_000;

function posted(over) {
  return Object.assign(
    {
      id: "c-1",
      post_id: "p-1",
      author: "someone",
      body: "body",
      status: COMMENT_STATUS.POSTED,
      our_reply_id: "r-1",
      seen_at: AT,
      replied_at: AT,
      reply_outcome: REPLY_OUTCOME.CREATED,
    },
    over || {},
  );
}

// ---- window -------------------------------------------------------------

await test("an explicit window is taken as asked", () => {
  const w = reportWindow(AT - 3600_000, AT, 1000);
  eq(w.to, AT);
  eq(w.from, AT - 3600_000);
});

await test("zero, negative, future and junk windows fall back to a day", () => {
  const junk = [0, -5, null, undefined, "abc", NaN, AT + 10_000];
  for (const value of junk) {
    const w = reportWindow(value, AT, 0);
    eq(w.from, AT - DEFAULT_REPORT_WINDOW_MS, "fallback for " + String(value));
    eq(w.to, AT);
  }
});

// ---- verifier log -------------------------------------------------------

await test("a verifier line is parsed into at, kind, postId and detail", () => {
  const parsed = parseVerifierLog("1700000000000s confirmed post=p-1 id=c-9\n");
  eq(parsed.unreadable, 0);
  eq(parsed.records.length, 1);
  eq(parsed.records[0].at, 1700000000000);
  eq(parsed.records[0].kind, "confirmed");
  eq(parsed.records[0].postId, "p-1");
  eq(parsed.records[0].detail, "id=c-9");
});

await test("an unreadable line is counted, never silently dropped", () => {
  const text = ["1700000000000s confirmed post=p-1 id=c-9", "half a record", ""].join("\n");
  const parsed = parseVerifierLog(text);
  eq(parsed.records.length, 1);
  eq(parsed.unreadable, 1);
});

await test("every kind the plugin writes is understood by the reader", () => {
  const kinds = ["confirmed", "mismatch", "refused", "divergence", "attempt", "deleted"];
  const text = kinds.map((k, i) => (AT + i) + "s " + k + " post=p-" + i).join("\n");
  const parsed = parseVerifierLog(text);
  eq(parsed.unreadable, 0);
  eq(parsed.records.map((r) => r.kind), kinds);
});

await test("an empty log is empty, not an error", () => {
  const parsed = parseVerifierLog("");
  eq(parsed.records.length, 0);
  eq(parsed.unreadable, 0);
  eq(parseVerifierLog(null).records.length, 0);
});

// ---- projection ---------------------------------------------------------

await test("only created and verified are work", () => {
  const work = projectWork(
    [
      posted({ id: "a", reply_outcome: REPLY_OUTCOME.CREATED }),
      posted({ id: "b", reply_outcome: REPLY_OUTCOME.VERIFIED }),
      posted({ id: "c", reply_outcome: REPLY_OUTCOME.REUSED }),
      posted({ id: "d", reply_outcome: "" }),
    ],
    AT - 1000,
  );
  eq(work.created.length, 1);
  eq(work.created[0].id, "a");
  eq(work.verified.length, 1);
  eq(work.verified[0].id, "b");
  eq(work.reused.length, 1);
});

await test("a pre-v5 row with an empty outcome is unknown, not work", () => {
  const work = projectWork([posted({ reply_outcome: "" })], AT - 1000);
  eq(work.unknown, 1);
  eq(work.created.length, 0);
  eq(work.verified.length, 0);
});

await test("rows older than the window stay out of the count", () => {
  const work = projectWork(
    [
      posted({ id: "old", replied_at: OLD }),
      posted({ id: "fresh", replied_at: AT }),
      posted({ id: "never", replied_at: 0, reply_outcome: REPLY_OUTCOME.CREATED }),
    ],
    AT - 1000,
  );
  eq(work.created.map((r) => r.id), ["fresh"]);
});

await test("failures are listed separately and are not replies", () => {
  const work = projectWork(
    [
      posted({ id: "f", status: COMMENT_STATUS.FAILED, seen_at: AT, replied_at: 0, reply_outcome: "" }),
      posted({ id: "stale", status: COMMENT_STATUS.FAILED, seen_at: OLD, replied_at: 0, reply_outcome: "" }),
    ],
    AT - 1000,
  );
  eq(work.failed.map((r) => r.id), ["f"]);
  eq(work.created.length, 0);
});

await test("no rows project to zeroes rather than to an error", () => {
  const work = projectWork([], AT);
  eq([work.created.length, work.verified.length, work.reused.length, work.failed.length, work.unknown], [0, 0, 0, 0, 0]);
  eq(projectWork(null, AT).created.length, 0);
});

// A refusal because of the depth cap is not a lost answer: it has to be
// retried at the top level of the same thread. Mixed in with the failures it
// would read as "everything is lost", and nobody would retry it.
await test("a deferred answer is a list of its own, not a failure", () => {
  const work = projectWork(
    [
      { id: "d-1", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT, seen_at: AT },
      { id: "f-1", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: "", replied_at: 0, seen_at: AT },
    ],
    AT - 1000,
    AT,
  );
  eq(work.deferred.map((r) => r.id), ["d-1"]);
  eq(work.deferred[0].postId, "p-1");
  eq(work.deferred[0].at, AT, "the moment of the refusal is kept");
  // Отложенный ответ - это НЕ провал: работа не потеряна, её можно повторить.
  // Пока он попадал и в провалы, и в отложенные, отчёт писал «провалов: N» и
  // «отложено: M» про один и тот же ответ.
  eq(work.failed.map((r) => r.id), ["f-1"], "a deferred row is not a failure");
});

// The same answer must not be counted twice: once as a failure and once as a
// deferral. The two numbers stand side by side in the summary.
await test("a deferred answer is not counted twice in the summary", () => {
  const rows = [
    { id: "d-1", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT, seen_at: AT },
    { id: "f-1", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: "", replied_at: 0, seen_at: AT },
  ];
  const text = renderReport({ at: AT, from: AT - 1000, to: AT, work: projectWork(rows, AT - 1000, AT) });
  truthy(text.includes("провалов записано: 1"), "one plain failure");
  truthy(text.includes("отложено до повтора на верхнем уровне: 1"), "one deferral");
});

// The status of a deferred row never changes, so without a window it hung in
// every report and the counter grew for ever. Once the deadline has passed the
// row answers the window like every other row of the journal.
await test("an expired deferral leaves the report with the window", () => {
  const work = projectWork(
    [{ id: "d-old", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: OLD, seen_at: OLD }],
    AT - 1000,
    AT,
  );
  eq(work.deferred.length, 0, "an expired deferral is not pending any more");
  eq(work.overdue.length, 0, "and it is out of the window too");
});

// A deferral that is still within its deadline can still be retried, so it
// stays visible even outside the window: otherwise nobody would retry it.
await test("a deferral within its deadline stays visible past the window", () => {
  const work = projectWork(
    [{ id: "d-1", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT - 2 * 86_400_000, seen_at: AT - 2 * 86_400_000 }],
    AT - 1000,
    AT,
  );
  eq(work.deferred.length, 1, "a fresh deferral is still actionable");
});

await test("a deferred row without a timestamp has no deadline", () => {
  eq(deferredDeadline(0, AT), "срок не установлен");
  eq(deferredDeadline(undefined, AT), "срок не установлен");
  const fresh = deferredDeadline(AT, AT);
  eq(fresh.indexOf("просрочено"), -1, "a fresh deferral is not overdue");
  eq(deferredDeadline(AT - 10 * 86_400_000, AT).indexOf("просрочено") > 0, true, "an old one is");
});

// An empty report and a dead cache look the same. The traces of the last
// read are the only proof that we actually reached the source.
await test("an empty journal proves nothing", () => {
  const proof = livenessProof([]);
  eq(proof.rows, 0);
  eq(proof.lastSeenAt, 0);
  eq(proof.newestId, "");
  eq(proof.newestAt, 0);
});

await test("the liveness proof names the newest comment and the last scan", () => {
  const proof = livenessProof([
    { id: "c-1", created_at: 100, seen_at: 500 },
    { id: "c-2", created_at: 900, seen_at: 700 },
    { id: "c-3", created_at: 300, seen_at: 600 },
  ]);
  eq(proof.rows, 3);
  eq(proof.lastSeenAt, 700);
  eq(proof.newestId, "c-2");
  eq(proof.newestAt, 900);
});

// Not every journal row knows when the comment was written. The proof must not
// invent a date for those: the freshest read wins and the date stays unknown.
await test("without creation times the newest is the freshest read", () => {
  const proof = livenessProof([
    { id: "c-1", seen_at: 500 },
    { id: "c-2", seen_at: 900 },
    { id: "c-3", seen_at: 700 },
  ]);
  eq(proof.newestId, "c-2");
  eq(proof.newestAt, 0);
});

await test("the deadline is three days after the refusal", () => {
  eq(DEFERRED_DEADLINE_MS, 3 * 86_400_000);
  eq(deferredDeadline(AT, AT), stampOf(AT + DEFERRED_DEADLINE_MS));
});

function stampOf(ms) {
  return new Date(ms).toISOString().replace("T", " ").slice(0, 19) + "Z";
}

// ---- pending ------------------------------------------------------------

await test("pending questions are other people's NEW rows only", () => {
  const pending = pendingQuestions(
    [
      { id: "q1", post_id: "p-1", author: "alice", body: "why?", status: COMMENT_STATUS.NEW },
      { id: "q2", post_id: "p-1", author: "opencodekz", body: "mine", status: COMMENT_STATUS.NEW },
      { id: "q3", post_id: "p-1", author: "bob", body: "done", status: COMMENT_STATUS.POSTED },
    ],
    "opencodekz",
    10,
  );
  eq(pending.length, 1);
  eq(pending[0].author, "alice");
  eq(pending[0].commentId, "q1");
});

await test("our own name is matched without regard to case", () => {
  const pending = pendingQuestions(
    [
      { id: "q1", post_id: "p-1", author: "OpenCodeKZ", body: "x", status: COMMENT_STATUS.NEW },
      { id: "q2", post_id: "p-1", author: "alice", body: "y", status: COMMENT_STATUS.NEW },
    ],
    "opencodekz",
    10,
  );
  eq(pending.map((p) => p.commentId), ["q2"]);
});

await test("the pending list is capped", () => {
  const rows = [];
  for (let i = 0; i < 40; i++) {
    rows.push({ id: "q" + i, post_id: "p-1", author: "a" + i, body: "b", status: COMMENT_STATUS.NEW });
  }
  eq(pendingQuestions(rows, "opencodekz", 5).length, 5);
  eq(pendingQuestions(rows, "opencodekz", 0).length, REPORT_PENDING_LIMIT);
});

// The capped list alone looks like "there are exactly 20 pending" for twenty ticks
// in a row. The full count has to be visible next to the shown one, otherwise the
// queue size is a guess.
await test("the full pending count is not capped", () => {
  const rows = [];
  for (let i = 0; i < 40; i++) {
    rows.push({ id: "q" + i, post_id: "p-1", author: "a" + i, body: "b", status: COMMENT_STATUS.NEW });
  }
  eq(countPending(rows, "opencodekz"), 40);
  eq(countPending(rows.slice(0, 5), "opencodekz"), 5);
  eq(countPending([], "opencodekz"), 0);
});

await test("the pending count skips our own rows", () => {
  const rows = [
    { id: "q1", post_id: "p-1", author: "alice", body: "why?", status: COMMENT_STATUS.NEW },
    { id: "q2", post_id: "p-1", author: "opencodekz", body: "mine", status: COMMENT_STATUS.NEW },
    { id: "q3", post_id: "p-1", author: "bob", body: "done", status: COMMENT_STATUS.POSTED },
  ];
  eq(countPending(rows, "opencodekz"), 1);
});

// ---- rendering ----------------------------------------------------------

function sample() {
  return {
    at: AT,
    from: AT - 3600_000,
    to: AT,
    work: {
      created: [{ id: "c-1", postId: "p-1", replyId: "r-1", at: AT }],
      verified: [{ id: "c-2", postId: "p-2", replyId: "r-2", at: AT }],
      reused: [{ id: "c-3", postId: "p-3", replyId: "r-3", at: AT }],
      failed: [{ id: "c-4", postId: "p-4" }],
      unknown: 7,
    },
    pending: [{ postId: "p-5", commentId: "c-5", author: "alice", body: "how are you?" }],
    refusals: [{ at: AT, kind: "refused", postId: "p-6", detail: "server-said-ok-but-not-visible" }],
    unreadable: 2,
  };
}

await test("the report names every section", () => {
  const text = renderReport(sample());
  truthy(text.startsWith("# "), "starts with a heading");
  // Итог, Отложено, Удаления в процессе, Ответы, Не про что, Отказы свидетеля, Живость.
  eq(text.split(/\n\n/).filter((part) => part.startsWith("## ")).length, 7, "seven sections");
  truthy(text.includes("## Отложено"), "the deferred section is there");
  truthy(text.includes("## Удаления в процессе"), "the deletions section is there");
  truthy(text.includes("## Живость"), "the liveness section is there");
});

await test("the report states the counts it computed", () => {
  const text = renderReport(sample());
  truthy(text.includes("reply_id") === false, "no internal field names leak");
  truthy(/\n- .*1\n/.test(text) || text.includes(": 1"), "created count is present");
  truthy(text.includes("2"), "verified count is present");
  truthy(text.includes("7"), "unknown count is present");
});

await test("an unknown outcome is never dressed up as work", () => {
  const text = renderReport(sample());
  truthy(text.includes("7"), "unknown row is visible");
  eq(text.includes("verified: 7"), false, "unknown is not counted as verified");
});

await test("pending questions and refusals are listed, not summarized away", () => {
  const text = renderReport(sample());
  truthy(text.includes("alice"), "pending author is named");
  truthy(text.includes("how are you?"), "pending body is quoted");
  truthy(text.includes("server-said-ok-but-not-visible"), "refusal reason is kept");
  truthy(text.includes("2"), "unreadable verifier lines are counted");
});

// "pending: 20" twenty ticks in a row reads as "the queue is exactly 20". The real
// size has to sit next to the shown count.
await test("the report states the full queue size next to the shown one", () => {
  const data = sample();
  data.pendingTotal = 37;
  const text = renderReport(data);
  truthy(
    text.includes("\u0432 \u043e\u0447\u0435\u0440\u0435\u0434\u0438 37, \u043f\u043e\u043a\u0430\u0437\u0430\u043d\u043e 1"),
    "the queue size is not the shown count",
  );
});

await test("an empty journal renders without throwing and says so", () => {
  const text = renderReport({ at: AT, from: AT, to: AT });
  truthy(text.includes("# "), "still a report");
  eq(renderReport({}).split(/\n\n/).filter((part) => part.startsWith("## ")).length, 7, "sections survive");
  truthy(text.includes("источник не прочитан"), "an unread source is named, not hidden");
});

// The report is read by a human agent that cannot see the network. Without
// traces of the last read an empty report looks exactly like a dead cache.
await test("the report proves the source was read", () => {
  const data = sample();
  data.liveness = livenessProof([{ id: "c-9", created_at: AT, seen_at: AT }]);
  const text = renderReport(data);
  truthy(text.includes("прочитано записей: 1"), "the number of rows read is shown");
  truthy(text.includes("c-9"), "the newest comment is named");
});

await test("an unknown creation time is said, not dated as 1970", () => {
  const data = sample();
  data.liveness = livenessProof([{ id: "c-9", seen_at: AT }]);
  const text = renderReport(data);
  truthy(text.includes("время неизвестно"), "the missing date is named");
  eq(text.includes("1970-01-01"), false, "no invented epoch date");
});

await test("a deferred answer is listed with its deadline", () => {
  const rows = [
    { id: "c-d", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT, seen_at: AT },
  ];
  const text = renderReport({ at: AT, from: AT - 1000, to: AT, work: projectWork(rows, AT - 1000, AT) });
  truthy(text.includes("пост p-1 / заблокированный родитель c-d"), "the deferred answer is named");
  truthy(text.includes("крайний срок"), "and it has a deadline");
});

// The parent the server refused lies under the depth cap. An agent that reads
// "reply to c-deep" repeats the very answer that was refused, and it is
// refused again. The recovery target is the top level of the thread.
await test("the report names the recovery target, not the blocked parent", () => {
  const rows = [
    { id: "c-deep", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT, seen_at: AT, resume_target: "c-root" },
  ];
  const text = renderReport({ at: AT, from: AT - 1000, to: AT, work: projectWork(rows, AT - 1000, AT) });
  truthy(text.includes("цель восстановления: верхний уровень треда, ответить на c-root"), "the top level of the thread is the target");
  eq(text.includes("ответить на c-deep"), false, "the refused parent is not a retry target");
});

await test("an overdue deferred answer says so and is counted apart", () => {
  const rows = [
    { id: "c-d", post_id: "p-1", status: COMMENT_STATUS.FAILED, reply_outcome: REPLY_OUTCOME.DEFERRED, replied_at: AT - 10 * 86_400_000, seen_at: AT },
  ];
  const work = projectWork(rows, 0, AT);
  eq(work.deferred.length, 0, "an overdue deferral is not a pending one");
  eq(work.overdue.length, 1, "it is counted as overdue");
  const text = renderReport({ at: AT, from: 0, to: AT, work });
  truthy(text.includes("просрочено"), "the overdue mark is there");
  truthy(text.includes("просрочено отложенных"), "and it has a metric of its own");
  truthy(text.includes("отложено до повтора на верхнем уровне: 0"), "an overdue deferral is not a pending one");
});

await test("a report without deferred answers says so", () => {
  const text = renderReport(sample());
  truthy(text.includes("отложенных ответов нет"), "an empty deferred list is stated");
});

// ---- удаления в процессе ------------------------------------------------------------
// Мягкое удаление применяется с задержкой: сервер подтвердил, а тело комментария
// ещё видно. Пока срок не вышел, ожидание видно всегда - иначе проверять потом
// некому, а удаление тихо забудется.
await test("a deletion in progress is listed with its deadline", () => {
  const rows = [
    {
      id: "c-d",
      post_id: "p-1",
      author: "opencodekz",
      body: "our old text",
      status: COMMENT_STATUS.SKIPPED,
      reply_outcome: REPLY_OUTCOME.DELETING,
      replied_at: AT,
      seen_at: AT,
    },
  ];
  const work = projectWork(rows, 0, AT);
  eq(work.deleting.length, 1, "the pending deletion is counted");
  eq(work.deletingOverdue.length, 0, "and it is not overdue yet");
  const text = renderReport({ at: AT, from: 0, to: AT, work });
  truthy(text.includes("## Удаления в процессе"), "the section is there");
  truthy(text.includes("пост p-1 / коммент c-d"), "the comment is named");
  truthy(text.includes("тело ещё видно"), "and the body is still visible");
  truthy(text.includes("крайний срок"), "and it has a deadline");
  truthy(text.includes("удалений в процессе: 1"), "the summary counts it");
});

await test("an overdue deletion says so and is counted apart", () => {
  const rows = [
    {
      id: "c-d",
      post_id: "p-1",
      author: "opencodekz",
      body: "our old text",
      status: COMMENT_STATUS.SKIPPED,
      reply_outcome: REPLY_OUTCOME.DELETING,
      replied_at: AT - 10 * 86_400_000,
      seen_at: AT,
    },
  ];
  const work = projectWork(rows, 0, AT);
  eq(work.deleting.length, 1, "it is still a wait");
  eq(work.deletingOverdue.length, 1, "and it is overdue");
  const text = renderReport({ at: AT, from: 0, to: AT, work });
  truthy(text.includes("просрочено"), "the overdue mark is there");
  truthy(text.includes("из них просрочено: 1"), "and it has a metric of its own");
});

await test("a failed deletion is not counted as a failed answer", () => {
  const rows = [
    {
      id: "c-d",
      post_id: "p-1",
      author: "opencodekz",
      body: "our old text",
      status: COMMENT_STATUS.FAILED,
      reply_outcome: REPLY_OUTCOME.DELETING,
      replied_at: AT,
      seen_at: AT,
    },
  ];
  const work = projectWork(rows, 0, AT);
  eq(work.failed.length, 0, "a failed deletion is not a failed answer");
  eq(work.deleteFailed.length, 1, "it is a failed deletion");
  const text = renderReport({ at: AT, from: 0, to: AT, work });
  truthy(text.includes("удаление не подтверждено"), "the report names it");
  truthy(text.includes("удалений не подтверждено (провал): 1"), "and it has a metric of its own");
});

await test("a report without pending deletions says so", () => {
  const text = renderReport(sample());
  truthy(text.includes("удалений в процессе нет"), "an empty deletion list is stated");
});

// ---- a report the PC agent cannot see is said out loud -------------------

await test("a note about the report lands in the refusal section", () => {
  const data = sample();
  data.refusals = [];
  data.unreadable = 0;
  data.notes = ["MCP_MOLTBOOK_REPORT_DIR is empty"];
  const text = renderReport(data);
  truthy(text.includes("MCP_MOLTBOOK_REPORT_DIR is empty"), "the note is printed");
  truthy(!text.includes("отказов не было"), "an empty refusals section is not claimed");
});

await test("without an external directory the report says nobody will read it", async () => {
  const file = journal([]);
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-report-").replace(/\//g, "/"));
  const before = process.env.MCP_MOLTBOOK_REPORT_DIR;
  delete process.env.MCP_MOLTBOOK_REPORT_DIR;
  try {
    const result = await moltbookReport({}, depsFor(file, [dir]));
    eq(result.notes.length, 1, "one honest note");
    truthy(result.notes[0].includes("MCP_MOLTBOOK_REPORT_DIR"), "the note names the env var");
    truthy(readFileSync(join4(dir, REPORT_NAME), "utf8").includes("MCP_MOLTBOOK_REPORT_DIR"),
      "the note is in the written file too");
  } finally {
    if (before === undefined) delete process.env.MCP_MOLTBOOK_REPORT_DIR;
    else process.env.MCP_MOLTBOOK_REPORT_DIR = before;
  }
});

await test("with an external directory there is nothing to complain about", async () => {
  const file = journal([]);
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-report-").replace(/\//g, "/"));
  const before = process.env.MCP_MOLTBOOK_REPORT_DIR;
  process.env.MCP_MOLTBOOK_REPORT_DIR = dir;
  try {
    const result = await moltbookReport({}, depsFor(file, [dir]));
    eq(result.notes.length, 0, "no note when the PC agent can read it");
  } finally {
    if (before === undefined) delete process.env.MCP_MOLTBOOK_REPORT_DIR;
    else process.env.MCP_MOLTBOOK_REPORT_DIR = before;
  }
});

// ---- writeReport --------------------------------------------------------

await test("the report is written atomically and leaves no temp file", () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-report-").replace(/\//g, "/"));
  const path = writeReport(dir, "hello\n");
  eq(path.replace(/\\/g, "/"), dir + "/" + REPORT_NAME);
  eq(readFileSync(path, "utf8"), "hello\n");
  eq(readdirSync(dir).filter((name) => name.indexOf(".tmp") >= 0).length, 0, "no temp leftovers");
});

await test("an unwritable directory is a refusal, not a crash", () => {
  eq(writeReport("", "hello\n"), null);
  // Родитель - обычный файл: mkdirSync обязан упасть на любой платформе.
  // Несуществующий путь был бы плохим выбором - его можно просто создать,
  // и на Windows /no/such/place создаётся без всякого прав.
  const blocker = mkdtempSync(join4(tmpdir(), "moltbook-block-").replace(/\//g, "/"));
  const asFile = join4(blocker, "blocker");
  writeFileSync(asFile, "not a directory\n");
  eq(writeReport(join4(asFile, "report"), "hello\n"), null);
});

await test("writing twice replaces the file instead of appending", () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-report-").replace(/\//g, "/"));
  writeReport(dir, "first\n");
  writeReport(dir, "second\n");
  eq(readFileSync(dir + "/" + REPORT_NAME, "utf8"), "second\n");
});

// ---- moltbookReport (real journal) --------------------------------------

function journal(rows) {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-db-").replace(/\//g, "/"));
  const file = dir + "/moltbook.db";
  const db = new DatabaseSync(file);
  db.exec(SCHEMA);
  for (const row of rows) {
    db.prepare(
      "INSERT INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
        " VALUES (?,?,?,?,?,?,?,'',?,?,?)",
    ).run(
      row.id,
      row.post_id,
      row.author || "",
      row.body || "",
      row.created_at || 0,
      row.status,
      row.our_reply_id === undefined ? null : row.our_reply_id,
      row.seen_at || 0,
      row.replied_at || 0,
      row.reply_outcome || "",
    );
  }
  db.close();
  return file;
}

function depsFor(file, dirs) {
  return {
    now: () => AT,
    openDb: () => ({ db: new DatabaseSync(file), path: file }),
    dirs: dirs,
    windowMs: 0,
    pendingLimit: 10,
  };
}

await test("the report is written and the counts come from the journal", async () => {
  const file = journal([
    posted({ id: "c-1", reply_outcome: REPLY_OUTCOME.CREATED }),
    posted({ id: "c-2", reply_outcome: REPLY_OUTCOME.VERIFIED }),
    posted({ id: "c-3", reply_outcome: REPLY_OUTCOME.REUSED }),
    { id: "q-1", post_id: "p-1", author: "alice", body: "why?", status: COMMENT_STATUS.NEW, seen_at: AT, replied_at: 0, reply_outcome: "" },
  ]);
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({}, depsFor(file, [dir]));
  eq(result.outcome, "written");
  eq(result.work.created, 1);
  eq(result.work.verified, 1);
  eq(result.work.reused, 1);
  eq(result.pending, 1);
  truthy(existsSync(dir + "/" + REPORT_NAME), "file is on disk");
  const text = readFileSync(dir + "/" + REPORT_NAME, "utf8");
  truthy(text.includes("alice"), "the pending question made it into the file");
});

await test("a missing journal is a refusal, not an empty report", async () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({}, { now: () => AT, openDb: () => null, dirs: [dir] });
  eq(result.outcome, "refused");
  eq(result.refused, "ledger-missing");
  eq(existsSync(dir + "/" + REPORT_NAME), false);
});

await test("an undeliverable directory is a refusal, not an empty report", async () => {
  const file = journal([]);
  const blocker = mkdtempSync(join4(tmpdir(), "moltbook-block-").replace(/\//g, "/"));
  const asFile = join4(blocker, "blocker");
  writeFileSync(asFile, "not a directory\n");
  const result = await moltbookReport({}, depsFor(file, ["", join4(asFile, "report")]));
  eq(result.outcome, "refused");
  eq(result.refused, "report-not-written");
});

await test("two directories both receive the report", async () => {
  const file = journal([posted({ reply_outcome: REPLY_OUTCOME.CREATED })]);
  const a = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const b = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({}, depsFor(file, [a, b]));
  eq(result.written.length, 2);
  truthy(existsSync(a + "/" + REPORT_NAME), "first copy");
  truthy(existsSync(b + "/" + REPORT_NAME), "second copy");
});

await test("the report is never built from the writer's own claims", async () => {
  // The same journal seen twice must give the same answer: that is what makes it
  // a projection rather than a tally.
  const file = journal([posted({ reply_outcome: REPLY_OUTCOME.CREATED })]);
  const dirA = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const dirB = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const first = await moltbookReport({}, depsFor(file, [dirA]));
  const second = await moltbookReport({}, depsFor(file, [dirB]));
  eq(first.work, second.work);
  eq(readFileSync(dirA + "/" + REPORT_NAME, "utf8"), readFileSync(dirB + "/" + REPORT_NAME, "utf8"));
});

await test("an explicit window keeps older work out of the report", async () => {
  const file = journal([posted({ id: "old", replied_at: OLD, reply_outcome: REPLY_OUTCOME.CREATED })]);
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({ since: AT - 3600_000 }, depsFor(file, [dir]));
  eq(result.work.created, 0);
  const wide = await moltbookReport({ since: AT - 30 * 86_400_000 }, depsFor(file, [dir]));
  eq(wide.work.created, 1);
});

await test("a broken statement is a refusal, never a half written file", async () => {
  const file = journal([]);
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const broken = {
    now: () => AT,
    openDb: () => ({
      db: {
        prepare: () => {
          throw new Error("no such table: comments");
        },
        close: () => {},
      },
      path: file,
    }),
    dirs: [dir],
  };
  const result = await moltbookReport({}, broken);
  eq(result.outcome, "refused");
  eq(existsSync(dir + "/" + REPORT_NAME), false);
});

await test("a pre-v5 schema without reply_outcome still produces a report", async () => {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-db-").replace(/\//g, "/"));
  const file = dir + "/moltbook.db";
  const db = new DatabaseSync(file);
  db.exec(`
    CREATE TABLE comments (
      id TEXT PRIMARY KEY, post_id TEXT NOT NULL, author TEXT NOT NULL DEFAULT '',
      body TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0,
      status TEXT NOT NULL, our_reply_id TEXT, summary_ru TEXT NOT NULL DEFAULT '',
      seen_at INTEGER NOT NULL, replied_at INTEGER NOT NULL DEFAULT 0
    );
  `);
  db.prepare(
    "INSERT INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at)" +
      " VALUES ('c-1','p-1','alice','hi',0,?,NULL,'',?,?)",
  ).run(COMMENT_STATUS.POSTED, AT, AT);
  db.close();
  const out = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({}, depsFor(file, [out]));
  eq(result.outcome, "written");
  eq(result.work.unknown, 1);
  eq(result.work.created, 0, "an unknown outcome is never counted as work");
});

// ---- 21. refusals the pre-commit review taught us ----
await test("a journal that is not there yet is a refusal, not a crash", async () => {
  // openLedger returns the TRUTHY object {db:null,path:""} when the file is
  // missing, so a guard that only checks `opened` never fires and the very
  // first run on a fresh install would die with a TypeError.
  const out = mkdtempSync(join4(tmpdir(), "moltbook-out-").replace(/\//g, "/"));
  const result = await moltbookReport({}, {
    now: () => AT,
    openDb: () => ({ db: null, path: "" }),
    dirs: [out],
    windowMs: 0,
    pendingLimit: 5,
  });
  eq(result.outcome, "refused");
  eq(result.refused, "ledger-missing");
});
// ---- 20. rows written by the old Kotlin ticker are visible too ----
// The Kotlin ledger stored CommentStatus.name, i.e. UPPERCASE. A
// comparison against the lowercase plugin constant silently dropped
// every one of them: the report said zero answers while the journal
// held fifty recorded ones.

test("an uppercase status from the old ticker still counts as posted", () => {
  const rows = [
    { id: "c-up", status: "POSTED", reply_outcome: "verified", replied_at: AT, our_reply_id: "r-up", post_id: "p-up" },
  ];
  const work = projectWork(rows, AT - 1000);
  eq(work.verified.length, 1, "an uppercase POSTED row is not invisible");
  eq(work.verified[0].id, "c-up");
  eq(work.created.length, 0);
});

test("an uppercase NEW row is still a question", () => {
  const rows = [
    { id: "q-up", status: "NEW", author: "someone-else", post_id: "p-up", body: "hi" },
    { id: "q-mine", status: "NEW", author: "OpenCodeKZ", post_id: "p-up", body: "mine" },
  ];
  const pending = pendingQuestions(rows, "opencodekz", 10);
  eq(pending.length, 1);
  eq(pending[0].commentId, "q-up");
});

test("statusIs ignores case and padding", () => {
  eq(statusIs("POSTED", "posted"), true);
  eq(statusIs(" posted ", "POSTED"), true);
  eq(statusIs("", "posted"), false);
  eq(statusIs(null, "posted"), false);
  eq(statusIs("posted", ""), false);
});

test("an uppercase POSTED row is never counted as reportable work twice", () => {
  const counted = countReportable([
    { status: "POSTED", reply_outcome: "created" },
    { status: "posted", reply_outcome: "created" },
  ]);
  eq(counted.reportable, 2, "both casings are real answers");
});

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}