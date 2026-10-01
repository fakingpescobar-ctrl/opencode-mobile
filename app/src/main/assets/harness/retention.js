// Обрезка раздутого журнала событий opencode.
//
// ЗАЧЕМ
// -----
// `event` - append-only журнал изменений, `part` - итоговое состояние.
// Измеренный разрыв на реальной базе (56 МБ, 10925 событий):
// `message.part.updated.1` - 6914 событий и 45.3 МБ payload. Одна сессия
// (`ses_f273ced11ffe`) дала 1913 событий на 79 частей - 37.5 МБ из одного
// стрима на 81 КБ вывода инструмента. Троттлинга в библиотеке opencode нет
// вообще (проверено по исходникам: throttle/debounce/coalesce - ноль
// вхождений), дыру приходится закрывать здесь.
//
// ГЛАВНЫЙ РЫЧАГ - ДЕДУПЛИКАЦИЯ
// ------------------------------
// `part.updated` - промежуточные состояния ОДНОЙ части при стриминге. Итог
// лежит и в таблице `part`, и в ПОСЛЕДНЕМ событии этой части. Промежуточные
// не несут информации, которой нет в итоге: они нужны только клиенту, который
// прямо сейчас стримит. Замер: 6914 событий против 2735 частей - 4179
// избыточных на 41.6 МБ. Ни одна сессия при этом не теряет историю.
//
// ПОЧЕМУ НЕ EXISTS, А `NOT IN (SELECT MAX(seq) ... GROUP BY ...)`
// -----------------------------------------------------------
// Казалось бы, избыточное событие - это то, у которого есть более позднее
// дубль той же части. Форма EXISTS(x.seq > e.seq) на этой базе отдавала
// 4179 на свежем соединении и 6886 на переиспользованном - два РАЗНЫХ ответа
// на неизменяемых данных. План запроса меняется, число гуляет, а цена ошибки
// - безвозвратная потеря журнала. Поэтому берём форму, которая по построению
// равна эталонному GROUP BY: оставляем последний seq каждой группы.
//
// Транзакция с проверкой: если после DELETE число событий не сошлось с
// ожидаемым числом групп - ROLLBACK и ненулевой код выхода. Ошибка в счёте
// не должна превращаться в тихую порчу данных.
//
// ГРУППИРОВКА - ГДЕ ЗДЕСЬ ЛЕГКО СЛОМАТЬ
// ------------------------------------
// `event.seq` уникален только ВНУТРИ `aggregate_id`, то есть внутри сессии.
// Дедуп по одному `part.id` без `aggregate_id` сравнивает seq из разных сессий
// и помечает на удаление чужие события; хуже всего - у жертвы не остаётся
// НИ ОДНОГО события, потому что для её последнего seq тоже «находится» более
// свежий дубль в чужой сессии. На живой базе такой запрос «нашёл» 6913
// избыточных при 2735 частях, то есть почти всё. В живой схеме от этого
// спасает PRIMARY KEY на `part.id`, но гвард остаётся: он ничего не стоит.
//
// `NOT IN` - ЛОВУШКА С NULL
// -------------------------
// Если хоть одно значение в списке `NOT IN` равно NULL, предикат даёт NULL и
// не удаляется НИЧЕГО - тихий no-op вместо обрезки. Поэтому NULL в ключах
// проверяются явно, и при их наличии дедуп отключается целиком.
//
// ЧТО НЕ ДЕЛАЕМ И ПОЧЕМУ
// -----------------------
// * Не режем по возрасту. Возрастной порог срезает тихую сессию на 300 КБ,
//   которую открывают раз в месяц. Режем только по размеру.
// * Не трогаем `event_sequence`. У `event` есть внешний ключ на
//   `event_sequence(aggregate_id)` с ON DELETE CASCADE, и каскад идёт В ЭТУ
//   сторону: удаление строки счётчика снесёт ВСЕ события сессии. Проверено
//   на живой базе `PRAGMA foreign_key_list(event)`. Таблица весит 4 КБ.
// * Не сбиваем `event_sequence.seq`: это последний выданный номер, а не счётчик
//   строк, и opencode берёт его ОТТУДА, а не из MAX(seq) по таблице событий.
//   Пропуски opencode не проверяет - монотонный счётчик переживает пропуск,
//   а сброс дал бы дубли seq у существующих событий.
//
// БЕЗОПАСНОСТЬ
// ------------
// По умолчанию скрипт НИЧЕГО НЕ УДАЛЯЕТ - только считает. Обрезка включается
// флагом --apply. Причина: обрезка необратима.
//
// VACUUM запускается только если обрезка что-то удалила: на 32 МБ он занимает
// ~253 мс, и в ежечасном тике при нулевой обрезке он только вхолостую
// переписывает базу на sdcardfs. Снаружи транзакции - VACUUM нельзя.
//
// WAL растёт отдельно и сам не сжимается, пока не сделан чекпоинт: 23 МБ WAL
// при 34 МБ базы - это треть места, и VACUUM его не трогает. TRUNCATE требует
// отсутствия читателей и при активном читателе возвращает busy=1, НЕ сделав
// ничего. Это «не в этот раз», а не ошибка.
//
// ЗАПУСК
// ------
//   bun retention.js                     расчёт, ничего не удаляет
//   bun retention.js --apply             дедуп + обрезка по размеру + VACUUM
//   bun retention.js --bytes 8388608     другой порог (по умолчанию 3 МБ)

const THRESHOLD_BYTES = 3 * 1024 * 1024;

// Тип события хранится с суффиксом схемы `.1`, который меняется с миграциями
// opencode. Матчим по началу имени: на жёстком равенстве версия с другой
// схемой молча перестала бы чистить.
const PART_UPDATED = "message.part.updated";
const PART_ID = "json_extract(data,'$.part.id')";

// Сколько ждать блокировку, прежде чем признать её «не в этот раз».
// Живой opencode держит соединение с этой же базой.
const BUSY_TIMEOUT_MS = 5000;

// WAL больше половины базы - повод попробовать чекпоинт. Не критерий успеха.
const WAL_RATIO = 0.5;

function argValue(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const dbPath = argValue("--db", process.env.OPENCODE_DB || "");
const threshold = Number(argValue("--bytes", THRESHOLD_BYTES));
const apply = process.argv.includes("--apply");

if (!dbPath) {
  console.error("retention: нужен --db <путь> или OPENCODE_DB в окружении");
  process.exit(2);
}

const { Database } = require("bun:sqlite");
const { existsSync, statSync } = require("node:fs");

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function openWithRetry(tries = 4) {
  let last = null;
  for (let i = 0; i < tries; i++) {
    try {
      const db = new Database(dbPath);
      // busy_timeout обязателен: без него первая же запись в базу при живом
      // сервере падает с SQLITE_BUSY вместо ожидания.
      db.exec(`PRAGMA busy_timeout = ${BUSY_TIMEOUT_MS}`);
      return db;
    } catch (e) {
      last = e;
      if (i + 1 < tries) sleep(800 * (i + 1));
    }
  }
  throw last;
}

let db;
try {
  db = openWithRetry();
} catch (e) {
  // Нет доступа - не повод паять: чаще всего скрипт запустили из run-as,
  // где у процесса нет прав на файл, принадлежащий uid приложения.
  console.error(`retention: не открыть ${dbPath}: ${e.code || e.message}`);
  console.error("retention: если это EACCES - запускай из процесса приложения, а не из run-as.");
  process.exit(3);
}

const mb = (n) => (Number(n || 0) / 1048576).toFixed(2) + " МБ";
const partUpdatedRows = `type LIKE '${PART_UPDATED}%'`;

// Число групп (сессия, часть) - эталон того, сколько событий должно уцелеть.
const GROUPS = `SELECT COUNT(*) AS n FROM (
  SELECT aggregate_id, ${PART_ID} FROM event WHERE ${partUpdatedRows}
  GROUP BY aggregate_id, ${PART_ID})`;

// NULL в ключах делают NOT IN тихим no-op: предикат даёт NULL и не удаляется
// ничего. Лучше дедуп отключить, чем сделать вид, что обрезали.
const nulls = db
  .query(
    `SELECT COUNT(*) AS n FROM (
       SELECT aggregate_id, MAX(seq) AS m FROM event WHERE ${partUpdatedRows}
        GROUP BY aggregate_id, ${PART_ID})
      WHERE aggregate_id IS NULL OR m IS NULL`,
  )
  .get().n;

const before = db
  .query("SELECT COUNT(*) AS n, SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event")
  .get();
const free = db.query("PRAGMA freelist_count").get();

console.log(`retention: база ${dbPath}`);
console.log(
  `retention: порог ${mb(threshold)}, режим ${apply ? "ОБРЕЗКА" : "РАСЧЕТ (ничего не удаляется)"}`,
);
console.log(
  `retention: событий ${before.n}, payload ${mb(before.b)}, свободных страниц ${free.freelist_count}`,
);

const groups = db.query(GROUPS).get().n;
const partUpdated = db.query(`SELECT COUNT(*) AS n FROM event WHERE ${partUpdatedRows}`).get().n;
const redundant = nulls === 0 ? partUpdated - groups : 0;
const dedupBytes =
  nulls === 0
    ? db
        .query(
          `SELECT SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event
            WHERE ${partUpdatedRows} AND (aggregate_id, seq) NOT IN (
              SELECT aggregate_id, MAX(seq) FROM event WHERE ${partUpdatedRows}
               GROUP BY aggregate_id, ${PART_ID})`,
        )
        .get().b
    : 0;

// Сессии под полную обрезку. РАЗМЕР ДО дедупа - чтобы видеть жертв стрима,
// но решение о снятии принимается ПОСЛЕ дедупа. Иначе дедуп бессмыслен:
// сессия на 37 МБ промежуточных состояний после дедупа занимает килобайты, и
// резать её целиком - выстрел в упор в собственное полезное действие.
const THRESHOLD_SQL = `
  SELECT aggregate_id AS id, COUNT(*) AS events,
         SUM(LENGTH(CAST(data AS TEXT))) AS bytes
    FROM event GROUP BY aggregate_id
   HAVING SUM(LENGTH(CAST(data AS TEXT))) > ?`;

// Что сессия будет весить после дедупа: последнее событие каждой части.
const postDedupBytes = (id) =>
  db
    .query(
      `SELECT SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event
        WHERE aggregate_id = ? AND (aggregate_id, seq) IN (
          SELECT aggregate_id, MAX(seq) FROM event WHERE ${partUpdatedRows}
           GROUP BY aggregate_id, ${PART_ID})`,
    )
    .get(id).b;

const bloatedBefore = db.query(THRESHOLD_SQL).all(threshold);
const cut = bloatedBefore
  .map((r) => ({ ...r, after: Number(postDedupBytes(r.id) || 0) }))
  .filter((r) => r.after > threshold);
const cutIds = new Set(cut.map((r) => r.id));

console.log(`retention: дедуп part.updated - избыточных ${redundant} на ${mb(dedupBytes)}`);
if (nulls > 0) console.log(`retention: ВНИМАНИЕ - ${nulls} ключей с NULL, дедуп пропущен`);
console.log(`retention: сессий больше порога до дедупа: ${bloatedBefore.length}`);
for (const row of bloatedBefore) {
  console.log(
    `  ${row.id}  ${mb(row.bytes)} -> после дедупа ${mb(row.after)}  ${
      cutIds.has(row.id) ? "режем" : "дуп ужал, остаётся цела"
    }`,
  );
}

const planned =
  Number(dedupBytes || 0) + cut.reduce((a, r) => a + Number(r.bytes || 0), 0);

if (!apply) {
  console.log(`retention: это был расчет. Повтори с --apply, чтобы освободить до ${mb(planned)} payload.`);
  db.close();
  process.exit(0);
}

let deleted = 0;

// Всё удаление - в одной транзакции с проверкой пост-условия. Ошибка в счёте
// обязана приводить к откату, а не к тихой порче.
db.exec("BEGIN IMMEDIATE");
try {
  if (redundant > 0) {
    const r = db
      .query(
        `DELETE FROM event
          WHERE ${partUpdatedRows} AND (aggregate_id, seq) NOT IN (
            SELECT aggregate_id, MAX(seq) FROM event WHERE ${partUpdatedRows}
             GROUP BY aggregate_id, ${PART_ID})`,
      )
      .run();
    deleted += Number(r.changes || 0);
    console.log(`retention: дедуп удалил ${r.changes || 0} промежуточных событий`);
  }

  // Пост-условие сразу после дедупа: должно остаться ровно по одному событию
  // на группу. Проверять позже нельзя - следующий шаг режет сессии целиком и
  // уменьшает счётчик сам по себе.
  if (redundant > 0) {
    const left = db.query(`SELECT COUNT(*) AS n FROM event WHERE ${partUpdatedRows}`).get().n;
    if (left !== groups) {
      throw new Error(`после дедупа осталось ${left} событий, а групп ${groups} - счёт не сошёлся`);
    }
  }

  // Пересчитываем порог по живым данным ПОСЛЕ дедупа - это тот самый момент,
  // когда размер сессии стал честным.
  for (const row of db.query(THRESHOLD_SQL).all(threshold)) {
    const r = db.query("DELETE FROM event WHERE aggregate_id = ?").run(row.id);
    deleted += Number(r.changes || 0);
    console.log(`retention: обрезана сессия ${row.id} (${r.changes || 0} событий)`);
  }

  db.exec("COMMIT");
} catch (e) {
  try {
    db.exec("ROLLBACK");
  } catch {}
  console.error(`retention: ОТКАТ, ничего не изменено: ${e.message}`);
  db.close();
  process.exit(4);
}

console.log(`retention: удалено событий всего ${deleted}`);

if (deleted > 0) {
  const t0 = Date.now();
  db.exec("VACUUM");
  console.log(`retention: VACUUM за ${Date.now() - t0} мс`);
} else {
  console.log("retention: удалять было нечего, VACUUM пропущен");
}

const dbBytes = existsSync(dbPath) ? statSync(dbPath).size : 0;
const walPath = dbPath + "-wal";
const walBytes = existsSync(walPath) ? statSync(walPath).size : 0;

if (walBytes > dbBytes * WAL_RATIO && walBytes > 0) {
  const t0 = Date.now();
  try {
    const res = db.query("PRAGMA wal_checkpoint(TRUNCATE)").get();
    const after = existsSync(walPath) ? statSync(walPath).size : 0;
    if (Number(res?.busy ?? 0)) {
      console.log(
        `retention: WAL ${mb(walBytes)} больше половины базы, но чекпоинт не прошёл (busy=1) - есть читатели, не в этот раз`,
      );
    } else {
      console.log(`retention: WAL ${mb(walBytes)} -> ${mb(after)} за ${Date.now() - t0} мс`);
    }
  } catch (e) {
    console.log(`retention: чекпоинт WAL не удался (${e.code || e.message}) - не в этот раз`);
  }
} else {
  console.log(`retention: WAL ${mb(walBytes)} при базе ${mb(dbBytes)} - чекпоинт не нужен`);
}

const after = db
  .query("SELECT COUNT(*) AS n, SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event")
  .get();
console.log(
  `retention: осталось событий ${after.n} (было ${before.n}), payload ${mb(after.b)} (было ${mb(before.b)})`,
);
db.close();
