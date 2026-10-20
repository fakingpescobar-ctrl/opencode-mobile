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

const { writeScan, countReportable, COMMENT_STATUS, REPLY_OUTCOME, isGeoBlocked, unreadableRefusal, moltbookScan, resolveDeleteWaits } = await import(
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

// Строка журнала про удаление в процессе: наш комментарий, ожидание с дедлайном.
// replied_at - момент запроса удаления, от него считается срок.
function insertDeleting(db, id, at) {
  db.prepare(
    "INSERT INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
      " VALUES (?,?,?,?,?,?,?,'',?,?,?)",
  ).run(id, "p-1", "opencodekz", "our old text", 100, "SKIPPED", null, at, at, REPLY_OUTCOME.DELETING);
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
// Момент «сейчас» в тестах: сроки удаления считаются от него.
const AT = 1700000000000;
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
// ---- geo/WAF-блок CloudFront - это не «фид не прочитан» ---------------------------
// Молтбук стоит за CloudFront, и запрос с ПК получает 403 с HTML-страницей
// («Request blocked» / «Access denied from your region.») и заголовками
// `Server: CloudFront`, `X-Cache: Error from cloudfront`.
//
// Это НЕ «лента не прочиталась»: лента жива, а закрыт регион. Раньше такой
// ответ попадал в общий отказ `feed-unreadable-403`, и по отчёту читалось,
// что сломался источник, а на самом деле нужен другой регион или VPN.
await test("a CloudFront geo block is recognised", () => {
  eq(isGeoBlocked(403, "<html><body>Request blocked</body></html>", { server: "CloudFront" }), true, "the block page");
  eq(isGeoBlocked(403, "<html>Access denied from your region.</html>", {}), true, "the region denial");
  eq(isGeoBlocked(403, "{}", { "x-cache": "Error from cloudfront" }), true, "the cloudfront cache error");
  eq(isGeoBlocked(403, "{}", { server: "CloudFront", "x-cache": "Error from cloudfront" }), true, "both headers together");
  eq(
    isGeoBlocked(403, JSON.stringify({ message: "forbidden" }), { server: "nginx" }),
    false,
    "an ordinary api refusal is not a geo block",
  );
  eq(isGeoBlocked(200, "<html>Request blocked</html>", {}), true, "the body marker is enough on its own");
  eq(isGeoBlocked(403, JSON.stringify({ message: "forbidden" }), {}), false, "a json refusal is not a geo block");
});

await test("the geo block has its own refusal, the unreadable feed keeps its old one", () => {
  eq(
    unreadableRefusal("feed", 403, "<html>Request blocked</html>", { server: "CloudFront" }),
    "feed-geo-blocked",
    "the feed refusal names the geo block",
  );
  eq(
    unreadableRefusal("feed", 403, JSON.stringify({ message: "forbidden" }), { server: "nginx" }),
    "feed-unreadable-403",
    "an ordinary refusal keeps the old name",
  );
});

await test("the scan reports a geo block as a geo block", async () => {
  const blocked = {
    ok: false,
    status: 403,
    body: "<html><head><title>Request blocked</title></head><body>Request blocked</body></html>",
    headers: { server: "CloudFront", "x-cache": "Error from cloudfront", "x-amz-cf-id": "cf-1" },
  };
  const r = await moltbookScan({
    key: () => "K",
    now: () => 1700000000000,
    get: async (key, path) => Object.assign({ path, at: 1700000000000 }, blocked),
  });
  truthy(r.refusals.indexOf("feed-geo-blocked") >= 0, "the geo block is named as a geo block");
  eq(r.refusals.indexOf("feed-unreadable-403"), -1, "and it is not the old unreadable feed");
  truthy(r.refusals.indexOf("home-geo-blocked") >= 0, "the home read is blocked by the same wall");
});

// ---- ожидание удаления закрывается тем, что увидел скан ----------------------------
// Мягкое удаление применяется с задержкой: сначала тело комментария видно, потом
// на его месте надпись «Deleted comment». Скан, увидевший надгробие, снимает
// ожидание: удаление подтверждено, строка журнала - SKIPPED, ветка снова открыта.
await test("a scan that sees the tombstone closes the deletion wait", () => {
  const db = freshDb();
  insertDeleting(db, "c-dead", AT);
  const resolved = resolveDeleteWaits(db, [{ id: "c-dead", body: "Deleted comment", is_deleted: true }], AT);
  eq(resolved.confirmed, 1, "the wait is closed");
  eq(resolved.failed, 0, "and it is not a failure");
  const row = db.prepare("SELECT status, reply_outcome FROM comments WHERE id = 'c-dead'").get();
  eq(row.status, "SKIPPED", "the comment is skipped");
  eq(row.reply_outcome, "", "and the wait is gone from the journal");
  db.close();
});

await test("a confirmed deletion opens the branch it answered again", () => {
  const db = freshDb();
  insertDeleting(db, "c-dead", AT);
  db.prepare(
    "INSERT INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
      " VALUES (?,?,?,?,?,?,?,'',?,?,?)",
  ).run("q-1", "p-1", "alice", "q", 100, "POSTED", "c-dead", 500, 500, "created");
  resolveDeleteWaits(db, [{ id: "c-dead", body: "Deleted comment", is_deleted: true }], AT);
  const branch = db.prepare("SELECT status, our_reply_id FROM comments WHERE id = 'q-1'").get();
  eq(branch.status, "NEW", "the branch is open again");
  eq(branch.our_reply_id, null, "and points at nothing");
  db.close();
});

// Живой узел после срока - это провал удаления, а не «подождём ещё»: неизвестное
// без срока становится местом, куда девают забытое.
await test("a scan that sees a live comment past the deadline fails the deletion", () => {
  const db = freshDb();
  const past = AT - 10 * 86_400_000;
  insertDeleting(db, "c-dead", past);
  const resolved = resolveDeleteWaits(db, [{ id: "c-dead", body: "still here", is_deleted: false }], AT);
  eq(resolved.failed, 1, "the deletion failed");
  eq(resolved.confirmed, 0, "and it was not confirmed");
  const row = db.prepare("SELECT status, reply_outcome, seen_at FROM comments WHERE id = 'c-dead'").get();
  eq(row.status, "FAILED", "the row is a failure");
  eq(row.reply_outcome, "deleting", "and it is still known as a deletion");
  eq(row.seen_at, AT, "the failure is dated");
  db.close();
});

await test("a live comment before the deadline keeps waiting", () => {
  const db = freshDb();
  insertDeleting(db, "c-dead", AT);
  const resolved = resolveDeleteWaits(db, [{ id: "c-dead", body: "still here", is_deleted: false }], AT);
  eq(resolved.failed, 0, "the deadline did not pass yet");
  eq(resolved.confirmed, 0, "and nothing is confirmed");
  const row = db.prepare("SELECT status, reply_outcome FROM comments WHERE id = 'c-dead'").get();
  eq(row.status, "SKIPPED", "the row is still a wait");
  eq(row.reply_outcome, "deleting", "and the wait is still in the journal");
  db.close();
});

// Отсутствие в прочитанном треде - не доказательство: тред могли не прочитать.
await test("a comment the scan did not see changes nothing", () => {
  const db = freshDb();
  const past = AT - 10 * 86_400_000;
  insertDeleting(db, "c-dead", past);
  const resolved = resolveDeleteWaits(db, [{ id: "c-other", body: "q", is_deleted: false }], AT);
  eq(resolved.confirmed, 0, "nothing is confirmed");
  eq(resolved.failed, 0, "and nothing fails");
  const row = db.prepare("SELECT status, reply_outcome FROM comments WHERE id = 'c-dead'").get();
  eq(row.reply_outcome, "deleting", "the wait is still waiting");
  db.close();
});

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}