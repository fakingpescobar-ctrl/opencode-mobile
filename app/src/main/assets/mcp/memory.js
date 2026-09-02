#!/usr/bin/env bun
// Local Memory MCP Server (JS, runs on in-app musl-Bun).
// Vector search (TF-IDF + cosine) + knowledge graph, stored in embedded SQLite.
// Transport: JSON-RPC 2.0 over stdio (newline-delimited), MCP protocol.
import { Database } from "bun:sqlite";
import { mkdirSync, appendFileSync } from "fs";
import { dirname, join } from "path";

// ---- Storage location -----------------------------------------------------
const DATA_DIR = process.env.MCP_MEMORY_DIR
  || join(process.env.HOME || "/storage/emulated/0/Documents/OpencodeTerminal", ".memory");
try { mkdirSync(DATA_DIR, { recursive: true }); } catch (e) {}

const DB = new Database(join(DATA_DIR, "memory.sqlite"));
DB.exec(`
CREATE TABLE IF NOT EXISTS memories (
  id TEXT PRIMARY KEY,
  content TEXT NOT NULL,
  type TEXT DEFAULT 'conversation',
  tags TEXT DEFAULT '',
  project TEXT DEFAULT '',
  created INTEGER
);
CREATE TABLE IF NOT EXISTS terms (
  memory_id TEXT,
  term TEXT,
  tfidf REAL,
  project TEXT,
  PRIMARY KEY(memory_id, term)
);
CREATE INDEX IF NOT EXISTS idx_terms_term ON terms(term);
CREATE INDEX IF NOT EXISTS idx_terms_proj ON terms(project);
CREATE TABLE IF NOT EXISTS graph (
  source TEXT, target TEXT, relation TEXT DEFAULT 'related',
  PRIMARY KEY(source, target, relation)
);
CREATE INDEX IF NOT EXISTS idx_graph_src ON graph(source);
CREATE INDEX IF NOT EXISTS idx_graph_tgt ON graph(target);
CREATE VIEW IF NOT EXISTS v_graph AS SELECT * FROM graph;
`);

// ---- Text utilities --------------------------------------------------------
const stop = new Set(("the a an and or but of to in on for with as is are was were be been has have had " +
  "это и на с в не то что как по для при да нет вы ты он она они же более менее все всё если если ты нас вас" +
  "i you he she it we they my your our their me him her us them a an the and or but").split(/\s+/));

function tokenize(s) {
  const t = String(s || "").toLowerCase().replace(/[^\p{L}\p{N}_]+/gu, " ").trim().split(/\s+/);
  const out = [];
  for (const w of t) if (w.length > 1 && !stop.has(w)) out.push(w);
  return out;
}
function hashtags(s) {
  const tags = [];
  const re = /#([\p{L}\p{N}_\-]+)/gu; let m;
  while ((m = re.exec(s))) tags.push(m[1].toLowerCase());
  return tags;
}

// ---- TF-IDF indexing -------------------------------------------------------
function computeTfIdf(content, project) {
  const toks = tokenize(content);
  if (!toks.length) return [];
  const freq = {};
  for (const t of toks) freq[t] = (freq[t] || 0) + 1;
  const idfCache = {};
  const stmt = DB.prepare("SELECT term, COUNT(*) c FROM terms WHERE project=? GROUP BY term");
  for (const r of stmt.all(project || "")) idfCache[r.term] = r.c;
  const totalDoc = (DB.prepare("SELECT COUNT(*) c FROM memories WHERE project=?").get(project || "").c) || 1;
  const N = totalDoc + 1;
  const rows = [];
  for (const term in freq) {
    const df = idfCache[term] || 0;
    const idf = Math.log((N) / (df + 1));
    rows.push([term, freq[term] / toks.length * idf]);
  }
  return rows;
}

function embed(content, project) {
  const toks = tokenize(content);
  if (!toks.length) return {};
  const freq = {};
  for (const t of toks) freq[t] = (freq[t] || 0) + 1;
  const v = {};
  const total = toks.length;
  for (const term in freq) v[term] = freq[term] / total;
  return v;
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (const k in a) { dot += (a[k] || 0) * (b[k] || 0); na += a[k] * a[k]; }
  for (const k in b) nb += b[k] * b[k];
  if (!na || !nb) return 0;
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

// ---- MCP tools ---------------------------------------------------------------
const tools = [
  { name: "local_memory_store", description: "Save a memory (content, optional id/type/tags/project). " +
    "Indexes it for vector search and adds graph node.",
    inputSchema: { type: "object", properties: {
      content: { type: "string" }, id: { type: "string" }, type: { type: "string" },
      tags: { type: "array", items: { type: "string" } }, project: { type: "string" } }, required: ["content"] } },
  { name: "local_memory_recall", description: "Vector search by relevance (TF-IDF cosine). " +
    "Returns top memories ranked by semantic similarity.",
    inputSchema: { type: "object", properties: {
      query: { type: "string" }, limit: { type: "number" }, project: { type: "string" }, type: { type: "string" } }, required: ["query"] } },
  { name: "local_memory_forget", description: "Delete a memory by id.",
    inputSchema: { type: "object", properties: { id: { type: "string" } }, required: ["id"] } },
  { name: "local_memory_list", description: "List recent memories, optional filter by project/type.",
    inputSchema: { type: "object", properties: { limit: { type: "number" }, project: { type: "string" }, type: { type: "string" } } } },
  { name: "local_memory_stats", description: "Counts: total memories, graph edges.",
    inputSchema: { type: "object", properties: {} } },
  { name: "local_memory_graph_query", description: "Query neighbours of a node (memory id or any label) in the graph.",
    inputSchema: { type: "object", properties: { node: { type: "string" }, depth: { type: "number" } }, required: ["node"] } },
  { name: "local_memory_graph_add_edge", description: "Add relation edge source->target.",
    inputSchema: { type: "object", properties: { source: { type: "string" }, target: { type: "string" }, relation: { type: "string" } }, required: ["source", "target"] } },
  { name: "local_memory_graph_connect", description: "Connect a memory id to a file path (File nodes appear in graph).",
    inputSchema: { type: "object", properties: { memory_id: { type: "string" }, file_path: { type: "string" }, relation: { type: "string" } }, required: ["memory_id", "file_path"] } },
];

function store(args) {
  const id = args.id || ("mem:" + Math.random().toString(36).slice(2) + Date.now().toString(36));
  const type = args.type || "conversation";
  const tags = Array.isArray(args.tags) ? args.tags.join(",") : (args.tags || "");
  const project = args.project || "";
  DB.prepare("INSERT OR REPLACE INTO memories(id,content,type,tags,project,created) VALUES(?,?,?,?,?,?)")
    .run(id, String(args.content), type, tags, project, Date.now());
  // re-index terms
  DB.prepare("DELETE FROM terms WHERE memory_id=?").run(id);
  if (project) DB.prepare("DELETE FROM terms WHERE memory_id=? AND project<>?").run(id, project);
  for (const [term, tfidf] of computeTfIdf(args.content, project)) {
    DB.prepare("INSERT OR REPLACE INTO terms(memory_id,term,tfidf,project) VALUES(?,?,?,?)").run(id, term, tfidf, project);
  }
  return { ok: true, id };
}

function recall(args) {
  const q = String(args.query || "");
  const limit = Math.max(1, Math.min(50, Number(args.limit) || 10));
  const project = args.project || "";
  const type = args.type || "";
  const qv = embed(q, project);
  const scores = [];
  const rows = DB.prepare(
    `SELECT id,content,type,tags,project,created FROM memories
     WHERE (project=?1 OR '${project}'='') AND (type=?2 OR '${type}'='')`
  ).all(project, type);
  if (!Object.keys(qv).length) {
    return { results: rows.slice(0, limit).map(r => ({ ...r, score: 1 })) };
  }
  for (const r of rows) {
    const rv = embed(r.content + " " + (r.tags||"").replace(/,/g," "), r.project);
    const score = cosine(qv, rv);
    if (score > 0) scores.push({ score, ...r });
  }
  scores.sort((a, b) => b.score - a.score);
  return { results: scores.slice(0, limit) };
}

function list(args) {
  const limit = Math.max(1, Math.min(100, Number(args.limit) || 20));
  const project = args.project || "";
  const type = args.type || "";
  const rows = DB.prepare(
    `SELECT id,content,type,tags,project,created FROM memories
     WHERE (project=?1 OR '${project}'='') AND (type=?2 OR '${type}'='')
     ORDER BY created DESC LIMIT ?3`
  ).all(project, type, limit);
  return { memories: rows };
}

function forget(args) {
  DB.prepare("DELETE FROM memories WHERE id=?").run(args.id);
  DB.prepare("DELETE FROM terms WHERE memory_id=?").run(args.id);
  DB.prepare("DELETE FROM graph WHERE source=? OR target=?").run(args.id, args.id);
  return { ok: true };
}

function stats() {
  const mem = DB.prepare("SELECT COUNT(*) c FROM memories").get().c;
  const edges = DB.prepare("SELECT COUNT(*) c FROM graph").get().c;
  return { memories: mem, edges };
}

function graphQuery(args) {
  const node = String(args.node || "");
  const depth = Math.max(1, Math.min(4, Number(args.depth) || 1));
  const seen = new Set();
  const out = [];
  let level = new Set([node]);
  for (let d = 0; d < depth && level.size; d++) {
    const next = new Set();
    for (const n of level) {
      if (seen.has(n)) continue;
      seen.add(n);
      const rel = DB.prepare("SELECT source,target,relation FROM graph WHERE source=?1 OR target=?1").all(n);
      for (const r of rel) {
        const other = r.source === n ? r.target : r.source;
        out.push({ from: r.source, to: r.target, relation: r.relation, relation_on: n, other, hop: d + 1 });
        next.add(other);
      }
    }
    level = next;
  }
  return { node, depth, edges: out };
}

function graphAddEdge(args) {
  DB.prepare("INSERT OR IGNORE INTO graph(source,target,relation) VALUES(?,?,?)")
    .run(args.source, args.target, args.relation || "related");
  return { ok: true };
}

function graphConnect(args) {
  DB.prepare("INSERT OR IGNORE INTO graph(source,target,relation) VALUES(?,?,?)")
    .run(args.memory_id, "__file:" + args.file_path, args.relation || "touches_file");
  return { ok: true };
}

// ---- JSON-RPC / MCP stdio loop ----------------------------------------------
function callTool(name, args) {
  switch (name) {
    case "local_memory_store": return store(args || {});
    case "local_memory_recall": return recall(args || {});
    case "local_memory_forget": return forget(args || {});
    case "local_memory_list": return list(args || {});
    case "local_memory_stats": return stats();
    case "local_memory_graph_query": return graphQuery(args || {});
    case "local_memory_graph_add_edge": return graphAddEdge(args || {});
    case "local_memory_graph_connect": return graphConnect(args || {});
    default: throw new Error("Unknown tool: " + name);
  }
}

function send(obj) { process.stdout.write(JSON.stringify(obj) + "\n"); }

// Общий обработчик одного JSON-RPC сообщения. Возвращает ответ (объект) или null
// (для notifications/без id). Вызывается как из stdio-транспорта, так и из HTTP.
function handleMessage(msg) {
  const id = msg.id;
  if (msg.method === "initialize") {
    return { id, result: {
      protocolVersion: (msg.params && msg.params.protocolVersion) || "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "opencode-mobile-memory", version: "1.0.0" } } };
  }
  if (msg.method === "notifications/initialized" || msg.method === "initialized") return null;
  if (msg.method === "tools/list") return { id, result: { tools } };
  if (msg.method === "tools/call") {
    const p = msg.params || {};
    try {
      const result = callTool(p.name, p.arguments);
      if (msg.id === undefined) return null; // notification, no reply
      return { id, result: { content: [{ type: "text", text: JSON.stringify(result) }], isError: false } };
    } catch (e) {
      return { id, result: { content: [{ type: "text", text: String((e && e.message) || e) }], isError: true } };
    }
  }
  if (msg.method === "ping" || msg.method === "resources/list") return { id, result: {} };
  if (msg.method === "shutdown") return { id, result: {} };
  if (id !== undefined) return { id, result: {} };
  return null;
}

// ---- Транспорт выбор: stdio (default) или HTTP/Streamable (env MCP_TCP_PORT) --
const TCP_PORT = Number(process.env.MCP_TCP_PORT) || 0;

if (TCP_PORT > 0) {
  // Streamable HTTP MCP server: GET /mcp = SSE stream, POST /mcp = JSON-RPC (object|array).
  const sseClients = new Set();
  const server = Bun.serve({
    port: TCP_PORT,
    hostname: "127.0.0.1",
    fetch(req, srv) {
      const url = new URL(req.url);
      if (url.pathname !== "/mcp") return new Response("not found", { status: 404 });

      if (req.method === "GET") {
        const stream = new ReadableStream({
          start(controller) {
            sseClients.add(controller);
            controller.enqueue("event: endpoint\ndata: /mcp\n\n");
            const iv = setInterval(() => {
              try { controller.enqueue(": keepalive\n\n"); } catch (_) { clearInterval(iv); }
            }, 15000);
            const onClose = () => { clearInterval(iv); sseClients.delete(controller); };
            req.signal.addEventListener("abort", onClose);
          }
        });
        return new Response(stream, { status: 200, headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
          "Connection": "keep-alive",
          "Access-Control-Allow-Origin": "*" } });
      }

      if (req.method === "POST") {
        return req.json().then((body) => {
          const batch = Array.isArray(body) ? body : [body];
          const responses = [];
          let notify = true; // has any non-notification message
          for (const m of batch) {
            if (m === null || m === undefined || typeof m !== "object") continue;
            const r = handleMessage(m);
            if (r !== null) { responses.push({ jsonrpc: "2.0", ...r }); notify = false; }
          }
          if (responses.length === 0) return new Response(null, { status: 202 });
          const json = responses.length === 1 ? responses[0] : responses;
          return new Response(JSON.stringify(json), { status: 200, headers: {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*" } });
        }).catch((e) => new Response(JSON.stringify({ jsonrpc: "2.0", error: { code: -32700, message: String(e) } }), {
          status: 400, headers: { "Content-Type": "application/json" } }));
      }

      if (req.method === "DELETE") return new Response(null, { status: 202 });
      return new Response("method not allowed", { status: 405 });
    }
  });
  // resend to nobody; server just listens
  // eslint-disable-next-line no-console
  console.error("memory MCP http on 127.0.0.1:" + server.port);
  process.stdout._handle; // keep process alive via server
} else {
  // stdio (default, для ручных тестов)
  const { createInterface } = require("node:readline");
  const rl = createInterface({ input: process.stdin });
  rl.on("line", (line) => {
    let msg;
    try { msg = JSON.parse(line); } catch { return; }
    const response = handleMessage(msg);
    if (response !== null) send(response);
  });
}