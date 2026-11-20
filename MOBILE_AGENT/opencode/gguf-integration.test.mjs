#!/usr/bin/env bun
// Gate загрузчика GGUF: ассет-манифест, Kotlin-каталог/хранилище/UI.
// Никаких сетей и закачек 400 МБ — только контракт и protections.
// Модели в APK не включаются: проверяем, что в staging лежит только JSON.

import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..");
const SRC = join(ROOT, "tmp", "diag-src") + "/";

let ok = 0;
let bad = 0;
const failures = [];
function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) ok++;
  else {
    bad++;
    failures.push((label || "eq") + ": actual=" + JSON.stringify(actual) + " expected=" + JSON.stringify(expected));
  }
}
function truthy(value, label) {
  if (value) ok++;
  else { bad++; failures.push("not true: " + (label || "")); }
}
function section(title) { console.log("\n=== " + title + " ==="); }

const read = (f) => readFileSync(SRC + f, "utf8");

// ---------------------------------------------------------------- 1. Манифест
section("1. Манифест моделей: ассет = источник истины");
{
  const src = readFileSync(join(ROOT, "tools", "llama-bench", "models.json"));
  const staged = readFileSync(SRC + "models.json");
  eq(Buffer.compare(src, staged), 0, "models.json: байт в байт");
  const models = JSON.parse(staged.toString("utf8"));
  truthy(models.length >= 5, "каталог не пустой: " + models.length);
  truthy(models.every((m) => m.id && m.file && m.size > 0 && /^[0-9a-f]{64}$/.test(m.sha256)),
    "у каждой модели есть id/file/size/sha256");
  truthy(models.every((m) => m.url.startsWith("https://")), "только https URL");
  const chosen = models.find((m) => m.id === "qwen3-0.6b-q4km");
  truthy(!!chosen, "выбранная модель в каталоге");
  eq(chosen && chosen.size, 396705472, "размер выбранной модели");
  eq(chosen && chosen.sha256, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
    "sha256 выбранной модели");
  const stagedFiles = readdirSync(SRC);
  eq(stagedFiles.filter((f) => f.endsWith(".gguf")).length, 0, "ни одного GGUF в APK/staging");
}

// ---------------------------------------------------------------- 2. Каталог
section("2. Kotlin-каталог: разбор и защита ассета");
{
  const kt = read("GgufModels.kt");
  truthy(kt.includes('ASSET = "llm/models.json"'), "читает ассет llm/models.json");
  truthy(kt.includes("fun parse(json: String)"), "чистый парсер для JVM-теста");
  truthy(kt.includes("PREFERRED = \"qwen3-0.6b-q4km\""), "зафиксированная модель в коде");
  truthy(kt.includes('file.endsWith(".gguf"'), "только .gguf");
  truthy(kt.includes("file.contains('/')") && kt.includes("file.contains('\\\\')"), "защита от path traversal");
  truthy(kt.includes("SHA_HEX") && kt.includes("startsWith(\"https://\")"), "проверка sha256 и https");
  truthy(kt.includes("ids.add(id)"), "отказ от дублей id");
  truthy(kt.includes("@Volatile"), "ассет parsing не повторяется на каждый опрос");
}

// ---------------------------------------------------------------- 3. Хранилище
section("3. Kotlin-хранилище: песочница, докачка, сверка");
{
  const kt = read("GgufStore.kt");
  truthy(kt.includes('REL = "musl"'), "модель рядом с .so в files/musl");
  truthy(kt.includes("OK_SUFFIX"), "маркер готовности только после sha256");
  truthy(kt.includes("FileFetcher.fetch(model.url, target)"), "докачка через общий FileFetcher");
  truthy(kt.includes("target.length() != model.size"), "сверка размера");
  truthy(kt.includes("ModelDownloader.sha256Of(target)"), "сверка sha256");
  truthy(kt.includes("target.delete()"), "при расхождении файл удаляется");
  truthy(kt.includes("ModelDownloader.freeBytes(context)"), "проверка свободного места до старта");
  truthy(kt.includes("withContext(Dispatchers.IO)"), "сеть и хеш не на главном потоке");
}

// ---------------------------------------------------------------- 4. UI и тест
section("4. Единый пульт Memory Core ⚡ и JVM-тест");
{
  const ui = read("DiagnosticsScreen.kt");
  truthy(ui.includes('DiagSection("Memory Core ⚡"'), "единая карточка в диагностике");
  truthy(ui.includes("MemoryCorePanel(context, refreshKey, ggufDl, startGgufDownload)"), "пульт подключён");
  truthy(!ui.includes('DiagSection("Локальная память'), "старая разрозненная секция убрана");
  truthy(!ui.includes('DiagSection("Модели LLM для ядра памяти (GGUF)"'), "отдельная GGUF-секция убрана");
  truthy(ui.includes("GgufStore.download(context, model)"), "кнопка вызывает загрузку с прогрессом");
  truthy(ui.includes("internal sealed interface GgufDownloadState"), "состояние закачки GGUF");

  const panel = read("MemoryCorePanel.kt");
  truthy(panel.includes('DiagSection("Состояние ядра"'), "подсекция статуса");
  truthy(panel.includes('DiagSection("Инструменты и вызовы"'), "подсекция инструментов");
  truthy(panel.includes('DiagSection("Модели LLM (GGUF)"'), "подсекция загрузчика");
  truthy(panel.includes('DiagSection("Записи и удаление"'), "подсекция записей");
  truthy(panel.includes('DiagSection("Технические детали"'), "подсекция технических деталей");
  truthy(panel.includes("MemoryTools.coreTools()"), "список тулов с живого сервера, не константа");
  truthy(panel.includes("MemoryTools.corePack("), "кнопка пак");
  truthy(panel.includes("MemoryTools.coreBench("), "кнопка бенча");
  truthy(panel.includes("memorySection()"), "управление записями внутри пульта");
  truthy(panel.includes('InfoRow("Зачем"'), "в UI объяснено назначение моделей");
  truthy(panel.includes("Семантический сопроцессор ядра"), "пояснение: LLM не заменяет ядро");
  truthy(panel.includes('InfoRow("Источник"'), "в UI указан источник закачки");
  truthy(panel.includes("model.repo + \"/\" + model.file"), "у каждой модели показан repo и файл");
  truthy(panel.includes('InfoRow("Откуда"'), "во время загрузки показан источник");
  truthy(panel.includes("GgufCatalog.byId(context, dl.model)"), "повтор докачки по id из каталога");

  const t = read("GgufCatalogTest.kt");
  truthy(t.includes("parseReadsAllFields"), "тест разбора полей");
  truthy(t.includes("rejectsTraversalAndNonGguf"), "тест path traversal");
  truthy(t.includes("rejectsBadHashUrlSizeAndDuplicates"), "тест sha/https/размера/дублей");
}

// ---------------------------------------------------------------- 5. Guard
section("5. Guard: без мусора и внешних зависимостей");
for (const f of ["GgufModels.kt", "GgufStore.kt", "GgufCatalogTest.kt", "DiagnosticsScreen.kt"]) {
  const src = read(f);
  eq(/[\u4e00-\u9fff\uac00-\ud7af]/.test(src), false, f + ": без CJK-мусора");
  eq(/\bfetch\s*\(["']http/.test(src) && !src.includes("FileFetcher"), false, f + ": сеть только через FileFetcher");
}

console.log("\n" + "=".repeat(56));
console.log("ГЕЙТ ЗАГРУЗЧИКА GGUF: OK=" + ok + " BAD=" + bad);
if (bad) {
  console.log("\nПРОВАЛЫ:\n" + failures.map((f) => "  - " + f).join("\n"));
  process.exit(1);
}
console.log("ВСЕ ПРОВЕРКИ ЗАГРУЗЧИКА ПРОШЛИ");
