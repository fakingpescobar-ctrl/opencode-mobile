// Tests for the provenance layer: who said it, and may the agent act on it.
//
// WHY THIS FILE EXISTS
// The self-improvement loop reads memory and decides whether to act on a
// lesson by itself. If that decision is wrong in one direction, the loop
// writes someone else's advice into its own code - someone else's advice
// being, say, "move tokens into an open config". This layer is the only
// thing standing between an untrusted suggestion and the agent's hands, so
// the tests attack it rather than admiring it.
//
// The dangerous failure is the quiet one: a default that trusts. Everything
// unknown must land on untrusted, because a database written by an older
// version has no provenance column at all, and "no value" must not read as
// "mine".
//
// Run:  node app/src/test/js/provenance.test.mjs
// No dependencies, no network, no device. Pure functions on purpose - this
// rule must be checkable on the PC, not only inside bun:sqlite on a phone.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import * as os from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
// Путь по умолчанию - от теста в дереве исходников. На устройстве скрипты
// лежат рядом с тестом, поэтому путь переопределяется переменной окружения:
// иначе проверка падала бы не на доверии, а на своём пути импорта.
const PROVENANCE = process.env.PROVENANCE_JS_PATH
  || join(HERE, "..", "..", "main", "assets", "mcp", "provenance.js");
const mod = await import(pathToFileURL(PROVENANCE).href);
const {
  normalizeProvenance, trustOf, autoApplyable, matchesTrust,
  TRUST_OWN, TRUST_OPERATOR, TRUST_EXTERNAL, TRUST_UNKNOWN,
  PROVENANCE_UNKNOWN, KNOWN_PROVENANCE, APPLY_RULE,
} = mod;

let ok = 0;
let bad = 0;
const failures = [];
async function test(name, fn) {
  try {
    await fn();
    ok++;
    console.log("  ok   " + name);
  } catch (e) {
    bad++;
    failures.push(name + " :: " + (e && e.message ? e.message : e));
    console.log("  FAIL " + name);
  }
}
function okIf(cond, msg) { if (!cond) throw new Error(msg); }
function eq(a, b, what) {
  const x = JSON.stringify(a), y = JSON.stringify(b);
  if (x !== y) throw new Error(`${what}: got ${x}, want ${y}`);
}

// ------------------------------------------------------------- normalize

await test("известные источники нормализуются", async () => {
  eq(normalizeProvenance("own-log"), "own-log", "own-log");
  eq(normalizeProvenance("moltbook"), "moltbook", "moltbook");
});

await test("регистр и пробелы не важны", async () => {
  eq(normalizeProvenance("  Own-Log "), "own-log", "пробелы и регистр");
  eq(normalizeProvenance("MOLTBOOK"), "moltbook", "верхний регистр");
});

await test("пустая строка - unknown, а не own", async () => {
  eq(normalizeProvenance(""), PROVENANCE_UNKNOWN, "пустая строка");
  eq(normalizeProvenance("   "), PROVENANCE_UNKNOWN, "только пробелы");
});

await test("undefined и null - unknown", async () => {
  eq(normalizeProvenance(undefined), PROVENANCE_UNKNOWN, "undefined");
  eq(normalizeProvenance(null), PROVENANCE_UNKNOWN, "null");
});

await test("не-строка - unknown, а не падение", async () => {
  eq(normalizeProvenance(42), PROVENANCE_UNKNOWN, "число");
  eq(normalizeProvenance({}), PROVENANCE_UNKNOWN, "объект");
  eq(normalizeProvenance(["own-log"]), PROVENANCE_UNKNOWN, "массив");
});

await test("опечатка в источнике - unknown, а не молчаливое доверие", async () => {
  // Самое коварное место: "own-lgo" почти похож на свой источник. Если бы
  // нормализация оставляла строку как есть, опечатка была бы нераспознанной,
  // но выглядела бы как свой совет.
  eq(normalizeProvenance("own-lgo"), PROVENANCE_UNKNOWN, "опечатка");
  eq(normalizeProvenance("own log"), PROVENANCE_UNKNOWN, "пробел вместо дефиса");
});

await test("слишком длинный источник - unknown, а не обрезанный", async () => {
  // Обрезка выдумала бы источник. Неизвестность честнее.
  eq(normalizeProvenance("a".repeat(200)), PROVENANCE_UNKNOWN, "длинная строка");
});

await test("нормализованный результат - либо известный источник, либо unknown", async () => {
  // Источники - латиница намеренно: это машинные значения в БД, и кириллица
  // в них означала бы, что кто-то придумал свой формат.
  for (const v of ["own-log", "MOLTBOOK", "  operator  ", "", "x".repeat(80), "оператор"]) {
    const n = normalizeProvenance(v);
    eq(KNOWN_PROVENANCE.includes(n) || n === PROVENANCE_UNKNOWN, true,
      "для " + JSON.stringify(v) + " получили " + n);
  }
});

await test("опечатка в источнике не проходит как есть", async () => {
  // Кириллическое «оператор» - это попытка записать источник по-русски.
  // Модуль понимает только машинные значения; всё прочее становится unknown,
  // а не новым источником.
  eq(normalizeProvenance("оператор"), PROVENANCE_UNKNOWN, "кириллица не источник");
});

// ------------------------------------------------------------------ trust

await test("свои источники доверенные", async () => {
  eq(trustOf("own-log"), TRUST_OWN, "own-log");
  eq(trustOf("own-test"), TRUST_OWN, "own-test");
  eq(trustOf("own-runtime"), TRUST_OWN, "own-runtime");
});

await test("источник человека - доверенный, но не свой", async () => {
  eq(trustOf("operator"), TRUST_OPERATOR, "operator");
  eq(trustOf("user"), TRUST_OPERATOR, "user");
});

await test("внешние источники - external", async () => {
  eq(trustOf("moltbook"), TRUST_EXTERNAL, "moltbook");
  eq(trustOf("external-agent"), TRUST_EXTERNAL, "external-agent");
  eq(trustOf("guide"), TRUST_EXTERNAL, "guide");
  eq(trustOf("web"), TRUST_EXTERNAL, "web");
});

await test("неизвестное - unknown, а не own", async () => {
  // Главная проверка. База, записанная старой версией без provenance,
  // прочитается как пустое значение, и это значение обязано быть
  // недоверенным. Иначе чужие советы получат права свои.
  eq(trustOf(undefined), TRUST_UNKNOWN, "undefined");
  eq(trustOf(null), TRUST_UNKNOWN, "null");
  eq(trustOf(""), TRUST_UNKNOWN, "пусто");
  eq(trustOf("own-lgo"), TRUST_UNKNOWN, "опечатка");
});

// ---------------------------------------------------------- auto-applyable

await test("применять самому можно только своё и человека", async () => {
  okIf(autoApplyable("own-log"), "own-log можно");
  okIf(autoApplyable("own-test"), "own-test можно");
  okIf(autoApplyable("own-runtime"), "own-runtime можно");
  okIf(autoApplyable("operator"), "operator можно");
});

await test("внешний совет нельзя применить самому", async () => {
  okIf(!autoApplyable("moltbook"), "молтбук нельзя");
  okIf(!autoApplyable("external-agent"), "чужой агент нельзя");
  okIf(!autoApplyable("guide"), "гайд нельзя");
});

await test("неизвестный источник нельзя применить самому", async () => {
  // Ключевое правило безопасности.
  okIf(!autoApplyable(undefined), "нет источника - нельзя");
  okIf(!autoApplyable(""), "пусто - нельзя");
  okIf(!autoApplyable("own-lgo"), "опечатка - нельзя");
  okIf(!autoApplyable(42), "не-строка - нельзя");
});

await test("внешний источник никогда не становится доверенным через регистр", async () => {
  okIf(!autoApplyable("MOLTBOOK"), "верхний регистр не помогает");
  okIf(!autoApplyable("  MoltBook  "), "пробелы не помогают");
});

// ------------------------------------------------------------ trust filter

await test("пустой фильтр пропускает всё (обратная совместимость)", async () => {
  okIf(matchesTrust("own-log", ""), "своё проходит");
  okIf(matchesTrust("moltbook", ""), "молтбук тоже проходит");
  okIf(matchesTrust(undefined, ""), "неизвестное проходит");
  okIf(matchesTrust("own-log", undefined), "undefined фильтра - всё");
});

await test("фильтр по уровню доверия отбирает верно", async () => {
  okIf(matchesTrust("own-log", TRUST_OWN), "own к own");
  okIf(!matchesTrust("moltbook", TRUST_OWN), "молтбук не к own");
  okIf(matchesTrust("moltbook", TRUST_EXTERNAL), "молтбук к external");
  okIf(matchesTrust(undefined, TRUST_UNKNOWN), "пустое к unknown");
});

await test("фильтр trusted = своё плюс человек", async () => {
  okIf(matchesTrust("own-log", "trusted"), "own-log доверенный");
  okIf(matchesTrust("operator", "trusted"), "operator доверенный");
  okIf(!matchesTrust("moltbook", "trusted"), "молтбук не доверенный");
  okIf(!matchesTrust(undefined, "trusted"), "неизвестное не доверенное");
});

await test("фильтр untrusted — это всё, кроме своего и человека", async () => {
  okIf(matchesTrust("moltbook", "untrusted"), "молтбук недоверенный");
  okIf(matchesTrust(undefined, "untrusted"), "неизвестное недоверенное");
  okIf(!matchesTrust("own-log", "untrusted"), "own-log не недоверенный");
  okIf(!matchesTrust("operator", "untrusted"), "operator не недоверенный");
});

await test("фильтр по конкретному источнику точнее уровня", async () => {
  okIf(matchesTrust("moltbook", "moltbook"), "точное имя источника");
  okIf(!matchesTrust("own-log", "moltbook"), "своё не молтбук");
});

await test("опечатка в фильтре даёт пусто, а не всё", async () => {
  // Тихая выдача лишнего хуже пустого ответа: агент решил бы, что
  // отфильтровал по доверию, а фильтр не сработал бы вовсе.
  okIf(!matchesTrust("own-log", "trsted"), "опечатка фильтра ничего не пропускает");
  okIf(!matchesTrust("moltbook", "trsted"), "и чужое не проходит");
});

// ------------------------------------------------------------- apply rule

await test("правило применения называет доверенные источники", async () => {
  okIf(APPLY_RULE.includes("own-log"), "свой лог назван");
  okIf(APPLY_RULE.includes("provenance"), "provenance назван");
  okIf(/предложение/.test(APPLY_RULE), "внешнее даёт предложение");
});

await test("правило не велит применять молтбук", async () => {
  // Проверка по смыслу, а не по подстрочному совпадению: запрет должен
  // читаться как запрет.
  okIf(/не действие/.test(APPLY_RULE), "внешнее - не действие");
});

// --------------------------------------------------------------- mutation

await test("ПОПЫТКА ОБОЙТИ: убрать unknown из normalize - пусто станет своим", async () => {
  // Мутация доказывает, что тесты смотрят на ту строку, на которую думают.
  // Смысл: если пустое значение перестанет превращаться в unknown,
  // старая база без provenance станет читаться как свои записи.
  const src = readFileSync(PROVENANCE, "utf8");
  const broken = src.replace(
    'if (!s) return PROVENANCE_UNKNOWN;',
    'if (!s) return "own-log";',
  );
  okIf(broken !== src, "мутация не применилась");
  const mutated = join(TMPDIR(), "prov-mut-" + Date.now() + ".mjs");
  writeMut(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  eq(m.normalizeProvenance(""), "own-log", "мутированный код выдаёт свой источник");
  eq(m.autoApplyable(""), true, "и пустое значение становится доверенным");
  rmMut(mutated);
});

await test("ПОПЫТКА ОБОЙТИ: сделать external доверенным", async () => {
  const src = readFileSync(PROVENANCE, "utf8");
  const broken = src.replace(
    "return t === TRUST_OWN || t === TRUST_OPERATOR;",
    "return true;",
  );
  okIf(broken !== src, "мутация не применилась");
  const mutated = join(TMPDIR(), "prov-mut2-" + Date.now() + ".mjs");
  writeMut(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  eq(m.autoApplyable("moltbook"), true, "мутированный код доверяет молтбуку");
  rmMut(mutated);
});

await test("ПОПЫТКА ОБОЙТИ: опечатка проходит как есть", async () => {
  const src = readFileSync(PROVENANCE, "utf8");
  const broken = src.replace(
    "if (!KNOWN_PROVENANCE.includes(s)) return PROVENANCE_UNKNOWN;",
    "",
  );
  okIf(broken !== src, "мутация не применилась");
  const mutated = join(TMPDIR(), "prov-mut3-" + Date.now() + ".mjs");
  writeMut(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  eq(m.normalizeProvenance("own-lgo"), "own-lgo", "мутированный код оставляет опечатку");
  rmMut(mutated);
});

// Временный каталог для мутаций. На Android /tmp нет, поэтому берётся
// переменная окружения, затем os.tmpdir(), и лишь потом каталог рядом.
function TMPDIR() {
  const fromEnv = process.env.SELFHARNESS_TEST_TMP;
  if (fromEnv) return fromEnv;
  try {
    mkdirSync(join(os.tmpdir(), "prov-probe-"), { recursive: true });
    return os.tmpdir();
  } catch (e) {
    return HERE;
  }
}
function writeMut(path, content) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}
function rmMut(path) {
  try { rmSync(path, { force: true }); } catch (e) { /* нечего удалять */ }
}

console.log("");
if (bad > 0) {
  console.log("ПРОВАЛЕНО: " + ok + " ок, " + bad + " сломалось");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("зелёное: " + ok + " ок, 0 сломалось");
}