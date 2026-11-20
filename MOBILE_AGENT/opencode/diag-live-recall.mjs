#!/usr/bin/env bun
// Разбор промахов recall@5 на ЖИВОЙ БД приложения (read-only).
// Показывает, какие gold-вопросы перестали попадать и почему.
import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..");
const DBPATH = join(ROOT, "..", "opencode", "opencode-home", ".memory", "memory.sqlite");
const GOLD = join(ROOT, "memory", "gold.jsonl");

const db = new Database(DBPATH, { readonly: true });
const core = await import(join(HERE, "memory-core", "memory-core.js"));
const gold = readFileSync(GOLD, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
let hit = 0;
const misses = [];
for (const g of gold) {
  const rows = core.recallDocs(db, g.query, { limit: 5 });
  const sources = rows.map((r) => r.source);
  const ok = sources.includes(g.expect_source);
  if (ok) hit++;
  else misses.push({ id: g.id, q: g.query, expect: g.expect_source, got: sources,
    expect_kind: g.expect_kind });
}
console.log("recall@5 = " + (hit / gold.length).toFixed(3) + " (" + hit + "/" + gold.length + ")");
console.log("промахи:");
for (const m of misses) {
  console.log("  " + m.id + " [" + m.q + "] ждал " + m.expect + " (" + m.expect_kind + ")");
  console.log("    получил: " + m.got.map((s) => s + "#" + m.got.indexOf(s)).join(", "));
}
db.close();
