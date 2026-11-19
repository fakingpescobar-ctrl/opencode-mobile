#!/usr/bin/env bun
// Quality-gate LLM как семантического сопроцессора ядра памяти.
// Ядро детерминированно даёт 8 кандидатов; модель должна выбрать до 3
// релевантных и объяснить почему. Мерим долю валидного JSON, recall источника
// из gold, точность выбора и наличие поля why.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const CORE = ROOT + "/opencode/memory-core/";
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");

const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_gen: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.i32, FFIType.ptr],
    returns: FFIType.i32,
  },
});
const only = process.argv.slice(2);
const runtime = lib.symbols.lb_version();
if (!runtime.includes("musl-4")) {
  console.error("нужна обёртка llama-bench-musl-4 с lb_gen, сейчас: " + runtime);
  process.exit(2);
}

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));

const SYS =
  "Ты семантический сопроцессор ядра памяти. Не думай вслух. " +
  "Выбери из списка кандидатов до 3 чанков, самых релевантных запросу. " +
  "Ответь строго одним JSON объектом без другого текста: " +
  "{\"ids\":[\"A\",\"B\"],\"why\":\"кратко почему\"}";

function wrap(family, sys, user) {
  if (family === "gemma") {
    return `<start_of_turn>user\n${sys}\n\n${user}<end_of_turn>\n<start_of_turn>model\n`;
  }
  return `<|im_start|>system\n${sys}<|im_end|>\n<|im_start|>user\n${user}<|im_end|>\n<|im_start|>assistant\n`;
}

function parseAnswer(text) {
  const m = String(text).match(/\{[\s\S]*\}/);
  if (!m) return null;
  try {
    const o = JSON.parse(m[0]);
    if (!o || !Array.isArray(o.ids)) return null;
    return { ids: o.ids.map(String), why: typeof o.why === "string" ? o.why.trim() : "" };
  } catch { return null; }
}

const models = [
  { id: "qwen3-0.6b-q4km", file: "Qwen3-0.6B-Q4_K_M.gguf", family: "chatml", role: "основная" },
  { id: "gemma3-270m-q4km", file: "gemma-3-270m-it-Q4_K_M.gguf", family: "gemma", role: "быстрый режим" },
  { id: "smollm2-135m-q4km", file: "SmolLM2-135M-Instruct-Q4_K_M.gguf", family: "chatml", role: "fallback" },
];

const out = [];
for (const m of models) {
  if (only.length && !only.includes(m.id)) continue;
  const path = ROOT + "/models/" + m.file;
  console.log(`\n=== ${m.id} (${m.role}) ===`);
  const rows = [];
  for (const g of gold) {
    const hits = core.recallDocs(DB, g.query, { limit: 8 });
    const letters = "ABCDEFGH";
    const expectedIn = hits.map((h, i) => letters[i] && h.source === g.expect_source ? letters[i] : null).filter(Boolean);
    const lines = hits.map((h, i) =>
      `[${letters[i]}] ${h.source} — ${String(h.heading || "").slice(0, 60)}: ${h.text.replace(/\s+/g, " ").slice(0, 260)}`);
    let user = `Запрос: ${g.query}\nКандидаты:\n${lines.join("\n")}`;
    // Qwen3 по умолчанию уходит в <think> и не успевает выдать JSON за бюджет.
    if (m.id.startsWith("qwen3")) user += "\n/no_think";
    const prompt = wrap(m.family, SYS, user);
    const buf = new Uint8Array(16384);
    const met = new Float64Array(5);
    const rc = lib.symbols.lb_gen(path, prompt, 2048, 80, buf, buf.length, met);
    if (rc !== 0) { console.error(`  ${g.id}: rc=${rc}`); continue; }
    const raw = new TextDecoder().decode(buf);
    const answer = parseAnswer(raw);
    const chosen = answer ? answer.ids.map((id) => hits[letters.indexOf(id)]).filter(Boolean) : [];
    const relevant = (h) => h && h.source === g.expect_source;
    rows.push({
      q: g.id,
      covered: expectedIn.length > 0,
      json: !!answer,
      why: !!(answer && answer.why.length > 0),
      recall: expectedIn.length > 0 ? chosen.some(relevant) : null,
      precision: answer && answer.ids.length ? chosen.filter(relevant).length / answer.ids.length : 0,
      baseline: hits.slice(0, 3).some(relevant),
      ms: met[2], tokens: met[3], raw: raw.slice(0, 700),
    });
    console.log(`  ${g.id}: json=${rows.at(-1).json ? 1 : 0} recall=${rows.at(-1).recall === null ? "n/a" : rows.at(-1).recall ? 1 : 0} why=${rows.at(-1).why ? 1 : 0} ${met[2].toFixed(0)} мс`);
  }
  const covered = rows.filter((r) => r.covered);
  const rate = (arr, key) => arr.length ? arr.filter((r) => r[key]).length / arr.length : 0;
  out.push({
    ...m,
    runtime,
    answered: rows.length,
    coverage: covered.length / gold.length,
    jsonValid: rate(covered, "json"),
    whyPresent: rate(covered, "why"),
    recall: rate(covered, "recall"),
    precision: covered.length ? covered.reduce((s, r) => s + r.precision, 0) / covered.length : 0,
    baselineRecall: rate(covered, "baseline"),
    avgMs: rows.length ? rows.reduce((s, r) => s + r.ms, 0) / rows.length : 0,
    avgTokens: rows.length ? rows.reduce((s, r) => s + r.tokens, 0) / rows.length : 0,
    rows,
  });
  const s = out.at(-1);
  console.log(`  ИТОГО: JSON ${(s.jsonValid * 100).toFixed(0)}% | recall ${(s.recall * 100).toFixed(0)}% | why ${(s.whyPresent * 100).toFixed(0)}% | baseline ${(s.baselineRecall * 100).toFixed(0)}% | ${s.avgMs.toFixed(0)} мс`);
  writeFileSync(ROOT + "/tools/llama-bench/quality-gate.json", JSON.stringify({ runtime, results: out }, null, 2));
}

writeFileSync(ROOT + "/tools/llama-bench/quality-gate.json", JSON.stringify({ runtime, results: out }, null, 2));
const md = [
  "# QUALITY-GATE LLM НАД ЯДРОМ ПАМЯТИ (21.11.2026)",
  "",
  `Рантайм: ${runtime}. Ядро детерминированно отдаёт 8 кандидатов на каждый из 20 gold-вопросов;`,
  "модель должна вернуть JSON `{\"ids\":[...],\"why\":\"...\"}` с до 3 выбранными чанками.",
  "Нативные чат-шаблоны: ChatML для Qwen3/SmolLM2, `<start_of_turn>` для gemma-3.",
  "Базис для сравнения — первые 3 кандидата ядра (выбор без LLM).",
  "",
  "| Модель | Роль | JSON валиден | Recall источника | Точность | why | Baseline ядра | Ответ, мс |",
  "|---|---|---:|---:|---:|---:|---:|---:|",
  ...out.map((r) => `| ${r.id} | ${r.role} | ${(r.jsonValid * 100).toFixed(0)}% | ${(r.recall * 100).toFixed(0)}% | ${(r.precision * 100).toFixed(0)}% | ${(r.whyPresent * 100).toFixed(0)}% | ${(r.baselineRecall * 100).toFixed(0)}% | ${r.avgMs.toFixed(0)} |`),
  "",
  "Сырые ответы: `tools/llama-bench/quality-gate.json`.",
  "",
].join("\n");
writeFileSync(ROOT + "/КАЧЕСТВО_ЛЛМ.md", md);
console.log("\nзаписано: КАЧЕСТВО_ЛЛМ.md и tools/llama-bench/quality-gate.json");
