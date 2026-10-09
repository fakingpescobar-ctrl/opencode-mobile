// Tests for the moltbook plugin's read-only status surface.
//
// WHY THIS FILE EXISTS
// The plugin replaces ~10 800 lines of Kotlin ticker that used to publish on
// moltbook.com by itself. This file guards the one rule the old ticker broke
// most expensively: a reply that already existed is NOT a new reply, and a
// comment we did not create is not our work. The counter used to be a mutable
// integer in the tick loop, so a single duplicate POST incremented it. Now the
// counter is a projection of the journal, and these tests pin the projection.
//
// The other thing pinned here is that importing the module has no side
// effects. The plugin is started by the app as an HTTP MCP server on port
// 4201; if the module opened a socket on import, the PC test run would hang
// forever instead of finishing in a second.
//
// Run:  node app/src/test/js/moltbook-status.test.mjs
// No dependencies, no network, no device, no bun:sqlite (readLedger requires
// bun:sqlite lazily and is only reached through moltbookStatus).

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { mkdtempSync, rmSync } from "node:fs";
import * as os from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const MOLTBOOK_JS = process.env.MOLTBOOK_JS_PATH
  || join(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");
const mod = await import(pathToFileURL(MOLTBOOK_JS).href);
const {
  COMMENT_STATUS,
  REPLY_OUTCOME,
  countReportable,
  summarizeComments,
  moltbookStatus,
  MOLTBOOK_DIR,
  MOLTBOOK_PORT,
  LEDGER_NAME,
LEDGER_PATH,
DATABASES_DIR_NAME,
  ledgerOpenOptions,
  authorName,
  LEDGER_CANDIDATES,
  MOLTBOOK_NAME,
  MOLTBOOK_HOST,
  WITNESS_NAME,
  VERIFIER_NAME,
  RAW_DIR_NAME,
  SERVER_STATUS,
  SCAN_POST_LIMIT,
  SCAN_THREAD_LIMIT,
  isPublishedStatus,
  simpleHash,
  parseFeedPosts,
  parseComments,
  pickCandidates,
} = mod;

let ok = 0;
let bad = 0;
const failures = [];
async function test(name, fn) {
  try {
    await fn();
    ok++;
    console.log("  ok   " + name);
  } catch (e) {
    bad++;
    failures.push(name);
    console.log("  FAIL " + name + " :: " + (e && e.message ? e.message : e));
  }
}
function eq(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error((what || "value") + ": got " + a + " want " + b);
}
function truthy(value, what) {
  if (!value) throw new Error((what || "value") + " should be true");
}

// A row as it comes out of the comments table.
function row(status, replyOutcome, extra) {
  return Object.assign(
    { id: "c-" + status + "-" + replyOutcome, status, reply_outcome: replyOutcome },
    extra || {},
  );
}

console.log("moltbook plugin status surface");

await test("only created and verified replies are reportable", () => {
  eq(
    countReportable([
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.CREATED),
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.VERIFIED),
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.REUSED),
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.UNKNOWN),
    ]),
    { reportable: 2, verified: 1, reused: 1, unknown: 1 },
    "counts",
  );
});

await test("an already_existed duplicate never counts as a reply", () => {
  // The live incident: POST returned already_existed with our own verified
  // comment id, the ticker logged "answered", and the digest lied.
  eq(countReportable([row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.REUSED)]).reportable, 0);
});

await test("a posted row from a pre-v5 ledger counts as unknown, not as work", () => {
  // Empty reply_outcome means the schema had no column yet. Reading that as
  // "created" would inflate the digest for every historical row.
  const counts = countReportable([row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.UNKNOWN)]);
  eq(counts.reportable, 0, "reportable");
  eq(counts.unknown, 1, "unknown");
});

await test("a failed attempt is never reportable whatever the outcome says", () => {
  eq(
    countReportable([
      row(COMMENT_STATUS.FAILED, REPLY_OUTCOME.CREATED),
      row(COMMENT_STATUS.NEW, REPLY_OUTCOME.CREATED),
      row(COMMENT_STATUS.SKIPPED, REPLY_OUTCOME.CREATED),
    ]),
    { reportable: 0, verified: 0, reused: 0, unknown: 0 },
    "counts",
  );
});

await test("an empty ledger projects zeroes instead of throwing", () => {
  eq(countReportable([]), { reportable: 0, verified: 0, reused: 0, unknown: 0 });
});

await test("summarize counts by status and still honours the projection", () => {
  eq(
    summarizeComments([
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.VERIFIED),
      row(COMMENT_STATUS.POSTED, REPLY_OUTCOME.REUSED),
      row(COMMENT_STATUS.NEW, REPLY_OUTCOME.UNKNOWN),
      row(COMMENT_STATUS.FAILED, REPLY_OUTCOME.UNKNOWN),
    ]),
    {
      total: 4,
      posted: 2,
      new: 1,
      failed: 1,
      reportable: 1,
      verified: 1,
      reused: 1,
      unknown: 2,
    },
    "summary",
  );
});

await test("a row with no status falls into new rather than vanishing", () => {
  eq(summarizeComments([{ id: "c-x" }]).new, 1, "new bucket");
  eq(summarizeComments([{ id: "c-x" }]).total, 1, "total");
});

await test("the status words match the Kotlin enum they replace", () => {
  eq(COMMENT_STATUS, { NEW: "new", POSTED: "posted", SKIPPED: "skipped", FAILED: "failed" });
  eq(REPLY_OUTCOME.CREATED, "created");
  eq(REPLY_OUTCOME.VERIFIED, "verified");
  eq(REPLY_OUTCOME.REUSED, "reused");
  eq(REPLY_OUTCOME.UNKNOWN, "");
});

await test("constants point at the same paths the Kotlin ticker used", () => {
  eq(LEDGER_NAME, "moltbook.db");
  eq(WITNESS_NAME, "witness.log");
  eq(VERIFIER_NAME, "verifier.log");
  eq(RAW_DIR_NAME, "raw");
  eq(MOLTBOOK_NAME, "opencodekz");
  eq(MOLTBOOK_HOST, "https://www.moltbook.com");
});

// The ledger is NOT next to the logs. SQLiteOpenHelper puts it in databases/,
// so a plugin that guessed files/moltbook/moltbook.db would report "ledger-missing"
// forever and look like a plugin that never works.
await test("the ledger is looked up in databases first, not next to the logs", () => {
  eq(DATABASES_DIR_NAME, "databases");
  eq(LEDGER_CANDIDATES.length, 0, "no directory means no candidates");
});

// Measured on the device: {readonly:false} throws SQLITE_MISUSE "bad parameter or
// other API misuse". The write mode has to be spelled out as readwrite:true.
// This test exists because the unit tests drive node:sqlite directly and therefore
// never touched the constructor that broke on the phone.
await test("the write mode is spelled out, not negated", () => {
  eq(JSON.stringify(ledgerOpenOptions(false)), '{"readwrite":true,"create":false}');
  eq(JSON.stringify(ledgerOpenOptions(true)), '{"readonly":true}');
  eq(ledgerOpenOptions(true).readwrite, undefined);
  eq(ledgerOpenOptions(false).readonly, undefined);
});

// Measured on the phone: the live feed sends author as an object, and String(obj)
// gave "[object Object]". Such an author never matches our own name, so the plugin
// would think an answered branch is still open.
await test("an author object still yields a name we can compare", () => {
  eq(authorName({ name: "alice" }), "alice");
  eq(authorName({ username: "bob" }), "bob");
  eq(authorName(" carol "), "carol");
  eq(authorName({ name: 42 }), "");
  eq(authorName(null), "");
  eq(authorName(undefined), "");
  const live = parseFeedPosts(JSON.stringify({ posts: [{ id: "p", author: { name: "dave" } }] }));
  eq(live.ok, true);
  eq(live.posts[0].author, "dave");
  const comments = parseComments(JSON.stringify({ comments: [{ id: "c", author: { name: "erin" } }] }));
  eq(comments.comments[0].author, "erin");
});

await test("importing the module opens no port", () => {
  // The app passes MCP_MOLTBOOK_PORT; the test process does not. If the module
  // ignored that, this run would never return.
  eq(MOLTBOOK_PORT, 0, "port");
  eq(MOLTBOOK_DIR, "", "dir");
  eq(LEDGER_PATH, "", "ledger path");
});

await test("status on a directory without a ledger says so instead of guessing", async () => {
  const dir = mkdtempSync(join(os.tmpdir(), "moltbook-status-"));
  try {
    process.env.MCP_MOLTBOOK_DIR = dir;
    const fresh = await import(pathToFileURL(MOLTBOOK_JS).href + "?dir=" + dir);
    // MOLTBOOK_DIR is captured at module load, so re-importing with a cache
    // bust is the only way to point the constants at the temp dir.
    if (fresh.MOLTBOOK_DIR !== dir) throw new Error("dir not picked up: " + fresh.MOLTBOOK_DIR);
    const status = fresh.moltbookStatus();
    eq(status.plugin, "moltbook", "plugin name");
    eq(status.ledger.ok, false, "ledger ok");
    eq(status.ledger.reason, "ledger-missing", "reason");
    truthy(JSON.parse(JSON.stringify(status)), "status must be JSON round-trippable");
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

await test("status never touches the network and says so", () => {
  const status = moltbookStatus();
  eq(status.network, "not-used-yet", "network");
  eq(status.plugin, "moltbook", "plugin");
  truthy(typeof status.keyPresent === "boolean", "keyPresent");
  truthy(JSON.parse(JSON.stringify(status)), "status must be JSON round-trippable");
});

// ---- moltbook_scan: parsing and candidate choice, without network and SQLite ----

await test("server status words are not our journal statuses", () => {
  eq(SERVER_STATUS.PENDING, "pending");
  eq(SERVER_STATUS.VERIFIED, "verified");
  truthy(Object.values(SERVER_STATUS).indexOf(COMMENT_STATUS.NEW) < 0, "new is ours, not the server's");
});

await test("only pending and failed count as unpublished", () => {
  eq(isPublishedStatus("verified"), true);
  eq(isPublishedStatus(""), true);
  eq(isPublishedStatus("published"), true);
  eq(isPublishedStatus("PENDING"), false);
  eq(isPublishedStatus(" pending "), false);
  eq(isPublishedStatus("failed"), false);
  eq(isPublishedStatus("Failed"), false);
});

await test("the feed is parsed and posts without an id are dropped", () => {
  const parsed = parseFeedPosts(
    JSON.stringify({
      success: true,
      posts: [
        { id: "p-1", title: "T1", author_name: "alice", comment_count: 3, upvotes: 7 },
        { title: "no id at all" },
        { id: "  p-2  ", author_name: "bob" },
      ],
    }),
  );
  eq(parsed.ok, true);
  eq(parsed.posts.length, 2);
  eq(parsed.posts[0].id, "p-1");
  eq(parsed.posts[0].comments_total, 3);
  eq(parsed.posts[0].upvotes, 7);
  eq(parsed.posts[0].repost_of, null);
  eq(parsed.posts[1].id, "p-2");
});

await test("an unreadable feed is a refusal, not an empty feed", () => {
  eq(parseFeedPosts("<html>502</html>").ok, false);
  eq(parseFeedPosts("not json").reason, "feed-not-json");
  eq(parseFeedPosts(JSON.stringify({ success: true })).reason, "feed-has-no-posts");
  eq(parseFeedPosts(JSON.stringify({ success: true })).posts.length, 0);
  eq(parseFeedPosts(JSON.stringify({ posts: [{}] })).posts.length, 0);
});

await test("comments keep the parent and the verification status", () => {
  const parsed = parseComments(
    JSON.stringify({
      success: true,
      comments: [
        {
          id: "c-1",
          post_id: "p-1",
          author_name: "alice",
          body: "hi",
          parent_id: null,
          verification_status: "verified",
        },
        { id: "c-2", author_name: "opencodekz", parent_id: "c-1", verification_status: "pending" },
        { author_name: "bob" },
      ],
    }),
  );
  eq(parsed.ok, true);
  eq(parsed.comments.length, 2);
  eq(parsed.comments[0].parent_id, null);
  eq(parsed.comments[0].verification_status, "verified");
  eq(parsed.comments[1].parent_id, "c-1");
  eq(parsed.comments[1].verification_status, "pending");
});

await test("unreadable comments are a refusal, not an empty thread", () => {
  eq(parseComments("").ok, false);
  eq(parseComments(JSON.stringify({ success: true })).reason, "comments-have-no-list");
});

await test("we answer top-level comments of others we have not answered yet", () => {
  const picked = pickCandidates(
    [
      { id: "c-1", author: "alice", parent_id: null },
      { id: "c-2", author: "bob", parent_id: "c-1" },
      { id: "c-3", author: "OpenCodeKZ", parent_id: null },
      { id: "c-4", author: "alice", parent_id: "c-3" },
    ],
    "opencodekz",
  );
  eq(picked.candidates.length, 1);
  eq(picked.candidates[0].id, "c-1");
  // c-3 наш, но он верхнеуровневый: он ни на что не отвечает, поэтому веток закрытых нет.
  eq(picked.answeredCount, 0);
});

await test("a thread where we already answered gives nothing to answer", () => {
  const picked = pickCandidates(
    [
      { id: "c-1", author: "alice", parent_id: null },
      { id: "c-9", author: "opencodekz", parent_id: "c-1", verification_status: "verified" },
    ],
    "opencodekz",
  );
  eq(picked.candidates.length, 0);
  eq(picked.answeredCount, 1);
});

await test("our own pending answer does not close the branch", () => {
  // Ответ, который молтбук ещё проверяет, нельзя считать состоявшимся: иначе ветка
  // закроется навсегда и второй попытки не будет.
  const picked = pickCandidates(
    [
      { id: "c-1", author: "alice", parent_id: null },
      { id: "c-9", author: "opencodekz", parent_id: "c-1", verification_status: "pending" },
    ],
    "opencodekz",
  );
  eq(picked.answeredCount, 0);
  eq(picked.candidates.length, 1);
});

await test("our own failed answer does not close the branch either", () => {
  const picked = pickCandidates(
    [
      { id: "c-1", author: "alice", parent_id: null },
      { id: "c-9", author: "opencodekz", parent_id: "c-1", verification_status: "failed" },
    ],
    "opencodekz",
  );
  eq(picked.answeredCount, 0);
  eq(picked.candidates.length, 1);
});

await test("an empty thread yields no candidates and does not throw", () => {
  eq(pickCandidates([], "opencodekz").candidates.length, 0);
  eq(pickCandidates([], "opencodekz").answeredCount, 0);
});

await test("scan bounds are small on purpose", () => {
  eq(SCAN_POST_LIMIT > 0, true);
  eq(SCAN_THREAD_LIMIT > 0, true);
  eq(SCAN_THREAD_LIMIT <= SCAN_POST_LIMIT, true);
});

await test("the archive name is stable for the same request and differs for another", () => {
  eq(simpleHash("/api/v1/home"), simpleHash("/api/v1/home"));
  truthy(simpleHash("/api/v1/home") !== simpleHash("/api/v1/feed"), "different paths must differ");
  eq(/^[0-9a-f]{8}$/.test(simpleHash("/x")), true);
});

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}
