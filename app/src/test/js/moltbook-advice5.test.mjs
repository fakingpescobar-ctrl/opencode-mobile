#!/usr/bin/env node
// Advice §5: отдельные тесты новых инвариантов (19.11.2026).
// Запуск: node app/src/test/js/moltbook-advice5.test.mjs
// MOLTBOOK_JS_PATH нужен только локально, когда Bun отдаёт .js как CJS без named exports.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join as joinPath } from "node:path";
import { mkdtempSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { DatabaseSync } from "node:sqlite";

const HERE = dirname(fileURLToPath(import.meta.url));
const TMP = mkdtempSync(joinPath(tmpdir(), "moltbook-advice5-"));
// Логи теста не должны попадать в живой каталог плагина.
process.env.MCP_MOLTBOOK_DIR = TMP;

const TARGET = process.env.MOLTBOOK_JS_PATH ||
  joinPath(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");
const m = await import(pathToFileURL(TARGET).href);

let ok = 0;
let bad = 0;
const failures = [];
function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) ok += 1;
  else {
    bad += 1;
    failures.push((label || "eq") + ": actual=" + JSON.stringify(actual) + " expected=" + JSON.stringify(expected));
  }
}
function truthy(value, label) {
  if (value) ok += 1;
  else {
    bad += 1;
    failures.push("not true: " + label);
  }
}

// ---- 1. терминальный исход внешней блокировки --------------------------------------
{
  const r = m.externalBlockResult(["feed-geo-blocked"], 1000, "geo", { post_id: "p-1" });
  eq(r.outcome, "blocked_on_external_grant", "внешний блок — отдельный исход");
  eq(r.retry, false, "внешний блок не ретраится");
  eq(r.counted, false, "внешний блок не считается работой");
  eq(r.post_id, "p-1", "контекст сохранён");
}

// ---- 2. verifier.log понимает новый вид --------------------------------------------
{
  const line = m.verifierLine(m.VERIFIER_KIND.EXTERNAL_BLOCKED, 1000, "feed", "geo");
  const parsed = m.parseVerifierLog(line + "\n");
  eq(parsed.unreadable, 0, "строка внешнего блока не мусор");
  eq(parsed.records.length, 1, "одна запись");
  eq(parsed.records[0].kind, "external-blocked", "вид прочитан");
  truthy(m.isRefusal(parsed.records[0].kind), "в witness это отказ, но не провал work");
}

// ---- 3. provenance read-back --------------------------------------------------------
{
  const proof = m.readBackProof({
    at: 1000,
    observedAt: 1200,
    performed: true,
    expectedParentId: "c-parent",
    actualParentId: "c-parent",
    nonce: "reply-x:c-1",
  });
  eq(proof.version, m.READBACK_VERSION, "версия формата зафиксирована");
  eq(proof.source, m.READBACK_SOURCE, "источник — тред получателя");
  eq(proof.expected_parent_id, "c-parent", "ожидаемый родитель зафиксирован");
  eq(proof.actual_parent_id, "c-parent", "фактический родитель зафиксирован");
  eq(proof.parent_matches, true, "совпадение родителей видно");
  eq(proof.fresh, true, "свежий снимок в пределе возраста");
  const stale = m.readBackProof({ at: 1000, observedAt: 1000 + m.READBACK_MAX_AGE_MS + 1, performed: true });
  eq(stale.fresh, false, "старый снимок не выдаётся за свежий");
  const mismatch = m.readBackProof({ performed: true, expectedParentId: "c-1", actualParentId: "c-2" });
  eq(mismatch.parent_matches, false, "расхождение родителей видно");
  const notRead = m.readBackProof({ performed: false, expectedParentId: "c-1" });
  eq(notRead.parent_matches, null, "без перечитки нет выдуманного вердикта");
}

// ---- 4. сверка двух источников ------------------------------------------------------
{
  const cross = m.reportCrossCheck({
    work: { created: [{ replyId: "c-1" }], verified: [], reused: [] },
    observed: {
      records: [
        { kind: "confirmed", detail: "id=c-1 ok" },
        { kind: "confirmed", detail: "id=c-ghost ok" },
        { kind: "external-blocked", detail: "geo" },
      ],
    },
    identity: { match: false, actual: "a", expected: "b" },
  });
  eq(cross.missing_in_ledger, ["c-ghost"], "подтверждение без строки журнала найдено");
  eq(cross.missing_in_verifier, [], "лишних строк журнала нет");
  eq(cross.external_blocks, 1, "внешний блок учтён");
  eq(cross.plugin_sha_match, false, "откат плагина виден");
}

// ---- 5. publish: geo-блок при чтении треда -----------------------------------------
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
const AT = 1700000000000;
function freshDb() {
  const path = joinPath(TMP, "publish-" + process.pid + "-" + Math.random().toString(36).slice(2) + ".db");
  const db = new DatabaseSync(path);
  db.exec(SCHEMA);
  db.prepare(
    "INSERT INTO comments (id, post_id, author, body, created_at, status, our_reply_id, summary_ru, seen_at, replied_at, reply_outcome) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
  ).run("c-parent", "p-1", "alice", "q", 0, "NEW", null, "", AT, 0, "");
  db.close();
  return path;
}
{
  const file = freshDb();
  const calls = [];
  const d = {
    now: () => AT,
    key: () => "K",
    openDb: () => ({ db: new DatabaseSync(file), path: file }),
    get: async (key, path) => {
      calls.push(path);
      return {
        ok: false,
        status: 403,
        path,
        body: "<html>Request blocked. Access denied from your region.</html>",
        headers: { server: "CloudFront", "x-cache": "Error from cloudfront" },
      };
    },
    send: async () => {
      calls.push("POST");
      return { ok: true, status: 201, path: "POST", body: "{}" };
    },
  };
  const r = await m.moltbookPublish(
    { post_id: "p-1", parent_id: "c-parent", content: "ответ" },
    d,
  );
  eq(r.outcome, "blocked_on_external_grant", "publish отдаёт терминальный исход");
  eq(r.retry, false, "publish не просит повтора");
  eq(r.counted, false, "publish не записывает работу");
  truthy(!calls.includes("POST"), "POST не отправлен после geo-блока");
  eq(r.read_back.performed, false, "нет выдуманного read-back");
  const db = new DatabaseSync(file);
  const row = db.prepare("SELECT status FROM comments WHERE id='c-parent'").get();
  db.close();
  eq(row && row.status, "NEW", "вопрос остался живым в журнале");
  try {
    unlinkSync(file);
  } catch (_) {}
}

// ---- 6. scan: geo-блок ленты --------------------------------------------------------
{
  const d = {
    now: () => AT,
    key: () => "K",
    get: async (key, path) => {
      if (path === "/api/v1/home") {
        return {
          ok: true,
          status: 200,
          path,
          body: JSON.stringify({ karma: 1, unread: 0 }),
          headers: {},
        };
      }
      if (path.indexOf("/api/v1/feed") === 0) {
        return {
          ok: false,
          status: 403,
          path,
          body: "<html>Access denied from your region.</html>",
          headers: { server: "CloudFront", "x-cache": "Error from cloudfront" },
        };
      }
      return { ok: true, status: 200, path, body: JSON.stringify({ notifications: [] }), headers: {} };
    },
  };
  const r = await m.moltbookScan(d);
  truthy(r.external_block !== null, "скан показывает внешнюю блокировку");
  eq(r.external_block.outcome, "blocked_on_external_grant", "у скана тот же терминальный исход");
  eq(r.external_block.retry, false, "скан не просит ретрая");
  truthy(r.refusals.includes("feed-geo-blocked"), "причина сохранена и в отказах");
}

console.log("");
if (bad > 0) {
  console.log("FAILED: " + ok + " ok, " + bad + " broken");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("all green: " + ok + " ok, 0 broken");
}
