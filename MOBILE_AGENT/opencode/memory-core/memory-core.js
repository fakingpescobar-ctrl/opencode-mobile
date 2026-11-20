// Ядро памяти мобайл-агента: гибридный поиск + упаковка контекста + аудит.
// Встраивается в memory.js (один MCP-процесс, маршрут /mcp).
// Зависимости только локальные: bun:sqlite, ./memory-index.mjs, ./memory-lemma.js,
// ./provenance.js (уже лежит в assets/mcp).

import { analyze } from "./memory-lemma.js";
import { AUTHORITY, DOC_SCHEMA, sha256 } from "./memory-index.mjs";

export const CORE_SCHEMA = DOC_SCHEMA + `
CREATE TABLE IF NOT EXISTS memory_audit (
  id TEXT PRIMARY KEY, op TEXT NOT NULL, target_id TEXT NOT NULL,
  sha_before TEXT NOT NULL DEFAULT '', sha_after TEXT NOT NULL DEFAULT '',
  reason TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL, approved_by TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS memory_snapshot (
  audit_id TEXT PRIMARY KEY, payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS bench_result (
  id INTEGER PRIMARY KEY AUTOINCREMENT, suite TEXT NOT NULL,
  recall5 REAL NOT NULL, mrr REAL NOT NULL, ndcg REAL NOT NULL,
  index_sha TEXT NOT NULL, at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS memory_staged (
  id TEXT PRIMARY KEY, content TEXT NOT NULL, sha TEXT NOT NULL,
  provenance TEXT NOT NULL DEFAULT 'unknown', at INTEGER NOT NULL
);
-- Граф из живого memory.js (та же схема, CREATE IF NOT EXISTS — без конфликта).
CREATE TABLE IF NOT EXISTS graph (
  source TEXT, target TEXT, relation TEXT DEFAULT 'related',
  PRIMARY KEY(source, target, relation)
);
CREATE INDEX IF NOT EXISTS idx_graph_src ON graph(source);
CREATE INDEX IF NOT EXISTS idx_graph_tgt ON graph(target);
`;

// Веса гибридной формулы (подогнаны под наше железо: 8 ядер, 15 ГБ, FTS5).
// v3: усилили релевантность (text+anchor 0.78), снизили authority до 0.05 —
// authority не должен перебивать явный смысл вопроса (было: rule везде выше advice).
const W = { bm25text: 0.45, bm25anchor: 0.33, graph: 0.10, authority: 0.05, recency: 0.07 };
const HALF_LIFE_MS = 30 * 24 * 60 * 60 * 1000;

function clamp(x, lo = 0, hi = 1) { return Math.max(lo, Math.min(hi, x)); }

function normalizeBm25(raw) {
  // bm25 в SQLite отрицателен: чем ближе к нулю, тем лучше. Переводим в 0..1.
  if (!Number.isFinite(raw)) return 0;
  return clamp(1 / (1 + Math.max(0, -raw) / 10));
}

function recencyOf(created, now) {
  if (!created) return 0.5;
  const age = Math.max(0, now - created);
  return 0.5 + 0.5 * Math.exp(-age / HALF_LIFE_MS);
}

function trustMultiplier(provenance, source) {
  const p = String(provenance || source || "").toLowerCase();
  if (["operator", "user", "own-log", "own-test", "own-runtime", "phone-agent"].includes(p)) return 1.0;
  if (["moltbook", "external-agent", "guide", "web", "external"].includes(p)) return 0.85;
  // Наша собственная память — исходники в MOBILE_AGENT/. Их автор — агент/оператор,
  // а не «неизвестный внешний источник». Иначе все rule/state занижались в 0.7.
  if (/\.md$/i.test(String(source || "")) || /\.(md|jsonl)$/i.test(String(source || ""))) return 1.0;
  return 0.7;
}

// Поднять авторитетность того kind, про который явно спрашивают в запросе.
// «советы молтбук» -> advice должен быть не ниже rule, даже при authority=0.5.
function effectiveAuthority(kind, anchors) {
  const boost = {
    advice: ["advice"], rule: ["rule"], state: ["state"], journal: ["journal", "history"],
    path: ["path"], catalog: ["catalog"], report: ["report"], apk: ["apk"], trust: ["trust"],
  };
  for (const [k, trig] of Object.entries(boost)) {
    if (k === kind && trig.some((t) => anchors.includes(t))) return Math.max(1.0, AUTHORITY[kind] ?? 0.6);
  }
  return AUTHORITY[kind] ?? 0.6;
}

// Графовая близость: сколько рёбер/grap-связей ведут от якорей запроса к чанку.
// Дешёвый proxy без обхода графа: используем счётчик общих якорей с соседними
// чанками. Полный follow_thread ниже делает настоящий обход по graph-таблице.
function graphProximity(DB, anchors, chunkId) {
  if (!anchors.length) return 0;
  try {
    const row = DB.prepare(`SELECT count(*) n FROM graph
      WHERE source IN (${anchors.map(() => "?").join(",")})
        AND target IN (SELECT id FROM doc_chunks WHERE source = ?)`)
      .get(...anchors, chunkId.split(":")[0]);
    return clamp((row?.n || 0) / 4);
  } catch { return 0; }
}

function queryFts(DB, table, query, limit) {
  try {
    return DB.prepare(`SELECT chunk_id, bm25(${table}) AS score FROM ${table}
      WHERE ${table} MATCH ? ORDER BY score LIMIT ?`).all(query, limit);
  } catch { return []; }
}

// Главный поиск. Возвращает топ-чанки с весами и объяснением `why`.
export function recallDocs(DB, query, opts = {}) {
  DB.exec(CORE_SCHEMA);
  const now = Date.now();
  const limit = Math.max(1, Math.min(opts.limit || 10, 20));
  const a = analyze(query);
  const anchors = a.anchors;
  // Trigram-FTS по умолчанию связывает слова через AND: длинная фраза «как агент
  // должен пользоваться памятью» не находит ничего, пока не разобьём её на OR.
  // Слова короче 3 символов trigram не индексирует вовсе — отбрасываем.
  const trigramWords = String(query).toLowerCase().replace(/ё/g, "е")
    .replace(/[^a-z0-9а-я\s]/gi, " ").split(/\s+/)
    .filter((w) => w.length >= 3);
  const trigramQuery = trigramWords.map((w) => `"${w}"`).join(" OR ");
  const anchorQuery = anchors.length ? anchors.map((x) => `"${x}"`).join(" OR ") : "";

  const textHits = queryFts(DB, "doc_fts", trigramQuery, Math.max(limit * 3, 12));
  const anchorHits = anchorQuery ? queryFts(DB, "anchor_fts", anchorQuery, Math.max(limit * 3, 12)) : [];
  const getChunk = DB.prepare("SELECT * FROM doc_chunks WHERE id = ?");
  const byId = new Map();
  for (const h of textHits) {
    const c = getChunk.get(h.chunk_id);
    if (c) byId.set(c.id, { chunk: c, textScore: normalizeBm25(h.score), anchorScore: 0 });
  }
  for (const h of anchorHits) {
    const cur = byId.get(h.chunk_id);
    const sc = normalizeBm25(h.score);
    if (cur) cur.anchorScore = sc;
    else {
      const c = getChunk.get(h.chunk_id);
      if (c) byId.set(c.id, { chunk: c, textScore: 0, anchorScore: sc });
    }
  }

  const scope = opts.scope || null; // 'rule' | 'state' | ... или массив
  // Самообучение: если агент ранее подтвердил полезность чанка для этого же
  // запроса — слегка поднимаем его. Эффект мягкий (≤6%), чтобы не сломать gate.
  const feedback = new Map();
  try {
    const qsha = sha256(String(query).toLowerCase().trim()).slice(0, 16);
    for (const r of DB.prepare("SELECT chunk_id, weight FROM memory_feedback WHERE query_sha=?").all(qsha)) {
      feedback.set(r.chunk_id, r.weight);
    }
  } catch { /* таблица ещё не создана — нормально */ }

  const results = [];
  for (const item of byId.values()) {
    const c = item.chunk;
    if (scope) {
      const kinds = Array.isArray(scope) ? scope : [scope];
      if (!kinds.includes(c.kind)) continue;
    }
    if (opts.as_of != null && c.created > opts.as_of) continue;
    const authority = effectiveAuthority(c.kind, anchors);
    const graph = graphProximity(DB, anchors, c.id);
    const recency = recencyOf(c.created, now);
    const trust = trustMultiplier(c.source, c.source);
    const fb = feedback.get(c.id) || 0;
    const fbMul = 1 + 0.06 * Math.max(-0.5, Math.min(1, fb));
    const score =
      (W.bm25text * item.textScore +
       W.bm25anchor * item.anchorScore +
       W.graph * graph +
       W.authority * authority +
       W.recency * recency) * trust * fbMul;
    results.push({
      id: c.id, source: c.source, heading: c.heading, kind: c.kind,
      line_from: c.line_from, line_to: c.line_to, text: c.text,
      score: Math.round(score * 10000) / 10000,
      why: {
        text: Math.round(item.textScore * 1000) / 1000,
        anchor: Math.round(item.anchorScore * 1000) / 1000,
        graph: Math.round(graph * 1000) / 1000,
        authority, recency: Math.round(recency * 1000) / 1000, trust, feedback: fb,
        anchors: anchors.filter((x) => c.anchors.split(" ").includes(x)),
      },
    });
  }
  results.sort((x, y) => y.score - x.score);
  // Дедупликация: один и тот же текст в ПРАВИЛА.md и docs/agent-rules.md
  // занимал два места в топе. Оставляем первый (он уже отсортирован по score).
  const seenText = new Set();
  const dedup = [];
  for (const r of results) {
    const key = sha256(r.text).slice(0, 16);
    if (seenText.has(key)) continue;
    seenText.add(key);
    dedup.push(r);
  }
  return dedup.slice(0, limit);
}

// Упаковка контекста в бюджет токенов по секциям. Главный инструмент агента.
export function memoryPack(DB, query, opts = {}) {
  const budget = Math.max(256, Math.min(opts.budgetTokens || 2048, 8192));
  const hits = recallDocs(DB, query, { limit: opts.limit || 12, scope: opts.scope, as_of: opts.as_of });
  const sections = [
    ["rule", "ПРАВИЛА"], ["state", "СОСТОЯНИЕ"], ["journal", "ИСТОРИЯ"],
    ["path", "ПУТИ"], ["catalog", "КАТАЛОГ"], ["advice", "ДАННЫЕ/СОВЕТЫ"], ["doc", "ДОКУМЕНТЫ"],
  ];
  const kindToTitle = new Map(sections);
  // ПАКУЕМ В ПОРЯДКЕ SCORE: самый релевантный чанк гарантированно попадёт в бюджет.
  // Раньше секции шли в фиксированном порядке (rule→state→…), из-за чего крупный
  // чанк из ПРАВИЛ съедал бюджет, а нужный ответ из ИСТОРИИ отрезался.
  const ordered = hits.slice().sort((a, b) => b.score - a.score);
  // Сколько примерно токенов: 1 токен ≈ 4 символа (кириллица консервативно).
  let used = 0;
  const out = [];
  const packedSources = new Set();
  let packedCount = 0;
  let curTitle = "";
  let buf = [];
  const flush = () => { if (buf.length > 1) out.push(buf.join("\n")); buf = []; };
  for (const h of ordered) {
    const est = Math.ceil((h.text.length + h.source.length + 40) / 4);
    if (used + est > budget) continue; // не break: следующий чанк может быть короче
    used += est;
    packedCount++;
    packedSources.add(h.source);
    const title = kindToTitle.get(h.kind) || "ДОКУМЕНТЫ";
    if (title !== curTitle) { flush(); curTitle = title; buf = [`## ${title}`]; }
    buf.push(`### ${h.source} :: ${h.heading} (${h.line_from}-${h.line_to}) [score ${h.score}]`);
    buf.push(h.text);
    buf.push(`> why: ${JSON.stringify(h.why)}`);
  }
  flush();
  return {
    query, budgetTokens: budget, usedTokensEstimate: used,
    packed: out.join("\n\n"), chunks: packedCount,
    sources: [...packedSources],
    topScore: hits.length ? hits[0].score : 0,
    index_sha: indexSha(DB), at: Date.now(),
  };
}

export function indexSha(DB) {
  try {
    const rows = DB.prepare("SELECT path, sha FROM doc_sources ORDER BY path").all();
    return sha256(JSON.stringify(rows)).slice(0, 16);
  } catch { return ""; }
}

// Настоящий обход графа: от правила к подтверждающему коммиту и обратно.
/**
 * Полный снимок графа для визуализации. `followThread` даёт только окрестность
 * одного узла, а карту связей надо показать целиком. Ограничение super limited:
 * экран читают глазами, а не скроллом, — 200 рёбер хватает, остальное не рисуем.
 */
export function graphSnapshot(DB, limit = 200) {
  const n = Math.max(1, Math.min(500, Number(limit) || 200));
  const rows = DB.prepare(
    "SELECT source, target, relation FROM graph ORDER BY source, target LIMIT ?",
  ).all(n);
  const nodes = new Set();
  const edges = rows.map((r) => {
    nodes.add(r.source);
    nodes.add(r.target);
    return { from: r.source, to: r.target, relation: r.relation || "related" };
  });
  return { nodes: [...nodes].sort(), edges };
}

export function followThread(DB, node, depth = 2) {
  const out = [];
  const seen = new Set([node]);
  let frontier = [node];
  for (let d = 0; d < Math.min(depth, 4); d++) {
    const next = [];
    for (const n of frontier) {
      const edges = DB.prepare("SELECT source, target, relation FROM graph WHERE source = ? OR target = ? LIMIT 20").all(n, n);
      for (const e of edges) {
        const other = e.source === n ? e.target : e.source;
        if (seen.has(other)) continue;
        seen.add(other);
        out.push({ from: n, to: other, relation: e.relation, depth: d + 1 });
        next.push(other);
      }
    }
    frontier = next;
  }
  return out;
}

// ---- Управление записью: staged → approve → audit → rollback ----------------

export function ingest(DB, args, provenanceModule) {
  const content = String(args.content || "").trim();
  if (!content) throw new Error("content required");
  const prov = provenanceModule ? provenanceModule.normalizeProvenance(args.provenance) : String(args.provenance || "unknown");
  const id = args.id || `stage-${sha256(content).slice(0, 16)}`;
  const sha = sha256(content);
  DB.exec(CORE_SCHEMA);
  DB.prepare(`INSERT INTO memory_staged(id, content, sha, provenance, at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha, provenance=excluded.provenance, at=excluded.at`)
    .run(id, content, sha, prov, Date.now());
  const auto = provenanceModule ? provenanceModule.autoApplyable(prov) : false;
  return { id, sha, provenance: prov, staged: true, auto_applyable: auto };
}

export function approve(DB, id, approvedBy = "operator", reason = "") {
  DB.exec(CORE_SCHEMA);
  const row = DB.prepare("SELECT * FROM memory_staged WHERE id = ?").get(id);
  if (!row) throw new Error("staged memory not found: " + id);
  const auditId = `audit-${sha256(id + Date.now()).slice(0, 16)}`;
  const payload = JSON.stringify(row);
  DB.prepare(`INSERT INTO memory_audit(id, op, target_id, sha_before, sha_after, reason, at, approved_by)
    VALUES (?, 'approve', ?, '', ?, ?, ?, ?)`)
    .run(auditId, id, row.sha, reason, Date.now(), approvedBy);
  DB.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)").run(auditId, payload);
  DB.prepare("DELETE FROM memory_staged WHERE id = ?").run(id);
  return { approved: true, id, sha: row.sha, audit_id: auditId };
}

export function rollback(DB, auditId) {
  DB.exec(CORE_SCHEMA);
  const snap = DB.prepare("SELECT payload FROM memory_snapshot WHERE audit_id = ?").get(auditId);
  if (!snap) throw new Error("audit snapshot not found: " + auditId);
  const row = JSON.parse(snap.payload);
  DB.prepare(`INSERT INTO memory_staged(id, content, sha, provenance, at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha, provenance=excluded.provenance, at=excluded.at`)
    .run(row.id, row.content, row.sha, row.provenance, row.at);
  DB.prepare("DELETE FROM memory_audit WHERE id = ?").run(auditId);
  DB.prepare("DELETE FROM memory_snapshot WHERE audit_id = ?").run(auditId);
  return { rolled_back: true, audit_id: auditId, restored: row.id, sha: row.sha };
}

// ---- Бенчмарк ---------------------------------------------------------------

export function runBench(DB, suite, gold, opts = {}) {
  DB.exec(CORE_SCHEMA);
  let hit5 = 0, rr = 0, ndcg = 0;
  for (const g of gold) {
    const hits = recallDocs(DB, g.query, { limit: 5, scope: opts.scope });
    const ids = hits.map((h) => h.source);
    const idx = ids.indexOf(g.expect_source);
    if (idx >= 0) { hit5++; rr += 1 / (idx + 1); ndcg += 1 / Math.log2(idx + 2); }
  }
  const n = Math.max(1, gold.length);
  // Gate: сравниваем с лучшим предыдущим замером ДО вставки новой строки.
  // Правило из архитектуры: регресс recall@5 не должен превышать 2 п.п.
  const prev = DB.prepare("SELECT MAX(recall5) best FROM bench_result").get();
  const recall5 = hit5 / n;
  let gate = { status: "no-baseline", delta: 0, maxDrop: 0.02 };
  if (prev && prev.best != null) {
    const delta = recall5 - prev.best;
    gate = {
      status: delta >= -0.02 ? "pass" : "FAIL",
      delta: Math.round(delta * 1000) / 1000,
      baseline: prev.best, maxDrop: 0.02,
    };
  }
  const result = {
    suite, n, recall5, mrr: rr / n, ndcg: ndcg / n,
    index_sha: indexSha(DB), at: Date.now(), gate,
  };
  DB.prepare(`INSERT INTO bench_result(suite, recall5, mrr, ndcg, index_sha, at) VALUES (?, ?, ?, ?, ?, ?)`)
    .run(suite, result.recall5, result.mrr, result.ndcg, result.index_sha, result.at);
  return result;
}
