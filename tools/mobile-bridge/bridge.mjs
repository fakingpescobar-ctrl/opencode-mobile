#!/usr/bin/env node
// bridge.mjs — мост к opencode serve на мобильном устройстве через adb forward.
//
// Позволяет opencode-агенту (или человеку) напрямую общаться с мобильной
// моделью big-pickle, работающей локально на телефоне (127.0.0.1:4096).
//
// Команды:
//   bridge.mjs ping                      — проверка связи + активная сессия
//   bridge.mjs sessions [--limit N]      — список сессий
//   bridge.mjs open [--title T] [--dir D] — создать новую сессию
//   bridge.mjs ask <text> [...]          — отправить, ВЫВЕСТИ чистый текст (для агента)
//   bridge.mjs chat <text> [...]         — отправить, pretty-вывод (для человека)
//   bridge.mjs history [--limit N]       — история активной сессии
//   bridge.mjs active [--set ID]         — показать/задать активную сессию
//   bridge.mjs clean                     — удалить активную сессию
//
// Изменение активной сессии: --new (создать), --session ID (выбрать существующую).
//
// Выход ask/chat: тело-answer — чистый текст ответа; ошибки идут в stderr,
// есть body.error для машинного чтения. exit 0 = успех, 1 = ошибка, 2 = usage.

import { spawn, spawnSync } from "node:child_process"
import { readFileSync, writeFileSync, existsSync, mkdirSync, appendFileSync } from "node:fs"
import { createHash } from "node:crypto"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"
import { parseArgs } from "node:util"

// ---------------------------------------------------------------------------
// Константы / конфиг
// ---------------------------------------------------------------------------

const ADB = process.env.ADB_PATH || `${process.env.LOCALAPPDATA}\\Android\\Sdk\\platform-tools\\adb.exe`
const HOST_PORT = 4098 // порт на ПК (forward); 4096/4097 заняты локальным opencode serve
const DEVICE_PORT = 4096 // порт serve на телефоне
const STATE_FILE = join(dirname(fileURLToPath(import.meta.url)), "state.json")
const DEFAULT_DIR = "/storage/emulated/0/Documents/OpencodeTerminal"
const DEFAULT_PROVIDER = "opencode"
const DEFAULT_MODEL = "big-pickle"
const REQ_TIMEOUT_MS = 120000

// ---------------------------------------------------------------------------
// Маленькие помощники (pure, без сайд-эффектов)
// ---------------------------------------------------------------------------

const hashText = (s) => createHash("sha1").update(s).digest("hex").slice(0, 24)

function loadState() {
  if (!existsSync(STATE_FILE)) return { activeSessionID: null }
  try {
    return JSON.parse(readFileSync(STATE_FILE, "utf8"))
  } catch {
    return { activeSessionID: null }
  }
}

function saveState(state) {
  mkdirSync(dirname(STATE_FILE), { recursive: true })
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2))
}

// Вытащить из ответа serve весь текст (части типа text), склеив в один блок.
function collectText(msg) {
  const parts = Array.isArray(msg?.parts) ? msg.parts : []
  const chunks = parts
    .filter((p) => p?.type === "text" && typeof p.text === "string" && p.text.length > 0)
    .map((p) => p.text)
  return chunks.join("\n").trim()
}

// ---------------------------------------------------------------------------
// Слой общения с adb / forward
// ---------------------------------------------------------------------------

function adb(args) {
  const res = spawnSync(ADB, args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 })
  if (res.error) {
    throw new Error(`adb не запустился: ${res.error.message} (${ADB})`)
  }
  return { code: res.status, stdout: res.stdout || "", stderr: res.stderr || "" }
}

let forwardEnsured = false

function ensureForward() {
  if (forwardEnsured) return
  const list = adb(["forward", "--list"]).stdout
  if (list.includes(`tcp:${HOST_PORT}`)) {
    forwardEnsured = true
    return
  }
  const res = adb(["forward", `tcp:${HOST_PORT}`, `tcp:${DEVICE_PORT}`])
  if (res.code !== 0 || res.stderr.trim()) {
    throw new Error(`не удалось создать adb forward: ${res.stderr.trim() || res.stdout.trim()}`)
  }
  forwardEnsured = true
}

// ---------------------------------------------------------------------------
// HTTP к serve (локально через forward)
// ---------------------------------------------------------------------------

async function serve(pathname, { method = "GET", body } = {}) {
  ensureForward()
  const url = `http://127.0.0.1:${HOST_PORT}${pathname}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQ_TIMEOUT_MS)
  try {
    const res = await fetch(url, {
      method,
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    })
    const text = await res.text()
    let json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      // оставить null, обработаем как ошибку ниже
    }
    if (!res.ok) {
      const detail = json?.data?.message || text.slice(0, 300)
      throw new Error(`serve ${method} ${pathname} → ${res.status}: ${detail}`)
    }
    return json
  } catch (e) {
    if (e.name === "AbortError") {
      throw new Error(`serve ${method} ${pathname}: таймаут ${REQ_TIMEOUT_MS}ms`)
    }
    throw e
  } finally {
    clearTimeout(timer)
  }
}

// ---------------------------------------------------------------------------
// Бизнес-операции над serve
// ---------------------------------------------------------------------------

async function listSessions(limit = 30) {
  const arr = await serve("/session")
  const sessions = Array.isArray(arr) ? arr : []
  return sessions
    .filter((s) => !s.time?.archived)
    .sort((a, b) => (b.time?.updated || 0) - (a.time?.updated || 0))
    .slice(0, limit)
}

async function createSession({ title, dir = DEFAULT_DIR }) {
  return serve("/session", {
    method: "POST",
    body: { directory: dir, title: title || `bridge-${hashText(String(Date.now()))}` },
  })
}

async function sendMessage(sessionID, text) {
  return serve(`/session/${sessionID}/message`, {
    method: "POST",
    body: {
      parts: [{ type: "text", text }],
      model: { providerID: DEFAULT_PROVIDER, modelID: DEFAULT_MODEL },
    },
  })
}

// Огнём и забыл: дочерний процесс держит POST до конца генерации (не рвёт
// соединение), иначе serve отменяет обработку и сообщение НЕ записывается.
// Возвращаемся сразу — ответ агента увидим через watch.
function sayFireAndForget(sessionID, text) {
  const script = `
    const sid = process.argv[1], text = process.argv[2]
    fetch("http://127.0.0.1:${HOST_PORT}/session/" + sid + "/message", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        parts: [{ type: "text", text }],
        model: { providerID: "${DEFAULT_PROVIDER}", modelID: "${DEFAULT_MODEL}" },
      }),
    }).then((r) => r.text()).then(() => process.exit(0)).catch(() => process.exit(1))
  `
  const child = spawn(process.execPath, ["-e", script, sessionID, text], {
    stdio: "ignore",
    detached: true,
    windowsHide: true,
  })
  child.unref()
}

async function cmdSay(sessionID, text) {
  try {
    const sid = sessionID || loadState().activeSessionID
    if (!sid) {
      err("no session (use --session ID or set active)")
      return
    }
    sayFireAndForget(sid, text)
    out(`sent (fire-and-forget): ${sid}`, { sessionID: sid })
  } catch (e) {
    err(e.message)
  }
}

async function getMessages(sessionID) {
  const arr = await serve(`/session/${sessionID}/message`)
  return Array.isArray(arr) ? arr : []
}

async function abortSession(sessionID) {
  return serve(`/session/${sessionID}/abort`, { method: "POST" })
}

// ---------------------------------------------------------------------------
// Вывод
// ---------------------------------------------------------------------------

function out(msg, body = {}) {
  process.stdout.write(JSON.stringify({ ...body, answer: msg }) + "\n")
}

function err(msg, body = {}) {
  const obj = { error: msg, ...body }
  process.stderr.write(JSON.stringify(obj))
  process.exitCode = 1
}

// ---------------------------------------------------------------------------
// Команды
// ---------------------------------------------------------------------------

function cmdPing() {
  return listSessions(1)
    .then((sessions) => {
      const state = loadState()
      out("connect ok", {
        version: sessions[0]?.version || "unknown",
        hostPort: `127.0.0.1:${HOST_PORT}`,
        activeSessionID: state.activeSessionID,
      })
    })
    .catch((e) => err(e.message))
}

function cmdSessions(limit) {
  return listSessions(limit).then((sessions) => {
    const rows = sessions.map((s) => ({
      id: s.id,
      title: s.title || "",
      dir: s.directory || "",
      updated: s.time?.updated || null,
      cost: s.cost ?? 0,
      tokensIn: s.tokens?.input ?? 0,
      tokensOut: s.tokens?.output ?? 0,
    }))
    out(`sessions: ${rows.length}`, { sessions: rows })
  }).catch((e) => err(e.message))
}

async function cmdOpen(title, dir) {
  try {
    const s = await createSession({ title, dir })
    const state = loadState()
    state.activeSessionID = s.id
    saveState(state)
    out(`session created: ${s.id}`, { sessionID: s.id, title: s.title })
  } catch (e) {
    err(e.message)
  }
}

async function resolveSession({ newSession, sessionID }) {
  if (newSession) {
    const s = await createSession({ title: "bridge chat" })
    return s.id
  }
  if (sessionID) return sessionID
  const state = loadState()
  if (state.activeSessionID) return state.activeSessionID
  const recent = await listSessions(1)
  if (recent.length > 0) return recent[0].id
  const s = await createSession({ title: "bridge chat" })
  return s.id
}

async function conversation({ text, newSession, sessionID, pretty }) {
  if (!text) {
    err("text required", { usage: "syntax_ok" })
    return
  }
  try {
    const sid = await resolveSession({ newSession, sessionID })
    const state = loadState()
    if (state.activeSessionID !== sid) {
      state.activeSessionID = sid
      saveState(state)
    }
    const msg = await sendMessage(sid, text)
    const answer = collectText(msg)
    if (pretty) {
      const head = msg?.info?.finish === "stop" ? "" : `\n[finish: ${msg?.info?.finish || "?"}]`
      process.stdout.write(`${answer || "(no text part)"}${head}\n`)
    } else {
      out(answer, {
        sessionID: sid,
        model: msg?.info?.modelID || DEFAULT_MODEL,
        provider: msg?.info?.providerID || DEFAULT_PROVIDER,
        tokens: msg?.info?.tokens || null,
        cost: msg?.info?.cost ?? null,
        finish: msg?.info?.finish || null,
      })
    }
  } catch (e) {
    err(e.message)
  }
}

async function cmdHistory(limit) {
  const state = loadState()
  if (!state.activeSessionID) {
    err("no active session", { hint: "set via --set, ask, or open" })
    return
  }
  try {
    const msgs = await getMessages(state.activeSessionID)
    const tail = msgs.slice(-limit)
    const rows = tail.map((m) => ({
      id: m.info?.id || m.id,
      role: m.info?.role || "?",
      model: m.info?.modelID || null,
      text: (m.parts || [])
        .filter((p) => p?.type === "text")
        .map((p) => p.text)
        .join("\n"),
    }))
    out(`history ${rows.length}`, { sessionID: state.activeSessionID, messages: rows })
  } catch (e) {
    err(e.message)
  }
}

function cmdActive(setID) {
  const state = loadState()
  if (setID !== undefined) {
    state.activeSessionID = setID || null
    saveState(state)
    out(`active: ${setID || "(cleared)"}`, { activeSessionID: state.activeSessionID })
    return
  }
  out(`active: ${state.activeSessionID || "(none)"}`, { activeSessionID: state.activeSessionID })
}

async function cmdClean() {
  const state = loadState()
  if (!state.activeSessionID) {
    err("no active session")
    return
  }
  try {
    await serve(`/session/${state.activeSessionID}`, { method: "DELETE" })
    state.activeSessionID = null
    saveState(state)
    out("cleaned")
  } catch (e) {
    err(e.message)
  }
}

async function cmdAbort(sessionID) {
  try {
    const sid = sessionID || loadState().activeSessionID
    if (!sid) {
      err("no session to abort (use --session ID or set active)")
      return
    }
    await abortSession(sid)
    out(`abort sent: ${sid}`, { sessionID: sid })
  } catch (e) {
    err(e.message)
  }
}

// ---------------------------------------------------------------------------
// Watch — живой стрим событий всех сессий (SSE /event)
// ---------------------------------------------------------------------------

// Разбор SSE-потока: набор строк "field: value" через "\n\n".
async function* parseSSE(res) {
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep
      while ((sep = buffer.indexOf("\n\n")) !== -1) {
        const raw = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        const data = raw
          .split("\n")
          .filter((l) => l.startsWith("data:"))
          .map((l) => l.slice(5).trimStart())
          .join("\n")
        if (data) yield data
      }
    }
  } finally {
    reader.releaseLock()
  }
}

const pad = (n) => String(n).padStart(2, "0")
const stamp = () => {
  const d = new Date()
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// Опасные паттерны для --guard: греп по строковому представлению tool-входа.
const GUARD_PATTERNS = [
  { re: /\brm\s+-rf\b/i, why: "rm -rf" },
  { re: /\bgit\s+push\s+(-f|--force)\b/, why: "git push --force" },
  { re: /\bdrop\s+table\b/i, why: "DROP TABLE" },
  { re: /\bformat\s+[a-z]:/i, why: "format drive" },
  { re: /\bcipher\s+\/w/i, why: "cipher /w (wipe)" },
  { re: /\bdel\s+\/s\b/i, why: "del /s" },
  { re: /\bshutdown\s+\/s\b/i, why: "shutdown" },
]

async function cmdWatch({ logFile, jsonlFile, sessionFilter, guard }) {
  const { appendFileSync } = await import("node:fs")
  const emit = (line) => {
    process.stdout.write(line + "\n")
    if (logFile) appendFileSync(logFile, line + "\n", "utf8")
  }
  const emitRaw = (evt) => {
    if (jsonlFile) appendFileSync(jsonlFile, JSON.stringify(evt) + "\n", "utf8")
  }

  // Кэш ролей по messageID и последний текст/выход по partID (для диффа стриминга)
  const roles = new Map()
  const lastText = new Map()
  const lastFinish = new Map()

  // Краткое действие инструмента из input/state (для читабельной строки).
  const toolAction = (part) => {
    const st = part?.state || {}
    const input = st.input
    const title = typeof st.title === "string" ? st.title : ""
    const pick = (v) => (typeof v === "string" ? v : v === undefined ? "" : JSON.stringify(v))
    const cmd = pick(input?.command)
    const qry = pick(input?.query)
    const path = pick(input?.filePath ?? input?.path)
    if (cmd) return cmd
    if (qry) return qry
    if (path && title) return `${path} — ${title}`
    if (path) return path
    if (title) return title
    const args = input ? JSON.stringify(input).slice(0, 160) : ""
    return args
  }

  const dot = (s) => {
    const t = typeof s === "string" ? s : JSON.stringify(s ?? "")
    return t.length > 200 ? t.slice(0, 200) + "…" : t
  }

  const handleEvent = (evt) => {
    emitRaw(evt)
    const payload = evt?.payload || evt
    const type = payload?.type
    const props = payload?.properties || {}
    if (sessionFilter && props.sessionID !== sessionFilter) return
    switch (type) {
      case "server.connected": {
        emit(`[${stamp()}] watching events${sessionFilter ? ` (session ${sessionFilter})` : " (all sessions)"}`)
        break
      }
      case "server.heartbeat":
        break
      case "message.updated": {
        const info = props.info
        if (info?.role) roles.set(`${info.id}`, info.role)
        if (info?.role === "assistant" && info.finish) {
          const key = `${info.id}:${info.finish}`
          if (!lastFinish.has(key)) {
            lastFinish.set(key, true)
            emit(`[${stamp()}] [assistant] ✦ finish=${info.finish} tokens=${info.tokens?.total ?? "?"} cost=${info.cost ?? 0}`)
          }
        }
        break
      }
      case "message.part.updated": {
        const part = props.part
        if (!part || typeof part !== "object") break
        const role = roles.get(`${part.messageID}`) || "assistant"
        if (part.type === "text") {
          if (typeof part.text !== "string") break
          const key = `${part.messageID}:${part.id}`
          const prev = lastText.get(key) || ""
          if (part.text.startsWith(prev)) {
            const diff = part.text.slice(prev.length)
            if (diff) emit(`[${stamp()}] [${role}] ${diff}`)
          } else {
            // часть переписана целиком (например перегенерация) — показать свежую
            emit(`[${stamp()}] [${role}] ${part.text}`)
          }
          lastText.set(key, part.text)
          break
        }
        if (part.type === "tool") {
          const name = part.tool || "?"
          const action = toolAction(part)
          const key = `${part.messageID}:${part.id}`
          const prev = lastText.get(key) || ""
          const now = name + (action ? ` ${action}` : "")
          // Дубликаты (прогресс-апдейты того же тула) не спамим.
          if (now !== prev) {
            lastText.set(key, now)
            emit(`[${stamp()}] [tool] ${"─".repeat(2)} ${role} → ${name}: ${dot(action)}`)
            if (guard) {
              const hay = JSON.stringify(part).slice(0, 4000)
              for (const p of GUARD_PATTERNS) {
                if (p.re.test(hay)) {
                  emit(`[${stamp()}] ⚠ GUARD: ${name} — ${p.why} (session ${props.sessionID || "?"})`)
                }
              }
            }
            const outp = part.state?.output
            if (typeof outp === "string" && outp.trim()) {
              const head = outp.trim().split("\n")[0]
              if (head) emit(`[${stamp()}] [tool] ${"─".repeat(2)} output: ${dot(head)}`)
            }
          }
          break
        }
        if (part.type === "step-start" || part.type === "reasoning") {
          if (typeof part.text !== "string") break
          const key = `${part.messageID}:${part.id}`
          const prev = lastText.get(key) || ""
          if (part.text.startsWith(prev)) {
            const diff = part.text.slice(prev.length)
            if (diff && process.env.BRIDGE_DEBUG) emit(`[${stamp()}] [${part.type}] ${diff}`)
          }
          lastText.set(key, part.text)
          break
        }
        break
      }
      case "session.updated": {
        const info = props.info
        if (info?.title) emit(`[${stamp()}] [session] title="${info.title}" sessionID=${props.sessionID}`)
        break
      }
      case "message.part.delta": {
        // Современный стриминг-формат: props {sessionID, messageID, partID, field, delta}.
        // field="text" (или "reasoning") — доклеиваем к накопленному тексту части и
        // выводим диффом (каждая delta — кусочек растущего ответа).
        const field = props.field
        const partID = props.partID
        const delta = props.delta
        if (typeof delta !== "string" || typeof partID !== "string") break
        if (field !== "text" && field !== "reasoning") break
        const role = roles.get(`${props.messageID}`) || "assistant"
        const key = `${props.messageID}:${partID}`
        const prev = lastText.get(key) || ""
        lastText.set(key, prev + delta)
        if (delta) emit(`[${stamp()}] [${field === "reasoning" ? "reasoning" : role}] ${delta}`)
        break
      }
      case "message.removed":
      case "message.part.removed":
      case "session.deleted":
        break
      default:
        if (type && !type.startsWith("server.")) {
          // прочие события — тихо, но для отладки можно включить DEBUG
          if (process.env.BRIDGE_DEBUG) emit(`[${stamp()}] [evt] ${type}`)
        }
    }
  }

  try {
    ensureForward()
    const url = `http://127.0.0.1:${HOST_PORT}/event`
    let attempts = 0
    for (;;) {
      try {
        const res = await fetch(url)
        if (!res.ok || !res.body) throw new Error(`event stream → ${res.status}`)
        attempts = 0
        for await (const data of parseSSE(res)) {
          let evt
          try {
            evt = JSON.parse(data)
          } catch {
            continue
          }
          handleEvent(evt)
        }
        // поток закрылся (не ошибка)
        emit(`[${stamp()}] stream closed, reconnecting...`)
      } catch (e) {
        if (e.name === "AbortError") throw e
        attempts++
        if (attempts > 3) throw new Error(`event stream lost: ${e.message}`)
        emit(`[${stamp()}] connection error (${e.message}), retry ${attempts}/3 in 2s...`)
        await new Promise((r) => setTimeout(r, 2000))
      }
    }
  } catch (e) {
    err(e.message)
  }
}

// ---------------------------------------------------------------------------
// Relay — автопилот-наблюдатель: сам следит за сессией мобильного агента и
// шлёт реакции по правилам (зацикливание, ошибки, опасные команды, завершение).
// Это «фоновое присутствие» ПК-агента, когда сам агент не может крутить цикл.
// ---------------------------------------------------------------------------

const RELAY_ERROR_RE = /error|failed|timed out|unable to connect|ECONNREFUSED|exited with code/i

// «Ящик входящих» для ПК-модели: relay кладёт сюда запросы мобильного агента
// по триггер-договору. ПК-модель читает хвост файла и отвечает агенту.
const INBOX_FILE = join(dirname(fileURLToPath(import.meta.url)), "pc-inbox.jsonl")

// Договор с мобильным агентом: он ставит слово в конце сообщения → relay
// пересылает запрос ПК-модели (пишет в INBOX_FILE) и для «проверь с ПК»
// дополнительно делает авто-проверку MCP с ПК и отвечает фактами.
const RELAY_TRIGGERS = [
  { id: "verify_pc", re: /(?:проверь|проверка|проверить)\s+с\s+пк|проверк[аи]?\s+с\s+пк|сверь.*\bпк\b/i, label: "проверь с ПК" },
  { id: "stuck", re: /шаг\s+застрял|застрял/i, label: "шаг застрял" },
  { id: "check_src", re: /проверь\s+исходники|сверь\s+исходники/i, label: "проверь исходники" },
  { id: "big_task", re: /большая\s+задача|многошагов/i, label: "большая задача" },
]

function relayRuleLoop({ sessionID, reportFile, intervalMs, maxReplies, guard }) {
  const log = (obj) => {
    const line = JSON.stringify({ ts: Date.now(), ...obj }) + "\n"
    if (reportFile) appendFileSync(reportFile, line, "utf8")
    process.stdout.write(line)
  }

  // состояние для правил
  const seenMsgIDs = new Set() // mid уже виден (новое сообщение = триггер)
  const seenToolKey = new Map() // "tool|input" → count
  const loopWarned = new Set() // ключ зацикливания уже предупреждён
  const errWarned = new Set() // текст ошибки уже прокомментирован
  const sentTriggers = new Set() // `${triggerId}:${mid}` — триггер уже доставлен
  const sentReplies = new Set() // хэш текста реакции — одну реакцию не шлём дважды
  let lastReactionAt = 0
  let repliesLeft = maxReplies
  let coldStart = true // первый тик только запоминает состояние, без реакций (не спамим стариной)

  const maybeSay = async (text) => {
    if (repliesLeft <= 0) {
      log({ type: "reply_skipped", reason: "max_replies", text: text.slice(0, 80) })
      return
    }
    const now = Date.now()
    if (now - lastReactionAt < 3 * 60_000) {
      log({ type: "reply_skipped", reason: "cooldown_3m", text: text.slice(0, 80) })
      return
    }
    const h = hashText(text)
    if (sentReplies.has(h)) {
      log({ type: "reply_skipped", reason: "duplicate_text", text: text.slice(0, 80) })
      return
    }
    sentReplies.add(h)
    lastReactionAt = now
    repliesLeft--
    try {
      sayFireAndForget(sessionID, text)
      log({ type: "reply_sent", text })
    } catch (e) {
      log({ type: "reply_error", error: e.message })
    }
  }

  return async function tick() {
    let msgs
    try {
      msgs = await getMessages(sessionID)
    } catch (e) {
      log({ type: "poll_error", error: e.message })
      return
    }
    // последние 6 сообщений для анализа
    const tail = msgs.slice(-6)

    // Холодный старт: только запомнить текущее состояние, НЕ реагировать на прошлое
    // (иначе первый тик комментирует старые события и сжигает лимит авто-ответов).
    if (coldStart) {
      coldStart = false
      for (const m of tail) {
        const mid = m.info?.id || m.id
        if (mid) seenMsgIDs.add(mid)
        for (const part of m.parts || []) {
          if (part.type !== "tool") continue
          const input = part.state?.input
          const inStr = typeof input === "string" ? input : JSON.stringify(input ?? "")
          seenToolKey.set(`${part.tool || "?"}|${inStr.slice(0, 120)}`, 1)
        }
      }
      log({ type: "cold_start_snapshot", sessionID })
      return
    }
    for (const m of tail) {
      const mid = m.info?.id || m.id
      if (!mid || seenMsgIDs.has(mid)) continue
      seenMsgIDs.add(mid)
      const role = m.info?.role
      const parts = m.parts || []
      const text = parts.filter((p) => p.type === "text").map((p) => p.text).join("\n")

      // Роль пользователь → это команда человеку/агенту; реагируем только на assistant.
      if (role !== "assistant") continue

      // 1. Ошибка в тексте ответа/выводе тулов — реагируем один раз на конкретную
      //    ошибку (по сниппету), а не на каждое упоминание.
      const errMatch = text.match(RELAY_ERROR_RE)
      if (errMatch) {
        const key = errMatch[0].slice(0, 40)
        if (!errWarned.has(key)) {
          errWarned.add(key)
          await maybeSay(
            "ПК-агент (автопилот): в твоём ходе упоминается ошибка/сбой («" +
              errMatch[0] +
              "»). Это штатно? Если нет — кратко: что пошло не так и что пробуешь дальше.",
          )
        }
      }

      // 2. Триггер-договор: агент явно зовёт ПК («проверь с ПК», «шаг застрял»,
      //    «проверь исходники», «большая задача»). Доставляем запрос ПК-модели
      //    в INBOX_FILE; для verify_pc делаем авто-проверку MCP и отвечаем фактами.
      const trg = RELAY_TRIGGERS.find((t) => t.re.test(text))
      if (trg) {
        const tkey = `${trg.id}:${mid}`
        if (!sentTriggers.has(tkey)) {
          sentTriggers.add(tkey)
          const inbox = { ts: Date.now(), trigger: trg.id, label: trg.label, mid, text: text.slice(0, 1500) }
          appendFileSync(INBOX_FILE, JSON.stringify(inbox) + "\n", "utf8")
          log({ type: "trigger", trigger: trg.id, label: trg.label, mid })
          if (trg.id === "verify_pc") {
            try {
              await ensureForward()
              const r = await fetch(`http://127.0.0.1:${HOST_PORT}/mcp`, { signal: AbortSignal.timeout(15000) })
              const status = await r.json()
              const summary = Object.entries(status || {})
                .map(([k, v]) => `${k}=${v?.status || v}`)
                .join(", ")
              inbox.check = summary
              appendFileSync(INBOX_FILE, JSON.stringify(inbox) + "\n", "utf8")
              await maybeSay("ПК-агент (автопилот): принял «проверь с ПК» — авто-проверка MCP с ПК: " + summary)
            } catch (e) {
              inbox.check = "error: " + e.message
              appendFileSync(INBOX_FILE, JSON.stringify(inbox) + "\n", "utf8")
              log({ type: "trigger_check_error", error: e.message })
            }
          }
        }
      }
      // 3. Tool-паттерны
      for (const part of parts) {
        if (part.type !== "tool") continue
        const name = part.tool || "?"
        const input = part.state?.input
        const inStr = typeof input === "string" ? input : JSON.stringify(input ?? "")
        const key = `${name}|${inStr.slice(0, 120)}`
        const cnt = (seenToolKey.get(key) || 0) + 1
        seenToolKey.set(key, cnt)

        // 3a. guard: опасные команды
        if (guard) {
          const hay = JSON.stringify(part).slice(0, 4000)
          for (const p of GUARD_PATTERNS) {
            if (p.re.test(hay)) {
              log({ type: "guard", tool: name, why: p.why })
            }
          }
        }

        // 3b. зацикливание: один и тот же вызов 2+ раза
        if (cnt >= 2 && !loopWarned.has(key)) {
          loopWarned.add(key)
          await maybeSay(
            "ПК-агент (автопилот): вижу повторный вызов " +
              name +
              " с одинаковым входом (" +
              inStr.slice(0, 100) +
              "). Если это не итерация с прогрессом — объясни, что мешает, или смени подход.",
          )
        }
      }
    }
  }
}

async function cmdRelay({ sessionID, reportFile, intervalSec, maxReplies, guard }) {
  const sid = sessionID || loadState().activeSessionID
  if (!sid) {
    err("no session (use --session ID or set active)")
    return
  }
  const intervalMs = Math.max(10, intervalSec) * 1000
  const tick = relayRuleLoop({ sessionID: sid, reportFile, intervalMs, maxReplies: maxReplies ?? 3, guard })
  try {
    ensureForward()
    process.stdout.write(`relay on ${sid} every ${intervalMs / 1000}s, replies left: ${maxReplies ?? 3}\n`)
    for (;;) {
      await tick()
      await new Promise((r) => setTimeout(r, intervalMs))
    }
  } catch (e) {
    err(e.message)
  }
}

async function cmdRecent(limit, hours) {
  try {
    const sessions = await listSessions(50)
    const cutoff = hours ? Date.now() - hours * 3600_000 : 0
    const fresh = sessions.filter((s) => (s.time?.updated || 0) >= cutoff)
    const report = []
    for (const s of fresh.slice(0, 5)) {
      const msgs = await getMessages(s.id)
      const tail = msgs.slice(-limit)
      const rows = tail.map((m) => ({
        role: m.info?.role || "?",
        model: m.info?.modelID || null,
        text: (m.parts || [])
          .filter((p) => p?.type === "text" && typeof p.text === "string")
          .map((p) => p.text)
          .join("\n"),
      }))
      report.push({
        id: s.id,
        title: s.title || "",
        updated: s.time?.updated || null,
        messages: rows,
      })
    }
    out(`recent: ${report.length} sessions`, { sessions: report })
  } catch (e) {
    err(e.message)
  }
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function usage() {
  process.stderr.write(
    [
      "bridge.mjs — мост к мобильной модели opencode serve",
      "",
      "  ping                    проверка связи + активная сессия",
      "  sessions [--limit N]    список сессий",
      "  open [--title T] [--dir D]  создать новую сессию (и сделать активной)",
      "  ask  <text> [...]       отправить и ДОЖДАТЬСЯ ответа, stdout = JSON (для агента)",
      "  chat <text> [...]       отправить и дождаться, pretty-вывод (для человека)",
      "  say  <text> [...]       ОГНЁМ И ЗАБЫЛ: гарантированно записать в сессию, вернуться сразу",
      "                          (самое то для вмешательства в работающего мобильного агента —",
      "                          ответ потом видно через watch)",
      "  history [--limit N]     история акт. сессии",
      "  active [--set ID]       показать/задать активную сессию",
      "  clean                   удалить активную сессию",
      "  abort [--session ID]    прервать текущую генерацию в сессии",
      "  watch [--log FILE] [--jsonl FILE] [--session ID] [--guard]  стрим всех диалогов в реальном времени",
      "  relay [--session ID] [--report FILE] [--interval S] [--max-replies N] [--guard]",
      "                          АВТОПИЛОТ: сам следит за сессией и шлёт реакции",
      "                          (зацикливание, ошибки в выводах, опасные команды, завершение)",
      "  recent [--limit N] [--hours H]     последние сообщения недавних сессий",
      "",
      "Флаги ask/chat:",
      "  --new        писать в новую сессию (и сделать активной)",
      "  --session ID писать в конкретную сессию",
      "",
      "Флаги watch:",
      "  --log FILE   дублировать человекочитаемый вывод в файл (UTF-8)",
      "  --jsonl FILE писать ВСЕ сырые события в JSONL (для diff-наблюдения агентом)",
      "  --session ID смотреть только одну сессию",
      "  --guard      предупреждать при опасных tool-командах (rm -rf, push --force и т.п.)",
      "",
      "Флаги recent:",
      "  --limit N    сообщений на сессию (по умолчанию 30)",
      "  --hours H    только сессии, обновлённые за последние H часов",
      "",
      "Флаги relay:",
      "  --session ID   какая сессия (по умолчанию активная)",
      "  --report FILE  JSONL-лог всех реакций (для чтения агентом)",
      "  --interval S   опрос сессии, секунды (по умолчанию 25)",
      "  --max-replies N  максимум авто-сообщений агенту (по умолчанию 3, защита от спама)",
      "  --guard        предупреждать при опасных командах",
      "",
      "env: ADB_PATH, ADB no default search in PATH",
      "",
    ].join("\n"),
  )
  process.exit(2)
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv.length === 0) return usage()

  const [cmd, ...rest] = argv
  let values, positionals
  try {
    const parsed = parseArgs({ args: rest, allowPositionals: true, options: {
      limit: { type: "string" },
      title: { type: "string" },
      dir: { type: "string" },
      session: { type: "string" },
      set: { type: "string" },
      new: { type: "boolean", default: false },
      help: { type: "boolean", default: false },
      log: { type: "string" },
      jsonl: { type: "string" },
      guard: { type: "boolean", default: false },
      report: { type: "string" },
      interval: { type: "string" },
      "max-replies": { type: "string" },
      hours: { type: "string" },
    } })
    values = parsed.values
    positionals = parsed.positionals
  } catch (e) {
    process.stderr.write(`bridge: ${e.message}\n`)
    return usage()
  }

  if (values.help) return usage()
  const opts = values

  const limit = opts.limit !== undefined ? Number(opts.limit) : 30
  // Все позиционные аргументы — это текст сообщения. parseArgs уже обработал
  // флаги и `--` терминатор, так что слова типа "--new" внутри текста сохранятся.
  const text = positionals.join(" ")

  switch (cmd) {
    case "ping":        return await cmdPing()
    case "sessions":    return await cmdSessions(limit)
    case "open":        return await cmdOpen(opts.title, opts.dir)
    case "ask":         return await conversation({ text, newSession: opts.new, sessionID: opts.session, pretty: false })
    case "chat":        return await conversation({ text, newSession: opts.new, sessionID: opts.session, pretty: true })
    case "say":         return await cmdSay(opts.session, text)
    case "history":     return await cmdHistory(limit)
    case "active":      return cmdActive(opts.set)
    case "clean":       return await cmdClean()
    case "abort":       return await cmdAbort(opts.session)
    case "watch":       return await cmdWatch({ logFile: opts.log, jsonlFile: opts.jsonl, sessionFilter: opts.session, guard: opts.guard })
    case "relay":       return await cmdRelay({ sessionID: opts.session, reportFile: opts.report, intervalSec: opts.interval !== undefined ? Number(opts.interval) : 25, maxReplies: opts["max-replies"] !== undefined ? Number(opts["max-replies"]) : 3, guard: opts.guard })
    case "recent":      return await cmdRecent(limit, opts.hours !== undefined ? Number(opts.hours) : undefined)
    default:
      process.stderr.write(`bridge: unknown command "${cmd}"\n`)
      return usage()
  }
}

main()
