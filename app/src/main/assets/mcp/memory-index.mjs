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
  sha TEXT NOT NULL, created INTEGER NOT NULL,
  ord INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_source ON doc_chunks(source);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_kind ON doc_chunks(kind);
CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(
  chunk_id UNINDEXED, text, tokenize='trigram'
);
CREATE VIRTUAL TABLE IF NOT EXISTS anchor_fts USING fts5(
  chunk_id UNINDEXED, anchors, tokenize='unicode61 remove_diacritics 2'
);
-- v4: тот же набор строк, но токенайзер держит технические идентификаторы
-- (UUID, пакетные имена, пути с точками/дефисами) одним токеном. v3 читает
-- anchor_fts и не меняется; v4-ядро подключается к anchor_fts_v4.
CREATE VIRTUAL TABLE IF NOT EXISTS anchor_fts_v4 USING fts5(
  chunk_id UNINDEXED, anchors, tokenize='unicode61 remove_diacritics 2 tokenchars ''-_.'''
);
`;

// Имя v4-таблицы якорей — единственный контракт, который нужно ядру v4.
export const ANCHOR_FTS_V4 = "anchor_fts_v4";

// v4: сырые технические идентификаторы (org.opencode.mobile.debug, apk-53,
// sha256-строки, пути) не попадают в канонические якоря analyze(). Добавляем
// их ТОЛЬКО в v4-таблицу — v3-колонка anchors остаётся байтно прежней.
export function rawIdentifiers(text, limit = 16) {
  const seen = new Set();
  for (const m of String(text || "").matchAll(/[a-z0-9][a-z0-9_.-]{2,}/gi)) {
    const s = m[0];
    if (/[.-]/.test(s) && /[a-z]/i.test(s) && !seen.has(s.toLowerCase())) seen.add(s.toLowerCase());
  }
  return [...seen].slice(0, limit);
}

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
  // Отчёт разового исследования — справочный артефакт, не рабочее правило.
  // Классифицируем ниже advice, чтобы его массив чанков не сдвигал ord
  // канонических источников в FTS (иначе падал gold recall по якорю advice).
  "ИССЛЕДОВАНИЕ_ПАМЯТИ_АГЕНТОВ.md": "report",
};
export const AUTHORITY = { rule: 1.0, state: 0.95, path: 0.9, catalog: 0.8, journal: 0.7, doc: 0.6, advice: 0.5, report: 0.4 };
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

// Закрытый контейнер источников. Приоритет без конфликтов:
//   opts.whitelist  — явный override для тестов (манифест не читается);
//   opts.manifest===false — явный открытый режим;
//   манифест есть   — индексируем только перечисленные в нём файлы;
//   манифеста нет   — старый обход дерева (обратная совместимость).
// sha256 в манифесте — не «версия контента», а замок: несовпадение исключает
// источник, чтобы правка рабочей памяти не меняла индекс молча.
const MANIFEST_REL = "memory/sources.json";

function readSourceManifest(docsDir, opts) {
  if (opts.whitelist || opts.manifest === false) return null;
  try {
    const m = JSON.parse(readFileSync(join(docsDir, MANIFEST_REL), "utf8"));
    return Array.isArray(m && m.sources) ? m : null;
  } catch { return null; }
}

export function manifestStatus(docsDir) {
  const m = readSourceManifest(docsDir, {});
  if (!m) return { enabled: false, accepted: 0, rejected: [], total: 0 };
  const rejected = [];
  let accepted = 0;
  for (const s of m.sources) {
    const rel = String(s && s.path || "");
    try {
      const full = join(docsDir, rel);
      if (relative(docsDir, full).startsWith("..") || !statSync(full).isFile()) {
        rejected.push(rel);
      } else if (s.sha && sha256(readFileSync(full, "utf8")) !== String(s.sha).toLowerCase()) {
        rejected.push(rel);
      } else accepted++;
    } catch { rejected.push(rel); }
  }
  return { enabled: true, accepted, rejected, total: m.sources.length };
}

// Точка автономного старта: ядро само читает свой контейнер, сверяет замки
// и индексирует только доверенные источники. Без манифеста считаем, что
// контейнер не сформирован (при requireManifest:false — явный открытый режим
// для миграции/тестов, с честным статусом "open").
export function bootContainer(DB, root, opts = {}) {
  const status = manifestStatus(root);
  if (!status.enabled) {
    if (opts.requireManifest === false) {
      const r = indexDocs(DB, root, { ...opts, manifest: false });
      return { status: "open", root, files: r.files, accepted: r.files,
        rejected: [], total: r.files, index_sha: containerSha(DB) };
    }
    return { status: "no-manifest", root, files: 0, ...status, index_sha: containerSha(DB) };
  }
  const r = indexDocs(DB, root, opts);
  return {
    status: status.rejected.length ? "partial" : "ok",
    root, files: r.files, ...status, index_sha: containerSha(DB),
  };
}

export function containerSha(DB) {
  try {
    const rows = DB.prepare("SELECT path, sha FROM doc_sources ORDER BY path").all();
    return createHash("sha256").update(JSON.stringify(rows)).digest("hex").slice(0, 16);
  } catch { return ""; }
}

// Собрать список .md-файлов: корень MOBILE_AGENT + docs/, без tmp/ и архивов/.
export function collectSources(docsDir, opts = {}) {
  const files = [];
  const whitelist = opts.whitelist || null;
  const manifest = readSourceManifest(docsDir, opts);
  if (manifest) {
    const seen = new Set();
    for (const s of manifest.sources) {
      const rel = String(s && s.path || "");
      if (!rel || seen.has(rel)) continue;
      const full = join(docsDir, rel);
      // Запрет выхода за каталог памяти: манифест не может сослаться на /etc.
      if (relative(docsDir, full).startsWith("..")) continue;
      try {
        if (!statSync(full).isFile()) continue;
        if (s.sha && sha256(readFileSync(full, "utf8")) !== String(s.sha).toLowerCase()) continue;
      } catch { continue; }
      seen.add(rel);
      files.push({ full, rel });
    }
  } else {
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
  }
  // Канонический порядок: сначала авторитетные источники, затем путь.
  // ord = fileIdx*100000 + chunkIdx, поэтому порядок обхода не может зависеть
  // от readdirSync — иначе пересборка FTS меняет топ ядра (/apk-53/).
  return files.sort((a, b) =>
    (AUTHORITY[kindOf(b.rel)] ?? 0.6) - (AUTHORITY[kindOf(a.rel)] ?? 0.6) ||
    (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0));
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
  // Старые базы не знают про ord — добавляем колонку точечно, без миграции.
  try { DB.exec("ALTER TABLE doc_chunks ADD COLUMN ord INTEGER NOT NULL DEFAULT 0"); } catch {}
  const now = Date.now();
  let added = 0, updated = 0, skipped = 0, secret = 0;
  const getSource = DB.prepare("SELECT sha, mtime, chunks FROM doc_sources WHERE path = ?");
  const delChunks = DB.prepare("DELETE FROM doc_chunks WHERE source = ?");
  const delFtsDoc = DB.prepare("DELETE FROM doc_fts WHERE chunk_id IN (SELECT id FROM doc_chunks WHERE source = ?)");
  const delFtsAnchor = DB.prepare("DELETE FROM anchor_fts WHERE chunk_id IN (SELECT id FROM doc_chunks WHERE source = ?)");
  // v4 чистится и наполняется тем же набором строк, что и v3, в том же порядке.
  const delFtsAnchorV4 = DB.prepare(`DELETE FROM ${ANCHOR_FTS_V4} WHERE chunk_id IN (SELECT id FROM doc_chunks WHERE source = ?)`);
  const insSource = DB.prepare(`INSERT INTO doc_sources(path, sha, mtime, chunks, kind, indexed_at)
    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET
    sha=excluded.sha, mtime=excluded.mtime, chunks=excluded.chunks, kind=excluded.kind, indexed_at=excluded.indexed_at`);
  const insChunk = DB.prepare(`INSERT INTO doc_chunks(id, source, heading, line_from, line_to, kind, text, trigram, anchors, lemmas, sha, created, ord)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  const insFtsDoc = DB.prepare("INSERT INTO doc_fts(chunk_id, text) VALUES (?, ?)");
  const insFtsAnchor = DB.prepare("INSERT INTO anchor_fts(chunk_id, anchors) VALUES (?, ?)");
  const insFtsAnchorV4 = DB.prepare(`INSERT INTO ${ANCHOR_FTS_V4}(chunk_id, anchors) VALUES (?, ?)`);

  const run = DB.transaction(() => {
    for (let fileIdx = 0; fileIdx < files.length; fileIdx++) {
      const { full, rel } = files[fileIdx];
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
      // created — это возраст конкретного чанка, а не время переиндексации файла.
      // Иначе правка одного абзаца делает «свежими» все чанки документа, и
      // составляющая свежести вытесняет эталон, который вообще не менялся.
      // id чанка включает source, номер строки и sha текста, поэтому у
      // неизменившегося чанка он совпадает — старое время и сохраняем.
      const oldCreated = prev
        ? new Map(
            DB.prepare("SELECT id, created FROM doc_chunks WHERE source = ?")
              .all(rel)
              .map((r) => [r.id, r.created]),
          )
        : new Map();
      if (prev) {
        delFtsDoc.run(rel);
        delFtsAnchor.run(rel);
        delFtsAnchorV4.run(rel);
        delChunks.run(rel);
      }
      let ci = 0;
      for (const c of chunks) {
        const parsed = analyze(c.text);
        const anchors = parsed.anchors.join(" ");
        const lemmas = parsed.lemmas.join(" ");
        const trigram = forTrigram(c.text);
        // ord — позиция чанка в каноническом порядке обхода файлов. Он не
        // меняется при инкрементальных правках: файл всегда занимает одно и
        // то же место в массиве files, поэтому переиндексация одного документа
        // не перемещает его чанки относительно остальных.
        insChunk.run(c.id, c.source, c.heading, c.line_from, c.line_to, c.kind, c.text, trigram, anchors, lemmas, sha, oldCreated.get(c.id) ?? now, fileIdx * 100000 + ci);
        ci++;
      }
      insSource.run(rel, sha, mtime, chunks.length, kind, now);
      if (prev) updated++; else added++;
    }
    // Пересчитать ord для ВСЕХ чанков, включая те, что достались от старой БД
    // с нулевым ord. Порядок файлов берём из того же массива files, который
    // обошёл индексатор, номер чанка — из его id (source:line:idx:sha).
    const fileOrder = new Map(files.map((f, i) => [f.rel, i]));
    const updOrd = DB.prepare("UPDATE doc_chunks SET ord = ? WHERE id = ?");
    for (const c of DB.prepare("SELECT id, source FROM doc_chunks").all()) {
      const fi = fileOrder.get(c.source);
      const ci = Number(String(c.id).split(":")[2]) || 0;
      updOrd.run((fi == null ? 1000000 : fi) * 100000 + ci, c.id);
    }
    // Пересобрать FTS целиком в порядке ord. Иначе удаление и повторная вставка
    // чанков при правке документа меняет rowid в FTS; у частого якоря (`apk`)
    // bm25 почти нулевой во всех строках, и ORDER BY score возвращает их в
    // порядке rowid — эталон выпадал из якорного пула (инцидент g11, apk-53).
    // Теперь порядок строк FTS детерминирован содержимым и не зависит от истории.
    DB.exec("DELETE FROM doc_fts; DELETE FROM anchor_fts; DELETE FROM " + ANCHOR_FTS_V4 + ";");
    const all = DB.prepare("SELECT id, text, anchors FROM doc_chunks ORDER BY ord, id").all();
    for (const c of all) {
      insFtsDoc.run(c.id, c.text);
      insFtsAnchor.run(c.id, c.anchors);
      // v4: канонические якоря + сырые идентификаторы. v3-строка не меняется.
      insFtsAnchorV4.run(c.id, (c.anchors + " " + rawIdentifiers(c.text).join(" ")).trim());
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
