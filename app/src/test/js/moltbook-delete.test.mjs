#!/usr/bin/env node
// moltbook_delete tests. The network is faked, the journal is not touched at all.
//
// The tool exists because DELETE /comments/{id} lies: the server answers
// {"success":true}, the comment stays public, and a visible "Deleted comment"
// placeholder takes its place in the tree. So the only proof of a deletion is
// our own re-read of the thread - and these tests pin that order down.
//
// Style follows the sibling files: a tiny eq/truthy harness, English test
// names, Russian comments that explain WHY.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { DatabaseSync } from "node:sqlite";

const HERE = dirname(fileURLToPath(import.meta.url));
function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}
const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

const m = await import(pathToFileURL(MOLTBOOK_JS).href);

let ok = 0;
let bad = 0;
const failures = [];
function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) ok++;
  else {
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
const POST = "p-1";

function thread(comments) {
  return JSON.stringify({ success: true, comments });
}
function ourComment(id, status) {
  return { id, parent_id: null, author: "opencodekz", body: "a", verification_status: status };
}
// The node the server leaves behind after a soft delete: the body is gone, the
// node stays in the tree with a label instead.
function tombstone(id, over) {
  return Object.assign({ id, parent_id: null, author: "opencodekz", body: "a", verification_status: "pending" }, over || {});
}
function deleteCalls(api) {
  return api.calls.filter((call) => call.startsWith("DELETE"));
}
function getCalls(api) {
  return api.calls.filter((call) => call.startsWith("GET"));
}

// deps: get reads the scripted thread bodies in order, delete answers with a script.
function deps(threads, deleteResponse) {
  let index = 0;
  const api = {
    now: () => AT,
    key: () => "K",
    calls: [],
    get: async (key, path) => {
      api.calls.push("GET " + path);
      const body = threads[Math.min(index, threads.length - 1)];
      index += 1;
      return { ok: true, status: 200, path, at: AT, body: String(body === undefined ? "" : body) };
    },
    delete: async (key, path) => {
      api.calls.push("DELETE " + path);
      return Object.assign(
        { ok: true, status: 200, path, at: AT, body: JSON.stringify({ success: true }) },
        deleteResponse || {},
      );
    },
  };
  return api;
}

// ---- 1. a comment that is not there needs no DELETE ---------------------------------
await test("a comment that is not there needs no DELETE", async () => {
  const d = deps([thread([{ id: "c-other", author: "alice", body: "q" }])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-gone" }, d);
  eq(r.outcome, "absent", "the comment is already gone");
  truthy(String(r.reason || "").length > 0, "and the tool says so");
  eq(deleteCalls(d).length, 0, "no DELETE request was sent");
});

// ---- 2. a foreign comment is never deleted ------------------------------------------
await test("a foreign comment is never deleted", async () => {
  const d = deps([thread([{ id: "c-x", author: "alice", body: "q", verification_status: "pending" }])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-x" }, d);
  eq(r.outcome, "refused", "someone else's comment is not ours to delete");
  truthy(String(r.reason || "").indexOf("только свои") >= 0, "and the reason says why");
  eq(deleteCalls(d).length, 0, "no DELETE request was sent");
});

// ---- 3. a verified comment stays: readers have already seen it ----------------------
await test("a verified comment is not deleted", async () => {
  const d = deps([thread([ourComment("c-v", "verified")])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-v" }, d);
  eq(r.outcome, "refused", "a verified comment is visible to readers");
  truthy(String(r.reason || "").indexOf("читателям") >= 0, "and the reason says why");
  eq(deleteCalls(d).length, 0, "no DELETE request was sent");
});

// ---- 4. an unknown status is not a verified one -------------------------------------
// Пустой статус - это «не знаю», а не «проверено». Запрещать удаление по
// незнанию нельзя: иначе непроверенный комментарий удалить было бы нельзя.
await test("an unknown status does not block the deletion", async () => {
  const d = deps([thread([ourComment("c-u", "")]), thread([])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-u" }, d);
  eq(r.outcome, "deleted", "an absent status is not a verified status");
});

// ---- 5. success from the server is not a deletion until we read it again -------------
await test("success from the server is not a deletion until we read it again", async () => {
  const body = thread([ourComment("c-p", "pending")]);
  const d = deps([body, body]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "unconfirmed", "the comment is still visible after the DELETE");
  truthy(String(r.reason || "").indexOf("ещё виден") >= 0, "and the tool says what it saw");
  eq(typeof r.server_message, "string", "the server answer is attached, cut to 200 characters");
  eq(r.server_message.length <= 200, true, "the server answer is cut to 200 characters");
});

// ---- 6. a deletion is confirmed by the re-read of the thread ------------------------
await test("a deletion is confirmed by the re-read of the thread", async () => {
  const d = deps([thread([ourComment("c-p", "pending")]), thread([])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the comment is gone from the tree");
  eq(r.counted, false, "a deletion is not work");
  eq(deleteCalls(d).length, 1, "exactly one DELETE request");
  eq(deleteCalls(d)[0].indexOf("/api/v1/comments/c-p") > 0, true, "and it went to the comment");
  eq(getCalls(d).length, 2, "the thread was read before the DELETE and after it");
});

// ---- 7. a nested reply is found in the tree -----------------------------------------
// Ответы лежат в replies[] родителя. Плоский поиск по верхнему уровню не увидел бы
// вложенный комментарий и решил бы, что удалять нечего.
await test("a nested reply is found and can be deleted", async () => {
  const tree = thread([
    { id: "c-top", author: "alice", body: "q", replies: [ourComment("c-n", "pending")] },
  ]);
  const d = deps([tree, thread([])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-n" }, d);
  eq(r.outcome, "deleted", "the nested comment was found and deleted");
});

// ---- 8. a thread we cannot read after the DELETE is not a deletion ------------------
await test("a thread we cannot read after the DELETE is not a deletion", async () => {
  const d = deps([thread([ourComment("c-p", "pending")]), "{ not json"]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "unconfirmed", "no read-back means no confirmation");
  truthy(String(r.reason || "").indexOf("не подтверждено") >= 0, "and the tool says why");
});

// ---- 9. an unreadable thread refuses instead of deleting blind ----------------------
await test("an unreadable thread refuses instead of deleting blind", async () => {
  const d = deps(["{ not json"]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "refused", "an unreadable thread is a refusal");
  eq(deleteCalls(d).length, 0, "and nothing was deleted");
});

// ---- 10. no key and no ids are refusals, not deletions --------------------------------
await test("no key and no ids are refusals, not deletions", async () => {
  const withoutKey = deps([thread([])]);
  withoutKey.key = () => "";
  const refused = await m.moltbookDelete({ post_id: POST, comment_id: "c-x" }, withoutKey);
  eq(refused.refused, "no-api-key", "no key => refusal");

  const withoutIds = deps([thread([])]);
  const missing = await m.moltbookDelete({ post_id: "", comment_id: "" }, withoutIds);
  eq(missing.refused, "need-post-id-and-comment-id", "no ids => refusal");
  eq(deleteCalls(withoutIds).length, 0, "and nothing was deleted");
});

// ---- 11. the search helper sees the whole tree --------------------------------------
await test("the search helper sees the whole tree", async () => {
  const parsed = m.parseComments(
    thread([{ id: "c-top", author: "alice", body: "q", replies: [ourComment("c-n", "pending")] }]),
  );
  eq(m.findComment(parsed.comments, "c-n").id, "c-n", "a nested comment is found");
  eq(m.findComment(parsed.comments, "c-none"), null, "an absent comment is not found");
  eq(m.findComment(parsed.comments, ""), null, "an empty id finds nothing");
});

// ---- 12. the tool is registered under its own name -----------------------------------
await test("the deletion helper exists and does not lie about the method", async () => {
  eq(typeof m.deleteRaw, "function", "deleteRaw is exported");
  eq(typeof m.moltbookDelete, "function", "moltbookDelete is exported");
});

// ---- 13. a deletion is written to the journal ---------------------------------------
// Без записи в журнале удалённый комментарий навсегда оставался «отвечено»:
// строка в базе утверждала, что ответ жив, а ветка, на которую он отвечал,
// оставалась закрытой - и вопрос больше никто бы не закрыл.
const SCHEMA = `
  CREATE TABLE comments (
    id TEXT PRIMARY KEY, post_id TEXT NOT NULL, author TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL, our_reply_id TEXT, summary_ru TEXT NOT NULL DEFAULT '',
    seen_at INTEGER NOT NULL, replied_at INTEGER NOT NULL DEFAULT 0,
    reply_outcome TEXT NOT NULL DEFAULT ''
  );
`;

let dbSeq = 0;
function journal(rows) {
  const dir = mkdtempSync(join4(tmpdir(), "moltbook-delete-").replace(/\//g, "/"));
  const file = dir + "/moltbook-" + process.pid + "-" + dbSeq++ + ".db";
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

await test("a deletion is written to the journal", async () => {
  const file = journal([
    { id: "c-p", post_id: POST, author: "opencodekz", status: "SKIPPED" },
    { id: "q-1", post_id: POST, author: "alice", status: "POSTED", our_reply_id: "c-p", reply_outcome: "created" },
  ]);
  const d = deps([thread([ourComment("c-p", "pending")]), thread([])]);
  d.openDb = () => ({ db: new DatabaseSync(file), path: file });
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the comment is gone from the tree");
  const db = new DatabaseSync(file);
  const own = db.prepare("SELECT status, reply_outcome FROM comments WHERE id = ?").get("c-p");
  const branch = db.prepare("SELECT status, our_reply_id, reply_outcome FROM comments WHERE id = ?").get("q-1");
  db.close();
  eq(own.status, "SKIPPED", "the deleted comment waits for nothing");
  eq(own.reply_outcome, "", "and it is not work any more");
  eq(branch.status, "NEW", "the branch it answered is open again");
  eq(branch.our_reply_id, null, "and it points at nothing");
});

await test("a deletion the journal never heard of is not a refusal", async () => {
  const file = journal([{ id: "q-1", post_id: POST, author: "alice", status: "NEW" }]);
  const d = deps([thread([ourComment("c-p", "pending")]), thread([])]);
  d.openDb = () => ({ db: new DatabaseSync(file), path: file });
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the comment is gone from the tree");
  eq(r.refusals.indexOf("journal-not-recorded"), -1, "and the journal had nothing to correct");
});

// ---- 14. a tombstone is a tombstone -----------------------------------------
// The server deletes SOFTLY: the node stays in the tree and only the body is
// replaced by a label. Both signs are in the answer, and neither of them is the
// verification status - the status of a deleted comment is kept as it was.
await test("a tombstone is recognised by its flag and by its label", () => {
  eq(m.isTombstone({ is_deleted: true }), true, "the flag is a tombstone");
  eq(m.isTombstone({ content: "Deleted comment" }), true, "the label is a tombstone");
  eq(m.isTombstone({ content: "  deleted COMMENT  " }), true, "the label is read without regard to case and spaces");
  eq(m.isTombstone({ body: "Deleted comment" }), true, "the label in the body is a tombstone");
  eq(m.isTombstone({ body: "a normal comment", verification_status: "verified" }), false, "a live comment is not a tombstone");
  eq(m.isTombstone({ is_deleted: false, content: "hello" }), false, "a live comment with a false flag is not either");
  eq(m.isTombstone({ is_deleted: true, verification_status: "verified" }), true, "the status of a tombstone does not matter");
  eq(m.isTombstone(null), false, "nothing is not a tombstone");
});

// ---- 15. a soft delete is a confirmed delete --------------------------------
// Молтбук удаляет МЯГКО: узел комментария остаётся в дереве, и вместо тела в
// нём «Deleted comment». Раньше плагин искал узел по id и, найдя его, объявлял
// удаление неподтверждённым: на живом API тул не срабатывал НИКОГДА.
await test("a soft delete is a confirmed delete", async () => {
  const d = deps([thread([ourComment("c-p", "pending")]), thread([tombstone("c-p", { content: "Deleted comment" })])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the label left in the tree is a confirmed deletion");
  eq(r.soft_delete, true, "and the tool says it was a soft delete");
  eq(r.refusals.indexOf("delete-not-confirmed"), -1, "a label is not a failed confirmation");
  truthy(String(r.reason || "").indexOf("Deleted comment") >= 0, "the reason names the label that is left");
});

await test("a soft delete is recognised by the flag alone", async () => {
  const d = deps([thread([ourComment("c-p", "pending")]), thread([tombstone("c-p", { is_deleted: true, content: "our old text" })])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the flag alone is a confirmed deletion");
  eq(r.soft_delete, true, "and it was a soft delete");
});

await test("a live node after the DELETE is still unconfirmed", async () => {
  const d = deps([thread([ourComment("c-p", "pending")]), thread([ourComment("c-p", "pending")])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "unconfirmed", "a live comment was not deleted");
  truthy(r.refusals.indexOf("delete-not-confirmed") >= 0, "and the tool says the deletion was not confirmed");
});

// ---- 16. an already deleted comment needs no DELETE -------------------------
await test("an already deleted comment needs no DELETE", async () => {
  const d = deps([thread([tombstone("c-p", { content: "Deleted comment" })])]);
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "absent", "the comment is already deleted");
  eq(deleteCalls(d).length, 0, "no DELETE request was sent");
  truthy(String(r.reason || "").indexOf("уже помечен удалённым") >= 0, "and the tool says why");
});

// ---- 17. a soft delete is written to the journal too -------------------------
// Подтверждённое удаление - факт журнала, и мягкое удаление подтверждено так же,
// как жёсткое: комментария больше нет, ветка снова открыта.
await test("a soft delete is written to the journal too", async () => {
  const file = journal([
    { id: "c-p", post_id: POST, author: "opencodekz", status: "SKIPPED" },
    { id: "q-1", post_id: POST, author: "alice", status: "POSTED", our_reply_id: "c-p", reply_outcome: "created" },
  ]);
  const d = deps([thread([ourComment("c-p", "pending")]), thread([tombstone("c-p", { content: "Deleted comment" })])]);
  d.openDb = () => ({ db: new DatabaseSync(file), path: file });
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "deleted", "the label left in the tree is a confirmed deletion");
  const db = new DatabaseSync(file);
  const own = db.prepare("SELECT status FROM comments WHERE id = ?").get("c-p");
  const branch = db.prepare("SELECT status, our_reply_id FROM comments WHERE id = ?").get("q-1");
  db.close();
  eq(own.status, "SKIPPED", "the deleted comment waits for nothing");
  eq(branch.status, "NEW", "the branch it answered is open again");
  eq(branch.our_reply_id, null, "and it points at nothing");
});

// ---- 18. «Comment not found» - комментария нет, а не отказ ------------------------
// Живая проба 20.10.2026: удаление несуществующего комментария отвечает 403 с
// телом {"message":"Comment not found"} - не 404, как ждёт человек, и не
// {"success":false}, как отвечает платформа на другие отказы.
//
// Это факт «комментария нет», а не отказ: исход absent, и повторный DELETE
// не нужен - он точно так же скажет, что комментария нет, и только зря съест
// место в лимите записи (10 POST/DELETE в минуту).
await test("a 403 'Comment not found' means the comment is absent", async () => {
  const d = deps(
    [thread([ourComment("c-p", "pending")])],
    { ok: false, status: 403, body: JSON.stringify({ message: "Comment not found" }) },
  );
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "absent", "the comment is not there - that is not a refusal");
  eq(r.refused, undefined, "and the tool does not call it a refusal");
  eq(deleteCalls(d).length, 1, "the DELETE was sent once and not repeated");
  truthy(String(r.reason || "").indexOf("Comment not found") >= 0, "and the tool repeats what the server said");
});

// ---- 19. другой 403 - это не «комментария нет» -------------------------------------
// Отказ, которого мы не знаем, нельзя читать как доказательство отсутствия:
// комментарий может быть на месте, и удаление просто не прошло.
await test("another 403 is not a proof that the comment is gone", async () => {
  const body = thread([ourComment("c-p", "pending")]);
  const d = deps([body, body], { ok: false, status: 403, body: JSON.stringify({ message: "Forbidden" }) });
  const r = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, d);
  eq(r.outcome, "unconfirmed", "an unknown refusal is not a missing comment");
  eq(deleteCalls(d).length, 1, "the DELETE was sent once");
});

// ---- 20. два других исхода удаления не сломались -----------------------------------
// Регрессия: живой узел после DELETE - это unconfirmed, надгробие - подтверждённое
// мягкое удаление. Новая ветка «Comment not found» не должна была их сдвинуть.
await test("the other two delete outcomes are unchanged", async () => {
  const live = deps([thread([ourComment("c-p", "pending")]), thread([ourComment("c-p", "pending")])]);
  const liveResult = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, live);
  eq(liveResult.outcome, "unconfirmed", "a live node after the DELETE is not a deletion");

  const soft = deps([
    thread([ourComment("c-p", "pending")]),
    thread([tombstone("c-p", { content: "Deleted comment" })]),
  ]);
  const softResult = await m.moltbookDelete({ post_id: POST, comment_id: "c-p" }, soft);
  eq(softResult.outcome, "deleted", "a label left in the tree is a confirmed deletion");
  eq(softResult.soft_delete, true, "and the tool says it was a soft delete");
});

// ---- 21. тело ответа узнаётся по содержимому, а не по догадке ----------------------
await test("the 'comment not found' answer is recognised by its body", () => {
  eq(m.isCommentNotFound(403, JSON.stringify({ message: "Comment not found" })), true, "the measured answer");
  eq(
    m.isCommentNotFound(403, JSON.stringify({ message: "  comment   NOT found " })),
    true,
    "case and extra spaces do not hide it",
  );
  eq(m.isCommentNotFound(403, "<html>Request blocked</html>"), false, "a geo block is not a missing comment");
  eq(
    m.isCommentNotFound(400, JSON.stringify({ message: "Comment not found" })),
    false,
    "only the measured status counts",
  );
  eq(m.isCommentNotFound(403, JSON.stringify({ message: "Forbidden" })), false, "another refusal is not a missing comment");
  eq(m.isCommentNotFound(403, "{ not json"), false, "a broken body is not a missing comment");
});

for (const f of failures) console.log("  FAIL " + f);
console.log((bad ? "\nFAILED " : "\nall green: ") + ok + " ok, " + bad + " broken");
process.exit(bad ? 1 : 0);
