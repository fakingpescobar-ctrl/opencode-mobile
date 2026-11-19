// Индексатор markdown-памяти мобайл-агента в SQLite FTS5.
// Работает на встроенном musl-Bun (bun:sqlite), без внешних зависимостей.
// Экспортируемые функции используются и самостоятельно (тесты/бенч),
// и из memory.js через import при встраивании ядра в APK.

import { createHash } from "crypto";
import { readFileSync, statSync, readdirSync } from "fs";
import { join, relative, basename } from "path";
import { analyze, forTrigram } from "./memory-lemma.js";

export const DOC_SCHEMA = `
CREATE TABLE IF NOT EXISTS doc_sources (
  path TEXT PRIMARY KEY, sha TEXT NOT NULL, mtime INTEGER NOT NULL,
  chunks INTEGER NOT NULL, kind TEXT NOT NULL, indexed_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS doc_chunks (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, heading TEXT NOT NULL,
  line_from INTEGER NOT NULL, line_to INTEGER NOT NULL, kind TEXT NOT NULL,
  text TEXT NOT NULL, trigram TEXT NOT NULL, anchors TEXT NOT NULL,
  lemmas TEXT NOT NULL DEFAULT '',
  sha TEXT NOT NULL, created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_source ON doc_chunks(source);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_kind ON doc_chunks(kind);
CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(
  chunk_id UNINDEXED, text, tokenize='trigram'
);
CREATE VIRTUAL TABLE IF NOT EXISTS anchor_fts USING fts5(
  chunk_id UNINDEXED, anchors, tokenize='unicode61 remove_diacritics 2'
);
`;

// Классификацияkind по имени файла — вес авторитетности берётся отсюда же.
export const KIND_BY_FILE = {
  "ПРАВИЛА.md": "rule",
  "СОСТОЯНИЕ.md": "state",
  "ЖУРНАЛ.md": "journal",
  "ХАРНЕС.md": "path",
  "АРХИТЕКТУРА_ПАМЯТИ.md": "rule",
  "КАТАЛОГ_ПЕРЕДЕЛОК.md": "catalog",
  "АРХИВ_СОСТОЯНИЯ.md": "journal",
  "agent-rules.md": "rule",
  "MOLTBOOK_ADVICE.md": "advice",
};
export const AUTHORITY = { rule: 1.0, state: 0.95, path: 0.9, catalog: 0.8, journal: 0.7, advice: 0.5, doc: 0.6 };
export function kindOf(relPath) {
  const base = basename(relPath);
  return KIND_BY_FILE[base] || "doc";
}

// Ищем только РЕАЛЬНЫЕ значения секретов, а не упоминания слов «ghtoken»:
// в правилах эти слова встречаются как текст (путь к файлу токена), и если
// отфильтровать по самому слову — потеряем большую часть рабочей памяти.
const SECRET_PATTERNS = [
  /\bghp_[A-Za-z0-9]{20,}/i,                 // GitHub personal access token
  /\bgithub_pat_[A-Za-z0-9_]{30,}/i,          // GitHub fine-grained token
  /-----BEGIN [A-Z ]*PRIVATE KEY/i,           // приватный ключ
  /(?:authorization\s*[:=]\s*)?Bearer\s+[A-Za-z0-9._-]{20,}/i, // Bearer + значение
  /\b(?:token|secret|password|pars?k)\s*[:=]\s*['\"]?[A-Za-z0-9._-]{24,}['\"]?/i,
];
export function looksSecret(text) {
  return SECRET_PATTERNS.some((re) => re.test(text));
}

export function sha256(text) {
  return createHash("sha256").update(text).digest("hex");
}

// Чанкование: режем по заголовкам, затем длинные секции — на куски ≤ maxLen
// с перекрытием overlap (чтобы фраза на стыке не потерялась).
export function chunkMarkdown(text, source, kind, maxLen = 800, overlap = 80) {
  const lines = String(text).split("\n");
  const sections = [];
  let heading = "";
  let buf = [];
  let start = 1;
  const flush = (endLine) => {
    const body = buf.join("\n").trim();
    if (body) sections.push({ heading, body, line_from: start, line_to: endLine });
    buf = [];
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const m = /^(#{1,6})\s+(.*)$/.exec(line);
    if (m) {
      flush(i); // предыдущая секция заканчивается перед новым заголовком
      heading = m[2].trim();
      start = i + 1;
      buf = [line];
    } else {
      if (buf.length === 0) start = i + 1;
      buf.push(line);
    }
  }
  flush(lines.length);

  const out = [];
  for (const sec of sections) {
    const body = sec.body;
    if (body.length <= maxLen) {
      out.push({ ...sec, text: body });
      continue;
    }
    let pos = 0;
    let part = 0;
    while (pos < body.length) {
      const end = Math.min(pos + maxLen, body.length);
      let slice = body.slice(pos, end);
      // Не рвём последнюю строку без необходимости: откатываемся к переводу строки.
      if (end < body.length) {
        const cut = slice.lastIndexOf("\n");
        if (cut > maxLen * 0.6) {
          slice = slice.slice(0, cut);
        }
      }
      const from = sec.line_from + Math.floor((pos / body.length) * (sec.line_to - sec.line_from));
      const to = sec.line_from + Math.floor((Math.min(end, body.length) / body.length) * (sec.line_to - sec.line_from));
      out.push({
        heading: part === 0 ? sec.heading : `${sec.heading} (${part + 1})`,
        body: slice,
        text: slice,
        line_from: from,
        line_to: Math.max(from, to),
      });
      if (end >= body.length) break;
      pos = Math.max(pos + 1, end - overlap);
      part++;
    }
  }
  return out.map((c, idx) => ({
    ...c,
    id: `${source}:${c.line_from}:${idx}:${sha256(c.text).slice(0, 12)}`,
    source,
    kind,
  }));
}

// Собрать список .md-файлов: корень MOBILE_AGENT + docs/, без tmp/ и архивов/.
export function collectSources(docsDir, opts = {}) {
  const files = [];
  const whitelist = opts.whitelist || null;
  const walk = (dir, prefix = "") => {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      if (ent.name === "tmp" || ent.name === "архивы" || ent.name === "opencode" || ent.name === "tools") continue;
      if (ent.name.startsWith(".")) continue;
      const full = join(dir, ent.name);
      const rel = prefix ? `${prefix}/${ent.name}` : ent.name;
      if (ent.isDirectory()) {
        if (prefix === "" && ent.name !== "docs") continue; // только docs/ вложенностью
        walk(full, rel);
      } else if (ent.isFile() && ent.name.endsWith(".md")) {
        if (whitelist && !whitelist.includes(rel)) continue;
        files.push({ full, rel });
      }
    }
  };
  walk(docsDir);
  return files;
}

function statsOf(DB) {
  const r = DB.prepare("SELECT count(*) n FROM doc_chunks").get();
  const s = DB.prepare("SELECT count(*) n FROM doc_sources").get();
  return { chunks: r?.n || 0, sources: s?.n || 0 };
}

// Проиндексировать список файлов [{full, rel}]. Возвращает статистику.
// Инкрементально: если sha и mtime совпадают с записанными — файл пропускается.
export function indexFiles(DB, files, opts = {}) {
  DB.exec(DOC_SCHEMA);
  const now = Date.now();
  let added = 0, updated = 0, skipped = 0, secret = 0;
  const getSource = DB.prepare("SELECT sha, mtime, chunks FROM doc_sources WHERE path = ?");
  const delChunks = DB.prepare("DELETE FROM doc_chunks WHERE source = ?");
  const delFtsDoc = DB.prepare("DELETE FROM doc_fts WHERE chunk_id IN (SELECT id FROM doc_chunks WHERE source = ?)");
  const delFtsAnchor = DB.prepare("DELETE FROM anchor_fts WHERE chunk_id IN (SELECT id FROM doc_chunks WHERE source = ?)");
  const insSource = DB.prepare(`INSERT INTO doc_sources(path, sha, mtime, chunks, kind, indexed_at)
    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET
    sha=excluded.sha, mtime=excluded.mtime, chunks=excluded.chunks, kind=excluded.kind, indexed_at=excluded.indexed_at`);
  const insChunk = DB.prepare(`INSERT INTO doc_chunks(id, source, heading, line_from, line_to, kind, text, trigram, anchors, lemmas, sha, created)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  const insFtsDoc = DB.prepare("INSERT INTO doc_fts(chunk_id, text) VALUES (?, ?)");
  const insFtsAnchor = DB.prepare("INSERT INTO anchor_fts(chunk_id, anchors) VALUES (?, ?)");

  const run = DB.transaction(() => {
    for (const { full, rel } of files) {
      let text, mtime;
      try {
        text = readFileSync(full, "utf8");
        mtime = Math.floor(statSync(full).mtimeMs);
      } catch (e) {
        continue;
      }
      const sha = sha256(text);
      const prev = getSource.get(rel);
      if (prev && prev.sha === sha && prev.mtime === mtime) { skipped++; continue; }
      if (looksSecret(text)) { secret++; continue; }
      const kind = kindOf(rel);
      const chunks = chunkMarkdown(text, rel, kind, opts.maxLen || 800, opts.overlap || 80);
      if (prev) {
        delFtsDoc.run(rel);
        delFtsAnchor.run(rel);
        delChunks.run(rel);
      }
      for (const c of chunks) {
        const parsed = analyze(c.text);
        const anchors = parsed.anchors.join(" ");
        const lemmas = parsed.lemmas.join(" ");
        const trigram = forTrigram(c.text);
        insChunk.run(c.id, c.source, c.heading, c.line_from, c.line_to, c.kind, c.text, trigram, anchors, lemmas, sha, now);
        insFtsDoc.run(c.id, c.text);
        insFtsAnchor.run(c.id, anchors);
      }
      insSource.run(rel, sha, mtime, chunks.length, kind, now);
      if (prev) updated++; else added++;
    }
  });
  run();
  return { added, updated, skipped, secret, ...statsOf(DB), at: now };
}

// Удобная обёртка: индексировать всю рабочую память.
export function indexDocs(DB, docsDir, opts = {}) {
  const files = collectSources(docsDir, opts);
  return { files: files.length, ...indexFiles(DB, files, opts) };
}
