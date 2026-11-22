// Data-plane адаптивного слоя: маленькие таблицы порогов/весов в app-private
// filesDir под hash-замком. НЕ меняет FTS5-ядро, НЕ пишет в исходники,
// НЕ включается по умолчанию. Code-plane (патчи ядра) остаётся отдельным.
//
// Контракт безопасности:
//   - enabled по умолчанию false;
//   - каждый proposal проходит delta cap 10%, кулдаун 20 уникальных
//     запросов и maxDrop 0.02 на held-out;
//   - запись атомарная (tmp + rename), при несовпадении hash состояние
//     карантинируется и используется дефолт — безопасный отказ;
//   - rollback хранит снимок параметров до последнего approve.
//
// Импорт: только локальные модули, без сети и без нативных библиотек.

import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { sha256 } from "./memory-index.mjs";

export const ADAPTIVE_LIMITS = Object.freeze({
  deltaCap: 0.10,
  cooldownUniqueQueries: 20,
  maxDrop: 0.02,
  maxSeen: 256,
  maxHistory: 32,
});

export const DEFAULT_PARAMS = Object.freeze({
  // Значения из research-протокола hybrid-gate.json; не подгонялись под gold.
  marginCut: 0.0057,
  confCut: 0.158,
  weight: 0.5,
  fusion: "rrf",
});

const VERSION = 1;
const FILE = "memory-adaptive.json";

function defaultState() {
  return {
    version: VERSION,
    enabled: false,
    params: { ...DEFAULT_PARAMS },
    signals: {},
    seen: [],
    pending: null,
    snapshot: null,
    history: [],
    updatedAt: 0,
    hash: "",
  };
}

function canonical(state) {
  const { hash, ...rest } = state;
  return JSON.stringify(rest);
}

function seal(state) {
  const copy = { ...state };
  copy.hash = sha256(canonical(copy));
  return copy;
}

// Возвращает путь к файлу состояния. Директория должна быть app-private.
export function statePath(dir) {
  return join(String(dir || process.env.MEMORY_ADAPTIVE_DIR || process.cwd()), FILE);
}

// Читает состояние. При битом hash или несовместимой версии возвращает
// дефолт с флагом quarantined — jamais не доверяем повреждённому файлу.
export function loadState(dir) {
  const path = statePath(dir);
  if (!existsSync(path)) return { state: seal(defaultState()), status: "new" };
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return { state: seal(defaultState()), status: "quarantined" };
  }
  if (!parsed || parsed.version !== VERSION || typeof parsed.hash !== "string" ||
      parsed.hash !== sha256(canonical(parsed))) {
    return { state: seal(defaultState()), status: "quarantined" };
  }
  return { state: parsed, status: "ok" };
}

// Атомарная запись. В tmp кладём уже запечатанный JSON.
export function saveState(dir, state) {
  const path = statePath(dir);
  const folder = join(path, "..");
  mkdirSync(folder, { recursive: true });
  const sealed = seal(state);
  const tmp = `${path}.tmp-${process.pid}`;
  writeFileSync(tmp, JSON.stringify(sealed, null, 2) + "\n");
  renameSync(tmp, path);
  return sealed;
}

export function setEnabled(dir, enabled) {
  const { state } = loadState(dir);
  state.enabled = Boolean(enabled);
  state.updatedAt = Date.now();
  return saveState(dir, state);
}

// Учёт сигналов. Возвращает, можно ли уже предлагать новую гипотезу.
export function recordSignal(dir, sig) {
  const { state } = loadState(dir);
  if (!state.enabled) return { eligible: false, reason: "disabled", seen: state.seen.length };
  const kind = String(sig?.kind || "any");
  const signal = String(sig?.signal || "unknown");
  const key = `${kind}:${signal}`;
  const row = state.signals[key] || { successes: 0, failures: 0 };
  if (sig?.success) row.successes++;
  else row.failures++;
  state.signals[key] = row;

  const qid = String(sig?.queryId || "");
  if (qid && !state.seen.includes(qid)) {
    state.seen.push(qid);
    if (state.seen.length > ADAPTIVE_LIMITS.maxSeen) {
      state.seen = state.seen.slice(-ADAPTIVE_LIMITS.maxSeen);
    }
  }
  const eligible = state.seen.length >= ADAPTIVE_LIMITS.cooldownUniqueQueries && !state.pending;
  const next = { ...state, updatedAt: Date.now() };
  saveState(dir, next);
  return {
    eligible,
    reason: eligible ? "ready" : state.pending ? "pending" : "cooldown",
    seen: next.seen.length,
  };
}

function numericDelta(a, b) {
  const av = Number(a), bv = Number(b);
  if (!Number.isFinite(av) || !Number.isFinite(bv)) return Infinity;
  return Math.abs(bv - av) / Math.max(Math.abs(av), 1e-9);
}

// Готовит гипотезу, но НЕ применяет её. Все три ограничения обязательны.
export function propose(dir, hypothesis) {
  const { state } = loadState(dir);
  if (!state.enabled) return { ok: false, reason: "disabled" };
  if (state.pending) return { ok: false, reason: "pending-exists" };
  if (state.seen.length < ADAPTIVE_LIMITS.cooldownUniqueQueries) {
    return { ok: false, reason: "cooldown" };
  }

  const base = hypothesis?.baseline || {};
  const cand = hypothesis?.candidate || {};
  const trainBase = Number(base.trainTop1), trainCand = Number(cand.trainTop1);
  const heldBase = Number(base.heldoutTop1), heldCand = Number(cand.heldoutTop1);
  if (![trainBase, trainCand, heldBase, heldCand].every(Number.isFinite)) {
    return { ok: false, reason: "bad-metrics" };
  }
  if (!(trainCand > trainBase)) return { ok: false, reason: "no-train-gain" };
  if (heldCand < heldBase - ADAPTIVE_LIMITS.maxDrop) return { ok: false, reason: "heldout-drop" };

  const nextParams = { ...state.params, ...(cand.params || {}) };
  for (const k of ["marginCut", "confCut", "weight"]) {
    if (numericDelta(state.params[k], nextParams[k]) > ADAPTIVE_LIMITS.deltaCap) {
      return { ok: false, reason: `delta-cap:${k}` };
    }
  }
  if (!["linear", "rrf"].includes(String(nextParams.fusion))) {
    return { ok: false, reason: "bad-fusion" };
  }

  state.pending = {
    indexSha: String(hypothesis?.indexSha || ""),
    baseline: { trainTop1: trainBase, heldoutTop1: heldBase },
    candidate: { trainTop1: trainCand, heldoutTop1: heldCand, params: nextParams },
    createdAt: Date.now(),
  };
  saveState(dir, state);
  return { ok: true, pending: state.pending };
}

// Финальный гейт: тот же index_sha, тот же maxDrop, то же улучшение train.
export function approve(dir, gate) {
  const { state } = loadState(dir);
  if (!state.pending) return { ok: false, reason: "no-pending" };
  const p = state.pending;
  if (!gate || String(gate.indexSha || "") !== p.indexSha) {
    return { ok: false, reason: "index-sha-mismatch" };
  }
  const train = Number(gate.trainTop1), held = Number(gate.heldoutTop1);
  if (!Number.isFinite(train) || !Number.isFinite(held)) return { ok: false, reason: "bad-gate" };
  if (!(train > p.baseline.trainTop1)) return { ok: false, reason: "no-train-gain" };
  if (held < p.baseline.heldoutTop1 - ADAPTIVE_LIMITS.maxDrop) {
    return { ok: false, reason: "heldout-drop" };
  }

  state.snapshot = { params: { ...state.params }, at: Date.now() };
  state.params = { ...p.candidate.params };
  state.history.push({
    indexSha: p.indexSha,
    from: p.baseline,
    to: { trainTop1: train, heldoutTop1: held },
    params: { ...state.params },
    at: Date.now(),
  });
  if (state.history.length > ADAPTIVE_LIMITS.maxHistory) {
    state.history = state.history.slice(-ADAPTIVE_LIMITS.maxHistory);
  }
  state.pending = null;
  state.seen = [];
  state.updatedAt = Date.now();
  saveState(dir, state);
  return { ok: true, params: { ...state.params } };
}

export function rejectPending(dir, reason) {
  const { state } = loadState(dir);
  if (!state.pending) return { ok: false, reason: "no-pending" };
  state.pending = null;
  state.updatedAt = Date.now();
  saveState(dir, state);
  return { ok: true, reason: String(reason || "rejected") };
}

// Возврат к параметрам до последнего approve. Предназначен для гейта/аварии.
export function rollback(dir, reason) {
  const { state } = loadState(dir);
  if (!state.snapshot) return { ok: false, reason: "no-snapshot" };
  state.params = { ...state.snapshot.params };
  state.snapshot = null;
  state.pending = null;
  state.history.push({ rollback: String(reason || "manual"), at: Date.now() });
  state.updatedAt = Date.now();
  saveState(dir, state);
  return { ok: true, params: { ...state.params } };
}

export function currentParams(dir) {
  const { state, status } = loadState(dir);
  return { enabled: state.enabled, params: { ...state.params }, status };
}

export function policySnapshot(dir) {
  const { state } = loadState(dir);
  return {
    enabled: state.enabled,
    limits: { ...ADAPTIVE_LIMITS },
    seen: state.seen.length,
    signals: { ...state.signals },
    pending: state.pending,
    history: state.history.slice(-8),
    updatedAt: state.updatedAt,
  };
}

// --- raw_signals: кандидаты в золотой корпус -------------------------------
//
// Хэш эталона нужен рантайму, чтобы запечатать снимок в момент сигнала. Алгоритм
// дублирует tools/gold-corpus.mjs.goldSha намеренно: тот модуль офлайн и в APK
// не входит. Расхождение ловит тест в gold-corpus.test.mjs (сверка двух хэшей
// на одном и том же gold.jsonl).

function canonicalGold(value) {
  if (Array.isArray(value)) return "[" + value.map(canonicalGold).join(",") + "]";
  if (value && typeof value === "object") {
    return "{" + Object.keys(value).sort()
      .map((k) => JSON.stringify(k) + ":" + canonicalGold(value[k])).join(",") + "}";
  }
  return JSON.stringify(value);
}

export function goldSha(gold) {
  return sha256(canonicalGold(Array.isArray(gold) ? gold : []));
}

// Отдельный запечатанный файл рядом с memory-adaptive.json. Так hash основного
// состояния не ломается, VERSION=1 не меняется, старые состояния остаются
// совместимыми. Всё остаётся в app-private filesDir; на GitHub уходит только
// отобранный куратором gold с ID источников.

export const GOLD_SPLIT_SALT = "gold-v1";
export const SPLIT_POLICY = "sha256-bucket-75-v1";
export const SIGNAL_LIMITS = Object.freeze({
  maxCandidates: 200,
  // Успешные запросы помечаются origin:control и нужны куратору как обычный
  // фон: без них эталон вырождается в сборник одних промахов. Но если их не
  // ограничить, они вытеснят промахи из буфера — поэтому отдельный лимит.
  maxControl: 40,
  ttlMs: 7 * 24 * 60 * 60 * 1000,
  curateThreshold: 10,
  batchTarget: 15,
});

const SIGNAL_FILE = "memory-signal-candidates.json";
const SIGNAL_VERSION = 1;

// Нормализация обязана совпадать с tools/gold-corpus.mjs.normalizeQuery.
export function normalizeQuery(query) {
  return String(query == null ? "" : query).trim().toLowerCase().replace(/\s+/g, " ");
}

// Сплит нового ID без обращения к остальной базе. Старые 40 кейсов сюда не
// попадают: их сплит закреплён в gold и backfill'ится один раз офлайн.
export function computeSplit(query, salt = GOLD_SPLIT_SALT) {
  const norm = normalizeQuery(query);
  const bucket = parseInt(sha256(String(salt) + norm).slice(0, 8), 16) % 100;
  return {
    norm,
    norm_hash: sha256(norm),
    bucket,
    split: bucket < 75 ? "train" : "held-out",
    split_policy: SPLIT_POLICY,
    hash_salt: String(salt),
  };
}

function defaultSignalState() {
  return { version: SIGNAL_VERSION, candidates: [], updatedAt: 0, hash: "" };
}

export function signalCandidatesPath(dir) {
  return join(String(dir || process.env.MEMORY_ADAPTIVE_DIR || process.cwd()), SIGNAL_FILE);
}

function saveSignalState(dir, state) {
  const path = signalCandidatesPath(dir);
  const folder = join(path, "..");
  mkdirSync(folder, { recursive: true });
  const sealed = seal(state);
  const tmp = `${path}.tmp-${process.pid}`;
  writeFileSync(tmp, JSON.stringify(sealed, null, 2) + "\n");
  renameSync(tmp, path);
  return sealed;
}

export function loadSignalCandidates(dir) {
  const path = signalCandidatesPath(dir);
  if (!existsSync(path)) return { state: seal(defaultSignalState()), status: "new" };
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return { state: seal(defaultSignalState()), status: "quarantined" };
  }
  if (!parsed || parsed.version !== SIGNAL_VERSION || !Array.isArray(parsed.candidates) ||
      typeof parsed.hash !== "string" || parsed.hash !== sha256(canonical(parsed))) {
    return { state: seal(defaultSignalState()), status: "quarantined" };
  }
  const now = Date.now();
  const kept = parsed.candidates.filter((c) =>
    Number.isFinite(c.ts) && now - c.ts <= SIGNAL_LIMITS.ttlMs);
  if (kept.length !== parsed.candidates.length) {
    const next = saveSignalState(dir, { ...parsed, candidates: kept, updatedAt: now });
    return { state: next, status: "pruned" };
  }
  return { state: parsed, status: "ok" };
}

// Сырой сигнал промаха. expected_chunk_id может быть null — не выводим его
// гаданием, заполняет куратор. origin: miss | control.
export function recordSignalCandidate(dir, cand) {
  const { state } = loadSignalCandidates(dir);
  const query = String(cand?.query == null ? "" : cand.query).trim();
  if (!query) return { ok: false, reason: "bad-query" };
  const split = computeSplit(query, cand?.hash_salt);
  const hash = split.norm_hash;
  const dup = state.candidates.find((c) => c.norm_hash === hash && c.status !== "rejected");
  if (dup) return { ok: false, reason: "duplicate", id: dup.id };

  const now = Date.now();
  let nextNum = 1;
  for (const c of state.candidates) {
    const m = /^s(\d+)$/.exec(String(c.id));
    if (m) nextNum = Math.max(nextNum, Number(m[1]) + 1);
  }
  const id = "s" + String(nextNum).padStart(3, "0");
  const row = {
    id,
    query,
    norm_hash: hash,
    suggested_split: split.split,
    split_policy: split.split_policy,
    hash_salt: split.hash_salt,
    bucket: split.bucket,
    index_sha: String(cand?.index_sha || ""),
    gold_sha: String(cand?.gold_sha || ""),
    candidate_chunk_ids: Array.isArray(cand?.candidate_chunk_ids) ? cand.candidate_chunk_ids.slice(0, 12) : [],
    expected_chunk_id: cand?.expected_chunk_id == null ? null : String(cand.expected_chunk_id),
    expect_source: cand?.expect_source == null ? null : String(cand.expect_source),
    expect_kind: cand?.expect_kind == null ? null : String(cand.expect_kind),
    origin: cand?.origin === "control" ? "control" : "miss",
    status: "raw",
    note: "",
    ts: now,
  };
  // Контрольных сигналов может быть сколь угодно много (каждый удачный
  // запрос), а промахи ценнее. Не даем контролю занять весь буфер.
  if (row.origin === "control") {
    const controls = state.candidates.filter((c) =>
      c.status === "raw" && c.origin === "control").length;
    if (controls >= SIGNAL_LIMITS.maxControl) {
      return { ok: false, reason: "control-quota-full", id: null };
    }
  }
  const candidates = state.candidates.concat([row]);
  let dropped = 0;
  while (candidates.length > SIGNAL_LIMITS.maxCandidates) {
    const i = candidates.findIndex((c) => c.status === "raw");
    candidates.splice(i < 0 ? 0 : i, 1);
    dropped++;
  }
  saveSignalState(dir, { ...state, candidates, updatedAt: now });
  return {
    ok: true,
    id,
    count: candidates.length,
    dropped,
    eligible: candidates.filter((c) => c.status === "raw").length >= SIGNAL_LIMITS.curateThreshold,
  };
}

export function listSignalCandidates(dir, opts = {}) {
  const { state } = loadSignalCandidates(dir);
  const want = opts.status == null ? null : Array.isArray(opts.status) ? opts.status : [opts.status];
  return state.candidates
    .filter((c) => want == null || want.includes(c.status))
    .map((c) => ({ ...c }));
}

const SIGNAL_STATUSES = ["raw", "staging", "alias", "gold", "rejected"];

export function setSignalCandidateStatus(dir, id, status, note) {
  const { state } = loadSignalCandidates(dir);
  if (!SIGNAL_STATUSES.includes(String(status))) return { ok: false, reason: "bad-status" };
  const idx = state.candidates.findIndex((c) => c.id === String(id));
  if (idx < 0) return { ok: false, reason: "not-found" };
  const row = { ...state.candidates[idx], status: String(status), note: String(note || "") };
  const candidates = state.candidates.slice();
  candidates[idx] = row;
  saveSignalState(dir, { ...state, candidates, updatedAt: Date.now() });
  return { ok: true, row };
}

export function pruneSignalCandidates(dir) {
  const { state } = loadSignalCandidates(dir);
  return { ok: true, count: state.candidates.length };
}
