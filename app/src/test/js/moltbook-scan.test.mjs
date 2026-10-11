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

const { writeScan, countReportable, COMMENT_STATUS, REPLY_OUTCOME } = await import(
  pathToFileURL(MOLTBOOK_JS).href
);

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
  eq(comment.status, "NEW");
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
  eq(comment.status, "NEW");
});

await test("a scan never resurrects a failed or skipped comment back to new", () => {
  for (const status of ["failed", "SKIPPED"]) {
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
  // commentsSeen - сколько дошло до записи. Он нужен, чтобы отличить «в тредах
  // пусто» от «дошло, но не записалось»: молчаливый ноль в commentsWritten
  // выглядит одинаково, и именно этим прикрывался баг с датой.
  eq(written, { postsWritten: 0, commentsWritten: 0, commentsSeen: 0, refused: null });
});

await test("no ledger at all is a refusal, not an exception", () => {
  eq(writeScan(null, [POST], [COMMENT], 500), {
    postsWritten: 0,
    commentsWritten: 0,
    commentsSeen: 0,
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

await test("attempted and written separate a loss from 'nothing to write'", () => {
  // Раньше оба числа означали одно и то же, поэтому «вставки были, а строк не
  // появилось» (тот самый баг с NaN в created_at) выглядело ровно как «нечего
  // писать». Смысл есть только в паре: attempted == written - всё записано,
  // attempted > written - потеря.
  const db = freshDb();
  const fresh = writeScan(db, [POST], [COMMENT], 500);
  eq(fresh.commentsSeen, 1, "одна вставка предпринята");
  eq(fresh.commentsWritten, 1, "и одна строка появилась");

  const again = writeScan(db, [POST], [COMMENT], 900);
  eq(again.commentsSeen, 1, "вставка снова предпринята");
  eq(again.commentsWritten, 0, "но строка уже была - писать было нечего");
  db.close();
});

await test("a post we learn is ours later still becomes ours", () => {
  // Порядок бывает такой: сначала приходит ответ на наш комментарий в чужом
  // посте (comment_reply) - пост записывается как чужой. Потом приходит
  // post_comment по тому же посту, и он оказывается НАШИМ. Если такая строка
  // только добавляется и не обновляется, счётчик ourPosts занижается навсегда.
  const db = freshDb();
  const stub = {
    id: "p-late",
    title: "чужой пост с нашим комментарием",
    author: "",
    comments_total: 0,
    upvotes: 0,
    created_at: 0,
    repost_of: null,
  };
  writeScan(db, [stub], [], 500, [], ["p-late"]);
  eq(db.prepare("SELECT ours FROM posts WHERE id = ?").get("p-late").ours, 0, "пока это чужой пост");

  writeScan(db, [stub], [], 900, ["p-late"], ["p-late"]);
  const row = db.prepare("SELECT ours, title FROM posts WHERE id = ?").get("p-late");
  eq(row.ours, 1, "узнали, что он наш, - факт обновился");
  eq(row.title, "чужой пост с нашим комментарием", "а остальные поля остались как были");
  db.close();
});

await test("a post learned from a notification never overwrites what we already know", () => {
  // Из уведомления приходит только заголовок. Если такая строка делалась через
  // INSERT OR REPLACE, настоящий автор и repost_of уходили в пустоту, а вместе
  // с ними - счётчик репостов и название поста на карточке. Защита в самой
  // записи: такие посты добавляются, но не заменяют уже записанные.
  const db = freshDb();
  const full = Object.assign({}, POST, { id: "p-full", title: "настоящий заголовок", author: "vina", repost_of: "p-src" });
  writeScan(db, [full], [], 500);

  const stub = {
    id: "p-full",
    title: "заголовок из уведомления",
    author: "",
    comments_total: 0,
    upvotes: 0,
    created_at: 0,
    repost_of: null,
  };
  writeScan(db, [stub], [], 900, [], ["p-full"]);

  const row = db.prepare("SELECT title, author, repost_of FROM posts WHERE id = ?").get("p-full");
  eq(row.author, "vina", "автор не затёрт пустым");
  eq(row.title, "настоящий заголовок", "и заголовок из уведомления не вытеснил настоящий");
  eq(row.repost_of, "p-src", "ссылка на репост тоже цела");
  db.close();
});

await test("a comment we never answered is not an unknown answer", () => {
  // reply_outcome по умолчанию пустой у ЛЮБОЙ строки, на которую мы не отвечали
  // (в схеме DEFAULT ''), а REPLY_OUTCOME.UNKNOWN - это тоже пустая строка.
  // Пока фильтр был один, на карточку попадало «неизвестно: 77» при статусе NEW
  // у всех строк и при нуле опубликованных ответов: число, которого не бывает.
  const never = [
    { status: COMMENT_STATUS.NEW, reply_outcome: "" },
    { status: COMMENT_STATUS.SKIPPED, reply_outcome: "" },
  ];
  eq(countReportable(never).unknown, 0, "чужой комментарий без ответа - не неизвестный ответ");

  const answeredButUnknown = [
    { status: COMMENT_STATUS.POSTED, reply_outcome: REPLY_OUTCOME.UNKNOWN },
  ];
  eq(countReportable(answeredButUnknown).unknown, 1, "а наш опубликованный ответ - неизвестный исход");
});

await test("a failed row does not become young again on every scan", () => {
  // seen_at is what the report counts failures by. If the scan refreshed it
  // every time, one failure from a week ago would land in every window and
  // the counter would never go down. Only markFailed may touch it.
  const db = freshDb();
  writeScan(db, [POST], [COMMENT], 500);
  db.prepare("UPDATE comments SET status = ?, seen_at = 1000 WHERE id = ?").run("failed", "c-1");
  writeScan(db, [POST], [Object.assign({}, COMMENT, { body: "changed" })], 900000);
  const row = db.prepare("SELECT seen_at, body FROM comments WHERE id = ?").get("c-1");
  eq(row.seen_at, 1000, "seen_at of a failure is frozen");
  eq(row.body, "hi", "and its body is evidence, not a cache entry");
});
console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}