#!/usr/bin/env bun
// Замер LLM в РЕАЛЬНОЙ роли сопроцессора: объяснение чанка и переформулировка
// запроса. Quality-gate мерил выбор чанков (роль, которую мы отвергли), поэтому
// выбор модели для объяснений до сих пор не был подтверждён замером.
//
// Метрики детерминированные, без LLM-судьи:
//   format   — непустой ответ без служебных токенов и без эха промпта;
//   grounded — объяснение содержит хотя бы одну лемму из чанка (нет галлюцинации);
//   rewrite  — переформулировка отличается от входа и сохраняет ≥50% лемм;
//   ms       — latency из C-обёртки.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const CORE = ROOT + "/opencode/memory-core/";
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");
const lemma = await import(CORE + "memory-lemma.js");

const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_gen: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.i32, FFIType.i32,
           FFIType.ptr, FFIType.i32, FFIType.ptr],
    returns: FFIType.i32,
  },
});
const runtime = lib.symbols.lb_version();
if (!runtime.includes("musl-5")) {
  console.error("нужна обёртка llama-bench-musl-5, сейчас: " + runtime);
  process.exit(2);
}

function wrap(family, sys, user) {
  if (family === "gemma") {
    return `<start_of_turn>user\n${sys}\n\n${user}<end_of_turn>\n<start_of_turn>model\n`;
  }
  const s = family === "qwen3" ? sys + "\n/no_think" : sys;
  return `<|im_start|>system\n${s}<|im_end|>\n<|im_start|>user\n${user}<|im_end|>\n<|im_start|>assistant\n`;
}

const SYS_WHY = "Объясни одним коротким предложением, почему этот фрагмент подходит для запроса. Ответь только предложением, без вступления и без перечисления.";
const SYS_REWRITE = "Переформулируй запрос одним коротким предложением так, чтобы его было проще искать в базе знаний. Сохрани смысл и ключевые слова. Ответь только переформулировкой.";

const BAD = [/<\|im_start\|>/, /<start_of_turn>/, /Запрос:/, /Фрагмент:/, /```/];
function clean(text) {
  const s = String(text || "").replace(/\s+/g, " ").trim();
  if (!s || s.length < 12 || s.length > 500) return "";
  if (BAD.some((re) => re.test(s))) return "";
  return s;
}
const lemmas = (t) => new Set(lemma.analyze(String(t || "")).lemmas);
function overlap(a, b) {
  if (!a.size) return 0;
  let hit = 0;
  for (const x of a) if (b.has(x)) hit++;
  return hit / a.size;
}

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l)).slice(0, 12);

const models = [
  { id: "qwen3-0.6b-q4km", file: "Qwen3-0.6B-Q4_K_M.gguf", family: "qwen3", role: "выбранная (объяснения)" },
  { id: "smollm2-135m-q4km", file: "SmolLM2-135M-Instruct-Q4_K_M.gguf", family: "chatml", role: "fallback" },
  { id: "gemma3-270m-q4km", file: "gemma-3-270m-it-Q4_K_M.gguf", family: "gemma", role: "быстрый режим" },
];
const only = process.argv.slice(2);
const out = [];

function gen(m, sys, user) {
  const buf = new Uint8Array(16384);
  const met = new Float64Array(5);
  const rc = lib.symbols.lb_gen(ROOT + "/models/" + m.file, wrap(m.family, sys, user), 3072, 96, buf, buf.length, met);
  if (rc !== 0) return { text: "", ms: 0, rc };
  const z = buf.indexOf(0);
  return { text: new TextDecoder().decode(z < 0 ? buf : buf.subarray(0, z)), ms: met[2], rc: 0 };
}

for (const m of models) {
  if (only.length && !only.includes(m.id)) continue;
  console.log(`\n=== ${m.id} (${m.role}) ===`);
  const rows = [];
  for (const g of gold) {
    const hit = core.recallDocs(DB, g.query, { limit: 1 })[0];
    const chunk = hit ? String(hit.text || "").replace(/\s+/g, " ").slice(0, 600) : g.query;
    const chunkL = lemmas(chunk);

    const why = gen(m, SYS_WHY, `Запрос: ${g.query}\nФрагмент: ${chunk}`);
    const whyText = clean(why.text);
    const whyGrounded = whyText ? overlap(lemmas(whyText), chunkL) >= 0.2 : false;

    const rw = gen(m, SYS_REWRITE, `Запрос: ${g.query}`);
    const rwText = clean(rw.text);
    const inL = lemmas(g.query);
    const rwOk = rwText && rwText.toLowerCase() !== g.query.toLowerCase() && overlap(inL, lemmas(rwText)) >= 0.5;

    rows.push({
      q: g.id,
      whyFormat: !!whyText, whyGrounded, whyMs: why.ms, whyRaw: why.text.slice(0, 220),
      rwFormat: !!rwText, rwOk, rwMs: rw.ms, rwRaw: rw.text.slice(0, 220),
    });
    const r = rows.at(-1);
    console.log(`  ${g.id}: why=${r.whyFormat ? 1 : 0}/${r.whyGrounded ? 1 : 0} rewrite=${r.rwFormat ? 1 : 0}/${r.rwOk ? 1 : 0} ${(why.ms + rw.ms).toFixed(0)} мс`);
  }
  const rate = (key) => rows.length ? rows.filter((r) => r[key]).length / rows.length : 0;
  const s = {
    ...m, runtime, n: rows.length,
    whyFormat: rate("whyFormat"), whyGrounded: rate("whyGrounded"),
    rwFormat: rate("rwFormat"), rwOk: rate("rwOk"),
    avgMs: rows.length ? rows.reduce((a, r) => a + r.whyMs + r.rwMs, 0) / rows.length : 0,
    rows,
  };
  out.push(s);
  console.log(`  ИТОГО: объяснение формат ${(s.whyFormat * 100).toFixed(0)}% привязка ${(s.whyGrounded * 100).toFixed(0)}% | переформулировка формат ${(s.rwFormat * 100).toFixed(0)}% качество ${(s.rwOk * 100).toFixed(0)}% | ${s.avgMs.toFixed(0)} мс/пару`);
}

writeFileSync(ROOT + "/tools/llama-bench/role-bench.json", JSON.stringify({ runtime, results: out }, null, 2));
console.log("\nJSON: tools/llama-bench/role-bench.json");
