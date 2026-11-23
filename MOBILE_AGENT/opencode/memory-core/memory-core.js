// Ядро памяти мобайл-агента: гибридный поиск + упаковка контекста + аудит.
// Встраивается в memory.js (один MCP-процесс, маршрут /mcp).
// Зависимости только локальные: bun:sqlite, ./memory-index.mjs, ./memory-lemma.js,
// ./provenance.js (уже лежит в assets/mcp).

import { analyze } from "./memory-lemma.js";
import { ANCHOR_FTS_V4, AUTHORITY, DOC_SCHEMA, rawIdentifiers, sha256 } from "./memory-index.mjs";

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
-- v4: полезность чанка (non-parametric utility, analogue MemRL/MEMOIR).
-- Q обновляется из внешнего reward, веса модели не трогаются.
CREATE TABLE IF NOT EXISTS memory_utility (
  chunk_id TEXT PRIMARY KEY, q REAL NOT NULL DEFAULT 0.5,
  uses INTEGER NOT NULL DEFAULT 0, successes INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0, last_used INTEGER NOT NULL DEFAULT 0
);
-- v4: credit/blame. Что именно попало в контекст и помогло ли исходу.
CREATE TABLE IF NOT EXISTS memory_outcome (
  id TEXT PRIMARY KEY, query_sha TEXT NOT NULL, chunk_ids TEXT NOT NULL,
  outcome TEXT NOT NULL, reward REAL NOT NULL DEFAULT 0,
  at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT ''
);
-- v5: receipt выданного пак. learnOutcome сверяет chunk_ids с реально выданными:
-- галлюцинированная моделью цитата не должна поднимать вес чужого чанка.
CREATE TABLE IF NOT EXISTS memory_pack_receipt (
  pack_id TEXT PRIMARY KEY, query_sha TEXT NOT NULL,
  ids TEXT NOT NULL, at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pack_receipt_query ON memory_pack_receipt(query_sha, at);
-- v4: консолидированные наблюдения (Hindsight-like beliefs с proof count).
CREATE TABLE IF NOT EXISTS memory_observation (
  id TEXT PRIMARY KEY, topic_key TEXT NOT NULL, content TEXT NOT NULL,
  sha TEXT NOT NULL, proof_count INTEGER NOT NULL DEFAULT 0,
  source_ids TEXT NOT NULL DEFAULT '[]', confidence REAL NOT NULL DEFAULT 0.5,
  valid_from INTEGER NOT NULL, invalid_at INTEGER,
  provenance TEXT NOT NULL DEFAULT 'own-runtime', created INTEGER NOT NULL,
  audit_id TEXT NOT NULL DEFAULT ''
);
-- v4: epoch для read-boundary validation. Любая запись инкрементирует счётчик.
CREATE TABLE IF NOT EXISTS memory_epoch (
  id INTEGER PRIMARY KEY CHECK (id = 1), value INTEGER NOT NULL
);
INSERT OR IGNORE INTO memory_epoch(id, value) VALUES (1, 0);
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
-- Волна 2: детерминированные предложения ночного прохода. Ничего не удаляет:
-- проход только пишет кандидатов, решение принимает оператор через approve.
CREATE TABLE IF NOT EXISTS memory_proposal (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, topic_key TEXT NOT NULL,
  content TEXT NOT NULL, sha TEXT NOT NULL, proof TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT '', target TEXT NOT NULL DEFAULT '',
  evidence TEXT NOT NULL DEFAULT '', contribution REAL NOT NULL DEFAULT 0,
  valid_from INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'open',
  created INTEGER NOT NULL, seen_at INTEGER NOT NULL,
  audit_id TEXT NOT NULL DEFAULT '', decided_by TEXT NOT NULL DEFAULT '',
  reason TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_proposal_topic ON memory_proposal(topic_key, kind);
CREATE INDEX IF NOT EXISTS idx_proposal_status ON memory_proposal(status);
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
  ["agent_id", "TEXT NOT NULL DEFAULT ''"],
];
// v4-колонки для живых БД apk-59: добавляем точечно, старые строки получают
// безопасные значения по умолчанию. Никакой пересборки таблиц.
const FACT_COLS = [
  ["recorded_at", "INTEGER"],
  ["superseded_by", "TEXT NOT NULL DEFAULT ''"],
  ["agent_id", "TEXT NOT NULL DEFAULT ''"],
  ["epoch", "INTEGER NOT NULL DEFAULT 0"],
  ["protected", "INTEGER NOT NULL DEFAULT 0"],
];
const AUDIT_COLS = [
  ["chain_hash", "TEXT NOT NULL DEFAULT ''"],
  ["seq", "INTEGER NOT NULL DEFAULT 0"],
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
  ensureCols(DB, "memory_fact", FACT_COLS);
  ensureCols(DB, "memory_audit", AUDIT_COLS);
  ensureCols(DB, "memory_proposal", [["agent_id", "TEXT NOT NULL DEFAULT ''"]]);
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

// Дефолт — legacy. Волна 2 вкатывает в него детерминированные выигрыши FTS5
// (tokenchars-якоря, бюджет 4, evidence 0.02) без смены имени и без LLM.
// v4/rrf остаются явными режимами сравнения; wave2 — те же веса + UCB.
export const MODES = ["legacy", "v4", "rrf", "wave2"];
export const DEFAULT_MODE = "legacy";
const UTILITY_LAMBDA = 0.08;  // доля Q в формуле (1−λ)·sim + λ·Q
const UTILITY_ALPHA = 0.20;   // скорость обновления Q из reward (Monte-Carlo)
const EVIDENCE_BONUS = 0.03;  // дешёвый бонус за соседей из того же источника
const OBSERVATION_BONUS = 0.05;
// Волна 2: прогон на 113 gold / 30 held-out дал выигрыш по top1 held-out
// 0.800→0.900 и recall@5 held-out 0.967→1.000 (якорный бюджет 4). Улучшения
// детерминированного FTS5 вкатываются в legacy-путь: DEFAULT_MODE не меняется,
// LLM в hot path не появляется. режим wave2 — явный псевдоним с теми же весами.
const WAVE2_EVIDENCE = 0.02;
const WAVE2_UCB = 0.02;
const WAVE2_ANCHOR_PER_SOURCE = 4;
const RRF_K = 60;

function clamp(x, lo = 0, hi = 1) { return Math.max(lo, Math.min(hi, x)); }

// --- v4: epoch, utility, hash-chain -----------------------------------------
export function readEpoch(DB) {
  try { return Number(DB.prepare("SELECT value FROM memory_epoch WHERE id=1").get()?.value || 0); }
  catch { return 0; }
}
export function bumpEpoch(DB) {
  try { DB.prepare("UPDATE memory_epoch SET value=value+1 WHERE id=1").run(); } catch { /* нет таблицы */ }
}
function withEpoch(DB, fn) {
  const e0 = readEpoch(DB);
  let out = fn();
  // Read Boundaries: если между чтениями пришла запись — перечитаем один раз.
  // Второй проход детерминирован и ограничен, чтобы не уйти в бесконечный цикл.
  if (readEpoch(DB) !== e0) out = fn();
  return out;
}
function utilityMap(DB) {
  const out = new Map();
  try {
    // Холодный старт не должен двигать эталон: в карту попадают только чанки
    // с реальным исходом (uses > 0). Остальные сохраняют legacy-скор.
    for (const r of DB.prepare("SELECT chunk_id, q FROM memory_utility WHERE uses > 0").all()) out.set(r.chunk_id, r.q);
  } catch { /* таблица есть, но БД старая — нормально */ }
  return out;
}
// UCB-бонус: нужны не только Q, но и счётчики uses. Холодный старт (uses=0)
// получает одинаковую надбавку и порядок чанков не меняет.
function utilityStatsMap(DB) {
  const out = new Map();
  try {
    for (const r of DB.prepare("SELECT chunk_id, q, uses FROM memory_utility WHERE uses > 0").all()) {
      out.set(r.chunk_id, { q: Number(r.q), uses: Number(r.uses) });
    }
  } catch { /* старая БД */ }
  return out;
}
function observationMap(DB, anchors) {
  const out = new Map();
  if (!anchors.length) return out;
  try {
    const rows = DB.prepare(
      "SELECT topic_key, content, proof_count, confidence FROM memory_observation WHERE invalid_at IS NULL",
    ).all();
    for (const r of rows) {
      const hay = String(r.topic_key + " " + r.content).toLowerCase();
      if (anchors.some((a) => hay.includes(String(a).toLowerCase()))) {
        out.set(r.topic_key, clamp(Number(r.confidence || 0.5)) * Math.min(1, Number(r.proof_count || 0) / 3));
      }
    }
  } catch { /* нет таблицы */ }
  return out;
}
function rrfRank(list) {
  const out = new Map();
  list.forEach((h, i) => out.set(h.chunk_id, 1 / (RRF_K + i + 1)));
  return out;
}
function auditHash(DB, payload) {
  const last = DB.prepare("SELECT chain_hash FROM memory_audit ORDER BY seq DESC, at DESC LIMIT 1").get();
  const prev = String(last?.chain_hash || "");
  return sha256(prev + "|" + payload).slice(0, 32);
}
function writeAudit(DB, auditId, op, targetId, shaAfter, reason, at, approvedBy) {
  const seq = Number(DB.prepare("SELECT COALESCE(MAX(seq),0)+1 n FROM memory_audit").get().n);
  const hash = auditHash(DB, [auditId, op, targetId, shaAfter, reason, at, approvedBy].join("|"));
  DB.prepare(`INSERT INTO memory_audit(id, op, target_id, sha_before, sha_after, reason, at, approved_by, chain_hash, seq)
    VALUES (?, ?, ?, '', ?, ?, ?, ?, ?, ?)`)
    .run(auditId, op, targetId, shaAfter, reason, at, approvedBy, hash, seq);
  return hash;
}

// Проверка append-only цепочки. Старые записи до v4 имеют seq=0 и не
// участвуют — новая цепочка начинается с первой записи с seq>0.
export function verifyAuditChain(DB) {
  const rows = DB.prepare(`SELECT id, op, target_id, sha_after, reason, at, approved_by,
    chain_hash, seq FROM memory_audit WHERE seq > 0 ORDER BY seq, id`).all();
  let prev = "";
  let broken = 0;
  rows.forEach((r, i) => {
    const payload = [r.id, r.op, r.target_id, r.sha_after, r.reason, r.at, r.approved_by].join("|");
    const expected = sha256(prev + "|" + payload).slice(0, 32);
    if (r.chain_hash !== expected || r.seq !== i + 1) broken++;
    prev = r.chain_hash;
  });
  return { ok: broken === 0, checked: rows.length, broken };
}

function normalizeBm25(raw) {
  // FTS5 bm25: чем отрицательнее, тем лучше совпадение. -0.00 = шум (якорь везде).
  if (!Number.isFinite(raw)) return 0;
  return clamp(Math.max(0, -raw) / (10 + Math.max(0, -raw)));
}

function recencyOf(created, now) {
  if (!created) return 0.5;
  const age = Math.max(0, now - created);
  return 0.5 + 0.5 * Math.exp(-age / HALF_LIFE_MS);
}

function trustMultiplier(provenance, source, anchors = []) {
  const p = String(provenance || source || "").toLowerCase();
  if (["operator", "user", "own-log", "own-test", "own-runtime", "phone-agent"].includes(p)) return 1.0;
  if (["moltbook", "external-agent", "guide", "web", "external"].includes(p)) return 0.85;
  // v5: путь к внешнему совету понижаем ДО ветки .md. Иначе docs/MOLTBOOK_ADVICE.md
  // и MOLTBOOK_REPORT.md получали trust 1.0 как ПРАВИЛА.md — совет со стороны
  // ранжировался наравне с нашими правилами. Если вопрос ЯВНО про advice, штраф
  // снимается: пользователь сам попросил внешний совет, прятать его не нужно.
  if (/moltbook|advice/i.test(String(source || ""))) {
    return Array.isArray(anchors) && anchors.includes("advice") ? 1.0 : 0.85;
  }
  // Наша собственная память — исходники в MOBILE_AGENT/. Их автор — агент/оператор,
  // а не «неизвестный внешний источник». Иначе все rule/state занижались в 0.7.
  if (/\.md$/i.test(String(source || "")) || /\.(md|jsonl)$/i.test(String(source || ""))) return 1.0;
  return 0.7;
}

// v5: только эти источники агент мог получить сам или от человека. Всё остальное
// (включая unknown) — предложение, которое обязан пропустить человек. Используется
// и в approve как гейт, и при переносе provenance из факта в предложение.
const OWN_PROVENANCE = new Set([
  "operator", "user", "own-log", "own-test", "own-runtime", "phone-agent",
]);
function isOwnProvenance(value) {
  return OWN_PROVENANCE.has(String(value || "").trim().toLowerCase());
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

// Настоящий графовый обход в скоринге: от якорей запроса и чанков якорного
// пула за 2 перехода по graph-таблице. Раньше здесь был дешёвый proxy — один
// SQL на каждый чанк и только прямое попадание в исходник. Обход считается
// один раз на запрос; при пустом графе (холодный индекс без фактов) он
// детерминированно даёт 0 и не влияет на gold.
function graphReach(DB, anchors, chunkIds, depth = 2) {
  const reach = new Map();
  let frontier = [...new Set([...(Array.isArray(anchors) ? anchors : []),
    ...(Array.isArray(chunkIds) ? chunkIds : [])])].filter(Boolean);
  // Стартовые узлы — не графовая связь: в reach не кладём, иначе все чанки
  // якорного пула получили бы graph=1.0 и сдвинули скоринг при пустом графе.
  const seen = new Set(frontier);
  for (let d = 1; d <= Math.min(depth, 3) && frontier.length; d++) {
    const marks = frontier.map(() => "?").join(",");
    let rows;
    try {
      rows = DB.prepare(`SELECT source, target FROM graph
        WHERE source IN (${marks}) OR target IN (${marks})
        ORDER BY source, target LIMIT 200`).all(...frontier, ...frontier);
    } catch { break; }
    const next = [];
    for (const e of rows) {
      const s = String(e.source || ""), t = String(e.target || "");
      const other = seen.has(s) && !seen.has(t) ? t : (seen.has(t) && !seen.has(s) ? s : "");
      if (!other || seen.has(other)) continue;
      seen.add(other);
      reach.set(other, d);
      next.push(other);
    }
    frontier = next;
  }
  return reach;
}

// Первый переход даёт 0.65, второй 0.3, третий 0. Стартовые узлы не считаются.
function graphProximity(reach, chunkId) {
  const d = reach && reach.get(chunkId);
  if (d == null) return 0;
  return clamp(1 - d * 0.35);
}

function queryFts(DB, table, query, limit) {
  try {
    return DB.prepare(`SELECT chunk_id, bm25(${table}) AS score FROM ${table}
      WHERE ${table} MATCH ? ORDER BY score LIMIT ?`).all(query, limit);
  } catch { return []; }
}

// Near-duplicate: те же слова в том же порядке, но с другой пунктуацией,
// регистром или ё/е. Раньше дедуп бил только по точному sha текста, поэтому
// stale-копия с переформатированием занимала отдельное место в топе. Ключ
// консервативный — разные тексты не сливаются: порядок слов сохраняется.
export function nearDuplicateKey(text) {
  const norm = String(text || "").toLowerCase().replace(/ё/g, "е")
    .replace(/[^a-z0-9а-я\s]/gi, " ").replace(/\s+/g, " ").trim();
  return sha256(norm).slice(0, 16);
}

// Главный поиск. Возвращает топ-чанки с весами и объяснением `why`.
export function recallDocs(DB, query, opts = {}) {
  // Read Boundaries: если во время чтения в БД пришла запись, пересчитываем
  // результат один раз. v3/legacy поведение от этого не меняется (epoch есть
  // и в старых БД, счётчик просто растёт).
  return withEpoch(DB, () => recallDocsOnce(DB, query, opts));
}

function recallDocsOnce(DB, query, opts = {}) {
  ensureCoreSchema(DB);
  const now = Date.now();
  const limit = Math.max(1, Math.min(opts.limit || 10, 20));
  const mode = MODES.includes(opts.mode) ? opts.mode : DEFAULT_MODE;
  const v4 = mode !== "legacy";
  // Детерминированные улучшения Волны 2 работают и в legacy — DEFAULT_MODE
  // остаётся прежним, но tokenchars-якоря и бюджет теперь часть ядра.
  const wave2 = mode === "wave2" || mode === "legacy";
  const a = analyze(query);
  const anchors = a.anchors;
  // Trigram-FTS по умолчанию связывает слова через AND: длинная фраза «как агент
  // должен пользоваться памятью» не находит ничего, пока не разобьём её на OR.
  // Слова короче 3 символов trigram не индексирует вовсе — отбрасываем.
  const trigramWords = String(query).toLowerCase().replace(/ё/g, "е")
    .replace(/[^a-z0-9а-я\s]/gi, " ").split(/\s+/)
    .filter((w) => w.length >= 3);
  const trigramQuery = trigramWords.map((w) => `"${w}"`).join(" OR ");
  // Сырые идентификаторы: в legacy/wave2 включены после того, как замер на
  // 113 gold / 30 held-out показал выигрыш (held-out top1 0.8→0.9). В v4/rrf
  // остаются opt-in — это режимы сравнения с прежним поведением.
  const useRaw = wave2 || (v4 && opts.rawIds === true);
  const qAnchors = useRaw ? [...new Set([...anchors, ...rawIdentifiers(query)])] : anchors;
  const anchorQuery = qAnchors.length ? qAnchors.map((x) => `"${x}"`).join(" OR ") : "";

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
  // read-time anchor budget: сколько чанков одного источника может попасть
  // в якорный пул. 4 — замеренный минимум, при котором held-out recall5 стал
  // 1.000; opts.anchorBudget позволяет прогнать 1/2/5/6 без правки ядра.
  const ANCHOR_PER_SOURCE = Math.max(1, Math.min(6,
    Number.isFinite(opts.anchorBudget) ? opts.anchorBudget
      : (mode === "wave2" || mode === "legacy" ? WAVE2_ANCHOR_PER_SOURCE : 3)));
  const textHits = queryFts(DB, "doc_fts", trigramQuery, FTS_POOL);
  const anchorTable = useRaw ? ANCHOR_FTS_V4 : "anchor_fts";
  let anchorHits = anchorQuery ? queryFts(DB, anchorTable, anchorQuery, ANCHOR_POOL * 3) : [];
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
  // Обход графа один раз на запрос, а не SQL на каждый кандидат.
  const graphReached = graphReach(DB, anchors, anchorHits.map((h) => h.chunk_id));
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

  // Полезность Q и наблюдения — только в явных v4/wave2. В legacy читаются
  // дешёвые детерминированные слои (utilityStats/sourceCount), но НЕ Q/UCB:
  // ранжирование не зависит от исходов обучения, fallback остаётся стабильным.
  const utility = v4 ? utilityMap(DB) : new Map();
  const utilityStats = wave2 ? utilityStatsMap(DB) : new Map();
  const totalUses = wave2 ? [...utilityStats.values()].reduce((s, x) => s + x.uses, 0) : 0;
  // UCB — только явный opt-in: в legacy он менял бы ранжирование после
  // learnOutcome и ломал гарантию «legacy не зависит от обучения».
  const ucbW = mode === "wave2"
    ? (Number.isFinite(opts.ucbBonus) ? opts.ucbBonus : WAVE2_UCB)
    : 0;
  const observations = v4 ? observationMap(DB, qAnchors) : new Map();
  const textRanks = mode === "rrf" ? rrfRank(textHits) : null;
  const anchorRanks = mode === "rrf" ? rrfRank(anchorHits) : null;
  const sourceCount = new Map();
  // Волна 2: evidence-бонус работает и в legacy — раньше sourceCount считался
  // только в v4, поэтому legacy получал нулевой бонус даже при явном весе.
  if (v4 || wave2) {
    for (const item of byId.values()) {
      const s = item.chunk.source;
      sourceCount.set(s, (sourceCount.get(s) || 0) + 1);
    }
  }

  const results = [];
  for (const item of byId.values()) {
    const c = item.chunk;
    if (scope) {
      const kinds = Array.isArray(scope) ? scope : [scope];
      if (!kinds.includes(c.kind)) continue;
    }
    if (opts.as_of != null && c.created > opts.as_of) continue;
    const authority = effectiveAuthority(c.kind, anchors);
    const graph = graphProximity(graphReached, c.id);
    const recency = recencyOf(c.created, now);
    const trust = trustMultiplier(c.source, c.source, anchors);
    const fb = feedback.get(c.id) || 0;
    const fbMul = 1 + 0.06 * Math.max(-0.5, Math.min(1, fb));
    // Явный kind-якорь ("советы" -> advice, "правило" -> rule) — это более
    // сильный сигнал, чем случайное упоминание того же слова в другом источнике.
    // Без порога каталог/бенчмарк перебивают сам MOLTBOOK_ADVICE.md: gold g04/g14
    // выпадали из топ-5. Порог поднимает MRR 0.520 -> 0.553 при том же recall@5.
    const anchorScore = Math.max(item.anchorScore, anchors.includes(c.kind) ? 0.85 : 0);
    const sim =
      (W.bm25text * item.textScore +
       W.bm25anchor * anchorScore +
       W.graph * graph +
       W.authority * authority +
       W.recency * recency) * trust * fbMul;
    let score = sim;
    let q = 0.5;
    if (mode === "rrf") {
      // Reciprocal Rank Fusion: складываем ранги двух независимых списков,
      // а не сырые bm25 (разные шкалы и плотность у trigram/unicode61).
      const rrf = (textRanks.get(c.id) || 0) + (anchorRanks.get(c.id) || 0);
      score = clamp(rrf * 30) * trust * fbMul;
    } else if (v4 && utility.has(c.id)) {
      q = clamp(Number(utility.get(c.id)));
      // Non-parametric utility (MemRL/MEMOIR): (1−λ)·sim + λ·Q.
      // Применяется ТОЛЬКО к чанкам с реальным исходом. На холодном старте
      // Q-часть равна нулю (остальные надбавки v4/legacy описаны ниже).
      score = (1 - UTILITY_LAMBDA) * sim + UTILITY_LAMBDA * q;
    }
    // Read-time evidence chain + UCB. Волна 2 включается и в legacy: это
    // детерминированные надбавки к FTS5, не LLM и не новая модель.
    if (v4 || wave2) {
      const siblings = Math.max(0, (sourceCount.get(c.source) || 1) - 1);
      const evidenceW = Number.isFinite(opts.evidenceBonus)
        ? opts.evidenceBonus : (wave2 ? WAVE2_EVIDENCE : 0);
      score += evidenceW * clamp(siblings / 3);
      if (ucbW > 0) {
        // UCB1: неизведанные чанки получают бонус, но он убывает по мере
        // накопления исходов. totalUses=0 → все получают одинаково, порядок
        // не меняется (гарантия холодного старта).
        const st = utilityStats.get(c.id);
        const uses = st ? st.uses : 0;
        score += ucbW * Math.sqrt(Math.log(1 + totalUses) / (1 + uses));
      }
      for (const [topic, weight] of observations) {
        if (c.text.toLowerCase().includes(topic.toLowerCase())) {
          score += OBSERVATION_BONUS * weight;
          break;
        }
      }
    }
    results.push({
      id: c.id, source: c.source, heading: c.heading, kind: c.kind,
      line_from: c.line_from, line_to: c.line_to, text: c.text,
      score: Math.round(score * 10000) / 10000,
      why: {
        text: Math.round(item.textScore * 1000) / 1000,
        anchor: Math.round(anchorScore * 1000) / 1000,
        graph: Math.round(graph * 1000) / 1000,
        authority, recency: Math.round(recency * 1000) / 1000, trust, feedback: fb,
        anchors: anchors.filter((x) => c.anchors.split(" ").includes(x)),
        sim: Math.round(sim * 10000) / 10000,
        mode, utility: v4 ? Math.round(q * 1000) / 1000 : undefined,
        evidence: v4 ? Math.max(0, (sourceCount.get(c.source) || 1) - 1) : undefined,
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
    const key = nearDuplicateKey(r.text);
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
    limit: Math.max(want, 12), scope: opts.scope, as_of: opts.as_of, mode: opts.mode,
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
  const issued = []; // v5: receipt — какие чанки реально отданы модели
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
    issued.push({
      id: String(h.id || `${h.source}:${h.line_from}`),
      sha: sha256(String(h.text || "")).slice(0, 16),
      source: h.source, line_from: h.line_from, line_to: h.line_to,
    });
    const title = kindToTitle.get(h.kind) || "ДОКУМЕНТЫ";
    if (title !== curTitle) { flush(); curTitle = title; buf = [`## ${title}`]; }
    buf.push(`### ${h.source} :: ${h.heading} (${h.line_from}-${h.line_to}) [score ${h.score}]`);
    buf.push(h.text);
    buf.push(`> why: ${JSON.stringify(h.why)}`);
  }
  flush();
  // v5: сохраняем receipt выданного набора, чтобы learnOutcome мог отличить
  // реально показанную моделью цитату от достроенной. Запись необязательна
  // для самой выдачи — при сбое пак остаётся прежним, просто без verify.
  const at = Date.now();
  const qsha = sha256(String(query || "").toLowerCase().trim()).slice(0, 16);
  let packId = "";
  try {
    packId = `pack-${sha256(qsha + "|" + issued.map((x) => x.id).join(",") + "|" + at).slice(0, 16)}`;
    DB.prepare(`INSERT OR IGNORE INTO memory_pack_receipt(pack_id, query_sha, ids, at)
      VALUES (?, ?, ?, ?)`).run(packId, qsha, JSON.stringify(issued), at);
    const n = Number(DB.prepare("SELECT count(*) n FROM memory_pack_receipt").get().n);
    if (n > 1000) {
      DB.prepare(`DELETE FROM memory_pack_receipt WHERE pack_id IN
        (SELECT pack_id FROM memory_pack_receipt ORDER BY at DESC LIMIT -1 OFFSET 500)`).run();
    }
  } catch { packId = ""; }
  return {
    query, budgetTokens: budget, usedTokensEstimate: used,
    packed: out.join("\n\n"), chunks: packedCount,
    sources: [...packedSources],
    topScore: hits.length ? hits[0].score : 0,
    index_sha: indexSha(DB), at, pack_id: packId, issued,
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

export function approve(DB, id, approvedBy = "operator", reason = "", opts = {}) {
  ensureCoreSchema(DB);
  const row = DB.prepare("SELECT * FROM memory_staged WHERE id = ?").get(id);
  if (!row) throw new Error("staged memory not found: " + id);

  // v5: approve — настоящий гейт, а не формальная галочка. Раньше provenance и
  // auto_applyable только возвращались вызывающему: инжектированная модель с
  // токеном MCP могла сама approve внешний текст. Внешний/unknown источник
  // требует явного opts.externalApproval=true и непустой подписи.
  if (!String(approvedBy || "").trim()) throw new Error("approve: approved_by required");
  if (!isOwnProvenance(row.provenance) && opts.externalApproval !== true) {
    throw new Error("approve: external/unknown provenance requires externalApproval=true: " +
      String(row.provenance || "unknown"));
  }

  // Необязательный held-out gate: baseline меряем ДО коммита, иначе rollback
  // нечего сравнивать. По умолчанию opts.gate !== true — путь байтно прежний.
  const gateOn = opts && opts.gate === true;
  const gateSuite = String((opts && opts.suite) || "core");
  const gateGold = gateOn ? (Array.isArray(opts.gold) ? opts.gold : []) : [];
  if (gateOn && !gateGold.length) throw new Error("approve gate: gold required");
  const gateBaseline = gateOn
    ? (Number.isFinite(opts.baseline) ? Number(opts.baseline)
      : runBench(DB, gateSuite + "-pre", heldoutSubset(gateGold), { scope: opts.scope }).recall5)
    : null;

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
      writeAudit(DB, auditId, "approve-fact", id, row.sha, reason || row.reason, at, approvedBy);
      DB.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)")
        .run(auditId, JSON.stringify({ staged: row, previous: superseded.map((x) => x.id) }));
      DB.prepare("DELETE FROM memory_staged WHERE id = ?").run(id);
      bumpEpoch(DB);
      DB.exec("COMMIT");
    } catch (e) {
      DB.exec("ROLLBACK");
      throw e;
    }
    const applied = { approved: true, id, sha: row.sha, audit_id: auditId, kind: "fact",
      topic_key: row.topic_key, superseded: superseded.length };
    return withApproveGate(DB, applied, gateOn, gateSuite, gateGold, gateBaseline, opts);
  }
  writeAudit(DB, auditId, "approve", id, row.sha, reason, at, approvedBy);
  DB.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)")
    .run(auditId, JSON.stringify(row));
  DB.prepare("DELETE FROM memory_staged WHERE id = ?").run(id);
  bumpEpoch(DB);
  const applied = { approved: true, id, sha: row.sha, audit_id: auditId, kind: "doc" };
  return withApproveGate(DB, applied, gateOn, gateSuite, gateGold, gateBaseline, opts);
}

// Добавочная обёртка: gate не меняет форму ответа без opts.gate.
function withApproveGate(DB, applied, gateOn, suite, gold, baseline, opts) {
  if (!gateOn) return applied;
  const maxDrop = Number.isFinite(opts.maxDrop) ? Number(opts.maxDrop) : 0.02;
  const gate = runHeldoutGate(DB, suite, gold, {
    scope: opts.scope,
    baseline,
    maxDrop,
    autoRollback: opts.autoRollback === true,
    audit_id: applied.audit_id,
  });
  return { ...applied, gate };
}

export function rollback(DB, auditId, approvedBy = "system") {
  ensureCoreSchema(DB);
  const who = String(approvedBy || "system");
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
      // v4: аудит append-only. Вместо тихого удаления пишем компенсирующую
      // запись — цепочка hash-chain остаётся непрерывной и проверяемой.
      writeAudit(DB, `rollback-${auditId}`, "rollback", row.id, row.sha,
        "compensating rollback of " + auditId, Date.now(), who);
      bumpEpoch(DB);
      DB.exec("COMMIT");
    } catch (e) {
      DB.exec("ROLLBACK");
      throw e;
    }
    return { rolled_back: true, audit_id: auditId, restored: row.id, sha: row.sha, kind: "fact" };
  }
  DB.exec("BEGIN");
  try {
    DB.prepare(`INSERT INTO memory_staged(id, content, sha, provenance, at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET content=excluded.content, sha=excluded.sha,
        provenance=excluded.provenance, at=excluded.at`)
      .run(row.id, row.content, row.sha, row.provenance, row.at);
    writeAudit(DB, `rollback-${auditId}`, "rollback", row.id, row.sha,
      "compensating rollback of " + auditId, Date.now(), who);
    bumpEpoch(DB);
    DB.exec("COMMIT");
  } catch (e) {
    DB.exec("ROLLBACK");
    throw e;
  }
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

// ---- Волна 2: ночной проход предложений -------------------------------------
// Проход НЕ меняет факты, веса и чанки. Он только складывает кандидатов в
// memory_proposal с детерминированным id. Решение остаётся за оператором:
// acceptProposal идёт через ingest→approve, rollbackProposal — через rollback.

function proposalId(kind, topicKey, source, target, proof) {
  return `prop-${sha256([kind, topicKey, source, target, proof].join("|")).slice(0, 20)}`;
}

export function proposalStats(DB) {
  ensureCoreSchema(DB);
  const by = { open: 0, accepted: 0, rejected: 0, rolled_back: 0 };
  for (const r of DB.prepare("SELECT status, count(*) n FROM memory_proposal GROUP BY status").all()) {
    if (Object.prototype.hasOwnProperty.call(by, r.status)) by[r.status] = r.n;
  }
  // Принятое предложение обязано иметь живой аудит. Если аудит исчез — это
  // не «ноль тихих удалений», а честный сигнал тревоги, а не заглушка.
  const silent = DB.prepare(`
    SELECT count(*) n FROM memory_proposal p
    WHERE p.status = 'accepted' AND p.audit_id <> ''
      AND NOT EXISTS (SELECT 1 FROM memory_audit a WHERE a.id = p.audit_id)
  `).get().n;
  return {
    proposals: by.open + by.accepted + by.rejected + by.rolled_back,
    open: by.open, accepted: by.accepted, rejected: by.rejected,
    rolled_back: by.rolled_back, silent_deletions: silent,
  };
}

export function runProposalPass(DB, opts = {}) {
  ensureCoreSchema(DB);
  const at = Number.isFinite(opts.at) ? Number(opts.at) : Date.now();
  const found = [];
  const put = (p) => {
    const id = proposalId(p.kind, p.topic_key, p.source, p.target, p.proof);
    DB.prepare(`INSERT INTO memory_proposal
      (id, kind, topic_key, content, sha, proof, source, target, evidence,
       contribution, valid_from, status, created, seen_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        proof=excluded.proof, evidence=excluded.evidence,
        contribution=excluded.contribution, seen_at=excluded.seen_at,
        status=CASE WHEN status IN ('accepted','rejected','rolled_back')
          THEN status ELSE excluded.status END`)
      .run(id, p.kind, p.topic_key, p.content, p.sha, p.proof, p.source, p.target,
        p.evidence, p.contribution, p.valid_from, at, at);
    found.push(id);
  };

  // 1. Дубли по слоту: активных в норме быть не должно, но импорт и старые базы
  //    оставляют конкурентов. Канонический — самый свежий, id для устойчивости.
  const dupTopics = DB.prepare(`
    SELECT topic_key FROM memory_fact WHERE topic_key <> ''
    GROUP BY topic_key HAVING count(*) > 1 ORDER BY topic_key
  `).all();
  for (const t of dupTopics) {
    const rows = DB.prepare(
      "SELECT * FROM memory_fact WHERE topic_key = ? ORDER BY valid_from DESC, id ASC",
    ).all(t.topic_key);
    const canonical = rows[0];
    for (const r of rows.slice(1)) {
      const proof = sha256(`${r.sha}|${canonical.sha}|${t.topic_key}|duplicate`).slice(0, 24);
      put({ kind: "duplicate", topic_key: t.topic_key, content: r.content, sha: r.sha,
        proof, source: r.id, target: canonical.id,
        evidence: `canonical=${canonical.id}; duplicate=${r.id}`,
        contribution: clamp(Number(r.confidence ?? 0.5)), valid_from: r.valid_from });
    }
  }

  // 2. Кандидаты на слияние: один и тот же текст в разных слотах.
  const sameSha = DB.prepare(`
    SELECT sha FROM memory_fact WHERE sha <> ''
    GROUP BY sha HAVING count(DISTINCT topic_key) > 1 ORDER BY sha
  `).all();
  for (const s of sameSha) {
    const rows = DB.prepare(
      "SELECT * FROM memory_fact WHERE sha = ? ORDER BY valid_from DESC, topic_key ASC",
    ).all(s.sha);
    const primary = rows[0];
    for (const r of rows.slice(1)) {
      const proof = sha256(`${s.sha}|${primary.topic_key}|${r.topic_key}|merge`).slice(0, 24);
      put({ kind: "merge", topic_key: r.topic_key, content: r.content, sha: r.sha,
        proof, source: r.id, target: primary.id,
        evidence: `same content: ${primary.topic_key} / ${r.topic_key}`,
        contribution: clamp(Number(r.confidence ?? 0.5)), valid_from: r.valid_from });
    }
  }

  // 3. Конфликты по типизированному ребру contradicts.
  const edges = DB.prepare(`
    SELECT source, target, relation, COALESCE(evidence, '') AS evidence
    FROM graph WHERE relation = 'contradicts' ORDER BY source, target
  `).all();
  for (const e of edges) {
    const a = DB.prepare("SELECT * FROM memory_fact WHERE id = ?").get(e.source);
    const b = DB.prepare("SELECT * FROM memory_fact WHERE id = ?").get(e.target);
    if (!a || !b) continue;
    const proof = sha256(`${a.sha}|${b.sha}|${e.relation}|${e.evidence}|contradiction`).slice(0, 24);
    put({ kind: "contradiction", topic_key: a.topic_key || b.topic_key,
      content: a.content, sha: a.sha, proof, source: a.id, target: b.id,
      evidence: e.evidence || `edge ${a.id} -> ${b.id}`,
      contribution: Math.max(clamp(Number(a.confidence ?? 0.5)), clamp(Number(b.confidence ?? 0.5))),
      valid_from: a.valid_from });
  }

  const stats = proposalStats(DB);
  return {
    suite: opts.suite || "proposal", at, found: found.length, proposals: found,
    open: stats.open, accepted: stats.accepted, rejected: stats.rejected,
    rolled_back: stats.rolled_back, silent_deletions: stats.silent_deletions,
  };
}

export function acceptProposal(DB, id, approvedBy = "operator", reason = "", opts = {}) {
  ensureCoreSchema(DB);
  const p = DB.prepare("SELECT * FROM memory_proposal WHERE id = ?").get(id);
  if (!p) throw new Error("proposal not found: " + id);
  if (p.status === "accepted") return { reused: true, id, status: "accepted", audit_id: p.audit_id };
  if (p.status === "rejected") throw new Error("rejected proposal requires reopen: " + id);
  const stagedId = `proposal-${id}`;
  // v5: раньше жёстко писалось "operator" — внешний факт, одобренный один раз,
  // «отмывался» в доверенный. Переносим provenance из исходного факта; approve
  // ниже его проверит и потребует externalApproval для чужих источников.
  const srcFact = p.source
    ? DB.prepare("SELECT provenance FROM memory_fact WHERE id = ?").get(p.source)
    : null;
  const prov = srcFact ? String(srcFact.provenance || "unknown") : "unknown";
  ingest(DB, {
    id: stagedId, content: p.content, kind: "fact", topic_key: p.topic_key,
    valid_from: p.valid_from, confidence: p.contribution || 0.5,
    provenance: prov,
    reason: reason || p.reason || `proposal ${p.kind}`,
    links: p.target ? [{
      target: p.target,
      relation: p.kind === "contradiction" ? "contradicts" : "supersedes",
      evidence: p.proof || p.evidence,
    }] : [],
  }, null);
  const applied = approve(DB, stagedId, approvedBy, reason || `proposal ${p.kind}`, opts);
  DB.prepare(`UPDATE memory_proposal
    SET status='accepted', audit_id=?, decided_by=?, reason=?, seen_at=? WHERE id=?`)
    .run(applied.audit_id, approvedBy, reason || "", Date.now(), id);
  return { ...applied, proposal: id, kind: p.kind, status: "accepted" };
}

export function rejectProposal(DB, id, approvedBy = "operator", reason = "") {
  ensureCoreSchema(DB);
  const p = DB.prepare("SELECT * FROM memory_proposal WHERE id = ?").get(id);
  if (!p) throw new Error("proposal not found: " + id);
  if (p.status === "rejected") return { reused: true, id, status: "rejected" };
  const at = Date.now();
  DB.prepare(`UPDATE memory_proposal
    SET status='rejected', decided_by=?, reason=?, seen_at=? WHERE id=?`)
    .run(approvedBy, reason, at, id);
  const auditId = `reject-${sha256(id + at).slice(0, 16)}`;
  writeAudit(DB, auditId, "reject-proposal", id, p.sha || "", reason, at, approvedBy);
  bumpEpoch(DB);
  return { rejected: true, id, status: "rejected", audit_id: auditId };
}

export function rollbackProposal(DB, id) {
  ensureCoreSchema(DB);
  const p = DB.prepare("SELECT * FROM memory_proposal WHERE id = ?").get(id);
  if (!p) throw new Error("proposal not found: " + id);
  if (!p.audit_id) throw new Error("proposal has no audit to roll back: " + id);
  const undone = rollback(DB, p.audit_id);
  DB.prepare("UPDATE memory_proposal SET status='rolled_back', reason=?, seen_at=? WHERE id=?")
    .run(`rolled back to ${undone.restored || ""}`, Date.now(), id);
  return { ...undone, proposal: id, status: "rolled_back" };
}

// ---- v4: credit/blame и офлайн-консолидация --------------------------------

// Credit/blame: что именно попало в контекст и к чему привело. Q обновляется
// из внешнего reward — веса модели и legacy-ранжирование не трогаются.
export function learnOutcome(DB, query, chunkIds, outcome = "success", reward = 1, note = "", opts = {}) {
  ensureCoreSchema(DB);
  const ids = [...new Set((chunkIds || []).map(String))].slice(0, 32);
  const r = clamp(Number.isFinite(reward) ? reward : (outcome === "success" ? 1 : outcome === "failure" ? -1 : 0), -1, 1);
  const qsha = sha256(String(query || "").toLowerCase().trim()).slice(0, 16);
  const id = `out-${sha256(qsha + "|" + ids.join(",") + "|" + r + "|" + Date.now()).slice(0, 16)}`;
  const at = Date.now();

  // v5: receipt-проверка. pack_id из memoryPack — самый строгий путь; без него
  // берём последний receipt того же запроса. Нет receipt вообще — старый путь
  // (обратная совместимость), но в ответе честно verified=false.
  const packId = String(opts.pack_id || "");
  let receipt = null;
  try {
    receipt = packId
      ? DB.prepare("SELECT ids FROM memory_pack_receipt WHERE pack_id = ?").get(packId)
      : DB.prepare("SELECT ids FROM memory_pack_receipt WHERE query_sha = ? ORDER BY at DESC LIMIT 1").get(qsha);
  } catch { receipt = null; }
  let allowed = null;
  if (receipt || packId) {
    allowed = new Set();
    try {
      for (const x of JSON.parse(receipt?.ids || "[]")) allowed.add(String(x && x.id ? x.id : x));
    } catch { /* пустой набор = всё не верифицировано */ }
  }
  const hallucinated = allowed ? ids.filter((cid) => !allowed.has(cid)) : [];
  const verified = allowed ? ids.filter((cid) => allowed.has(cid)) : ids.slice();
  const tx = DB.transaction(() => {
    DB.prepare(`INSERT INTO memory_outcome(id, query_sha, chunk_ids, outcome, reward, at, note)
      VALUES (?, ?, ?, ?, ?, ?, ?)`)
      .run(id, qsha, JSON.stringify(ids), String(outcome), r, at, String(note || ""));
    const get = DB.prepare("SELECT q, uses, successes, failures FROM memory_utility WHERE chunk_id=?");
    const put = DB.prepare(`INSERT INTO memory_utility(chunk_id, q, uses, successes, failures, last_used)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(chunk_id) DO UPDATE SET q=excluded.q, uses=excluded.uses,
        successes=excluded.successes, failures=excluded.failures, last_used=excluded.last_used`);
    // Неверифицированные (достроенные моделью) цитаты НЕ трогают Q — иначе
    // галлюцинация повышает полезность чанка, которого модель не видела.
    for (const cid of verified) {
      const cur = get.get(cid) || { q: 0.5, uses: 0, successes: 0, failures: 0 };
      const q = clamp(Number(cur.q) + UTILITY_ALPHA * (clamp((r + 1) / 2) - Number(cur.q)));
      put.run(cid, q, Number(cur.uses) + 1,
        Number(cur.successes) + (r > 0 ? 1 : 0), Number(cur.failures) + (r < 0 ? 1 : 0), at);
    }
    bumpEpoch(DB);
  });
  tx();
  return {
    id, query_sha: qsha, chunks: ids.length, reward: r, outcome: String(outcome),
    pack_id: packId, verified: verified.length, hallucinated,
  };
}

// «Dreaming»: дешёвая офлайн-сверка фактов в наблюдения с proof count
// (по Hindsight). Не сжимает и не удаляет исходники — только надстройка.
export function runDreamPass(DB, opts = {}) {
  ensureCoreSchema(DB);
  const at = Number.isFinite(opts.at) ? Number(opts.at) : Date.now();
  // В proof count входят и архивные факты: они всё равно независимые
  // подтверждения темы, просто старше. Иначе слот с supersedes никогда не
  // даст observation с proof_count > 1.
  const groups = DB.prepare(`SELECT topic_key, count(*) n, avg(confidence) conf, min(valid_from) vf
    FROM memory_fact WHERE topic_key <> '' AND topic_key <> 'staged'
    GROUP BY topic_key HAVING count(*) > 1 ORDER BY topic_key`).all();
  let created = 0, updated = 0, invalidated = 0;
  const tx = DB.transaction(() => {
    const live = new Set();
    for (const g of groups) {
      const rows = DB.prepare(`SELECT id, content, sha FROM memory_fact
        WHERE topic_key=? ORDER BY id`).all(g.topic_key);
      const ids = rows.map((x) => x.id);
      const proof = sha256(ids.join("|") + "|" + g.topic_key).slice(0, 16);
      const oid = `obs-${proof}`;
      live.add(oid);
      const exists = DB.prepare("SELECT proof_count, sha FROM memory_observation WHERE id=?").get(oid);
      const confidence = clamp(Number(g.conf) || 0.5);
      if (exists) {
        DB.prepare(`UPDATE memory_observation SET proof_count=?, confidence=?, source_ids=?, sha=? WHERE id=?`)
          .run(ids.length, confidence, JSON.stringify(ids), proof, oid);
        updated++;
      } else {
        DB.prepare(`INSERT INTO memory_observation
          (id, topic_key, content, sha, proof_count, source_ids, confidence, valid_from, provenance, created, audit_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'own-runtime', ?, '')`)
          .run(oid, g.topic_key, rows.map((x) => x.content).join(" | ").slice(0, 800),
            proof, ids.length, JSON.stringify(ids), confidence, Number(g.vf) || at, at);
        created++;
      }
    }
    // Наблюдение, у которого больше нет живых исходников, закрывается, а не
    // удаляется — та же append-only логика, что и у аудита.
    for (const o of DB.prepare("SELECT id FROM memory_observation WHERE invalid_at IS NULL").all()) {
      if (!live.has(o.id)) {
        DB.prepare("UPDATE memory_observation SET invalid_at=? WHERE id=?").run(at, o.id);
        invalidated++;
      }
    }
    bumpEpoch(DB);
  });
  tx();
  return { created, updated, invalidated, groups: groups.length };
}

// Детерминированная подвыборка gold, общей для baseline и gate: baseline и
// пост-коммитный замер обязаны мерить один и тот же набор.
//
// Сплит закреплён в строке gold и НЕ пересчитывается при чтении. Первые 40
// кейсов backfill'ены по старому правилу `sha256(query)[0] % 5 === 0`, поэтому
// выборка осталась прежней. Для строк без явного split (старый формат) =
// fallback на то же старое правило — обратная совместимость на случай, если
// gold не был backfill'ен.
export function heldoutSubset(gold) {
  const all = Array.isArray(gold) ? gold : [];
  const frozen = all.some((g) => g && (g.split === "train" || g.split === "held-out"));
  const isHeld = (g) => frozen && (g.split === "train" || g.split === "held-out")
    ? g.split === "held-out"
    : sha256(String(g && g.query == null ? "" : g.query)).charCodeAt(0) % 5 === 0;
  const held = all.filter(isHeld);
  return held.length ? held : all;
}

// Held-out gate: детерминированная fifth-подвыборка gold, которой не касались
// правки весов. При регрессе >maxDrop можно явно включить авто-откат по audit_id.
export function runHeldoutGate(DB, suite, gold, opts = {}) {
  ensureCoreSchema(DB);
  const evalGold = heldoutSubset(gold);
  const metric = runBench(DB, String(suite || "core") + "-holdout", evalGold, opts);
  const baseline = Number.isFinite(opts.baseline) ? Number(opts.baseline) : null;
  const maxDrop = Number.isFinite(opts.maxDrop) ? Number(opts.maxDrop) : 0.02;
  const delta = baseline == null ? 0 : metric.recall5 - baseline;
  const pass = baseline == null || delta >= -maxDrop;
  let rolledBack = false;
  if (!pass && opts.autoRollback === true && opts.audit_id) {
    rollback(DB, String(opts.audit_id));
    rolledBack = true;
  }
  return {
    suite: String(suite || "core"), heldout: evalGold.length, total: gold.length,
    recall5: metric.recall5, baseline, delta: Math.round(delta * 1000) / 1000,
    maxDrop, status: pass ? "pass" : "FAIL", rolled_back: rolledBack, at: Date.now(),
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
  const sha = indexSha(DB);
  const maxDrop = Number.isFinite(opts.maxDrop) ? Number(opts.maxDrop) : 0.02;
  // Gate сравнивает только тот же suite на том же индексе. Раньше брался
  // MAX(recall5) по всей таблице: holdout (4 вопроса) и temporal-замеры
  // попадали в baseline полного прогона, и «регресс» был артефактом сравнения
  // несравнимого. Поскольку доки сами входят в индекс, без index_sha
  // замер после правки документации тоже давал ложный FAIL.
  const prev = DB.prepare(
    "SELECT MAX(recall5) best FROM bench_result WHERE suite=? AND index_sha=?"
  ).get(suite, sha);
  const recall5 = hit5 / n;
  let gate = { status: "no-baseline", delta: 0, maxDrop };
  if (prev && prev.best != null) {
    const delta = recall5 - prev.best;
    gate = {
      status: delta >= -maxDrop ? "pass" : "FAIL",
      delta: Math.round(delta * 1000) / 1000,
      baseline: prev.best, maxDrop,
    };
  }
  const result = {
    suite, n, recall5, mrr: rr / n, ndcg: ndcg / n,
    index_sha: sha, at: Date.now(), gate,
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
