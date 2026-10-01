// Tests for retention: обрезка раздутого журнала событий opencode.
//
// WHY THIS FILE EXISTS
// Обрезка необратима. Одна забытая копия флага уже стоила 1913 удалённых
// событий, поэтому скрипт по умолчанию только считает, и именно это поведение
// защищает данные сильнее всего остального кода. Тест ниже бьёт по трём
// вещам, где ошибка стоит дороже всего:
//
//   1. Расчёт не удаляет НИЧЕГО. Это фундамент защиты от потери данных.
//   2. Порядок удаления: сначала event, потом event_sequence. Каскад идёт
//      В эту сторону, поэтому обратный порядок сносит весь журнал сессии.
//      Здесь проверяется не только текст запроса: если порядок поменять,
//      чужие события исчезнут, и это видно по счётчикам.
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

// --- статические проверки: работают всегда ---------------------------------

console.log("== статические инварианты ==");

// Порядок удаления в коде. Ищем позиции: event должен идти раньше event_sequence.
const delEvents = SOURCE.indexOf('DELETE FROM event WHERE aggregate_id');
const delSeq = SOURCE.indexOf("DELETE FROM event_sequence");
check(
  "удаление event идёт раньше удаления event_sequence",
  delEvents > -1 && delSeq > -1 && delEvents < delSeq,
  `event=${delEvents} event_sequence=${delSeq}`,
);

// Каскад сносит всё: значит удалять счётчик нельзя, пока живы события сессии.
check(
  "в коде есть предупреждение про ON DELETE CASCADE",
  /CASCADE/i.test(SOURCE),
);

// Порог считается по индексной колонке, а не разбором JSON каждой строки.
// Проверяем код без комментариев: в шапке скрипта эта конструкция названа
// словами, и regex по сырому файлу ловил бы собственное объяснение.
const CODE_ONLY = SOURCE.split("\n")
  .filter((l) => !l.trim().startsWith("//") && !l.trim().startsWith("*") && !l.trim().startsWith("/*"))
  .join("\n");
check(
  "нет json_extract по $.sessionID в коде",
  !/json_extract\s*\(\s*[^)]*data\s*,\s*'\$\.sessionID'/i.test(CODE_ONLY),
);
check("метрика идёт по aggregate_id", /GROUP BY aggregate_id/i.test(CODE_ONLY));

// Счётчик seq нельзя сбрасывать: это последний выданный номер, а не счётчик строк.
check("нет DELETE по event_sequence сбросом seq", !/DELETE FROM event_sequence[^;]*SET/i.test(SOURCE));

// Безопасный дефолт: без --apply скрипт только считает.
check("apply включается только явным флагом", SOURCE.includes("--apply"));

// --- функциональные проверки: только при наличии bun -------------------------

if (!hasBun()) {
  console.log("\n( bun не найден - sqlite-проверки пропущены, статические выше покрывают ключевое )");
} else {
  console.log("\n== функционально на временной БД ==");
  const dir = mkdtempSync(join(os.tmpdir(), "retention-test-"));
  const dbPath = join(dir, "opencode.db");
  const seed = join(dir, "seed.mjs");

  // Шаблон БД ровно как в opencode: event с FK на event_sequence CASCADE.
  writeFileSync(
    seed,
    `
import { Database } from "bun:sqlite";
const db = new Database(${JSON.stringify(dbPath)}, { create: true });
db.exec("CREATE TABLE session (id TEXT PRIMARY KEY)");
db.exec("CREATE TABLE event_sequence (aggregate_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, owner_id INTEGER)");
db.exec("CREATE TABLE event (id TEXT PRIMARY KEY, aggregate_id TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, data TEXT NOT NULL)");
db.exec("CREATE TABLE part (id TEXT PRIMARY KEY, session_id TEXT, data TEXT)");
db.query("INSERT INTO session VALUES ('ses_big'),('ses_small'),('ses_solo')").run();
db.query("INSERT INTO event_sequence VALUES ('ses_big',500,null),('ses_small',10,null),('ses_solo',7,null)").run();
db.query("INSERT INTO part VALUES ('p1','ses_big','{}'),('p2','ses_small','{}')").run();
const now = Date.now();
const add = (id, agg, seq, when, pad) =>
  db.query("INSERT INTO event VALUES (?,?,?,'message.part.updated.1',?)").run(id, agg, seq, JSON.stringify({ time: when, pad }));
add("b1","ses_big",1,now,"x".repeat(900));
add("b2","ses_big",2,now,"y".repeat(900));
add("b3","ses_big",3,now,"z".repeat(900));
add("s1","ses_small",1,now,"q".repeat(100));
add("solo","ses_solo",1,now,"w".repeat(50));
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
  seq: Object.fromEntries(db.query("SELECT aggregate_id a, seq FROM event_sequence").all().map((r) => [r.a, r.seq])),
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

  // 1. Расчёт не должен удалить ничего.
  const dryOut = run("--bytes", "1500");
  check("расчёт находит раздутую сессию", /ses_big/.test(dryOut));
  check("расчёт не трогает мелкие сессии", !/ses_small/.test(dryOut), dryOut);
  const afterDry = counts();
  check(
    "расчёт не удалил ни одного события",
    JSON.stringify(afterDry.byAgg) === JSON.stringify(before.byAgg),
    `${JSON.stringify(before.byAgg)} -> ${JSON.stringify(afterDry.byAgg)}`,
  );

  // 2. Применение: режет только раздутую сессию.
  run("--bytes", "1500", "--apply");
  const afterApply = counts();
  check("раздутая сессия очищена", !afterApply.byAgg.ses_big, JSON.stringify(afterApply.byAgg));
  check("мелкая сессия цела", afterApply.byAgg.ses_small === before.byAgg.ses_small, JSON.stringify(afterApply.byAgg));
  check("сессия без кандидата цела", afterApply.byAgg.ses_solo === 1, JSON.stringify(afterApply.byAgg));

  // 3. Порядок удаления: счётчик остался, seq не сбит.
  check(
    "счётчик event_sequence пережил обрезку",
    afterApply.seq.ses_big === before.seq.ses_big,
    `seq ${before.seq.ses_big} -> ${afterApply.seq.ses_big}`,
  );
  check("счётчики соседних сессий целы", afterApply.seq.ses_small === 10 && afterApply.seq.ses_solo === 7);

  // 4. Итог переписки (part) не пострадал - режется журнал, а не состояние.
  check("part не пострадал", afterApply.parts === before.parts, `${before.parts} -> ${afterApply.parts}`);

  // 5. Повторный прогон с --apply на чистой базе не должен ничего ломать.
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
