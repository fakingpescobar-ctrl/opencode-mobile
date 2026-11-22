#!/usr/bin/env bun
// Замер hs-embed-1 в роли семантического переранжировщика 5 кандидатов ядра.
//Query и документы кодируются одним и тем же bi-encoder, ранжирование — cosine.
// Один gold, один index_sha, те же 5 кандидатов. Дефолтное ядро не меняется.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
// Вызов: embed-bench.mjs [model.gguf] [sha|any] [out.json]
const MODEL = process.argv[2]
  ? (process.argv[2].startsWith("/") ? process.argv[2] : ROOT + "/models/" + process.argv[2])
  : ROOT + "/models/hs-embed-1-f16.gguf";
const SHA = process.argv[3] || "any";
const OUT = process.argv[4] || "embed-bench.json";
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
  lb_embed: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.ptr, FFIType.i32, FFIType.ptr],
    returns: FFIType.i32,
  },
});
const runtime = lib.symbols.lb_version();
const rtNum = Number(String(runtime).split("musl-")[1] || 0);
if (!(rtNum >= 12)) {
  console.error(`нужна обёртка musl-12+ с lb_embed и ctx=1024, сейчас ${runtime}`);
  process.exit(2);
}

const CAP = 2048;
let dim = 0;
const metrics = new Float64Array(4);
const embed = (text) => {
  const out = new Float32Array(CAP);
  const n = lib.symbols.lb_embed(MODEL, String(text), out, CAP, metrics);
  if (n < 1) throw new Error("lb_embed failed");
  dim = n;
  const v = out.slice(0, n);
  let norm = 0;
  for (const x of v) norm += x * x;
  norm = Math.sqrt(norm) || 1;
  for (let i = 0; i < v.length; i++) v[i] /= norm;
  return v;
};
const cosine = (a, b) => {
  let s = 0;
  for (let i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
  return s;
};

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
const heldout = new Set(core.heldoutSubset(gold).map((g) => g.id));
const index = core.indexSha(DB);

const rows = [];
let modelTop1 = 0, modelTop3 = 0, coreTop1 = 0, coreTop3 = 0;
let hModelTop1 = 0, hCoreTop1 = 0, hN = 0;
let totalMs = 0, texts = 0;

for (const g of gold) {
  const hits = core.recallDocs(DB, g.query, { limit: 5 });
  if (hits.length !== 5) continue;
  const relevant = hits.map((x) => x.source === g.expect_source);
  if (relevant[0]) coreTop1++;
  if (relevant.slice(0, 3).some(Boolean)) coreTop3++;

  let qv;
  try { qv = embed(g.query); } catch (e) { console.error(`${g.id}: ${e.message}`); continue; }
  let rowMs = metrics[0];
  totalMs += metrics[0]; texts++;
  const scored = [];
  for (let i = 0; i < hits.length; i++) {
    const text = String(hits[i].text || "").replace(/\s+/g, " ").slice(0, 600);
    let dv;
    try { dv = embed(text); } catch (e) { console.error(`${g.id}/${i}: ${e.message}`); continue; }
    rowMs += metrics[0];
    totalMs += metrics[0]; texts++;
    scored.push({ i, score: cosine(qv, dv) });
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
    embed_order: order.map((x) => ({ source: hits[x.i].source, score: Math.round(x.score * 1e6) / 1e6 })),
    model_top1: hit1, model_top3: hit3,
    core_top1: relevant[0] === true, core_top3: relevant.slice(0, 3).some(Boolean),
    ms: Math.round(rowMs),
  });
  console.log(`${g.id}${heldout.has(g.id) ? " [held-out]" : ""}: embed ${hit1 ? "OK" : "MISS"} | ядро ${relevant[0] ? "OK" : "MISS"}`);
}

const n = rows.length || 1;
const out = {
  model: "hindsight-ai/hs-embed-1",
  file: MODEL.split("/").pop(),
  sha256: gotSha,
  bytes: modelSize,
  runtime,
  role: "косинусный ранжир 5 кандидатов ядра, 6 forward pass на запрос",
  index_sha: index,
  dim,
  n: rows.length,
  texts,
  model_top1: modelTop1 / n,
  model_top3: modelTop3 / n,
  core_top1: coreTop1 / n,
  core_top3: coreTop3 / n,
  heldout: { n: hN, model_top1: hN ? hModelTop1 / hN : 0, core_top1: hN ? hCoreTop1 / hN : 0 },
  avg_ms_per_text: Math.round(totalMs / (texts || 1)),
  avg_ms_per_query: Math.round(totalMs / n),
  rows,
};
writeFileSync(ROOT + "/tools/llama-bench/" + OUT, JSON.stringify(out, null, 2) + "\n");
console.log(`\nИТОГ: embed top1 ${(modelTop1 / n).toFixed(3)}, top3 ${(modelTop3 / n).toFixed(3)}; ` +
  `ядро top1 ${(coreTop1 / n).toFixed(3)}, top3 ${(coreTop3 / n).toFixed(3)}; ` +
  `held-out ${hModelTop1}/${hN} против ядра ${hCoreTop1}/${hN}`);
