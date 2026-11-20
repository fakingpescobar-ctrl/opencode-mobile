// Локальный LLM-сопроцессор ядра памяти.
//
// Зачем этот модуль. Ядро остаётся детерминированным источником истины: оно
// ищет чанки по FTS5/леммам/графу и считает `why`. LLM получает только узкий
// список кандидатов и отвечает на вопрос «какое именно событие/факт здесь
// имеется в виду». Она НЕ пишет в память и НЕ заменяет ранжирование —
// качество gate показало 24% против 65% у детерминированного топа.
//
// Старт идёт вместе с memory.js: initLlm() проверяет выбранную модель
// и греет рантайм при старте, поэтому пользователю не надо каждый раз жать
// «Назначить». Если модель не скачана, не совпал sha256 или .so недоступен —
// статус становится unavailable, а поиск честно падает на детерминированный
// топ. Никаких внешних npm-зависимостей: bun:ffi + node:crypto встроены.

import { dlopen, FFIType } from "bun:ffi";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { join } from "node:path";

const SO_NAME = "libllama-bench.so";
const ACTIVE_FILE = "active-model.json";
const DEFAULT_DIR = "/data/user/0/org.opencode.mobile.debug/files/musl";
const ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;
const SHA_RE = /^[0-9a-f]{64}$/;
// Жёсткий потолок входа: 12 кандидатов. Больше малая модель не переваривает, а
// качество выбора падает; ядро всё равно отдаёт кандидатов по убыванию score.
const LETTERS = "ABCDEFGHIJKL";
const DEFAULT_MAX_LATENCY_MS = 8000;

let initPromise = null;
let lib = null;
let state = {
  state: "idle",
  active: false,
  model: "",
  file: "",
  runtime: "",
  reason: "",
  warmMs: 0,
  readyAt: 0,
};

/** Папка рантайма: в APK это filesDir/musl, первый элемент LD_LIBRARY_PATH. */
export function muslDir() {
  if (process.env.MCP_GGUF_DIR) return process.env.MCP_GGUF_DIR;
  const first = String(process.env.LD_LIBRARY_PATH || "").split(":").filter(Boolean)[0];
  return first || DEFAULT_DIR;
}

function familyOf(id) {
  const s = String(id);
  if (s.startsWith("gemma")) return "gemma";
  if (s.startsWith("qwen")) return "qwen"; // chatml-обёртка + /no_think
  return "chatml";
}

/** Чистая сборка промпта — тестируется без модели и без FFI. */
export function buildPrompt(query, candidates, family = "chatml") {
  // Промпт намеренно крошечный: prefill на устройстве ~70–90 токен/с, поэтому
  // каждый лишний токен кандидата стоит миллисекунд. Три поля, короткие тексты,
  // одна цифра why. Модель видит коридор ядра, а не всю память.
  const sys =
    "Селектор памяти. Не судья: память не меняй. Ответь тремя строками:\n" +
    "ID: буквы кандидатов через запятую (A, C) или пусто.\n" +
    "ПОИСК: 2–3 синонима для поиска ядра.\n" +
    "EVIDENCE: один факт строго из выбранных, без выдумок.";
  const clean = (s, n) => String(s || "")
    .replace(/[`*_#>|\[\]]/g, " ")
    .replace(/\s+/g, " ").trim().slice(0, n);
  const lines = candidates.slice(0, LETTERS.length).map((c, i) => {
    const head = clean(c.heading, 44);
    const text = clean(c.text, 52);
    const w = c.why || {};
    // w0.0 — это отсутствие якоря, а не сигнал. Такой шум сбивал 0.6B модель
    // и она выдавала «ПОИСК: w0.0, w0.8» вместо настоящих синонимов.
    const anchor = typeof w.anchor === "number" && w.anchor >= 0.2
      ? ` (${w.anchor.toFixed(1)})` : "";
    return `[${LETTERS[i]}] ${head}${anchor}: ${text}`;
  });
  const user = `Запрос: ${String(query || "").replace(/\s+/g, " ").slice(0, 400)}\n` +
    `Кандидаты:\n${lines.join("\n")}`;
  if (family === "gemma") {
    return `<start_of_turn>user\n${sys}\n\n${user}<end_of_turn>\n<start_of_turn>model\n`;
  }
  // /no_think убирает пустой <think> у Qwen3 и экономит бюджет токенов.
  const noThink = String(family).includes("qwen") ? "\n/no_think" : "";
  return `<|im_start|>system\n${sys}${noThink}<|im_end|>\n` +
    `<|im_start|>user\n${user}<|im_end|>\n<|im_start|>assistant\n`;
}

function lineAfter(clean, label) {
  const m = clean.match(new RegExp("(?:^|\\n)" + label + "\\s*:\\s*([^\\n]*)", "i"));
  return m ? m[1].trim() : "";
}

/**
 * Структурированный ответ: ID + ПОИСК + EVIDENCE. Возвращает индексы
 * кандидатов, синонимы для второго быстрого прохода ядра и цитату
 * строго по выбранным чанкам.
 */
export function parseStructured(raw, count) {
  const clean = String(raw || "").replace(/<think>[\s\S]*?<\/think>/gi, "");
  const text = lineAfter(clean, "ID");
  let found = text ? text.match(/[A-L]/g) : null;
  // Fallback-формат старых промптов: [A, C] или "id":"B".
  if (!found) {
    const bracket = clean.match(/\[([^\]]*)\]/);
    found = bracket ? bracket[1].match(/[A-L]/g) : null;
  }
  if (!found) {
    const single = clean.match(/"id"\s*:\s*"([A-L])"/);
    found = single ? [single[1]] : null;
  }
  const letters = [];
  for (const letter of found || []) {
    const index = LETTERS.indexOf(letter);
    if (index >= 0 && index < count && !letters.includes(index)) letters.push(index);
  }
  const reformulations = lineAfter(clean, "ПОИСК")
    .split(/[;,|]/)
    .map((s) => s.replace(/^[-*•\d.\s]+/, "").trim())
    .filter((s) => s.length > 1 && s.length <= 80)
    .slice(0, 4);
  const evidence = lineAfter(clean, "EVIDENCE").slice(0, 400);
  return { letters, reformulations, evidence };
}

/** Узкая обёртка для тестов и обратной совместимости. */
export function parseLetters(raw, count) {
  return parseStructured(raw, count).letters;
}

function shortHit(hit, letter) {
  return {
    letter,
    id: hit.id,
    source: hit.source,
    heading: hit.heading,
    text: String(hit.text || "").slice(0, 240),
    score: hit.score,
    why: hit.why,
  };
}

/** Честный откат: модель не участвовала, выдаём детерминированный топ. */
export function fallbackResult(query, hits, reason, model = state.model) {
  return {
    query,
    model,
    runtime: state.runtime,
    answer: "",
    raw: "",
    selected: hits.slice(0, 3).map((h, i) => shortHit(h, LETTERS[i])),
    selectedIds: hits.slice(0, 3).map((h) => h.id),
    deterministicTop: hits.slice(0, 3).map((h) => h.id),
    reformulations: [],
    changed: false,
    fallback: true,
    reason,
    latencyMs: 0,
    candidateCount: hits.length,
  };
}

function sha256File(path) {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

function validateActive(a) {
  if (!a || typeof a !== "object") throw new Error("active-model.json пуст");
  if (!ID_RE.test(String(a.id || ""))) throw new Error("неверный id модели");
  const file = String(a.file || "");
  if (!file.endsWith(".gguf") || file.includes("/") || file.includes("\\") || file.includes("..")) {
    throw new Error("неверное имя GGUF");
  }
  if (!Number.isInteger(a.size) || a.size <= 0) throw new Error("неверный размер модели");
  if (!SHA_RE.test(String(a.sha256 || "").toLowerCase())) throw new Error("неверный sha256 модели");
  return { id: a.id, file, size: a.size, sha256: a.sha256.toLowerCase() };
}

/** Авто-инициализация при старте MCP. Никогда не бросает наружу. */
export function initLlm() {
  if (!initPromise) {
    initPromise = (async () => {
      const dir = muslDir();
      try {
        const raw = await Bun.file(join(dir, ACTIVE_FILE)).text();
        const active = validateActive(JSON.parse(raw));
        const modelPath = join(dir, active.file);
        const actualSize = await Bun.file(modelPath).size;
        if (actualSize !== active.size) {
          throw new Error(`размер ${actualSize} ≠ ${active.size}`);
        }
        const sha = await sha256File(modelPath);
        if (sha !== active.sha256) throw new Error("sha256 модели не совпал");

        lib = dlopen(join(dir, SO_NAME), {
          lb_version: { args: [], returns: FFIType.cstring },
          lb_gen: {
            args: [FFIType.cstring, FFIType.cstring, FFIType.i32, FFIType.i32,
              FFIType.ptr, FFIType.i32, FFIType.ptr],
            returns: FFIType.i32,
          },
        });
        const runtime = lib.symbols.lb_version();
        if (!String(runtime).includes("lb_gen") && !lib.symbols.lb_gen) {
          throw new Error("в .so нет lb_gen");
        }

        // Прогрев: доказываем, что модель реально грузится и выдаёт токены.
        const family = familyOf(active.id);
        const warmPrompt = family === "gemma"
          ? "<start_of_turn>user\nпривет<end_of_turn>\n<start_of_turn>model\n"
          : "<|im_start|>user\nпривет<|im_end|>\n<|im_start|>assistant\n";
        const buf = new Uint8Array(512);
        const met = new Float64Array(5);
        const t0 = performance.now();
        const rc = lib.symbols.lb_gen(modelPath, warmPrompt, 512, 4, buf, buf.length, met);
        if (rc !== 0) throw new Error("прогрев LLM не удался, rc=" + rc);
        state = {
          state: "ready",
          active: true,
          model: active.id,
          file: active.file,
          runtime,
          reason: "",
          warmMs: Math.round(performance.now() - t0),
          readyAt: Date.now(),
        };
      } catch (e) {
        lib = null;
        state = {
          state: "unavailable",
          active: false,
          model: "",
          file: "",
          runtime: "",
          reason: String((e && e.message) || e),
          warmMs: 0,
          readyAt: Date.now(),
        };
      }
      return state;
    })();
  }
  return initPromise;
}

export async function llmStatus() {
  const s = await initLlm();
  return {
    ...s,
    mode: "resident",
    maxCandidates: LETTERS.length,
    maxLatencyMs: DEFAULT_MAX_LATENCY_MS,
    so: SO_NAME,
    dir: muslDir(),
    note: "LLM проверяется и греется при старте; чанки по-прежнему выбирает ядро.",
  };
}

/**
 * Точечный поиск события поверх кандидатов ядра. Читает память, но НЕ пишет:
 * любой вывод остаётся предложением агенту, а не записью в SQLite.
 */
export async function llmSearch(query, candidates, opts = {}) {
  const q = String(query || "").trim();
  if (!q) throw new Error("query required");
  const all = Array.isArray(candidates) ? candidates.filter(Boolean) : [];
  // Жёсткий коридор входа: 5–12 чанков. Больше отдавать нельзя — это уже не
  // «точечный поиск», а попытка засунуть всю базу в 0.6B модель.
  const hits = all.slice(0, LETTERS.length);
  const maxLatencyMs = Math.max(500, Math.min(30000, Number(opts.maxLatencyMs) || DEFAULT_MAX_LATENCY_MS));
  if (!hits.length) return fallbackResult(q, [], "нет кандидатов от ядра");
  const s = await initLlm();
  if (s.state !== "ready" || !lib) {
    return fallbackResult(q, hits, "LLM недоступна: " + (s.reason || s.state));
  }
  const modelPath = join(muslDir(), s.file);
  const prompt = buildPrompt(q, hits, familyOf(s.model));
  // Три коротких строки ответа; потолок токенов ограничивает худший случай
  // латентности, а замер ниже честно превращает превышение в fallback.
  const maxTokens = Math.max(16, Math.min(96, Number(opts.maxTokens) || 48));
  const buf = new Uint8Array(8192);
  const met = new Float64Array(5);
  const t0 = performance.now();
  try {
    const rc = lib.symbols.lb_gen(modelPath, prompt, 2048, maxTokens, buf, buf.length, met);
    const latencyMs = Math.round(performance.now() - t0);
    if (rc !== 0) return fallbackResult(q, hits, `lb_gen вернул rc=${rc}`);
    if (latencyMs > maxLatencyMs) {
      return { ...fallbackResult(q, hits, `таймаут ${latencyMs}мс > ${maxLatencyMs}мс`), latencyMs };
    }
    const z = buf.indexOf(0);
    const raw = new TextDecoder().decode(z < 0 ? buf : buf.subarray(0, z)).trim();
    const parsed = parseStructured(raw, hits.length);
    if (!parsed.letters.length) {
      return {
        ...fallbackResult(q, hits, "модель не выбрала ни одного кандидата"),
        answer: parsed.evidence || raw.slice(0, 400),
        raw: raw.slice(0, 800),
        reformulations: parsed.reformulations,
        latencyMs,
      };
    }
    const chosen = parsed.letters.map((i) => shortHit(hits[i], LETTERS[i]));
    const deterministicTop = hits.slice(0, 3).map((h) => h.id);
    return {
      query: q,
      model: s.model,
      runtime: s.runtime,
      answer: parsed.evidence || "",
      raw: raw.slice(0, 800),
      selected: chosen,
      selectedIds: chosen.map((x) => x.id),
      reformulations: parsed.reformulations,
      deterministicTop,
      changed: chosen.some((x, i) => x.id !== deterministicTop[i]),
      fallback: false,
      reason: "",
      latencyMs,
      maxLatencyMs,
      candidateCount: hits.length,
      promptTokens: met[4],
      generatedTokens: met[3],
      decodeTs: Math.round(met[1] * 10) / 10,
    };
  } catch (e) {
    return fallbackResult(q, hits, "сбой LLM: " + String((e && e.message) || e));
  }
}
