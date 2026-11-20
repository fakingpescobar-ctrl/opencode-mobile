#!/usr/bin/env bun
// Замер «до/после» подключения LLM-сопроцессора к ядру памяти.
// Один и тот же gold. Три режима:
//   1) ядро: детерминированный pack (источник истины);
//   2) ядро + синонимы LLM: тот же поиск, но с подмешанными формулировками;
//   3) LLM-селектор: что выбрала модель из узкого коридора (справочно).
// Ничего не пишем в рабочую БД, память не мутируем.
//
// Запуск:
//   NATIVE=$(dirname "$(pm path org.opencode.mobile.debug | sed 's/package://' | head -1)")/lib/arm64
//   LD_LIBRARY_PATH="/data/user/0/org.opencode.mobile.debug/files/musl:$NATIVE" \
//     "$NATIVE/libldmusl.so" "$NATIVE/libbun-musl.so" \
//     MOBILE_AGENT/tools/llama-bench/llm-coprocessor-bench.mjs [число_запросов]

import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const CORE = join(ROOT, "opencode", "memory-core") + "/";
const LIMIT = Math.max(1, Math.min(20, Number(process.argv[2]) || 12));
const CORRIDOR = 8;
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");
const llm = await import(CORE + "memory-llm.js");

const DB = new Database(":memory:");
idx.indexDocs(DB, ROOT);
const gold = readFileSync(join(ROOT, "memory", "gold.jsonl"), "utf8")
  .trim().split("\n").map((l) => JSON.parse(l)).slice(0, LIMIT);

// Парафразы тех же фактов: именно здесь сопроцессор должен был бы показать
// пользу, если бы умел связывать скрытые связи. Сравниваем честно с тем же
// детерминированным паком ядра.
const PARAPHRASES = [
  { id: "p01", query: "какой каталог плагин использует для отчёта", expect_source: "СОСТОЯНИЕ.md" },
  { id: "p02", query: "переменная окружения с путём отчёта мoltbook", expect_source: "ХАРНЕС.md" },
  { id: "p03", query: "как сверить хэш установленного приложения", expect_source: "ХАРНЕС.md" },
  { id: "p04", query: "можно ли применить совет извне без человека", expect_source: "docs/MOLTBOOK_ADVICE.md" },
  { id: "p05", query: "что делать вместо безвозвратного удаления записи", expect_source: "ПРАВИЛА.md" },
  // Эталон исправлен 20.11: порты 4199/4200/4201 описаны в ХАРНЕС.md,
  // АРХИТЕКТУРА_ПАМЯТИ.md их не содержит. Старый эталон держал p06 на 6-м ранге.
  { id: "p06", query: "какие порты слушает сервер памяти", expect_source: "ХАРНЕС.md" },
];

const boot = await llm.initLlm();
console.log("LLM:", JSON.stringify({ state: boot.state, model: boot.model, reason: boot.reason }));

const rows = [];
for (const g of gold) {
  const t0 = performance.now();
  const hits = core.recallDocs(DB, g.query, { limit: CORRIDOR });
  const base = core.memoryPack(DB, g.query, { budgetTokens: 2048, limit: 12 });
  const baseMs = Math.round(performance.now() - t0);
  const baseHit = base.sources.includes(g.expect_source);

  const r = await llm.llmSearch(g.query, hits, { maxTokens: 48, maxLatencyMs: 8000 });
  const llmHit = (r.selectedIds || []).some((id) => {
    const h = hits.find((x) => x.id === id);
    return h && h.source === g.expect_source;
  });

  // Синонимы идут в повторный БЫСТРЫЙ проход ядра — это и есть полезная
  // работа сопроцессора. Сравниваем с базовым паком на том же корпусе.
  const t1 = performance.now();
  const extra = (r.reformulations || []).slice(0, 3).join(" ");
  const expanded = extra
    ? core.memoryPack(DB, `${g.query} ${extra}`, { budgetTokens: 2048, limit: 12 })
    : base;
  const expandedMs = Math.round(performance.now() - t1);
  const expandedHit = expanded.sources.includes(g.expect_source);

  rows.push({
    id: g.id, query: g.query, expect: g.expect_source,
    baseHit, expandedHit, llmHit, baseMs, expandedMs, llmMs: r.latencyMs,
    fallback: r.fallback, reason: r.reason,
    selected: r.selectedIds, reformulations: r.reformulations,
  });
  console.log(g.id, "ядро", baseHit ? "✓" : "✗",
    "+синонимы", expandedHit ? "✓" : "✗", "LLM", llmHit ? "✓" : "✗",
    "|", baseMs + "мс", expandedMs + "мс", r.latencyMs + "мс",
    r.fallback ? "fallback=" + r.reason : "", JSON.stringify(r.reformulations || []));
}
const paraphraseRows = [];
for (const g of PARAPHRASES) {
  const base = core.memoryPack(DB, g.query, { budgetTokens: 2048, limit: 12 });
  const hits = core.recallDocs(DB, g.query, { limit: CORRIDOR });
  const r = await llm.llmSearch(g.query, hits, { maxTokens: 48, maxLatencyMs: 8000 });
  const llmHit = (r.selectedIds || []).some((id) => {
    const h = hits.find((x) => x.id === id);
    return h && h.source === g.expect_source;
  });
  paraphraseRows.push({
    id: g.id, query: g.query, baseHit: base.sources.includes(g.expect_source),
    llmHit, llmMs: r.latencyMs, reformulations: r.reformulations,
  });
  console.log("ПАРАФРАЗ", g.id, base.sources.includes(g.expect_source) ? "ядро✓" : "ядро✗",
    llmHit ? "LLM✓" : "LLM✗", r.latencyMs + "мс", JSON.stringify(r.reformulations || []));
}
DB.close();

const rate = (key) => rows.filter((r) => r[key]).length / rows.length;
const avg = (key) => Math.round(rows.reduce((s, r) => s + r[key], 0) / rows.length);
const out = {
  at: new Date().toISOString(), n: rows.length,
  model: boot.model, runtime: boot.runtime, warmMs: boot.warmMs, corridor: CORRIDOR,
  baseline: { recall: rate("baseHit"), ms: avg("baseMs") },
  coreWithSynonyms: { recall: rate("expandedHit"), ms: avg("expandedMs") + avg("llmMs") },
  llmSelector: { recall: rate("llmHit"), ms: avg("llmMs") },
  fallbacks: rows.filter((r) => r.fallback).length,
  paraphrases: {
    n: paraphraseRows.length,
    baselineRecall: paraphraseRows.filter((r) => r.baseHit).length / paraphraseRows.length,
    llmRecall: paraphraseRows.filter((r) => r.llmHit).length / paraphraseRows.length,
  },
  rows,
  paraphraseRows,
};
console.log("\nИТОГ:", JSON.stringify({
  baseline: out.baseline, coreWithSynonyms: out.coreWithSynonyms,
  llmSelector: out.llmSelector, fallbacks: out.fallbacks, paraphrases: out.paraphrases,
}, null, 2));

const md = [
  "# Замер LLM-сопроцессора памяти",
  "",
  `- Дата: ${out.at}`,
  `- Модель: ${out.model} (${out.runtime}), прогрев ${out.warmMs} мс`,
  `- Выборка: ${out.n} gold-запросов + ${out.paraphrases.n} парафраз, коридор ${CORRIDOR}`,
  "",
  "| Режим | recall по ожидаемому источнику | латентность на запрос |",
  "| --- | --- | --- |",
  `| Ядро (источник истины) | ${out.baseline.recall.toFixed(3)} | ${out.baseline.ms} мс |`,
  `| Ядро + синонимы LLM | ${out.coreWithSynonyms.recall.toFixed(3)} | ${out.coreWithSynonyms.ms} мс |`,
  `| LLM как селектор (справочно) | ${out.llmSelector.recall.toFixed(3)} | ${out.llmSelector.ms} мс |`,
  "",
  `### Парафразы (${out.paraphrases.n})`,
  "",
  "| Режим | recall |",
  "| --- | --- |",
  `| Ядро | ${out.paraphrases.baselineRecall.toFixed(3)} |`,
  `| LLM-селектор | ${out.paraphrases.llmRecall.toFixed(3)} |`,
  "",
  `Fallback-ов: ${out.fallbacks}/${out.n}. LLM работает селектором и ` +
    "переформулировщиком поверх кандидатов ядра; детерминированный `pack` " +
    "всегда лежит в ответе `memory_llm_search`. При пустом/невалидном ответе " +
    "или таймауте агент получает тот же pack с `fallback=true`.",
  "",
  "| id | запрос | ядро | +синонимы | LLM | ядро, мс | LLM, мс | синонимы |",
  "| --- | --- | --- | --- | --- | --- | --- | --- |",
  ...rows.map((r) => `| ${r.id} | ${r.query} | ${r.baseHit ? "✓" : "✗"} | ` +
    `${r.expandedHit ? "✓" : "✗"} | ${r.llmHit ? "✓" : "✗"} | ${r.baseMs} | ` +
    `${r.llmMs} | ${(r.reformulations || []).join("; ")} |`),
  "",
  "| id | парафраза | ядро | LLM | синонимы |",
  "| --- | --- | --- | --- | --- |",
  ...paraphraseRows.map((r) => `| ${r.id} | ${r.query} | ${r.baseHit ? "✓" : "✗"} | ` +
    `${r.llmHit ? "✓" : "✗"} | ${(r.reformulations || []).join("; ")} |`),
  "",
].join("\n");
writeFileSync(join(ROOT, "БЕНЧМАРК_СОПРОЦЕССОРА.md"), md);
console.log("Записано: MOBILE_AGENT/БЕНЧМАРК_СОПРОЦЕССОРА.md");
