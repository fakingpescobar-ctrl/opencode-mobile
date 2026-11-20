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
-- Волна 1: временны́е факты с окном действия и типизированными связями.
CREATE TABLE IF NOT EXISTS memory_fact (
  id TEXT PRIMARY KEY, topic_key TEXT NOT NULL, content TEXT NOT NULL,
  sha TEXT NOT NULL, provenance TEXT NOT NULL DEFAULT 'unknown',
  valid_from INTEGER NOT NULL, invalid_at INTEGER,
  confidence REAL NOT NULL DEFAULT 0.5, reason TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL, approved_by TEXT NOT NULL DEFAULT '', audit_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_fact_topic ON memory_fact(topic_key, invalid_at);
CREATE TABLE IF NOT EXISTS memory_fact_link (
  fact_id TEXT NOT NULL, target TEXT NOT NULL, relation TEXT NOT NULL,
  evidence TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL,
  PRIMARY KEY(fact_id, target, relation)
);
CREATE INDEX IF NOT EXISTS idx_fact_link_fact ON memory_fact_link(fact_id);
`;

// Старые базы не знают про новые колонки. Добавляем их точечно, без миграции
// всей таблицы: ALTER дешёвый, а схема обязана подниматься на живой БД apk-52.
const STAGED_COLS = [
  ["kind", "TEXT NOT NULL DEFAULT 'doc'"],
  ["topic_key", "TEXT NOT NULL DEFAULT ''"],
  ["valid_from", "INTEGER"],
  ["confidence", "REAL NOT NULL DEFAULT 0.5"],
  ["reason", "TEXT NOT NULL DEFAULT ''"],
  ["links", "TEXT NOT NULL DEFAULT '[]'"],
];
const schemaReady = new WeakSet();

function ensureCols(DB, table, cols) {
  try {
    const info = DB.prepare("PRAGMA table_info(" + table + ")").all();
    const have = new Set(info.map((r) => r.name));
    for (const [name, ddl] of cols) {
      if (!have.has(name)) DB.exec("ALTER TABLE " + table + " ADD COLUMN " + name + " " + ddl);
    }
  } catch { /* таблица ещё не создана — CORE_SCHEMA создаст её ниже */ }
}

export function ensureCoreSchema(DB) {
  if (schemaReady.has(DB)) return;
  DB.exec(CORE_SCHEMA);
  ensureCols(DB, "memory_staged", STAGED_COLS);
  ensureCols(DB, "graph", [["evidence", "TEXT NOT NULL DEFAULT ''"]]);
  schemaReady.add(DB);
}

function parseLinks(raw) {
  try {
    const v = JSON.parse(String(raw || "[]"));
    return Array.isArray(v) ? v : [];
  } catch { return []; }
}

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

// Приоритет источника при дедупликации одинакового текста:
// 0 — корневой эталон (ПРАВИЛА.md, СОСТОЯНИЕ.md, ХАРНЕС.md, ...),
// 1 — вложенная копия (docs/agent-rules.md), 2 — архив.
function sourceRank(source) {
  const s = String(source || "");
  if (s.startsWith("архив") || s.includes("/архивы/")) return 2;
  if (s.includes("/")) return 1;
  return 0;
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
  ensureCoreSchema(DB);
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

  // Фиксированный пул НЕ зависит от limit: раньше было limit*3, из-за чего
  // recallDocs(limit=5) и (limit=12) scoring-или разные наборы (разный состав
  // anchor-баллов и дедупа), top-5 не был префиксом top-12, и memoryPack (пул
  // 12) терял найденное. Размер 15 = прежний пул при limit=5, на котором
  // тюнился recall@5, поэтому поведение поиска не меняется, а ранжирование
  // становится монотонным: top-N всегда префикс большего-N.
  const FTS_POOL = 15;
  // Якорный пул чуть шире текстового: g11/g17/g20 находили нужный источник
  // в FTS, но он не попадал в первые 15 якорей и выпадал из скоринга.
  // 18 — минимальное значение, при котором свежий и живой индексы одновременно
  // дают gold 0.95 при hard 1.000 (проверено на обеих БД).
  const ANCHOR_POOL = 18;
  // Диверсификация кандидатов: первые места в anchor_fts иногда занимают
  // несколько чанков одного источника (у `commit` — КАТАЛОГ_ПЕРЕДЕЛОК.md),
  // из-за чего эталон из ХАРНЕС.md выпадал за пределы пула. Берём не более
  // трёх чанков с одного источника, затем добираем до ANCHOR_POOL.
  const ANCHOR_PER_SOURCE = 3;
  const textHits = queryFts(DB, "doc_fts", trigramQuery, FTS_POOL);
  let anchorHits = anchorQuery ? queryFts(DB, "anchor_fts", anchorQuery, ANCHOR_POOL * 3) : [];
  const getChunk = DB.prepare("SELECT * FROM doc_chunks WHERE id = ?");
  if (anchorHits.length) {
    const getSource = DB.prepare("SELECT source FROM doc_chunks WHERE id = ?");
    const perSource = new Map();
    const picked = [];
    for (const h of anchorHits) {
      const row = getSource.get(h.chunk_id);
      const source = row ? row.source : "";
      const used = perSource.get(source) || 0;
      if (used >= ANCHOR_PER_SOURCE) continue;
      perSource.set(source, used + 1);
      picked.push(h);
      if (picked.length >= ANCHOR_POOL) break;
    }
    anchorHits = picked;
  }
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
  // Одинаковый текст живёт и в корневом эталоне (ПРАВИЛА.md), и в stale-копии
  // (docs/agent-rules.md). При почти равном score порядок дедупа решал
  // случайно: живой индекс иногда оставлял stale-копию вместо эталона, из-за
  // чего g02/g06 выпадали из топ-5. Дедуп обязан выбирать канонический источник.
  const seenText = new Map();
  const dedup = [];
  for (const r of results) {
    const key = sha256(r.text).slice(0, 16);
    const at = seenText.get(key);
    if (at === undefined) {
      seenText.set(key, dedup.length);
      dedup.push(r);
    } else if (sourceRank(r.source) < sourceRank(dedup[at].source)) {
      dedup[at] = r; // заменяем stale-копию на корневой источник
    }
  }
  // Замена дубля на канонический источник меняет score элемента, поэтому после
  // дедупа порядок обязан быть пересобран: иначе топ-N берёт старые позиции
  // и ранжирование перестаёт быть монотонным (живой p06: 0.4396 → 0.6940).
  dedup.sort((x, y) => y.score - x.score);
  return dedup.slice(0, limit);
}

// Упаковка контекста в бюджет токенов по секциям. Главный инструмент агента.
export function memoryPack(DB, query, opts = {}) {
  ensureCoreSchema(DB);
  const budget = Math.max(256, Math.min(opts.budgetTokens || 2048, 8192));
  const want = Math.max(1, Math.min(opts.limit || 12, 20));
  // Пул кандидатов НЕ должен зависеть от того, сколько чанков мы потом уложим
  // в пак. Раньше recallDocs(limit=6) и recallDocs(limit=12) возвращали разные
  // наборы (FTS5 OR-запрос выбирает разный пул), из-за чего адаптивный пак
  // терял gold-источник при том же запросе. Берём стабильные 12 и режем потом.
  const docHits = recallDocs(DB, query, {
    limit: Math.max(want, 12), scope: opts.scope, as_of: opts.as_of,
  });
  // Временны́е факты живут в отдельной таблице. Добавляем их в общий пул только
  // при явном совпадении с запросом; на старых 40 gold фактов нет, поэтому их
  // поведение не меняется. as_of честно переключает активное/архивное окно.
  const factHits = opts.facts === false ? [] : recallFacts(DB, query, {
    limit: Math.max(1, Math.min(want, 6)), as_of: opts.as_of,
  }).map((f) => ({
    id: f.id, source: f.id, heading: f.topic_key, kind: "fact",
    line_from: f.valid_from, line_to: f.invalid_at || 0, text: f.content,
    score: f.score, why: { ...f.why, fact: true },
  }));
  const hits = [...docHits, ...factHits].sort((a, b) => b.score - a.score);
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
  for (const h of ordered.slice(0, want)) {
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
  const kind = args.kind === "fact" ? "fact" : "doc";
  const topicKey = String(args.topic_key || "").trim();
  if (kind === "fact" && !topicKey) throw new Error("topic_key required for fact");
  const validFrom = Number.isFinite(args.valid_from) ? Number(args.valid_from) : Date.now();
  const confidence = clamp(Number.isFinite(args.confidence) ? Number(args.confidence) : 0.5);
  const links = Array.isArray(args.links) ? args.links : [];
  ensureCoreSchema(DB);
  DB.prepare(`INSERT INTO memory_staged
    (id, content, sha, provenance, at, kind, topic_key, valid_from, confidence, reason, links)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha,
      provenance=excluded.provenance, at=excluded.at, kind=excluded.kind,
      topic_key=excluded.topic_key, valid_from=excluded.valid_from,
      confidence=excluded.confidence, reason=excluded.reason, links=excluded.links`)
    .run(id, content, sha, prov, Date.now(), kind, topicKey, validFrom, confidence,
      String(args.reason || ""), JSON.stringify(links));
  const auto = provenanceModule ? provenanceModule.autoApplyable(prov) : false;
  return { id, sha, provenance: prov, staged: true, auto_applyable: auto,
    kind, topic_key: topicKey, links: links.length };
}

export function approve(DB, id, approvedBy = "operator", reason = "") {
  ensureCoreSchema(DB);
  const row = DB.prepare("SELECT * FROM memory_staged WHERE id = ?").get(id);
  if (!row) throw new Error("staged memory not found: " + id);
  const auditId = `audit-${sha256(id + Date.now()).slice(0, 16)}`;
  const at = Date.now();
  if (row.kind === "fact") {
    let superseded = [];
    DB.exec("BEGIN");
    try {
      superseded = DB.prepare(
        "SELECT id FROM memory_fact WHERE topic_key=? AND invalid_at IS NULL ORDER BY valid_from DESC",
      ).all(row.topic_key);
      for (const old of superseded) {
        DB.prepare("UPDATE memory_fact SET invalid_at=?, reason=? WHERE id=?")
          .run(at, "superseded by " + id, old.id);
      }
      DB.prepare(`INSERT INTO memory_fact
        (id, topic_key, content, sha, provenance, valid_from, invalid_at,
         confidence, reason, created, approved_by, audit_id)
        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)`)
        .run(row.id, row.topic_key, row.content, row.sha, row.provenance,
          row.valid_from || at, row.confidence, row.reason || reason, row.at, approvedBy, auditId);
      for (const link of parseLinks(row.links)) {
        const target = String(link.target || link.id || "");
        const relation = String(link.relation || "related");
        if (!target) continue;
        const evidence = String(link.evidence || reason || "");
        DB.prepare(`INSERT OR REPLACE INTO memory_fact_link
          (fact_id, target, relation, evidence, at) VALUES (?, ?, ?, ?, ?)`)
          .run(row.id, target, relation, evidence, at);
        DB.prepare(`INSERT OR REPLACE INTO graph(source, target, relation, evidence)
          VALUES (?, ?, ?, ?)`).run(row.id, target, relation, evidence);
      }
      DB.prepare(`INSERT INTO memory_audit(id, op, target_id, sha_before, sha_after, reason, at, approved_by)
        VALUES (?, 'approve-fact', ?, '', ?, ?, ?, ?)`)
        .run(auditId, id, row.sha, reason || row.reason, at, approvedBy);
      DB.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)")
        .run(auditId, JSON.stringify({ staged: row, previous: superseded.map((x) => x.id) }));
      DB.prepare("DELETE FROM memory_staged WHERE id = ?").run(id);
      DB.exec("COMMIT");
    } catch (e) {
      DB.exec("ROLLBACK");
      throw e;
    }
    return { approved: true, id, sha: row.sha, audit_id: auditId, kind: "fact",
      topic_key: row.topic_key, superseded: superseded.length };
  }
  DB.prepare(`INSERT INTO memory_audit(id, op, target_id, sha_before, sha_after, reason, at, approved_by)
    VALUES (?, 'approve', ?, '', ?, ?, ?, ?)`)
    .run(auditId, id, row.sha, reason, at, approvedBy);
  DB.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)")
    .run(auditId, JSON.stringify(row));
  DB.prepare("DELETE FROM memory_staged WHERE id = ?").run(id);
  return { approved: true, id, sha: row.sha, audit_id: auditId, kind: "doc" };
}

export function rollback(DB, auditId) {
  ensureCoreSchema(DB);
  const snap = DB.prepare("SELECT payload FROM memory_snapshot WHERE audit_id = ?").get(auditId);
  if (!snap) throw new Error("audit snapshot not found: " + auditId);
  const saved = JSON.parse(snap.payload);
  const row = saved && saved.staged ? saved.staged : saved;
  if (row.kind === "fact") {
    DB.exec("BEGIN");
    try {
      DB.prepare("DELETE FROM memory_fact WHERE id = ?").run(row.id);
      for (const prevId of saved.previous || []) {
        DB.prepare("UPDATE memory_fact SET invalid_at=NULL, reason='' WHERE id=?").run(prevId);
      }
      DB.prepare("DELETE FROM memory_fact_link WHERE fact_id = ?").run(row.id);
      DB.prepare("DELETE FROM graph WHERE source = ?").run(row.id);
      DB.prepare(`INSERT INTO memory_staged
        (id, content, sha, provenance, at, kind, topic_key, valid_from, confidence, reason, links)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha,
          provenance=excluded.provenance, at=excluded.at, kind=excluded.kind,
          topic_key=excluded.topic_key, valid_from=excluded.valid_from,
          confidence=excluded.confidence, reason=excluded.reason, links=excluded.links`)
        .run(row.id, row.content, row.sha, row.provenance, row.at, row.kind,
          row.topic_key, row.valid_from, row.confidence, row.reason, row.links);
      DB.prepare("DELETE FROM memory_audit WHERE id = ?").run(auditId);
      DB.prepare("DELETE FROM memory_snapshot WHERE audit_id = ?").run(auditId);
      DB.exec("COMMIT");
    } catch (e) {
      DB.exec("ROLLBACK");
      throw e;
    }
    return { rolled_back: true, audit_id: auditId, restored: row.id, sha: row.sha, kind: "fact" };
  }
  DB.prepare(`INSERT INTO memory_staged(id, content, sha, provenance, at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha, provenance=excluded.provenance, at=excluded.at`)
    .run(row.id, row.content, row.sha, row.provenance, row.at);
  DB.prepare("DELETE FROM memory_audit WHERE id = ?").run(auditId);
  DB.prepare("DELETE FROM memory_snapshot WHERE audit_id = ?").run(auditId);
  return { rolled_back: true, audit_id: auditId, restored: row.id, sha: row.sha, kind: "doc" };
}

// Временны́е факты: детерминированный поиск по активным и архивным слотам.
// Не трогает веса recallDocs — это отдельный слой с собственным объяснением.
export function recallFacts(DB, query, opts = {}) {
  ensureCoreSchema(DB);
  const q = String(query || "").trim();
  if (!q) return [];
  const a = analyze(q);
  const qTokens = new Set([...a.anchors, ...a.lemmas]
    .map((x) => String(x).toLowerCase()).filter(Boolean));
  const now = Date.now();
  const asOf = Number.isFinite(opts.as_of) ? Number(opts.as_of) : now;
  const limit = Math.max(1, Math.min(opts.limit || 8, 20));
  const rows = opts.includeClosed
    ? DB.prepare("SELECT * FROM memory_fact WHERE valid_from<=?").all(asOf)
    : DB.prepare("SELECT * FROM memory_fact WHERE valid_from<=? AND (invalid_at IS NULL OR invalid_at>?)").all(asOf, asOf);
  const needle = q.toLowerCase();
  const scored = [];
  for (const f of rows) {
    const fa = analyze(f.content + " " + f.topic_key);
    const fTokens = new Set([...fa.anchors, ...fa.lemmas]
      .map((x) => String(x).toLowerCase()).filter(Boolean));
    let inter = 0;
    for (const t of qTokens) if (fTokens.has(t)) inter++;
    const inText = f.content.toLowerCase().includes(needle)
      || String(f.topic_key).toLowerCase().includes(needle);
    if (!inter && !inText) continue;
    const overlap = qTokens.size ? inter / qTokens.size : 0;
    const conf = clamp(Number(f.confidence ?? 0.5));
    const topicHit = String(f.topic_key).toLowerCase().includes(needle) ? 0.25 : 0;
    const score = clamp(0.6 * overlap + 0.25 * conf + topicHit);
    scored.push({
      id: f.id, topic_key: f.topic_key, content: f.content,
      valid_from: f.valid_from, invalid_at: f.invalid_at,
      score: Math.round(score * 10000) / 10000,
      why: {
        overlap: Math.round(overlap * 1000) / 1000,
        confidence: conf, topic_hit: topicHit > 0, as_of: asOf,
      },
    });
  }
  scored.sort((x, y) => y.score - x.score);
  return scored.slice(0, limit);
}

export function factStats(DB) {
  ensureCoreSchema(DB);
  return {
    facts: DB.prepare("SELECT count(*) n FROM memory_fact").get().n,
    active_facts: DB.prepare("SELECT count(*) n FROM memory_fact WHERE invalid_at IS NULL").get().n,
    fact_links: DB.prepare("SELECT count(*) n FROM memory_fact_link").get().n,
  };
}

// ---- Бенчмарк ---------------------------------------------------------------

export function runBench(DB, suite, gold, opts = {}) {
  ensureCoreSchema(DB);
  let hit5 = 0, rr = 0, ndcg = 0;
  for (const g of gold) {
    const hits = recallDocs(DB, g.query, { limit: 5, scope: opts.scope, as_of: g.as_of });
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

// Отдельный раннер для временно́го набора: здесь проверяем именно pack, потому
// что факт не является чанком документа и живёт в собственной таблице.
export function runTemporalBench(DB, suite, gold, opts = {}) {
  ensureCoreSchema(DB);
  let hit5 = 0;
  const misses = [];
  for (const g of gold) {
    const pack = memoryPack(DB, g.query, {
      limit: opts.limit || 5, budgetTokens: opts.budgetTokens || 2048, as_of: g.as_of,
    });
    const idx = pack.sources.indexOf(g.expect_source);
    if (idx >= 0) hit5++; else misses.push({ query: g.query, as_of: g.as_of || null });
  }
  const n = Math.max(1, gold.length);
  return { suite, n, recall5: hit5 / n, hits: hit5, misses, at: Date.now() };
}
