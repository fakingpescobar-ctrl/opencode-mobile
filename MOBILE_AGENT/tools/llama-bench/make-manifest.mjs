#!/usr/bin/env bun
// Сформировать манифест выбранных GGUF: репо, файл, точный размер и sha256 из HF API.
import { writeFileSync } from "node:fs";
// Метаданные не выдумываем: latency из БЕНЧМАРК_ЛЛМ.md, качество из
// КАЧЕСТВО_ЛЛМ.md, лицензии из ВЫБОР_МОДЕЛИ_LLM.md. Регенерация манифеста
// обязана сохранять эти поля — UI диагностики показывает их пользователю.
const REPOS = {
  "smollm2-135m-q4km": ["bartowski/SmolLM2-135M-Instruct-GGUF", "SmolLM2-135M-Instruct-Q4_K_M.gguf"],
  "qwen3-0.6b-q2k": ["unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q2_K.gguf"],
  "qwen3-0.6b-q4km": ["unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"],
  "gemma3-270m-q4km": ["unsloth/gemma-3-270m-it-GGUF", "gemma-3-270m-it-Q4_K_M.gguf"],
  "lfm2.5-350m-q4km": ["LiquidAI/LFM2.5-350M-GGUF", "LFM2.5-350M-Q4_K_M.gguf"],
  "lfm2.5-1.2b-q4km": ["LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf"],
  "lfm3-1.7b-q4km": ["LiquidAI/LFM3-1.7B-GGUF", "LFM3-1.7B-Q4_K_M.gguf"],
};
const META = {
  "smollm2-135m-q4km": { purpose: "Резерв: быстрые короткие объяснения", ttftMs: 270, decodeTs: 37.2, total24Ms: 889, license: "apache-2.0", quality: "Слабее ядра: 6% recall на gold" },
  "qwen3-0.6b-q2k": { purpose: "Компактный режим при нехватке места", ttftMs: 591, decodeTs: 21.5, total24Ms: 1665, license: "apache-2.0", quality: "Та же модель, ниже квант" },
  "qwen3-0.6b-q4km": { purpose: "Основная: объяснения и переформулировка", ttftMs: 433, decodeTs: 27.0, total24Ms: 1286, license: "apache-2.0", quality: "Лучший разбор ответов (60%), но 24% recall" },
  "gemma3-270m-q4km": { purpose: "Самый быстрый опытный режим", ttftMs: 169, decodeTs: 37.0, total24Ms: 795, license: "gemma (ограниченная)", quality: "0% разбора, отсеяна" },
  "lfm2.5-350m-q4km": { purpose: "Эксперимент: быстрая маленькая модель", ttftMs: 257, decodeTs: 37.2, total24Ms: 875, license: "lfm1.0 (other)", quality: "Не проходила quality-gate" },
  "lfm2.5-1.2b-q4km": { purpose: "Эксперимент: средняя модель", ttftMs: 749, decodeTs: 21.9, total24Ms: 1798, license: "lfm1.0", quality: "Не проходила quality-gate" },
  "lfm3-1.7b-q4km": { purpose: "Эксперимент: крупная модель, медленная", ttftMs: 1080, decodeTs: 17.4, total24Ms: 2408, license: "lfm1.0", quality: "Не проходила quality-gate" },
};
const selected = Object.keys(META).map((id) => {
  const [repo, file] = REPOS[id];
  return [id, repo, file];
});
const out = [];
for (const [id, repo, file] of selected) {
  const r = await fetch("https://huggingface.co/api/models/" + repo + "?blobs=true");
  if (!r.ok) { console.error("HF HTTP " + r.status + " для " + repo); process.exit(2); }
  const j = await r.json();
  const s = (j.siblings || []).find((x) => x.rfilename === file);
  if (!s) { console.error("файл не найден: " + repo + "/" + file); process.exit(3); }
  const sha = s.lfs?.sha256 || s.lfs?.oid;
  if (!sha || sha.length !== 64) { console.error("нет валидного LFS sha для " + file); process.exit(4); }
  out.push({ id, repo, file, size: s.size, sha256: sha, url: `https://huggingface.co/${repo}/resolve/main/${file}?download=true`, ...META[id] });
  console.log(`${id}: ${(s.size / 1e6).toFixed(1)} МБ sha=${sha.slice(0, 16)}…`);
}
writeFileSync("/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT/tools/llama-bench/models.json", JSON.stringify(out, null, 2));
console.log("манифест записан: models.json (" + out.length + ")");
