// Лемматизатор и якоря для ядра памяти мобайл-агента.
// Собрано из лучшего, что нашли в внешних проектах, но без npm/network в APK:
//   - Snowball RussianStemmer (MPL 2.0, fortnightlabs/snowball-js) — 15 КБ,
//     встроен как russian-stemmer.js; настоящая русская морфология;
//   - Intl.Segmenter (встроен в Bun 1.4) — корректное выделение слов;
//   - доменные синонимы -> латинские якоря для unicode61-FTS;
//   - fuzzysearch (MIT, bevacqua/fuzzysearch) — опечатки, 1 КБ ниже.
//
// Никаких эмбеддингов и внешних вызовов: всё работает офлайн на устройстве.

import { stemRussian } from "./russian-stemmer.js";

// Сегментатор слов из Bun (бесплатно, без зависимостей).
const SEG = typeof Intl !== "undefined" && typeof Intl.Segmenter === "function"
  ? new Intl.Segmenter("ru", { granularity: "word" })
  : null;

const STOP = new Set((
  "и а но да или же ли бы как что чтобы этот эта эти тот та те он она оно они мы вы я ты " +
  "в во на за под над при от до из с со к ко у о об обо по про для без через " +
  "не ни ну вот уже ещё еще тоже лишь только очень более менее так такой такая такое такие " +
  "когда где куда почему зачем какой какая какие который которая которые " +
  "есть был была было были буду будешь будет будем будут быть " +
  "the a an and or but of to in on for with as is are was were be been has have had it its " +
  "this that these those i you he she we they them their our your my me him her us"
).split(/\s+/).filter(Boolean));

// Синонимы домена: все варианты -> один канонический якорь (латиницей).
const SYNONYM_GROUPS = [
  ["memory", "память", "памяти", "памятный", "запоминать", "запомнить", "mem", "memory_recall", "memory_pack", "recall", "вспомнить", "вспоминать", "мемори", "ядро", "бюджет", "токен"],
  ["semantic", "семантика", "семантического", "семантический", "смысл", "смысловой", "по смыслу", "semantics"],
  ["rule", "правило", "правила", "правил", "правилам", "инструкция", "регламент", "rule51", "правило51"],
  ["state", "состояние", "состояния", "состояний", "статус", "current", "текущее"],
  ["journal", "журнал", "журнала", "журналы", "история", "хронология", "log", "лог", "запись"],
  ["report", "отчёт", "отчет", "отчёта", "отчета", "репорт", "reportDir", "report_dir"],
  ["apk", "apk", "сборка", "собрать", "build", "релиз", "release", "версия", "version", "sdk", "android", "device", "cph2747", "прошивка"],
  ["plugin", "плагин", "плагина", "moltbook.js", "moltbook", "extension", "расширение"],
  ["trust", "доверие", "доверия", "сверка", "проверка", "verify", "verification", "auditing", "аудит", "sha", "sha256", "хэш", "хеш", "hash"],
  ["advice", "совет", "советы", "советов", "рекомендация", "рекомендации", "suggestion", "external", "внешний"],
  ["path", "путь", "пути", "каталог", "директория", "folder", "dir", "папка", "файл", "file"],
  ["benchmark", "бенчмарк", "тест", "тесты", "recall@5", "mrr", "ndcg", "longmemeval", "качество", "метрика"],
  ["mcp", "mcp", "инструмент", "инструменты", "tool", "tools", "server", "сервер"],
  ["commit", "коммит", "коммита", "git", "push", "пуш", "main", "репозиторий", "repo"],
];

const WORD_TO_ANCHOR = new Map();
for (const group of SYNONYM_GROUPS) {
  const anchor = group[0];
  for (const word of group) WORD_TO_ANCHOR.set(word.toLowerCase(), anchor);
}

function words(text) {
  const s = String(text == null ? "" : text).toLowerCase().replace(/ё/g, "е");
  if (SEG) return [...SEG.segment(s)].filter((x) => x.isWordLike).map((x) => x.segment);
  return s.replace(/[^a-z0-9а-я\s]/gi, " ").split(/\s+/).filter(Boolean);
}

// Fuzzy-поиск по одному слову (идея bevacqua/fuzzysearch, MIT).
// Возвращает true, если query — подпоследовательность target (опечатки/сокращения).
export function fuzzyMatch(query, target) {
  const q = String(query || "").toLowerCase();
  const t = String(target || "").toLowerCase();
  let i = 0;
  for (let j = 0; j < t.length && i < q.length; j++) {
    if (q[i] === t[j]) i++;
  }
  return i === q.length;
}

export function analyze(text) {
  const list = words(text);
  const lemmas = [];
  const anchors = new Set();
  for (const word of list) {
    if (STOP.has(word)) continue;
    const direct = WORD_TO_ANCHOR.get(word);
    if (direct) anchors.add(direct);
    const base = stemRussian(word);
    if (base.length >= 3 && !STOP.has(base)) lemmas.push(base);
    const byStem = WORD_TO_ANCHOR.get(base);
    if (byStem) anchors.add(byStem);
  }
  return { lemmas: [...new Set(lemmas)], anchors: [...anchors] };
}

export function forTrigram(text) {
  return String(text == null ? "" : text).toLowerCase().replace(/ё/g, "е")
    .replace(/[^a-z0-9а-я\s]/gi, " ").replace(/\s+/g, " ").trim();
}

export function anchorsOf(text) { return analyze(text).anchors; }

export const _internal = { STOP, SYNONYM_GROUPS, WORD_TO_ANCHOR, words };
