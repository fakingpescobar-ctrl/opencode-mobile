#!/usr/bin/env node
// Откуда берётся ключ Moltbook и что видно в статусе, когда его нет.
//
// Ключ - ТОЛЬКО файл MCP_MOLTBOOK_KEY_FILE. `MCP_MOLTBOOK_TOKEN` для этого не
// годится: это локальный пароль MCP-сервера (RuntimeManager.kt:270 генерирует
// его как UUID), а не ключ платформы. Раньше плагин объявлял его и не читал;
// соблазн «пусть будет фолбэком» проверен ревью и отвергнут: такой фолбэк
// отправляет пароль сервера на api.moltbook.com. Здесь это закреплено тестом.
//
// Модуль читает окружение на импорте, поэтому каждый сценарий импортирует его
// заново через cache-busting query.

import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import * as os from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const MOLTBOOK_JS = process.env.MOLTBOOK_JS_PATH
  || join(HERE, "..", "..", "main", "assets", "mcp", "moltbook.js");
const URL_BASE = pathToFileURL(MOLTBOOK_JS).href;

let ok = 0;
let bad = 0;
function eq(actual, expected, label) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) ok++;
  else {
    bad++;
    console.log("  FAIL " + label + ": actual=" + JSON.stringify(actual) + " expected=" + JSON.stringify(expected));
  }
}

const TMP = mkdtempSync(join(os.tmpdir(), "moltbook-key-"));

// ---- 1. файла нет, а токен MCP есть: ключа всё равно нет --------------------------
// Это и есть защита от утечки пароля MCP-сервера на чужой хост.
{
  process.env.MCP_MOLTBOOK_KEY_FILE = join(TMP, "absent");
  process.env.MCP_MOLTBOOK_TOKEN = "mcp-server-password";
  const m = await import(URL_BASE + "?case=token");
  eq(m.moltbookKeySource(), "none", "токен окружения не считается ключом");
  eq(m.readMoltbookKey(), null, "и на запросы он не пойдёт");
}

// ---- 2. ключ из файла, края обрезаны ----------------------------------------------
{
  const file = join(TMP, "moltkey");
  writeFileSync(file, "  key-from-file\n", "utf8");
  process.env.MCP_MOLTBOOK_KEY_FILE = file;
  process.env.MCP_MOLTBOOK_TOKEN = "mcp-server-password";
  const m = await import(URL_BASE + "?case=file");
  eq(m.moltbookKeySource(), "file", "источник - файл");
  eq(m.readMoltbookKey(), "key-from-file", "ключ из файла обрезан по краям");
}

// ---- 3. пустой файл - это «нет ключа», а не повод взять пароль сервера ------------
{
  const file = join(TMP, "empty");
  writeFileSync(file, "   \n", "utf8");
  process.env.MCP_MOLTBOOK_KEY_FILE = file;
  process.env.MCP_MOLTBOOK_TOKEN = "mcp-server-password";
  const m = await import(URL_BASE + "?case=empty");
  eq(m.moltbookKeySource(), "none", "пустой файл - ключа нет");
  eq(m.readMoltbookKey(), null, "и подмены из окружения нет");
}

// ---- 4. ни файла, ни токена - честное «нет» --------------------------------------
{
  process.env.MCP_MOLTBOOK_KEY_FILE = join(TMP, "absent");
  delete process.env.MCP_MOLTBOOK_TOKEN;
  const m = await import(URL_BASE + "?case=none");
  eq(m.moltbookKeySource(), "none", "источник - никакой");
  eq(m.readMoltbookKey(), null, "и ключа нет");
}

rmSync(TMP, { recursive: true, force: true });
console.log((bad ? "\nFAILED " : "\nall green: ") + ok + " ok, " + bad + " broken");
process.exit(bad ? 1 : 0);
