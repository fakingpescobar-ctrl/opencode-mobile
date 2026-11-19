#!/usr/bin/env bun
// Сформировать манифест выбранных GGUF: репо, файл, точный размер и sha256 из HF API.
import { writeFileSync } from "node:fs";
const selected = [
  ["smollm2-135m-q4km", "bartowski/SmolLM2-135M-Instruct-GGUF", "SmolLM2-135M-Instruct-Q4_K_M.gguf"],
  ["qwen3-0.6b-q2k", "unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q2_K.gguf"],
  ["qwen3-0.6b-q4km", "unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"],
  ["gemma3-270m-q4km", "unsloth/gemma-3-270m-it-GGUF", "gemma-3-270m-it-Q4_K_M.gguf"],
  ["lfm2.5-350m-q4km", "LiquidAI/LFM2.5-350M-GGUF", "LFM2.5-350M-Q4_K_M.gguf"],
  ["lfm2.5-1.2b-q4km", "LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf"],
  ["lfm3-1.7b-q4km", "LiquidAI/LFM3-1.7B-GGUF", "LFM3-1.7B-Q4_K_M.gguf"],
];
const out = [];
for (const [id, repo, file] of selected) {
  const r = await fetch("https://huggingface.co/api/models/" + repo + "?blobs=true");
  if (!r.ok) { console.error("HF HTTP " + r.status + " для " + repo); process.exit(2); }
  const j = await r.json();
  const s = (j.siblings || []).find((x) => x.rfilename === file);
  if (!s) { console.error("файл не найден: " + repo + "/" + file); process.exit(3); }
  const sha = s.lfs?.sha256 || s.lfs?.oid;
  if (!sha || sha.length !== 64) { console.error("нет валидного LFS sha для " + file); process.exit(4); }
  out.push({ id, repo, file, size: s.size, sha256: sha, url: `https://huggingface.co/${repo}/resolve/main/${file}?download=true` });
  console.log(`${id}: ${(s.size / 1e6).toFixed(1)} МБ sha=${sha.slice(0, 16)}…`);
}
writeFileSync("/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT/tools/llama-bench/models.json", JSON.stringify(out, null, 2));
console.log("манифест записан: models.json (" + out.length + ")");
