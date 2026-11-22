// Заморозка сплитов и версионирование золотого корпуса.
// Офлайн-модуль: в APK НЕ входит, MCP-контракт и дефолт ядра не трогает.
//
// Главное правило: сплит записывается в строку gold один раз и больше никогда
// не пересчитывается. Первые 40 кейсов backfill'им по старому правилу
// `sha256(query)[0] % 5 === 0` — иначе примеры, на которых подбирались пороги,
// уехали бы в held-out и валидация стала бы враньём. Новые ID получают
// детерминированный бакет от `sha256(salt + norm)`.
//
// Импорты только локальные: без сети, без нативных библиотек.

import { readFileSync, renameSync, writeFileSync, existsSync } from "node:fs";
import { dirname } from "node:path";
import { sha256 } from "../opencode/memory-core/memory-index.mjs";
import { analyze } from "../opencode/memory-core/memory-lemma.js";

export const SPLIT_POLICY = "sha256-bucket-75-v1";
export const LEGACY_POLICY = "legacy-fifth-v1";
export const GOLD_SPLIT_SALT = "gold-v1";
export const DEFAULT_ALIAS_THRESHOLD = 0.75;
export const DEFAULT_MAX_SHARE = 0.30;

// Нормализация обязана совпадать с memory-adaptive-store.normalizeQuery —
// оба модуля считают norm_hash для одного и того же запроса.
export function normalizeQuery(query) {
  return String(query == null ? "" : query).trim().toLowerCase().replace(/\s+/g, " ");
}

// Старое правило сплита. Используется ТОЛЬКО для backfill первых 40 строк.
export function legacySplit(query) {
  return sha256(String(query == null ? "" : query)).charCodeAt(0) % 5 === 0
    ? "held-out" : "train";
}

// Новое правило сплита для новых ID. Соль версионирована и хранится в строке.
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

// Заморозка: если строка уже размечена — не трогаем её. Остальные получают
// сплит по старому правилу ровно один раз.
export function backfillSplits(gold) {
  const rows = Array.isArray(gold) ? gold : [];
  return rows.map((row) => {
    if (row && (row.split === "train" || row.split === "held-out")) return row;
    const split = legacySplit(row && row.query);
    return {
      ...row,
      split,
      split_policy: LEGACY_POLICY,
      hash_salt: null,
    };
  });
}

// Канонический JSON: порядок ключей и пробелы не влияют на хэш.
function canonical(value) {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value && typeof value === "object") {
    return "{" + Object.keys(value).sort()
      .map((k) => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  }
  return JSON.stringify(value);
}

// Хэш самого эталона. Вторая часть пары (index_sha — первая, из memory-core).
export function goldSha(gold) {
  return sha256(canonical(Array.isArray(gold) ? gold : []));
}

export function readGold(path) {
  const text = readFileSync(path, "utf8");
  const rows = [];
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    rows.push(JSON.parse(line));
  }
  return rows;
}

// Атомарная запись: tmp + rename. Получившийся файл либо целиком старый,
// либо целиком новый — никогда не «полузаписанный» эталон.
export function writeGold(path, rows) {
  const tmp = `${path}.tmp-${process.pid}`;
  writeFileSync(tmp, (Array.isArray(rows) ? rows : [])
    .map((r) => JSON.stringify(r)).join("\n") + "\n");
  renameSync(tmp, path);
  return rows;
}

export function appendGold(path, entries) {
  const rows = readGold(path);
  const byId = new Set(rows.map((r) => String(r.id)));
  let next = 1;
  for (const r of rows) {
    const m = /^g(\d+)$/.exec(String(r.id));
    if (m) next = Math.max(next, Number(m[1]) + 1);
  }
  const added = [];
  for (const e of (Array.isArray(entries) ? entries : [])) {
    const id = e.id && !byId.has(String(e.id)) ? String(e.id) : "g" + String(next).padStart(2, "0");
    while (byId.has(id)) { next++; }
    const row = { ...e, id };
    byId.add(id);
    if (/^g(\d+)$/.test(id)) next = Math.max(next, Number(id.slice(1)) + 1);
    rows.push(row);
    added.push(row);
  }
  writeGold(path, rows);
  return { rows, added, gold_sha: goldSha(rows) };
}

// Jaccard по леммам: ловит «температура печи» против «температура печи какая».
// Не ловит синонимы без общих токенов и разные намерения — поэтому это только
// подсказка алиаса, финальное слово за ревью.
export function jaccardLemmas(a, b) {
  const A = new Set(analyze(String(a == null ? "" : a)).lemmas);
  const B = new Set(analyze(String(b == null ? "" : b)).lemmas);
  if (!A.size || !B.size) return 0;
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  const union = A.size + B.size - inter;
  return union ? inter / union : 0;
}

// Jaccard с поправкой на вложенность. «Температура печи» против «температура
// печи какая сейчас» даёт чистый Jaccard 0.667 — ниже порога 0.75, хотя это
// один и тот же кейс. Один и тот же набор лемм, вложенный в более длинный,
// считаем алиасом; вес вложенности снижен, чтобы случайное совпадение одного
// слова не склеило разные намерения («найти память» / «удалить память»).
export function aliasScore(a, b) {
  const A = new Set(analyze(String(a == null ? "" : a)).lemmas);
  const B = new Set(analyze(String(b == null ? "" : b)).lemmas);
  if (A.size < 2 || B.size < 2) return 0;
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  const union = A.size + B.size - inter;
  const jaccard = union ? inter / union : 0;
  const containment = inter / Math.min(A.size, B.size);
  return Math.max(jaccard, containment * 0.8);
}

// Ищем самый близкий существующий вопрос. Алиас наследует сплит родителя и
// НЕ считается независимым кейсом в метриках.
export function findAlias(gold, query, threshold = DEFAULT_ALIAS_THRESHOLD) {
  let best = null;
  for (const row of (Array.isArray(gold) ? gold : [])) {
    const score = aliasScore(query, row && row.query);
    if (!best || score > best.score) {
      best = { id: row && row.id, split: row && row.split, score, row };
    }
  }
  return best && best.score >= threshold ? best : null;
}

// Дедуп буфера: сначала по norm_hash, потом по смыслу внутри самого буфера.
export function dedupCandidates(cands, gold, threshold = DEFAULT_ALIAS_THRESHOLD) {
  const seenHash = new Set((Array.isArray(gold) ? gold : [])
    .map((g) => g && g.norm_hash).filter(Boolean));
  const out = [];
  for (const c of (Array.isArray(cands) ? cands : [])) {
    const hash = c.norm_hash || sha256(normalizeQuery(c.query));
    if (seenHash.has(hash)) {
      out.push({ ...c, dup_reason: "norm_hash", alias_of: c.alias_of || null });
      continue;
    }
    const alias = c.alias_of || findAlias(gold, c.query, threshold);
    const sibling = alias ? null : out.find((o) => !o.dup_reason &&
      aliasScore(o.query, c.query) >= threshold);
    const parent = alias || (sibling ? { id: sibling.id, split: sibling.suggested_split, score: 1 } : null);
    seenHash.add(hash);
    out.push({
      ...c,
      norm_hash: hash,
      alias_of: parent ? parent.id : null,
      alias_split: parent ? parent.split : null,
      alias_score: parent ? parent.score : 0,
      dup_reason: parent ? "semantic" : null,
    });
  }
  return out;
}

// Квота на источник за батч, отдельно для train и held-out. Переполнение не
// выбрасываем — оно уходит в pending с приоритетом на следующий проход.
export function quotaSelect(cands, opts = {}) {
  const target = Number.isFinite(opts.target) ? Math.max(1, Math.floor(opts.target)) : 15;
  const maxShare = Number.isFinite(opts.maxShare) ? opts.maxShare : DEFAULT_MAX_SHARE;
  const cap = Math.max(1, Math.floor(target * maxShare));
  const eligible = (Array.isArray(cands) ? cands : [])
    .filter((c) => c && !c.alias_of && c.status !== "rejected");
  const groups = new Map();
  for (const c of eligible) {
    const key = String(c.suggested_split || c.split || "train") + "|" + String(c.expect_source || "unknown");
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(c);
  }
  const selected = [], pending = [];
  for (const [key, list] of groups) {
    const [split, source] = key.split("|");
    list.forEach((c, i) => {
      const row = { ...c, expect_source: c.expect_source || source, suggested_split: split };
      if (i < cap) selected.push(row);
      else pending.push({ ...row, quota_skipped: true });
    });
  }
  selected.sort((a, b) => String(a.id).localeCompare(String(b.id)));
  return { selected: selected.slice(0, target), pending: pending.concat(selected.slice(target)), cap };
}
