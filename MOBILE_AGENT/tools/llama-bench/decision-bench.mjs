#!/usr/bin/env bun
// Замер decision-модели hs-decide-1-flash именно в роли ядра памяти.
// Ядро детерминированно даёт 5 кандидатов; модель получает те же 5 как вариант
// choice и читает probability choice-токенов через lb_score в позиции ":"
// строки "Q1:" одним forward pass (не генерацией буквы).
// Сравнение честное: baseline и модель меряются на одном и том же gold и том же
// наборе кандидатов. Результат — research, дефолтное поведение ядра не меняется.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { createHash } from "node:crypto";
import { goldSha } from "../gold-corpus.mjs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
// Вызов: decision-bench.mjs [model.gguf] [sha|any] [out.json]
// Без аргументов — эталонный bf16 с жёсткой sha-проверкой; для квантов ша
// передаётся явно (или "any"), чтобы протокол оставался воспроизводимым.
const MODEL = process.argv[2]
  ? (process.argv[2].startsWith("/") ? process.argv[2] : ROOT + "/models/" + process.argv[2])
  : ROOT + "/models/hs-decide-1-flash-bf16.gguf";
const SIZE = process.argv[2] ? 0 : 1516744960;
const SHA = process.argv[3] || "1fd78a125d9e6347d3c90030c7fe2d4ef3990ff8faa2be08264e18d3b037e312";
const OUT = process.argv[4] || "decision-bench.json";
const CORE = ROOT + "/opencode/memory-core/";
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");

const modelSize = statSync(MODEL).size;
if (SIZE && modelSize !== SIZE) {
  console.error(`размер GGUF ${modelSize}, ожидался ${SIZE}`);
  process.exit(2);
}
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
  lb_code_ids: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.ptr, FFIType.i32],
    returns: FFIType.i32,
  },
  lb_score: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.ptr, FFIType.i32,
           FFIType.ptr, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.ptr],
    returns: FFIType.i32,
  },
});
const runtime = lib.symbols.lb_version();
const rtNum = Number(String(runtime).split("musl-")[1] || 0);
if (!(rtNum >= 8)) {
  console.error(`нужна обёртка musl-8+ с lb_score/lb_code_ids, сейчас ${runtime}`);
  process.exit(2);
}

const LETTERS = "ABCDE";
const ALIASES = 4;
// id токенов кодов не зависят от запроса — считаем один раз за прогон.
const groups = new Int32Array(LETTERS.length * ALIASES).fill(-1);
for (let i = 0; i < LETTERS.length; i++) {
  const ids = new Int32Array(ALIASES);
  const n = lib.symbols.lb_code_ids(MODEL, LETTERS[i], ids, ALIASES);
  if (n < 1) {
    console.error(`код ${LETTERS[i]} не дал однотокенный алиас`);
    process.exit(2);
  }
  for (let a = 0; a < ALIASES; a++) groups[i * ALIASES + a] = a < n ? ids[a] : -1;
}

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const index = core.indexSha(DB);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
// Сплит из gold, никогда не пересчитывается; как в rerank/embed — чтобы
// research-цифры можно было сравнивать только внутри одной пары хэшей.
const heldout = new Set(core.heldoutSubset(gold).map((g) => g.id));
const goldShaValue = goldSha(gold);

const rows = [];
let modelTop1 = 0, modelTop3 = 0, coreTop1 = 0, coreTop3 = 0;
let hN = 0, hModelTop1 = 0, hModelTop3 = 0, hCoreTop1 = 0, hCoreTop3 = 0;
let parseOk = 0, totalMs = 0;

for (const g of gold) {
  const hits = core.recallDocs(DB, g.query, { limit: LETTERS.length });
  if (hits.length !== LETTERS.length) continue;
  const coreOrder = hits.map((x) => x.source === g.expect_source);
  if (coreOrder[0]) coreTop1++;
  if (coreOrder.slice(0, 3).some(Boolean)) coreTop3++;

  const lines = hits.map((hit, i) => {
    const desc = String(hit.text || "").replace(/\s+/g, " ").slice(0, 220);
    return `  ${LETTERS[i]}: ${hit.source} — ${String(hit.heading || "").slice(0, 60)}: ${desc}`;
  });
  // Ровно формат typed_render.py: questions -> блок вариантов -> ответная строка Q1:.
  const prompt =
    "<|im_start|>user\nState:\n" + g.query +
    "\n\nQuestions:\n[Q1] (choice) Which memory chunk is relevant to the state?\n" +
    lines.join("\n") + "\n\n<|im_end|>\n<|im_start|>assistant\nQ1:\n";
  const slots = new Int32Array([-2]); // двоеточие — предпоследний токен перед "\n"
  const probs = new Float64Array(LETTERS.length);
  const metrics = new Float64Array(4);
  const rc = lib.symbols.lb_score(MODEL, prompt, slots, 1,
    groups, LETTERS.length, ALIASES, probs, metrics);
  if (rc !== 0) {
    console.error(`${g.id}: rc=${rc}`);
    continue;
  }
  parseOk++;
  totalMs += metrics[1];
  const order = [...probs].map((p, i) => ({ i, p })).sort((a, b) => b.p - a.p);
  const top = order[0].i;
  const hit1 = coreOrder[top] === true;
  const hit3 = order.slice(0, 3).some((x) => coreOrder[x.i] === true);
  if (hit1) modelTop1++;
  if (hit3) modelTop3++;
  if (heldout.has(g.id)) {
    hN++;
    if (hit1) hModelTop1++;
    if (hit3) hModelTop3++;
    if (coreOrder[0]) hCoreTop1++;
    if (coreOrder.slice(0, 3).some(Boolean)) hCoreTop3++;
  }
  rows.push({
    id: g.id, heldout: heldout.has(g.id), query: g.query, expect: g.expect_source,
    core_top: hits.map((x) => x.source),
    choice_order: order.map((x) => ({ letter: LETTERS[x.i], p: Math.round(x.p * 1000) / 1000 })),
    model_top1: hit1, model_top3: hit3,
    core_top1: coreOrder[0] === true, core_top3: coreOrder.slice(0, 3).some(Boolean),
    ms: Math.round(metrics[1]), prompt_tokens: metrics[2],
  });
  console.log(`${g.id}${heldout.has(g.id) ? " [held-out]" : ""}: модель ${LETTERS[top]} ${hit1 ? "OK" : "MISS"} | ядро ${coreOrder[0] ? "OK" : "MISS"} | ${Math.round(metrics[1])} мс`);
}

const n = rows.length || 1;
const out = {
  model: "hindsight-ai/hs-decide-1-flash",
  file: MODEL.split("/").pop(),
  sha256: gotSha,
  bytes: modelSize,
  runtime,
  role: "choice над 5 кандидатами ядра, один forward pass",
  index_sha: index,
  gold_sha: goldShaValue,
  n: rows.length,
  model_top1: modelTop1 / n,
  model_top3: modelTop3 / n,
  core_top1: coreTop1 / n,
  core_top3: coreTop3 / n,
  heldout: {
    n: hN,
    model_top1: hN ? hModelTop1 / hN : 0,
    model_top3: hN ? hModelTop3 / hN : 0,
    core_top1: hN ? hCoreTop1 / hN : 0,
    core_top3: hN ? hCoreTop3 / hN : 0,
  },
  avg_ms: Math.round(totalMs / n),
  rows,
};
writeFileSync(ROOT + "/tools/llama-bench/" + OUT, JSON.stringify(out, null, 2) + "\n");
console.log(`\nИТОГ: модель top1 ${(modelTop1 / n).toFixed(3)}, top3 ${(modelTop3 / n).toFixed(3)}; ` +
  `ядро top1 ${(coreTop1 / n).toFixed(3)}, top3 ${(coreTop3 / n).toFixed(3)}; ` +
  `${Math.round(totalMs / n)} мс/запрос`);
if (hN) console.log(`held-out (${hN}): модель top1 ${(hModelTop1 / hN).toFixed(3)} / ` +
  `ядро ${(hCoreTop1 / hN).toFixed(3)}; top3 ${(hModelTop3 / hN).toFixed(3)} / ${(hCoreTop3 / hN).toFixed(3)}`);
