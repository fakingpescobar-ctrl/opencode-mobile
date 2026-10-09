#!/usr/bin/env bun
// moltbook.js - MCP-сервер плагина Moltbook для мобильного агента.
//
// Зачем он нужен
// --------------
// До этого плагина сайт обходил Kotlin-тикер: AlarmManager -> MoltbookAlarmReceiver ->
// MoltbookTicker -> MoltbookClient. Логика жила в приложении (social/, 6640 строк), агент
// про неё ничего не знал, а отчёт для ПК-агента писался вручную.
//
// Теперь тик - это будильник, который будит АГЕНТА. Агент сам берёт инструмент, сам
// решает, что ответить, и сам вызывает отчёт. Значит весь доступ к moltbook.com должен
// быть инструментами, а не кодом приложения.
//
// Этот файл - сервер MCP, который отдаёт эти инструменты. Он запускается как отдельный
// процесс musl-Bun (local stdio MCP на этой сборке opencode не работает: дети наследуют
// stdin/stdout serve, и JSON-RPC уходит не туда), слушает 127.0.0.1:<порт> и регистрируется
// в opencode.jsonc как remote MCP - ровно как память и Яндекс.Музыка.
//
// Транспорт
// ---------
// Streamable HTTP MCP: GET /mcp - SSE-поток, POST /mcp - JSON-RPC (объект или пачка),
// DELETE /mcp - разрывает сессию. Bearer обязателен: локальный порт без авторизации -
// это открытая публикация инструмента, который пишет на чужой сайт.
//
// Честно о состоянии
// -----------------
// Сейчас здесь ОДИН инструмент - moltbook_status. Он ничего не публикует и не ходит на
// сайт: только читает журнал. Инструменты scan/publish/verify/report/karma добавляются
// следующими шагами, и объявлять их раньше, чем они работают, нельзя: модель получила бы
// шесть инструментов, которые гарантированно ничего не делают.
//
// Инварианты, которые нельзя потерять при переносе (см. docs/moltbook-legacy.md)
// ---------------------------------------------------------------------------
//   1. Ответ, который мы уже опубликовали и который verified, нельзя удалять. Случалось:
//      purgeDeadPending снёс наш живой verified-коммент 0a359f36.
//   2. already_existed - это отдельный исход (reused), а не успех. Ноль новых
//      подтверждённых фактов.
//   3. Дедуп на стороне moltbook - по тексту и автору БЕЗ родителя. Ответ в тред B может
//      вернуть наш же id из треда A. Совпадение родителя ничего не доказывает.
//   4. Чтение треда — не ответ. read-by-post имеет смысл только после настоящего ответа
//      в этот тред.
//   5. Счётчик - проекция журнала, а не счётчик в коде.
//   6. Свидетель, который не умеет отказать, - это самоотчёт.

import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/** Короткий устойчивый хэш для имени файла архива. Не безопасность, а имя. */
export function simpleHash(text) {
  let hash = 5381;
  for (let i = 0; i < text.length; i++) hash = ((hash << 5) + hash + text.charCodeAt(i)) | 0;
  return (hash >>> 0).toString(16).padStart(8, "0");
}

// ---- Настройки из окружения ---------------------------------------------------
//
// Пути приходят из Kotlin (OpencodeRuntime.startMoltbookServer), чтобы скрипт ничего не
// угадывал о раскладке приватных файлов приложения.
export const MOLTBOOK_DIR = process.env.MCP_MOLTBOOK_DIR || "";
export const MOLTBOOK_PORT = Number(process.env.MCP_MOLTBOOK_PORT || 0);
export const MOLTBOOK_TOKEN = process.env.MCP_MOLTBOOK_TOKEN || "";
export const MOLTBOOK_KEY_FILE = process.env.MCP_MOLTBOOK_KEY_FILE || "";
export const MOLTBOOK_NAME = "opencodekz";
export const MOLTBOOK_HOST = "https://www.moltbook.com";
export const LEDGER_NAME = "moltbook.db";
export const DATABASES_DIR_NAME = "databases";
/**
 * База живёт не рядом с логами, а там, где её создал SQLiteOpenHelper. Путь приходит
 * готовым из приложения: выводить его от каталога логов значило бы угадывать уровень
 * вверх, и одна ошибка вверх выглядит как «плагин никогда не работает».
 */
export const LEDGER_DB_DIR = process.env.MCP_MOLTBOOK_DB_DIR || "";
export const LEDGER_CANDIDATES = LEDGER_DB_DIR
  ? [join(LEDGER_DB_DIR, LEDGER_NAME), join(MOLTBOOK_DIR, LEDGER_NAME)]
  : [];
export const LEDGER_PATH = LEDGER_CANDIDATES[0] || "";
export const WITNESS_NAME = "witness.log";
export const VERIFIER_NAME = "verifier.log";
export const RAW_DIR_NAME = "raw";

/** Статусы комментария в журнале - ровно как enum CommentStatus в MoltbookLedger.kt. */
export const COMMENT_STATUS = {
  NEW: "new",
  POSTED: "posted",
  SKIPPED: "skipped",
  FAILED: "failed",
};

/**
 * Слова, которые приходят от МОЛТБУКА в verification_status. Это не наши статусы
 * журнала: «ещё проверяется» и «отклонён» - разные исходы, и подменять одно другим
 * нельзя (см. MoltbookClient.STATUS_PENDING / STATUS_FAILED).
 */
export const SERVER_STATUS = {
  VERIFIED: "verified",
  PENDING: "pending",
  FAILED: "failed",
};

/**
 * Исходы ответа - ровно как enum ReplyOutcome: created/verified/reused плюс пустая
 * строка для строк, записанных до пятой версии схемы.
 *
 * Пустая строка означает «не знаю» и намеренно не попадает в пересчёт: счётчик должен
 * быть консервативным, а не оптимистичным.
 */
export const REPLY_OUTCOME = {
  CREATED: "created",
  VERIFIED: "verified",
  REUSED: "reused",
  UNKNOWN: "",
};

/**
 * Считает ответы, которые можно честно показать в отчёте.
 *
 * created и verified - это появление нового комментария. reused не считается: нового
 * факта не появилось, это наш же старый ответ, найденный дедупом молтбука. Строка с
 * пустым исходом (до v5 схемы) тоже не считается - «не знаю» это не «да».
 *
 * Сделано пересчётом по журналу, а не накоплением счётчика в коде: забытый `++` молча
 * менял цифру в отчёте и нигде не падал.
 */
export function countReportable(rows) {
  const counted = rows.filter(
    (row) =>
      row.status === COMMENT_STATUS.POSTED &&
      (row.reply_outcome === REPLY_OUTCOME.CREATED || row.reply_outcome === REPLY_OUTCOME.VERIFIED),
  );
  const verified = counted.filter((row) => row.reply_outcome === REPLY_OUTCOME.VERIFIED);
  const reused = rows.filter((row) => row.reply_outcome === REPLY_OUTCOME.REUSED);
  const unknown = rows.filter((row) => row.reply_outcome === REPLY_OUTCOME.UNKNOWN);
  return {
    reportable: counted.length,
    verified: verified.length,
    reused: reused.length,
    unknown: unknown.length,
  };
}

/**
 * Сводит строки журнала в сводку для инструмента и отчёта.
 *
 * Отдельная чистая функция, чтобы считать можно было без SQLite - тогда это проверяется
 * обычным тестом, а не только на устройстве.
 */
export function summarizeComments(rows) {
  const byStatus = {};
  for (const row of rows) {
    const key = row.status || COMMENT_STATUS.NEW;
    byStatus[key] = (byStatus[key] || 0) + 1;
  }
  return Object.assign({ total: rows.length }, byStatus, countReportable(rows));
}

/** Статус комментария, который мы считаем опубликованным - тот же, что в Kotlin. */
export function isPublishedStatus(status) {
  const value = String(status || "").trim().toLowerCase();
  return value !== SERVER_STATUS.PENDING && value !== SERVER_STATUS.FAILED;
}

/**
 * Режим открытия журнала.
 *
 * `readonly: false` - это НЕ противоположность `readonly: true`: в bun:sqlite режим
 * задаётся явно, и без него open падает с SQLITE_MISUSE "bad parameter or other API
 * misuse". Замерено на устройстве: readonly:true - ок, readonly:false - падает,
 * readwrite:true - ок, {} - падает. Поэтому чтение и запись - это два разных
 * объекта, а не один флаг.
 */
export function ledgerOpenOptions(readonly) {
  return readonly ? { readonly: true } : { readwrite: true, create: false };
}

/**
 * Имя автора. Сервер отдаёт author объектом `{name: "..."}`, а иногда строкой,
 * и `String(объект)` даёт "[object Object]" - такой автор не совпадёт с нашим именем
 * и мы решим, что ветку никто не закрывал, хотя ответ там есть.
 */
export function authorName(value) {
  if (typeof value === "string") return value.trim();
  if (value && typeof value === "object") {
    const name = value.name || value.username || value.display_name;
    if (typeof name === "string") return name.trim();
  }
  return "";
}

/** Открывает журнал. readonly=true - только чтение, иначе можно испортить журнал. */
function openLedger(readonly) {
  const path = LEDGER_CANDIDATES.find((candidate) => existsSync(candidate)) || "";
  if (!path) return { db: null, path: "" };
  const { Database } = require("bun:sqlite");
  return { db: new Database(path, ledgerOpenOptions(readonly)), path };
}

/**
 * Разбирает ленту в список постов. Чистая функция: проверяется обычным тестом.
 *
 * Молтбук отдаёт `{"success":true,"posts":[...]}`. Отсутствие поля posts - это не пустая
 * лента, а непонятный ответ, и путать их нельзя: «прочитали ноль постов» и «лента не
 * пришла» выглядят снаружи одинаково, а означают противоположное.
 */
export function parseFeedPosts(raw) {
  let parsed = null;
  try {
    parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch (error) {
    return { ok: false, reason: "feed-not-json", posts: [] };
  }
  if (!parsed || typeof parsed !== "object" || !Array.isArray(parsed.posts)) {
    return { ok: false, reason: "feed-has-no-posts", posts: [] };
  }
  const posts = [];
  for (const item of parsed.posts) {
    if (!item || typeof item !== "object") continue;
    const id = String(item.id || "").trim();
    if (!id) continue;
    posts.push({
      id,
      title: String(item.title || ""),
      author: authorName(item.author_name || item.author),
      comments_total: Number(item.comment_count || item.comments_count || 0),
      upvotes: Number(item.upvotes || item.score || 0),
      created_at: Number(item.created_at || 0),
      repost_of: item.repost_of ? String(item.repost_of) : null,
    });
  }
  return { ok: true, reason: null, posts };
}

/**
 * Разбирает комментарии треда. Чистая функция.
 *
 * `parent_id` - то, на что отвечают. `verification_status` - факт публикации, а не
 * «id существует»: уже случалось, что success:true и already_existed:true приехали
 * одним ответом, а комментарий так и остался pending.
 */
export function parseComments(raw) {
  let parsed = null;
  try {
    parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch (error) {
    return { ok: false, reason: "comments-not-json", comments: [] };
  }
  if (!parsed || typeof parsed !== "object" || !Array.isArray(parsed.comments)) {
    return { ok: false, reason: "comments-have-no-list", comments: [] };
  }
  const comments = [];
  for (const item of parsed.comments) {
    if (!item || typeof item !== "object") continue;
    const id = String(item.id || "").trim();
    if (!id) continue;
    comments.push({
      id,
      post_id: String(item.post_id || ""),
      author: authorName(item.author_name || item.author),
      body: String(item.body || item.content || ""),
      parent_id: item.parent_id ? String(item.parent_id) : null,
      created_at: Number(item.created_at || 0),
      verification_status: String(item.verification_status || ""),
    });
  }
  return { ok: true, reason: null, comments };
}

/**
 * Кому в этом треде можно ответить. Чистая функция, зеркалит deservesReply в Kotlin.
 *
 * Отвечаем на верхнеуровневые комментарии чужих авторов, на которые мы ещё не ответили.
 * Уже отвеченные отсекаются по факту: наш опубликованный комментарий с parent_id этого
 * комментария. Не по флажку в нашем коде, а по прочитанному треду - иначе «мы ответили»
 * держилось бы на памяти плагина и терялось при переустановке.
 */
export function pickCandidates(comments, ourName) {
  const ours = String(ourName || "").trim().toLowerCase();
  const mine = (author) => String(author || "").trim().toLowerCase() === ours;
  const answered = new Set();
  for (const comment of comments) {
    if (mine(comment.author) && isPublishedStatus(comment.verification_status) && comment.parent_id) {
      answered.add(comment.parent_id);
    }
  }
  const candidates = [];
  for (const comment of comments) {
    if (mine(comment.author)) continue;
    if (comment.parent_id) continue;
    if (answered.has(comment.id)) continue;
    candidates.push(comment);
  }
  return { candidates, answeredCount: answered.size };
}

/** Кладёт сырой ответ в архив. Тот же формат, что у MoltbookRawArchive: <мс>-<хэш>.json. */
export function archiveRaw(dir, at, path, status, body) {
  if (!dir) return null;
  try {
    const name = String(at) + "-" + simpleHash(path + "|" + String(body)) + ".json";
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, name), JSON.stringify({ method: "GET", path, status, at, body }));
    return name;
  } catch (error) {
    return null;
  }
}

/** Читает журнал. Ни одного сетевого запроса - только то, что уже записано. */
function readLedger() {
  const path = LEDGER_CANDIDATES.find((candidate) => existsSync(candidate)) || "";
  if (!path) {
    return { ok: false, reason: "ledger-missing", path: LEDGER_PATH || null };
  }
  let db = null;
  try {
    // bun:sqlite тянется лениво и внутри функции: на верхнем уровне импорт ломал бы
    // обычный node-тест этого файла, который проверяет чистые функции без SQLite.
    const { Database } = require("bun:sqlite");
    // Отдельный режим только для чтения: «посмотреть статус» не может испортить журнал.
    db = new Database(path, ledgerOpenOptions(true));
    const hasOutcome = db
      .query("SELECT COUNT(*) AS n FROM pragma_table_info('comments') WHERE name = 'reply_outcome'")
      .get();
    const columns = hasOutcome && hasOutcome.n > 0 ? "reply_outcome" : "''";
    const rows = db
      .query(`SELECT status, ${columns} AS reply_outcome FROM comments ORDER BY created_at ASC`)
      .all();
    const version = db.prepare("PRAGMA user_version").get();
    db.close();
    db = null;
    return {
      ok: true,
      path,
      bytes: statSync(path).size,
      schemaVersion: version ? version.user_version : 0,
      hasReplyOutcome: columns !== "''",
      comments: summarizeComments(rows),
    };
  } catch (error) {
    return { ok: false, reason: String((error && error.message) || error), path };
  } finally {
    if (db) {
      try {
        db.close();
      } catch (_) {
        // закрытие не удалось - ничего не теряем, файл только читался
      }
    }
  }
}

/** Наш инструмент: что плагин знает, ничего не меняя и никуда не ходя. */
export function moltbookStatus() {
  const ledger = readLedger();
  return {
    plugin: "moltbook",
    network: "not-used-yet",
    keyPresent: Boolean(MOLTBOOK_KEY_FILE) && existsSync(MOLTBOOK_KEY_FILE),
    ledger,
  };
}

/** Ключ молтбука. Отсутствие ключа - это отказ, а не «нулевой результат». */
export function readMoltbookKey() {
  if (!MOLTBOOK_KEY_FILE || !existsSync(MOLTBOOK_KEY_FILE)) return null;
  try {
    const key = readFileSync(MOLTBOOK_KEY_FILE, "utf8").trim();
    return key || null;
  } catch (error) {
    return null;
  }
}

/**
 * Один GET к молтбуку с архивированием сырого ответа.
 *
 * Сырой ответ пишется ДО разбора и разбирается из той же строки, что ушла в архив.
 * Иначе отчёт и архив могут описывать разные ответы, а расхождение обнаружится
 * только через месяц.
 */
export async function fetchRaw(key, path) {
  const at = Date.now();
  const url = MOLTBOOK_HOST + path;
  try {
    const response = await fetch(url, { headers: { Authorization: "Bearer " + key } });
    const body = await response.text();
    archiveRaw(MOLTBOOK_DIR ? join(MOLTBOOK_DIR, RAW_DIR_NAME) : "", at, path, response.status, body);
    return { ok: response.ok, status: response.status, path, at, body };
  } catch (error) {
    archiveRaw(MOLTBOOK_DIR ? join(MOLTBOOK_DIR, RAW_DIR_NAME) : "", at, path, 0, String(error));
    return { ok: false, status: 0, path, at, body: "", error: String((error && error.message) || error) };
  }
}

/**
 * Записывает найденное в журнал.
 *
 * Граница, которую здесь нельзя пересекать: posts - кэш обнаружения, comments - журнал.
 * Пост можно переписать без потерь (он не помнит ответов), а у комментария трогаются
 * ТОЛЬКО новые строки: существующей мы меняем лишь текст и время просмотра, но НИКОГДА
 * status, our_reply_id, replied_at и reply_outcome. Иначе скан, который ничего не
 * публиковал, способен был бы обнулить запись о нашем же ответе.
 */
export function writeScan(db, posts, comments, now) {
  if (!db) return { postsWritten: 0, commentsWritten: 0, refused: "ledger-missing" };
  let postsWritten = 0;
  let commentsWritten = 0;
  const upsertPost = db.prepare(
    "INSERT OR REPLACE INTO posts (id,title,author,ours,repost_of,comments_total,upvotes,seen_at,updated_at)" +
      " VALUES (?,?,?,?,?,?,?,?,?)",
  );
  const insertComment = db.prepare(
    "INSERT OR IGNORE INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
      " VALUES (?,?,?,?,?,?,NULL,'',?,0,'')",
  );
  const touchComment = db.prepare("UPDATE comments SET body=?, seen_at=? WHERE id=? AND status != 'posted'");
  // Явные BEGIN/COMMIT, а не db.transaction(): этот файл проверяется обычным node,
  // где движок - node:sqlite, а у него нет .transaction(). Транзакция всё равно одна.
  db.exec("BEGIN");
  try {
    for (const post of posts) {
      upsertPost.run(
        post.id,
        post.title,
        post.author,
        post.author.trim().toLowerCase() === MOLTBOOK_NAME ? 1 : 0,
        post.repost_of,
        post.comments_total,
        post.upvotes,
        now,
        now,
      );
      postsWritten++;
    }
    for (const comment of comments) {
      if (!comment.post_id) continue;
      const result = insertComment.run(
        comment.id,
        comment.post_id,
        comment.author,
        comment.body,
        comment.created_at,
        COMMENT_STATUS.NEW,
        now,
      );
      if (result.changes > 0) {
        commentsWritten++;
      } else {
        touchComment.run(comment.body, now, comment.id);
      }
    }
    db.exec("COMMIT");
  } catch (error) {
    try {
      db.exec("ROLLBACK");
    } catch (_) {
      // откат не удался - транзакция всё равно не применена частично, это причина тревоги
    }
    throw error;
  }
  return { postsWritten, commentsWritten, refused: null };
}

/** Сколько постов и сколько тредов смотреть за один вызов. */
export const SCAN_POST_LIMIT = 6;
export const SCAN_THREAD_LIMIT = 2;

/**
 * Наш инструмент: сходить на сайт и записать, что мы увидели. Ничего не публикует.
 *
 * Решения не принимает: отдаёт кандидатов данными, а «кому и что ответить» решает
 * агент. Публикация - отдельный инструмент с отдельным подтверждением.
 */
export async function moltbookScan() {
  const now = Date.now();
  const refusals = [];
  const requests = [];
  const key = readMoltbookKey();
  if (!key) {
    return {
      plugin: "moltbook",
      at: now,
      refused: "no-api-key",
      reason: "Файл с ключом молтбука не найден или пуст. Публиковать и ходить на сайт нечем.",
      candidates: [],
      requests,
      refusals,
    };
  }
  const home = await fetchRaw(key, "/api/v1/home");
  requests.push({ path: home.path, status: home.status, ok: home.ok });
  const feed = await fetchRaw(key, "/api/v1/feed?sort=new");
  requests.push({ path: feed.path, status: feed.status, ok: feed.ok });
  if (!feed.ok) refusals.push("feed-unreadable-" + feed.status);

  const parsedFeed = parseFeedPosts(feed.body);
  if (!parsedFeed.ok) refusals.push(parsedFeed.reason);
  const posts = parsedFeed.posts.slice(0, SCAN_POST_LIMIT);

  const allComments = [];
  const threadsRead = [];
  for (const post of posts.slice(0, SCAN_THREAD_LIMIT)) {
    const raw = await fetchRaw(key, "/api/v1/posts/" + post.id + "/comments?sort=new&limit=100");
    requests.push({ path: raw.path, status: raw.status, ok: raw.ok });
    const parsed = parseComments(raw.body);
    if (!parsed.ok) {
      refusals.push("thread-" + post.id + "-" + parsed.reason);
      continue;
    }
    threadsRead.push(post.id);
    for (const comment of parsed.comments) {
      if (!comment.post_id) comment.post_id = post.id;
      allComments.push(comment);
    }
  }

  const written = (function () {
    let opened = null;
    try {
      opened = openLedger(false);
      if (!opened.db) return { postsWritten: 0, commentsWritten: 0, refused: "ledger-missing" };
      const result = writeScan(opened.db, posts, allComments, now);
      opened.db.close();
      return result;
    } catch (error) {
      if (opened && opened.db) {
        try {
          opened.db.close();
        } catch (_) {
          // закрытие не удалось - транзакция всё равно не была применена
        }
      }
      return { postsWritten: 0, commentsWritten: 0, refused: String((error && error.message) || error) };
    }
  })();
  if (written.refused) refusals.push("ledger-" + written.refused);

  const seenByPost = {};
  for (const post of posts) seenByPost[post.id] = true;
  const fresh = allComments.filter((c) => seenByPost[c.post_id]);
  const picked = pickCandidates(fresh, MOLTBOOK_NAME);

  let karma = null;
  let unread = null;
  try {
    const homeJson = JSON.parse(home.body || "{}");
    if (homeJson && homeJson.your_account) {
      karma = homeJson.your_account.karma;
      unread = homeJson.your_account.unread_notification_count;
    }
  } catch (error) {
    refusals.push("home-not-json");
  }

  return {
    plugin: "moltbook",
    at: now,
    network: { karma, unread, postsSeen: posts.length, threadsRead },
    ledger: {
      ok: !written.refused,
      postsWritten: written.postsWritten,
      commentsWritten: written.commentsWritten,
    },
    candidates: picked.candidates.map((c) => ({
      post_id: c.post_id,
      comment_id: c.id,
      parent_id: null,
      author: c.author,
      body: c.body.slice(0, 400),
    })),
    answerable: picked.candidates.length,
    refusals,
    requests,
  };
}

const TOOLS = [
  {
    name: "moltbook_status",
    description:
      "Состояние плагина Moltbook: лежит ли журнал, сколько в нём комментариев по статусам, " +
      "сколько ответов можно честно показать в отчёте (created+verified), сколько было reused. " +
      "Ничего не публикует и не ходит на сайт - только читает уже записанный журнал. " +
      "Вызывай это первым, когда хочешь понять, что плагин видит.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "moltbook_scan",
    description:
      "Сходить на moltbook.com, записать увиденное в журнал и вернуть список комментариев, " +
      "на которые ещё никто не отвечал. Ничего не публикует и ничего не удаляет. " +
      "Поля candidates - это данные, а не решение: что и кому ответить, решаешь ты сам. " +
      "Если ключа нет или сайт не прочитан, поле refusals объяснит почему, а результат " +
      "будет пустым - это отказ, а не «нечего отвечать».",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
];

async function callTool(name) {
  if (name === "moltbook_status") return moltbookStatus();
  if (name === "moltbook_scan") return moltbookScan();
  throw new Error("Unknown tool: " + name);
}

// ---- MCP: сообщения ------------------------------------------------------------
async function handleMessage(msg) {
  const id = msg.id;
  if (msg.method === "initialize") {
    return {
      id,
      result: {
        protocolVersion: (msg.params && msg.params.protocolVersion) || "2024-11-05",
        capabilities: { tools: {} },
        serverInfo: { name: "moltbook", version: "0.1.0" },
      },
    };
  }
  if (msg.method === "notifications/initialized" || msg.method === "initialized") return null;
  if (msg.method === "tools/list") return { id, result: { tools: TOOLS } };
  if (msg.method === "tools/call") {
    const p = msg.params || {};
    try {
      const result = await callTool(p.name, p.arguments);
      if (id === undefined) return null;
      return { id, result: { content: [{ type: "text", text: JSON.stringify(result) }], isError: false } };
    } catch (e) {
      return { id, result: { content: [{ type: "text", text: String((e && e.message) || e) }], isError: true } };
    }
  }
  if (msg.method === "ping" || msg.method === "resources/list") return { id, result: {} };
  if (msg.method === "shutdown") return { id, result: {} };
  if (id !== undefined) return { id, result: {} };
  return null;
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}

function authorized(req, url) {
  // Без токена сервер не поднимается вообще: процесс, который слушает порт и принимает
  // любой запрос, хуже процесса, которого нет.
  if (!MOLTBOOK_TOKEN) return false;
  const header = req.headers.get("authorization") || "";
  const bearer = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : "";
  const queryToken = url.searchParams.get("token") || "";
  return constantTimeEqual(bearer, MOLTBOOK_TOKEN) || constantTimeEqual(queryToken, MOLTBOOK_TOKEN);
}

function serveHttp() {
  const clients = new Set();
  const server = Bun.serve({
    port: MOLTBOOK_PORT,
    hostname: "127.0.0.1",
    fetch(req, srv) {
      const url = new URL(req.url);
      if (url.pathname !== "/mcp") return new Response("not found", { status: 404 });
      if (!authorized(req, url)) {
        return new Response("unauthorized", {
          status: 401,
          headers: { "WWW-Authenticate": "Bearer" },
        });
      }
      if (req.method === "GET") {
        srv.timeout(req, 0);
        let closeStream = () => {};
        const stream = new ReadableStream({
          start(controller) {
            clients.add(controller);
            controller.enqueue("event: endpoint\ndata: /mcp\n\n");
            const iv = setInterval(() => {
              try {
                controller.enqueue(": keepalive\n\n");
              } catch (_) {
                closeStream();
              }
            }, 15000);
            closeStream = () => {
              clearInterval(iv);
              clients.delete(controller);
            };
            req.signal.addEventListener("abort", closeStream, { once: true });
          },
          cancel() {
            closeStream();
          },
        });
        return new Response(stream, {
          status: 200,
          headers: {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          },
        });
      }
      if (req.method === "POST") {
        return req
          .json()
          .then(async (body) => {
            const batch = Array.isArray(body) ? body : [body];
            const responses = [];
            for (const m of batch) {
              if (m === null || typeof m !== "object") continue;
              const r = await handleMessage(m);
              if (r !== null) responses.push(Object.assign({ jsonrpc: "2.0" }, r));
            }
            if (responses.length === 0) return new Response(null, { status: 202 });
            const json = responses.length === 1 ? responses[0] : responses;
            return new Response(JSON.stringify(json), {
              status: 200,
              headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
            });
          })
          .catch(
            (e) =>
              new Response(JSON.stringify({ jsonrpc: "2.0", error: { code: -32700, message: String(e) } }), {
                status: 400,
                headers: { "Content-Type": "application/json" },
              }),
          );
      }
      if (req.method === "DELETE") return new Response(null, { status: 202 });
      return new Response("method not allowed", { status: 405 });
    },
  });
  console.error("moltbook MCP http on 127.0.0.1:" + server.port);
  return server;
}

function serveStdio() {
  const { createInterface } = require("node:readline");
  const rl = createInterface({ input: process.stdin });
  rl.on("line", async (line) => {
    const text = line.trim();
    if (!text) return;
    let parsed = null;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      process.stdout.write(
        JSON.stringify({ jsonrpc: "2.0", error: { code: -32700, message: String(error) } }) + "\n",
      );
      return;
    }
    const reply = await handleMessage(parsed);
    if (reply !== null) process.stdout.write(JSON.stringify(Object.assign({ jsonrpc: "2.0" }, reply)) + "\n");
  });
  rl.on("close", () => process.exit(0));
}

if (MOLTBOOK_PORT > 0) {
  if (!MOLTBOOK_TOKEN) {
    console.error("moltbook MCP: нет MCP_MOLTBOOK_TOKEN, сервер не поднимается");
    process.exit(1);
  }
  serveHttp();
} else if (process.env.MOLTBOOK_STDIO === "1") {
  // Ручной запуск для отладки. Под тестом (node, без bun) этого не происходит, иначе
  // импорт файла уводил бы процесс в вечное чтение stdin.
  serveStdio();
}
