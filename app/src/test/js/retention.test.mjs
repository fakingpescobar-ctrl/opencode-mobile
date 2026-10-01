// Tests for retention: обрезка раздутого журнала событий opencode.
//
// WHY THIS FILE EXISTS
// Обрезка необратима. Одна забытая копия флага уже стоила 1913 удалённых
// событий, поэтому скрипт по умолчанию только считает, и именно это поведение
// защищает данные сильнее всего остального кода.
//
// ГЛАВНАЯ ЛОВУШКА - ЭТОТ ФАЙЛ
// ----------------------------
// `event.seq` уникален только ВНУТРИ `aggregate_id`, то есть внутри сессии.
// Дедупликация, которая группирует по одному `part.id` без `aggregate_id`,
// сравнивает seq из РАЗНЫХ сессий между собой - и помечает на удаление чужие
// события. Хуже всего, что страдает не «лишнее»: если у соседней сессии
// номера выше, то у жертвы не остаётся ни одного события вообще, потому что
// для её последнего seq тоже находится «более свежий» дубль в чужой сессии.
// На реальной базе такой запрос «нашёл» 6913 избыточных при 2735 частях -
// то есть почти всё. Тест `сессия с общим part.id не теряет события
// полностью` ловит именно это.
//
// Run:  node app/src/test/js/retention.test.mjs   (bun нужен для sqlite-части)
//       без bun откатывается на статические проверки скрипта.

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { mkdtempSync, rmSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import * as os from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const RETENTION = join(HERE, "..", "..", "main", "assets", "harness", "retention.js");
const SOURCE = readFileSync(RETENTION, "utf8");

let ok = 0;
let bad = 0;
const failures = [];

function check(name, cond, extra = "") {
  if (cond) {
    ok++;
    console.log(`  ok   ${name}`);
  } else {
    bad++;
    failures.push(name);
    console.log(`  FAIL ${name}${extra ? ` -- ${extra}` : ""}`);
  }
}

function hasBun() {
  try {
    execFileSync("bun", ["--version"], { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

// Код без комментариев: в шапке скрипта эти же конструкции описаны словами,
// и regex по сырому файлу ловил бы собственное объяснение.
const CODE_ONLY = SOURCE.split("\n")
  .filter((l) => !l.trim().startsWith("//") && !l.trim().startsWith("*") && !l.trim().startsWith("/*"))
  .join("\n");

// --- статические инварианты: работают всегда ---------------------------------

console.log("== статические инварианты ==");

// ГЛАВНОЕ. `event` ссылается на `event_sequence(aggregate_id)` с
// ON DELETE CASCADE, и каскад идёт В эту сторону: удаление строки счётчика
// снесёт ВСЕ события сессии. Таблица весит 4 КБ - выигрыш не окупает риск,
// поэтому скрипт не должен содержать удаления event_sequence ВООБЩЕ.
check(
  "скрипт не удаляет event_sequence нигде",
  !/DELETE\s+FROM\s+event_sequence/i.test(CODE_ONLY),
);
check("в коде есть предупреждение про ON DELETE CASCADE", /CASCADE/i.test(SOURCE));

// seq - последний выданный номер, а не счётчик строк. Сброс дал бы дубли seq
// у уже существующих событий.
check(
  "нет UPDATE/DELETE по event_sequence",
  !/(UPDATE|DELETE)\s+FROM\s+event_sequence/i.test(CODE_ONLY),
);

// Дедуп обязан группировать по паре (aggregate_id, part.id). Форма - NOT IN с
// MAX(seq): она по построению равна эталонному GROUP BY. EXISTS(x.seq > e.seq)
// запрещён: на неизменяемых данных эта база отдавала то 4179, то 6886.
const dedupCode = CODE_ONLY.slice(CODE_ONLY.indexOf("const GROUPS"));
check(
  "дедуп оставляет последний seq группы (NOT IN + MAX)",
  /NOT IN \(\s*SELECT aggregate_id,\s*MAX\(seq\)/i.test(dedupCode),
);
check("группировка идёт по aggregate_id и part.id", /GROUP BY aggregate_id/i.test(dedupCode));
check(
  "нестабильная EXISTS-форма дедупа не используется",
  !/x\.seq\s*>\s*(e|event)\.seq/i.test(CODE_ONLY),
);
check("дедуп использует part.id из JSON", /json_extract\s*\(\s*[^)]*data\s*,\s*'\$\.part\.id'/i.test(CODE_ONLY));

// Обрезка необратима, поэтому ошибка в счёте обязана приводить к откату, а не к
// тихой порче: транзакция плюс проверка, что уцелело ровно столько, сколько групп.
check("удаление идёт в транзакции", /BEGIN IMMEDIATE/.test(CODE_ONLY));
check("есть откат при несошедшемся счёте", /ROLLBACK/.test(CODE_ONLY));
check("счёт сверяется с числом групп", /left\s*!==\s*groups/.test(CODE_ONLY));
check("NULL в ключах NOT IN проверяется", /NULL/.test(dedupCode));

// Порог считается по индексной колонке, а не разбором JSON каждой строки.
check(
  "нет json_extract по $.sessionID в коде",
  !/json_extract\s*\(\s*[^)]*data\s*,\s*'\$\.sessionID'/i.test(CODE_ONLY),
);
check("метрика идёт по aggregate_id", /GROUP BY aggregate_id/i.test(CODE_ONLY));

// Возрастной порог срезает тихую сессию на 300 КБ, которую открывают раз в
// месяц. Режем только по размеру - значит возраста в коде быть не должно.
check(
  "нет возрастного порога",
  !/julianday|'-\s*7\s*days'|ageDays|MAX_AGE/i.test(CODE_ONLY),
);

// Событие хранится с суффиксом схемы `.1`, который меняется с миграциями
// opencode. Точное равенство молча отключило бы чистку после обновления.
check("тип события матчится по началу имени", /LIKE\s+'message\.part\.updated%'/.test(CODE_ONLY.replace(/\$\{PART_UPDATED\}/g, "message.part.updated")));

// busy - это «не в этот раз», а не ошибка: иначе ежечасный тик будет ругаться.
check("чекпоинт WAL трактует busy как пропуск", /busy/.test(CODE_ONLY));
check("выставлен busy_timeout", /busy_timeout/.test(CODE_ONLY));

// Безопасный дефолт: без --apply скрипт только считает.
check("apply включается только явным флагом", SOURCE.includes("--apply"));
check("VACUUM только после фактического удаления", /if \(deleted > 0\)/.test(CODE_ONLY));

// --- функциональные проверки: только при наличии bun -------------------------

if (!hasBun()) {
  console.log("\n( bun не найден - sqlite-проверки пропущены, статические выше покрывают ключевое )");
} else {
  console.log("\n== функционально на временной БД ==");
  const dir = mkdtempSync(join(os.tmpdir(), "retention-test-"));
  const dbPath = join(dir, "opencode.db");
  const seed = join(dir, "seed.mjs");

  // Шаблон БД ровно как в opencode: event с FK на event_sequence CASCADE.
  // ses_low / ses_high делят ОДИН part.id, но seq у ses_high намеренно выше -
  // это ловушка для дедупа без aggregate_id.
  writeFileSync(
    seed,
    `
import { Database } from "bun:sqlite";
const db = new Database(${JSON.stringify(dbPath)}, { create: true });
db.exec("CREATE TABLE session (id TEXT PRIMARY KEY)");
db.exec("CREATE TABLE event_sequence (aggregate_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, owner_id INTEGER)");
db.exec("CREATE TABLE event (id TEXT PRIMARY KEY, aggregate_id TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, data TEXT NOT NULL)");
// part В ТЕСТЕ без PK по id: в живой схеме id - PRIMARY KEY, там коллизия
// невозможна. Сняв его, мы проверяем именно гвард aggregate_id - на живой
// базе он страховка, и тест должен доказывать, что страховка работает.
db.exec("CREATE TABLE part (id TEXT, session_id TEXT, data TEXT)");
db.query("INSERT INTO session VALUES ('ses_low'),('ses_high'),('ses_dup'),('ses_pad')").run();
db.query("INSERT INTO event_sequence VALUES ('ses_low',3,null),('ses_high',60,null),('ses_dup',5,null),('ses_pad',1,null)").run();
db.query("INSERT INTO part VALUES ('shared','ses_low','{}'),('shared','ses_high','{}'),('dupA','ses_dup','{}')").run();
const now = Date.now();
// payload реального события: sessionID / part.id / time
const add = (id, agg, seq, partId, pad) =>
  db.query("INSERT INTO event VALUES (?,?,?,'message.part.updated.1',?)")
    .run(id, agg, seq, JSON.stringify({ sessionID: agg, part: { id: partId }, time: now, pad }));
// одна и та же часть, стримится 5 раз - должно остаться 1 событие
for (let i = 1; i <= 5; i++) add("d" + i, "ses_dup", i, "dupA", "z".repeat(50));
// ЛОВУШКА: ОДИН И ТОТ ЖЕ part.id у двух сессий, причём у ses_high seq выше.
// Дедуп без aggregate_id удалит у ses_low ВСЕ три события: для её последнего
// seq тоже «найдётся» более свежий дубль в чужой сессии.
add("lo1","ses_low",1,"shared","a");
add("lo2","ses_low",2,"shared","a");
add("lo3","ses_low",3,"shared","a");
add("hi1","ses_high",50,"shared","b");
add("hi2","ses_high",60,"shared","b");
// раздутая сессия под порог обрезки
add("pad1","ses_pad",1,"padP","p".repeat(2000));
db.close();
`,
  );
  execFileSync("bun", [seed], { stdio: "ignore" });

  const probe = join(dir, "probe.mjs");
  const counts = () => {
    writeFileSync(
      probe,
      `
import { Database } from "bun:sqlite";
const db = new Database(${JSON.stringify(dbPath)}, { readonly: true });
const out = {
  byAgg: Object.fromEntries(db.query("SELECT aggregate_id a, count(*) n FROM event GROUP BY aggregate_id").all().map((r) => [r.a, r.n])),
  maxSeq: Object.fromEntries(db.query("SELECT aggregate_id a, max(seq) m FROM event GROUP BY aggregate_id").all().map((r) => [r.a, r.m])),
  seqCounter: Object.fromEntries(db.query("SELECT aggregate_id a, seq FROM event_sequence").all().map((r) => [r.a, r.seq])),
  parts: db.query("SELECT count(*) n FROM part").get().n,
};
db.close();
console.log(JSON.stringify(out));
`,
    );
    return JSON.parse(execFileSync("bun", [probe], { encoding: "utf8" }).trim());
  };

  const run = (...args) => execFileSync("bun", [RETENTION, "--db", dbPath, ...args], { encoding: "utf8" });

  const before = counts();
  check("наполнение прошло", before.byAgg.ses_dup === 5, JSON.stringify(before.byAgg));

  // 1. Расчёт не должен удалить ничего.
  const dryOut = run("--bytes", "1500");
  check("расчёт находит раздутую сессию", /ses_pad/.test(dryOut));
  check("расчёт не трогает мелкие сессии", !/ses_small|ses_low/.test(dryOut), dryOut);
  check("расчёт показывает дедуп-кандидатов", /дедуп part\.updated/.test(dryOut));
  const afterDry = counts();
  check(
    "расчёт не удалил ни одного события",
    JSON.stringify(afterDry.byAgg) === JSON.stringify(before.byAgg),
    `${JSON.stringify(before.byAgg)} -> ${JSON.stringify(afterDry.byAgg)}`,
  );

  // 2. Применение: дедуп + обрезка по размеру.
  run("--bytes", "1500", "--apply");
  const afterApply = counts();

  // Дедуп: 5 событий одной части -> 1 (последнее).
  check("дедуп оставил последнее событие части", afterApply.byAgg.ses_dup === 1, JSON.stringify(afterApply.byAgg));
  check(
    "дедуп оставил именно последний seq",
    afterApply.maxSeq.ses_dup === 5,
    `maxSeq=${afterApply.maxSeq.ses_dup}`,
  );

  // ЛОВУШКА: сессии делят part.id, у ses_high seq выше. Обе обязаны уцелеть.
  check(
    "сессия с общим part.id не теряет события полностью",
    afterApply.byAgg.ses_low === 1 && afterApply.byAgg.ses_high === 1,
    `ses_low=${afterApply.byAgg.ses_low} ses_high=${afterApply.byAgg.ses_high} - дедуп сравнивает seq между сессиями`,
  );
  check(
    "у каждой сессии остался её собственный последний seq",
    afterApply.maxSeq.ses_low === 3 && afterApply.maxSeq.ses_high === 60,
    `low=${afterApply.maxSeq.ses_low} high=${afterApply.maxSeq.ses_high}`,
  );

  // Обрезка по размеру: раздутая сессия уходит, дедуп её не спас.
  check("раздутая сессия обрезана", !afterApply.byAgg.ses_pad, JSON.stringify(afterApply.byAgg));

  // Счётчики opencode неприкосновенны.
  check(
    "счётчик event_sequence пережил обрезку",
    JSON.stringify(afterApply.seqCounter) === JSON.stringify(before.seqCounter),
    `${JSON.stringify(before.seqCounter)} -> ${JSON.stringify(afterApply.seqCounter)}`,
  );

  // Итог переписки (part) не пострадал - режется журнал, а не состояние.
  check("part не пострадал", afterApply.parts === before.parts, `${before.parts} -> ${afterApply.parts}`);

  // 3. Повторный прогон с --apply на чистой базе не должен ничего ломать.
  run("--bytes", "1500", "--apply");
  const second = counts();
  check(
    "повторная обрезка идемпотентна",
    JSON.stringify(second.byAgg) === JSON.stringify(afterApply.byAgg) && second.parts === before.parts,
  );

  rmSync(dir, { recursive: true, force: true });
}

console.log(`\nзелёное: ${ok} ок, ${bad} сломалось`);
if (bad > 0) {
  console.log("сломано:");
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
if (!existsSync(RETENTION)) {
  console.log("нет файла retention.js - тест бессмысленен");
  process.exit(1);
}
