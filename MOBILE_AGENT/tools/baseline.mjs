#!/usr/bin/env bun
// Один базовый прогон ядра после смены пары (index_sha, gold_sha).
// Ключ в baselines.json — буквально эта пара; сравнивать гипотезы можно
// только внутри одного ключа. При вливании новой пачки gold вызывается заново
// и фиксирует новую точку отсчёта.
//
// Запуск: bun tools/baseline.mjs [--gold=<path>]

import { readFileSync, writeFileSync, existsSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const gc = await import(ROOT + "/tools/gold-corpus.mjs");
const core = await import(ROOT + "/opencode/memory-core/memory-core.js");
const idx = await import(ROOT + "/opencode/memory-core/memory-index.mjs");
const { Database } = await import("bun:sqlite");

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith("--" + name + "="));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const GOLD = arg("gold", ROOT + "/memory/gold.jsonl");
const OUT = ROOT + "/tools/llama-bench/baselines.json";

const gold = gc.readGold(GOLD);
const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const indexSha = core.indexSha(DB);
const goldSha = gc.goldSha(gold);
const key = indexSha + "|" + goldSha;
const held = core.heldoutSubset(gold);

const all = core.runBench(DB, "baseline-all", gold, {});
const heldout = core.runBench(DB, "baseline-heldout", held, {});

const base = existsSync(OUT) ? JSON.parse(readFileSync(OUT, "utf8")) : { version: 1, baselines: {} };
if (!base.baselines || typeof base.baselines !== "object") base.baselines = {};
base.baselines[key] = {
  index_sha: indexSha,
  gold_sha: goldSha,
  gold_n: gold.length,
  heldout_n: held.length,
  recall5: all.recall5,
  mrr: all.mrr,
  ndcg: all.ndcg,
  heldout_recall5: heldout.recall5,
  heldout_mrr: heldout.mrr,
  at: Date.now(),
};
writeFileSync(OUT, JSON.stringify(base, null, 2) + "\n");
console.log(`Базовый прогон зафиксирован: ${key}`);
console.log(`gold ${gold.length}, held-out ${held.length}, recall5 ${all.recall5.toFixed(3)}, heldout ${heldout.recall5.toFixed(3)}`);
console.log("Файл: " + OUT);
