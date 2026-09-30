// Проверка provenance в настоящем MCP-сервере памяти, на базе, записанной
// СТАРОЙ версией - без колонки provenance.
//
// Почему так: `CREATE TABLE IF NOT EXISTS` на существующей таблице не делает
// ничего. Если бы миграции не было, сервер поднялся бы, все запросы работали
// бы, и агент получал бы записи с пустым источником - то есть с правом
// действовать по чужому совету. Молчание выглядело бы как успех.
//
// Проверяется и вторая, самая частая поломка миграций: повторный запуск.
// Приложение стартует много раз за день, и "duplicate column name" на втором
// запуске означал бы, что память не работает вообще.
//
// ТЕСТ ТРЕБУЕТ BUN, А НЕ NODE
//
// memory.js работает на bun:sqlite, и node этот модуль не умеет вовсе. Но
// build.ps1 jstest гоняет все *.test.mjs через node. Поэтому файл сам
// перезапускается под bun, если его запустили не тем. Молча пропустить эту
// проверку нельзя: она единственная ловит «миграции нет, база выглядит здоровой».
// Если bun не найден - тест падает с внятным объяснением, а не делает вид,
// что проверил.
//
// Run:  bun app/src/test/js/memory-migration.test.mjs

import { spawnSync, spawn } from "node:child_process";
import { mkdtempSync, rmSync, existsSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const SELF = fileURLToPath(import.meta.url);

// ---- Перезапуск под bun ------------------------------------------------------
// Порядок: явная команда в переменной окружения (так устройство запускает
// musl-мост), потом BUN, потом просто "bun" из PATH.
//
// Команда нужна в двух местах: чтобы перезапустить сам тест и чтобы запустить
// memory.js. Раньше для второго использовался process.execPath - на ПК это
// bun.exe, а на устройстве это libldmusl.so, и сервер просто не запустился
// бы. Одна функция на оба случая: на устройстве тест гоняется тем же
// гарнессом, что и остальные, через SELFHARNESS_BUN_CMD.
function bunParts() {
  const raw = process.env.SELFHARNESS_BUN_CMD || process.env.BUN || "bun";
  return raw.split(/\s+/).filter(Boolean);
}

/** Команда запуска bun над скриптом: %s в шаблоне заменяется на путь. */
function bunRun(scriptPath) {
  const parts = bunParts();
  if (parts.some(p => p.includes("%s"))) {
    return { cmd: parts[0], args: parts.slice(1).map(p => p.replace("%s", scriptPath)) };
  }
  return { cmd: parts[0], args: [...parts.slice(1), scriptPath] };
}

if (!process.versions.bun) {
  const self = bunRun(SELF);
  const r = spawnSync(self.cmd, self.args, { stdio: "inherit", env: process.env });
  if (r.error || r.status !== 0) {
    console.log("ПРОВАЛЕНО: этот тест проверяет миграцию SQLite и обязан идти под bun");
    console.log("  не удалось запустить: " + self.cmd + " " + self.args.join(" "));
    console.log("  причина: " + (r.error ? r.error.message : "код выхода " + r.status));
    process.exit(1);
  }
  process.exit(0);
}

// bun:sqlite доступен только отсюда и ниже - до этой строки файл остаётся
// совместимым с node, поэтому и перезапуск выше возможен.
const { Database } = await import("bun:sqlite");

const HERE = dirname(fileURLToPath(import.meta.url));
// Путь к memory.js по умолчанию — от теста в дереве исходников. На устройстве
// раскладка другая (скрипты кладутся рядом с тестом в filesDir), поэтому путь
// переопределяется переменной окружения. Раньше он был жёстко зашит, и на
// устройстве тест падал с "Module not found" на пути из репозитория - то есть
// проверял не память, а свою раскладку.
const MEMORY_JS = process.env.MEMORY_JS_PATH
  || join(HERE, "..", "..", "main", "assets", "mcp", "memory.js");
const PROVENANCE_JS = join(dirname(MEMORY_JS), "provenance.js");

let ok = 0, bad = 0;
const failures = [];
async function test(name, fn) {
  try { await fn(); ok++; console.log("  ok   " + name); }
  catch (e) { bad++; failures.push(name + " :: " + (e && e.message ? e.message : e)); console.log("  FAIL " + name); }
}
function okIf(c, m) { if (!c) throw new Error(m); }
function eq(a, b, w) {
  const x = JSON.stringify(a), y = JSON.stringify(b);
  if (x !== y) throw new Error(`${w}: got ${x}, want ${y}`);
}

const dir = mkdtempSync(join(tmpdir(), "memmig-"));
const DB_PATH = join(dir, "memory.sqlite");

// ---- Шаг 1: база ровно в старом виде -----------------------------------------
function createOldDatabase() {
  const db = new Database(DB_PATH);
  db.exec(`
CREATE TABLE memories (
  id TEXT PRIMARY KEY,
  content TEXT NOT NULL,
  type TEXT DEFAULT 'conversation',
  tags TEXT DEFAULT '',
  project TEXT DEFAULT '',
  created INTEGER
);
CREATE TABLE terms (
  memory_id TEXT, term TEXT, tfidf REAL, project TEXT,
  PRIMARY KEY(memory_id, term)
);
CREATE INDEX IF NOT EXISTS idx_terms_term ON terms(term);
CREATE TABLE graph (
  source TEXT, target TEXT, relation TEXT DEFAULT 'related',
  PRIMARY KEY(source, target, relation)
);
`);
  const ins = db.prepare("INSERT INTO memories(id,content,type,tags,project,created) VALUES(?,?,?,?,?,?)");
  ins.run("mem:old1", "старый урок из лога, источник не записан", "error-solution", "", "opencode-mobile", 1000);
  ins.run("mem:old2", "ещё старый урок без источника", "architecture", "", "opencode-mobile", 2000);
  db.close();
}

// ---- Шаг 2: сервер как дочерний процесс --------------------------------------
// Ответы читаются по строке на каждое сообщение с id. stderr собирается
// отдельно: именно туда MCP пишет запуск, и именно там искалось бы
// "duplicate column name", если бы миграция падала на повторе.
//
// stdin закрывается только после того, как пришли ВСЕ ответы. Раньше он
// закрывался сразу, и bun успевал выйти, не успев поднять readline: ввод
// терялся, ответов было ноль, а код выхода оставался 0 - идеальная тишина,
// которая выглядит как «сервер молчал, но не упал».
//
// Предохранитель по времени нужен, чтобы тест не висел вечно, если сервер
// не ответит совсем: тогда он убьёт процесс и вернёт накопленное, чтобы
// падение показало диагностику, а не таймаут без объяснений.
function runServer(messages) {
  const bun = bunRun(MEMORY_JS);
  return new Promise((resolve, reject) => {
    const child = spawn(bun.cmd, bun.args, {
      env: { ...process.env, MCP_MEMORY_DIR: dir },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let out = "", err = "";
    const pending = new Map();
    let buf = "";
    let got = 0;
    let finished = false;

    const kill = setTimeout(() => {
      if (finished) return;
      finished = true;
      try { child.kill(); } catch (e) { /* уже мёртв */ }
      resolve({ code: -1, out, err: err + "\n[тест] сервер не ответил за 20с", timedOut: true });
    }, 20000);

    child.stdout.on("data", (chunk) => {
      // И в buf для разбора по строкам, и в out для проверок. Раньше шла
      // запись только в buf, и тест «получал» пустой вывод, хотя сервер
      // отвечал: тишина была не ошибкой сервера, а потерянной копией.
      out += chunk.toString();
      buf += chunk.toString();
      let i;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id !== undefined && pending.has(msg.id)) {
          pending.get(msg.id)(msg);
          pending.delete(msg.id);
          got++;
        }
      }
    });
    child.stderr.on("data", (chunk) => { err += chunk.toString(); });
    child.on("error", (e) => { clearTimeout(kill); reject(e); });
    child.on("close", (code) => {
      if (finished) return;
      finished = true;
      clearTimeout(kill);
      resolve({ code, out, err, replies: got });
    });

    let id = 0;
    const needed = [];
    for (const m of messages) {
      const req = { jsonrpc: "2.0", id: ++id, ...m };
      needed.push(new Promise((res) => pending.set(req.id, res)));
      child.stdin.write(JSON.stringify(req) + "\n");
    }
    // Ждём все ответы, затем закрываем stdin: EOF обрывает readline, и
    // процесс завершается сам.
    Promise.all(needed).then(() => {
      setTimeout(() => { try { child.stdin.end(); } catch (e) { /* нечего закрывать */ } }, 150);
    });
  });
}

// Разыменование ответа tools/call: сервер кладёт JSON строкой в content[0].text.
function callResult(msg) {
  const text = msg.result.content[0].text;
  return JSON.parse(text);
}
function isError(msg) { return !!(msg.result && msg.result.isError); }

createOldDatabase();

// Первый запуск: он же выполняет миграцию.
const first = await runServer([
  { method: "initialize", params: { protocolVersion: "2024-11-05" } },
  { method: "tools/call", params: { name: "local_memory_stats", arguments: {} } },
]);

await test("сервер поднялся на старой базе", async () => {
  eq(first.code, 0, "код выхода");
  okIf(!/duplicate column/.test(first.err), "миграция упала: " + first.err.slice(0, 300));
  // Вложенный JSON экранирован, поэтому ищем без кавычек: иначе проверка
  // искала бы экранированную строку в выводе и всегда врала бы.
  okIf(/memories/.test(first.out), "stats не ответили: " + first.out.slice(0, 300));
});

// Второй запуск на той же базе. Это и есть проверка идемпотентности.
const second = await runServer([
  { method: "initialize", params: { protocolVersion: "2024-11-05" } },
  { method: "tools/call", params: { name: "local_memory_stats", arguments: {} } },
]);

await test("повторный запуск не падает на ALTER TABLE", async () => {
  eq(second.code, 0, "код выхода второго запуска");
  okIf(!/duplicate column/.test(second.err),
    "второй запуск упал на повторном ALTER: " + second.err.slice(0, 300));
});

await test("миграция не записала ошибку в migration.log", async () => {
  const logPath = join(dir, "migration.log");
  // Файл читается только если он есть: иначе проверка падала бы с ENOENT и
  // сообщала бы о несуществующем файле вместо «миграция прошла».
  okIf(!existsSync(logPath),
    existsSync(logPath) ? "миграция упала: " + readFileSync(logPath, "utf8") : "");
});

// ---- Шаг 3: форма базы после миграции ---------------------------------------
// Колонка проверяется РАНЬШЕ чтения данных. Иначе отсутствие миграции
// выглядело бы не как «сервер не добавил колонку», а как
// SQLiteError: no such column - и по стектрейсу нельзя было бы понять,
// что виноват сервер, а не запрос в тесте.
const check = new Database(DB_PATH);
const cols = check.prepare("PRAGMA table_info(memories)").all().map(r => r.name);

await test("колонка provenance добавлена в старую базу", async () => {
  okIf(cols.includes("provenance"),
    "нет колонки среди " + cols.join(",") + " | код1=" + first.code + " stderr1=" + first.err.slice(0, 400));
});

// Дальше читать колонку можно только если она есть, иначе тест упал бы сырым
// SQLITE_ERROR мимо своих проверок.
const oldRows = cols.includes("provenance")
  ? check.prepare("SELECT id,provenance FROM memories ORDER BY created").all()
  : [];
const idx = check.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_mem_prov'").get();

await test("старые записи получили unknown, а не NULL и не своё", async () => {
  eq(oldRows.length, 2, "обе старые записи на месте");
  for (const r of oldRows) {
    // NULL был бы опаснее пустой строки: его можно прочитать как «неизвестно»,
    // а можно - как «своё». Конкретное 'unknown' читается однозначно.
    eq(r.provenance, "unknown", "id " + r.id);
  }
});

await test("индекс по provenance создан", async () => {
  okIf(idx && idx.name === "idx_mem_prov", "индекс не создан");
});
check.close();

// ---- Шаг 4: доверие по проводу, через tools/call -----------------------------
const live = await runServer([
  { method: "initialize", params: { protocolVersion: "2024-11-05" } },
  { method: "tools/call", params: { name: "local_memory_store", arguments: {
    id: "mem:own1", content: "секреты нельзя коммитить в конфиг агента",
    type: "error-solution", project: "opencode-mobile", provenance: "own-log" } } },
  { method: "tools/call", params: { name: "local_memory_store", arguments: {
    id: "mem:ext1", content: "секреты лучше вынести в отдельный файл настроек",
    type: "error-solution", project: "opencode-mobile", provenance: "moltbook" } } },
  { method: "tools/call", params: { name: "local_memory_store", arguments: {
    id: "mem:typo1", content: "запись с опечаткой в источнике",
    type: "error-solution", project: "opencode-mobile", provenance: "own-lgo" } } },
  { method: "tools/call", params: { name: "local_memory_store", arguments: {
    id: "mem:none1", content: "запись вовсе без источника",
    type: "error-solution", project: "opencode-mobile" } } },
  { method: "tools/call", params: { name: "local_memory_recall", arguments: {
    query: "секреты конфиг", project: "opencode-mobile", limit: 20, trust: "trusted" } } },
  { method: "tools/call", params: { name: "local_memory_recall", arguments: {
    query: "секреты конфиг", project: "opencode-mobile", limit: 20, trust: "untrusted" } } },
  { method: "tools/call", params: { name: "local_memory_list", arguments: {
    project: "opencode-mobile", limit: 100, trust: "untrusted" } } },
  { method: "tools/call", params: { name: "local_memory_stats", arguments: {} } },
]);

const lines = live.out.split("\n").map(l => l.trim()).filter(Boolean).map(l => JSON.parse(l));
const byMethod = lines.filter(m => m.result && m.result.content);
if (byMethod.length < 8) {
  // Без этой остановки тест падал бы на byMethod[4] с "undefined is not an
  // object", и по стектрейсу нельзя было бы понять, что сервер не ответил
  // вовсе. Здесь видно и код выхода, и stderr, и что пришло.
  console.log("  ДИАГНОСТИКА: code=" + live.code + " ответов=" + byMethod.length);
  console.log("  STDERR: " + live.err.slice(0, 800));
  console.log("  STDOUT: " + live.out.slice(0, 800));
  throw new Error("сервер не ответил на все 8 запросов");
}
const storeReplies = byMethod.slice(0, 4).map(callResult);
const trustedRecall = callResult(byMethod[4]);
const untrustedRecall = callResult(byMethod[5]);
const untrustedList = callResult(byMethod[6]);
const stats = callResult(byMethod[7]);

await test("сервер принял записи без ошибок", async () => {
  eq(live.code, 0, "код выхода");
  eq(storeReplies.length, 4, "ответов на четыре записи");
  for (const r of storeReplies) okIf(r.ok === true, "запись не прошла: " + JSON.stringify(r));
});

await test("store возвращает источник и доверие, а не молчит", async () => {
  eq(storeReplies[0].provenance, "own-log", "свой источник");
  eq(storeReplies[0].trust, "own", "своё доверие");
  eq(storeReplies[0].auto_applyable, true, "своё можно применять");
  eq(storeReplies[1].provenance, "moltbook", "внешний источник");
  eq(storeReplies[1].auto_applyable, false, "внешнее применять нельзя");
});

await test("опечатка в источнике сохраняется как unknown", async () => {
  // Не как «own-lgo»: иначе поле выглядело бы своим, а фильтр по доверию
  // его не видел бы.
  eq(storeReplies[2].provenance, "unknown", "опечатка");
  eq(storeReplies[2].auto_applyable, false, "опечатка не доверенная");
});

await test("запись без источника - unknown и не применяется сама", async () => {
  eq(storeReplies[3].provenance, "unknown", "нет источника");
  eq(storeReplies[3].auto_applyable, false, "нет источника - нельзя");
});

await test("фильтр trusted отдаёт только своё и человека", async () => {
  const ids = (trustedRecall.results || []).map(r => r.id);
  okIf(ids.includes("mem:own1"), "своя запись пропала из trusted");
  okIf(!ids.includes("mem:ext1"), "внешняя запись попала в trusted");
  okIf(!ids.includes("mem:typo1"), "опечатка попала в trusted");
  okIf(!ids.includes("mem:none1"), "запись без источника попала в trusted");
});

await test("фильтр untrusted отдаёт внешнее, а не своё", async () => {
  // Поиск по похожести: сюда попадают только записи, чей текст пересекается
  // с запросом. Старые записи сюда не попадут - они о другом, и их отсутствие
  // здесь не про доверие, а про TF-IDF. Их проверяет list ниже.
  const ids = (untrustedRecall.results || []).map(r => r.id);
  okIf(ids.includes("mem:ext1"), "внешняя запись пропала из untrusted");
  okIf(!ids.includes("mem:own1"), "своя запись попала в untrusted");
});

await test("в ответе recall у каждой записи есть доверие", async () => {
  for (const r of (untrustedRecall.results || [])) {
    okIf(typeof r.provenance === "string", "нет provenance у " + r.id);
    okIf(typeof r.trust === "string", "нет trust у " + r.id);
    okIf(typeof r.auto_applyable === "boolean", "нет auto_applyable у " + r.id);
  }
});

await test("старая запись видна как недоверенная, а не потеряна", async () => {
  // Ключевая проверка миграции на живых данных: запись из базы без колонки
  // обязана быть видна в выдаче как unknown. Если бы она просто исчезла, это
  // выглядело бы как «миграция почистила мусор» - но агент потерял бы уроки.
  const ids = (untrustedList.memories || []).map(r => r.id);
  okIf(ids.includes("mem:old1"), "старая запись исчезла из выдачи совсем");
  okIf(ids.includes("mem:old2"), "вторая старая запись исчезла из выдачи");
  okIf(!ids.includes("mem:own1"), "своя запись попала в untrusted");
  const old = (untrustedList.memories || []).find(r => r.id === "mem:old1");
  eq(old.provenance, "unknown", "источник старой записи");
  eq(old.trust, "unknown", "доверие старой записи");
  eq(old.auto_applyable, false, "старая запись получила права действовать");
});

await test("list возвращает правило применения вместе со строками", async () => {
  // Чтобы вызывающий не мог получить выдачу и забыть, что с ней нельзя
  // делать без человека.
  okIf(typeof untrustedList.apply_rule === "string" && untrustedList.apply_rule.length > 10,
    "list не вернул apply_rule");
});

await test("stats показывает разбивку по доверию", async () => {
  okIf(stats.by_trust, "нет разбивки by_trust");
  eq(stats.by_trust.own, 1, "своих записей");
  eq(stats.by_trust.external, 1, "внешних записей");
  // unknown: две старые записи + опечатка + запись без источника
  eq(stats.by_trust.unknown, 4, "записей без источника");
  eq(stats.by_trust.operator, 0, "записей человека");
  eq(stats.auto_applyable, 1, "применяемых без человека");
  eq(stats.needs_human, 5, "ждущих человека");
  okIf(/не действие/.test(stats.apply_rule), "stats не возвращает правило применения");
});

await test("схема инструмента объявляет provenance", async () => {
  const l = await runServer([
    { method: "initialize", params: { protocolVersion: "2024-11-05" } },
    { method: "tools/list" },
  ]);
  const toolsLine = l.out.split("\n").map(x => x.trim()).filter(Boolean)
    .map(x => JSON.parse(x)).find(m => m.result && m.result.tools);
  okIf(toolsLine, "tools/list не ответил");
  const store = toolsLine.result.tools.find(t => t.name === "local_memory_store");
  const recallTool = toolsLine.result.tools.find(t => t.name === "local_memory_recall");
  okIf(store.inputSchema.properties.provenance,
    "у local_memory_store нет provenance в схеме - агент не узнает, что поле есть");
  okIf(/moltbook/.test(store.inputSchema.properties.provenance.enum.join(",")),
    "в перечне источников нет внешних");
  okIf(recallTool.inputSchema.properties.trust,
    "у local_memory_recall нет фильтра доверия");
});

rmSync(dir, { recursive: true, force: true });
console.log("");
if (bad > 0) {
  console.log("ПРОВАЛЕНО: " + ok + " ок, " + bad + " сломалось");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("зелёное: " + ok + " ок, 0 сломалось");
}