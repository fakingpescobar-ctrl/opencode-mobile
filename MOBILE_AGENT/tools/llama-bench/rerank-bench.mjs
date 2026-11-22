#!/usr/bin/env bun
// Замер hs-rerank-1 в роли переранжировщика 5 кандидатов детерминированного ядра.
// Один и тот же gold, тот же index_sha, те же 5 кандидатов — сравнение честное.
// Результат research: дефолтное поведение ядра и legacy fallback не меняются.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";

// Запуск: LLAMA_VERBOSE=1 ... 2>/dev/null — обёртка musl-14 чинит рекурсию
// в set_logging(), но env всё равно нужен, чтобы видеть причины ошибок GGUF.
const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
// Вызов: rerank-bench.mjs [model.gguf] [sha|any] [out.json]
const MODEL = process.argv[2]
  ? (process.argv[2].startsWith("/") ? process.argv[2] : ROOT + "/models/" + process.argv[2])
  : ROOT + "/models/hs-rerank-1-f16.gguf";
const SHA = process.argv[3] || "any";
const OUT = process.argv[4] || "rerank-bench.json";
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
  lb_state_init: { args: [FFIType.cstring], returns: FFIType.ptr },
  lb_cls_score: {
    args: [FFIType.ptr, FFIType.cstring, FFIType.ptr, FFIType.ptr],
    returns: FFIType.i32,
  },
  lb_state_free: { args: [FFIType.ptr], returns: FFIType.void },
});
const runtime = lib.symbols.lb_version();
const rtNum = Number(String(runtime).split("musl-")[1] || 0);
if (!(rtNum >= 15)) {
  console.error(`нужна обёртка musl-15+ с rank из embeddings_seq, сейчас ${runtime}`);
  process.exit(2);
}
const st = lib.symbols.lb_state_init(MODEL);
if (!st) { console.error("lb_state_init failed"); process.exit(2); }

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
const heldout = new Set(core.heldoutSubset(gold).map((g) => g.id));
const index = core.indexSha(DB);

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
    // Sentence-Transformers CrossEncoder: query + sep_token + document.
    // В GGUF sep_token_id = eos (<eos>), add_special добавляет <bos>/<eos>.
    const prompt = String(g.query) + " <eos> " + text;
    const score = new Float64Array(1);
    const metrics = new Float64Array(4);
    const rc = lib.symbols.lb_cls_score(st, prompt, score, metrics);
    if (rc !== 0) {
      console.error(`${g.id}/${i}: rc=${rc}`);
      continue;
    }
    totalMs += metrics[1];
    rowMs += metrics[1];
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
    rerank_order: order.map((x) => ({ source: hits[x.i].source, score: Math.round(x.score * 1e6) / 1e6 })),
    model_top1: hit1, model_top3: hit3,
    core_top1: relevant[0] === true, core_top3: relevant.slice(0, 3).some(Boolean),
    ms: Math.round(rowMs),
  });
  console.log(`${g.id}${heldout.has(g.id) ? " [held-out]" : ""}: rerank ${hit1 ? "OK" : "MISS"} | ядро ${relevant[0] ? "OK" : "MISS"}`);
}

const n = rows.length || 1;
const out = {
  model: "hindsight-ai/hs-rerank-1",
  file: MODEL.split("/").pop(),
  sha256: gotSha,
  bytes: modelSize,
  runtime,
  role: "переранжирование 5 кандидатов ядра, 5 forward pass на запрос",
  note: "classifier_activation=gelu_pytorch_tanh удалён при конвертации: llama.cpp b12554 использует GELU_ERF",
  index_sha: index,
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
