#!/usr/bin/env bun
// Сравнение режимов ядра на собственном gold: legacy (эталон/fallback),
// v4 (utility + evidence + observations), rrf (fusion рангов).
import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const HERE = dirname(fileURLToPath(import.meta.url));
const DOCS = join(HERE, "..");
const idx = await import(join(HERE, "memory-core/memory-index.mjs"));
const core = await import(join(HERE, "memory-core/memory-core.js"));

const gold = readFileSync(join(DOCS, "memory/gold.jsonl"), "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
const DB = new Database(":memory:");
idx.indexDocs(DB, DOCS);

const rows = [];
for (const mode of core.MODES) {
  let hit = 0, rr = 0;
  const t0 = performance.now();
  for (const g of gold) {
    const hits = core.recallDocs(DB, g.query, { limit: 5, mode });
    const pos = hits.findIndex((h) => h.source === g.expect_source);
    if (pos >= 0) { hit++; rr += 1 / (pos + 1); }
  }
  const ms = performance.now() - t0;
  rows.push({ mode, recall5: hit / gold.length, mrr: rr / gold.length,
    ms_total: Math.round(ms), ms_per_query: Math.round((ms / gold.length) * 100) / 100 });
}
console.log("\nРЕЖИМ      RECALL@5   MRR      мс/запрос");
for (const r of rows) {
  console.log(r.mode.padEnd(10), r.recall5.toFixed(3).padStart(7), r.mrr.toFixed(3).padStart(8), String(r.ms_per_query).padStart(10));
}
const base = rows.find((r) => r.mode === "legacy");
for (const r of rows.filter((x) => x.mode !== "legacy")) {
  const delta = r.recall5 - base.recall5;
  console.log(`\n${r.mode} vs legacy: ${delta >= 0 ? "+" : ""}${(delta * 100).toFixed(1)} п.п. recall@5`);
}

// v4 after credit/blame: кормим реальными исходами по gold и смотрим,
// даёт ли выученная полезность прирост без RIGHT-сдвига legacy.
let fed = 0;
for (const g of gold) {
  const cand = core.recallDocs(DB, g.query, { limit: 20, mode: "v4" });
  const good = cand.find((h) => h.source === g.expect_source);
  if (good) {
    core.learnOutcome(DB, g.query, [good.id], "success", 1, "gold-positive");
    fed++;
  }
}
let hit = 0, rr = 0;
for (const g of gold) {
  const hits = core.recallDocs(DB, g.query, { limit: 5, mode: "v4" });
  const pos = hits.findIndex((h) => h.source === g.expect_source);
  if (pos >= 0) { hit++; rr += 1 / (pos + 1); }
}
console.log(`\nv4 после ${fed} исходов: recall@5=${(hit / gold.length).toFixed(3)} mrr=${(rr / gold.length).toFixed(3)}`);
DB.close();
