#!/usr/bin/env bun
// Пульс буфера сигналов. Никакого демона: вызывается руками раз в цикл.
// Показывает, пора ли звать куратора, и (с --stage) переводит raw в staging.
//
// Запуск:
//   bun tools/signal-status.mjs --dir=<filesDir> [--stage]
//   bun tools/signal-status.mjs --dir=<filesDir> --stage --run
//
// --run дополнительно печатает готовую команду curate-gold.mjs. Сам куратор
// намеренно не вызывается автоматически: y/n/edit — ручное решение.

import { writeFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const store = await import(ROOT + "/opencode/memory-core/memory-adaptive-store.js");

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith("--" + name + "="));
  return hit ? hit.slice(name.length + 3) : fallback;
};
// Живой MCP поднимается с MCP_MEMORY_DIR (сейчас общий воркспейс opencode-home),
// поэтому пульс должен видеть тот же буфер без ручного --dir. Явный --dir и
// MEMORY_ADAPTIVE_DIR по-прежнему переопределяют путь (тесты/смок).
const DIR = arg("dir", process.env.MEMORY_ADAPTIVE_DIR || process.env.MCP_MEMORY_DIR || process.cwd());
const STAGE = process.argv.includes("--stage");
const PRINT = process.argv.includes("--run");

const all = store.listSignalCandidates(DIR);
const byStatus = {}, byOrigin = {};
for (const c of all) {
  byStatus[c.status] = (byStatus[c.status] || 0) + 1;
  byOrigin[c.origin] = (byOrigin[c.origin] || 0) + 1;
}
const raw = all.filter((c) => c.status === "raw");
const eligible = raw.length >= store.SIGNAL_LIMITS.curateThreshold;
let staged = 0;
if (STAGE && eligible) {
  for (const c of raw) {
    if (store.setSignalCandidateStatus(DIR, c.id, "staging", "порог курации").ok) staged++;
  }
}

const report = {
  total: all.length,
  by_status: byStatus,
  by_origin: byOrigin,
  raw_unique: raw.length,
  threshold: store.SIGNAL_LIMITS.curateThreshold,
  eligible,
  staged,
  limits: { ...store.SIGNAL_LIMITS },
  at: Date.now(),
};
const OUT = ROOT + "/tools/llama-bench/signal-status.json";
writeFileSync(OUT, JSON.stringify(report, null, 2) + "\n");

console.log(`Сигналов всего: ${report.total}`);
console.log(`По статусу: ${JSON.stringify(byStatus)}`);
console.log(`По происхождению: ${JSON.stringify(byOrigin)}`);
console.log(`raw уникальных: ${raw.length} / ${report.threshold}`);
console.log(eligible ? "ПОРА КУРИРОВАТЬ" : "ещё рано — копим сигналы");
if (staged) console.log(`В staging переведено: ${staged}`);
if (PRINT && eligible) {
  console.log("\nДальше руками:");
  console.log(`  bun ${ROOT}/tools/curate-gold.mjs --dir="${DIR}"`);
  console.log(`  bun ${ROOT}/tools/baseline.mjs`);
}
console.log("Протокол: " + OUT);
