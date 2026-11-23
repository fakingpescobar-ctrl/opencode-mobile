#!/usr/bin/env bun
// Дешёвый гейт для decision-модели: зовём 4-секундный арбитр только когда
// детерминированное ядро неуверенно. Порог выбирается на train, held-out —
// один финальный замер. Никаких правок ядра/APK.
import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const dec = JSON.parse(readFileSync(ROOT + "/tools/llama-bench/decision-bench-q8_0-apk67.json", "utf8"));
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8").split("\n").filter(Boolean).map((x) => JSON.parse(x));
const idx = await import(ROOT + "/opencode/memory-core/memory-index.mjs");
const core = await import(ROOT + "/opencode/memory-core/memory-core.js");
const { confidenceOf } = await import(ROOT + "/opencode/memory-core/memory-adaptive.js");

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const feats = new Map();
for (const g of gold) {
  const hits = core.recallDocs(DB, g.query, { limit: 5 });
  const s = hits.map((h) => h.score);
  const margin = (s[0] - s[1]) / (s[0] + 1e-6);
  const c = confidenceOf(hits.map((h) => ({ score: h.score })));
  feats.set(g.id, { margin, conf: c.value, peak: c.peak, top: s[0] });
}

const rows = dec.rows.map((r) => {
  const f = feats.get(r.id);
  return {
    id: r.id, heldout: r.heldout, expect: r.expect,
    coreTop: r.core_top, choice_order: r.choice_order,
    core: r.core_top1, model: r.model_top1,
    margin: f.margin, conf: f.conf, peak: f.peak,
  };
});
const train = rows.filter((r) => !r.heldout);
const held = rows.filter((r) => r.heldout);
const fmt = (x) => x.toFixed(3);

// Режим A: зовём модель только при низкой уверенности ядра, берём её top1.
function evaluate(key, mode) {
  let best = null;
  for (let t = 0; t <= 1.0001; t += 0.02) {
    const T = Math.round(t * 100) / 100;
    const call = (r) => r[key] <= T;
    const calls = train.filter(call).length;
    const top1 = train.filter((r) => call(r) ? (mode === "rrf" ? rrf(r, 0.6) : r.model) : r.core).length / train.length;
    if (!best || top1 > best.top1 + 1e-9 || (Math.abs(top1 - best.top1) < 1e-9 && calls < best.calls)) best = { T, top1, calls };
  }
  const call = (r) => r[key] <= best.T;
  const hTop = held.filter((r) => call(r) ? (mode === "rrf" ? rrf(r, 0.6) : r.model) : r.core).length / held.length;
  const hCalls = held.filter(call).length;
  console.log(`${key} / ${mode}: порог <= ${best.T.toFixed(2)} | train ${fmt(best.top1)} (${best.calls}/${train.length} вызовов) | held ${fmt(hTop)} (${hCalls}/${held.length} вызовов)`);
  return { key, mode, T: best.T, train: best.top1, held: hTop, calls: best.calls, hCalls };
}

function rrf(r, w) {
  const cr = new Map(r.coreTop.map((s, i) => [s, i]));
  const mr = new Map(r.choice_order.map((x, i) => [x.letter, i]));
  let bestI = -1, bestS = -Infinity;
  for (let i = 0; i < r.coreTop.length; i++) {
    const s = (1 - w) / (1 + (cr.get(r.coreTop[i]) ?? 9)) + w / (1 + (mr.get(String.fromCharCode(65 + i)) ?? 9));
    if (s > bestS) { bestS = s; bestI = i; }
  }
  return r.coreTop[bestI] === r.expect;
}

console.log(`baseline: ядро train ${fmt(train.filter((r) => r.core).length / train.length)} held ${fmt(held.filter((r) => r.core).length / held.length)}`);
console.log(`always model: train ${fmt(train.filter((r) => r.model).length / train.length)} held ${fmt(held.filter((r) => r.model).length / held.length)}`);
for (const key of ["margin", "conf", "peak"]) {
  for (const mode of ["model", "rrf"]) evaluate(key, mode);
}

console.log(`\nheld-out, признаки ядра:`);
for (const r of held) {
  console.log(`  ${r.id} margin=${r.margin.toFixed(3)} conf=${r.conf.toFixed(3)} core=${r.core ? "OK" : "MISS"} model=${r.model ? "OK" : "MISS"}`);
}

console.log(`\nЧувствительность порога (при срабатывании берём модель, иначе ядро):`);
for (const [key, ts] of [["margin", [0.01, 0.02, 0.03, 0.05]], ["conf", [0.14, 0.15, 0.16, 0.17, 0.18]]]) {
  for (const T of ts) {
    const call = (r) => r[key] <= T;
    const hTop = held.filter((r) => call(r) ? r.model : r.core).length / held.length;
    const hCalls = held.filter(call).length;
    const tTop = train.filter((r) => call(r) ? r.model : r.core).length / train.length;
    const tCalls = train.filter(call).length;
    console.log(`  ${key} <= ${T.toFixed(2)}: train ${fmt(tTop)}/${tCalls}, held ${fmt(hTop)}/${hCalls}`);
  }
}
