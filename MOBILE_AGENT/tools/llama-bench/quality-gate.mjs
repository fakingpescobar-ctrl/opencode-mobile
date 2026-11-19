#!/usr/bin/env bun
// Quality-gate LLM как семантического сопроцессора ядра памяти.
// Ядро детерминированно даёт 6 кандидатов; модель должна выбрать до 3
// релевантных. Промпт без готового примера: малые модели копировали его дословно
// (проверено). Ответ разбирается гибко под формат каждого семейства.
// Мерим: долю разбираемого ответа, recall нужного источника из gold, точность
// выбора и сравнение с базовым топом ядра без LLM.
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
    args: [FFIType.cstring, FFIType.cstring, FFIType.i32, FFIType.i32,
           FFIType.ptr, FFIType.i32, FFIType.ptr],
    returns: FFIType.i32,
  },
});
const only = process.argv.slice(2);
const runtime = lib.symbols.lb_version();
if (!runtime.includes("musl-5")) {
  console.error("нужна обёртка llama-bench-musl-5 с lb_gen_g, сейчас: " + runtime);
  process.exit(2);
}

const SYS =
  "Ты семантический сопроцессор ядра памяти. Выбери из списка кандидатов " +
  "до 3 чанков, самых релевантных запросу. Ответь только списком букв " +
  "выбранных кандидатов в квадратных скобках через запятую.";

function wrap(family, sys, user) {
  if (family === "gemma") {
    return `<start_of_turn>user\n${sys}\n\n${user}<end_of_turn>\n<start_of_turn>model\n`;
  }
  // Qwen3: /no_think убирает пустой <think> и экономит бюджет токенов.
  const s = family === "qwen3" ? sys + "\n/no_think" : sys;
  return `<|im_start|>system\n${s}<|im_end|>\n<|im_start|>user\n${user}<|im_end|>\n<|im_start|>assistant\n`;
}

// Разные семейства выдают разные оболочки: Qwen3 — [ "A", "C" ] внутри ```json,
// gemma — {"id":"A",...}, SmolLM2 может вообще повторить промпт. Достаём буквы.
function parseAnswer(text) {
  const s = String(text);
  const bracket = s.match(/\[([^\]]*)\]/);
  if (bracket) {
    const ids = bracket[1].match(/[A-F]/g);
    if (ids && ids.length) return ids;
  }
  const single = s.match(/"id"\s*:\s*"([A-F])"/);
  return single ? [single[1]] : null;
}

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));

const models = [
  { id: "qwen3-0.6b-q4km", file: "Qwen3-0.6B-Q4_K_M.gguf", family: "qwen3", role: "основная" },
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
    const hits = core.recallDocs(DB, g.query, { limit: 6 });
    const letters = "ABCDEF";
    const expectedIn = hits.map((h, i) => h.source === g.expect_source ? letters[i] : null).filter(Boolean);
    const lines = hits.map((h, i) =>
      `[${letters[i]}] ${h.source} — ${String(h.heading || "").slice(0, 50)}: ${h.text.replace(/\s+/g, " ").slice(0, 180)}`);
    const user = `Запрос: ${g.query}\nКандидаты:\n${lines.join("\n")}`;
    const prompt = wrap(m.family, SYS, user);
    const buf = new Uint8Array(16384);
    const met = new Float64Array(5);
    const rc = lib.symbols.lb_gen(path, prompt, 3072, 48, buf, buf.length, met);
    if (rc !== 0) { console.error(`  ${g.id}: rc=${rc}`); continue; }
    const z = buf.indexOf(0);
    const raw = new TextDecoder().decode(z < 0 ? buf : buf.subarray(0, z));
    const answer = parseAnswer(raw);
    const chosen = answer ? answer.map((id) => hits[letters.indexOf(id)]).filter(Boolean) : [];
    const relevant = (h) => h && h.source === g.expect_source;
    rows.push({
      q: g.id,
      covered: expectedIn.length > 0,
      parsed: !!answer,
      recall: expectedIn.length > 0 ? chosen.some(relevant) : null,
      precision: answer && answer.length ? chosen.filter(relevant).length / answer.length : 0,
      baseline: hits.slice(0, 3).some(relevant),
      ms: met[2], tokens: met[3], raw: raw.slice(0, 200),
    });
    const r = rows.at(-1);
    console.log(`  ${g.id}: parse=${r.parsed ? 1 : 0} recall=${r.recall === null ? "n/a" : r.recall ? 1 : 0} base=${r.baseline ? 1 : 0} ${met[2].toFixed(0)} мс`);
  }
  const covered = rows.filter((r) => r.covered);
  const rate = (arr, key) => arr.length ? arr.filter((r) => r[key]).length / arr.length : 0;
  out.push({
    ...m, runtime, answered: rows.length,
    coverage: covered.length / gold.length,
    parsed: rate(rows, "parsed"),
    recall: rate(covered, "recall"),
    precision: covered.length ? covered.reduce((s, r) => s + r.precision, 0) / covered.length : 0,
    baselineRecall: rate(covered, "baseline"),
    avgMs: rows.length ? rows.reduce((s, r) => s + r.ms, 0) / rows.length : 0,
    avgTokens: rows.length ? rows.reduce((s, r) => s + r.tokens, 0) / rows.length : 0,
    rows,
  });
  const s = out.at(-1);
  console.log(`  ИТОГО: формат ${(s.parsed * 100).toFixed(0)}% | recall ${(s.recall * 100).toFixed(0)}% | точность ${(s.precision * 100).toFixed(0)}% | baseline ядра ${(s.baselineRecall * 100).toFixed(0)}% | ${s.avgMs.toFixed(0)} мс`);
  writeFileSync(ROOT + "/tools/llama-bench/quality-gate.json", JSON.stringify({ runtime, results: out }, null, 2));
}

writeFileSync(ROOT + "/tools/llama-bench/quality-gate.json", JSON.stringify({ runtime, results: out }, null, 2));
const md = [
  "# QUALITY-GATE LLM НАД ЯДРОМ ПАМЯТИ (21.11.2026)",
  "",
  `Рантайм: ${runtime}. Ядро детерминированно отдаёт 6 кандидатов на каждый из 20 gold-вопросов;`,
  "модель выбирает до 3 букв. Разбор гибкий: Qwen3 отдаёт массив в ```json, gemma — поле `id`.",
  "Нативные шаблоны: Qwen3/SmolLM2 — ChatML, gemma-3 — `<start_of_turn>`.",
  "Поле `why` куски получают детерминированно от ядра, не от LLM.",
  "GBNF-самплер через наш FFI-мост дал segfault — отдельная проблема моста, в gate не используется.",
  "",
  "| Модель | Роль | Формат | Recall источника | Точность | Baseline ядра | Ответ, мс |",
  "|---|---|---:|---:|---:|---:|---:|",
  ...out.map((r) => `| ${r.id} | ${r.role} | ${(r.parsed * 100).toFixed(0)}% | ${(r.recall * 100).toFixed(0)}% | ${(r.precision * 100).toFixed(0)}% | ${(r.baselineRecall * 100).toFixed(0)}% | ${r.avgMs.toFixed(0)} |`),
  "",
  "Сырые ответы: `tools/llama-bench/quality-gate.json`.",
  "",
].join("\n");
writeFileSync(ROOT + "/КАЧЕСТВО_ЛЛМ.md", md);
console.log("\nзаписано: КАЧЕСТВО_ЛЛМ.md и tools/llama-bench/quality-gate.json");
