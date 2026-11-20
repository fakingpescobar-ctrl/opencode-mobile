#!/usr/bin/env bun
// Полный набор тестов ядра памяти мобайл-агента.
// Запуск (через Bun приложения):
//   NATIVE=$(dirname "$(pm path org.opencode.mobile.debug | sed 's/package://' | head -1)")/lib/arm64
//   LD_LIBRARY_PATH="/data/user/0/org.opencode.mobile.debug/files/musl:$NATIVE" \
//     "$NATIVE/libldmusl.so" "$NATIVE/libbun-musl.so" \
//     MOBILE_AGENT/opencode/memory-core.test.mjs
//
// Тесты не трогают живой MCP и не пишут в рабочую БД — только :memory:.
// Живой плагин memory.js в этом наборе не участвует.

import { Database } from "bun:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const CORE = join(HERE, "memory-core") + "/";
const DOCS = join(HERE, ".."); // MOBILE_AGENT/

const lemma = await import(CORE + "memory-lemma.js");
const idx = await import(CORE + "memory-index.mjs");
const core = await import(CORE + "memory-core.js");
const adaptive = await import(CORE + "memory-adaptive.js");

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

// ---------------------------------------------------------------- 1. Леммы
section("1. Лемматизатор и якоря");
{
  eq(typeof lemma.analyze, "function", "модуль загружен");
  const pairs = [
    ["память", "памяти"], ["семантика", "семантического"], ["отчёт", "отчета"],
    ["правило", "правилам"], ["плагин", "плагина"], ["сборка", "собрать"],
    ["sha256", "хэш"], ["журнал", "история"],
  ];
  let anchorHits = 0;
  for (const [a, b] of pairs) {
    const A = lemma.analyze(a).anchors;
    const B = lemma.analyze(b).anchors;
    if (A.some((x) => B.includes(x))) anchorHits++;
  }
  eq(anchorHits, 8, "якоря сводят 8/8 морфологических пар");
  truthy(lemma.analyze("память").lemmas.length > 0, "леммы не пустые");
  eq(lemma.analyze("и в на").anchors.length, 0, "стоп-слова не дают якорей");
  truthy(lemma.fuzzyMatch("память", "памятью"), "fuzzy ловит окончание");
  eq(lemma.fuzzyMatch("память", "погода"), false, "fuzzy не ловит мусор");
  truthy(lemma.forTrigram("Ёж École").includes("еж"), "ё нормализуется в е");
  eq(lemma.analyze("").lemmas.length, 0, "пустой текст → пустой анализ");
  // russian-stemmer: настоящий Snowball, детерминированный результат
  const st = await import(CORE + "russian-stemmer.js");
  eq(st.stemRussian("Отчёта"), "отчет", "Snowball: ё→е и снятие окончания");
  eq(st.stemRussian("ПРАВИЛО"), "прав", "Snowball: регистр и окончание");
}

// ---------------------------------------------------------------- 2. Индекс
section("2. Индексатор и FTS5");
{
  const md = "# Первый\n.Text один\n\n## Второй\nТекст два\n";
  const chunks = idx.chunkMarkdown(md, "test.md", "doc", 40, 5);
  truthy(chunks.length >= 2, "чанкуется по заголовкам");
  eq(new Set(chunks.map((c) => c.id)).size, chunks.length, "id чанков уникальны");
  truthy(chunks.every((c) => c.text.length <= 41), "чанки не превышают maxLen");

  truthy(idx.looksSecret("токен ghp_abcdefghijklmnopqrst здесь"), "ловим ghp_");
  truthy(idx.looksSecret("Authorization: Bearer abcdefghijklmnopqrstu"), "ловим Bearer");
  eq(idx.looksSecret("путь к ghtoken записан в правилах"), false, "слово «ghtoken» не секрет");
  eq(idx.kindOf("docs/agent-rules.md"), "rule", "классификация kind");
  eq(idx.kindOf("MOLTBOOK_ADVICE.md"), "advice", "advice классифицируется");
  eq(idx.sha256("abc"), idx.sha256("abc"), "sha256 детерминирован");

  const DB = new Database(":memory:");
  const stat1 = idx.indexDocs(DB, DOCS);
  truthy(stat1.files >= 10, "найдены файлы памяти: " + stat1.files);
  truthy(stat1.chunks > 300, "проиндексировано >300 чанков: " + stat1.chunks);
  eq(stat1.secret, 0, "секретов в памяти нет");
  const stat2 = idx.indexDocs(DB, DOCS);
  eq(stat2.added, 0, "повторная индексация ничего не добавляет");
  eq(stat2.updated, 0, "повторная индексация ничего не обновляет");
  truthy(stat2.skipped >= 10, "инкрементальность работает: " + stat2.skipped);
  const withLemmas = DB.prepare("SELECT count(*) n FROM doc_chunks WHERE lemmas != ''").get().n;
  truthy(withLemmas > 300, "колонка lemmas заполнена: " + withLemmas);
  const fts = DB.prepare("SELECT count(*) n FROM doc_fts WHERE doc_fts MATCH ?").get('"память"');
  truthy(fts.n > 0, "FTS5 trigram ищет: " + fts.n);
  DB.close();
}

// ---------------------------------------------------------------- 3. Поиск
section("3. Поиск, ранжирование, пак, аудит");
const DB = new Database(":memory:");
idx.indexDocs(DB, DOCS);
const gold = (readFileSync(join(DOCS, "memory", "gold.jsonl"), "utf8"))
  .trim().split("\n").filter(Boolean).map(JSON.parse);
{
  const h = core.recallDocs(DB, gold[0].query, { limit: 5 });
  truthy(h.length > 0, "recallDocs возвращает результаты");
  truthy(h.every((x, i) => i === 0 || h[i - 1].score >= x.score), "результаты отсортированы по score");
  truthy(h[0].why && "text" in h[0].why && "anchor" in h[0].why && "trust" in h[0].why, "why несёт составляющие");
  const texts = h.map((x) => idx.sha256(x.text));
  eq(new Set(texts).size, texts.length, "дедупликация по тексту работает");

  let hit5 = 0;
  for (const g of gold) {
    const r = core.recallDocs(DB, g.query, { limit: 5 });
    if (r.some((x) => x.source === g.expect_source)) hit5++;
  }
  truthy(hit5 / gold.length >= 0.85, "recall@5 не ниже 0.85: " + (hit5 / gold.length));

  const pack = core.memoryPack(DB, "куда plugin пишет отчёт moltbook", { budgetTokens: 1024, limit: 12 });
  truthy(pack.usedTokensEstimate <= 1024, "бюджет ≠ превышен: " + pack.usedTokensEstimate);
  eq(pack.sources.length, new Set(pack.sources).size, "sources уникальны");
  truthy(pack.sources.length > 0, "sources не пустые");
  const scores = [...pack.packed.matchAll(/\[score ([\d.]+)\]/g)].map((m) => Number(m[1]));
  truthy(scores.every((s, i) => i === 0 || scores[i - 1] >= s), "пак отсортирован по score");
  truthy(pack.packed.includes("why:"), "каждый кусок несёт why");
  truthy(typeof core.indexSha(DB) === "string" && core.indexSha(DB).length === 16, "indexSha стабилен");

  const bench = core.runBench(DB, "test-suite", gold);
  eq(bench.n, gold.length, "runBench считает все вопросы");
  truthy(bench.recall5 >= 0.85, "runBench recall: " + bench.recall5);
  truthy(["pass", "FAIL", "no-baseline"].includes(bench.gate.status), "gate присутствует");

  const ingest = core.ingest(DB, { content: "тестовая запись", provenance: "own-test" });
  truthy(ingest.id && ingest.sha, "ingest создал staged");
  const approved = core.approve(DB, ingest.id, "operator", "тест");
  truthy(approved.audit_id, "approve создал аудит");
  const back = core.rollback(DB, approved.audit_id);
  eq(back.restored, ingest.id, "rollback восстановил staged");
}

// ---------------------------------------------------------------- 4. Адаптив
section("4. Адаптивная семантика и самообучение");
{
  // Уверенность меряем на фиксированных оценках, а не на живом индексе:
  // документы MOBILE_AGENT растут, и один и тот же запрос со временем перестаёт
  // быть «чётким» — это дрейф контента, а не регресс формулы. Тест обязан
  // проверять формулу, а не текущий текст документации.
  const sharpHits = [
    { id: "fake-sharp-1", score: 0.92 }, { id: "fake-sharp-2", score: 0.25 },
    { id: "fake-sharp-3", score: 0.24 },
  ];
  const garbageHits = [
    { id: "fake-garbage-1", score: 0.12 }, { id: "fake-garbage-2", score: 0.11 },
    { id: "fake-garbage-3", score: 0.10 },
  ];
  const cSharp = adaptive.confidenceOf(sharpHits);
  const cGarbage = adaptive.confidenceOf(garbageHits);
  eq(cSharp.level, "high", "чёткий ключ → high: " + JSON.stringify(cSharp));
  eq(cGarbage.level, "none", "мусор → none: " + JSON.stringify(cGarbage));
  truthy(cSharp.peak > cGarbage.peak, "peak разделяет ключ и мусор");
  eq(cGarbage.matched, false, "мусор не помечен как matched");

  const plan = adaptive.planFor(cSharp);
  eq(plan.limit, 6, "план high: 6 чанков");
  eq(plan.budgetTokens, 1400, "план high: 1400 токенов");
  const capped = adaptive.planFor(cSharp, { maxChunks: 3, maxBudget: 500 });
  eq(capped.limit, 3, "maxChunks уважается");
  eq(capped.budgetTokens, 500, "maxBudget уважается");

  const stubRecall = () => sharpHits;
  const stubPack = (_db, _q, opts) => ({
    chunks: opts.limit,
    usedTokensEstimate: Math.min(opts.budgetTokens, opts.limit * 200),
    sources: ["stub"],
    packed: "",
  });
  const r = adaptive.adaptivePack(DB, "стабильный зонд", stubRecall, stubPack, { withThread: true });
  truthy(r.adaptive && r.adaptive.confidence && r.adaptive.plan, "adaptivePack вернул метаданные");
  truthy(r.chunks <= 6, "adaptivePack ограничивает число чанков: " + r.chunks);
  truthy(r.usedTokensEstimate <= 1400, "adaptivePack держит бюджет: " + r.usedTokensEstimate);

  const probeSharp = core.recallDocs(DB, "apk-40 sha установки verify-install", { limit: 12 });
  const thread = adaptive.conceptThread(DB, probeSharp.slice(0, 3).map((h) => h.id), { max: 4 });
  truthy(Array.isArray(thread), "conceptThread вернул массив");
  truthy(!thread.some((t) => probeSharp.slice(0, 3).some((h) => h.id === t.id)), "нить не содержит seed-чанки");

  const q = "правило 51 память";
  const before = core.recallDocs(DB, q, { limit: 3 });
  const topBefore = before[0].score;
  const learned = adaptive.learnFromFeedback(DB, q, [before[0].id], true);
  eq(learned.learned, 1, "learnFromFeedback обучил 1 чанк");
  const boosted = adaptive.feedbackBoost(DB, q);
  eq(boosted.length, 1, "feedbackBoost читает запись");
  const after = core.recallDocs(DB, q, { limit: 3 });
  truthy(after[0].score > topBefore, "feedback поднял score: " + topBefore + " → " + after[0].score);
  truthy(after[0].score - topBefore <= 0.07, "feedback не взрывной (≤6%)");

  const ab = adaptive.runAdaptiveBench(DB, gold, core.recallDocs, core.memoryPack, { suite: "test-adaptive" });
  truthy(ab.adaptive.recall >= ab.fixed.recall - 0.02, "gate адаптива: " + ab.fixed.recall + " → " + ab.adaptive.recall);
  truthy(ab.tokenSaving > 0, "адаптив экономит токены: " + ab.tokenSaving);
  truthy(ab.latencyMsPerQuery < 100, "latency разумна: " + ab.latencyMsPerQuery + " мс");
}

// ------------------------------------------------- 4b. Негатив и крайности
section("4b. Негативные случаи и крайности");
{
  // Пустые/странные запросы не должны ронять ядро.
  eq(core.recallDocs(DB, "", { limit: 5 }).length, 0, "пустой запрос → пусто");
  eq(core.recallDocs(DB, "*** ' OR 1=1 --", { limit: 5 }).length, 0, "FTS-инъекция не взрывает БД");
  const emptyPack = core.memoryPack(DB, "", { budgetTokens: 256 });
  eq(emptyPack.chunks, 0, "пустой пак: 0 чанков");
  eq(emptyPack.sources.length, 0, "пустой пак: 0 источников");

  // scope и as_of фильтры.
  const scoped = core.recallDocs(DB, "советы молтбук", { limit: 12, scope: "advice" });
  truthy(scoped.length > 0, "scope=advice даёт результаты");
  truthy(scoped.every((x) => x.kind === "advice"), "scope фильтрует по kind");
  eq(core.recallDocs(DB, "память", { limit: 12, as_of: 0 }).length, 0, "as_of=0 отсекает всё");

  // Чанкование без переводов строк (одна длинная строка).
  const longLine = "слово ".repeat(900);
  const longChunks = idx.chunkMarkdown(longLine, "long.md", "doc", 800, 80);
  truthy(longChunks.length >= 2, "длинная строка режется на части: " + longChunks.length);
  truthy(longChunks.every((c) => c.text.length <= 801), "части ≤ maxLen");

  // collectSources не тащит tmp/, архивы/, opencode/.
  const sources = idx.collectSources(DOCS).map((f) => f.rel);
  truthy(!sources.some((s) => s.startsWith("tmp/") || s.startsWith("архивы/") || s.startsWith("opencode/")),
    "индексатор исключает служебные каталоги");

  // graphSnapshot отдаёт весь граф, а не только окрестность одного узла.
  {
    const ins = DB.prepare("INSERT OR IGNORE INTO graph(source,target,relation) VALUES(?,?,?)");
    ins.run("test-a", "test-b", "depends-on");
    ins.run("test-b", "test-c", "evidence-in");
    const snap = core.graphSnapshot(DB, 200);
    truthy(Array.isArray(snap.nodes) && Array.isArray(snap.edges), "graphSnapshot: узлы и рёбра");
    eq(snap.edges.length, 2, "graphSnapshot: два тестовых ребра");
    truthy(snap.nodes.includes("test-a") && snap.nodes.includes("test-c"), "graphSnapshot: узлы из рёбер");
    truthy(snap.edges.every((e) => snap.nodes.includes(e.from) && snap.nodes.includes(e.to)),
      "graphSnapshot: каждый узел рёбер присутствует в nodes");
    eq(core.graphSnapshot(DB, 1).edges.length, 1, "graphSnapshot уважает limit");
  }

  // followThread не зацикливается и уважает depth.
  const thread = core.followThread(DB, "rule51", 2);
  truthy(Array.isArray(thread), "followThread вернул массив");
  truthy(thread.length <= 40, "followThread ограничен: " + thread.length);

  // Повторное обучение отрицательным сигналом не ломает запись.
  const negQ = "негативный тест";
  const negId = core.recallDocs(DB, "память", { limit: 1 })[0].id;
  adaptive.learnFromFeedback(DB, negQ, [negId], false);
  const negBoost = adaptive.feedbackBoost(DB, negQ);
  truthy(negBoost[0].weight < 0, "отрицательный feedback имеет отрицательный вес");

  // confidence/conceptThread на пустых входах.
  eq(adaptive.confidenceOf([]).level, "none", "confidenceOf([]) → none");
  eq(adaptive.conceptThread(DB, [], { max: 4 }).length, 0, "conceptThread([]) → пусто");
  eq(adaptive.conceptThread(DB, ["нет-такого-чанка"], { max: 4 }).length, 0, "неизвестный seed → пусто");

  // Точность rollback: восстановленный контент совпадает с исходным.
  const ing2 = core.ingest(DB, { content: "проверка точности rollback", provenance: "own-test", id: "rb-check" });
  const ap2 = core.approve(DB, ing2.id);
  const rb2 = core.rollback(DB, ap2.audit_id);
  const staged = DB.prepare("SELECT content, sha FROM memory_staged WHERE id = ?").get("rb-check");
  eq(staged.content, "проверка точности rollback", "rollback вернул точный контент");
  eq(staged.sha, ing2.sha, "rollback вернул точный sha");
  eq(rb2.restored, "rb-check", "rollback вернул правильный id");

  // query-aware authority: советы должны попадать в топ по запросу про советы.
  const advTop = core.recallDocs(DB, "советы молтбук external не инструкция", { limit: 5 });
  truthy(advTop.some((x) => x.kind === "advice"), "advice поднимается по явному якорю вопроса");
}
DB.close();

// ---------------------------------------------------------------- 5. Guard
section("5. Guard: без внешних зависимостей и мусорных вкраплений");
{
  const files = readdirSync(CORE).filter((f) => /\.(js|mjs)$/.test(f));
  for (const f of files) {
    const src = readFileSync(CORE + f, "utf8");
    const imports = [...src.matchAll(/(?:from|import)\s*["']([^"']+)["']/g)].map((m) => m[1]);
    for (const imp of imports) {
      truthy(imp.startsWith(".") || ["crypto", "fs", "path", "bun:sqlite", "node:fs", "node:path", "node:url"].includes(imp),
        f + ": импорт «" + imp + "» встроенный/относительный");
    }
    eq(/[\u4e00-\u9fff\uac00-\ud7af]/.test(src), false, f + ": нет китайского/корейского мусора");
    eq(/\bfetch\s*\(|XMLHttpRequest|WebSocket|require\(["']http|from\s+["']http/.test(src), false,
      f + ": нет сетевых вызовов (офлайн)");
    eq(/\bchild_process\b|\beval\s*\(|\bnew Function\s*\(/.test(src), false, f + ": нет eval/child_process");
  }
  const stemSize = readFileSync(CORE + "russian-stemmer.js").length;
  truthy(stemSize < 20 * 1024, "стеммер <20 КБ: " + stemSize);
}

// ---------------------------------------------------------------- итог
console.log("\n" + "=".repeat(56));
console.log("ТЕСТЫ ЯДРА ПАМЯТИ: OK=" + ok + " BAD=" + bad);
if (bad) {
  console.log("\nПРОВАЛЫ:\n" + failures.map((f) => "  - " + f).join("\n"));
  process.exit(1);
}
console.log("ВСЕ ТЕСТЫ ПРОШЛИ");
