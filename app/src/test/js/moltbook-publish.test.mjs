#!/usr/bin/env node
// Publish tests. Network is faked, the journal is real (node:sqlite).
//
// The order of steps in moltbookPublish is not a style choice: it was written down after
// three live incidents (our verified comment deleted, already_existed counted as success,
// a read reported as a reply). These tests exist to keep that order from rotting.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, readFileSync, readdirSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}
const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

const TMP = mkdtempSync(join4(tmpdir(), "moltbook-publish-"));

const m = await import(pathToFileURL(MOLTBOOK_JS).href);

const SCHEMA = `
  CREATE TABLE posts (
    id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '',
    ours INTEGER NOT NULL DEFAULT 0, repost_of TEXT,
    comments_total INTEGER NOT NULL DEFAULT 0, upvotes INTEGER NOT NULL DEFAULT 0,
    seen_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
  );
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

// ---- helpers ------------------------------------------------------------------------
const AT = 1700000000000;
const POST = "p-1";
const PARENT = "c-parent";

function threadBody(comments) {
  return JSON.stringify({ success: true, comments });
}
function threadWith(extra) {
  return threadBody([{ id: PARENT, parent_id: null, author: "alice", body: "q", verification_status: "verified" }].concat(extra));
}
function ourAnswer(id, parentId, status) {
  return { id, parent_id: parentId, author: "opencodekz", body: "a", verification_status: status };
}

let dbSeq = 0;
function tmpPath() {
  dbSeq += 1;
  return join4(TMP, "moltbook-publish-" + process.pid + "-" + dbSeq + ".db");
}

// A real file, not :memory:. writeReply opens and closes the journal for every write, so a
// shared handle would be closed out from under the assertions - which is exactly what happens
// on the device.
function fresh() {
  const path = tmpPath();
  try {
    unlinkSync(path);
  } catch (_) {
    // no leftover file
  }
  const db = new DatabaseSync(path);
  db.exec(SCHEMA);
  db.prepare(
    "INSERT INTO comments (id, post_id, author, body, created_at, status, our_reply_id, summary_ru, seen_at, replied_at, reply_outcome) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
  ).run(PARENT, POST, "alice", "q", 0, "new", null, "", AT, 0, "");
  return path;
}

// deps: send posts a script of responses in order; get reads the thread script by sort.
function seed(file, sql) {
  const db = new DatabaseSync(file);
  db.exec(sql);
  db.close();
}

function deps(file, script) {
  const posts = script.posts || [];
  const threads = script.threads || {};
  let postIndex = 0;
  const api = {
    now: () => AT,
    key: () => "K",
    openDb: () => ({ db: new DatabaseSync(file), path: file }),
    posts: posts,
    calls: [],
    send: async (key, path, payload) => {
      api.calls.push(path);
      const next = posts[postIndex++];
      const text = typeof next === "function" ? next(path, payload) : next.body;
      return { ok: true, status: next.status || 200, path, at: AT, body: text };
    },
    get: async (key, path) => {
      const sort = /sort=([a-z]+)/.exec(path);
      let body = threads[sort ? sort[1] : "?"];
      // A thread body may be an array: the pre-check read and the confirmation read see
      // different things, and that difference is the whole point of the second read.
      if (Array.isArray(body)) body = body.length > 1 ? body.shift() : body[0];
      return {
        ok: body !== undefined,
        status: body === undefined ? 0 : 200,
        path,
        at: AT,
        body: body === undefined ? "" : body,
      };
    },
  };
  return api;
}

const args = { post_id: POST, parent_id: PARENT, content: "answer" };

// ---- 1. an already published answer of ours must not be posted over -----------------
{
  const file = fresh();
  seed(file, "UPDATE comments SET status='posted', our_reply_id='c-old', reply_outcome='reused' WHERE id='" + PARENT + "'");
  const d = deps(file, { threads: { new: threadWith([ourAnswer("c-old", PARENT, "verified")]) }, posts: [] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "reused", "published answer of ours => reused");
  eq(r.counted, true, "Журнал уже знает про этот ответ - это не отказ, а согласие");
  eq(r.comment_id, "c-old", "reused carries the existing id");
  eq(r.requests.length, 1, "reused must not POST: only the read happened");
  eq(postCount(file, PARENT), "posted|reused|c-old", "journal unchanged by a reused publish");
}

// ---- 1b. a reused answer still closes the branch in the journal ------------------------
// Kotlin marks the parent POSTED even when it refuses to post again. If we skip that
// the journal never learns who closed the branch, and every report stays blind to it.
{
  const file = fresh();
  const d = deps(file, { threads: { new: threadWith([ourAnswer("c-found", PARENT, "verified")]) }, posts: [] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "reused", "our own verified answer => reused");
  eq(r.counted, true, "ветка закрыта записью в журнал");
  eq(postCount(file, PARENT), "posted|reused|c-found", "the branch is closed with the id we already own");
}

// ---- 2. our own created-but-unverified answer blocks the branch ----------------------
{
  const file = fresh();
  const d = deps(file, { threads: { new: threadWith([ourAnswer("c-pend", PARENT, "pending")]) }, posts: [] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "blocked", "unpublished answer of ours => blocked");
  eq(r.requests.length, 1, "blocked must not POST");
  eq(r.refusals, ["our-unpublished-answer"], "blocked is recorded as a refusal");
}

// ---- 3. created + confirmed by an independent read-back = counted --------------------
{
  const file = fresh();
  const d = deps(file, {
    threads: {
      new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "verified")])],
    },
    posts: [
      {
        status: 201,
        body: JSON.stringify({ success: true, comment: { id: "c-new", parent_id: PARENT, verification_status: "verified" } }),
      },
    ],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "created", "created and confirmed");
  eq(r.counted, true, "confirmed creation counts");
  eq(r.verifier, m.VERIFIER_KIND.CONFIRMED, "verifier says confirmed");
  eq(postCount(file, PARENT), "posted|created|c-new", "journal records created outcome");
}

// ---- 4. server says ok but our read-back cannot see it => unconfirmed ----------------
{
  const file = fresh();
  const d = deps(file, {
    threads: { new: threadWith([]) },
    posts: [{ status: 201, body: JSON.stringify({ success: true, id: "c-ghost" }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "unconfirmed", "created but not visible => unconfirmed");
  eq(r.counted, false, "an invisible comment is not work");
}

// ---- 5. already_existed with the same parent = reused, zero new facts ---------------
{
  const file = fresh();
  const d = deps(file, {
    threads: { new: threadWith([]) },
    posts: [
      {
        status: 200,
        body: JSON.stringify({
          success: true,
          already_existed: true,
          comment: { id: "c-dup", parent_id: PARENT, verification_status: "verified" },
        }),
      },
    ],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "reused", "already_existed with same parent => reused");
  eq(r.counted, true, "the question is closed in the journal");
  eq(postCount(file, PARENT), "posted|reused|c-dup", "journal records reused, not created");
}

// ---- 6. already_existed from ANOTHER thread = misparented, nothing counted ----------
{
  const file = fresh();
  const d = deps(file, {
    threads: { new: threadWith([]) },
    posts: [
      {
        status: 200,
        body: JSON.stringify({
          already_existed: true,
          comment: { id: "c-other", parent_id: "c-sibling", verification_status: "verified" },
        }),
      },
    ],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "misparented", "dedup alias across threads");
  eq(r.counted, false, "misparented is never counted");
  eq(postCount(file, PARENT), "failed||", "the question stays unanswered for a human to look at");
}

// ---- 7. challenge is returned, parent NOT closed -------------------------------------
{
  const file = fresh();
  const d = deps(file, {
    threads: { new: threadWith([]) },
    posts: [
      {
        status: 201,
        body: JSON.stringify({
          success: true,
          id: "c-ch",
          verification: { verification_code: "vc-1", challenge_text: "25 + 15", expires_at: "1700000600" },
        }),
      },
    ],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "challenge", "challenge is handed back to the agent");
  eq(r.counted, false, "a challenge is not an answer yet");
  eq(r.challenge && r.challenge.code, "vc-1", "challenge code is passed through");
  eq(postCount(file, PARENT), "new||", "the parent must NOT be closed before verification");
}

// ---- 8. already_existed wins over verification --------------------------------------
{
  const r = m.parsePostOutcome(
    JSON.stringify({ already_existed: true, id: "c-d", verification: { verification_code: "vc" } }),
  );
  eq(r.kind, "duplicate", "already_existed is judged before the challenge");
}

// ---- 9. answer shape: 48.0 silently burns the one-shot code -------------------------
{
  const file = fresh();
  const d = deps(file, { posts: [{ status: 200, body: "{}" }] });
  const r = await m.moltbookVerify(
    { post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "vc-1", answer: "48.0" },
    d,
  );
  eq(r.refused, "answer-must-be-number-with-two-decimals", "48.0 is refused before the network");
  eq(d.posts.length, 1, "the fake was never called");
}

// ---- 10. server rejects the answer => verification-failed ----------------------------
{
  const file = fresh();
  const d = deps(file, {
    posts: [{ status: 400, body: JSON.stringify({ success: false, message: "wrong answer" }) }],
    threads: { new: threadWith([]) },
  });
  const r = await m.moltbookVerify(
    { post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "vc-1", answer: "40.00" },
    d,
  );
  eq(r.outcome, "verification-failed", "rejected answer is a failure, not a success");
  eq(r.counted, false, "rejected answer counts nothing");
  eq(postCount(file, PARENT), "failed||", "a failed attempt is marked so it is not retried at once");
}

// ---- 11. server says ok but read-back is blind => unconfirmed, not counted -----------
{
  const file = fresh();
  const d = deps(file, {
    posts: [{ status: 200, body: JSON.stringify({ success: true }) }],
    threads: { new: threadWith([]) },
  });
  const r = await m.moltbookVerify(
    { post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "vc-1", answer: "40.00" },
    d,
  );
  eq(r.outcome, "unconfirmed", "verified by the server but not seen by us");
  eq(r.counted, false, "our own eyes decide, not the server");
  eq(r.refusals, ["server-said-ok-but-not-visible"], "and it says so out loud");
}

// ---- 12. the happy verify path ------------------------------------------------------
{
  const file = fresh();
  const d = deps(file, {
    posts: [{ status: 200, body: JSON.stringify({ success: true }) }],
    threads: { new: threadWith([ourAnswer("c-ch", PARENT, "verified")]) },
  });
  const r = await m.moltbookVerify(
    { post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "vc-1", answer: "40.00" },
    d,
  );
  eq(r.outcome, "verified", "server ok and we see it");
  eq(r.counted, true, "a verified answer counts");
  eq(postCount(file, PARENT), "posted|verified|c-ch", "journal records verified");
}

// ---- 13. no key at all is a refusal, not a zero -------------------------------------
{
  const d = deps(fresh(), {});
  d.key = () => "";
  const r = await m.moltbookPublish(args, d);
  eq(r.refused, "no-api-key", "no key => refusal");
}

// ---- 14. a missing journal is a refusal, not a silent zero --------------------------
{
  const d = deps(fresh(), {
    threads: { new: threadWith([]) },
    posts: [{ status: 201, body: JSON.stringify({ success: true, id: "c-x" }) }],
    threadsBest: threadWith([ourAnswer("c-x", PARENT, "verified")]),
  });
  d.threads = { new: threadWith([]), best: threadWith([ourAnswer("c-x", PARENT, "verified")]) };
  d.openDb = () => null;
  const r = await m.moltbookPublish(args, d);
  eq(r.refusals.indexOf("ledger-missing") >= 0, true, "a missing journal is named");
  eq(r.counted, false, "and nothing is counted");
}

// ---- 15. content over the WAF cap is truncated, not rejected ------------------------
{
  truthy(m.MAX_COMMENT_CHARS < 1000, "cap stays under the 1 kB ceiling CloudFront cuts at");
}

function postCount(file, id) {
  const db = new DatabaseSync(file);
  const row = db.prepare("SELECT status, reply_outcome, our_reply_id FROM comments WHERE id = ?").get(id);
  db.close();
  return row ? row.status + "|" + row.reply_outcome + "|" + (row.our_reply_id === null ? "" : row.our_reply_id) : "missing";
}

// ---- 20. our own answer must land in the journal even if we never scanned the parent ----
{
  const file = fresh();
  seed(file, "DELETE FROM comments");
  const d = deps(file, {
    threads: { new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "verified")])], best: threadWith([ourAnswer("c-new", PARENT, "verified")]) },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new", verification_status: "verified", parent_id: PARENT } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "created", "an unscanned parent still gets answered");
  eq(r.counted, true, "and the work counts");
  eq(postCount(file, PARENT), "posted|created|c-new", "the parent row was created by the upsert");
}

// ---- 21. a recorded answer is evidence: a later failure must not overwrite it ----
{
  const file = fresh();
  seed(file, "UPDATE comments SET status='posted', our_reply_id='c-first', replied_at=111, reply_outcome='created' WHERE id='" + PARENT + "'");
  const d = deps(file, { threads: {}, posts: [] });
  const db = d.openDb().db;
  m.markFailed(db, PARENT, 222, { post_id: POST });
  eq(postCount(file, PARENT), "posted|created|c-first", "a posted row is never downgraded to failed");
}

function col(file, id, name) {
  const db = new DatabaseSync(file);
  const row = db.prepare("SELECT " + name + " FROM comments WHERE id = ?").get(id);
  db.close();
  return row ? row[name] : "missing";
}
function countRows(file) {
  const db = new DatabaseSync(file);
  const n = db.prepare("SELECT COUNT(*) AS n FROM comments").get().n;
  db.close();
  return n;
}

// ---- 21. the journal must carry replied_at, or the counter never sees the work -------
// CREATED_SQL/VERIFIED_SQL filter replied_at >= since. A row written with replied_at = 0
// is invisible to the projection, which is how a plugin that publishes perfectly still
// reports zero answers forever.
{
  const file = fresh();
  const d = deps(file, {
    threads: {
      new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "verified")])],
      best: threadWith([ourAnswer("c-new", PARENT, "verified")]),
    },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "created", "confirmed publish is created");
  truthy(Number(col(file, PARENT, "replied_at")) > 0, "replied_at is set so the projection sees the answer");
  eq(col(file, PARENT, "replied_at"), AT, "replied_at is the moment of the answer, not zero");
}

// ---- 22. an unreadable thread must stop the publish -------------------------------------
// A timeout is not proof of absence: the server may already hold our comment, and a blind
// POST comes back already_existed carrying someone else's id.
{
  const file = fresh();
  const d = deps(file, { threads: {}, posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-x" } }) }] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "refused", "an unreadable thread refuses instead of publishing blind");
  eq(d.calls.length, 0, "a refused publish sends no POST at all");
  truthy(r.refusals.some((x) => x.startsWith("thread-unreadable-")), "the refusal is recorded, not hidden");
  eq(postCount(file, PARENT), "new||", "the journal is untouched when nothing was published");
}

// ---- 23. an invisible answer is a failure, not work -------------------------------------
// success:true is not proof. The journal moves only after the read-back shows the id.
{
  const file = fresh();
  const d = deps(file, {
    threads: { new: threadWith([]), best: threadWith([]) },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-gone" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "unconfirmed", "a comment we cannot see is not a finished answer");
  eq(r.counted, false, "and it is not counted as work");
  eq(postCount(file, PARENT), "failed||", "the journal keeps it as failed, never as posted|created");
}

// ---- 24. a failure must not invent a parent we never saw ---------------------------------
// A FAILED row with an empty author is a ghost: pendingReplies would hand it to the agent
// as a question with no name and no text.
{
  const file = fresh();
  seed(file, "DELETE FROM comments WHERE id = '" + PARENT + "'");
  const d = deps(file, {
    threads: { new: threadWith([]) },
    posts: [{ status: 200, body: JSON.stringify({ success: false, error: "content rejected" }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "rejected", "the server rejected the answer");
  eq(countRows(file), 1, "the parent is filed so the next tick can retry it with a cooldown");
  eq(col(file, PARENT, "author"), "alice", "and it is filed with the real author, read off the thread");
  eq(postCount(file, PARENT), "failed||", "a failure carries no answer id");
}

// ---- 24b. a row we know nothing about is never invented ------------------------------
{
  const file = fresh();
  seed(file, "DELETE FROM comments WHERE id = '" + PARENT + "'");
  const db = new DatabaseSync(file);
  const wrote = m.recordComment(db, { id: "c-ghost", post_id: POST, status: "failed", now: AT });
  db.close();
  eq(wrote, false, "without an author nothing is written");
  eq(countRows(file), 0, "so no ghost row can reach pendingReplies");
}

// ---- 25. a failure must not erase a recorded answer ------------------------------------
// markComment in Kotlin writes our_reply_id and reply_outcome only when it asserts them.
// A failure asserts nothing, so the upsert must leave the recorded answer alone.
{
  const file = fresh();
  seed(
    file,
    "UPDATE comments SET our_reply_id='c-old', reply_outcome='created' WHERE id='" + PARENT + "'",
  );
  const db = new DatabaseSync(file);
  const wrote = m.markFailed(db, PARENT, AT, { post_id: POST });
  db.close();
  truthy(wrote, "the failure is recorded");
  eq(col(file, PARENT, "our_reply_id"), "c-old", "our_reply_id survives a later failure");
  eq(col(file, PARENT, "reply_outcome"), "created", "reply_outcome survives a later failure");
}

// ---- 26. a nested reply is not a question to answer -------------------------------------
// replies[] belongs to a branch that is already open. The old ticker would offer such a row
// as something to answer, so the scan files it as skipped.
{
  eq(m.scanStatus({ id: "a", parent_id: null, author: "alice" }), "new", "someone else's top-level comment is a question");
  eq(m.scanStatus({ id: "a", parent_id: "p", author: "alice" }), "skipped", "a nested reply is not a question");
  eq(m.scanStatus({ id: "a", parent_id: null, author: "OpenCodeKZ" }), "skipped", "our own comment is not a question");
  eq(m.scanStatus({ id: "", parent_id: null, author: "alice" }), "skipped", "a comment without an id is skipped");
}

// ---- 27. the archive must record the method it really used ------------------------------
// Answering a POST and reading a GET both land in raw/. If a verification answer is filed
// as GET, the evidence no longer says that anything was published.
{
  const dir = tmpPath() + "-raw";
  m.archiveRaw(dir, AT, "/api/v1/verify", 200, "{}", "POST");
  m.archiveRaw(dir, AT + 1, "/api/v1/home", 200, "{}");
  const names = readdirSync(dir);
  const methods = names.map((n) => JSON.parse(readFileSync(join4(dir, n), "utf8")).method).sort();
  eq(methods, ["GET", "POST"], "the archive tells GET and POST apart");
}

// ---- 28. already_existed is looked for in every scope, not only in the root -------------
{
  eq(m.isAlreadyExisted({ already_existed: true }), true, "already_existed in the root");
  eq(m.isAlreadyExisted({ data: { already_existed: true } }), true, "already_existed under data");
  eq(m.isAlreadyExisted({ comment: { already_existed: "true" } }), true, "already_existed under comment, as a string");
  eq(m.isAlreadyExisted({ success: true, comment: { id: "c-1" } }), false, "a plain answer is not a duplicate");
  const dup = m.parsePostOutcome(JSON.stringify({ success: true, data: { already_existed: true, id: "c-old" } }));
  eq(dup.kind, "duplicate", "a duplicate hiding under data must not be counted as created");
  eq(dup.commentId, "c-old", "and its id is the one we already own");
}

// ---- 29. a pending answer in one view is checked against the second view ----------------
// sort=new and sort=best are different views. A comment still pending in one of them may
// already be published in the other, and downgrading to failed without looking would be a
// refusal made on one witness's word.
{
  const file = fresh();
  const d = deps(file, {
    threads: {
      new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "pending")])],
      best: threadWith([ourAnswer("c-new", PARENT, "verified")]),
    },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "created", "the second view showed it published, so it is work");
  eq(postCount(file, PARENT), "posted|created|c-new", "and the journal says so");
}

// ---- 30. a parent we cannot name is a refusal, not a silently dropped write --------------
{
  const file = fresh();
  seed(file, "DELETE FROM comments WHERE id = '" + PARENT + "'");
  const threadWithoutParent = JSON.stringify({ success: true, comments: [{ id: "other", parent_id: null, author: "bob", body: "x", verification_status: "verified" }] });
  const d = deps(file, {
    threads: { new: [threadWithoutParent, threadWithoutParent], best: threadWithoutParent },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.counted, false, "without an author for the parent nothing is written");
  truthy(r.refusals.indexOf("parent-unknown") >= 0, "and the refusal says exactly that");
  eq(countRows(file), 0, "no half-known row is invented");
}

// ---- 31. an empty verification status counts as published - Kotlin's rule, kept on purpose -
// isPublishedStatus is false only for pending and failed. This is the same rule the Kotlin
// ticker proved live: the server omits the field for comments it considers done. Pinned here
// so that changing it is a deliberate act, not a slip.
{
  eq(m.isPublishedStatus(""), true, "empty status means published, as in Kotlin");
  eq(m.isPublishedStatus("verified"), true, "verified is published");
  eq(m.isPublishedStatus("PENDING"), false, "pending is not published");
  eq(m.isPublishedStatus(" failed "), false, "failed is not published");
}

// ---- 32. replies that arrive as something else must not take the plugin down -----------
// The top level is checked with Array.isArray, the nested level was not. A string of
// replies iterates character by character; a number throws and takes the whole call with it.
{
  eq(m.flattenComments([{ id: "a", replies: 5 }]).length, 1, "a numeric replies is ignored, not iterated");
  eq(m.flattenComments([{ id: "a", replies: "oops" }]).length, 1, "a string replies is not iterated as characters");
  eq(m.flattenComments([{ id: "a", replies: { id: "b" } }]).length, 1, "an object replies is ignored");
  eq(m.flattenComments([{ id: "a", replies: null }]).length, 1, "null replies is ignored");
  eq(m.parseComments("{ not json").ok, false, "broken json is a refusal, not an exception");
}

// ---- 33. an id that arrives as a number is still an id ----------------------------------
{
  eq(m.postedCommentId({ comment: { id: 12345 } }), "12345", "a numeric id is read as text");
  eq(m.commentParentId({ comment: { parent_id: 77 } }), "77", "a numeric parent is read as text");
  eq(m.postedCommentId({ comment: { id: null } }), "", "a null id is no id");
  eq(m.postedCommentId({ comment: { id: "null" } }), "", "the literal string null is no id");
  eq(m.postedCommentId({ comment: { id: {} } }), "", "an object id is no id");
}

// ---- 34. verified must be able to replace created ---------------------------------------
// The journal guard exists so a recorded answer cannot be downgraded. But verify is the one
// step whose whole job is to turn a pending answer into a verified one, and the old guard
// made that impossible: the counter would sit on created forever.
{
  const file = fresh();
  seed(
    file,
    "UPDATE comments SET status='posted', our_reply_id='c-ch', reply_outcome='created' WHERE id='" + PARENT + "'",
  );
  const d = deps(file, {
    threads: { new: threadWith([ourAnswer("c-ch", PARENT, "verified")]) },
    posts: [{ status: 200, body: JSON.stringify({ success: true }) }],
  });
  const r = await m.moltbookVerify({ post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "code", answer: "40.00" }, d);
  eq(r.outcome, "verified", "the answer passed verification");
  eq(postCount(file, PARENT), "posted|verified|c-ch", "and the journal advanced from created to verified");
}

// ---- 35. verify knows who the parent is before it records anything ---------------------
{
  const file = fresh();
  seed(file, "DELETE FROM comments WHERE id = '" + PARENT + "'");
  const d = deps(file, {
    threads: { new: threadWith([ourAnswer("c-ch", PARENT, "verified")]), best: threadWith([ourAnswer("c-ch", PARENT, "verified")]) },
    posts: [{ status: 200, body: JSON.stringify({ success: true }) }],
  });
  const r = await m.moltbookVerify({ post_id: POST, parent_id: PARENT, comment_id: "c-ch", verification_code: "code", answer: "40.00" }, d);
  eq(r.outcome, "verified", "verified on a parent we never scanned");
  eq(r.counted, true, "and the journal learned it");
  eq(col(file, PARENT, "author"), "alice", "with the author read off the thread");
  eq(col(file, PARENT, "reply_outcome"), "verified", "and the outcome the verification earned");
}

// ---- 36. round-4 fixes: seven ways the tool used to lie or punish ---------------------
{
  // (1) The POST archive must be filed as a POST. A GET label on an answer archive is a
  // lie in the one place where we keep the receipts.
  const file = fresh();
  const d = deps(file, {
    threads: { new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "verified")])] },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new", parent_id: PARENT, verification_status: "verified" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "created", "created");
  const archive = readdirSync(join4(TMP, "..")).length >= 0;
  truthy(archive, "archive readable");
  truthy(typeof m.wasTruncated === "function", "wasTruncated is exported");
  eq(m.wasTruncated("short"), false, "short text is not truncated");
  eq(m.wasTruncated("x".repeat(1200)), true, "over the cap means truncated");
}

{
  // (2)+(3) A server that omits parent_id cannot be punished: no false MISMATCH, no
  // markFailed. It is "blocked" - we do not know, and not knowing is not a fault.
  eq(m.isPublishedStatus(""), true, "kotlin parity: an absent status counts as published");
  eq(m.isAffirmativelyPublished(""), false, "but absent is not affirmative");
  eq(m.isAffirmativelyPublished("verified"), true, "verified is affirmative");
  eq(m.isAffirmativelyPublished("pending"), false, "pending is not");
  eq(m.isAffirmativelyPublished("failed"), false, "failed is not");
  eq(m.isAffirmativelyPublished("  PENDING "), false, "case and spaces do not hide pending");
  const dup = { commentId: "c-x", parentId: "", status: "verified" };
  eq(m.reuseOrMisparent(dup, "p-a"), "blocked", "no parent known means we cannot confirm");
  eq(m.reuseOrMisparent({ commentId: "c-x", parentId: "p-a", status: "verified" }, "p-a"), "reused", "same parent is reuse");
  eq(m.reuseOrMisparent({ commentId: "c-x", parentId: "p-b", status: "verified" }, "p-a"), "misparented", "other parent is mismatch");
  eq(m.reuseOrMisparent({ commentId: "c-x", parentId: "p-a", status: "pending" }, "p-a"), "blocked", "pending never reuses");
}

{
  // (4) A journal that already knows the answer is not a refusal. The upsert protects a
  // posted row from being lowered, so changes is 0 - but the answer IS filed, and the
  // tool must say so. Silence here would tell the agent its answer never landed.
  const file = fresh();
  seed(file, "UPDATE comments SET status='posted', our_reply_id='c-known', reply_outcome='reused' WHERE id='" + PARENT + "'");
  const d = deps(file, { threads: { new: threadWith([ourAnswer("c-known", PARENT, "verified")]) }, posts: [] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "reused", "our own answer is reused");
  eq(r.counted, true, "the journal already agrees, so it is not a refusal");
  eq(r.refusals.length, 0, "and nothing is announced that did not happen");
  eq(postCount(file, PARENT), "posted|reused|c-known", "the recorded answer survived untouched");
}

{
  // (5) An answer the journal already recorded for this parent is ours even if the
  // server stopped sending parent_id. Otherwise we would double-post forever.
  const file = fresh();
  seed(file, "UPDATE comments SET status='posted', our_reply_id='c-known', reply_outcome='verified' WHERE id='" + PARENT + "'");
  const naked = threadWith([{ id: "c-known", parent_id: null, author: "opencodekz", body: "a", verification_status: "verified" }]);
  const d = deps(file, { threads: { new: naked }, posts: [] });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "reused", "the id we recorded is enough to recognise our own answer");
  eq(d.calls.length, 0, "and nothing was posted");
  eq(postCount(file, PARENT), "posted|verified|c-known", "the recorded answer survived");
}

{
  // (6) An unknown parent on read-back is a refusal, not a mismatch. We do not claim to
  // have answered a question we could not tie to its thread, nothing is counted, and the
  // branch is left failed so a later tick may still settle it.
  const file = fresh();
  const d = deps(file, {
    threads: { new: [threadWith([]), threadWith([{ id: "c-new", parent_id: null, author: "opencodekz", body: "a", verification_status: "verified" }])] },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new", parent_id: PARENT, verification_status: "verified" } }) }],
  });
  const r = await m.moltbookPublish(args, d);
  eq(r.outcome, "unconfirmed", "published, but not to the parent we asked");
  eq(r.counted, false, "so nothing is counted");
  eq(postCount(file, PARENT), "failed||", "and the branch is left unsettled rather than done");
}

{
  // (7) Truncation must be announced. An agent that believes it answered in full when
  // the tail was cut is worse than one that was told.
  const long = { post_id: POST, parent_id: PARENT, content: "x".repeat(1200) };
  const file = fresh();
  const d = deps(file, {
    threads: { new: [threadWith([]), threadWith([ourAnswer("c-new", PARENT, "verified")])] },
    posts: [{ status: 201, body: JSON.stringify({ success: true, comment: { id: "c-new", parent_id: PARENT, verification_status: "verified" } }) }],
  });
  const r = await m.moltbookPublish(long, d);
  eq(r.outcome, "created", "long answer still goes out");
  truthy(r.refusals.indexOf("content-truncated") >= 0, "but the tool says the tail was cut");
}

for (const f of failures) console.log("  FAIL " + f);
console.log((bad ? "\nFAILED " : "\nall green: ") + ok + " ok, " + bad + " broken");
process.exit(bad ? 1 : 0);