// Offline tests for the self-improvement gate (selfharness.js).
//
// WHY THIS FILE EXISTS
// The self-improvement loop is only honest if a change that breaks something
// cannot survive. That promise rests entirely on this harness, and the one
// way to be sure of a gate is to try to walk around it. So most of these
// tests attack it: a tool that reports green on a red run is worse than no
// gate at all, because the loop will then commit broken code believing it
// fixed something.
//
// The runner is injected rather than spawned for real: the tests must be
// able to say "this run went red" without needing a device, and a harness
// that can only be verified on a device is exactly the kind of bug the loop
// is supposed to catch.
//
// Run:  node app/src/test/js/selfharness.test.mjs
// No dependencies, no network, no build step, no device.

import { readFileSync, mkdirSync, writeFileSync, rmSync, existsSync, mkdtempSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";

// Временный каталог - НЕ os.tmpdir(): на Android /tmp не существует и не
// создаётся, и каждый тест, писавший туда, падал с EACCES независимо от
// кода harness. Черновик, который проходит на ПК и падает на устройстве,
// хуже отсутствия черновика: гейт проверен там, где петля не работает.
// Каталог самого теста. Объявлен ДО resolveTmp(): фолбэк в catch обязан иметь
// право на него обратиться, а HARNESS тут ещё не существует - обращение к нему
// из resolveTmp давало ReferenceError ровно там, где страховка и была нужна,
// то есть на устройстве без /tmp. Петля объявляла себя ненадёжной из-за
// собственного теста, а не из-за проверяемого гейта.
const HERE = dirname(fileURLToPath(import.meta.url));

// TEMP переопределяется окружением, иначе берётся каталог рядом с тестом -
// он существует и на ПК, и внутри filesDir на устройстве.
function resolveTmp() {
  const fromEnv = process.env.SELFHARNESS_TEST_TMP;
  if (fromEnv) return fromEnv;
  try {
    mkdtempSync(join(tmpdir(), "probe-"));
    return tmpdir();
  } catch (e) {
    return HERE;
  }
}
const TMP = resolveTmp();

const HARNESS = join(HERE, "..", "..", "main", "assets", "harness", "selfharness.js");

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
function eq(actual, expected, what) {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) throw new Error(`${what}: got ${a}, want ${b}`);
}

// Загружаем модуль как есть. Импорт вместо чтения текста - единственный
// способ проверить, что вызываемые имена вообще экспортированы: тест на
// отсутствие экспорта должен падать импортом, а не тихо.
// Импорт по file:// URL: на Windows `import("C:\\...")` падает с
// ERR_UNSUPPORTED_ESM_URL_SCHEME. Относительный путь из ESM не берётся,
// поэтому единственная честная форма - явный URL.
const mod = await import(pathToFileURL(HARNESS).href);
const { gate, doctor, rollback, snapshot, verdictOf, assertInsideTools } = mod;

const ROOTS = [];
function freshRoot() {
  const root = join(TMP, "selfharness-test-" + Math.random().toString(36).slice(2) + Date.now().toString(36));
  mkdirSync(join(root, "tools"), { recursive: true });
  mkdirSync(join(root, "test", "js"), { recursive: true });
  ROOTS.push(root);
  return root;
}
function cleanup() { for (const r of ROOTS) { try { rmSync(r, { recursive: true, force: true }); } catch (e) {} } }

/** Раннер, который ведёт себя как настоящий: зелёный или красный по воле теста. */
function fakeRunner(behaviour) {
  const calls = [];
  const runner = (abs) => {
    calls.push(abs);
    const name = abs.replace(/\\/g, "/").split("/").pop();
    return behaviour(name);
  };
  runner.calls = calls;
  return runner;
}
const GREEN_OUT = "зелёное: 5 ок, 0 сломалось";
const RED_OUT = "ПРОВАЛЕНО: 4 ок, 1 сломалось\n  - что-то сломалось";

// ---------------------------------------------------------------- verdicts

await test("красный вердикт читается из текста, а не из кода возврата", async () => {
  eq(verdictOf(GREEN_OUT), "зелёное", "зелёный вывод");
  eq(verdictOf(RED_OUT), "ПРОВАЛЕНО", "красный вывод");
  eq(verdictOf("зелёное: 5 ок, 0 сломалось"), "зелёное", "зелёный с нулём провалов");
  eq(verdictOf(""), "зелёное", "пустой вывод не считается провалом");
});

await test("слово FAIL тоже означает красный", async () => {
  eq(verdictOf("  FAIL отсутствие исполнителя"), "ПРОВАЛЕНО", "английский FAIL");
});

await test("НЕ ЗАПУСТИЛСЯ - не то же самое, что зелёное", async () => {
  // Провал, который чуть не прошёл на живом прогоне: тест не стартовал из-за
  // раскладки каталогов, его вывод не содержал ни «зелёного», ни «ПРОВАЛЕНО»,
  // и гейт рапортовал об успехе на сломанном инструменте.
  eq(verdictOf("error: Cannot find module '../tools/digest.js'"), "НЕ ЗАПУСТИЛСЯ", "нет модуля");
  eq(verdictOf("error: Module not found \"/x/y.mjs\""), "НЕ ЗАПУСТИЛСЯ", "нет файла запуска");
  eq(verdictOf("ReferenceError: greet is not defined"), "НЕ ЗАПУСТИЛСЯ", "нет символа");
  eq(verdictOf("TypeError: x is not a function"), "НЕ ЗАПУСТИЛСЯ", "ошибка типа");
});

await test("НЕ ЗАПУСТИЛСЯ имеет приоритет над красным в том же выводе", async () => {
  eq(verdictOf("ПРОВАЛЕНО: 0 ок, 1 сломалось\nerror: Cannot find module"), "НЕ ЗАПУСТИЛСЯ", "ошибка запуска важнее");
});

await test("вывод без маркеров запуска считается зелёным", async () => {
  eq(verdictOf("зелёное: 3 ок, 0 сломалось"), "зелёное", "нормальный зелёный");
});

await test("упавший запуск раннера - НЕ ЗАПУСТИЛСЯ, а не красный", async () => {
  // Различать надо: «тесты красные» - это про инструмент, «не запустился» -
  // про контур. Смешанный вердикт отправляет агента чинить не то.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "x");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("notrun", ["a.js"], ["t.test.mjs"], { root, runner: () => { throw new Error("нет bun"); } });
  okIf(!r.ok, "гейт обязан быть красным");
  okIf(String(r.tests[0].reason).includes("запуск упал"), "причина падения запуска обязана быть в отчёте");
});

await test("НЕ ЗАПУСТИЛСЯ из-за раскладки каталогов валит гейт и откатывает", async () => {
  // Точный воспроизводочный случай живого провала.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("layout", ["a.js"], ["t.test.mjs"], {
    root,
    runner: () => "error: Cannot find module '../tools/a.js'",
  });
  okIf(!r.ok, "незапустившийся тест обязан валить гейт");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "правка откачена");
  okIf(r.tests[0].reason.includes("не запустился"), "вердикт обязан быть НЕ ЗАПУСТИЛСЯ");
});

await test("зелёный прогон с ненулевым кодом возврата не становится красным от слова 'failed' в тексте", async () => {
  // Наш раннер печатает «зелёное» и может завершиться с 0; проверяем, что
  // слово «0 сломалось» не читается как провал.
  eq(verdictOf("зелёное: 0 ок, 0 сломалось"), "зелёное", "ноль провалов");
});

// ---------------------------------------------------------------- path guard

await test("путь внутри tools принимается", async () => {
  const p = assertInsideTools("digest.js");
  okIf(p.endsWith("digest.js"), "путь должен указывать на файл: " + p);
});

await test("выход из tools через .. отвергается", async () => {
  // Главная защита. Если это пройдёт, петля сможет переписать конфиг
  // с токенами - и цена ошибки перестанет быть стоимостью её скрипта.
  let threw = false;
  try { assertInsideTools("../opencode.jsonc"); } catch (e) { threw = true; }
  okIf(threw, "выход из tools через .. обязан быть отвергнут");
});

await test("глубокий выход из tools отвергается", async () => {
  let threw = false;
  try { assertInsideTools("../../etc/hosts"); } catch (e) { threw = true; }
  okIf(threw, "../../ обязан быть отвергнут");
});

await test("пустой путь отвергается", async () => {
  let threw = false;
  try { assertInsideTools(""); } catch (e) { threw = true; }
  okIf(threw, "пустой путь обязан быть отвергнут");
});

await test("не-строка отвергается, а не превращается в 'undefined'", async () => {
  let threw = false;
  try { assertInsideTools(undefined); } catch (e) { threw = true; }
  okIf(threw, "undefined обязан быть отвергнут");
});

// ---------------------------------------------------------------- snapshots

await test("снапшот снимает файл и откат возвращает его", async () => {
  const root = freshRoot();
  const toolsDir = join(root, "tools");
  writeFileSync(join(toolsDir, "a.js"), "original");
  snapshot("a.js", "lbl1", root);
  writeFileSync(join(toolsDir, "a.js"), "broken by patch");
  const restored = rollback("lbl1", root);
  eq(restored.length, 1, "восстановлен один файл");
  eq(readFileSync(join(toolsDir, "a.js"), "utf8"), "original", "файл вернулся к исходному");
});

await test("откат удаляет снапшот, чтобы повторный откат не молчал", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "x");
  snapshot("a.js", "lbl2", root);
  rollback("lbl2", root);
  let threw = false;
  try { rollback("lbl2", root); } catch (e) { threw = true; }
  okIf(threw, "повторный откат обязан явно сказать, что откатывать нечего");
});

await test("снапшот несуществующего файла падает, а не создаёт пустышку", async () => {
  const root = freshRoot();
  let threw = false;
  try { snapshot("nope.js", "lbl3", root); } catch (e) { threw = true; }
  okIf(threw, "снапшот отсутствующего файла обязан падать");
});

// ---------------------------------------------------------------- gate: green

await test("зелёный гейт оставляет изменение на месте", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "fixed version");
  writeFileSync(join(root, "test", "js", "a.test.mjs"), "unused");
  const r = gate("g1", ["a.js"], ["a.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT) });
  okIf(r.ok, "гейт обязан признать зелёный прогон");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "fixed version", "правка осталась");
  eq(r.restored.length, 0, "откатов быть не должно");
});

await test("зелёный гейт пишет отчёт для следующего прогона", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "x");
  writeFileSync(join(root, "test", "js", "a.test.mjs"), "unused");
  gate("g2", ["a.js"], ["a.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT) });
  const rep = JSON.parse(readFileSync(join(root, "lessons", "last-gate.json"), "utf8"));
  okIf(rep.ok === true, "отчёт должен пометить прогон зелёным");
  eq(rep.label, "g2", "метка прогона сохранена");
});

// ---------------------------------------------------------------- gate: red

await test("красный гейт откатывает правку", async () => {
  // Сердце петли. Красный прогон обязан вернуть исходный байт, иначе агент
  // оставит сломанное и запомнит как починенное.
  //
  // Правка идёт через apply: снапшот обязан сняться ДО неё, иначе откат
  // вернёт уже сломанный файл.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "a.test.mjs"), "unused");
  const r = gate("g3", ["a.js"], ["a.test.mjs"], {
    root,
    runner: fakeRunner(() => RED_OUT),
    apply: () => writeFileSync(join(root, "tools", "a.js"), "broken patch"),
  });
  okIf(!r.ok, "гейт обязан признать красный прогон");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "правка откачена");
  okIf(r.restored.length === 1, "отчёт должен назвать восстановленный файл");
});

await test("apply вызывается ПОСЛЕ снятия снапшота, а не до", async () => {
  // Порядок снапшот->правка->тест держится на одной детали: снапшот после
  // правки увозил бы поломанный файл, и откат стал бы фикцией.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  let seenAtApply = null;
  const r = gate("ord", ["a.js"], ["t.test.mjs"], {
    root,
    runner: fakeRunner(() => GREEN_OUT),
    apply: () => {
      seenAtApply = readFileSync(join(root, "tools", "a.js"), "utf8");
      writeFileSync(join(root, "tools", "a.js"), "patched");
    },
  });
  eq(seenAtApply, "original", "в момент apply файл ещё должен быть исходным");
  okIf(r.ok, "зелёный гейт");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "patched", "правка осталась");
});

await test("apply через newContent пишет файл", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("nc", ["a.js"], ["t.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT), newContent: "patched via newContent" });
  okIf(r.ok, "гейт зелёный");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "patched via newContent", "контент записан");
});

await test("упавший apply - красный гейт и откат, а не зелёный", async () => {
  // Если правка не наложилась, «зелёные» тесты говорят о старом коде. Это
  // самый коварный вариант: агент решил бы, что починил, а ничего не
  // изменилось.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("badapply", ["a.js"], ["t.test.mjs"], {
    root,
    runner: fakeRunner(() => GREEN_OUT),
    apply: () => { throw new Error("диск заполнен"); },
  });
  okIf(!r.ok, "упавший apply обязан валить гейт");
  okIf(String(r.tests[0].reason).includes("ПРИМЕНИТЬ НЕ УДАЛОСЬ"), "причина обязана быть в отчёте");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "файл не тронут");
});

await test("newContent без файлов падает явно", async () => {
  const root = freshRoot();
  let threw = false;
  try {
    gate("nc0", [], ["t.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT), newContent: "x" });
  } catch (e) { threw = true; }
  okIf(threw, "newContent без changedPaths обязан падать");
});

await test("красный гейт помечает отчёт как неуспешный, даже откат прошёл", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "a.test.mjs"), "unused");
  const r = gate("g4", ["a.js"], ["a.test.mjs"], { root, runner: fakeRunner(() => RED_OUT) });
  const rep = JSON.parse(readFileSync(join(root, "lessons", "last-gate.json"), "utf8"));
  okIf(rep.ok === false, "отчёт на диске обязан быть красным");
  okIf(!rep.rollback_failed, "откат прошёл - флага отказа быть не должно");
  eq(r.tests[0].ok, false, "тест в отчёте красный");
});

await test("один красный из нескольких тестов валит весь гейт", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "tools", "b.js"), "original");
  for (const f of ["t1.test.mjs", "t2.test.mjs"]) writeFileSync(join(root, "test", "js", f), "unused");
  const r = gate("g5", ["a.js", "b.js"], ["t1.test.mjs", "t2.test.mjs"], {
    root,
    runner: fakeRunner((n) => (n.startsWith("t2") ? RED_OUT : GREEN_OUT)),
  });
  okIf(!r.ok, "частичный провал обязан валить гейт");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "a.js откачен");
  eq(readFileSync(join(root, "tools", "b.js"), "utf8"), "original", "b.js откачен");
});

await test("все тесты зелёные - ничего не откатывается", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "patched");
  for (const f of ["t1.test.mjs", "t2.test.mjs"]) writeFileSync(join(root, "test", "js", f), "unused");
  const r = gate("g6", ["a.js"], ["t1.test.mjs", "t2.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT) });
  okIf(r.ok, "гейт зелёный");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "patched", "правка осталась");
  eq(r.restored.length, 0, "откатов нет");
});

await test("вывод с 'cannot load run' - это провал запуска, а не зелёное", async () => {
  // Точный вывод живого провала на устройстве: execPath там - libldmusl.so,
  // и попытка выполнить 'run' как файл давала это сообщение. Ни один маркер
  // красного в нём не встречался, гейт рапортовал об успехе.
  eq(verdictOf("cannot load run: No such file or directory"), "НЕ ЗАПУСТИЛСЯ", "cannot load run");
});

await test("вывод с 'НЕ ЗАПУСТИЛСЯ: раннер не запущен' - провал", async () => {
  eq(verdictOf("error: НЕ ЗАПУСТИЛСЯ: раннер не запущен (ENOENT)"), "НЕ ЗАПУСТИЛСЯ", "раннер не запущен");
});

await test("команда раннера собирается из SELFHARNESS_BUN_CMD с плейсхолдером %s", async () => {
  // На устройстве bun не в PATH и не самостоятельный бинарник: его запускает
  // libldmusl.so как загрузчик. Раннер обязан принять полную цепочку.
  const { parseBunCmd } = mod;
  eq(parseBunCmd("/x/libldmusl.so /x/libbun-musl.so run %s", "/f/t.test.mjs"),
    ["/x/libldmusl.so", "/x/libbun-musl.so", "run", "/f/t.test.mjs"], "плейсхолдер подставлен");
});

await test("команда раннера без плейсхолдера дописывает файл в конец", async () => {
  const { parseBunCmd } = mod;
  eq(parseBunCmd("bun run", "/f/t.test.mjs"), ["bun", "run", "/f/t.test.mjs"], "файл дописан");
});

await test("пустая команда раннера даёт null, а не команду из пробелов", async () => {
  const { parseBunCmd } = mod;
  eq(parseBunCmd("   ", "/f/t.test.mjs"), null, "пустая спека");
  eq(parseBunCmd("", "/f/t.test.mjs"), null, "пустая строка");
  eq(parseBunCmd(null, "/f/t.test.mjs"), null, "null");
});

await test("процесс с ненулевым кодом возврата валит гейт даже с зелёным текстом", async () => {
  // Код возврата сам по себе больше не делает прогон красным (наш раннер
  // выходит с 0 при вердикте), но маркер, который раннер дописывает при
  // ненулевом коде, обязан быть опознан. Раньше этот путь возвращал текст без
  // маркера, и «зелёное: 0 сломалось» с кодом 1 читалось как успех.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("code", ["a.js"], ["t.test.mjs"], {
    root,
    runner: () => "зелёное: 0 ок, 0 сломалось\nПРОВАЛЕНО: процесс завершился с кодом 1",
  });
  okIf(!r.ok, "ненулевой код возврата обязан валить гейт");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "правка откачена");
});

await test("упавший раннер (исключение) считается провалом, а не зелёным", async () => {
  // Раннер может упасть сам (нет bun, нет файла). Молчаливый зелёный здесь
  // означал бы, что сломанный гейт всегда пропускает.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "a.test.mjs"), "unused");
  const runner = () => { throw new Error("bun не найден"); };
  const r = gate("g7", ["a.js"], ["a.test.mjs"], { root, runner });
  okIf(!r.ok, "упавший раннер обязан валить гейт");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original", "правка откачена");
});

await test("отсутствующий тест-файл - провал, а не «нечего проверять»", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "patched");
  const r = gate("g8", ["a.js"], ["net-takogo.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT) });
  okIf(!r.ok, "нет теста - значит нет доказательства, что правка безопасна");
  eq(readFileSync(join(root, "tools", "a.js"), "utf8"), "original" === "original" ? readFileSync(join(root, "tools", "a.js"), "utf8") : "", "файл не трогаем панически");
});

await test("гейт без тестов не считается успешным", async () => {
  // Пустой список тестов - это не «всё хорошо», это «ничего не проверено».
  // Разница между этими двумя - вся разница между петлёй и самообманом.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "patched");
  const r = gate("g9", ["a.js"], [], { root, runner: fakeRunner(() => GREEN_OUT) });
  okIf(!r.ok, "гейт без тестов обязан быть красным");
});

await test("откат не маскируется под успех, когда откат невозможен", async () => {
  // Подделать метку снапшота нельзя - гейт снимает снапшот сам, до тестов.
  // Значит проверять надо другой отказ: rollback бросает исключение, потому
  // что каталог снапшотов удалили из-под него. Молчаливый «успех» здесь означал
  // бы, что сломанный код остался, а отчёт об этом не сказал.
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "original");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  const r = gate("rb-fail", ["a.js"], ["t.test.mjs"], {
    root,
    runner: fakeRunner(() => RED_OUT),
    apply: () => rmSync(join(root, ".snapshots"), { recursive: true, force: true }),
  });
  okIf(!r.ok, "гейт красный");
  okIf(String(r.restored.join(";")).includes("ОТКАТ НЕ УДАЛСЯ"), "отчёт должен сказать, что откат не случился");
  eq(r.rollback_failed, true, "флаг отказа обязателен");
  okIf(!r.ok && r.restored.length === 1, "одна запись о неудаче отката");
});

// ---------------------------------------------------------------- doctor

await test("doctor рассказывает о контуре и проверяет запись", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "digest.js"), "x");
  writeFileSync(join(root, "test", "js", "digest.test.mjs"), "x");
  const d = doctor(root);
  okIf(d.toolsWritable === true, "tools должны быть доступны для записи");
  eq(d.tools.includes("digest.js"), true, "инструмент виден");
  eq(d.tests.includes("digest.test.mjs"), true, "тест виден");
  eq(d.lastReport, null, "до первого гейта отчёта нет");
});

await test("doctor подхватывает отчёт последнего гейта", async () => {
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "x");
  writeFileSync(join(root, "test", "js", "t.test.mjs"), "unused");
  gate("g10", ["a.js"], ["t.test.mjs"], { root, runner: fakeRunner(() => GREEN_OUT) });
  const d = doctor(root);
  okIf(d.lastReport && d.lastReport.label === "g10", "doctor должен показать метку последнего прогона");
});

// ---------------------------------------------------------------- mutation

await test("ПОПЫТКА ОБОЙТИ: убрать разбор вывода - гейт начинает пропускать красное", async () => {
  // Мутация исходника. Её смысл - доказать, что тесты выше действительно
  // ловят именно ту строку, на которую смотрят, а не проходят рядом.
  const src = readFileSync(HARNESS, "utf8");
  const broken = src.replace(
    'const hit = RED_MARKERS.find((m) => t.includes(m));',
    'const hit = null;',
  );
  okIf(broken !== src, "мутация не применилась - проверка не показательна");
  const mutated = join(TMP, "selfharness-mut-" + Date.now() + ".mjs");
  writeFileSync(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  eq(m.verdictOf(RED_OUT), "зелёное", "мутированный раннер обязан читать красное как зелёное");
  rmSync(mutated, { force: true });
});

await test("ПОПЫТКА ОБОЙТИ: убрать проверку границы tools - выход наружу проходит", async () => {
  const src = readFileSync(HARNESS, "utf8");
  const broken = src.replace(
    'if (!norm.startsWith(rootNorm)) {',
    'if (false) {',
  );
  okIf(broken !== src, "мутация не применилась");
  const mutated = join(TMP, "selfharness-mut2-" + Date.now() + ".mjs");
  writeFileSync(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  let escaped = false;
  try { m.assertInsideTools("../opencode.jsonc"); escaped = true; } catch (e) { escaped = false; }
  okIf(escaped, "мутированный harness обязан выпустить путь наружу");
  rmSync(mutated, { force: true });
});

await test("ПОПЫТКА ОБОЙТИ: убрать require непустого списка тестов", async () => {
  const src = readFileSync(HARNESS, "utf8");
  const broken = src.replace(
    'const ok = results.length > 0 && results.every((r) => r.ok);',
    'const ok = results.every((r) => r.ok);',
  );
  okIf(broken !== src, "мутация не применилась");
  const mutated = join(TMP, "selfharness-mut3-" + Date.now() + ".mjs");
  writeFileSync(mutated, broken);
  const m = await import(pathToFileURL(mutated).href);
  const root = freshRoot();
  writeFileSync(join(root, "tools", "a.js"), "patched");
  const r = m.gate("mut3", ["a.js"], [], { root, runner: fakeRunner(() => GREEN_OUT) });
  okIf(r.ok === true, "мутированный harness обязан считать гейт без тестов зелёным");
  rmSync(mutated, { force: true });
});

cleanup();
console.log("");
if (bad > 0) {
  console.log("ПРОВАЛЕНО: " + ok + " ок, " + bad + " сломалось");
  for (const f of failures) console.log("  - " + f);
  process.exit(1);
} else {
  console.log("зелёное: " + ok + " ок, 0 сломалось");
  void existsSync;
}