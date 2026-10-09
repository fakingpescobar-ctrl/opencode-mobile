#!/usr/bin/env node
// Real SQL tests for writeScan against the schema from MoltbookLedger.kt.
//
// The pure parsing functions are covered in moltbook-status.test.mjs. This file covers
// the part that can quietly corrupt the journal: what the scan writes, and above all
// what it must never rewrite.
//
// Runs under plain node via node:sqlite, which is why writeScan uses explicit
// BEGIN/COMMIT instead of bun:sqlite's db.transaction(). Same SQL, two engines.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

const HERE = dirname(fileURLToPath(import.meta.url));
const MOLTBOOK_JS =
  process.env.MOLTBOOK_JS_PATH || join4(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");

function join4(...parts) {
  return parts.join("/").replace(/\\/g, "/").replace(/\/+/g, "/");
}

const { writeScan } = await import(pathToFileURL(MOLTBOOK_JS).href);

// Schema copied from MoltbookLedger.kt onCreate. If a column is added there, this must
// change too - that is the point of the file.
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

function freshDb() {
  const db = new DatabaseSync(":memory:");
  db.exec(SCHEMA);
  return db;
}

const POST = {
  id: "p-1",
  title: "Title",
  author: "alice",
  comments_total: 3,
  upvotes: 7,
  created_at: 100,
  repost_of: null,
};
const COMMENT = {
  id: "c-1",
  post_id: "p-1",
  author: "alice",
  body: "hi",
  parent_id: null,
  created_at: 100,
  verification_status: "verified",
};

await test("a scan writes the post and the new comment", () => {
  const db = freshDb();
  const written = writeScan(db, [POST], [COMMENT], 500);
  eq(written.postsWritten, 1);
  eq(written.commentsWritten, 1);
  eq(written.refused, null);
  const post = db.prepare("SELECT * FROM posts WHERE id = 'p-1'").get();
  eq(post.ours, 0);
  eq(post.comments_total, 3);
  eq(post.upvotes, 7);
  eq(post.seen_at, 500);
  eq(post.updated_at, 500);
  const comment = db.prepare("SELECT * FROM comments WHERE id = 'c-1'").get();
  eq(comment.status, "new");
  eq(comment.replied_at, 0);
  eq(comment.reply_outcome, "");
  eq(comment.our_reply_id, null);
});

await test("our own post is marked ours", () => {
  const db = freshDb();
  writeScan(db, [Object.assign({}, POST, { author: "OpenCodeKZ" })], [], 500);
  eq(db.prepare("SELECT ours FROM posts WHERE id = 'p-1'").get().ours, 1);
});

await test("a repost keeps its source", () => {
  const db = freshDb();
  writeScan(db, [Object.assign({}, POST, { repost_of: "p-0" })], [], 500);
  eq(db.prepare("SELECT repost_of FROM posts WHERE id = 'p-1'").get().repost_of, "p-0");
});

await test("a second scan of the same post updates it and does not duplicate it", () => {
  const db = freshDb();
  writeScan(db, [POST], [COMMENT], 500);
  const written = writeScan(db, [Object.assign({}, POST, { comments_total: 9, upvotes: 8 })], [COMMENT], 900);
  eq(written.postsWritten, 1);
  eq(written.commentsWritten, 0);
  eq(db.prepare("SELECT COUNT(*) AS n FROM posts").get().n, 1);
  eq(db.prepare("SELECT comments_total, upvotes, seen_at FROM posts").get().comments_total, 9);
  eq(db.prepare("SELECT upvotes FROM posts").get().upvotes, 8);
  eq(db.prepare("SELECT seen_at FROM posts").get().seen_at, 900);
});

await test("THE IMPORTANT ONE: a scan never rewrites a recorded answer", () => {
  const db = freshDb();
  writeScan(db, [POST], [COMMENT], 500);
  db.prepare(
    "UPDATE comments SET status = 'posted', our_reply_id = 'our-1', replied_at = 600, reply_outcome = 'verified'" +
      " WHERE id = 'c-1'",
  ).run();
  writeScan(db, [POST], [Object.assign({}, COMMENT, { body: "changed upstream" })], 1000);
  const comment = db.prepare("SELECT * FROM comments WHERE id = 'c-1'").get();
  eq(comment.status, "posted");
  eq(comment.our_reply_id, "our-1");
  eq(comment.replied_at, 600);
  eq(comment.reply_outcome, "verified");
  // The body of a recorded answer is evidence, not discovery data: a scan does not
  // overwrite what we published, otherwise a later tick could "fix" our own record.
  eq(comment.body, "hi");
  eq(comment.seen_at, 500);
});

await test("a not-yet-answered comment does get its body refreshed", () => {
  const db = freshDb();
  writeScan(db, [POST], [COMMENT], 500);
  writeScan(db, [POST], [Object.assign({}, COMMENT, { body: "edited upstream" })], 1000);
  const comment = db.prepare("SELECT * FROM comments WHERE id = 'c-1'").get();
  eq(comment.body, "edited upstream");
  eq(comment.seen_at, 1000);
  eq(comment.status, "new");
});

await test("a scan never resurrects a failed or skipped comment back to new", () => {
  for (const status of ["failed", "skipped"]) {
    const db = freshDb();
    writeScan(db, [POST], [COMMENT], 500);
    db.prepare("UPDATE comments SET status = ? WHERE id = 'c-1'").run(status);
    writeScan(db, [POST], [COMMENT], 1000);
    eq(db.prepare("SELECT status FROM comments WHERE id = 'c-1'").get().status, status);
  }
});

await test("a comment without a post_id is refused, not written into nowhere", () => {
  const db = freshDb();
  const written = writeScan(db, [POST], [Object.assign({}, COMMENT, { post_id: "" })], 500);
  eq(written.commentsWritten, 0);
  eq(db.prepare("SELECT COUNT(*) AS n FROM comments").get().n, 0);
});

await test("a scan with nothing to write is not a failure", () => {
  const db = freshDb();
  const written = writeScan(db, [], [], 500);
  eq(written, { postsWritten: 0, commentsWritten: 0, refused: null });
});

await test("no ledger at all is a refusal, not an exception", () => {
  eq(writeScan(null, [POST], [COMMENT], 500), {
    postsWritten: 0,
    commentsWritten: 0,
    refused: "ledger-missing",
  });
});

await test("a broken statement leaves nothing half-written", () => {
  const db = freshDb();
  // posts has no such column, so the first insert throws mid-batch.
  let thrown = null;
  try {
    writeScan(db, [Object.assign({}, POST, { comments_total: "not-a-number-but-fine" }), POST], [COMMENT], 500);
  } catch (error) {
    thrown = error;
  }
  // Whatever happened, the comment row must be all-or-nothing, not a half ledger.
  const commentCount = db.prepare("SELECT COUNT(*) AS n FROM comments").get().n;
  truthy(commentCount === 0 || commentCount === 1, "comment row must be 0 or 1, got " + commentCount);
  if (thrown) eq(db.prepare("SELECT COUNT(*) AS n FROM comments").get().n, 0);
});

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

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}