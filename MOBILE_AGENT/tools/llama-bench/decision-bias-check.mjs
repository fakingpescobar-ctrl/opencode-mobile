#!/usr/bin/env bun
// Контроль позиционного смещения decision-модели на held-out подвыборке.
// Те же 5 gold, но кандидаты ядра сдвинуты на 2 позиции: если модель читает
// содержимое, правильный ответ сохранится; если любит последнюю букву — нет.
import { dlopen, FFIType } from "bun:ffi";
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const MODEL = ROOT + "/models/hs-decide-1-flash-bf16.gguf";
const idx = await import(ROOT + "/opencode/memory-core/memory-index.mjs");
const core = await import(ROOT + "/opencode/memory-core/memory-core.js");
const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_code_ids: { args: [FFIType.cstring, FFIType.cstring, FFIType.ptr, FFIType.i32], returns: FFIType.i32 },
  lb_score: { args: [FFIType.cstring, FFIType.cstring, FFIType.ptr, FFIType.i32,
    FFIType.ptr, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 },
});
if (!lib.symbols.lb_version().includes("musl-8")) process.exit(2);

const L = "ABCDE";
const A = 4;
const groups = new Int32Array(L.length * A).fill(-1);
for (let i = 0; i < L.length; i++) {
  const ids = new Int32Array(A);
  const n = lib.symbols.lb_code_ids(MODEL, L[i], ids, A);
  if (n < 1) process.exit(3);
  for (let a = 0; a < A; a++) groups[i * A + a] = a < n ? ids[a] : -1;
}
const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(ROOT + "/memory/gold.jsonl", "utf8").split("\n")
  .filter(Boolean).map((x) => JSON.parse(x))
  .filter((g) => createHash("sha256").update(g.query).digest("hex").charCodeAt(0) % 5 === 0);

const rows = [];
for (const g of gold) {
  const base = core.recallDocs(DB, g.query, { limit: L.length });
  const hits = [...base.slice(2), ...base.slice(0, 2)];
  const lines = hits.map((hit, i) => {
    const text = String(hit.text || "").replace(/\s+/g, " ").slice(0, 220);
    return `  ${L[i]}: ${hit.source} — ${String(hit.heading || "").slice(0, 60)}: ${text}`;
  });
  const prompt = "<|im_start|>user\nState:\n" + g.query +
    "\n\nQuestions:\n[Q1] (choice) Which memory chunk is relevant to the state?\n" +
    lines.join("\n") + "\n\n<|im_end|>\n<|im_start|>assistant\nQ1:\n";
  const probs = new Float64Array(L.length);
  const met = new Float64Array(4);
  const rc = lib.symbols.lb_score(MODEL, prompt, new Int32Array([-2]), 1,
    groups, L.length, A, probs, met);
  if (rc !== 0) { console.log(g.id, "rc=" + rc); continue; }
  const top = [...probs].map((p, i) => ({ i, p })).sort((a, b) => b.p - a.p)[0].i;
  const row = {
    id: g.id, expect: g.expect_source, pick: L[top], pick_source: hits[top].source,
    hit: hits[top].source === g.expect_source, original_position: base.findIndex((x) => x.source === g.expect_source),
    ms: Math.round(met[1]),
  };
  rows.push(row);
  console.log(`${g.id}: ${row.pick} -> ${row.pick_source} ${row.hit ? "OK" : "MISS"} (${row.ms} мс)`);
}
writeFileSync(ROOT + "/tools/llama-bench/decision-bias.json", JSON.stringify({ rows }, null, 2) + "\n");
console.log(`ИТОГ сдвинутых кандидатов: ${rows.filter((r) => r.hit).length}/${rows.length}`);
