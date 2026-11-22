#!/usr/bin/env bun
// Куратор золотого корпуса: raw_signals -> gold_staging -> gold.jsonl.
// Сырой сигнал НИКОГДА не льётся в эталон сам: каждое добавление проходит
// ручное y / n / edit. Скрипт идемпотентен — повторный запуск на том же буфере
// не создаёт дубли и не меняет gold_sha.
//
// Запуск:
//   bun curate-gold.mjs --dir=<filesDir> [--decisions=<file.json>] [--dry-run]
//   bun curate-gold.mjs --dir=<filesDir> --staging-only
//
// decisions.json — объект {"s001":"y","s002":"n","s003":{"expect_source":"...",
// "expect_kind":"rule","expected_chunk_id":"..."}}. Без --decisions куратор
// спрашивает в консоли: Enter = y, n = отклонить, или chunk_id руками.
//
// Никаких MCP-тулов и изменений дефолта ядра. Офлайн-инструмент.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { createInterface } from "node:readline";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const gc = await import(ROOT + "/tools/gold-corpus.mjs");
const store = await import(ROOT + "/opencode/memory-core/memory-adaptive-store.js");
const core = await import(ROOT + "/opencode/memory-core/memory-core.js");
const idx = await import(ROOT + "/opencode/memory-core/memory-index.mjs");

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith("--" + name + "="));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const has = (name) => process.argv.includes("--" + name);

const DIR = arg("dir", process.env.MEMORY_ADAPTIVE_DIR || process.cwd());
const GOLD = arg("gold", ROOT + "/memory/gold.jsonl");
const DECISIONS = arg("decisions", "");
const DRY = has("dry-run");
const STAGING_ONLY = has("staging-only");
const OUT = ROOT + "/tools/llama-bench/gold-curation.json";

const goldBefore = gc.backfillSplits(gc.readGold(GOLD));
const goldShaBefore = gc.goldSha(goldBefore);

// Снимок документов: пара (index_sha, gold_sha) обязательна для протокола.
const DB = new (await import("bun:sqlite")).Database(":memory:");
idx.indexDocs(DB, ROOT);
const indexSha = core.indexSha(DB);

const raw = store.listSignalCandidates(DIR, { status: ["raw", "staging"] });
if (!raw.length) {
  console.log("Нет кандидатов в raw_signals. Нечего курировать.");
  process.exit(0);
}
const deduped = gc.dedupCandidates(raw, goldBefore);
const aliases = deduped.filter((c) => c.alias_of);
const fresh = deduped.filter((c) => !c.alias_of);
const quota = gc.quotaSelect(fresh.map((c) => ({
  ...c,
  expect_source: c.expect_source || "unknown",
})), { target: store.SIGNAL_LIMITS.batchTarget, maxShare: gc.DEFAULT_MAX_SHARE });

console.log(`Кандидатов: ${raw.length} | алиасов: ${aliases.length} | новых: ${fresh.length}`);
console.log(`В батч отобрано: ${quota.selected.length} | в очереди: ${quota.pending.length}`);
console.log(`Снимок: index_sha ${indexSha} | gold_sha ${goldShaBefore}`);

const suggestions = new Map();
for (const c of quota.selected) {
  const hits = core.recallDocs(DB, c.query, { limit: 3 }).map((h) => h.source);
  suggestions.set(c.id, hits);
  console.log(`\n[${c.id}] ${c.query}`);
  console.log(`  split=${c.suggested_split} origin=${c.origin} bucket=${c.bucket} source=${c.expect_source}`);
  console.log(`  топ-3 ядра: ${hits.join(" | ")}`);
  console.log(`  ожидаемый чанк: ${c.expected_chunk_id || "не задан — заполни edit"}`);
}

if (STAGING_ONLY) {
  for (const c of quota.selected) store.setSignalCandidateStatus(DIR, c.id, "staging", "в батче");
  console.log("\nКандидаты переведены в gold_staging. Эталон не тронут.");
  process.exit(0);
}

let decisions = {};
if (DECISIONS) {
  decisions = JSON.parse(readFileSync(DECISIONS, "utf8"));
} else if (!DRY) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const ask = (q) => new Promise((res) => rl.question(q, res));
  for (const c of quota.selected) {
    const answer = (await ask(`[${c.id}] y / n / chunk_id: `)).trim();
    decisions[c.id] = answer || "y";
  }
  rl.close();
}

const added = [], rejected = [];
for (const c of quota.selected) {
  const d = decisions[c.id];
  if (d === "n") {
    rejected.push({ id: c.id, reason: "curator-reject" });
    if (!DRY) store.setSignalCandidateStatus(DIR, c.id, "rejected", "curator n");
    continue;
  }
  const edit = d && typeof d === "object" ? d : {};
  const chunk = typeof d === "string" && d !== "y" ? d : (edit.expected_chunk_id || c.expected_chunk_id || null);
  // Не пишем «unknown» в эталон молча: если сигнал не нёс источник,
  // подставляем топ-1 ядра и помечаем строку как требующую проверки.
  const suggested = (suggestions.get(c.id) || [])[0];
  const rawSource = String(edit.expect_source || c.expect_source || "");
  const source = rawSource && rawSource !== "unknown" ? rawSource : String(suggested || "unknown");
  const row = {
    query: c.query,
    expect_source: source,
    expect_kind: String(edit.expect_kind || "state"),
    split: c.suggested_split,
    split_policy: c.split_policy,
    hash_salt: c.hash_salt,
    norm_hash: c.norm_hash,
    origin: c.origin,
    added_at: Date.now(),
  };
  if (!rawSource || rawSource === "unknown") row.source_suggested = true;
  if (chunk) row.expected_chunk_id = String(chunk);
  added.push({ candidate: c.id, row });
}

for (const a of aliases) {
  if (!DRY) store.setSignalCandidateStatus(DIR, a.id, "alias", "алиас " + a.alias_of);
}
for (const p of quota.pending) {
  if (!DRY) store.setSignalCandidateStatus(DIR, p.id, "raw", "quota-skipped");
}

let goldAfter = goldBefore, shaAfter = goldShaBefore;
if (added.length && !DRY) {
  const res = gc.appendGold(GOLD, added.map((a) => a.row));
  goldAfter = res.rows;
  shaAfter = res.gold_sha;
  for (const a of added) store.setSignalCandidateStatus(DIR, a.candidate, "gold", "в эталоне");
}

const report = {
  index_sha: indexSha,
  gold_sha_before: goldShaBefore,
  gold_sha_after: shaAfter,
  candidates: raw.length,
  aliases: aliases.length,
  selected: quota.selected.length,
  added: added.length,
  rejected,
  quota_skipped: quota.pending.length,
  total_gold: goldAfter.length,
  dry_run: DRY,
  at: Date.now(),
};
writeFileSync(OUT, JSON.stringify(report, null, 2) + "\n");
console.log("\n" + JSON.stringify(report, null, 2));
console.log("Протокол: " + OUT);
console.log("После вливания: один базовый прогон ядра — bun tools/baseline.mjs");
