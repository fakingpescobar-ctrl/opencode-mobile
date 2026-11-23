#!/usr/bin/env bun
// Анализ свежего decision-протокола: где модель выигрывает у ядра и можно ли
// включать её только по собственной уверенности (не на каждый запрос).
// Порог выбирается ТОЛЬКО на train, held-out держится до финального замера.
import { readFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const P = process.argv[2] || ROOT + "/tools/llama-bench/decision-bench-q8_0-apk67.json";
const j = JSON.parse(readFileSync(P, "utf8"));

const rows = j.rows.map((r) => {
  const p = r.choice_order.map((x) => x.p);
  const margin = p[0] - p[1];
  return {
    id: r.id, heldout: r.heldout, expect: r.expect,
    coreTop: r.core_top, choice_order: r.choice_order, modelTop: r.choice_order[0].letter,
    model: r.model_top1, core: r.core_top1,
    margin: Math.round(margin * 1000) / 1000,
    p0: p[0], ms: r.ms,
  };
});

const train = rows.filter((r) => !r.heldout);
const held = rows.filter((r) => r.heldout);
const rate = (rs, key) => rs.filter((r) => r[key]).length / (rs.length || 1);
const fmt = (x) => x.toFixed(3);

const gain = rows.filter((r) => r.model && !r.core);
const loss = rows.filter((r) => r.core && !r.model);
const agree = rows.filter((r) => r.model === r.core);

console.log(`index_sha=${j.index_sha} gold_sha=${j.gold_sha.slice(0, 12)} n=${j.n} ms=${j.avg_ms}`);
console.log(`train=${train.length} heldout=${held.length}`);
console.log(`ядро top1: train ${fmt(rate(train, "core"))} held ${fmt(rate(held, "core"))}`);
console.log(`модель top1: train ${fmt(rate(train, "model"))} held ${fmt(rate(held, "model"))}`);
console.log(`выигрыш модели (модель OK / ядро MISS): ${gain.length} [${gain.map((r) => r.id + "(m" + r.margin + ")").join(", ")}]`);
console.log(`потеря модели (ядро OK / модель MISS): ${loss.length} [${loss.map((r) => r.id + "(m" + r.margin + ")").join(", ")}]`);
console.log(`совпадений: ${agree.length}/${rows.length}`);
console.log(`средняя уверенность: выигрыш ${fmt(gain.reduce((a, r) => a + r.margin, 0) / (gain.length || 1))}, потеря ${fmt(loss.reduce((a, r) => a + r.margin, 0) / (loss.length || 1))}`);

// Порог по уверенности модели. Проверяем и p0, и margin, чтобы не звать арбитра
// при «все варианты одинаково плохи». Выбор лучшего порога — только на train.
let best = null;
for (let t = 0; t <= 0.6; t += 0.01) {
  const T = Math.round(t * 100) / 100;
  const use = (r) => r.margin >= T;
  const calls = train.filter(use).length;
  const top1 = train.filter((r) => use(r) ? r.model : r.core).length / train.length;
  if (!best || top1 > best.top1 + 1e-9 || (Math.abs(top1 - best.top1) < 1e-9 && calls < best.calls)) {
    best = { T, top1, calls };
  }
}
const use = (r) => r.margin >= best.T;
const hFinal = held.filter((r) => use(r) ? r.model : r.core).length / held.length;
const hCalls = held.filter(use).length;
console.log(`\nГЕЙТ (только по уверенности модели): margin >= ${best.T.toFixed(2)}`);
console.log(`  train: top1 ${fmt(best.top1)} при ${best.calls}/${train.length} вызовах (${fmt(best.calls / train.length)} моделей)`);
console.log(`  held-out: top1 ${fmt(hFinal)} при ${hCalls}/${held.length} вызовах; ядро ${fmt(rate(held, "core"))}, всегда-модель ${fmt(rate(held, "model"))}`);

// Для справки: верхняя граница при идеальном знании «ядро ошиблось».
// В рантайме такого оракула нет — это только оценка потенциала.
console.log(`\nВерхняя граница (идеальное знание core wrong): ${gain.length}/${rows.length} исправлено, ${loss.length} испорчено`);

console.log(`\nСдвинутые held-out, уверенность модели:`);
for (const r of held) console.log(`  ${r.id} m=${r.margin} core=${r.core ? "OK" : "MISS"} model=${r.model ? "OK" : "MISS"}`);

// RRF-смешивание рангов ядра и модели: модель не «решает», а подправляет порядок.
// Оба ранга — внутри одних и тех же 5 кандидатов, поэтому top3 не меняется,
// меняется только то, кто окажется первым. Вес выбирается на train.
function rrfTop(r, w) {
  const coreRank = new Map(r.coreTop.map((s, i) => [s, i]));
  const modelRank = new Map([...r.choice_order].map((x, i) => [x.letter, i]));
  let bestI = -1, bestS = -Infinity;
  for (let i = 0; i < r.coreTop.length; i++) {
    const letter = String.fromCharCode(65 + i);
    const s = (1 - w) / (1 + (coreRank.get(r.coreTop[i]) ?? 9)) + w / (1 + (modelRank.get(letter) ?? 9));
    if (s > bestS) { bestS = s; bestI = i; }
  }
  return r.coreTop[bestI] === r.expect;
}
let bw = null;
for (let w = 0; w <= 1.0001; w += 0.05) {
  const W = Math.round(w * 100) / 100;
  const t = train.filter((r) => rrfTop(r, W)).length / train.length;
  if (!bw || t > bw.t + 1e-9 || (Math.abs(t - bw.t) < 1e-9 && W < bw.w)) bw = { W, t };
}
console.log(`\nRRF (ранг ядра + ранг модели): вес модели ${bw.W.toFixed(2)}`);
console.log(`  train top1 ${fmt(bw.t)} | held-out top1 ${fmt(held.filter((r) => rrfTop(r, bw.W)).length / held.length)}`);

