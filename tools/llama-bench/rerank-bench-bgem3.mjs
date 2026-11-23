#!/usr/bin/env bun
// Замер bge-reranker-v2-m3 в роли переранжировщика 5 кандидатов ядра.
// Один и тот же gold, тот же index_sha, те же 5 кандидатов — сравнение честное.
// Результат research: дефолтное поведение ядра и legacy fallback не меняются.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { goldSha } from "../gold-corpus.mjs";

// Запуск: LLAMA_VERBOSE=1 ... 2>/dev/null — обёртка musl-14 чинит рекурсию
// в set_logging(), но env всё равно нужен, чтобы видеть причины ошибок GGUF.
const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
// Вызов: rerank-bench.mjs [model.gguf] [sha|any] [out.json]
const MODEL = process.argv[2]
  ? (process.argv[2].startsWith("/") ? process.argv[2] : ROOT + "/models/" + process.argv[2])
  : ROOT + "/models/bge-reranker-v2-m3-Q8_0.gguf";
const SHA = process.argv[3] || "any";
const OUT = process.argv[4] || "rerank-bench-bge-v2-m3.json";
const CORE = ROOT + "/opencode/memory-core/";
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");

const modelSize = statSync(MODEL).size;
const h = createHash("sha256");
const fd = await (await import("node:fs/promises")).open(MODEL, "r");
const chunk = Buffer.allocUnsafe(8 * 1024 * 1024);
for (;;) {
  const { bytesRead } = await fd.read(chunk, 0, chunk.length, null);
  if (!bytesRead) break;
  h.update(chunk.subarray(0, bytesRead));
}
await fd.close();
const gotSha = h.digest("hex");
if (SHA !== "any" && gotSha !== SHA) {
  console.error(`sha GGUF не совпал: ${gotSha}`);
  process.exit(2);
}

const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_state_init: { args: [FFIType.cstring, FFIType.i32], returns: FFIType.ptr },
  lb_cls_score: {
    args: [FFIType.ptr, FFIType.cstring, FFIType.ptr, FFIType.ptr],
    returns: FFIType.i32,
  },
  lb_state_free: { args: [FFIType.ptr], returns: FFIType.void },
});
const runtime = lib.symbols.lb_version();
const rtNum = Number(String(runtime).split("musl-")[1] || 0);
if (!(rtNum >= 16)) {
  console.error(`нужна обёртка musl-16+ с явным POOLING_TYPE_RANK, сейчас ${runtime}`);
  process.exit(2);
}
const st = lib.symbols.lb_state_init(MODEL, 4);  // LLAMA_POOLING_TYPE_RANK
if (!st) { console.error("lb_state_init failed"); process.exit(2); }

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
const heldout = new Set(core.heldoutSubset(gold).map((g) => g.id));
const index = core.indexSha(DB);
const goldShaValue = goldSha(gold);

const rows = [];
let modelTop1 = 0, modelTop3 = 0, coreTop1 = 0, coreTop3 = 0;
let hModelTop1 = 0, hCoreTop1 = 0, hN = 0;
let totalMs = 0, pairs = 0;

for (const g of gold) {
  const hits = core.recallDocs(DB, g.query, { limit: 5 });
  if (hits.length !== 5) continue;
  const relevant = hits.map((x) => x.source === g.expect_source);
  if (relevant[0]) coreTop1++;
  if (relevant.slice(0, 3).some(Boolean)) coreTop3++;

  const scored = [];
  let rowMs = 0;
  for (let i = 0; i < hits.length; i++) {
    const text = String(hits[i].text || "").replace(/\s+/g, " ").slice(0, 600);
    // bge-reranker-v2-m3 построен на XLM-Roberta: sep_token = </s>.
    // Пробелы вокруг разделителя дают лишние space-токены — только вплотную.
    const prompt = String(g.query).trimEnd() + "</s>" + text.trimStart();
    const score = new Float64Array(1);
    const metrics = new Float64Array(4);
    const rc = lib.symbols.lb_cls_score(st, prompt, score, metrics);
    if (rc !== 0) {
      console.error(`${g.id}/${i}: rc=${rc}`);
      continue;
    }
    // persistent API: metrics[0]=decode мс, [1]=токенов, [2]=n_cls, [3]=n_embd
    totalMs += metrics[0];
    rowMs += metrics[0];
    pairs++;
    scored.push({ i, score: score[0] });
  }
  if (scored.length !== hits.length) continue;
  const order = scored.sort((a, b) => b.score - a.score);
  const top = order[0].i;
  const hit1 = relevant[top] === true;
  const hit3 = order.slice(0, 3).some((x) => relevant[x.i] === true);
  if (hit1) modelTop1++;
  if (hit3) modelTop3++;
  if (heldout.has(g.id)) {
    hN++;
    if (hit1) hModelTop1++;
    if (relevant[0]) hCoreTop1++;
  }
  rows.push({
    id: g.id, heldout: heldout.has(g.id), query: g.query, expect: g.expect_source,
    core_top: hits.map((x) => x.source),
    // i — индекс кандидата ядра: нужен гибридному гейту, чтобы не потерять
    // выравнивание при повторяющихся source в топ-5.
    rerank_order: order.map((x) => ({
      i: x.i, source: hits[x.i].source,
      score: Math.round(x.score * 1e6) / 1e6,
      core: hits[x.i].score,  // для гибридного гейта: та же шкала, что у ядра
    })),
    model_top1: hit1, model_top3: hit3,
    core_top1: relevant[0] === true, core_top3: relevant.slice(0, 3).some(Boolean),
    ms: Math.round(rowMs),
  });
  console.log(`${g.id}${heldout.has(g.id) ? " [held-out]" : ""}: rerank ${hit1 ? "OK" : "MISS"} | ядро ${relevant[0] ? "OK" : "MISS"}`);
}

const n = rows.length || 1;
const out = {
  model: "BAAI/bge-reranker-v2-m3 (GGUF: gpustack/bge-reranker-v2-m3-GGUF)",
  file: MODEL.split("/").pop(),
  sha256: gotSha,
  bytes: modelSize,
  runtime,
  role: "переранжирование 5 кандидатов ядра, 5 forward pass на запрос",
  note: "XLM-Roberta sep_token=</s>; rank pooling через lb_cls_score, llama.cpp b12554",
  index_sha: index,
  gold_sha: goldShaValue,
  n: rows.length,
  pairs,
  model_top1: modelTop1 / n,
  model_top3: modelTop3 / n,
  core_top1: coreTop1 / n,
  core_top3: coreTop3 / n,
  heldout: { n: hN, model_top1: hN ? hModelTop1 / hN : 0, core_top1: hN ? hCoreTop1 / hN : 0 },
  avg_ms_per_pair: Math.round(totalMs / (pairs || 1)),
  avg_ms_per_query: Math.round(totalMs / n),
  rows,
};
lib.symbols.lb_state_free(st);
writeFileSync(ROOT + "/tools/llama-bench/" + OUT, JSON.stringify(out, null, 2) + "\n");
console.log(`\nИТОГ: rerank top1 ${(modelTop1 / n).toFixed(3)}, top3 ${(modelTop3 / n).toFixed(3)}; ` +
  `ядро top1 ${(coreTop1 / n).toFixed(3)}, top3 ${(coreTop3 / n).toFixed(3)}; ` +
  `held-out ${hModelTop1}/${hN} против ядра ${hCoreTop1}/${hN}`);
