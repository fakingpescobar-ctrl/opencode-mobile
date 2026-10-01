// Обрезка раздутого журнала событий opencode.
//
// ЗАЧЕМ
// -----
// `event` — append-only журнал изменений, а `part` — итоговое состояние.
// Журнал втрое больше итога, и разница копится с каждым стримом: один вывод
// инструмента на 81 КБ при стриминге дал 1837 обновлений и 37.5 МБ, то есть
// 58% всей базы за одну сессию. База растёт на 38 МБ в сутки, и переносом
// каталогов это не лечится: задержка sdcardfs против ext4 - 1.45x на
// коммите, не на порядок.
//
// ЧТО ДЕЛАЕМ
// ----------
// Порог по размеру, а не по возрасту. Замер: вся база моложе 7 дней, порог
// "старше 3 дней" съедает 32% и роста не касается - растёт активная сессия.
// Возраст оставлен как страховка, основной триггер - payload.
//
// ПОРЯДОК УДАЛЕНИЯ - НЕ МЕНЯТЬ
// -----------------------------
// У `event` есть внешний ключ на `event_sequence(aggregate_id)` с
// ON DELETE CASCADE, и каскад идёт В ЭТУ СТОРОНУ. То есть удаление строки
// из `event_sequence` каскадом снесёт ВСЕ события сессии. Поэтому сначала
// удаляются события, и только потом - осиротевшие записи счётчика. Обратный
// порядок уничтожает журнал.
//
// `event_sequence.seq` - это последний выданный номер, а не счётчик строк
// (проверено: max(event.seq) == event_sequence.seq для всех сессий). Сбивать
// его нельзя: новая нумерация даст дубли seq у существующих событий.
//
// БЕЗОПАСНОСТЬ
// ------------
// По умолчанию скрипт НИЧЕГО НЕ УДАЛЯЕТ - только считает и печатает. Обрезка
// включается флагом --apply. Причина: обрезка необратима, а одна забытая
// копия флага уже стоила 1913 удалённых событий.
//
// VACUUM запускается только если обрезка что-то удалила: на 32 МБ он занимает
// ~253 мс, и в ежечасном тике при нулевой обрезке он будет только вхолостую
// переписывать базу на sdcardfs.
//
// ЗАПУСК
// ------
//   bun retention.js            расчёт, ничего не удаляет
//   bun retention.js --apply    обрезка + VACUUM, если было что удалять
//   bun retention.js --bytes 5242880   другой порог (по умолчанию 3 МБ)

const THRESHOLD_BYTES = 3 * 1024 * 1024;
const AGE_FLOOR_DAYS = 7;

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
const db = new Database(dbPath);

// Сессии-кандидаты на обрезку: payload журнала больше порога.
// Считаем по aggregate_id, а не по json_extract(data,'$.sessionID'):
// значения идентичны (проверено, 0 расхождений), но aggregate_id индексирован
// и не требует разбора JSON каждой строки.
const bloated = db
  .query(
    `SELECT aggregate_id AS id,
            COUNT(*) AS events,
            SUM(LENGTH(CAST(data AS TEXT))) AS bytes
       FROM event
      GROUP BY aggregate_id
     HAVING SUM(LENGTH(CAST(data AS TEXT))) > ?`,
  )
  .all(threshold);

// Страховка по возрасту: одна сессия старше AGE_FLOOR_DAYS - обрезаем
// независимо от размера, иначе база вырастет за счёт тихих старых чатов.
const old = db
  .query(
    `SELECT e.aggregate_id AS id,
            COUNT(*) AS events,
            SUM(LENGTH(CAST(e.data AS TEXT))) AS bytes
       FROM event e
      GROUP BY e.aggregate_id
     HAVING MAX(COALESCE(
           json_extract(e.data, '$.time'),
           json_extract(e.data, '$.info.time.created'),
           json_extract(e.data, '$.info.time.updated'))) < ?`,
  )
  .all(Date.now() - AGE_FLOOR_DAYS * 86400000);

const targets = new Map();
for (const row of [...bloated, ...old]) targets.set(row.id, row);

const before = db.query("SELECT COUNT(*) AS n, SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event").get();
const free = db.query("PRAGMA freelist_count").get();
const pageSize = db.query("PRAGMA page_size").get();

const mb = (n) => (n / 1048576).toFixed(2) + " МБ";

console.log(`retention: база ${dbPath}`);
console.log(`retention: порог ${mb(threshold)}, режим ${apply ? "ОБРЕЗКА" : "РАСЧЕТ (ничего не удаляется)"}`);
console.log(`retention: событий ${before.n}, payload ${mb(before.b)}, свободных страниц ${free.freelist_count}`);

if (targets.size === 0) {
  console.log("retention: кандидатов нет, база в порядке");
  db.close();
  process.exit(0);
}

let planned = 0;
for (const [id, row] of targets) {
  const why = bloated.some((b) => b.id === id) ? "размер" : "возраст";
  console.log(`  ${id}  событий ${row.events}  ${mb(row.bytes)}  (${why})`);
  planned += row.bytes;
}
console.log(`retention: под обрезку ${targets.size} сессий, освободится до ${mb(planned)} payload`);

if (!apply) {
  console.log("retention: это был расчет. Повтори с --apply, чтобы удалить.");
  db.close();
  process.exit(0);
}

// Порядок критичен, см. комментарий в шапке: сначала event, потом event_sequence.
let deleted = 0;
for (const [id] of targets) {
  const r = db.query("DELETE FROM event WHERE aggregate_id = ?").run(id);
  deleted += Number(r.changes || 0);
}
// Осиротевшие счётчики: их вес ничтожен (4 КБ на 33 строки), но счётчик -
// состояние, которое opencode читает на каждой вставке, и разъехавшийся счётчик
// диагностировать с телефона потом будешь долго.
const orphans = db
  .query("DELETE FROM event_sequence WHERE aggregate_id NOT IN (SELECT id FROM session)")
  .run();

console.log(`retention: удалено событий ${deleted}, записей счетчика ${orphans.changes || 0}`);

if (deleted > 0) {
  const t0 = Date.now();
  db.exec("VACUUM");
  console.log(`retention: VACUUM за ${Date.now() - t0} мс`);
} else {
  console.log("retention: удалять было нечего, VACUUM пропущен");
}

const after = db.query("SELECT COUNT(*) AS n, SUM(LENGTH(CAST(data AS TEXT))) AS b FROM event").get();
console.log(`retention: осталось событий ${after.n}, payload ${mb(after.b)}`);
db.close();
