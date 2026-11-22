#!/usr/bin/env bun
// РЕЗЕАРЧ-ГЕЙТ: гибрид детерминированного ядра и hs-rerank-1.
// Не меняет memory-core, не импортирует recallDocs в рантайме: читает уже
// замороженный протокол rerank-bench.json, где для каждого из 5 кандидатов
// сохранены и core score, и rank score. Так сравнение остаётся честным.
//
// Правила, которые здесь соблюдаются:
//   1. Порог отрыва и вес смешивания выбираются ТОЛЬКО на train-части gold.
//   2. held-out держится за кадром до финального замера.
//   3. Сравнение имеет смысл только внутри одного index_sha (проверяем).
//   4. Дефолт ядра и legacy fallback не трогаются — это opt-in research.
//
// Запуск: bun hybrid-gate.mjs [rerank-bench.json]
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { confidenceOf } from "../../opencode/memory-core/memory-adaptive.js";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const PROTO = process.argv[2] || ROOT + "/tools/llama-bench/rerank-bench.json";
const j = JSON.parse(readFileSync(PROTO, "utf8"));
if (j.runtime !== "llama-bench-musl-16") {
  console.error(`нужен протокол musl-16, тут ${j.runtime}`);
  process.exit(2);
}
const rows = j.rows;
if (!rows.every((r) => r.rerank_order?.length === 5 && r.rerank_order.every((x) => Number.isFinite(x.core)))) {
  console.error("в протоколе нет core score для всех 5 кандидатов — перезапусти rerank-bench");
  process.exit(2);
}

const EPS = 1e-6;
const train = rows.filter((r) => !r.heldout);
const held = rows.filter((r) => r.heldout);

// --- Признаки unsureness -------------------------------------------------
// relative margin: (s0 - s1) / (s0 + eps). Не абсолютная разница: BM25
// плавает с длиной запроса и редкостью терминов.
// min_confidence: если и лидер мусорный, зовём арбитра даже при большом
// отрыве — он должен вытащить 4-й/5-й кандидат.
function features(r) {
  const byI = new Map(r.rerank_order.map((x) => [x.i, x]));
  const s0 = byI.get(0).core;
  const s1 = byI.get(1).core;
  // Формула из ревью: относительный отрыв, не сырая разница BM25.
  const margin = (s0 - s1) / (s0 + EPS);
  // Переиспользуем уже существующий confidence-слой ядра: peak = top-median,
  // raw = 0.65*peak/0.30 + 0.35*top. Это вторая независимая оценка.

  const conf = confidenceOf([0, 1, 2, 3, 4].map((i) => ({ score: byI.get(i).core })));
  return { margin, top: s0, conf: conf.value, peak: conf.peak, matched: conf.matched };
}

function minmax(values) {
  const lo = Math.min(...values), hi = Math.max(...values);
  if (hi - lo < EPS) return values.map(() => 0.5);
  return values.map((v) => (v - lo) / (hi - lo));
}

function rankRow(r, sel) {
  const byI = new Map(r.rerank_order.map((x) => [x.i, x]));
  const cs = [0, 1, 2, 3, 4].map((i) => byI.get(i).core);
  const rs = [0, 1, 2, 3, 4].map((i) => byI.get(i).score);
  let mixed;
  if (sel.fusion === "rrf") {
    // Reciprocal Rank Fusion: складываем РАНГИ, а не сырые score (разные
    // шкалы BM25 и cross-encoder). Модель — поправка, не хозяин.
    const cOrder = cs.map((v, i) => ({ v, i })).sort((a, b) => b.v - a.v).map((x) => x.i);
    const rOrder = rs.map((v, i) => ({ v, i })).sort((a, b) => b.v - a.v).map((x) => x.i);
    const cRank = new Map(cOrder.map((idx, rank) => [idx, rank]));
    const rRank = new Map(rOrder.map((idx, rank) => [idx, rank]));
    mixed = [0, 1, 2, 3, 4].map((i) =>
      (1 - sel.w) / (1 + cRank.get(i)) + sel.w / (1 + rRank.get(i)));
  } else {
    const core = minmax(cs), rank = minmax(rs);
    mixed = core.map((c, i) => (1 - sel.w) * c + sel.w * rank[i]);
  }
  return mixed.map((v, i) => ({ v, i })).sort((a, b) => b.v - a.v).map((x) => x.i);
}

function score(rows_, sel) {
  let top1 = 0, top3 = 0, calls = 0;
  for (const r of rows_) {
    const f = features(r);
    const value = sel.mode === "margin" ? f.margin : f.conf;
    const call = value < sel.cut || (sel.useMinConf && f.top < sel.minConf);
    if (call) calls++;
    const order = call ? rankRow(r, sel) : [0, 1, 2, 3, 4];
    const rel = order.map((i) => r.core_top[i] === r.expect);
    if (rel[0]) top1++;
    if (rel.slice(0, 3).some(Boolean)) top3++;
  }
  return { top1: top1 / rows_.length, top3: top3 / rows_.length, calls };
}

// --- Выбор параметров на train ------------------------------------------
const quantile = (arr, p) => arr[Math.min(arr.length - 1, Math.floor(p * arr.length))];
const margins = train.map(features).map((f) => f.margin).sort((a, b) => a - b);
const confs = train.map(features).map((f) => f.conf).sort((a, b) => a - b);
const tops = train.map(features).map((f) => f.top).sort((a, b) => a - b);
const qm = (p) => quantile(margins, p);
const qc = (p) => quantile(confs, p);
const conf25 = quantile(tops, 0.25);

// Пара хэшей обязательна. Старые протоколы без gold_sha больше не годятся:
// сравнение гипотез вне пары (index_sha, gold_sha) размывает границы снимка.
if (typeof j.gold_sha !== "string" || !/^[0-9a-f]{64}$/.test(j.gold_sha)) {
  console.error("в протоколе нет gold_sha — перезапусти бенч после backfill'а gold");
  process.exit(2);
}
const BASELINES = ROOT + "/tools/llama-bench/baselines.json";
if (existsSync(BASELINES)) {
  const b = JSON.parse(readFileSync(BASELINES, "utf8"));
  const entries = b.baselines || {};
  if (Object.keys(entries).length &&
      !entries[j.index_sha + "|" + j.gold_sha]) {
    console.error(`нет базового прогона для пары ${j.index_sha}|${j.gold_sha} — сначала bun tools/baseline.mjs`);
    process.exit(2);
  }
}

console.log(`протокол: ${PROTO.split("/").pop()} | index_sha ${j.index_sha} | gold_sha ${j.gold_sha}`);
console.log(`train ${train.length}, held-out ${held.length}`);
console.log(`отрыв margin: min ${margins[0].toFixed(4)} | q25 ${qm(0.25).toFixed(4)} | q30 ${qm(0.30).toFixed(4)} | q50 ${qm(0.50).toFixed(4)} | max ${margins.at(-1).toFixed(4)}`);
console.log(`confidence ядра: min ${confs[0].toFixed(4)} | q25 ${qc(0.25).toFixed(4)} | q30 ${qc(0.30).toFixed(4)} | max ${confs.at(-1).toFixed(4)}`);
console.log(`core top score: min ${tops[0].toFixed(4)} | q25 ${conf25.toFixed(4)} | max ${tops.at(-1).toFixed(4)}`);

const NEVER = { mode: "margin", cut: -Infinity, w: 0, useMinConf: false, minConf: 0, fusion: "linear" };
const trainCore = score(train, NEVER);
console.log(`эталон train: top1 ${trainCore.top1.toFixed(3)} top3 ${trainCore.top3.toFixed(3)}`);

let best = null;
for (const mode of ["margin", "conf"]) {
  for (const fusion of ["linear", "rrf"]) {
    for (const w of [0.2, 0.3, 0.4, 0.5, 0.6]) {
      for (const p of [0.20, 0.25, 0.30]) {
        for (const useMinConf of [false, true]) {
          const sel = {
            mode, fusion, w, p, useMinConf, minConf: conf25,
            cut: mode === "margin" ? qm(p) : qc(p),
          };
          const s = score(train, sel);
          const cand = { ...sel, ...s };
          // Тай-брейк: при равном top1 берём меньше вызовов модели.
          if (!best || s.top1 > best.top1 + 1e-9 ||
              (Math.abs(s.top1 - best.top1) < 1e-9 && s.calls < best.calls)) {
            best = cand;
          }
        }
      }
    }
  }
}
console.log(`лучшее на train: ${best.fusion}/${best.mode} cut=q${best.p} (${best.cut.toFixed(4)}) w=${best.w} min_conf=${best.useMinConf ? best.minConf.toFixed(4) : "off"} -> top1 ${best.top1.toFixed(3)} top3 ${best.top3.toFixed(3)}, вызовов ${best.calls}/${train.length}`);

// --- Один финальный замер на held-out -----------------------------------
const heldCore = score(held, NEVER);
const heldHybrid = score(held, best);
console.log(`held-out ядро:    top1 ${heldCore.top1.toFixed(3)} (${heldCore.top1 * held.length}/${held.length}) top3 ${heldCore.top3.toFixed(3)}`);
console.log(`held-out гибрид:  top1 ${heldHybrid.top1.toFixed(3)} (${heldHybrid.top1 * held.length}/${held.length}) top3 ${heldHybrid.top3.toFixed(3)}, вызовов ${heldHybrid.calls}/${held.length}`);
console.log(`средняя цена: ~${Math.round((heldHybrid.calls / held.length) * j.avg_ms_per_query)} мс/запрос (пару ${j.avg_ms_per_pair} мс)`);

// Защита онлайн-контура от раскачки: фиксируем как контракт, а не как
// подгонянный под gold параметр. Применять в observeSignal -> approve(gate).
console.log("онлайн-контур: delta cap 10% за цикл, кулдаун 20 уникальных запросов, maxDrop=0.02");

writeFileSync(ROOT + "/tools/llama-bench/hybrid-gate.json", JSON.stringify({
  protocol: PROTO.split("/").pop(), index_sha: j.index_sha, gold_sha: j.gold_sha,
  train: { n: train.length, core_top1: trainCore.top1, chosen: best, q30: qm(0.30), qc30: qc(0.30), conf25 },
  heldout: { n: held.length, core_top1: heldCore.top1, hybrid_top1: heldHybrid.top1,
             core_top3: heldCore.top3, hybrid_top3: heldHybrid.top3, calls: heldHybrid.calls },
  online: { delta_cap: 0.10, cooldown_unique_queries: 20, max_drop: 0.02 },
}, null, 2) + "\n");
console.log("протокол записан в tools/llama-bench/hybrid-gate.json");
