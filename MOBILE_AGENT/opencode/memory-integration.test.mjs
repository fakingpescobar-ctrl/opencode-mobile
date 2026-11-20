#!/usr/bin/env bun
// Интеграционный gate между ядром и живым APK-кодом.
// Проверяет ровно то, что будет запушено: staged-ассеты совпадают с
// протестированными модулями, memory.js регистрирует и диспатчит новые/tools,
// Kotlin копирует все модули и передаёт MCP_DOCS_DIR. Плюс один реальный pack
// на staged-модулях — чтобы импорты не были «бумажными».
//
// Тест не трогает живой MCP и не пишет в рабочую БД.

import { Database } from "bun:sqlite";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..");
const TMP = join(ROOT, "tmp");
const CORE = join(HERE, "memory-core") + "/";
const STAGE = join(TMP, "apk-integrate", "mcp") + "/";

let ok = 0;
let bad = 0;
const failures = [];
function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) ok++;
  else {
    bad++;
    failures.push((label || "eq") + ": actual=" + JSON.stringify(actual) + " expected=" + JSON.stringify(expected));
  }
}
function truthy(value, label) {
  if (value) ok++;
  else { bad++; failures.push("not true: " + (label || "")); }
}
function section(title) { console.log("\n=== " + title + " ==="); }

const CORE_FILES = [
  "memory-core.js", "memory-adaptive.js", "memory-lemma.js",
  "memory-index.mjs", "russian-stemmer.js",
];

// ---------------------------------------------------------------- 1. Ассеты
section("1. Staged-ассеты совпадают с протестированным ядром");
for (const f of CORE_FILES) {
  const src = readFileSync(CORE + f);
  const staged = readFileSync(STAGE + f);
  eq(staged.length, src.length, f + ": размер ассета");
  eq(Buffer.compare(src, staged), 0, f + ": байт в байт");
}
{
  const src = readFileSync(join(ROOT, "memory", "gold.jsonl"));
  const staged = readFileSync(STAGE + "memory-gold.jsonl");
  eq(Buffer.compare(src, staged), 0, "memory-gold.jsonl: байт в байт");
  const js = readFileSync(join(TMP, "memory.js"));
  const stagedJs = readFileSync(STAGE + "memory.js");
  eq(Buffer.compare(js, stagedJs), 0, "memory.js: staged копия байт в байт");
}

// ---------------------------------------------------------------- 2. memory.js
section("2. Живой memory.js: импорты, инструменты, диспатч");
{
  const js = readFileSync(join(TMP, "memory.js"), "utf8");
  for (const f of ["memory-core.js", "memory-index.mjs", "memory-adaptive.js"]) {
    truthy(js.includes('from "./' + f + '"'), "импорт " + f);
  }
  // lemma/stemmer приходят транзитивно через core/index/adaptive — это дешевле
  // и не создаёт второй экземпляр модуля с другим состоянием.
  truthy(js.includes("memory-lemma.js") === false && js.includes("russian-stemmer.js") === false,
    "транзитивные модули не импортируются дважды");
  truthy(js.includes("CORE_SCHEMA"), "схема ядра инициализируется");
  truthy(js.includes("MCP_DOCS_DIR"), "каталог памяти приходит через env");

  const tools = [
    "memory_pack", "memory_recall_doc", "memory_adaptive", "memory_feedback",
    "memory_thread", "memory_graph", "memory_bench", "memory_index_stats",
    "memory_ingest", "memory_approve", "memory_rollback",
  ];
  for (const t of tools) {
    truthy(js.includes('name: "' + t + '"'), t + ": зарегистрирован");
    truthy(js.includes('case "' + t + '"'), t + ": есть dispatch");
  }
  truthy(js.includes("approved_by required"), "approve требует подпись");
  truthy(js.includes("ingest(DB, args"), "ingest передаёт DB первым аргументом");
  truthy(js.includes("approve(DB,") && js.includes("rollback(DB,"), "approve/rollback передают DB");
  truthy(js.includes("не притворяемся"), "нет docs-dir — честный отказ");
  eq(/[\u4e00-\u9fff\uac00-\ud7af]/.test(js), false, "memory.js: без CJK-мусора");
  eq(/\bchild_process\b|\beval\s*\(|\bnew Function\s*\(/.test(js), false, "ядро без eval/child_process");
}

// ---------------------------------------------------------------- 3. Kotlin
section("3. Kotlin: копирование ассетов и env");
{
  checkKotlin("OpencodeRuntime.kt");
  function checkKotlin(file) {
    const kt = readFileSync(join(TMP, file), "utf8");
    for (const f of [...CORE_FILES, "memory-gold.jsonl"]) {
      truthy(kt.includes('"mcp/' + f + '" to "' + f + '"'), f + ": в CORE_ASSETS");
    }
    truthy(kt.includes('"MCP_DOCS_DIR" to moltbookReportDir(context)'), "MCP_DOCS_DIR в env памяти");
    truthy(kt.includes("CORE_ASSETS.all"), "отсутствие модуля = отказ MCP, а не тишина");
    // Реестр счётчиков не трогаем: он спрашивает live tools/list, а не хардкодит числа.
    truthy(kt.includes("MemoryMcp.MEMORY_PATH") || kt.includes("MCP_TCP_PORT"), "маршрут памяти не сломан");
    eq(/[\u4e00-\u9fff\uac00-\ud7af]/.test(kt), false, file + ": без CJK-мусора");
  }
}

// ---------------------------------------------------------------- 4. Реальный pack
section("4. Pack на staged-модулях (изолированная БД)");
{
  const idx = await import(pathToFileURL(STAGE + "memory-index.mjs").href);
  const core = await import(pathToFileURL(STAGE + "memory-core.js").href);
  const adaptive = await import(pathToFileURL(STAGE + "memory-adaptive.js").href);
  const DB = new Database(":memory:");
  const stat = idx.indexDocs(DB, ROOT);
  truthy(stat.chunks > 300, "проиндексировано >300 чанков: " + stat.chunks);
  truthy(stat.sources >= 8, "источников >=8: " + stat.sources);

  const hits = core.recallDocs(DB, "как агент пользоваться памятью", { limit: 6 });
  truthy(hits.length > 0, "поиск вернул чанки");
  truthy(hits.every((h) => h.why && typeof h.why === "object"), "каждый чанк несёт why");

  const pack = core.memoryPack(DB, "как агент пользоваться памятью", { budgetTokens: 1536 });
  truthy(pack.chunks > 0, "пак не пустой");
  truthy(pack.usedTokensEstimate <= 1536, "бюджет соблюдён");
  truthy(pack.packed.includes("why:"), "в пак есть why");
  truthy(pack.sources.every((s) => pack.packed.includes(s)), "sources только из упакованных чанков");
  truthy((pack.packed.match(/^### /gm) || []).length === pack.chunks, "число чанков совпадает с паком");

  const conf = adaptive.confidenceOf(hits);
  truthy(["none", "low", "medium", "high"].includes(conf.level), "confidence level корректен");
  const plan = adaptive.planFor(conf);
  truthy(plan.budgetTokens > 0 && plan.limit > 0, "план адаптива не пустой");
  const ap = adaptive.adaptivePack(DB, "как агент пользоваться памятью", core.recallDocs, core.memoryPack);
  truthy(ap.adaptive && ap.adaptive.confidence, "adaptivePack вернул Confidence");
  DB.close();
}

console.log("\n" + "=".repeat(56));
console.log("ИНТЕГРАЦИОННЫЕ ТЕСТЫ: OK=" + ok + " BAD=" + bad);
if (bad) {
  console.log("\nПРОВАЛЫ:\n" + failures.map((f) => "  - " + f).join("\n"));
  process.exit(1);
}
console.log("ВСЕ ИНТЕГРАЦИОННЫЕ ТЕСТЫ ПРОШЛИ");
