#!/usr/bin/env bun
// Диагностический прогон жёсткого gold-набора: ищем конкретные провалы ядра,
// а не «ещё немного улучшаем recall». Ничего не пишет в рабочую БД.
// Запуск:
//   NATIVE=$(dirname "$(pm path org.opencode.mobile.debug | sed 's/package://' | head -1)")/lib/arm64
//   LD_LIBRARY_PATH="/data/user/0/org.opencode.mobile.debug/files/musl:$NATIVE" \
//     "$NATIVE/libldmusl.so" "$NATIVE/libbun-musl.so" \
//     MOBILE_AGENT/opencode/hard-gold-bench.mjs
import { Database } from "bun:sqlite";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const CORE = join(ROOT, "opencode", "memory-core") + "/";
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");
const ad = await import(CORE + "memory-adaptive.js");

const DB = new Database(":memory:");
// Отчёт этого же скрипта лежит внутри MOBILE_AGENT/ и иначе индексируется
// обратно в corpus, превращая замер в самоисполняющееся эхо. Исключаем его.
const whitelist = idx.collectSources(ROOT)
  .filter((f) => f.rel !== "БЕНЧМАРК_СЛОЖНЫХ_ЗАПРОСОВ.md")
  .map((f) => f.rel);
idx.indexDocs(DB, ROOT, { whitelist });
const gold = readFileSync(join(ROOT, "memory", "gold-hard.jsonl"), "utf8")
  .trim().split("\n").map((l) => JSON.parse(l));

const rows = [];
for (const g of gold) {
  const t0 = performance.now();
  const top5 = core.recallDocs(DB, g.query, { limit: 5 });
  const top12 = core.recallDocs(DB, g.query, { limit: 12 });
  const pack = core.memoryPack(DB, g.query, { budgetTokens: 2048, limit: 12 });
  const ms = Math.round(performance.now() - t0);
  const apt = performance.now();
  const adaptive = ad.adaptivePack(DB, g.query, core.recallDocs, core.memoryPack);
  const adaptiveMs = Math.round(performance.now() - apt);
  const acceptable = g.expect_any || [g.expect_source];
  const has = (list) => list.some((h) => acceptable.includes(h.source));
  rows.push({
    id: g.id, query: g.query, expect: g.expect_source, acceptable,
    hit5: has(top5),
    hit12: has(top12),
    pack: has(pack.sources.map((s) => ({ source: s }))),
    adaptive: has(adaptive.sources.map((s) => ({ source: s }))),
    ms, adaptiveMs,
    usedTokens: adaptive.usedTokensEstimate,
    top: top5.map((h) => `${h.source}:${h.score.toFixed(3)}`),
    plan: adaptive.adaptive?.plan,
  });
}
DB.close();

const rate = (key) => rows.filter((r) => r[key]).length / rows.length;
const avg = (key) => Math.round(rows.reduce((s, r) => s + r[key], 0) / rows.length);
const out = {
  at: new Date().toISOString(), n: rows.length,
  indexSha: null,
  recall5: rate("hit5"), recall12: rate("hit12"),
  packHit: rate("pack"), adaptiveHit: rate("adaptive"),
  avgMs: avg("ms"), avgAdaptiveMs: avg("adaptiveMs"),
  avgTokens: avg("usedTokens"),
  misses: rows.filter((r) => !r.hit5),
  rows,
};
const summary = {
  n: out.n, recall5: out.recall5, recall12: out.recall12,
  packHit: out.packHit, adaptiveHit: out.adaptiveHit,
  avgMs: out.avgMs, avgAdaptiveMs: out.avgAdaptiveMs, avgTokens: out.avgTokens,
  misses: out.misses.map((r) => r.id),
};
console.log("ИТОГ:", JSON.stringify(summary, null, 2));
for (const r of out.misses) {
  console.log("ПРОМАХ", r.id, r.query, "ожидали", r.expect, "топ:", r.top.join(" | "));
}

const md = [
  "# Жёсткий gold: поиск конкретных провалов ядра",
  "",
  `- Дата: ${out.at}`,
  `- Набор: ${out.n} запросов с проверенной ожидаемой системой (` +
    "`MOBILE_AGENT/memory/gold-hard.jsonl`); не входит в обязательный gold." ,
  "",
  "| Метрика | Значение |",
  "| --- | --- |",
  `| recall@5 (топ ядра) | ${out.recall5.toFixed(3)} (${rows.filter((r) => r.hit5).length}/${out.n}) |`,
  `| recall@12 | ${out.recall12.toFixed(3)} (${rows.filter((r) => r.hit12).length}/${out.n}) |`,
  `| Пак (2048 токенов, 12 чанков) | ${out.packHit.toFixed(3)} |`,
  `| Адаптивный пак | ${out.adaptiveHit.toFixed(3)} |`,
  `| Средняя латентность ядра | ${out.avgMs} мс |`,
  `| Средняя латентность адаптива | ${out.avgAdaptiveMs} мс |`,
  `| Средний бюджет адаптива | ${out.avgTokens} токенов |`,
  "",
  "## Промахи recall@5",
  "",
  out.misses.length
    ? out.misses.map((r) => `- **${r.id}** «${r.query}» → ждали ` +
        `\`${r.expect}\`, топ: ${r.top.join(", ")}`).join("\n")
    : "Нет. Ядро закрывает все жёсткие запросы; оснований для плотных " +
      "эмбеддингов по этому набору нет.",
  "",
  "## Все запросы",
  "",
  "| id | запрос | ждали | @5 | @12 | пак | адаптив | мс |",
  "| --- | --- | --- | --- | --- | --- | --- | --- |",
  ...rows.map((r) => `| ${r.id} | ${r.query} | ${r.expect} | ` +
    `${r.hit5 ? "✓" : "✗"} | ${r.hit12 ? "✓" : "✗"} | ` +
    `${r.pack ? "✓" : "✗"} | ${r.adaptive ? "✓" : "✗"} | ${r.ms} |`),
  "",
].join("\n");
// Отчёт пишем в архивы/: collectSources индексатора пропускает этот каталог,
// иначе результат замера сам попадёт в corpus и станет самоисполняющимся эхом.
writeFileSync(join(ROOT, "архивы", "БЕНЧМАРК_СЛОЖНЫХ_ЗАПРОСОВ.md"), md);
console.log("Записано: MOBILE_AGENT/архивы/БЕНЧМАРК_СЛОЖНЫХ_ЗАПРОСОВ.md");
