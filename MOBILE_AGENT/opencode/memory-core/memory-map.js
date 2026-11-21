// Движок памяти v2: надстройка над детерминированным FTS5-ядром.
// Не заменяет ядро, а добавляет то, чего в нём не было:
//   1) карту памяти — агент знает, что есть в памяти, без полного обхода;
//   2) self-supervised mining — закономерности «термин -> источник» учатся
//      из самого корпуса, без разметки и без LLM;
//   3) meta-learning — статистика того, какие сигналы помогали/мешали.
// Всё на bun:sqlite, без сети, без root, без нативных зависимостей.

import { analyze } from "./memory-lemma.js";

export const MAP_SCHEMA = `
CREATE TABLE IF NOT EXISTS memory_pattern (
  lemma TEXT NOT NULL, source TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  confidence REAL NOT NULL DEFAULT 0,
  PRIMARY KEY(lemma, source)
);
CREATE INDEX IF NOT EXISTS idx_pattern_source ON memory_pattern(source);
CREATE INDEX IF NOT EXISTS idx_pattern_lemma ON memory_pattern(lemma);
CREATE TABLE IF NOT EXISTS memory_signal (
  query_kind TEXT NOT NULL, signal TEXT NOT NULL,
  successes INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(query_kind, signal)
);
`;

export function ensureMapSchema(DB) {
  DB.exec(MAP_SCHEMA);
}

// Self-supervised обучение: считаем, какие леммы/якоря устойчиво встречаются
// в каком источнике. Полная пересборка детерминированна — порядок и top-N не
// зависят от порядка строк в SQLite. Никаких весов модели не трогаем.
export function minePatterns(DB, opts = {}) {
  ensureMapSchema(DB);
  const minCount = Number.isFinite(opts.minCount) ? opts.minCount : 2;
  const top = Number.isFinite(opts.top) ? opts.top : 8;
  const rows = DB.prepare("SELECT source, lemmas, anchors FROM doc_chunks").all();
  const counts = new Map();
  for (const row of rows) {
    const terms = new Set();
    for (const t of String(row.lemmas || "").split(" ")) if (t.length >= 3) terms.add(t);
    for (const t of String(row.anchors || "").split(" ")) if (t.length >= 3) terms.add(t);
    for (const term of terms) {
      const key = term + "\t" + String(row.source);
      counts.set(key, (counts.get(key) || 0) + 1);
    }
  }
  const bySource = new Map();
  for (const [key, count] of counts) {
    if (count < minCount) continue;
    const at = key.indexOf("\t");
    const term = key.slice(0, at);
    const source = key.slice(at + 1);
    if (!bySource.has(source)) bySource.set(source, []);
    bySource.get(source).push({ term, count });
  }
  DB.exec("BEGIN");
  try {
    DB.exec("DELETE FROM memory_pattern");
    const put = DB.prepare(
      "INSERT INTO memory_pattern(lemma,source,count,confidence) VALUES (?,?,?,?)");
    for (const [source, list] of bySource) {
      list.sort((a, b) => b.count - a.count || (a.term < b.term ? -1 : a.term > b.term ? 1 : 0));
      for (const p of list.slice(0, top)) {
        put.run(p.term, source, p.count, Math.round(Math.min(1, p.count / 10) * 1000) / 1000);
      }
    }
  } catch (e) { DB.exec("ROLLBACK"); throw e; }
  DB.exec("COMMIT");
  const patterns = DB.prepare("SELECT COUNT(*) n FROM memory_pattern").get().n;
  return { chunks: rows.length, sources: bySource.size, patterns };
}

// Карта памяти: один компактный объект вместо полного обхода(doc_chunks).
export function buildMemoryMap(DB, opts = {}) {
  ensureMapSchema(DB);
  const sources = DB.prepare(
    "SELECT path, kind, chunks FROM doc_sources ORDER BY path").all();
  const pats = DB.prepare(
    "SELECT source, lemma, confidence FROM memory_pattern ORDER BY source, confidence DESC, lemma").all();
  const bySource = new Map();
  for (const p of pats) {
    if (!bySource.has(p.source)) bySource.set(p.source, []);
    const list = bySource.get(p.source);
    if (list.length < (opts.keys || 5)) list.push(p.lemma);
  }
  const map = {
    totalSources: sources.length,
    totalChunks: sources.reduce((s, x) => s + Number(x.chunks || 0), 0),
    totalPatterns: pats.length,
    sources: sources.map((s) => ({
      path: s.path, kind: s.kind, chunks: Number(s.chunks || 0),
      keys: bySource.get(s.path) || [],
    })),
  };
  map.sha = mapSha(map);
  return map;
}

function mapSha(map) {
  const sig = map.sources.map((s) =>
    s.path + "|" + s.kind + "|" + s.chunks + "|" + s.keys.join(",")).join("\n");
  // Локальный хэш без внешних зависимостей: детерминированный FNV-1a 32.
  let h = 2166136261;
  for (let i = 0; i < sig.length; i++) {
    h ^= sig.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0).toString(16).padStart(8, "0");
}

// Компактный текст для контекста агента: одна строка на источник.
export function mapText(map, opts = {}) {
  const limit = Number.isFinite(opts.limit) ? opts.limit : map.sources.length;
  const lines = [
    `КАРТА ПАМЯТИ: ${map.totalSources} источников, ${map.totalChunks} чанков, ` +
      `${map.totalPatterns} закономерностей, sha=${map.sha}`,
  ];
  for (const s of map.sources.slice(0, limit)) {
    lines.push(`[${s.kind}] ${s.path} (${s.chunks}) | ${s.keys.slice(0, 5).join(" ")}`);
  }
  return lines.join("\n");
}

// Подсказка источников по выученным закономерностям. Opt-in: ядро её не
// использует по умолчанию, пока held-out gate не покажет выигрыш.
export function suggestSources(DB, query, opts = {}) {
  ensureMapSchema(DB);
  const a = analyze(String(query || ""));
  const terms = [...new Set([...a.lemmas, ...a.anchors])];
  if (!terms.length) return [];
  const placeholders = terms.map(() => "?").join(",");
  const rows = DB.prepare(
    `SELECT source, SUM(confidence) score, COUNT(*) hits FROM memory_pattern
     WHERE lemma IN (${placeholders}) GROUP BY source ORDER BY score DESC, source`
  ).all(...terms);
  return rows.slice(0, opts.limit || 5).map((r) => ({
    source: r.source, score: Math.round(r.score * 1000) / 1000, hits: r.hits,
  }));
}

// Meta-learning: поощрение/наказание сигнала по типу запроса.
export function observeSignal(DB, queryKind, signal, success) {
  ensureMapSchema(DB);
  const ok = success ? 1 : 0;
  DB.prepare(`INSERT INTO memory_signal(query_kind,signal,successes,failures)
    VALUES (?,?,?,?) ON CONFLICT(query_kind,signal) DO UPDATE SET
    successes=successes+excluded.successes, failures=failures+excluded.failures`)
    .run(String(queryKind || "any"), String(signal || "unknown"), ok, 1 - ok);
}

// Политика: какой сигнал заслуживает доверия. Бета-среднее с минимальным
// приором (1 успех / 1 провал) — не даёт редкому сигналу ложной уверенности.
export function signalPolicy(DB, queryKind) {
  ensureMapSchema(DB);
  return DB.prepare(
    "SELECT signal, successes, failures FROM memory_signal WHERE query_kind=?")
    .all(String(queryKind || "any"))
    .map((r) => ({
      signal: r.signal,
      score: Math.round(((r.successes + 1) / (r.successes + r.failures + 2)) * 1000) / 1000,
      n: r.successes + r.failures,
    }))
    .sort((a, b) => b.score - a.score || (a.signal < b.signal ? -1 : 1));
}
