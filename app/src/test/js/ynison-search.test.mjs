// Offline tests for the search layer of the Ynison MCP asset.
//
// WHY THIS FILE EXISTS
// The artist-aware search rewrite shipped with two silent bugs, both found by a throwaway
// harness that ran the real functions against the live API before the build:
//   1. byTitle was strict enough that a performer asked for by name alone matched nothing.
//   2. The "is this a performer request?" signal looked at the artists of ANY hit, and DMX
//      is a credited collaborator on Eminem and Limp Bizkit tracks that sit at the top of a
//      search for "DMX" - so the signal read as a match and returned the wrong person.
// Neither bug throws. Both return a plausible, wrong list. That is why the decisions get
// pinned here rather than left to the next reader.
//
// WHY IT SLICES THE SOURCE
// ynison.js is a top-level script: it reads the environment and opens sockets the moment it
// is imported, so it cannot be imported as a module. Slicing the search block out and running
// it with an injected apiGet tests the code that actually ships instead of a copy of it.
// The two anchors and the required function list are asserted below, so a refactor that moves
// the block fails loudly instead of silently testing nothing.
//
// Run:  node app/src/test/js/ynison-search.test.mjs
// No dependencies, no network, no build step.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SOURCE = join(HERE, "..", "..", "main", "assets", "mcp", "ynison.js");

const BLOCK_START = "const CLIENT_ID =";
const BLOCK_END = "// Shaped like the entries the phone itself publishes";
const REQUIRED = ["catalogGet", "artistTracks", "resolveArtist", "byTitle", "searchTracks", "searchTrack"];

// ---------------------------------------------------------------------------
// Fixtures. Shapes copied from real responses, values trimmed to what a test needs.
// ---------------------------------------------------------------------------

// "DMX" is a name shared by 55 accounts. The exact one is NOT first - picking the first
// hit is how a search for one band quietly returns another. The lookalikes are deliberately
// NOT spelled "Dmx": the comparison is case-insensitive, so a fixture entry named "Dmx" would
// itself be an exact match and the test would pass for the wrong reason.
const ARTIST_SEARCH_DMX = {
  result: {
    artists: {
      results: [
        { id: "900001", name: "Dmx Band" },
        { id: "900002", name: "DMX Cover Band" },
        { id: "4118", name: "DMX" },
        { id: "900003", name: "Dmx Type Beat" },
      ],
    },
  },
};

// result.tracks is a FLAT array here, not { results } - a different shape from search.
const ARTIST_TRACKS_DMX = {
  result: {
    tracks: [
      { id: "65382", title: "The Rain", artists: [{ id: "4118", name: "DMX" }] },
      { id: "65393", title: "X Gon' Give It To Ya", artists: [{ id: "4118", name: "DMX" }] },
      { id: "7435", title: "Party Up", artists: [{ id: "4118", name: "DMX" }] },
    ],
  },
};

// What type=track really returns for "DMX": other people's songs with DMX in the TITLE.
// Note the last two - DMX is a real CO-CREDIT on them. Any signal that scans all artists
// sees "DMX" here and concludes the results are fine.
const TRACK_SEARCH_DMX_JUNK = {
  result: {
    tracks: {
      results: [
        { id: "1", title: "DMX", artists: [{ name: "Primer" }] },
        { id: "2", title: "DMX", artists: [{ name: "Locs" }] },
        { id: "767123", title: "Go To Sleep", artists: [{ name: "Eminem" }, { name: "DMX" }, { name: "Obie Trice" }] },
        { id: "21737472", title: "Rollin'", artists: [{ name: "Limp Bizkit" }, { name: "DMX" }] },
      ],
    },
  },
};

// A genuine track-title search. The lead artist differs from the query and no artist by that
// name exists, so the performer branch must decline and leave these results alone.
const TRACK_SEARCH_BARK = {
  result: {
    tracks: {
      results: [
        { id: "1979", title: "Bark at the Moon", artists: [{ name: "Ozzy Osbourne" }] },
        { id: "1980", title: "Bark At The Moon", artists: [{ name: "Some Cover" }] },
      ],
    },
  },
};

const ARTIST_SEARCH_BARK = { result: { artists: { results: [{ id: "77", name: "Barking Mad" }] } } };

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

function loadBlock() {
  const src = readFileSync(SOURCE, "utf8");
  const from = src.indexOf(BLOCK_START);
  const to = src.indexOf(BLOCK_END);
  if (from < 0) throw new Error(`якорь не найден: ${BLOCK_START} - блок поиска переехал, тест надо поправить`);
  if (to < 0) throw new Error(`якорь не найден: ${BLOCK_END} - блок поиска переехал, тест надо поправить`);
  if (to <= from) throw new Error("BLOCK_END идёт раньше BLOCK_START - anchors crossed");

  const block = src.slice(from, to);
  const missing = REQUIRED.filter((name) => !new RegExp(`(function|const)\\s+${name}\\b`).test(block));
  if (missing.length) {
    throw new Error(`в срезе нет: ${missing.join(", ")} - блок изменился, тест молча ничего не проверяет`);
  }
  return block;
}

/** Fake apiGet over the fixtures. Records every call so tests can assert on the request itself. */
function makeApiGet(routes) {
  const calls = [];
  const apiGet = async (path, headers) => {
    calls.push({ path, headers: { ...(headers ?? {}) } });
    for (const [needle, body] of Object.entries(routes)) {
      if (path.includes(needle)) return body;
    }
    throw new Error(`незапланированный запрос: ${path}`);
  };
  return { apiGet, calls };
}

function build(routes) {
  const { apiGet, calls } = makeApiGet(routes);
  const trackSummary = (t) => ({ id: String(t.id ?? ""), title: t.title });
  // OAUTH is in scope for the block (the socket path uses it) but the catalog path must not.
  const factory = new Function(
    "apiGet", "trackSummary", "OAUTH",
    `${loadBlock()}\n return { searchTracks, searchTrack, resolveArtist, byTitle };`,
  );
  return { ...factory(apiGet, trackSummary, "fake-token"), calls };
}

const DMX_ROUTES = {
  "type=artist": ARTIST_SEARCH_DMX,
  "/artists/4118/tracks": ARTIST_TRACKS_DMX,
  "type=track": TRACK_SEARCH_DMX_JUNK,
};

let passed = 0;
const failures = [];

async function test(name, fn) {
  try {
    // The await is load-bearing. Without it an async body has not run when the catch is
    // evaluated, so every test would be counted as passed no matter what it did - a harness
    // that cannot fail is worse than no harness, because it reads as coverage.
    await fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failures.push({ name, err });
    console.log(`  FAIL ${name}\n         ${err.message}`);
  }
}

function eq(actual, expected, what) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${what}\n         ожидалось: ${e}\n         получено:  ${a}`);
}
function ok(cond, what) {
  if (!cond) throw new Error(what);
}
async function rejects(fn, re, what) {
  try {
    await fn();
  } catch (err) {
    ok(re.test(String(err.message)), `${what}: сообщение "${err.message}" не про ${re}`);
    return;
  }
  throw new Error(`${what}: ожидался отказ, а он вернул результат`);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

console.log("\nresolveArtist");

await test("точное совпадение имени побеждает первый выдавшийся аккаунт", async () => {
  const { resolveArtist } = build(DMX_ROUTES);
  const hit = await resolveArtist("DMX");
  eq(hit.id, "4118", "взят не тот аккаунт из 55 одноимённых");
  eq(hit.exact, true, "точное совпадение не отмечено");
});

await test("нет точного совпадения - exact=false, чтобы вызывающий не принял это за совпадение", async () => {
  const { resolveArtist } = build({ "type=artist": { result: { artists: { results: [{ id: "77", name: "Barking Mad" }] } } } });
  const hit = await resolveArtist("Bark at the Moon");
  eq(hit.exact, false, "выдуманное совпадение");
});

console.log("\nsearchTracks с артистом");

await test("берёт каталог артиста, а не выдачу по названию", async () => {
  const { searchTracks } = build(DMX_ROUTES);
  const r = await searchTracks("DMX", "DMX");
  ok(r.length > 0, "пусто");
  ok(r.every((t) => t.artists[0].name === "DMX"), "в выдаче чужие исполнители");
});

await test("название трека сужает каталог артиста", async () => {
  const { searchTracks } = build(DMX_ROUTES);
  const r = await searchTracks("Party Up", "DMX");
  eq(r.map((t) => t.id), ["7435"], "сужение по названию не сработало");
});

await test("название не найдено - отдаём кандидатов, а не пустоту", async () => {
  const { searchTracks } = build(DMX_ROUTES);
  const r = await searchTracks("нет такого трека", "DMX");
  ok(r.length > 0, "пусто: выдумать id хуже, но показать нечего - значит агент не сможет предложить варианты");
});

console.log("\nsearchTracks без артиста");

await test("исполнитель без названия трека - берём его каталог", async () => {
  const { searchTracks } = build(DMX_ROUTES);
  const r = await searchTracks("DMX");
  ok(r.length > 0, "пусто");
  ok(r.every((t) => t.artists[0].name === "DMX"), "остались треки чужих исполнителей");
});

await test("РЕГРЕССИЯ: соавторство не должно выдавать себя за совпадение", async () => {
  // DMX стоит в artists у двух верхних хитов. Сигнал по ВСЕМ исполнителям считает это
  // совпадением и возвращает мусор обратно. Работать должен только ГЛАВНЫЙ исполнитель.
  const { searchTracks } = build(DMX_ROUTES);
  const r = await searchTracks("DMX");
  const leadNames = r.map((t) => t.artists[0].name);
  ok(
    !leadNames.includes("Primer") && !leadNames.includes("Locs"),
    `мусор наверху выдачи: ${leadNames.slice(0, 3).join(", ")}`,
  );
});

await test("обычный поиск по названию не перехватывается", async () => {
  const { searchTracks, calls } = build({
    "type=artist": ARTIST_SEARCH_BARK,
    "type=track": TRACK_SEARCH_BARK,
  });
  const r = await searchTracks("Bark at the Moon");
  eq(r.map((t) => t.id), ["1979", "1980"], "обычный поиск испорчен");
  // The artist lookup is not waste: the lead artist differs from the query, so that is
  // exactly the case the heuristic has to rule ON. Two calls, and the second is the check.
  eq(calls.length, 2, "неожиданное число запросов");
  ok(calls[1].path.includes("type=artist"), "второй запрос должен быть проверкой имени исполнителя");
});

console.log("\nsearchTrack для проигрывания");

await test("конкретный трек выбирается верно", async () => {
  const { searchTrack } = build(DMX_ROUTES);
  const t = await searchTrack("Party Up", "DMX");
  eq(t.id, "7435", "выбран не тот трек");
});

await test("исполнитель без названия - берём трек этого исполнителя", async () => {
  const { searchTrack } = build(DMX_ROUTES);
  const t = await searchTrack("DMX", "DMX");
  eq(t.artists[0].name, "DMX", "взяли чужой трек");
});

await test("несуществующий трек - отказ, а не первый попавшийся", async () => {
  const { searchTrack } = build(DMX_ROUTES);
  await rejects(
    () => searchTrack("несуществующий трек", "DMX"),
    /no track titled/i,
    "проигрывание выдуманного трека неотличимо от успеха",
  );
});

console.log("\ntransport");

await test("каталог не шлёт токен: он всё равно получает 403, но тратит на это два круга", async () => {
  const { searchTracks, calls } = build(DMX_ROUTES);
  await searchTracks("DMX", "DMX");
  for (const c of calls) {
    const hasAuth = Object.keys(c.headers).some((k) => k.toLowerCase() === "authorization");
    ok(!hasAuth, `Authorization ушёл в публичный каталог: ${c.path}`);
  }
  eq(calls.length, 2, "ожидались artist-поиск + треки, без повторов на 403");
});

// ---------------------------------------------------------------------------

console.log(`\n${failures.length ? "ПРОВАЛЕНО" : "зелёное"}: ${passed} ок, ${failures.length} сломалось\n`);
process.exit(failures.length ? 1 : 0);
