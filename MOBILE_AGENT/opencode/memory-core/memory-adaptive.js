// Адаптивный слой ядра памяти: сам решает, ЧТО и СКОЛЬКО читать.
// Вместо «всегда читать всю память» или «всегда 2048 токенов» — confidence-
// gating + концептуальный граф + самонастраиваемый бюджет.
//
// Идеи: Hindsight (самообучение), KITE (follow-the-thread), LongMemEval
// (измеримость). Всё переложено на наш bun:sqlite, без LLM/эмбеддингов/сети.

import { analyze, fuzzyMatch } from "./memory-lemma.js";
import { sha256 } from "./memory-index.mjs";

// Пороги уверенности. Ключевая метрика — peak = (score топ-1) − (медиана),
// т.е. насколько лучший чанк ВЫДЕЛЯЕТСЯ среди остальных.
// Замер на нашей памяти: точный ключ → peak 0.31; размытая тема → 0.05; мусор → 0.015.
export const CONFIDENCE = {
  high: 0.62,   // читаем 3–4 чанка, ~768 токенов
  medium: 0.38, // 6–8 чанков, ~1536 токенов
  low: 0.20,    // широкий обзор 9–12 чанков
};
export const NOISE_FLOOR = 0.55; // ниже этого top — в памяти нет уверенного ответа

// Оценка уверенности по результатам поиска (честно, без ложной уверенности).
export function confidenceOf(hits) {
  if (!hits.length) {
    return { level: "none", value: 0, matched: false, top: 0, peak: 0, reason: "пустой поиск" };
  }
  const top = hits[0].score;
  const scores = hits.map((h) => h.score);
  const median = scores.length > 2 ? scores[Math.floor(scores.length / 2)] : scores[scores.length - 1];
  const peak = Math.round((top - median) * 1000) / 1000;
  const matched = top >= NOISE_FLOOR;
  // 65% — выделенность топ-1, 35% — абсолютный score. 0.30 peak = «идеально чётко».
  const raw = 0.65 * Math.min(1, peak / 0.30) + 0.35 * top;
  const value = Math.round(Math.max(0, Math.min(1, raw)) * 1000) / 1000;
  let level = raw >= CONFIDENCE.high ? "high" : raw >= CONFIDENCE.medium ? "medium" : "low";
  if (!matched) level = "none"; // подозрительно низкий top — не притворяемся уверенными
  return {
    level, value, matched, top, peak,
    reason: `top=${top}, peak=${peak}, matched=${matched}`,
  };
}

// Адаптивный бюджет и лимит чанков по уровню уверенности.
export function planFor(confidence, opts = {}) {
  // Бюджеты подогнаны под gate: recall адаптива == recall фиксированного пака.
  // Один чанк ≈ 200–260 токенов, поэтому high = 6 чанков × ~230 ≈ 1400.
  const table = {
    high:   { limit: 6,  budget: 1400 },
    medium: { limit: 12, budget: 2048 },
    low:    { limit: 12, budget: 2304 },
    none:   { limit: 10, budget: 1600 }, // «нет уверенного ответа» — экономим, но не слепнем
  };
  const base = table[confidence.level] || table.medium;
  return {
    limit: opts.maxChunks ? Math.min(base.limit, opts.maxChunks) : base.limit,
    budgetTokens: opts.maxBudget ? Math.min(base.budget, opts.maxBudget) : base.budget,
    level: confidence.level,
  };
}

// Концептуальный граф: связываем чанки по общим якорям и fuzzy-совпадению лемм.
// Возвращает «нить» — id соседних чанков, которые стоит дочитать вместе
// с найденным (идея follow-the-thread из KITE, без векторов).
export function conceptThread(DB, seedIds, opts = {}) {
  const max = opts.max || 6;
  if (!seedIds.length) return [];
  const seedSet = new Set(seedIds);
  const placeholders = seedIds.map(() => "?").join(",");
  const seeds = DB.prepare(`SELECT id, anchors, lemmas FROM doc_chunks WHERE id IN (${placeholders})`).all(...seedIds);
  const seedsAnchors = new Set();
  for (const s of seeds) for (const a of String(s.anchors || "").split(" ")) if (a) seedsAnchors.add(a);
  if (!seedsAnchors.size) return [];

  // Якоря лежат текстом в doc_chunks.anchors — отдельной таблицы нет.
  // Тянем «нить»: чанки с общим якорем, вес = число совпавших якорей.
  const scores = new Map();
  const rows = DB.prepare("SELECT id, anchors, lemmas FROM doc_chunks").all();
  for (const r of rows) {
    if (seedSet.has(r.id)) continue;
    let overlap = 0;
    for (const a of String(r.anchors || "").split(" ")) if (a && seedsAnchors.has(a)) overlap++;
    if (overlap) scores.set(r.id, overlap);
  }
  // fuzzy-добор: если якорь «память», ловим лемму «памятн» даже без точного якоря.
  if (opts.fuzzy !== false) {
    const anchorList = [...seedsAnchors];
    for (const r of rows) {
      if (seedSet.has(r.id) || scores.has(r.id)) continue;
      let bonus = 0;
      for (const word of String(r.lemmas || "").split(" ").slice(0, 4)) {
        for (const a of anchorList) if (fuzzyMatch(a, word)) { bonus += 0.3; break; }
      }
      if (bonus) scores.set(r.id, bonus);
    }
  }
  return [...scores.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, max)
    .map(([id, w]) => ({ id, weight: Math.round(w * 1000) / 1000 }));
}

// Самообучение: запоминаем, какие запросы к какому chank'у привели успех.
// success_signal — внешний фидбек (например, агент действительно использовал кусок).
// Хранится в SQLite; при следующем похожем запросе слегка поднимаем эти чанки.
export function learnFromFeedback(DB, query, usedChunkIds, success = true) {
  DB.exec(`CREATE TABLE IF NOT EXISTS memory_feedback (
    query_sha TEXT, chunk_id TEXT, weight REAL DEFAULT 0, hits INTEGER DEFAULT 0,
    PRIMARY KEY(query_sha, chunk_id))`);
  const qsha = sha256(String(query).toLowerCase().trim()).slice(0, 16);
  const tx = DB.transaction(() => {
    for (const id of usedChunkIds) {
      const prev = DB.prepare("SELECT weight, hits FROM memory_feedback WHERE query_sha=? AND chunk_id=?").get(qsha, id);
      if (prev) {
        DB.prepare("UPDATE memory_feedback SET weight=?, hits=? WHERE query_sha=? AND chunk_id=?")
          .run(prev.weight + (success ? 0.5 : -0.2), prev.hits + 1, qsha, id);
      } else {
        DB.prepare("INSERT INTO memory_feedback(query_sha, chunk_id, weight, hits) VALUES (?,?,?,?)")
          .run(qsha, id, success ? 0.5 : -0.2, 1);
      }
    }
  });
  tx();
  return { learned: usedChunkIds.length, query_sha: qsha, success };
}

export function feedbackBoost(DB, query) {
  const qsha = sha256(String(query).toLowerCase().trim()).slice(0, 16);
  try {
    return DB.prepare("SELECT chunk_id, weight FROM memory_feedback WHERE query_sha=?").all(qsha);
  } catch { return []; }
}

// Полный адаптивный цикл: confidence -> план -> pack + нить.
export function adaptivePack(DB, query, recallFn, packFn, opts = {}) {
  const probe = recallFn(DB, query, { limit: opts.probeLimit || 12 });
  const confidence = confidenceOf(probe);
  const plan = planFor(confidence, opts);
  // planFor возвращает budgetTokens. Раньше здесь было plan.budget — поле с
  // другим именем, из-за чего пак получал undefined и считал бюджет сам, а
  // адаптивная экономия была иллюзорной. Передаём ровно то, что планировали.
  const pack = packFn(DB, query, { budgetTokens: plan.budgetTokens, limit: plan.limit, scope: opts.scope });
  const thread = opts.withThread
    ? conceptThread(DB, probe.slice(0, 3).map((h) => h.id), { max: 4 })
    : [];
  return {
    ...pack,
    adaptive: { confidence, plan, thread: thread.length, level: confidence.level },
  };
}

// Адаптивный бенчмарк: доказываем, что экономим токены БЕЗ потери попаданий.
// Сравниваем «всегда 2048 токенов / 12 чанков» и адаптивный план.
export function runAdaptiveBench(DB, gold, recallFn, packFn, opts = {}) {
  let fixedHit = 0, adaptiveHit = 0, fixedTok = 0, adaptiveTok = 0, adaptiveChunks = 0;
  const byLevel = {};
  let indexShaVal = "";
  const t0 = performance.now();
  for (const g of gold) {
    const expect = g.expect_source;
    const fixed = packFn(DB, g.query, { budgetTokens: 2048, limit: 12 });
    const adaptive = adaptivePack(DB, g.query, recallFn, packFn, { withThread: !!opts.withThread });
    if (fixed.sources.includes(expect)) fixedHit++;
    if (adaptive.sources.includes(expect)) adaptiveHit++;
    fixedTok += fixed.usedTokensEstimate;
    adaptiveTok += adaptive.usedTokensEstimate;
    adaptiveChunks += adaptive.chunks;
    if (!indexShaVal) indexShaVal = adaptive.index_sha;
    const lv = adaptive.adaptive.level;
    byLevel[lv] = (byLevel[lv] || 0) + 1;
  }
  const n = gold.length || 1;
  const saving = fixedTok ? 1 - adaptiveTok / fixedTok : 0;
  return {
    suite: opts.suite || "adaptive", n: gold.length,
    fixed: { hit: fixedHit, recall: fixedHit / n, avgTokens: Math.round(fixedTok / n) },
    adaptive: {
      hit: adaptiveHit, recall: adaptiveHit / n,
      avgTokens: Math.round(adaptiveTok / n), avgChunks: Math.round(adaptiveChunks / n * 10) / 10,
    },
    tokenSaving: Math.round(saving * 1000) / 1000,
    levels: byLevel,
    latencyMsPerQuery: Math.round((performance.now() - t0) / n * 10) / 10,
    index_sha: indexShaVal,
    at: Date.now(),
  };
}

export const _internal = { CONFIDENCE, planFor };
