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

import {
  existsSync,
  mkdirSync,
  readFileSync,
  statSync,
  writeFileSync,
  openSync,
  fsyncSync,
  closeSync,
  writeSync,
  renameSync,
} from "node:fs";
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
/**
 * Имя плагина в ответах инструментов. Не путать с [MOLTBOOK_NAME]: это наш логин на
 * сайте, а это кто отвечает. Раньше в двух местах стоял логин, и отчёт подписывался
 * именем агента - выглядит как «отчёт от агента», хотя писал его плагин.
 */
export const PLUGIN_NAME = "moltbook";
export const MOLTBOOK_HOST = "https://www.moltbook.com";
export const LEDGER_NAME = "moltbook.db";
export const DATABASES_DIR_NAME = "databases";
/**
 * Предел одного запроса к молтбуку.
 *
 * Скан ходит на сайт десятком запросов, и очередь вызовов у него одна на весь
 * плагин: зависший сокет без предела глушит не только себя, но и moltbook_status,
 * на который агент смотрит, чтобы понять, живой ли плагин. 30 секунд - с запасом
 * на медленный мобильный интернет и при этом достаточно мало, чтобы ожидание
 * было заметно меньше минуты тика.
 */
export const MOLTBOOK_TIMEOUT_MS = 30_000;
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
  NEW: "NEW",
  POSTED: "POSTED",
  SKIPPED: "SKIPPED",
  FAILED: "FAILED",
};

/**
 * Сравнение статуса без учёта регистра.
 *
 * Зачем: Kotlin-тикер писал `CommentStatus.name`, то есть ПРОПИСНЫМИ
 * (`POSTED`, `SKIPPED`), а плагин пишет строчными (`posted`). Обе
 * разновидности лежат в одной таблице, и сравнение `=== "posted"`
 * молча теряло все строки тикера: отчёт показывал ноль ответов при
 * пятидесяти записанных, а `status != 'posted'` в upsert разрешал
 * переписать ответ, который тикер уже закрыл.
 *
 * В SQL то же самое делается через `UPPER(status)` - SQLite не знает
 * наш хелпер, и молчаливый промах здесь стоил бы записанного ответа.
 */
/**
 * Status is stored UPPERCASE, exactly like the Kotlin ticker's
 * CommentStatus.name. The columns are compared without COLLATE NOCASE, so
 * while this plugin wrote lowercase the old ticker could not see its
 * questions at all, and a plugin failure could lower an answer the ticker
 * had already closed. We read case-insensitively (statusIs) and write
 * the way Kotlin writes.
 */
export function storedStatus(value) {
  const text = String(value === null || value === undefined ? "" : value).trim();
  return text === "" ? "" : text.toUpperCase();
}

export function statusIs(value, status) {
  const text = (input) => String(input == null ? "" : input).trim().toUpperCase();
  return text(value) === text(status);
}

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
      statusIs(row.status, COMMENT_STATUS.POSTED) &&
      (row.reply_outcome === REPLY_OUTCOME.CREATED || row.reply_outcome === REPLY_OUTCOME.VERIFIED),
  );
  const verified = counted.filter((row) => row.reply_outcome === REPLY_OUTCOME.VERIFIED);
  const reused = rows.filter((row) => row.reply_outcome === REPLY_OUTCOME.REUSED);
  // Неизвестный исход бывает только у НАШЕГО ответа, который мы опубликовали, но
  // не смогли классифицировать. Пустой reply_outcome есть у любой строки, которую
  // мы НЕ отвечали (в схеме DEFAULT ''), и раньше именно они попадали сюда
  // все: на карточке появлялось «неизвестно: 77» при статусе NEW у всех и при
  // нуле опубликованных ответов. То есть число, которого не существует.
  const unknown = rows.filter(
    (row) =>
      statusIs(row.status, COMMENT_STATUS.POSTED) && row.reply_outcome === REPLY_OUTCOME.UNKNOWN,
  );
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
    const key = storedStatus(row.status) || COMMENT_STATUS.NEW;
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
      created_at: parseCreatedAt(item.created_at || item.createdAt),
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
/**
 * Время в миллисекундах из того, что прислал сервер.
 *
 * Зачем отдельная функция: молтбук отдаёт дату строкой ISO в поле `createdAt`,
 * а колонка ждёт число миллисекунд. Раньше здесь стояло Number(item.created_at),
 * и поле с другим именем давало NaN - SQLite принимал его за NULL, а
 * INSERT OR IGNORE молча проглатывал нарушение NOT NULL. Итог был тихий:
 * треды читались, кандидаты находились, а в журнал не попадало ни одного
 * комментария. Теперь непонятная дата становится нулём (порядок не нарушен),
 * а мусор не притворяется временем.
 */
export function parseCreatedAt(value) {
  if (typeof value === "number") return Number.isFinite(value) ? value : 0;
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Date.parse(value);
    if (Number.isFinite(parsed)) return parsed;
    const asNumber = Number(value);
    return Number.isFinite(asNumber) ? asNumber : 0;
  }
  return 0;
}

/**
 * Разворачивает ветку в плоский список.
 *
 *
 * Ответ на наш ответ приносится не в верхнем `comments`, а в `replies`
 * родителя, и тогда `parent_id` задан только на самом объекте.
 * Пока мы смотрели только верхний уровень, плагин не мог найти свой собственный ответ и вечно писал
 * unconfirmed, хотя сервер его вернул.
 *
 * Владеец невый родитель присодинся тем же идентификатором:
 * свой `parent_id` именнее поля, иначе увязываемся с родителем.
 */
export function flattenComments(items, inheritedParentId = null) {
  const flat = [];
  // Не верим, что массив: сервер может прислать сюда вместо
  // массива replies. Если войти по числу, for..of по числу нет, а по числу идёт итерация по символам - и уронили весь скан.
  const rows = Array.isArray(items) ? items : [];
  for (const item of rows) {
    if (!item || typeof item !== "object") continue;
    const id = String(item.id || "").trim();
    if (!id) continue;
    flat.push({
      id,
      post_id: String(item.post_id || ""),
      author: authorName(item.author_name || item.author),
      body: String(item.body || item.content || ""),
      parent_id: item.parent_id ? String(item.parent_id) : inheritedParentId,
      created_at: parseCreatedAt(item.created_at || item.createdAt),
      verification_status: String(item.verification_status || ""),
    });
    flat.push(...flattenComments(item.replies, id));
  }
  return flat;
}

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
  return { ok: true, reason: null, comments: flattenComments(parsed.comments) };
}

/**
 * Кому в этом треде можно ответить. Чистая функция, зеркалит deservesReply в Kotlin.
 *
 * Отвечаем на верхнеуровневые комментарии чужих авторов, на которые мы ещё не ответили.
 * Уже отвеченные отсекаются по факту: наш опубликованный комментарий с parent_id этого
 * комментария. Не по флажку в нашем коде, а по прочитанному треду - иначе «мы ответили»
 * держилось бы на памяти плагина и терялось при переустановке.
 */
/** Автор родителя, чтобы в журнал попал не пустой строкой. */
export function parentMeta(comments, parentId) {
  const found = (comments || []).find((c) => c && String(c.id) === String(parentId));
  if (!found) return null;
  return { author: String(found.author || ""), body: String(found.body || "") };
}

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

/**
 * Сколько страниц уведомлений тянем за один скан.
 *
 * У агента копится больше сотни непрочитанных, и `has_more` у этого эндпоинта
 * всегда true, пока их не потянуть. Тянуть все - значит всё время ходить в сеть
 * за тем, что мы уже видели. Две страницы - это «что нового с прошлого раза»
 * для обычного темпа: больше успевает накопиться только если агент не работал.
 */
export const SCAN_NOTIFICATION_PAGES = 2;

/**
 * Типы уведомлений, из которых есть смысл что-то извлекать.
 *
 * `new_follower` отброшен нарочно: он не несёт ни postId, ни commentId, а
 * стоит отдельный сетевой запрос ради одного счётчика, который агент всё
 * равно видит в /home. Пустое уведомление должно честно остаться пустым, а не
 * превратиться в пост с выдуманным автором.
 */
export const NOTIFICATION_KINDS = ["post_comment", "comment_reply"];

/**
 * Разбирает страницу уведомлений. Чистая функция, без сети.
 *
 * Зачем вообще: лента /feed отдаёт только чужие посты, поэтому наш счётчик
 * ours был нулём, пока посты агента жили в /home и в /notifications. Молтбук
 * показывал «сколько нас в чужих постах» и молчал про то, что происходит с
 * нашими - при 123 непрочитанных.
 *
 * Уведомление - указатель, а не источник имён: в нём есть `comment.authorId`
 * (uuid), но нет имени, а /agents/{id} отдаёт 404. Имена добираются там, где
 * они есть: в /posts/{id}/comments у каждого комментария лежит полный author.
 * Поэтому здесь мы достаём только идентификаторы и отдаём список постов,
 * комментарии к которым имеет смысл прочитать.
 */
export function parseNotifications(raw) {
  let parsed = null;
  try {
    parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch (error) {
    return { ok: false, reason: "notifications-not-json", items: [], posts: [], nextCursor: null };
  }
  if (!parsed || typeof parsed !== "object" || !Array.isArray(parsed.notifications)) {
    return {
      ok: false,
      reason: "notifications-have-no-list",
      items: [],
      posts: [],
      nextCursor: null,
    };
  }
const items = [];
  const posts = [];
  const ourPosts = [];
  const titles = {};
  const seenPosts = new Set();
  for (const note of parsed.notifications) {
    if (!note || typeof note !== "object") continue;
    const kind = String(note.type || "");
    if (!NOTIFICATION_KINDS.includes(kind)) continue;
    const postId = String(note.relatedPostId || "").trim();
    // Пост без id - это объект, к которому нельзя привязать ни комментарий,
    // ни пост. Записывать его в журнал нельзя: получится строка, которая
    // выглядит как пост, но не привязана ни к чему и не отвечает на вопрос.
    if (!postId) continue;
    const item = {
      kind,
      post_id: postId,
      comment_id: String(note.relatedCommentId || "").trim(),
      parent_id: String((note.comment && note.comment.parentId) || "").trim(),
      created_at: String(note.createdAt || ""),
      is_read: note.isRead === true,
    };
    items.push(item);
    if (!seenPosts.has(postId)) {
      seenPosts.add(postId);
      posts.push(postId);
      titles[postId] = String((note.post && note.post.title) || "");
    }
    // Только post_comment означает «это ВАШ пост». comment_reply - это ответ
    // на наш комментарий в ЧУЖОМ посте, и такой пост нашим не является: если
    // пометить его ours=1, то и ourPosts, и repliesToOurs на карточке считают
    // чужую работу нашей.
    if (kind === "post_comment" && !ourPosts.includes(postId)) ourPosts.push(postId);
  }
  return {
    ok: true,
    reason: null,
    items,
    posts,
    ourPosts,
    titles,
    nextCursor: parsed.next_cursor ? String(parsed.next_cursor) : null,
    hasMore: parsed.has_more === true,
    unread: Number(parsed.unread_count || 0),
  };
}

/**
 * Адрес страницы уведомлений по курсору. Первая страница курсора не требует.
 *
 * Отдельная функция, а не цикл в скане, потому что у этого эндпоинта курсор
 * обязателен: limit и offset он игнорирует, а has_more всегда true. Если курсор
 * не двигать, страница будет перечитываться до бесконечности - и скан будет
 * честно ходить в сеть, ни разу не увидев ничего нового.
 */
export function notificationPath(cursor) {
  const value = cursor ? String(cursor).trim() : "";
  if (!value) return "/api/v1/notifications";
  return "/api/v1/notifications?cursor=" + encodeURIComponent(value);
}

/**
 * Стоит ли идти за следующей страницей уведомлений.
 *
 * Разведено с notificationPath специально: адрес следующей страницы известен
 * только ПОСЛЕ ответа предыдущей, а бюджет «сколько страниц ещё можно» живёт
 * между запросами. Здесь проверяется главное - что курсор вообще двигается.
 * Повторяющийся курсор означает, что сервер отдаёт ту же страницу: идти дальше
 * бессмысленно, и без этой проверки скан крутился бы вечно.
 */
export function nextNotificationPath(previousCursor, parsed, pageIndex, pageBudget) {
  if (pageIndex + 1 >= Math.max(1, pageBudget)) return null;
  const next = parsed && parsed.nextCursor ? String(parsed.nextCursor) : null;
  if (!next) return null;
  const before = previousCursor ? String(previousCursor) : null;
  if (before !== null && next === before) return null;
  return notificationPath(next);
}

/** Кладёт сырой ответ в архив. Тот же формат, что у MoltbookRawArchive: <мс>-<хэш>.json. */
export function archiveRaw(dir, at, path, status, body, method = "GET") {
  if (!dir) return null;
  try {
    const name = String(at) + "-" + simpleHash(path + "|" + String(body)) + ".json";
    mkdirSync(dir, { recursive: true });
    // Метод часть данных: ответ на запрос и код сверки лежат
    // в той же архиве, что бы по нему можно был попубтимь, а не прочитать.
    writeFileSync(join(dir, name), JSON.stringify({ method, path, status, at, body }));
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
  const now = Date.now();
  const ledger = readLedger();
  return {
    plugin: PLUGIN_NAME,
    at: now,
    network: "not-used-yet",
    keyPresent: Boolean(MOLTBOOK_KEY_FILE) && existsSync(MOLTBOOK_KEY_FILE),
    ledger,
    panel: readPanel(now),
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
 * fetch, который обязан закончиться.
 *
 * Молтбук - чужой сайт, и он может висеть сколько угодно. Без предела один
 * зависший GET намертво занимает очередь вызовов (см. enqueueTool): следующий
 * инструмент не стартует, и панель молчит, пока процесс жив. Abort роняет сокет,
 * дальше срабатывает обычный catch, запрос попадает в архив как отказ - то есть
 * агент видит "сервер не ответил", а не пустоту.
 *
 * Предел задаётся третьим аргументом только ради теста: ждать настоящие полминуты
 * ради проверки, что сокет рвётся, никто не потерпит. Позвонивший без него
 * получает MOLTBOOK_TIMEOUT_MS, а во время вызова тула - ещё и остаток от
 * MOLTBOOK_CALL_BUDGET_MS.
 *
 * Возвращает тело строкой, а не Response: иначе предел закончился бы на
 * заголовках, а тело читалось бы уже без него.
 */
/**
 * Предел ОДНОГО вызова тула, а не одного запроса.
 *
 * Скан делает десяток последовательных GET, и предела на запрос для этого
 * мало: десять висящих по полминуты запросов держат очередь вызовов пять
 * минут, а moltbook_status - проверка живости - всё это время молчит, хотя
 * жив. Сквозной дедлайн ставится вокруг вызова, а fetchWithTimeout берёт
 * остаток от него. Ноль означает "вне вызова", тогда работает только предел
 * запроса.
 */
export const MOLTBOOK_CALL_BUDGET_MS = 120_000;
let callDeadlineAt = 0;

export async function fetchWithTimeout(url, options, timeoutMs) {
  const wanted = Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : MOLTBOOK_TIMEOUT_MS;
  const left = callDeadlineAt > 0 ? callDeadlineAt - Date.now() : wanted;
  // Остаток может кончиться между запросами: тогда limit становится 1мс,
  // и запрос честно падает как "время вызова вышло", а не висит дальше.
  const limit = Math.max(1, Math.min(wanted, left));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), limit);
  try {
    const response = await fetch(url, Object.assign({}, options, { signal: controller.signal }));
    // Тело читается ПОД ТЕМ ЖЕ пределом, и это не формальность: сервер может
    // отдать заголовки и зависнуть на теле. Снять таймер после fetch - значит
    // оставить response.text() без предела, то есть вернуть ровно тот зависший
    // вызов, ради которого предел и вводился.
    const body = await response.text();
    return { ok: response.ok, status: response.status, body };
  } finally {
    clearTimeout(timer);
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
    const { ok, status, body } = await fetchWithTimeout(url, {
      method: "GET",
      headers: { Authorization: "Bearer " + key },
    });
    archiveRaw(MOLTBOOK_DIR ? join(MOLTBOOK_DIR, RAW_DIR_NAME) : "", at, path, status, body);
    return { ok, status, path, at, body };
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
/**
 * Что в журнале лежит как вопрос. Вопрос без родителя (ответ на чужой) и наш собственный комментарий не вопрос: старый тикер выдал им вопросы и посылат бы получить што только на себя.
 */
export function scanStatus(comment) {
  if (!comment || !comment.id) return COMMENT_STATUS.SKIPPED;
  if (comment.parent_id) return COMMENT_STATUS.SKIPPED;
  if (String(comment.author || "").trim().toLowerCase() === MOLTBOOK_NAME) return COMMENT_STATUS.SKIPPED;
  return COMMENT_STATUS.NEW;
}

export function writeScan(db, posts, comments, now, ourPostIds = [], partialPostIds = []) {
  if (!db) return { postsWritten: 0, commentsWritten: 0, commentsSeen: 0, refused: "ledger-missing" };
  let postsWritten = 0;
  let commentsWritten = 0;
  let attempted = 0;
  // Пост из уведомления - наш по факту: post_comment по определению значит
  // «прокомментировали ваш пост». Сравнивать тут не с чем: в уведомлении
  // только authorId (uuid), а /agents/{id} отдаёт 404, так что имени автора
  // мы не знаем и сравнить его с MOLTBOOK_NAME физически нечем. Ложный ноль
  // здесь хуже честного факта: агент должен видеть, что его пост жив.
  const oursByNotice = new Set((ourPostIds || []).map(String));
  // Пост, пришедший из уведомления, знаем только по заголовку: автора, счётчиков
  // и repost_of в уведомлении нет. Перезаписывать им строку из журнала нельзя -
  // настоящий автор и repost_of ушли бы в пустоту, а вместе с ними счётчик
  // репостов и название поста на карточке. Такие строки только добавляются.
  const partial = new Set((partialPostIds || []).map(String));
  const upsertPost = db.prepare(
    "INSERT OR REPLACE INTO posts (id,title,author,ours,repost_of,comments_total,upvotes,seen_at,updated_at)" +
      " VALUES (?,?,?,?,?,?,?,?,?)",
  );
  // Тот же пост, но «не трогать, если уже есть»: для строк из уведомлений.
  const addPostIfMissing = db.prepare(
    "INSERT OR IGNORE INTO posts (id,title,author,ours,repost_of,comments_total,upvotes,seen_at,updated_at)" +
      " VALUES (?,?,?,?,?,?,?,?,?)",
  );
  const insertComment = db.prepare(
    "INSERT OR IGNORE INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
      " VALUES (?,?,?,?,?,?,NULL,'',?,0,'')",
  );
  // Тело и seen_at обновляются у новых и пропущенных строк, но НЕ у failed.
  // seen_at у провала пишет только markFailed: если бы скан подновлял его
  // каждый раз, старый провал снова и снова попадал бы в «провалы за окно»,
  // и счётчик не убывал бы никогда.
  const touchComment = db.prepare("UPDATE comments SET body=?, seen_at=? WHERE id=? AND UPPER(status) NOT IN ('POSTED','FAILED')");
  // Явные BEGIN/COMMIT, а не db.transaction(): этот файл проверяется обычным node,
  // где движок - node:sqlite, а у него нет .transaction(). Транзакция всё равно одна.
  db.exec("BEGIN");
  try {
    // Сколько строк было до и сколько стало - единственный честный способ
    // посчитать записанные комментарии. Раньше считалось через changes от
    // INSERT OR IGNORE, но Bun 1.4.0 для этой таблицы отдаёт changes:0 при
    // успешной вставке (проверено: lastInsertRowid рос, changes был нулём), и
    // плагин годами показывал «записано 0» при полном журнале. Дополнительно
    // OR IGNORE молча проглатывает нарушения NOT NULL - именно так пропал
    // created_at из-за NaN, и потери не было видно нигде.
    const countBefore = db.prepare("SELECT COUNT(*) AS n FROM comments").get().n;
    const existed = db.prepare("SELECT 1 AS n FROM posts WHERE id = ?");
    // Нестрогая проверка не случайна: bun:sqlite отдаёт при отсутствии строки
    // null, а node:sqlite (на нём идут тесты) - undefined. Строгое сравнение с
    // undefined обнулило бы postsWritten в проде, и тесты бы это скрыли.
    const isKnown = (postId) => existed.get(postId) != null;
    // Пост, который мы прежде записали как чужой (например, пришли ответ на наш
    // комментарий), а потом узнали, что он наш. Строка не трогается целиком -
    // только факт ours. Иначе наш счётчик занижался бы навсегда: addPostIfMissing
    // не обновляет уже существующие строки, а значит и ours остался бы 0.
    const markOurs = db.prepare("UPDATE posts SET ours = 1 WHERE id = ? AND ours = 0");
    for (const post of posts) {
      const wasKnown = partial.has(post.id) && isKnown(post.id);
      const statement = partial.has(post.id) ? addPostIfMissing : upsertPost;
      statement.run(
        post.id,
        post.title,
        post.author,
        post.author.trim().toLowerCase() === MOLTBOOK_NAME || oursByNotice.has(post.id) ? 1 : 0,
        post.repost_of,
        post.comments_total,
        post.upvotes,
        now,
        now,
      );
      // Пост из уведомления, который уже был в журнале, не считается записанным:
      // INSERT OR IGNORE его пропустил, и правда в том, что новых строк не
      // добавилось. А вот факт «это наш пост» обновляется в любом случае.
      if (oursByNotice.has(post.id)) markOurs.run(post.id);
      if (!wasKnown) postsWritten++;
    }
    for (const comment of comments) {
      if (!comment.post_id) continue;
      insertComment.run(
        comment.id,
        comment.post_id,
        comment.author,
        comment.body,
        comment.created_at,
        scanStatus(comment),
        now,
      );
      attempted += 1;
      // Строка могла уже быть - тогда обновляем тело и время просмотра, но НЕ
      // трогаем status/our_reply_id/replied_at/reply_outcome (см. доки above).
      touchComment.run(comment.body, now, comment.id);
    }
    const countAfter = db.prepare("SELECT COUNT(*) AS n FROM comments").get().n;
    commentsWritten = Math.max(0, countAfter - countBefore);
    db.exec("COMMIT");
  } catch (error) {
    try {
      db.exec("ROLLBACK");
    } catch (_) {
      // откат не удался - транзакция всё равно не применена частично, это причина тревоги
    }
    throw error;
  }
  return {
    postsWritten,
    commentsWritten,
    // Сколько вставок мы ПРЕДПРИНЯЛИ. Именно эта пара чисел отличает две разные
    // ситуации, которые раньше выглядели одинаково: attempted == written это
    // «нечего писать, всё уже в журнале», а attempted > written это «вставки
    // были, а строк не появилось» - то есть потеря. Именно так выглядел баг с
    // NaN в created_at: attempted 400, written 0.
    commentsSeen: attempted,
    refused: null,
  };
}

/** Сколько постов и сколько тредов смотреть за один вызов. */
export const SCAN_POST_LIMIT = 6;
/**
 * Тредов за один скан. Было 2, и этого хватало, пока скан смотрел только ленту:
 * два верхних поста и два ответа на них.
 *
 * Теперь тредов два вида - чужие из ленты и наши из уведомлений - и при лимите 2
 * наши просто не влезали бы: агент уходил в сеть и не видел, что написали
 * ему. Поэтому лимит поднят, а сами треды отбираются так, чтобы наши не
 * вытеснялись: сначала наши, потом лента.
 */
export const SCAN_THREAD_LIMIT = 6;

/**
 * Квоты тредов по источникам.
 *
 * Отдельные константы, потому что один общий лимит ничего не решает: либо
 * наши посты съедают всё и лента не читается, либо наоборот. Проверено
 * симуляцией - при общем лимите третий источник (чужие посты, где нам ответили
 * на наш комментарий) не получал ни одного слота НИКОГДА, то есть целая
 * половина второго источника была декорацией: уведомления приходили, число
 * repliesToUs росло, а сами комментарии не читались.
 */
export const SCAN_OWN_THREAD_LIMIT = 2;
export const SCAN_FEED_THREAD_LIMIT = 2;

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
      plugin: PLUGIN_NAME,
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

  // Второй контур: наши посты. Лента отдаёт чужие, поэтому наши посты видны
  // только через /notifications. Без этого агент не видел своей работы вообще.
  const notices = [];
  let noticePosts = [];
  const noticeOurPosts = [];
  const noticeTitles = {};
  let cursor = null;
  for (let page = 0; page < SCAN_NOTIFICATION_PAGES; page += 1) {
    const raw = await fetchRaw(key, notificationPath(cursor));
    requests.push({ path: raw.path, status: raw.status, ok: raw.ok });
    const parsedNotices = parseNotifications(raw.body);
    if (!parsedNotices.ok) {
      refusals.push(parsedNotices.reason);
      break;
    }
    for (const item of parsedNotices.items) notices.push(item);
    for (const postId of parsedNotices.posts) {
      if (noticePosts.includes(postId)) continue;
      noticePosts.push(postId);
      noticeTitles[postId] = parsedNotices.titles[postId];
    }
    for (const postId of parsedNotices.ourPosts) {
      if (!noticeOurPosts.includes(postId)) noticeOurPosts.push(postId);
    }
    const nextPath = nextNotificationPath(cursor, parsedNotices, page, SCAN_NOTIFICATION_PAGES);
    if (!nextPath) break;
    cursor = parsedNotices.nextCursor;
  }

  const allComments = [];
  const threadsRead = [];
  // Порядок тредов - это приоритет, а не случайность.
  //
  // Сначала наши посты: на них нам пишут, и это ровно то, ради чего агент выходит
  // в сеть. Затем лента: без неё скан перестаёт замечать новые вопросы, и это
  // было бы тихой слепотой - при ста с лишним непрочитанных на два страницы
  // уведомлений очередь из чужих постов всегда переполняла бы лимит целиком.
  // И только потом чужие посты, где нам ответили на наш комментарий: они важны,
  // но уступают тем двум, без которых работает всё остальное.
  const threadQueue = [];
  const seenThread = new Set();
  let ownTaken = 0;
  for (const postId of noticeOurPosts) {
    if (ownTaken >= SCAN_OWN_THREAD_LIMIT || threadQueue.length >= SCAN_THREAD_LIMIT) break;
    if (seenThread.has(postId)) continue;
    seenThread.add(postId);
    threadQueue.push(postId);
    ownTaken += 1;
  }
  let feedTaken = 0;
  for (const post of posts) {
    if (feedTaken >= SCAN_FEED_THREAD_LIMIT || threadQueue.length >= SCAN_THREAD_LIMIT) break;
    if (seenThread.has(post.id)) continue;
    seenThread.add(post.id);
    threadQueue.push(post.id);
    feedTaken += 1;
  }
  // Остаток достаётся чужим постам, где нам ответили на наш комментарий.
  for (const postId of noticePosts) {
    if (threadQueue.length >= SCAN_THREAD_LIMIT) break;
    if (seenThread.has(postId)) continue;
    seenThread.add(postId);
    threadQueue.push(postId);
  }
  for (const postId of threadQueue) {
    const raw = await fetchRaw(key, "/api/v1/posts/" + postId + "/comments?sort=new&limit=100");
    requests.push({ path: raw.path, status: raw.status, ok: raw.ok });
    const parsed = parseComments(raw.body);
    if (!parsed.ok) {
      refusals.push("thread-" + postId + "-" + parsed.reason);
      continue;
    }
    threadsRead.push(postId);
    for (const comment of parsed.comments) {
      if (!comment.post_id) comment.post_id = postId;
      allComments.push(comment);
    }
  }

  // Посты из уведомлений надо дописать в журнал: счётчик repliesToOurs делает
  // JOIN по posts, и без строки поста ответы на наши посты просто не считались
  // бы - осталось бы ровно то молчание, ради устранения которого всё затевалось.
  const feedPostIds = {};
  for (const post of posts) feedPostIds[post.id] = true;
  const noticePartialPosts = [];
  for (const postId of noticePosts) {
    // Пост, который уже записан из ленты, НЕ перезаписываем: из уведомления мы
    // знаем только заголовок, а автор, счётчики и repost_of ушли бы в пустоту,
    // и в панели появился бы пост без названия. Лучше неполные данные, чем
    // затирание настоящих.
    if (feedPostIds[postId]) continue;
    feedPostIds[postId] = true;
    noticePartialPosts.push(postId);
    posts.push({
      id: postId,
      title: noticeTitles[postId] || "",
      // Автора не знаем: в уведомлении только authorId, а /agents/{id} отдаёт
      // 404. Пустая строка честнее выдуманного имени - и ours проставится по
      // самому факту уведомления (writeScan, ourPostIds).
      author: "",
      comments_total: 0,
      upvotes: 0,
      created_at: 0,
      repost_of: null,
    });
  }

  const written = (function () {
    let opened = null;
    try {
      opened = openLedger(false);
      if (!opened.db) return { postsWritten: 0, commentsWritten: 0, commentsSeen: 0, refused: "ledger-missing" };
      const result = writeScan(opened.db, posts, allComments, now, noticeOurPosts, noticePartialPosts);
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
      return { postsWritten: 0, commentsWritten: 0, commentsSeen: 0, refused: String((error && error.message) || error) };
    }
  })();
  if (written.refused) refusals.push("ledger-" + written.refused);

  const seenByPost = {};
  for (const post of posts) seenByPost[post.id] = true;
  for (const postId of noticePosts) seenByPost[postId] = true;
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
  if (karma !== null || unread !== null) {
    const cached = cacheHome(karma, unread, now);
    if (cached) refusals.push("home-cache-" + cached);
  }

  // Переводы - после записи журнала и отдельным шагом: их отказ не должен
  // отменять скан. Панель получит русский текст со следующим кругом, а пока
  // покажет оригинал.
  const translation = await (async function () {
    let opened = null;
    try {
      opened = openLedger(false);
      if (!opened.db) return { attempted: 0, translated: 0, skipped: 0 };
      return await translatePending(opened.db, {
        target: TRANSLATE_TARGET,
        // Бюджет вызова скана делится с переводами: упор в сетевую ленту не
        // должен оставлять переводчику 0 ms, в которые fetch отдаст таймаут на
        // каждый из двадцати пяти текстов подряд.
        budgetMs: Math.min(TRANSLATE_BUDGET_MS, Math.max(0, callDeadlineAt - Date.now())),
      });
    } catch (error) {
      return { attempted: 0, translated: 0, refused: String((error && error.message) || error) };
    } finally {
      if (opened && opened.db) {
        try {
          opened.db.close();
        } catch (_) {
          // закрытие не удалось - переводы уже записаны, база закроется с процессом
        }
      }
    }
  })();
  if (translation.refused) refusals.push("translate-" + translation.refused);

  return {
    plugin: PLUGIN_NAME,
    at: now,
    network: { karma, unread, postsSeen: posts.length, threadsRead },
    // Что пришло к нам лично: без этого поля агент видит только «мы никого не
    // ждём», хотя под notified сидит сто с лишним непрочитанных.
    notices: {
      seen: notices.length,
      unread: notices.filter((n) => !n.is_read).length,
      ourPosts: noticeOurPosts.length,
      // Сколько постов пришлось прочитать из уведомлений - это число больше:
      // сюда попадают и чужие посты, где нам ответили на наш комментарий.
      postsRead: noticePosts.length,
      repliesToUs: notices.filter((n) => n.kind === "comment_reply").length,
    },
    ledger: {
      ok: !written.refused,
      postsWritten: written.postsWritten,
      commentsWritten: written.commentsWritten,
      // Что дошло до записи. Ноль здесь при ненулевом commentsWritten означает,
      // что потерялись до SQLite - и это видно сразу, а не по симптому.
      commentsSeen: written.commentsSeen,
    },
    candidates: picked.candidates.map((c) => ({
      post_id: c.post_id,
      comment_id: c.id,
      parent_id: null,
      author: c.author,
      body: c.body.slice(0, 400),
    })),
    answerable: picked.candidates.length,
    // Сколько русских текстов доехало в этом круге. Не «сколько всего»: панель
    // показывает переведённое, а сколько ещё в очереди - видно по заголовку.
    translation: { attempted: translation.attempted, translated: translation.translated, skipped: translation.skipped },
    refusals,
    requests,
  };
}

/** Ключи, которыми сервер отдаёт id опубликованного комментария. Порядок значим. */
export const SUCCESS_ID_KEYS = ["id", "comment_id", "commentId"];
/** Ключи со статусом проверки - серверные слова, не наши (см. SERVER_STATUS). */
export const STATUS_KEYS = ["verification_status", "verificationStatus"];
/** Ответ на задачу платформы: ровно `число.00`. Другой формат молча тратит код. */
export const ANSWER_SHAPE = /^\d{1,6}\.\d{2}$/;
/** CloudFront перед API режет тела примерно от килобайта. */
export const MAX_COMMENT_CHARS = 900;
/** Глубина, на которой ищем поля ответа: корень, comment, data. */
const SCAN_DEPTH = 3;

/**
 * Куда в ответе сервера смотреть за полями.
 *
 * Молтбук отдаёт комментарий то на верхнем уровне, то вложенным в `comment`, то под
 * `data`, и энпоинты меняют форму от случая к случаю. Читать только один уровень -
 * значит либо получить пустой id, либо принять чужой объект за наш.
 */
export function commentScopes(root) {
  const scopes = [];
  let cursor = root;
  for (let depth = 0; depth < SCAN_DEPTH && cursor && typeof cursor === "object"; depth++) {
    scopes.push(cursor);
    if (cursor.comment && typeof cursor.comment === "object") scopes.push(cursor.comment);
    cursor = cursor.data;
  }
  return scopes;
}

/** id созданного комментария или пустая строка. */
function idText(value) {
  if (value === null || value === undefined || typeof value === "object") return "";
  const text = String(value).trim();
  return text === "null" || text === "undefined" ? "" : text;
}

/** Идентификатор текста: сервер может прислать не только строку, а flattenComments приводит тот же id к строке. */
export function postedCommentId(root) {
  for (const scope of commentScopes(root)) {
    for (const key of SUCCESS_ID_KEYS) {
      const text = idText(scope[key]);
      if (text) return text;
    }
  }
  return "";
}

/** Серверный статус проверки, а не наш статус журнала. */
export function verificationStatusOf(root) {
  for (const scope of commentScopes(root)) {
    for (const key of STATUS_KEYS) {
      const value = scope[key];
      if (typeof value === "string" && value && value.toLowerCase() !== "null") return value;
    }
  }
  return "";
}

/** Родитель, на который сервер повесил ответ, либо null. */
export function commentParentId(root) {
  for (const scope of commentScopes(root)) {
    const text = idText(scope.parent_id);
    if (text) return text;
  }
  return null;
}

/** Флаг дубля. Строка "true" тоже считается: платформа местами присылает строкой. */
export function isAlreadyExisted(root) {
  return commentScopes(root).some(
    (scope) => scope.already_existed === true || scope.already_existed === "true",
  );
}

/** Задача платформы: код, текст и срок. `null`, если задачи нет. */
export function verificationChallenge(root) {
  for (const scope of commentScopes(root)) {
    const challenge = scope.verification;
    if (challenge && typeof challenge === "object" && String(challenge.verification_code || "")) {
      return {
        code: String(challenge.verification_code),
        text: String(challenge.challenge_text || ""),
        expires_at: String(challenge.expires_at || ""),
      };
    }
  }
  return null;
}

/** JSON без исключений: неразобранный ответ - это отказ, а не повод упасть всему визиту. */
export function safeJson(raw) {
  try {
    const parsed = JSON.parse(String(raw === undefined || raw === null ? "" : raw));
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch (error) {
    return null;
  }
}

/**
 * Исход POST без побочных эффектов. Никакой сети, никакого журнала.
 *
 * Порядок обязателен: `already_existed` важнее verification. Если сервер ничего не
 * создал, то и код выдал не для нас - публиковать тут нечего, и читать задачу как
 * «создано, ждём проверки» нельзя.
 */
export function parsePostOutcome(raw) {
  const root = safeJson(raw);
  if (!root) {
    return { kind: "rejected", commentId: "", status: "", parentId: null, challenge: null, message: "ответ сервера не JSON" };
  }
  const commentId = postedCommentId(root);
  const status = verificationStatusOf(root);
  const parentId = commentParentId(root);
  if (isAlreadyExisted(root)) {
    return { kind: "duplicate", commentId, status, parentId, challenge: null, message: "" };
  }
  const challenge = verificationChallenge(root);
  if (challenge) {
    return { kind: "challenge", commentId, status, parentId, challenge, message: "" };
  }
  if (!commentId) {
    return { kind: "rejected", commentId, status, parentId, challenge: null, message: String(root.message || "без comment и без verification") };
  }
  return { kind: "created", commentId, status, parentId, challenge: null, message: "" };
}

/**
 * Что означает дубль с точки зрения нашего ответа на конкретный вопрос.
 *
 * Дедуп молтбука - по тексту и автору БЕЗ родителя, поэтому сервер в ответ на вопрос в
 * треде B вправе прислать id нашего же комментария из треда A. Одинаковый родитель -
 * единственное доказательство, что это ответ именно на тот вопрос, который мы задаём.
 *
 * published + тот же родитель -> reused: это наш же ответ, ноль новых фактов.
 * published + чужой родитель   -> misparented: подтвердить нечем, удалять нельзя.
 * не published                 -> blocked: комментарий создан, но не виден, второй
 *                                  ответ платформа всё равно не даст.
 */
/**
 * Опубликован или нет — это разные вещи, и «не знаю» это третий исход.
 *
 * `isPublishedStatus` считает пустой статус опубликованным: это паритет Kotlin,
 * где платформа просто не присылает поле. Но для решения «не публиковать второй
 * ответ» такой счёт не годится: отсутствие поля ничего не доказывает, а приняв
 * его за публикацию, мы навсегда закрыли бы ветку и потеряли бы наш ответ.
 * Поэтому здесь — только явный статус.
 */
export function isAffirmativelyPublished(status) {
  const text = String(status == null ? "" : status).trim();
  return text !== "" && isPublishedStatus(text);
}
export function reuseOrMisparent(outcome, expectedParentId) {
  if (!isAffirmativelyPublished(outcome.status)) return "blocked";
  // Родителя нет в ответе — это не «чужой родитель», а «не знаю».
  // Утверждать «чужой» нельзя: мы ничего не разводили.
  if (!outcome.parentId) return "blocked";
  return outcome.parentId === expectedParentId ? "reused" : "misparented";
}

/**
 * Наш ответ на конкретный вопрос прямо сейчас.
 *
 * Три исхода, а не два: опубликованный ответ, созданный-но-невидимый (он не отвечает
 * ни на что и блокирует ветку навсегда) и отсутствие ответа.
 */
export function ourAnswerFor(comments, parentId, ourName, knownReplyId) {
  let unpublished = null;
  const mine = String(ourName || "").trim().toLowerCase();
  const known = String(knownReplyId || "").trim();
  for (const comment of comments) {
    if (String(comment.author || "").trim().toLowerCase() !== mine) continue;
    // Платформа может отдать наш ответ без parent_id. Если журнал уже записал
    // его как ответ именно на этот вопрос, id из журнала и есть доказательство.
    if (comment.parent_id !== parentId && !(known && comment.id === known)) continue;
    if (isAffirmativelyPublished(comment.verification_status)) {
      return { kind: "published", id: comment.id || "", status: comment.verification_status };
    }
    if (!unpublished) unpublished = { kind: "unpublished", id: comment.id || "", status: comment.verification_status };
  }
  return unpublished || { kind: "absent", id: "", status: "" };
}

/**
 * Записи журнала, которые вправо сделать только публикация.
 *
 * `markAnswered` закрывает чужой комментарий, на который мы ответили, и пишет id
 * нашего ответа. `markFailed` честно помечает вопрос как нерешённый: иначе он
 * выглядел бы не отвеченным и кандидатом снова.
 */
/**
 * Записывает ответ в журнал целиком.
 *
 *
 * Раньше был простой UPDATE по id, и он молчал:
 * UPDATE по такой строке, которой ещё не была в журнале - строки нет,
 * а наш свой ответ в журнал не попадал ни когда.
 * Счётчик того стал бы весьм ноль: ответой на сайте и счётчик равны нулю.
 *
 * Строка, уже записанная как posted, не переписывается: тело записанного ответа
 * длжно сохраняться как доказательство.
 */
const RECORD_SQL =
  "INSERT INTO comments (id,post_id,author,body,created_at,status,our_reply_id,summary_ru,seen_at,replied_at,reply_outcome)" +
  " VALUES (?,?,?,?,?,?,?,'',?,?,?)" +
  " ON CONFLICT(id) DO UPDATE SET status = excluded.status," +
  " our_reply_id = COALESCE(excluded.our_reply_id, comments.our_reply_id)," +
  " reply_outcome = CASE WHEN excluded.reply_outcome = '' THEN comments.reply_outcome ELSE excluded.reply_outcome END," +
  " replied_at = CASE WHEN excluded.replied_at = 0 THEN comments.replied_at ELSE excluded.replied_at END," +
  " seen_at = excluded.seen_at" +
  // Записанная posted строка не переписывается: тело ответа — это доказательство,
  // и потерять его нельзя. Единственный пропускаемый переход — created -> verified,
  // иначе подтверждение никогда не смогло бы довести ответ до verified.
" WHERE UPPER(comments.status) != 'POSTED' OR (comments.reply_outcome = 'created' AND excluded.reply_outcome = 'verified')";

export function recordComment(db, row) {
  if (!db || !row || !row.id) return false;
  const known = db.prepare("SELECT author FROM comments WHERE id = ?").get(String(row.id));
  const author = String(row.author || (known && known.author) || "").trim();
  // Автор нужен только на вставку: pendingReplies отдаст пустый автор
  // как вопрос с пустым именем и пустым текстом. У уже стоявшей строки автор из журнала достаен, поэтому дефолт не нужен.
  if (!author) return false;
  const ourReplyId = row.our_reply_id ? String(row.our_reply_id) : null;
  const now = Number(row.now || 0);
const stmt = db.prepare(RECORD_SQL);
  const changed = stmt.run(
    String(row.id),
    String(row.post_id || ""),
    author,
    String(row.body || ""),
    Number(row.created_at || 0),
    storedStatus(row.status) || COMMENT_STATUS.NEW,
    ourReplyId,
    now,
    // replied_at - не то, что ищет CREATED_SQL/VERIFIED_SQL:
    // ответ без нашего id не может быть нашим ответом на этот вопрос
    // и не должен попасть в счётчик работы.
    ourReplyId ? now : 0,
    String(row.reply_outcome || ""),
  ).changes > 0;
  if (changed) return true;
  // Ничего не изменилось - но это не обязательно отказ. Запись могла уже стоять
  // раньше: posted-строка защищена от понижения, и повторная публикация по тому
  // же parent_id обязан вернуть «журнал уже это знает», а не «отказались
  // записать». Иначе инструмент врёт агенту о записанном ответе.
  const settled = db
    .prepare("SELECT status, our_reply_id FROM comments WHERE id = ?")
    .get(String(row.id));
  if (!settled) return false;
  if (!statusIs(settled.status, COMMENT_STATUS.POSTED)) return false;
  const settledReply = String(settled.our_reply_id || "");
  return Boolean(settledReply) && (!ourReplyId || settledReply === ourReplyId);
}


export function markAnswered(db, commentId, ourReplyId, now, outcome, row) {
  if (!db || !commentId) return false;
  const extra = row || {};
  return recordComment(db, {
    id: commentId,
    post_id: extra.post_id || "",
    author: extra.author || "",
    body: extra.body || "",
    created_at: Number(extra.created_at || 0),
    status: COMMENT_STATUS.POSTED,
    our_reply_id: ourReplyId || null,
    now,
    reply_outcome: String(outcome || ""),
  });
}

export function markFailed(db, commentId, now, row) {
  if (!db || !commentId) return false;
  const extra = row || {};
  return recordComment(db, {
    id: commentId,
    post_id: extra.post_id || "",
    author: extra.author || "",
    body: extra.body || "",
    created_at: Number(extra.created_at || 0),
    status: COMMENT_STATUS.FAILED,
    now,
  });
}

/** Виды записей свидетеля. Согласие пишется рядом с отказом - иначе журнал врёт. */
export const VERIFIER_KIND = {
  CONFIRMED: "confirmed",
  MISMATCH: "mismatch",
  REFUSED: "refused",
  DIVERGENCE: "divergence",
};

/** Одна строка журнала свидетеля. Пробелы схлопываются: запись всегда должна быть строкой. */
export function verifierLine(kind, at, postId, detail) {
  const safe = String(detail === undefined || detail === null ? "" : detail).replace(/\s+/g, " ");
  return at + "s " + kind + " post=" + postId + " " + safe;
}

/**
 * Приписывает запись в `verifier.log` рядом с `witness.log`.
 *
 * Только добавление и только fsync: журнал свидетеля, который переписывает публикующий
 * код, - это не свидетель, а самоотчёт. Ошибка записи не должна ронять визит - поэтому
 * `null`, а не исключение.
 */
export function appendVerifier(kind, at, postId, detail) {
  if (!MOLTBOOK_DIR) return null;
  let handle = null;
  try {
    mkdirSync(MOLTBOOK_DIR, { recursive: true });
    handle = openSync(join(MOLTBOOK_DIR, VERIFIER_NAME), "a");
    writeFileSync(handle, verifierLine(kind, at, postId, detail) + "\n");
    fsyncSync(handle);
    return VERIFIER_NAME;
  } catch (error) {
    return null;
  } finally {
    if (handle !== null) {
      try {
        closeSync(handle);
      } catch (_) {
        // закрытие не удалось - запись уже на диске или fsync её не подтвердил
      }
    }
  }
}

/** POST с телом. Архивирует сырой ответ тем же способом, что и GET. */
export async function postRaw(key, path, payload) {
  const at = Date.now();
  try {
    const { ok, status, body } = await fetchWithTimeout(MOLTBOOK_HOST + path, {
      method: "POST",
      headers: { Authorization: "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    archiveRaw(MOLTBOOK_DIR ? join(MOLTBOOK_DIR, RAW_DIR_NAME) : "", at, path, status, body, "POST");
    return { ok, status, path, at, body };
  } catch (error) {
    archiveRaw(MOLTBOOK_DIR ? join(MOLTBOOK_DIR, RAW_DIR_NAME) : "", at, path, 0, String(error), "POST");
    return { ok: false, status: 0, path, at, body: "", error: String((error && error.message) || error) };
  }
}

/**
 * Ищем свой комментарий перечитыванием ветки, а не доверием ответу POST.
 *
 * Первое представление может не показать комментарий, и это ещё не значит, что его нет:
 * замерено, что сортировки `new`, `old` и `best` возвращают разные первые элементы.
 * Второе представление - независимая проверка, а не повтор того же чтения.
 */
export async function confirmComment(get, key, postId, commentId, parentId) {
  const views = [];
  let undecided = null;
  for (const sort of ["new", "best"]) {
    const raw = await get(key, "/api/v1/posts/" + postId + "/comments?sort=" + sort + "&limit=100");
    views.push({ sort, status: raw.status, ok: raw.ok });
    const parsed = parseComments(raw.body);
    if (!parsed.ok) continue;
    const hit = parsed.comments.find((comment) => comment.id === commentId);
    if (!hit) continue;
    const seen = {
      found: true,
      sort,
      status: hit.verification_status,
      published: isAffirmativelyPublished(hit.verification_status),
      // Разрывный parent_id — это незнание, а не доказательство расхождения.
      parentUnknown: !hit.parent_id,
      parentMatches: !parentId || hit.parent_id === parentId,
      actualParent: hit.parent_id || null,
      parent: parentMeta(parsed.comments, parentId),
      views: views.slice(),
    };
    if (seen.published && seen.parentMatches) return seen;
    if (!undecided) undecided = seen;
  }
  return undecided || {
    found: false,
    sort: null,
    status: "",
    published: false,
    parentUnknown: false,
    parentMatches: false,
    actualParent: null,
    parent: null,
    views,
  };
}

/**
 * Что запись в журнал свидетеля должна сказать о перечитывании.
 *
 * `success: true` в ответе платформы ничего не доказывает: замерено, что в одном ответе
 * приходили `success: true` и `already_existed: true`, а комментарий при этом оставался
 * `pending`. Доказывает только наш собственный read-back по конкретному id.
 */
export function verifyVerdict(confirmed) {
  if (!confirmed.found) return VERIFIER_KIND.REFUSED;
  if (!confirmed.published) return VERIFIER_KIND.REFUSED;
  if (!confirmed.parentMatches) return confirmed.parentUnknown ? VERIFIER_KIND.REFUSED : VERIFIER_KIND.MISMATCH;
  return VERIFIER_KIND.CONFIRMED;
}

/** Обрезает текст под CloudFront, не разрывая слова. */
export function capForWaf(text) {
  const trimmed = String(text || "").trim();
  if (trimmed.length <= MAX_COMMENT_CHARS) return trimmed;
  const budget = MAX_COMMENT_CHARS - 1;
  const cut = trimmed.slice(0, budget);
  const lastSpace = cut.lastIndexOf(" ");
  return (cut.slice(0, lastSpace > budget / 2 ? lastSpace : budget).trimEnd() || cut) + "…";
}

export function recordedReplyId(openDb, parentId) {
  if (!openDb || !parentId) return "";
  let opened = null;
  try {
    opened = openDb(true);
    if (!opened || !opened.db) return "";
    const row = opened.db.prepare("SELECT our_reply_id FROM comments WHERE id = ?").get(String(parentId));
    return row && row.our_reply_id ? String(row.our_reply_id) : "";
  } catch (_) {
    return "";
  } finally {
    if (opened && opened.db) {
      try {
        opened.db.close();
      } catch (_) {
        // Журнал не закрылся — читать его дальше нельзя, и ответ мы не получили.
        // Молча отдаём пустое: следующий вызов попробует снова.
      }
    }
  }
}

/** Обрезали ли хвост: молча обрезанный текст хуже, чем честная метка об усечении. */
export function wasTruncated(text) {
  return String(text || "").trim().length > MAX_COMMENT_CHARS;
}

const REFUSAL_NO_KEY = "Файл с ключом молтбука не найден или пуст. Публиковать нечем.";

/** Отказ без сети: пустой результат здесь означал бы «нечего отвечать», а это ложь. */
function publishRefusal(reason, at, detail) {
  return {
    plugin: PLUGIN_NAME,
    at,
    refused: reason,
    reason: detail || REFUSAL_NO_KEY,
    outcome: "refused",
    requests: [],
    refusals: [reason],
  };
}

/**
 * Наш инструмент: ответить на комментарий.
 *
 * Порядок шагов зафиксирован прошлыми инцидентами и не переставляется:
 *   1. перечитать ветку - запись в сокет уходит раньше ответа, поэтому таймаут на чтении
 *      означает «сервер, возможно, уже создал комментарий», а повторный POST без проверки
 *      публикует второй ответ на тот же вопрос;
 *   2. POST с явным parent_id;
 *   3. дубль разводится по статусу и родителю, а не считается успехом;
 *   4. перечитывание ветки вторым представлением и запись в журнал свидетеля.
 */
export async function moltbookPublish(args, deps) {
  const io = deps || {};
  const now = io.now ? io.now() : Date.now();
  const key = io.key ? io.key() : readMoltbookKey();
  const send = io.send || postRaw;
  const get = io.get || fetchRaw;
  const openDb = io.openDb || openLedger;
  const requests = [];
  const refusals = [];
  if (!key) return publishRefusal("no-api-key", now, REFUSAL_NO_KEY);

  const postId = String((args && args.post_id) || "");
  const parentId = String((args && args.parent_id) || "");
  const rawContent = String((args && args.content) || "");
  const content = capForWaf(rawContent);
  if (!postId || !parentId || !content) {
    return publishRefusal("need-post-id-parent-id-and-content", now, "Нужны post_id, parent_id и непустой content.");
  }
  if (wasTruncated(rawContent)) refusals.push("content-truncated");

  const threadPath = "/api/v1/posts/" + postId + "/comments?sort=new&limit=100";
  // Журнал уже знает, какой ответ мы уже дали на эту ветку.
  const recorded = recordedReplyId(openDb, parentId);
  const before = await get(key, threadPath);
  requests.push({ path: before.path, status: before.status, ok: before.ok });
  const parsedBefore = parseComments(before.body);
  const parentRow = parentMeta(parsedBefore.comments, parentId);
  if (parsedBefore.ok) {
    const answer = ourAnswerFor(parsedBefore.comments, parentId, MOLTBOOK_NAME, recorded);
    if (answer.kind === "published") {
      appendVerifier(VERIFIER_KIND.CONFIRMED, now, postId, "id=" + answer.id + " повторная публикация не нужна");
      const closed = writeReply(openDb, (db) => markAnswered(db, parentId, answer.id, now, REPLY_OUTCOME.REUSED, { post_id: postId, ...parentRow }), refusals);
      return {
        plugin: PLUGIN_NAME,
        at: now,
        outcome: "reused",
        comment_id: answer.id,
        post_id: postId,
        parent_id: parentId,
        counted: closed.counted,
        requests,
        refusals,
      };
    }
    if (answer.kind === "unpublished") {
      refusals.push("our-unpublished-answer");
      appendVerifier(VERIFIER_KIND.REFUSED, now, postId, "id=" + answer.id + " status=" + statusText(answer.status) + " второй ответ платформа не даст");
      return {
        plugin: PLUGIN_NAME,
        at: now,
        outcome: "blocked",
        comment_id: answer.id,
        post_id: postId,
        parent_id: parentId,
        counted: false,
        reason: "Наш прошлый ответ создан, но не прошёл проверку. Второй ответ платформа всё равно не даст.",
        requests,
        refusals,
      };
    }
  } else {
    // Таймаут на чтении — это не доказательство отсутствия:
    // сервер возможно уже создал наш комментарий, и слепой POST вернул бы already_existed с чужим id.
    refusals.push("thread-unreadable-" + before.status);
    return replyResult("refused", "", postId, parentId, now, requests, refusals, false);
  }

  const posted = await send(key, "/api/v1/posts/" + postId + "/comments", { content, parent_id: parentId });
  requests.push({ path: posted.path, status: posted.status, ok: posted.ok });
  const outcome = parsePostOutcome(posted.body);

  if (outcome.kind === "duplicate") {
    const verdict = reuseOrMisparent(outcome, parentId);
    if (verdict === "reused") {
      const written = writeReply(openDb, (db) => markAnswered(db, parentId, outcome.commentId, now, REPLY_OUTCOME.REUSED, { post_id: postId, ...parentRow }), refusals);
      appendVerifier(VERIFIER_KIND.CONFIRMED, now, postId, "id=" + outcome.commentId + " reused");
      return replyResult("reused", outcome.commentId, postId, parentId, now, requests, refusals, written.counted);
    }
    if (verdict === "misparented") {
      writeReply(openDb, (db) => markFailed(db, parentId, now, { post_id: postId, ...parentRow }), refusals);
      appendVerifier(
        VERIFIER_KIND.MISMATCH,
        now,
        postId,
        "dedup-alias parent=" + parentId + " actual=" + (outcome.parentId || "unknown-parent") + " id=" + outcome.commentId,
      );
      return replyResult("misparented", outcome.commentId, postId, parentId, now, requests, refusals, false);
    }
    refusals.push("our-unpublished-answer");
    writeReply(openDb, (db) => markFailed(db, parentId, now, { post_id: postId, ...parentRow }), refusals);
    return replyResult("blocked", outcome.commentId, postId, parentId, now, requests, refusals, false);
  }

  if (outcome.kind === "rejected") {
    refusals.push("post-rejected");
    writeReply(openDb, (db) => markFailed(db, parentId, now, { post_id: postId, ...parentRow }), refusals);
    const rejected = replyResult("rejected", "", postId, parentId, now, requests, refusals, false);
    rejected.reason = outcome.message;
    return rejected;
  }

  if (outcome.kind === "challenge") {
    // Комментарий создан, но не виден читателям. Родителя закрывать рано: задача может
    // быть не решена, и тогда ветка останется неотвеченной - честно.
    refusals.push("needs-verification");
    appendVerifier(VERIFIER_KIND.REFUSED, now, postId, "id=" + outcome.commentId + " ждёт проверки");
    const challenged = replyResult("challenge", outcome.commentId, postId, parentId, now, requests, refusals, false);
    challenged.challenge = outcome.challenge;
    return challenged;
  }

  const confirmed = await confirmComment(get, key, postId, outcome.commentId, parentId);
  const kind = verifyVerdict(confirmed);
  // Подтвержденный id — единственный исход журнала.
  // Если сверка не видел комментарий — это не работа, а не делано, и записываться нельзя.
  const knownParent = parentRow || confirmed.parent;
  if (!knownParent) refusals.push("parent-unknown");
  const journalRow = { post_id: postId, ...(knownParent || {}) };
  const written =
    kind === VERIFIER_KIND.CONFIRMED
      ? writeReply(openDb, (db) => markAnswered(db, parentId, outcome.commentId, now, REPLY_OUTCOME.CREATED, journalRow), refusals)
      : writeReply(openDb, (db) => markFailed(db, parentId, now, journalRow), refusals);
  appendVerifier(
    kind,
    now,
    postId,
    "id=" + outcome.commentId + " status=" + statusText(confirmed.status) + " sort=" + (confirmed.sort || "нет") + " parent=" + (confirmed.actualParent || "нет"),
  );
  const created = replyResult(kind === VERIFIER_KIND.CONFIRMED ? "created" : "unconfirmed", outcome.commentId, postId, parentId, now, requests, refusals, kind === VERIFIER_KIND.CONFIRMED && written.counted);
  created.verifier = kind;
  created.server_status = confirmed.status;
  return created;
}

/**
 * Статус для строки журнала. Пустое значение и «сервер не ответил» — это разные
 * вещи, поэтому неизвестное записывается словом, а не пустотой: `status=` в логе
 * читается как обрезанная строка, и потом никто не поймёт, что там было.
 */
export const UNKNOWN_STATUS = "неизвестно";

export function statusText(value) {
  const text = String(value == null ? "" : value).trim();
  return text === "" ? UNKNOWN_STATUS : text;
}

/** Решение по задаче платформы. Её решает агент, а не плагин. */
export async function moltbookVerify(args, deps) {
  const io = deps || {};
  const now = io.now ? io.now() : Date.now();
  const key = io.key ? io.key() : readMoltbookKey();
  const send = io.send || postRaw;
  const get = io.get || fetchRaw;
  const openDb = io.openDb || openLedger;
  const requests = [];
  const refusals = [];
  if (!key) return publishRefusal("no-api-key", now, REFUSAL_NO_KEY);

  const postId = String((args && args.post_id) || "");
  const parentId = String((args && args.parent_id) || "");
  const commentId = String((args && args.comment_id) || "");
  const code = String((args && args.verification_code) || "");
  const answer = String((args && args.answer) || "").trim();
  if (!postId || !parentId || !commentId || !code) {
    return publishRefusal("need-post-id-parent-id-comment-id-and-code", now, "Нужны post_id, parent_id, comment_id и verification_code.");
  }
  if (!ANSWER_SHAPE.test(answer)) {
    // Формат задаёт платформа. Ответ вида "48.0" молча тратит одноразовый код.
    return publishRefusal("answer-must-be-number-with-two-decimals", now, "Ответ должен быть числом с двумя знаками, например 40.00.");
  }

  // Чтобы записать, кому мы отвечаем: имя родителя без автора - строка придурака
  // получила бы без неменного автора, и markAnswering просто бы не запишала ни одной строки.
  const beforeThread = await get(key, "/api/v1/posts/" + postId + "/comments?sort=new&limit=100");
  requests.push({ path: beforeThread.path, status: beforeThread.status, ok: beforeThread.ok });
  const parsedBefore = parseComments(beforeThread.body);
  const parentRow = parentMeta(parsedBefore.ok ? parsedBefore.comments : [], parentId);
  if (!parentRow) refusals.push("parent-unknown");

  const verified = await send(key, "/api/v1/verify", { verification_code: code, answer });
  requests.push({ path: verified.path, status: verified.status, ok: verified.ok });
  const root = safeJson(verified.body);
  const accepted = Boolean(root && root.success === true);
  if (!accepted) {
    refusals.push("verification-rejected");
    writeReply(openDb, (db) => markFailed(db, parentId, now, { post_id: postId, ...(parentRow || {}) }), refusals);
    appendVerifier(VERIFIER_KIND.REFUSED, now, postId, "verify answer=" + answer + " id=" + commentId);
    const rejected = replyResult("verification-failed", commentId, postId, parentId, now, requests, refusals, false);
    rejected.server_message = String((root && root.message) || verified.body || "").slice(0, 200);
    return rejected;
  }

  const confirmed = await confirmComment(get, key, postId, commentId, parentId);
  const kind = verifyVerdict(confirmed);
  if (kind !== VERIFIER_KIND.CONFIRMED) {
    refusals.push("server-said-ok-but-not-visible");
    appendVerifier(kind, now, postId, "verify answer=" + answer + " id=" + commentId + " status=" + statusText(confirmed.status));
    const doubted = replyResult("unconfirmed", commentId, postId, parentId, now, requests, refusals, false);
    doubted.verifier = kind;
    doubted.server_status = confirmed.status;
    return doubted;
  }
  const written = writeReply(
    openDb,
    (db) => markAnswered(db, parentId, commentId, now, REPLY_OUTCOME.VERIFIED, { post_id: postId, ...(parentRow || confirmed.parent || {}) }),
    refusals,
  );
  appendVerifier(VERIFIER_KIND.CONFIRMED, now, postId, "verify answer=" + answer + " id=" + commentId + " status=" + statusText(confirmed.status));
  const result = replyResult("verified", commentId, postId, parentId, now, requests, refusals, written.counted);
  result.verifier = kind;
  result.server_status = confirmed.status;
  return result;
}

/**
 * Запись в журнал под замком: открыли, записали, закрыли.
 *
 * Отказ открытия - это отказ, а не ноль: вернувший `0` без причины выглядел бы так же,
 * как «мы ничего не ответили», и потерял бы различие.
 */
export function writeReply(openDb, action, refusals) {
  let opened = null;
  try {
    opened = openDb(false);
    if (!opened || !opened.db) {
      refusals.push("ledger-missing");
      return { counted: false };
    }
    try {
      const counted = action(opened.db) === true;
      // Тихий отказ без причины — раньше бы вернули "записано", что молчали.
      if (!counted) refusals.push("journal-not-recorded");
      return { counted };
    } finally {
      opened.db.close();
    }
  } catch (error) {
    refusals.push("ledger-" + String((error && error.message) || error));
    if (opened && opened.db) {
      try {
        opened.db.close();
      } catch (_) {
        // закрытие не удалось - запись всё равно не применена
      }
    }
    return { counted: false };
  }
}

/** Общий вид ответа публикации: исход, что засчитано, и чем это подтверждено. */
function replyResult(outcome, commentId, postId, parentId, at, requests, refusals, counted) {
  return {
    plugin: PLUGIN_NAME,
    at,
    outcome,
    comment_id: commentId,
    post_id: postId,
    parent_id: parentId,
    counted: counted === true,
    requests,
    refusals,
  };
}

// ---- отчёт для ПК-агента --------------------------------------------------
// Файл, который читает ПК-агент. В нём должны быть не только успехи, но и
// отказы: свидетель, который не умеет отказывать, - это самоотчёт (совет с
// moltbook). Поэтому в отчёте есть раздел «Отказы свидетеля» и «Не про
// что», а счётчик работы выводится проекцией журнала, а не памятью тула.

export const REPORT_NAME = "MOLTBOOK_REPORT.md";

/** Сколько времени отчёту смотрит назад, если агент не попросил окно явно. */
export const DEFAULT_REPORT_WINDOW_MS = 86_400_000;

/** Сколько неотвеченных вопросов перечисляем в отчёте. */
export const REPORT_PENDING_LIMIT = 20;

/**
 * Окно отчёта. `since` приходит от агента, и он может прислать что угодно,
 * поэтому нечисловое и нулевое значение заменяем окном по умолчанию, а не
 * отказываем: пустой отчёт хуже отчёта за сутки.
 */
export function reportWindow(since, now, windowMs) {
  const to = Number(now);
  const fallback = Number.isFinite(windowMs) && windowMs > 0 ? windowMs : DEFAULT_REPORT_WINDOW_MS;
  const asked = Number(since);
  const from = Number.isFinite(asked) && asked > 0 && asked <= to ? asked : to - fallback;
  return { from, to };
}

/**
 * Разбор журнала свидетеля. Одна запись - одна строка вида
 * `<at>s <kind> post=<id> <detail>`.
 *
 * Строку, которую не удалось разобрать, мы НЕ выбрасываем молча: она
 * возвращается отдельным счётчиком. Иначе оборванная запись выглядела бы
 * как «свидетель ничего не наблюдал», а это ровно тот самоотчёт, который
 * нам и запрещали.
 */
export function parseVerifierLog(text) {
  const records = [];
  let unreadable = 0;
  for (const raw of String(text || "").split("\n")) {
    const line = raw.trim();
    if (line === "") continue;
    const match = /^(\d+)s (confirmed|mismatch|refused|divergence) post=(\S*)(?: (.*))?$/.exec(line);
    if (!match) {
      unreadable += 1;
      continue;
    }
    records.push({
      at: Number(match[1]),
      kind: match[2],
      postId: match[3] || "",
      detail: match[4] || "",
    });
  }
  // unreadable считается по ВСЕМУ файлу, потому что у непрочитанной строки
  // нет времени: положить её в окно отчёта нельзя честно. Поэтому в отчёте
  // это отдельная строка «без времени», а не часть отказов в окне.
  return { records, unreadable };
}

/**
 * Отказ свидетеля - это НЕ то же самое, что его согласие.
 *
 * Подтверждение тоже пишется в verifier.log: иначе нельзя отличить
 * «проверили и подтвердили» от «проверили и не смогли подтвердить».
 * Но в отчёте успех не должен выглядеть как отказ - иначе агент и
 * ПК-агент будут читать одно и то же как бесконечную череду отказов.
 */
export function isRefusal(kind) {
  return String(kind || "") !== VERIFIER_KIND.CONFIRMED;
}

/**
 * Проекция журнала: что мы реально сделали за окно.
 *
 * created и verified - это работа, её и показываем в счётчике. reused - не
 * работа, но видеть его надо: значит, мы попали в ветку, которую закрыли
 * раньше. Пустой исход (строки до v5) не в счёт ни в одну строку: неизвестное
 * не равно сделанному.
 */
export function projectWork(rows, from) {
  const list = Array.isArray(rows) ? rows : [];
  const status = (row) => String(row.status || "");
  const outcome = (row) => String(row.reply_outcome || "");
  const repliedAfter = (row) => Number(row.replied_at || 0) >= from;
  const posted = (row) => statusIs(row.status, COMMENT_STATUS.POSTED);
  const pick = (wire) =>
    list
      .filter((row) => posted(row) && repliedAfter(row) && outcome(row) === wire)
      .map((row) => ({
        id: String(row.id || ""),
        postId: String(row.post_id || ""),
        replyId: String(row.our_reply_id || ""),
        at: Number(row.replied_at || 0),
      }));
  const failed = list
    .filter((row) => statusIs(row.status, COMMENT_STATUS.FAILED) && Number(row.seen_at || 0) >= from)
    .map((row) => ({ id: String(row.id || ""), postId: String(row.post_id || "") }));
  const unknown = list.filter((row) => posted(row) && repliedAfter(row) && outcome(row) === "").length;
  return {
    created: pick(REPLY_OUTCOME.CREATED),
    verified: pick(REPLY_OUTCOME.VERIFIED),
    reused: pick(REPLY_OUTCOME.REUSED),
    failed,
    unknown,
  };
}

/**
 * Вопросы, на которые мы ещё не ответили. Свои собственные комментарии в
 * список не попадают: иначе агент будет сам у себя спрашивать разрешения.
 */
export function pendingQuestions(rows, ourName, limit) {
  const list = Array.isArray(rows) ? rows : [];
  const mine = String(ourName || "").toLowerCase();
  const max = Number.isFinite(limit) && limit > 0 ? limit : REPORT_PENDING_LIMIT;
  return list
    .filter((row) => statusIs(row.status, COMMENT_STATUS.NEW))
    .filter((row) => String(row.author || "").toLowerCase() !== mine)
    .slice(0, max)
    .map((row) => ({
      postId: String(row.post_id || ""),
      commentId: String(row.id || ""),
      author: String(row.author || ""),
      body: String(row.body || "").slice(0, 300),
    }));
}

function stamp(ms) {
  const value = Number(ms);
  return Number.isFinite(value) ? new Date(value).toISOString().replace("T", " ").slice(0, 19) + "Z" : "неизвестно";
}

function line(label) {
  return "- " + label;
}

/**
 * Сборка текста отчёта. Чистая функция: на вход объект, на выход строка,
 * поэтому весь формат проверяется тестами без диска и без сети.
 */
export function renderReport(data) {
  const work = data.work || { created: [], verified: [], reused: [], failed: [], unknown: 0 };
  const pending = Array.isArray(data.pending) ? data.pending : [];
  const refusals = Array.isArray(data.refusals) ? data.refusals : [];
  const unreadable = Number(data.unreadable || 0);
  const notes = Array.isArray(data.notes) ? data.notes : [];
  const done = work.created.length + work.verified.length;
  const out = [];
  out.push("# Отчёт Moltbook");
  out.push("");
  out.push("Окно: " + stamp(data.from) + " … " + stamp(data.to));
  out.push("Собрано: " + stamp(data.at));
  out.push("");
  out.push("## Итог");
  out.push(line("ответов создано: " + work.created.length));
  out.push(line("ответов проверено: " + work.verified.length));
  out.push(line("работы всего: " + done));
  out.push(line("веток закрыто повторно (reused, работы не считается): " + work.reused.length));
  out.push(line("провалов записано: " + work.failed.length));
  out.push(line("исход неизвестен (строки до v5, работы не считаются): " + work.unknown));
  out.push("");
  out.push("## Ответы");
  const replies = work.created.concat(work.verified);
  if (replies.length === 0) {
    out.push("- за окно ответов нет");
  } else {
    for (const reply of replies) {
      out.push(
        line("пост " + reply.postId + " / наш коммент " + reply.id +
          (reply.replyId ? " / ответ сервера " + reply.replyId : "") + " / " + stamp(reply.at))
      );
    }
  }
  out.push("");
  out.push("## Не про что");
  if (pending.length === 0) {
    out.push("- неотвеченных вопросов нет");
  } else {
    for (const item of pending) {
      out.push(line("пост " + item.postId + " / коммент " + item.commentId + " от " + item.author +
        " — " + item.body.replace(/\s+/g, " ")));
    }
  }
  out.push("");
  out.push("## Отказы свидетеля");
  for (const note of notes) {
    out.push(line(note));
  }
  if (refusals.length === 0 && unreadable === 0 && notes.length === 0) {
    out.push("- отказов не было");
  } else {
    for (const record of refusals) {
      out.push(line(stamp(record.at) + " " + record.kind + " пост " + record.postId + " — " + record.detail));
    }
    if (unreadable > 0) {
      out.push(line("\u041d\u0435\u043f\u0440\u043e\u0447\u0438\u0442\u0430\u043d\u043d\u044b\u0435\u0020\u0441\u0442\u0440\u043e\u043a\u0438\u0020\u0432\u0020verifier.log (\u0431\u0435\u0437 \u0432\u0440\u0435\u043c\u0435\u043d\u0438\u002c \u043f\u043e\u0442\u043e\u043c\u0443 \u0447\u0442\u043e \u044d\u0442\u043e\u0020\u043d\u0435 \u043f\u043e\u043f\u0430\u043b\u0430 \u0432 \u043e\u043a\u043d\u043e\u0020\u043e\u0442\u0447\u0451\u0442\u0430): " + unreadable));;
    }
  }
  out.push("");
  return out.join("\n");
}

/**
 * Запись отчёта. tmp → fsync → rename → fsync каталога: одноимённый rename
 * сам по себе не доводит запись до диска (совет 3.6 с moltbook).
 * Возвращает путь или null; исключение наружу не выпускаем.
 */
export function writeReport(dir, text) {
  if (!dir) return null;
  let handle = null;
  let dirHandle = null;
  const target = join(dir, REPORT_NAME);
  const temp = target + ".tmp";
  try {
    mkdirSync(dir, { recursive: true });
    handle = openSync(temp, "w");
    writeSync(handle, text);
    fsyncSync(handle);
    closeSync(handle);
    handle = null;
    renameSync(temp, target);
    try {
      dirHandle = openSync(dir, "r");
      fsyncSync(dirHandle);
    } catch (_) {
      // Каталог не даёт fsync - это не повод выбрасывать уже переименованный файл.
    }
    return target;
  } catch (_) {
    return null;
  } finally {
    if (handle !== null) {
      try {
        closeSync(handle);
      } catch (_) {
        // Ручка уже могла закрыться сама.
      }
    }
    if (dirHandle !== null) {
      try {
        closeSync(dirHandle);
      } catch (_) {
        // То же самое.
      }
    }
  }
}
/** Куда класть отчёт: своё хранилище всегда, внешний каталог - если дан. */
export function reportDirs() {
  const external = String(process.env.MCP_MOLTBOOK_REPORT_DIR || "");
  const list = [MOLTBOOK_DIR, external].filter((dir) => dir !== "");
  return list.filter((dir, index) => list.indexOf(dir) === index);
}

/** Все записи журнала: проекции нужны полные строки, а не только сводка. */
const REPORT_SELECT =
  "SELECT id, post_id, author, body, status, our_reply_id, seen_at, replied_at, " +
  "reply_outcome FROM comments";

function reportRows(db, hasReplyOutcome) {
  const columns = hasReplyOutcome ? "reply_outcome" : "''";
  return db.prepare(REPORT_SELECT.replace(", reply_outcome", ", " + columns)).all();
}

function verifierRecords(from) {
  if (!MOLTBOOK_DIR) return { refusals: [], unreadable: 0 };
  let text = "";
  try {
    text = readFileSync(join(MOLTBOOK_DIR, VERIFIER_NAME), "utf8");
  } catch (_) {
    return { refusals: [], unreadable: 0 };
  }
  const parsed = parseVerifierLog(text);
  const inWindow = parsed.records.filter((record) => record.at >= from);
  return {
    refusals: inWindow.filter((record) => isRefusal(record.kind)),
    unreadable: parsed.unreadable,
  };
}

function reportFailure(at, reason, extra) {
  return Object.assign({ plugin: PLUGIN_NAME, at, outcome: "refused", refused: reason }, extra || {});
}

/**
 * Отчёт для ПК-агента: что сделали, на что не ответили и где свидетель
 * отказался. Сеть не трогаем - отчёт это чтение журнала, а поход на сайт
 * делает moltbook_scan.
 */
export async function moltbookReport(args, deps) {
  const options = deps || {};
  const input = args || {};
  const at = options.now ? options.now() : Date.now();
  const window = reportWindow(input.since, at, options.windowMs);
  // openLedger actually opens the file, and Database throws on a corrupt one.
  // This call used to sit OUTSIDE try, so a broken journal arrived as a
  // TypeError instead of a refusal. A refusal is also a result.
  let opened = null;
  try {
    opened = options.openDb ? options.openDb() : openLedger(true);
  } catch (_) {
    return reportFailure(at, "ledger-unreadable", { window });
  }
  // openLedger на отсутствующем файле отдаёт объект {db:null}, а он истинен,
  // поэтому проверять только сам opened бессмысленно: без проверки db на
  // свежей установке prepare() упал бы с TypeError вместо отказа.
  if (!opened || !opened.db) return reportFailure(at, "ledger-missing", { window });

  try {
    // prepare(), а не query(): query() - это обёртка bun, её нет ни у
    // node:sqlite, ни у настоящего движка на некоторых сборках.
    const column = opened.db
      .prepare("SELECT name FROM pragma_table_info('comments') WHERE name = 'reply_outcome'")
      .get();
    const hasReplyOutcome = Boolean(column);
    const rows = reportRows(opened.db, hasReplyOutcome);
    const work = projectWork(rows, window.from);
    const pending = pendingQuestions(rows, MOLTBOOK_NAME, options.pendingLimit);
    const observed = verifierRecords(window.from);
    const externalDir = String(process.env.MCP_MOLTBOOK_REPORT_DIR || "");
    const notes = externalDir
      ? []
      : [
          "внешний каталог недоступен - отчёт лежит только в приватном хранилище, " +
            "ПК-агент его не увидит (MCP_MOLTBOOK_REPORT_DIR пуст)",
        ];
    const text = renderReport({
      at,
      from: window.from,
      to: window.to,
      work,
      pending,
      refusals: observed.refusals,
      unreadable: observed.unreadable,
      notes,
    });
    const written = [];
    const dirs = options.dirs || reportDirs();
    for (const dir of dirs) {
      const path = writeReport(dir, text);
      if (path) written.push(path);
    }
    if (written.length === 0) return reportFailure(at, "report-not-written", { window });
    return {
      plugin: PLUGIN_NAME,
      at,
      outcome: "written",
      from: window.from,
      to: window.to,
      written,
      work: {
        created: work.created.length,
        verified: work.verified.length,
        reused: work.reused.length,
        failed: work.failed.length,
        unknown: work.unknown,
      },
      pending: pending.length,
      refusals: observed.refusals.length,
      notes,
      unreadableVerifierLines: observed.unreadable,
    };
  } catch (error) {
    return reportFailure(at, "report-failed:" + String(error && error.message ? error.message : error), { window });
  } finally {
    if (options.openDb === undefined && opened.db) {
      try {
        opened.db.close();
      } catch (_) {
        // Ручка могла закрыться сама.
      }
    }
  }
}

/** Имя файла-снимка для панели «М». Панель читает файл, а не лезет в SQLite. */
export const PANEL_NAME = "panel.json";

/**
 * Сколько неотвеченных вопросов уходит в снимок панели.
 *
 * Раньше было 6 - и карточка врала: сверху «67 ждут ответа», снизу шесть строк,
 * и ни слова о том, где остальные. Пользователь это увидел и спросил прямо.
 * Читать очередь нужно и панели (нажать, посмотреть, что там), поэтому верхняя
 * граница поднята с шести: шесть это «подставить витрину», а не «прочитать».
 *
 * Предел 120 - не из жадности, а из веса. Каждая строка несёт тело до 4000
 * символов плюс перевод того же объёма, то есть 120 строк это примерно
 * мегабайт, и он целиком парсится при каждом обновлении панели. Реальная
 * очередь агента - десятки, так что 120 это запас, а не обрезка того, что
 * нужно прямо сейчас. Сколько всего - считается отдельно и не зависит от
 * этого числа, поэтому панель всегда знает, что осталось за краем.
 */
const PANEL_PENDING_LIMIT = 120;
// Тело вопроса показывается целиком: юзер читает его сам, и обрезанный на 400
// символах комментарий - это не «кратко», а «не прочитано». 4000 - заведомо
// больше типичного комментария, но не снимок всей ветки в один файл.
const PANEL_BODY_CHARS = 4000;

// Второй писатель этих ключей - MoltbookTicker.kt:80-81, из другого процесса.
// Он мёртв: конструктор MoltbookTicker( в проекте не вызывается ни разу, и
// внутри себя тикер сам пишет «не запускать старый тикер». Если его когда-нибудь
// оживят, он обязан писать и karma_at, иначе карточка подпишет его карму как
// «только что», взяв время из нашего кэша.
const PANEL_HOME_KEYS = { KARMA: "karma", UNREAD: "unread", AT: "karma_at" };

const PANEL_SQL = {
  ourPosts: "SELECT COUNT(*) AS n FROM posts WHERE ours = 1",
  repliesToOurs:
    "SELECT COUNT(*) AS n FROM comments c JOIN posts p ON p.id = c.post_id" +
    " WHERE p.ours = 1 AND UPPER(c.author) != UPPER(?)",
  // Репосты НАШИХ постов, а не все репосты подряд. Прежний тикер под той же
  // подписью считал `repost_of IS NOT NULL` по всей таблице, то есть включал
  // чужие репосты чужих постов; цифра на карточке от этого станет меньше, и
  // это осознанное изменение, а не тихая ошибка.
  reposts: "SELECT COUNT(*) AS n FROM posts r JOIN posts p ON p.id = r.repost_of WHERE p.ours = 1",
  upvotes: "SELECT COUNT(*) AS n FROM upvotes",
pending:
    "SELECT c.id AS id, c.author AS author, c.body AS body, c.post_id AS post_id, p.title AS title" +
    " FROM comments c LEFT JOIN posts p ON p.id = c.post_id" +
    " WHERE UPPER(c.status) = 'NEW' AND UPPER(c.author) != UPPER(?)" +
    " ORDER BY c.created_at ASC LIMIT ?",
  // Счётчик отделён от списка: список обрезан шестью строками, а отвечать надо
  // на вопрос "сколько ждут ответа", то есть по всей таблице. Один и тот же
  // запрос не может дать и то и другое.
  awaiting:
    "SELECT COUNT(*) AS n FROM comments c" +
    " WHERE UPPER(c.status) = 'NEW' AND UPPER(c.author) != UPPER(?)",
};

const HOME_SQL =
  "INSERT INTO ticker_state (key, value) VALUES (?, ?)" +
  " ON CONFLICT(key) DO UPDATE SET value = excluded.value";

function panelCount(db, sql, ...args) {
  const value = Number((db.prepare(sql).get(...args) || {}).n || 0);
  return value > 0 ? value : 0;
}

/**
 * Тело вопроса в том виде, в каком его увидит юзер.
 *
 * Одна функция, а не обрезка на месте: ключ перевода считается от того же
 * текста, который показывается. Две разные обрезки означали бы, что у длинного
 * комментария перевод ищется не по тому, что на экране, и не находится.
 */
export function panelBody(raw) {
  return String(raw || "")
    .replace(/\s+/g, " ")
    .slice(0, PANEL_BODY_CHARS);
}

/**
 * Вопросы для панели: без id строки нет.
 *
 * `translations` - Map из ключа [translationKey] в русский текст. Это кэш, а не
 * сеть: панель открыта часто, а ходить за переводом на каждый чих нельзя. Нет
 * перевода - поле пустое, и карточка показывает оригинал, а не пустую строку.
 */
export function panelPending(rows, translations) {
  const cache = translations instanceof Map ? translations : new Map();
  return (Array.isArray(rows) ? rows : [])
    .map((row) => {
      const body = panelBody((row && row.body) || "");
      return {
        id: String((row && row.id) || "").trim(),
        author: String((row && row.author) || "").trim(),
        post_id: String((row && row.post_id) || "").trim(),
        title: String((row && row.title) || "").trim(),
        body,
        bodyRu: String(cache.get(translationKey(body)) || ""),
      };
    })
    .filter((row) => row.id !== "");
}

/**
 * Переводы ленты на русский.
 *
 * Зачем: панель читает чужие комментарии, а читать их должен юзер, а не агент.
 * Оригинал без перевода - это работа для него, а не отдых.
 *
 * Где хранится: в ticker_state под ключом `tr:<хэш>:<длина>`, а не новой
 * таблицей. Таблица переводов означала бы миграцию схемы, то есть ещё одну
 * причину упасть на старте, - а состояние плагина и так переживает переезд
 * между хранилищами.
 */
export const TRANSLATE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";
export const TRANSLATE_PER_SCAN = 25;
export const TRANSLATE_BUDGET_MS = 40_000;
export const TRANSLATE_KEY_PREFIX = "tr:";

/** Язык перевода. Задан константой, а не размазан по строке, чтобы смена языка была одной правкой. */
const TRANSLATE_TARGET = "ru";

// Сколько символов отдаём переводчику за раз. Равно PANEL_BODY_CHARS намеренно:
// карточка показывает именно столько, и перевод короче этой границы был бы
// половиной тела, поданной как полный перевод. Запас Google на одну строку -
// 5000 символов, то есть мы в него помещаемся.
const TRANSLATE_MAX_CHARS = PANEL_BODY_CHARS;
export const TRANSLATE_TIMEOUT_MS = 15_000;
const TRANSLATE_STATE_PREFIX = "translate-";

// Сколько строк читаем, чтобы найти те, что ещё без перевода. Не limit: limit
// брал бы первые N по created_at, а переведённые из прошлого круга там же -
// то есть очередь второго круга состояла бы из continue'ов и до хвоста не
// дошла бы никогда.
const TRANSLATE_SCAN_ROWS = 400;

// Не начинать запрос, если осталось меньше этого. Равен таймауту одного
// перевода: страж в 2 секунды при переводе на 15 секунд не защищал ровно от
// того, от чего объявлен - запрос, начатый впритык, всё равно уходил за бюджет.
export const TRANSLATE_TAIL_GUARD_MS = TRANSLATE_TIMEOUT_MS;

const TRANSLATE_SQL =
  "SELECT id, body FROM comments" +
  " WHERE UPPER(status) = 'NEW' AND UPPER(author) != UPPER(?)" +
  " ORDER BY created_at ASC LIMIT ?";

/**
 * Ключ перевода: хэш текста плюс длина.
 *
 * Длина добавлена намеренно: хэш короткого текста вполне может совпасть с
 * хэшем длинного, а молотбук полон однотипных коротких реплик, где совпадение
 * стоило бы показать не тот перевод.
 */
export function translationKey(text) {
  const value = String(text || "")
    .replace(/\s+/g, " ")
    .trim();
  let hash = 5381;
  for (let i = 0; i < value.length; i += 1) {
    hash = (Math.imul(hash, 33) + value.charCodeAt(i)) >>> 0;
  }
  return TRANSLATE_KEY_PREFIX + hash.toString(16) + ":" + value.length;
}

/** Адрес перевода. Отдельно от самой сети, чтобы проверялся обычным тестом. */
export function translateUrl(text, target = "ru") {
  const query = String(text || "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, TRANSLATE_MAX_CHARS);
  return (
    TRANSLATE_ENDPOINT +
    "?client=gtx&sl=auto&dt=t" +
    "&tl=" +
    encodeURIComponent(target) +
    "&q=" +
    encodeURIComponent(query)
  );
}

/**
 * Текст из ответа переводчика.
 *
 * Сервис отдаёт `[[["перевод","оригинал",...],["хвост",...]]]` - кусками, и
 * мусор вместо JSON здесь не редкость. Любой не-JSON даёт пустую строку: пустой
 * перевод панель честно заменяет оригиналом, а выдуманный - нет.
 */
export function parseTranslation(raw) {
  if (typeof raw !== "string" || raw.trim() === "") return "";
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed) || !Array.isArray(parsed[0])) return "";
    return parsed[0]
      .map((chunk) => (Array.isArray(chunk) ? String(chunk[0] || "") : ""))
      .join("")
      .trim();
  } catch (_) {
    return "";
  }
}

/**
 * Готовый перевод из состояния или пустая строка, если его ещё нет.
 *
 * Промис под ключом - часть контракта с [rememberTranslation]: переводы лежат
 * рядом с кармой, и без префикса они перетирали бы `karma` тем же хэшем.
 */
export function translationFor(db, key) {
  const row = db
    .prepare("SELECT value FROM ticker_state WHERE key = ?")
    .get(TRANSLATE_STATE_PREFIX + String(key));
  return row ? String(row.value || "") : "";
}

/** Запомнить перевод: тот же приём, что у кармы, ключ перезаписывает значение. */
export function rememberTranslation(db, key, translated) {
  const value = String(translated || "").trim();
  if (value === "") return false;
  db.prepare(HOME_SQL).run(TRANSLATE_STATE_PREFIX + String(key), value);
  return true;
}

/**
 * Перевод одного текста. Сеть внутри, поэтому проверяется подставленным
 * `request`, а не настоящим выходом наружу.
 *
 * Любой отказ - пустая строка. Перевод не проходит: скан обязан отработать и
 * показать оригинал, а не упасть из-за чужого сервиса.
 */
export async function translateText(text, options = {}) {
  const trimmed = String(text || "")
    .replace(/\s+/g, " ")
    .trim();
  if (trimmed === "") return "";
  const target = options.target || "ru";
  const timeoutMs = Number(options.timeoutMs) > 0 ? Number(options.timeoutMs) : TRANSLATE_TIMEOUT_MS;
  const send = options.request || ((url) => fetchWithTimeout(url, { method: "GET" }, timeoutMs));
  try {
    const response = await send(translateUrl(trimmed, target));
    if (!response || response.ok !== true) return "";
    return parseTranslation(response.body);
  } catch (_) {
    return "";
  }
}

/**
 * Досылаем переводы для очереди неотвеченных.
 *
 * С бюджетом по времени и по числу текстов: очередь бывает и в сотню
 * комментариев, а скан уже уложился в свой лимит. Лучше недоперевести десяток,
 * чем сорвать скан целиком - недопереведённое дойдёт следующим кругом.
 *
 * Уже переведённые пропускаются и НЕ в счёт лимита: иначе очередь из
 * двадцати пяти самых старых комментариев держала бы переводчик вечно, а до
 * остальных он не дошёл бы никогда.
 */
export async function translatePending(db, options = {}) {
  const limit = Number(options.limit) > 0 ? Math.floor(Number(options.limit)) : TRANSLATE_PER_SCAN;
  // Ноль - это НОЛЬ, а не «бюджет не передан». Иначе остаток дедлайна вызова,
  // дошедший до нуля, читался бы как «переводи хоть полминуты» - и скан
  // уходил бы в сотни заведомо мёртвых запросов к переводчику.
  const budgetMs =
    options.budgetMs === undefined || options.budgetMs === null
      ? TRANSLATE_BUDGET_MS
      : Math.max(0, Number(options.budgetMs));
  const clock = options.now || (() => Date.now());
  const startedAt = clock();
  let attempted = 0;
  let translated = 0;
  let skipped = 0;
  let rows = [];
  try {
    rows = db.prepare(TRANSLATE_SQL).all(MOLTBOOK_NAME, TRANSLATE_SCAN_ROWS);
  } catch (_) {
    return { attempted: 0, translated: 0, skipped: 0, refused: "translate-query" };
  }
  for (const row of rows) {
    if (attempted >= limit) break;
    if (budgetMs <= 0) break;
    if (clock() - startedAt > budgetMs - TRANSLATE_TAIL_GUARD_MS) break;
    const body = String((row && row.body) || "").trim();
    if (body === "") continue;
    // Ключ от того же текста, что увидит юзер: иначе перевод длинного тела
    // лёг бы в кэш под ключом, которого панель никогда не спросит.
    const key = translationKey(panelBody(body));
    if (translationFor(db, key) !== "") {
      skipped += 1;
      continue;
    }
    attempted += 1;
    const ru = await translateText(body, options);
    if (ru !== "" && rememberTranslation(db, key, ru)) translated += 1;
  }
  return { attempted, translated, skipped };
}

/**
 * Карма и непрочитанные в кэш состояния.
 *
 * Панель не ходит на сайт сама: она читает файл. Значит цифры у неё не новее
 * последнего скана, и единственный честный способ показать их — запомнить
 * вместе со временем, когда их видели. Молча ставить ноль было бы хуже: юзер
 * решил бы, что аккаунт обнулили.
 */
export function rememberHome(db, karma, unread, seenAt) {
  const stmt = db.prepare(HOME_SQL);
  const at = Number(seenAt) > 0 ? String(Math.floor(Number(seenAt))) : String(Date.now());
  let wrote = false;
  const write = (key, value) => {
    if (value === null || value === undefined || Number.isNaN(Number(value))) return;
    stmt.run(key, String(value));
    wrote = true;
  };
  write(PANEL_HOME_KEYS.KARMA, karma);
  write(PANEL_HOME_KEYS.UNREAD, unread);
  // Время ЗАПИСИ этих чисел, а не время вызова тула. Без него карточка подписывает
  // трёхдневную карму как «только что»: снимок-то свежий, а карма в нём старая.
  // Если не записано ничего, время тоже не пишем - иначе подпись «карма посмотрена
  // только что» появилась бы рядом с пустотой.
  if (wrote) stmt.run(PANEL_HOME_KEYS.AT, at);
}

function readHome(db) {
  // Не SELECT * по ticker_state: там лежит repost_seen:* на каждый пост, а нужны
  // три ключа. Таблица не урезается, поэтому её размер растёт с активностью, и
  // полный скан на каждом вызове - это работа, которой никто не просил.
  const rows = db
    .prepare("SELECT key, value FROM ticker_state WHERE key IN (?, ?, ?)")
    .all(PANEL_HOME_KEYS.KARMA, PANEL_HOME_KEYS.UNREAD, PANEL_HOME_KEYS.AT);
  const out = {};
  for (const row of rows) out[String(row.key)] = String(row.value);
  const number = (key) => {
    // Пустая строка - это не ноль, а неизвестность: иначе старая схема оставила бы
    // на карточке выдуманную нулевую карму рядом со свежими счётчиками.
    const raw = out[key];
    if (raw === undefined || raw.trim() === "") return null;
    const value = Number(raw);
    return Number.isFinite(value) ? value : null;
  };
  return {
    karma: number(PANEL_HOME_KEYS.KARMA),
    unread: number(PANEL_HOME_KEYS.UNREAD),
    at: number(PANEL_HOME_KEYS.AT) || 0,
  };
}

/** Пустая панель: нули и честная причина, а не «всё хорошо, просто ничего нет». */
export function panelEmpty(reason) {
  return {
    // at = 0, а не время попытки: снимка нет, поэтому снимок и не датирован.
    // С временем попытки карточка писала "только что" и зелёный ноль, хотя
    // плагин не прочитал ничего (стale => "нет данных", at <= 0).
    at: 0,
    ok: false,
    reason,
    awaiting: 0,
    answered: 0,
    verified: 0,
    reused: 0,
    failed: 0,
    unknown: 0,
    ourPosts: 0,
    repliesToOurs: 0,
    reposts: 0,
upvotes: 0,
    karma: null,
    unread: null,
    // 0 = карму не видели никогда. Иначе карточка подписывает «карма 103» как
    // «только что», хотя это число может лежать в кэше третий день.
    karmaAt: 0,
    pending: [],
  };
}

/**
 * Снимок панели из журнала.
 *
 * Отдельная чистая функция принимает сводку и строки, поэтому числа проверяются
 * обычным тестом без SQLite. `awaiting` приходит отдельным счётом, а не длиной
 * списка: список обрезан, и «сколько ждут ответа» на обрезке стало бы враньём.
 * Нет счёта - длина списка, потому что в тестах списки не обрезаны.
 */
export function panelState(at, summary, pending, counts) {
  const awaiting = counts && counts.awaiting !== undefined ? counts.awaiting : pending && pending.length;
  return Object.assign(panelEmpty(null), {
    ok: true,
    at,
    awaiting: Number(Array.isArray(pending) ? awaiting || 0 : awaiting || 0),
    answered: Number(summary.reportable || 0),
    verified: Number(summary.verified || 0),
    reused: Number(summary.reused || 0),
    failed: Number(summary[COMMENT_STATUS.FAILED] || 0),
    unknown: Number(summary.unknown || 0),
    ourPosts: Number((counts && counts.ourPosts) || 0),
    repliesToOurs: Number((counts && counts.repliesToOurs) || 0),
    reposts: Number((counts && counts.reposts) || 0),
    upvotes: Number((counts && counts.upvotes) || 0),
    pending: Array.isArray(pending) ? pending.slice(0, PANEL_PENDING_LIMIT) : [],
  });
}

/** Снимок панели целиком: журнал плюс то, что лежит рядом с ним. */
export function readPanel(at) {
  const ledger = readLedger();
  if (!ledger.ok) return panelEmpty(ledger.reason);
  let db = null;
  try {
    const { Database } = require("bun:sqlite");
    db = new Database(ledger.path, ledgerOpenOptions(true));
    const ours = MOLTBOOK_NAME;
    const pendingRows = db.prepare(PANEL_SQL.pending).all(ours, PANEL_PENDING_LIMIT);
    // Переводы достаются из кэша, а не просятся у сети: панель открывают часто,
    // а очередь перевода живёт своим темпом в конце скана.
    const cached = new Map();
    for (const row of pendingRows) {
      // Ключ от того же текста, что увидит юзер, - panelBody(), а не сырой body.
      const key = translationKey(panelBody((row && row.body) || ""));
      const ru = translationFor(db, key);
      if (ru !== "") cached.set(key, ru);
    }
    const pending = panelPending(pendingRows, cached);
    const panel = panelState(
      at,
      ledger.comments,
      pending,
      {
        awaiting: panelCount(db, PANEL_SQL.awaiting, ours),
        ourPosts: panelCount(db, PANEL_SQL.ourPosts),
        repliesToOurs: panelCount(db, PANEL_SQL.repliesToOurs, ours),
        reposts: panelCount(db, PANEL_SQL.reposts),
        upvotes: panelCount(db, PANEL_SQL.upvotes),
      },
    );
const home = readHome(db);
    panel.karma = home.karma;
    panel.unread = home.unread;
    panel.karmaAt = home.at;
    return panel;
  } catch (error) {
    return panelEmpty(String((error && error.message) || error));
  } finally {
    if (db) {
      try {
        db.close();
      } catch (_) {
        // закрытие не удалось - файл только читался
      }
    }
  }
}

/**
 * Запись снимка на диск: tmp -> fsync -> rename, как и отчёт.
 *
 * Панель читает файл в произвольный момент, поэтому наполовину записанный
 * снимок хуже отсутствующего: юзер увидит случайные цифры и примет их за правду.
 *
 * Отказ НЕ затирает предыдущий снимок. Транзиентная ошибка (журнал занят
 * сканом) дала бы вместо вчерашних забот карточку из одних тире - то есть
 * хорошие данные исчезли бы из-за того, что чтение не удалось. Старый снимок
 * честнее: у него есть время, и карточка скажет «данные 22:53».
 */
export function writePanel(at, panel) {
  if (!MOLTBOOK_DIR) return null;
  // Снимок, который вызов уже посчитал, переиспользуется: moltbook_status
  // возвращает панель в ответе и не должен ради неё открывать журнал второй раз.
  const snapshot = panel || readPanel(at);
  if (!snapshot || snapshot.ok !== true) return null;
  const target = join(MOLTBOOK_DIR, PANEL_NAME);
  const temp = target + ".tmp";
  let handle = null;
  try {
    mkdirSync(MOLTBOOK_DIR, { recursive: true });
    handle = openSync(temp, "w");
    writeSync(handle, JSON.stringify(snapshot));
    fsyncSync(handle);
    closeSync(handle);
    handle = null;
    renameSync(temp, target);
    return target;
  } catch (_) {
    return null;
  } finally {
    if (handle !== null) {
      try {
        closeSync(handle);
      } catch (_) {
        // уже нечего закрывать
      }
    }
  }
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
    name: "moltbook_publish",
    description:
      "Ответить на комментарий. Перед публикацией ветка перечитывается: если там уже есть " +
      "наш опубликованный ответ - ничего не постится, возвращается reused. Ответ, который сервер " +
      "пометил already_existed, не считается успехом: сверяется родитель, иначе это misparented " +
      "(дедуп у сервера по тексту без родителя). Если сервер требует проверку - возвращается " +
      "challenge с задачей, вызывай moltbook_verify. Поле counted говорит, попал ли ответ в журнал.",
    inputSchema: {
      type: "object",
      properties: {
        post_id: { type: "string", description: "id треда" },
        parent_id: { type: "string", description: "id комментария, на который отвечаем" },
        content: { type: "string", description: "текст ответа, до 900 символов" },
      },
      required: ["post_id", "parent_id", "content"],
      additionalProperties: false,
    },
  },
  {
    name: "moltbook_verify",
    description:
      "Ответить на challenge, который вернул moltbook_publish. Ответ считается зачтённым только " +
      "если сервер подтвердил И наша перечитка ветки это увидела. Иначе unconfirmed - и это честный " +
      "отказ, а не успех.",
    inputSchema: {
      type: "object",
      properties: {
        post_id: { type: "string" },
        parent_id: { type: "string" },
        comment_id: { type: "string" },
        verification_code: { type: "string" },
        answer: { type: "string", description: "число вида 42.00 - ровно два знака после запятой" },
      },
      required: ["post_id", "parent_id", "comment_id", "verification_code", "answer"],
      additionalProperties: false,
    },
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
  {
    name: "moltbook_report",
    description:
      "Собрать MOLTBOOK_REPORT.md по журналу: что реально сделано за окно, что осталось " +
      "непрочитанным и какие проверки отказались подтвердить. Отчёт - это проекция журнала: " +
      "неизвестный исход не считается работой, повторное использование чужого ответа тоже. " +
      "Ничего не публикует и не ходит на сайт. Если отчёт не записался, поле refused скажет почему, " +
      "а refusals в успешном ответе - это число строк-отказов в verifier.log, а не причина.",
    inputSchema: {
      type: "object",
      properties: {
        since: { type: "integer", description: "окно счёта в миллисекундах эпохи; 0 или мусор - за сутки" },
      },
      additionalProperties: false,
    },
  },
];

/**
 * Защёлка на весь вызов тула.
 *
 * Журнал и снимок панели - по одному на плагин, и два параллельных вызова
 * писали бы в одни и те же строки. Поздний из них при этом затирал бы более
 * свежие числа своими, уже устаревшими (оба скана начались в одно время, но
 * приехали в разном порядке), и панель показала бы карму назад по времени.
 * Очередь не даёт вызовам пересекаться; ничего не теряется, вызовы просто
 * ждут друг друга - агент всё равно шлёт их по одному.
 */
let toolChain = Promise.resolve();

export function enqueueTool(task) {
  const next = toolChain.then(task, task);
  toolChain = next.then(
    () => undefined,
    () => undefined,
  );
  return next;
}

async function callTool(name, args) {
  return enqueueTool(async () => {
    // Сквозной предел на весь вызов: очередь вызовов общая, и висящий скан
    // не должен отнимать её у moltbook_status на десяток пределов подряд.
    callDeadlineAt = Date.now() + MOLTBOOK_CALL_BUDGET_MS;
    let result;
    try {
      result = await runTool(name, args);
    } finally {
      callDeadlineAt = 0;
    }
    // Снимок панели переписывается после ЛЮБОГО вызова: панель открывают между
    // тиками, и если бы она обновлялась только на скане, юзер видел бы протухшие
    // цифры с часовой давностью и думал, что агент встал. Вызов, который уже
    // посчитал снимок, отдаёт его сюда, чтобы журнал не открывался дважды.
    writePanel(Date.now(), result && typeof result.panel === "object" ? result.panel : null);
    return result;
  });
}

async function runTool(name, args) {
  if (name === "moltbook_status") return moltbookStatus();
  if (name === "moltbook_scan") return moltbookScan();
  if (name === "moltbook_publish") return moltbookPublish(args || {});
  if (name === "moltbook_verify") return moltbookVerify(args || {});
  if (name === "moltbook_report") return moltbookReport(args || {});
  throw new Error("Unknown tool: " + name);
}

/**
 * Карма и непрочитанные в состояние - для панели.
 *
 * Отдельная попытка вместо записи внутри уже открытого журнала: скан держит
 * транзакцию, а лишняя запись после неё всё равно требует своего открытия.
 * Отказ здесь не важен для работы агента, но он виден в ответе, иначе панель
 * молча показывала бы нули.
 */
export function cacheHome(karma, unread, seenAt) {
  let db = null;
  try {
    const opened = openLedger(false);
    if (!opened.db) return "ledger-missing";
    db = opened.db;
    rememberHome(db, karma, unread, seenAt);
    db.close();
    db = null;
    return null;
  } catch (error) {
    return String((error && error.message) || error);
  } finally {
    if (db) {
      try {
        db.close();
      } catch (_) {
        // закрытие не удалось - запись уже ушла или уже не нужна
      }
    }
  }
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

/**
 * Ждём, пока порт реально освободится, и только потом поднимаем сервер.
 *
 * Зачем: плагин перезапускается вместе с serve (виток RuntimeManager), а его порт
 * 4201 фиксирован. Прошлый процесс мы гасим - и дожидаемся его смерти - но раскрытые
 * им соединения (SSE с keepalive, их держит opencode) уходят в TIME_WAIT, и порт
 * ещё несколько секунд не берётся. Bun.serve не ставит SO_REUSEADDR, поэтому
 * новый процесс падал с EADDRINUSE - и плагин молча выпадал из набора инструментов
 * модели, пока кто-нибудь не посмотрит в лог.
 *
 * Повторов ровно столько, чтобы пережить TIME_WAIT, и НЕ больше: если порт держит
 * живой чужой процесс, это не «занято momentarily», это баг - и мы обязаны упасть
 * громко, а не висеть молча. Пробуем только EADDRINUSE: любая другая ошибка
 * listen - наша собственная, повторять её бессмысленно.
 */
export const MOLTBOOK_BIND_RETRY_DELAYS_MS = [250, 500, 1000, 1500, 2500];

export function isPortBusyError(error) {
  if (error && error.code === "EADDRINUSE") return true;
  // Bun не всегда ставит code (в Bun 1.4.0 на телефоне приходит только текст
  // "Failed to start server. Is port 4201 in use?"), поэтому ловим и формулировку.
  return /EADDRINUSE|address already in use|is port \d+ in use/i.test(
    String((error && error.message) || ""),
  );
}

/**
 * bindHttpServer(options, { delays, create }) - create подменяется в тестах,
 * чтобы не поднимать настоящий сокет. Продлён сигнатурой, а не новым аргументом
 * функции: аргумент options и так несёт порт, а лишний позиционный параметр
 * легко перепутать местами при вызове.
 */
export async function bindHttpServer(
  options,
  { delays = MOLTBOOK_BIND_RETRY_DELAYS_MS, create = (opts) => Bun.serve(opts) } = {},
) {
  let lastError = null;
  for (let attempt = 0; attempt <= delays.length; attempt += 1) {
    try {
      return create(options);
    } catch (error) {
      if (!isPortBusyError(error)) throw error;
      lastError = error;
      const delayMs = delays[attempt];
      if (delayMs === undefined) break;
      console.error(
        "moltbook MCP: порт " +
          options.port +
          " ещё занят (попытка " +
          (attempt + 1) +
          "), жду " +
          delayMs +
          " мс",
      );
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  throw lastError;
}

async function serveHttp() {
  const clients = new Set();
  const server = await bindHttpServer({
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
// await, а не голый вызов: подъём теперь умеет ждать освобождения порта, и без
  // await ошибка EADDRINUSE после исчерпания повторов ушла бы в unhandled rejection
  // вместо падения процесса с внятным кодом.
  await serveHttp();
} else if (process.env.MOLTBOOK_STDIO === "1") {
  // Ручной запуск для отладки. Под тестом (node, без bun) этого не происходит, иначе
  // импорт файла уводил бы процесс в вечное чтение stdin.
  serveStdio();
}
