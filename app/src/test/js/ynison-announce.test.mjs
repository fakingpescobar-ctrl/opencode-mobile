// Offline tests for the one message this bridge sends that is not a command: the
// announcement it makes about itself when a session opens.
//
// WHY THIS FILE EXISTS
// On 30.09.2026 a real play call on a real phone reported success and the track paused a
// few seconds later. The handshake published a hardcoded
//
//     player_state: { status: { paused: true, ... }, player_queue: { playable_list: [] } }
//
// on every call - including the music_status that noticed the pause, which is why it looked
// like the phone deciding on its own.
//
// The fix is NOT to invent a nicer state. The server does not allow that. Measured, in
// order, on the device:
//
//   no player_state                 -> 400030002 "Unspecified repeat mode is not allowed."
//                                      + the socket is torn down, nothing ever arrives
//   paused:false + empty queue      -> 400030001 "Player is not paused, but queue is
//                                      probably empty (currentIndex=-1, size=0)"
//   paused:true  + empty queue      -> accepted, and this is what paused the track
//
// So `paused: true` + empty is the only legal way to say "I know nothing", and there is no
// neutral state to fall back on. The only correct move is to stop saying "I know nothing"
// once we do know something: the handshake publishes the newest state the server sent, or the
// last real one from disk, and only a genuine cold start - fresh install, no cache file -
// falls back to the empty paused state.
//
// The contract pinned here is therefore:
//   1. a real state always wins over the fallback, from frames or from cache
//   2. the fallback is only ever reached when there is genuinely nothing
//   3. the real queue, position and paused flag are never altered
//   4. only the repeat enum is repaired, because the phone's UNSPECIFIED is fatal from us
//
// Run:  node app/src/test/js/ynison-announce.test.mjs
// No dependencies, no network, no build step.

import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

const BLOCK_START = "function neutralState() {";
const BLOCK_END = "// Open a Ynison session and complete the handshake.";

function loadBlock() {
  const src = readFileSync(SOURCE, "utf8");
  const from = src.indexOf(BLOCK_START);
  const to = src.indexOf(BLOCK_END);
  if (from < 0) throw new Error(`якорь не найден: ${BLOCK_START} - функция переехала, тест надо поправить`);
  if (to < 0) throw new Error(`якорь не найден: ${BLOCK_END} - функция переехала, тест надо поправить`);
  if (to <= from) throw new Error("BLOCK_END идёт раньше BLOCK_START - anchors crossed");
  return src.slice(from, to);
}

/**
 * Runs the real announceDevice against a recording send().
 * `STATE_CACHE` is injected, so each test gets its own cache file and none of them can see
 * what another one wrote - a shared cache would make every test depend on run order.
 */
function announce(frames, cachePath = "", seed = null) {
  const sent = [];
  const send = (_ws, message) => sent.push(message);
  const meta = () => ({ rid: "rid-1", player_action_timestamp_ms: "1", activity_interception_type: "DO_NOT_INTERCEPT_BY_DEFAULT" });
  const version = () => ({ device_id: "opencode-mobile", version: "1790000000000", timestamp_ms: "0" });
  const traced = [];
  const trace = (...a) => traced.push(a.map((x) => (typeof x === "string" ? x : JSON.stringify(x))).join(" "));

  const factory = new Function(
    "send", "meta", "SELF_ID", "STATE_CACHE", "version", "readFileSync", "writeFileSync", "trace",
    `${loadBlock()}\n return announceDevice;`,
  );
  factory(send, meta, "opencode-mobile", cachePath, version, readFileSync, writeFileSync, trace)(null, frames, seed);
  return { msg: sent[0], traced };
}

const serverState = (paused, title, repeat = "NONE") => ({
  player_state: {
    status: { paused, duration_ms: "320290", progress_ms: "2721", playback_speed: 1, version: { device_id: "phone", version: "111", timestamp_ms: "0" } },
    player_queue: {
      current_playable_index: 0, entity_id: "", entity_type: "VARIOUS",
      playable_list: [{ playable_id: "18860", playable_type: "TRACK", title }],
      options: { repeat_mode: repeat },
      entity_context: "BASED_ON_ENTITY_BY_DEFAULT", version: { device_id: "phone", version: "111", timestamp_ms: "0" }, from_optional: "",
    },
  },
});

// A genuinely empty queue - which is what the account was left holding on 30.09.2026 and the
// state 400030001 refuses. Distinct from serverState(), which always has one track in it.
const emptyState = () => {
  const s = serverState(true, "placeholder").player_state;
  s.player_queue.playable_list = [];
  s.player_queue.current_playable_index = -1;
  return s;
};

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    // The await is load-bearing. Without it an async body has not run when the catch is
    // evaluated, so every test would be counted as passed no matter what it did.
    await fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failures.push({ name, err });
    console.log(`  FAIL ${name}\n         ${err.message}`);
  }
}

function eq(actual, expected, what) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${what}\n         ожидалось: ${JSON.stringify(expected)}\n         получено:  ${JSON.stringify(actual)}`);
  }
}
function ok(cond, what) {
  if (!cond) throw new Error(what);
}

const scratch = mkdtempSync(join(tmpdir(), "ynison-announce-"));
let cacheSeq = 0;
const freshCache = () => join(scratch, `cache-${cacheSeq++}.json`);

console.log("\nannounceDevice: что уходит на провод при открытии сессии");

// --- the fallback: only ever legal because of the two measured server rules ---------

await test("холодный старт без кэша - состояние есть и оно валидно", async () => {
  // paused:true + empty is the ONLY shape the server accepts for "I know nothing":
  // no state at all earns 400030002, and paused:false + empty earns 400030001.
  const ps = announce([]).msg.update_full_state.player_state;
  ok(ps, "состояния нет - сервер вернёт Unspecified repeat mode is not allowed");
  eq(ps.player_queue.options.repeat_mode, "NONE", "сервер вернёт Unspecified repeat mode is not allowed");
});

await test("пустой фолбэк ТОЛЬКО при пустых кадрах и отсутствии кэша", async () => {
  const r = announce([]);
  eq(r.msg.update_full_state.player_state.status.paused, true, "фолбэк не сработал");
  ok(r.traced.some((l) => l.includes("no live or cached")), "фолбэк применён молча - его не найти в трейсе");
});

await test("фолбэк срабатывает и на битом кэше - и это видно в трейсе", async () => {
  const cache = freshCache();
  writeFileSync(cache, "{ truncated json");
  const r = announce([], cache);
  eq(r.msg.update_full_state.player_state.status.paused, true, "битый кэш роняет handshake или уходит в невалидное состояние");
  ok(r.traced.some((l) => l.includes("no live or cached")), "битый кэш не отмечен");
});

// --- the real path: a state we can defend ------------------------------------------

await test("кэш спасает холодный старт: настоящая очередь вместо фолбэка", async () => {
  // This is the actual fix for the reported bug. The status call that noticed the pause used
  // to republish the hardcoded empty pause; now it republishes what the phone actually had.
  const cache = freshCache();
  const real = serverState(false, "Lose Yourself").player_state;
  writeFileSync(cache, JSON.stringify(real));
  const r = announce([], cache);
  eq(r.msg.update_full_state.player_state, real, "кэш не ушёл на провод как есть");
  eq(r.msg.update_full_state.player_state.status.paused, false, "играющий трек снова публикуется как пауза");
  ok(!r.traced.some((l) => l.includes("no live or cached")), "фолбэк сработал при наличии кэша");
});

await test("кадр с сервера выигрывает у кэша", async () => {
  const cache = freshCache();
  writeFileSync(cache, JSON.stringify(serverState(false, "Gunshotta").player_state));
  eq(announce([serverState(false, "Lose Yourself")], cache).msg.update_full_state.player_state.player_queue.playable_list[0].title, "Lose Yourself", "выигровал старый кэш");
});

await test("берётся ПОСЛЕДНИЙ кадр, а не первый", async () => {
  // The server sends several states during a handshake and the first is the stale one.
  // Publishing that would undo the fix through exactly the route that caused the bug.
  const stale = serverState(true, "Gunshotta");
  const fresh = serverState(false, "Lose Yourself");
  const ps = announce([stale, fresh]).msg.update_full_state.player_state;
  eq(ps.player_queue.playable_list[0].title, "Lose Yourself", "отправлено устаревшее состояние");
  eq(ps.status.paused, false, "отправлена чужая пауза");
});

await test("состояние сервера уходит назад без единой правки", async () => {
  const frame = serverState(false, "Lose Yourself");
  eq(announce([frame]).msg.update_full_state.player_state, frame.player_state, "эхо изменено - значит мы снова можем что-то сломать");
});

await test("пауза, которая реальна, остаётся паузой", async () => {
  // The fix must not invent playback either: a genuinely paused phone stays paused.
  const frame = serverState(true, "Lose Yourself");
  eq(announce([frame]).msg.update_full_state.player_state.status.paused, true, "снимаем настоящую паузу");
});

await test("UNSPECIFIED от телефона чинится в NONE - иначе сервер бракует всё сообщение", async () => {
  const ps = announce([serverState(false, "Lose Yourself", "UNSPECIFIED")]).msg.update_full_state.player_state;
  eq(ps.player_queue.options.repeat_mode, "NONE", "сервер отвергнет это сообщение целиком");
});

await test("починка repeat НЕ трогает очередь, позицию и флаг паузы", async () => {
  const frame = serverState(true, "Lose Yourself", "UNSPECIFIED");
  const sent = announce([frame]).msg.update_full_state.player_state;
  eq(sent.player_queue.playable_list, frame.player_state.player_queue.playable_list, "очередь изменена");
  eq(sent.status, frame.player_state.status, "состояние воспроизведения изменено");
  eq(sent.player_queue.options.repeat_mode, "NONE", "единственная правка - repeat_mode");
});

await test("настоящий repeat пользователя (ONE/ALL) не затирается", async () => {
  eq(announce([serverState(false, "X", "ALL")]).msg.update_full_state.player_state.player_queue.options.repeat_mode, "ALL", "сбросили repeat пользователя");
  eq(announce([serverState(false, "X", "ONE")]).msg.update_full_state.player_state.player_queue.options.repeat_mode, "ONE", "сбросили repeat пользователя");
});

await test("отсутствие options тоже чинится, а не падает", async () => {
  const frame = serverState(false, "X");
  delete frame.player_state.player_queue.options;
  const sent = announce([frame]).msg.update_full_state.player_state;
  eq(sent.player_queue.options, { repeat_mode: "NONE" }, "серверу без options не отвергнуть, но пусть будет валидно");
});

// --- the seed: the only way out of an empty account queue ---------------------------
//
// Measured 30.09.2026: with the account queue empty, the server answers 400030001
// "Empty playable list is restricted" to the handshake itself. So a read-only tool cannot
// open a session at all, and music_play could never refill the queue - a deadlock. The seed
// is the state a write is about to publish anyway, so a write can always open its own
// session, no matter what the account currently holds.

await test("сид открывает сессию, когда очередь аккаунта пуста", async () => {
  const cache = freshCache();
  writeFileSync(cache, JSON.stringify(emptyState()));
  const seed = serverState(false, "Lose Yourself").player_state;
  const ps = announce([], cache, seed).msg.update_full_state.player_state;
  eq(ps.player_queue.playable_list[0].title, "Lose Yourself", "пустая очередь заблокировала запись трека");
});

await test("сид работает и на совсем холодном старте", async () => {
  const seed = serverState(false, "Lose Yourself").player_state;
  eq(announce([], "", seed).msg.update_full_state.player_state.player_queue.playable_list[0].title, "Lose Yourself", "холодный старт заблокирован");
});

await test("живой кадр бьёт сид - мы не затираем то, что телефон уже играет", async () => {
  const seed = serverState(false, "Lose Yourself").player_state;
  const ps = announce([serverState(false, "Gunshotta")], "", seed).msg.update_full_state.player_state;
  eq(ps.player_queue.playable_list[0].title, "Gunshotta", "сид затёр живое состояние телефона");
});

await test("непустой кэш бьёт сид", async () => {
  const cache = freshCache();
  writeFileSync(cache, JSON.stringify(serverState(false, "Gunshotta").player_state));
  const seed = serverState(false, "Lose Yourself").player_state;
  eq(announce([], cache, seed).msg.update_full_state.player_state.player_queue.playable_list[0].title, "Gunshotta", "сид затёр непустой кэш");
});

await test("сид чинится тем же repeat-ремонтом", async () => {
  const seed = serverState(false, "Lose Yourself", "UNSPECIFIED").player_state;
  eq(announce([], "", seed).msg.update_full_state.player_state.player_queue.options.repeat_mode, "NONE", "сервер отвергнет сид");
});

await test("пустой сид не хуже, чем ничего - и оба оставляют пустую очередь", async () => {
  // Not a licence to invent: an empty seed stays empty, so openSessionOnce refuses to open
  // rather than sending something the server will reject.
  const seed = emptyState();
  eq(announce([], "", seed).msg.update_full_state.player_state.player_queue.playable_list, [], "из пустого сида сделали непустой");
});

// --- the shape of the message, which must not drift ---------------------------------

await test("can_be_player остаётся true - без него claim в handoff не пройдёт", async () => {
  const caps = announce([]).msg.update_full_state.device.capabilities;
  eq(caps.can_be_player, true, "claim перестанет работать");
  eq(caps.can_be_remote_controller, true, "потеряна роль контроллера");
});

await test("is_currently_active всегда false - активная роль не наша", async () => {
  eq(announce([]).msg.update_full_state.is_currently_active, false, "мы объявили себя активным игроком");
});

await test("update_full_state пишется ровно в одном месте", async () => {
  // openSessionOnce and ensurePhone both go through announceDevice now, so a second
  // hand-built update_full_state anywhere means someone is publishing state behind its back.
  const src = readFileSync(SOURCE, "utf8");
  eq(src.split("update_full_state:").length - 1, 1, "состояние снова пишется руками в обход announceDevice");
});

await test("meta() приложен - без rid сервер отбрасывает сообщение", async () => {
  const msg = announce([]).msg;
  ok(msg.rid, "нет rid");
  ok(msg.activity_interception_type, "нет activity_interception_type");
});

console.log(`\n${failures.length ? "ПРОВАЛЕНО" : "зелёное"}: ${passed} ок, ${failures.length} сломалось\n`);
process.exit(failures.length ? 1 : 0);
