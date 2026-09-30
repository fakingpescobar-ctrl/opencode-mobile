#!/usr/bin/env bun
// Yandex Music control over Ynison (JS, runs on in-app musl-Bun).
//
// Transport mirrors memory.js: newline-delimited JSON-RPC on stdio by default,
// Streamable HTTP when YNISON_TCP_PORT > 0.
//
// ---------------------------------------------------------------------------
// Why the control flow looks the way it does
// ---------------------------------------------------------------------------
// Ynison is a websocket state-sync protocol, not a REST control API. A device can
// be a "player" (owns the queue, actually plays) or a "remote controller" (may only
// observe). The non-obvious part, established empirically against a real Yandex
// Music app on Android:
//
//   A passive remote controller CANNOT change playback. The server silently drops
//   update_playing_status and update_active_device - no echo, no error, no reaction.
//   (Confirmed twice: the packet is accepted and the phone stays paused.)
//
//   Queues are device-scoped: only the device that currently holds the active
//   player role may update the queue. The phone holds it, so the phone is the only
//   one who can write - and the only way to make it write is to become the active
//   player yourself, write the queue, then give the role back.
//
// Hence the three-step handoff, the only sequence that works:
//
//   1. update_active_device -> SELF     (requires can_be_player:true on our device)
//   2. update_player_state  -> queue    (authored by SELF, so the server accepts it)
//   3. update_active_device -> PHONE    (server hands the role back, keeps the queue)
//
// We never play audio. We hold the active role for ~2.5s purely to write the queue.
// The phone then plays from what we wrote, in the background, as usual.
//
// Wire details that are not guessable and each cost a failed run:
//   - every mutating message needs rid + player_action_timestamp_ms +
//     activity_interception_type; without them the server rejects or ignores it
//   - progress_ms / duration_ms / version.* are STRINGS. Numbers get HTTP 500 and
//     the socket is torn down mid-handoff
//   - commands are separate oneof fields, never update_full_state
//   - the handshake requires player_queue.options.repeat_mode
//   - the search endpoint is bare /search; /v1/search and /v2/search are 404
//
// Protocol shapes cross-checked against the Apache-2.0 music-assistant Ynison
// client and MarshalX/yandex-music-api (LGPL, used as a protocol reference only).
import { readFileSync, appendFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";

const OAUTH = process.env.YNISON_TOKEN || "";
const TCP_PORT = Number(process.env.YNISON_TCP_PORT) || 0;
const SELF_ID = process.env.YNISON_DEVICE_ID || "opencode-mobile";
// Bearer for the LOCAL MCP endpoint. Deliberately NOT the Yandex OAuth token: this one
// is written into opencode.jsonc (as {env:...}) and is the only thing standing between a
// random app on the device and a full Yandex.Music session. Two secrets, two env vars.
const MCP_TOKEN = process.env.MCP_YNISON_TOKEN || "";
// musl-Bun on Android cannot resolve DNS at all (no /etc/resolv.conf, no netd bridge),
// so the app resolves these hosts in Java and hands us IPs. We then dial the IP and send
// the real hostname as the `Host` header. Measured on-device: a plain hostname fetch dies
// with `getaddrinfo ETIMEOUT` in ~5s, the IP+Host form answers in ~0.3s.
const YNISON_HOST = "ynison.music.yandex.ru";
const API_HOST = "api.music.yandex.net";
const RESOLVED_YNISON_IP = process.env.YNISON_WS_IP || "";
const YNISON_PORT = Number(process.env.YNISON_WS_PORT) || 443;
// Local Android bridge (AppInstallBridge). Used for one thing only: starting the Yandex
// Music app when no device is online, because playback targets a real app on the phone.
const BRIDGE_TOKEN = process.env.MOBILE_INSTALL_TOKEN || "";
const BRIDGE_PORT = Number(process.env.MOBILE_INSTALL_PORT) || 4202;
const MUSIC_APP_PACKAGE = "ru.yandex.music";
// Пул моста — 2 потока, тяжёлые маршруты держат слот до 20+с. 25с — с запасом на самую долгую
// штатную операцию (COLD_START у Яндекс.Музыки — 9с плюс connect), но не на ожидание в очереди.
const BRIDGE_TIMEOUT_MS = 25_000;
const RESOLVED_API_IP = process.env.YNISON_API_IP || "";
const REDIRECT_SERVICE = "redirector.YnisonRedirectService/GetRedirectToYnison";
const STATE_SERVICE = "ynison_state.YnisonStateService/PutYnisonState";
const DEVICE_INFO = JSON.stringify({ app_name: "OpenCodeMobile", type: 1 });

// Tunables that the timing below depends on. Changing them without re-checking the
// handoff risks a half-completed claim, which would leave the phone with no active
// player at all - so they are named rather than sprinkled as literals.
const CLAIM_SETTLE_MS = 2500;   // server must move the active role before we write
const WRITE_SETTLE_MS = 3000;   // server must accept the queue before we hand back
const HANDSHAKE_MS = 2500;      // server sends the first player_state on its own
const VERIFY_MS = 1500;         // grace period when re-reading state to confirm
const STEP_TIMEOUT_MS = 10000;  // per websocket step; a silent server must not hang us
const VERIFY_ATTEMPTS = 2;      // state is echoed asynchronously; give it a few tries
const WRITE_ATTEMPTS = 3;       // the server drops a valid write often enough to retry
// Cold start of the Yandex Music app. The app has to authenticate and open its own
// Ynison socket, so it needs seconds, not milliseconds - but it must be bounded so a
// logged-out or first-run app reports itself instead of hanging the tool.
const APP_LAUNCH_SETTLE_MS = 3000;
const APP_ONLINE_ATTEMPTS = 8;

// Before deciding the phone is gone, ask the server again. Launching Yandex Music takes the
// screen away from the chat the user is typing into, so it has to be the last resort, not
// the first guess - the device list is allowed to be a beat behind.
const PHONE_OFFLINE_PROBES = 3;
const PHONE_OFFLINE_PROBE_MS = 1200;
// Every tool opens a session first, so a cold start hit all of them at once: right after
// the app (and this script) starts, the first connect can miss the per-step timeout while
// the process and TLS warm up. A failed first call is worse than a slow one - the agent
// reads the error as "this tool is broken" and walks off to click the UI instead.
const OPEN_ATTEMPTS = 3;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const DEBUG = process.env.YNISON_DEBUG === "1";
const TRACE_FILE = process.env.YNISON_TRACE_FILE || "";
// Where the last real player_state is cached between runs. Derived from the trace path so
// the app does not have to grow another env var - it is always the same directory.
const STATE_CACHE = process.env.YNISON_STATE_CACHE
  || (TRACE_FILE ? TRACE_FILE.replace(/[\\/][^\\/]*$/, "") + "/ynison-state.json" : "");
const trace = (...a) => {
  if (!DEBUG) return;
  const line = "[ynison] " + a.map((x) => (typeof x === "string" ? x : JSON.stringify(x))).join(" ") + "\n";
  console.error(line.trimEnd());
  // The child's stderr is a pipe the app consumes, so stderr never reaches logcat.
  // With a trace file set we append there too, which is the only channel that survives.
  if (TRACE_FILE) {
    try {
      appendFileSync(TRACE_FILE, line);
    } catch {
      /* tracing must never break control flow */
    }
  }
};

// ---- protocol helpers ---------------------------------------------------------

// The required metadata envelope. Kept in one place because every mutating message
// needs it and getting a field wrong fails silently rather than loudly.
function meta() {
  return {
    rid: crypto.randomUUID(),
    player_action_timestamp_ms: String(Date.now()),
    activity_interception_type: "DO_NOT_INTERCEPT_BY_DEFAULT",
  };
}

// Version block. All three values are strings by protocol requirement - passing
// numbers is what produced HTTP 500 + socket teardown during development.
function version() {
  return { device_id: SELF_ID, version: String(Date.now()), timestamp_ms: "0" };
}

// A minimal WebSocket client over Bun.connect, replacing the built-in WebSocket for the
// Ynison sockets. Both failures it works around were measured on the device, not guessed:
//
//  1. musl-Bun cannot resolve DNS at all, so the URL would have to carry an IP. An IP URL
//     makes the built-in client put that IP in the TLS SNI, and nginx (the front for
//     ynison.music.yandex.ru) tears such connections down - `onerror` in ~250ms, while a
//     plain `Bun.connect` with `tls.servername` set to the real host handshakes fine.
//  2. Ynison's Sec-WebSocket-Protocol must be sent as a raw header anyway (see
//     subprotocolHeader), so the upgrade request is hand-rolled regardless.
//
// Measured with the bundled Bun 1.4.0 on the phone: Bun.connect -> 101 in 230ms on both
// the redirect and the state socket, the built-in WebSocket -> ERROR in 200-380ms.
class YnisonWs {
  constructor({ path, address, servername, protocol }) {
    this.path = path; // "/service.Name/Method"
    this.address = address; // IP when resolved by the app, hostname otherwise
    this.servername = servername; // real hostname: HTTP Host + TLS SNI
    this.protocol = protocol; // raw Sec-WebSocket-Protocol value
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.upgraded = false;
    this.fragments = [];
    this.fragmentOpcode = 0x1;
    this.settled = false;
  }

  connect() {
    return new Promise((resolve, reject) => {
      // Bun.connect resolves to the socket; the real socket object arrives with the first
      // handler call, which is why it is stored there and not off the connect() result.
      Bun.connect({
        hostname: this.address,
        port: YNISON_PORT,
        tls: { servername: this.servername },
        socket: {
          handshake: (s) => {
            this.socket = s;
            s.write(this.upgradeRequest());
          },
          data: (s, chunk) => this.onData(chunk),
          error: (s, err) => this.fail(`socket: ${err.code || err.message}`),
          close: () => this.onSocketClose(),
          connectError: (s, err) => this.fail(`connect: ${err.code || err.message}`),
        },
      }).catch((err) => this.fail(err.message || String(err)));
      this.resolveOpen = resolve;
      this.rejectOpen = reject;
    });
  }

  // Bun's WebSocket API throws for a bad key; a plain 16 random bytes in base64 is the
  // handshake requirement, and this client does not validate the server's accept hash -
  // Ynison does not rely on it and adding verification would only add a failure mode.
  upgradeRequest() {
    const key = Buffer.from(crypto.getRandomValues(new Uint8Array(16))).toString("base64");
    return [
      `GET ${this.path} HTTP/1.1`,
      `Host: ${this.servername}`,
      "Upgrade: websocket",
      "Connection: Upgrade",
      `Sec-WebSocket-Key: ${key}`,
      "Sec-WebSocket-Version: 13",
      "Origin: https://music.yandex.ru",
      `Authorization: OAuth ${OAUTH}`,
      `Sec-WebSocket-Protocol: ${this.protocol}`,
      "User-Agent: ynison-opencode-mobile",
      "",
      "",
    ].join("\r\n");
  }

  onData(chunk) {
    if (DEBUG) trace("rx", chunk.length + "B", chunk.subarray(0, 10).toString("hex"));
    if (this.upgraded) {
      this.feed(chunk);
      return;
    }
    this.buffer = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : Buffer.from(chunk);
    const end = this.buffer.indexOf("\r\n\r\n");
    if (end < 0) return;
    const head = this.buffer.subarray(0, end).toString("latin1");
    const rest = this.buffer.subarray(end + 4);
    this.buffer = Buffer.alloc(0);
    if (!head.startsWith("HTTP/1.1 101")) {
      const status = head.split("\r\n")[0];
      this.fail(`upgrade rejected: ${status}`);
      this.socket.end();
      return;
    }
    this.upgraded = true;
    this.settled = true;
    this.resolveOpen?.();
    this.onopen?.();
    if (rest.length) this.feed(rest);
  }

  // RFC 6455 framing, JSON-only traffic: text/continuation frames (state payloads run to
  // tens of KB, hence the 16- and 64-bit length forms), ping/pong, close.
  feed(chunk) {
    const b = this.buffer.length ? Buffer.concat([this.buffer, chunk]) : Buffer.from(chunk);
    let offset = 0;
    for (;;) {
      if (b.length - offset < 2) break;
      const fin = (b[offset] & 0x80) !== 0;
      const opcode = b[offset] & 0x0f;
      const masked = (b[offset + 1] & 0x80) !== 0;
      let length = b[offset + 1] & 0x7f;
      let head = 2;
      if (length === 126) {
        if (b.length - offset < 4) break;
        length = b.readUInt16BE(offset + 2);
        head = 4;
      } else if (length === 127) {
        if (b.length - offset < 10) break;
        length = Number(b.readBigUInt64BE(offset + 2));
        head = 10;
      }
      const maskAt = offset + head;
      const body = maskAt + (masked ? 4 : 0);
      if (b.length - body < length) break;
      const payload = Buffer.from(b.subarray(body, body + length));
      if (masked) {
        const mask = b.subarray(maskAt, maskAt + 4);
        for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      }
      offset = body + length;
      if (opcode === 0x0) {
        this.fragments.push(payload);
        if (fin) this.deliver();
      } else if (opcode === 0x1 || opcode === 0x2) {
        this.fragmentOpcode = opcode;
        this.fragments = [payload];
        if (fin) this.deliver();
      } else if (opcode === 0x9) {
        this.writeFrame(0xa, payload);
      } else if (opcode === 0x8) {
        this.socket.end();
      }
    }
    // Keep whatever is left, including the whole buffer when the frame is incomplete:
    // dropping it here restarted parsing mid-frame on the next chunk, which turned a
    // 25KB player_state into bogus opcodes - the class answered PING to a text frame
    // and the server dropped the connection before sending anything.
    this.buffer = Buffer.from(b.subarray(offset));
  }

  deliver() {
    const payload = Buffer.concat(this.fragments);
    this.fragments = [];
    this.onmessage?.({ data: this.fragmentOpcode === 0x2 ? payload : payload.toString("utf8") });
  }

  // Clients must mask; the server drops unmasked frames.
  writeFrame(opcode, payload) {
    const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), "utf8");
    let header;
    if (data.length < 126) {
      header = Buffer.allocUnsafe(2);
      header[1] = 0x80 | data.length;
    } else if (data.length < 65536) {
      header = Buffer.allocUnsafe(4);
      header[1] = 0x80 | 126;
      header.writeUInt16BE(data.length, 2);
    } else {
      header = Buffer.allocUnsafe(10);
      header[1] = 0x80 | 127;
      header.writeBigUInt64BE(BigInt(data.length), 2);
    }
    header[0] = 0x80 | opcode;
    const mask = Buffer.from(crypto.getRandomValues(new Uint8Array(4)));
    const masked = Buffer.allocUnsafe(data.length);
    for (let i = 0; i < data.length; i++) masked[i] = data[i] ^ mask[i & 3];
    if (DEBUG) trace("tx", opcode, data.length + "B", Buffer.concat([header, mask]).toString("hex"), data.subarray(0, 24).toString("utf8"));
    this.socket.write(Buffer.concat([header, mask, masked]));
  }

  send(text) {
    if (!this.upgraded) throw new Error("send before upgrade");
    this.writeFrame(0x1, text);
  }

  close() {
    if (this.socket && this.upgraded) {
      try {
        this.writeFrame(0x8, Buffer.alloc(0));
      } catch {
        /* socket already gone - nothing to close politely */
      }
    }
    this.socket?.end();
  }

  // Used by the redirect path: the promise is already settled, so the error has to travel
  // through onerror instead of rejecting.
  terminate() {
    this.socket?.end();
  }

  fail(reason) {
    const err = new Error(reason);
    if (!this.settled) {
      this.settled = true;
      this.rejectOpen?.(err);
    }
    this.onerror?.(err);
  }

  onSocketClose() {
    this.onclose?.();
  }
}

// The server identifies devices by a JSON blob passed as the third websocket
// subprotocol, URL-encoded. The first two entries are the literal subprotocol names.
//
// It MUST go in as a raw Sec-WebSocket-Protocol header, not as WebSocket's `protocols`
// argument. Bun's native client fails the upgrade ("Expected 101") with any value in
// `protocols`, and its only workaround there - bundling the `ws` package - breaks
// again under Bun ("Unexpected server response: 101"). Hand-rolling the header is the
// one form that works: the upgrade succeeds and the server replies normally.
function subprotocolHeader(inner) {
  return ["Bearer", "v2", encodeURIComponent(JSON.stringify(inner))].join(", ");
}

// YNISON_DUMP=<file> records every frame sent, for diffing a working run against a
// failing one. That diff is what proved the intermittent write drops were a server
// behaviour and not a payload bug.
const DUMP = process.env.YNISON_DUMP || "";
function send(ws, message) {
  if (DUMP) {
    try { appendFileSync(DUMP, JSON.stringify(message) + "\n"); } catch { /* diagnostics only */ }
  }
  ws.send(JSON.stringify(message));
}

// ---- session ------------------------------------------------------------------

// What this session says about itself, in the shape the server will accept.
//
// MEASURED, three times over on a real phone (30.09.2026). All three are load-bearing, and
// together they mean this function has no freedom left - it can only report what it knows:
//
//  1. No player_state at all  -> 400030002 "Unspecified repeat mode is not allowed.",
//     and the server tears the socket down, so nothing ever arrives.
//  2. paused:false + empty queue -> 400030001 "Player is not paused, but queue is probably
//     empty (currentIndex=-1, size=0)". Also fatal.
//  3. So `paused: true` + empty queue is the ONLY legal way to say "I have nothing", which
//     is exactly what the old handshake hardcoded - and that is what paused a playing track,
//     on every single call, including the music_status that noticed the pause.
//
// So there is no neutral state to invent. The state is, in order of preference:
//
//   - the newest frame the server just sent on this socket
//   - the last real state from any earlier session, from the on-disk cache
//   - and only on a genuinely cold start (fresh install, no cache file), the empty
//     paused:true state, because rule 2 leaves nothing else
//
// The cold case is a once-per-install event: saveCachedState runs on every state frame, so
// the cache exists from the first successful call onwards. It is the price of a protocol
// that will not let a device say "nothing" without saying "paused", and it is much better
// than pausing on every call, which is what it replaced.
//
// can_be_player stays true: the claim in handoff needs it (see the header comment).

// The last-resort state, for a cold start with nothing cached. Valid by rule 3 and nothing
// else. Traced loudly, because "I published a pause I did not believe in" must be visible.
function neutralState() {
  trace("no live or cached player_state - publishing the empty paused state");
  return {
    status: { paused: true, duration_ms: "0", progress_ms: "0", playback_speed: 1, version: version() },
    player_queue: {
      current_playable_index: -1, entity_id: "", entity_type: "VARIOUS",
      playable_list: [], options: { repeat_mode: "NONE" },
      entity_context: "BASED_ON_ENTITY_BY_DEFAULT", version: version(), from_optional: "",
    },
  };
}

function loadCachedState() {
  if (!STATE_CACHE) return null;
  try {
    const parsed = JSON.parse(readFileSync(STATE_CACHE, "utf8"));
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null; // no cache yet, or it was truncated - both mean "we know nothing"
  }
}

function saveCachedState(state) {
  if (!STATE_CACHE || !state) return;
  try {
    writeFileSync(STATE_CACHE, JSON.stringify(state));
  } catch {
    /* the cache is an optimisation, never a reason to fail a session */
  }
}

// Repeat modes the server accepts. Measured: the phone publishes
// options.repeat_mode = UNSPECIFIED, which is fine coming from the app and fatal coming
// from us, so the echo repairs that one enum and nothing else - the queue, the position and
// the paused flag go back exactly as they arrived, which is what makes the echo incapable of
// pausing anything. A real setting (ONE, ALL) is left alone.
const REPEAT_MODES = new Set(["NONE", "ONE", "ALL"]);

function announceable(state) {
  if (!state) return null;
  const q = state.player_queue;
  // No queue to repair: inventing one is exactly the thing this file must never do, and
  // there is nothing here the server validates.
  if (!q) return state;
  const options = q.options || {};
  if (REPEAT_MODES.has(options.repeat_mode)) return state;
  return { ...state, player_queue: { ...q, options: { ...options, repeat_mode: "NONE" } } };
}

// What this session publishes about itself.
//
// Order matters. A live frame beats the cache beats the seed - except on an account whose
// queue is empty, where the live frame and the cache are the same useless empty state and
// the seed (the queue this call is about to write) is the only thing the server will take.
//
// `seed` is only ever a state the caller is going to write anyway, never a guess.
function stateToAnnounce(frames, seed = null) {
  let live = null;
  for (let i = frames.length - 1; i >= 0; i--) {
    if (frames[i].player_state) { live = frames[i].player_state; break; }
  }
  // The newest frame wins, not the first: the server may send several, and a stale one
  // would undo the fix by the same route as the bug.
  const chosen = live || loadCachedState() || seed || neutralState();
  // Measured 30.09.2026: an empty playable_list is refused outright - 400030001
  // "Empty playable list is restricted" - as the handshake and as anything sent on top of
  // it. So when the account queue is empty, the only state that can be published is one
  // that carries something. That is the whole reason seed exists.
  if ((chosen.player_queue?.playable_list || []).length) return announceable(chosen);
  return announceable(seed) || chosen;
}

function announceDevice(ws, frames, seed = null) {
  const player_state = stateToAnnounce(frames, seed);
  send(ws, {
    ...meta(),
    update_full_state: {
      player_state,
      device: {
        info: { device_id: SELF_ID, type: "ANDROID", title: "OpenCode", app_name: "opencode" },
        capabilities: { can_be_player: true, can_be_remote_controller: true, volume_granularity: 0 },
        volume_info: { volume: 0 },
      },
      is_currently_active: false,
    },
  });
}

// Open a Ynison session and complete the handshake. Returns the live socket plus the
// frames received so far. The handshake declares can_be_player:true because we must
// be eligible for the active role during the handoff - see the header comment.
// A ban arrives as data, not as a closed socket. Ynison answers
//
//   {"ynison-error-code":"400090001","ynison-go-away-for-seconds":"3600"}
//
// which is the server asking this device id to disappear for that many seconds, because too
// many sessions were opened under it in a short window.
//
// Two things must not happen here. It must not look like an auth failure: the token is fine,
// and an agent told "refused the token" will go and re-login a working token for nothing. And
// it must not be retried: every retry is one more connection from the id that is being
// penalised, so retrying does not just fail, it lengthens the ban. Hence the permanent flag -
// matching on message text would only catch the wording we happen to use today.
function goAwayError(payload) {
  if (!payload || String(payload["ynison-error-code"]) !== "400090001") return null;
  const secs = payload["ynison-go-away-for-seconds"] || "some";
  const e = new Error(
    `Yandex Music dropped this device and asked it to stay away ${secs}s (ynison 400090001). ` +
      `The phone goes offline when too many Ynison sessions are opened under one device id. ` +
      `The token is valid - do not re-login. Wait out the interval before trying again.`,
  );
  e.permanent = true;
  e.goAwaySeconds = Number(payload["ynison-go-away-for-seconds"]) || 0;
  return e;
}

async function openSessionOnce(seed = null) {
  if (!OAUTH) throw new Error("YNISON_TOKEN is not set");

  const redirect = await new Promise((resolve, reject) => {
    const ws = new YnisonWs({
      path: `/${REDIRECT_SERVICE}`,
      address: RESOLVED_YNISON_IP || YNISON_HOST,
      servername: YNISON_HOST,
      protocol: subprotocolHeader({
        "Ynison-Device-Id": SELF_ID, "Ynison-Device-Info": DEVICE_INFO,
      }),
    });
    const timer = setTimeout(() => { ws.terminate(); reject(new Error("redirect timeout")); }, STEP_TIMEOUT_MS);
    ws.onmessage = (e) => {
      clearTimeout(timer);
      let parsed = null;
      try { parsed = JSON.parse(e.data); } catch { parsed = null; }
      // Close before rejecting so a ban does not leave the redirect socket hanging.
      ws.close();
      const away = goAwayError(parsed);
      if (away) reject(away);
      else resolve(parsed);
    };
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error(`ynison redirect failed: ${e.message}`)); };
    ws.connect();
  });

  if (!redirect.host || !redirect.session_id) {
    // This is what an under-scoped token produces. Worth naming explicitly: it looks
    // identical to a network failure unless you check host/session_id.
    throw new Error("ynison refused the token (no host/session in redirect)");
  }

  const inner = {
    "Ynison-Device-Id": SELF_ID,
    "Ynison-Device-Info": DEVICE_INFO,
    "Ynison-Redirect-Ticket": redirect.redirect_ticket,
    "Ynison-Session-Id": String(redirect.session_id),
  };

  const ws = new YnisonWs({
    path: `/${STATE_SERVICE}`,
    address: RESOLVED_YNISON_IP || redirect.host,
    servername: redirect.host,
    protocol: subprotocolHeader(inner),
  });
  const frames = [];
  ws.onmessage = (e) => {
    try {
      const parsed = JSON.parse(e.data);
      trace("state frame keys:", Object.keys(parsed).join(",") || "(none)", String(e.data).slice(0, 300));
      frames.push(parsed);
      // Remember it. The next session's handshake has to publish a valid state before it
      // has seen any, and the only state worth publishing is a real one.
      if (parsed.player_state) saveCachedState(parsed.player_state);
    } catch {
      trace("state frame non-JSON:", String(e.data).slice(0, 200));
    }
  };
  ws.onclose = () => trace("state socket closed by server");

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { ws.terminate(); reject(new Error("state open timeout")); }, STEP_TIMEOUT_MS);
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error(`ynison state connect failed: ${e.message}`)); };
    ws.connect().then(() => { clearTimeout(timer); resolve(); }).catch((e) => { clearTimeout(timer); reject(e); });
  });

  // The ban can arrive on the state socket instead of the redirect, as a frame that carries
  // the code rather than a close. Checked here, after the handshake and before any write, so
  // a banned device is not sent a command on the way to reporting that it is banned.
  for (const frame of frames) {
    const away = goAwayError(frame);
    if (away) { ws.close(); throw away; }
  }

  // The first message on the socket has to carry a state the server will accept, and the
  // account queue being empty leaves exactly one such state: the one this call is about to
  // write. A read-only tool has no such state, and neither does a cold start, so it is told
  // plainly instead of eating three protocol errors the agent cannot act on.
  const seed_state = stateToAnnounce(frames, seed);
  if (!(seed_state?.player_queue?.playable_list || []).length) {
    ws.close();
    const e = new Error(
      "the Yandex Music queue is empty and Ynison refuses an empty playable list " +
        "(ynison 400030001), so no session can be opened. Play anything once in the " +
        "Yandex Music app on the phone, then retry.",
    );
    e.permanent = true;
    throw e;
  }

  announceDevice(ws, frames, seed);

  await wait(HANDSHAKE_MS);
  trace(`handshake done: ${frames.length} frame(s)`);

  // The server has now told us what is really going on, so hand that back instead of what we
  // opened with. announceable() repairs only the repeat enum, so this cannot change what the
  // phone is doing - but it does keep our published state from contradicting the live one.
  if (frames.some((f) => f.player_state) && !seed) {
    announceDevice(ws, frames);
    await wait(CLAIM_SETTLE_MS);
    trace("announced server state back");
  }
  return { ws, frames };
}

function readState(frames) {
  const frame = frames.find((f) => f.player_state);
  if (!frame) return null;
  const s = frame.player_state;
  const q = s.player_queue;
  const current = q.playable_list[q.current_playable_index];
  return {
    paused: s.status.paused,
    progressMs: s.status.progress_ms,
    index: q.current_playable_index,
    title: current?.title ?? "",
    artist: (current?.artists ?? []).map((a) => a.name).join(", "),
    queue: q,
    activeDeviceId: frame.active_device_id_optional ?? null,
    devices: (frame.devices ?? []).map((d) => ({
      id: d.info?.device_id,
      title: d.info?.title,
      canBePlayer: d.capabilities?.can_be_player ?? false,
      offline: d.is_offline ?? false,
    })),
  };
}

// The phone is whoever can play and is not us. Not hardcoded: the id changes if the
// app is reinstalled, and hardcoding it was how the first experiments silently
// targeted a stale, offline device.
function findPhone(state) {
  // state is null whenever no frame has carried a player_state yet - a first call, or a gap
  // between frames. That is "not known", not "offline", and must not throw.
  if (!state || !Array.isArray(state.devices)) return null;
  return state.devices.find((d) => d.id !== SELF_ID && d.canBePlayer && !d.offline) ?? null;
}

// The newest state wins. Once we start re-sending update_full_state to poll the device
// list, `frames` holds several states, and the first one is the stale one we are trying
// to move past.
function latestState(frames) {
  for (let i = frames.length - 1; i >= 0; i--) {
    if (frames[i].player_state) return readState([frames[i]]);
  }
  return null;
}

// The player_queue the server echoes is large: it carries the full playable list plus
// a wave_queue with a recommended_playable_list, and the phone's queue items have no
// artists array at all. Handing that to the model as tool output burns context on
// nothing, so responses get reduced to the handful of fields a caller actually uses.
function summarize(state) {
  if (!state) return null;
  const current = state.queue?.playable_list?.[state.index];
  return {
    paused: state.paused,
    index: state.index,
    title: state.title,
    // Идентификатор играющего трека, а не только название. Название не годится для записи
    // в плейлист, а лайк/добавление без id заставляют агента искать трек заново и получать
    // не тот (одинаковые названия у разных записей - норма). playable_id в Ynison и есть
    // каталоговый id Яндекса, поэтому он годится и для mobile_yandex_playlist_add_track.
    nowPlayingId: current?.playable_id ?? null,
    progressMs: state.progressMs,
    queueLength: state.queue?.playable_list?.length ?? 0,
    activeDeviceId: state.activeDeviceId,
  };
}

// State arrives asynchronously, so a single read right after a command proves
// nothing. Poll briefly and accept the first frame that actually reflects the change.
//
// ONE socket for the whole poll, not one per attempt. The device id is stable, so
// reopening a session looks to Yandex like the same device dialling again seconds later;
// three quick connections under one id is what earns the
// 400090001 / "go-away-for-seconds: 3600" ban, and a banned socket then costs the PHONE its
// online presence, not just our retry. `frames` is a live array fed by onmessage, so
// re-reading it is exactly what a fresh socket would have produced, minus the handshake.
//
// The settle wait after opening is not optional: reading immediately on the first
// attempt returned a pre-command frame often enough to report a failed pause, even
// though the phone had applied it. The server needs a moment after the handshake
// before the write is reflected back.
async function waitForState(expect, attempts = VERIFY_ATTEMPTS) {
  const { ws, frames } = await openSession();
  try {
    for (let i = 0; i < attempts; i++) {
      await wait(VERIFY_MS);
      const state = readState(frames);
      trace("verify attempt", i, "paused=", state?.paused, "index=", state?.index, "title=", state?.title, "qver=", state?.queue?.version?.version);
      if (state && expect(state)) return state;
    }
    return null;
  } finally {
    ws.close();
  }
}

// Retries only what a retry can fix. A token the server refuses, or a missing token, is
// a configuration problem: repeating it just delays the same message three times over.
// A go-away ban is worse than useless to retry - each attempt is another connection from the
// id being penalised - and it carries permanent=true so the decision does not depend on how
// the message happens to be worded.
const PERMANENT_OPEN_ERRORS = ["is not set", "refused the token"];

async function openSession(seed = null) {
  let lastError = null;
  for (let attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
    try {
      return await openSessionOnce(seed);
    } catch (e) {
      lastError = e;
      trace("open attempt", attempt, "failed:", e.message);
      if (e.permanent || PERMANENT_OPEN_ERRORS.some((s) => e.message.includes(s))) break;
      await wait(500);
    }
  }
  throw lastError;
}

// ---- the handoff --------------------------------------------------------------

/**
 * The phone is the only device that can actually make sound, so every write needs one.
 * It is also an ordinary app: it gets swiped away, killed by the launcher, or simply
 * was never opened after a reboot. Failing with "launch the app first" pushed that work
 * onto the agent, which has to guess the package, call a second tool, wait, and retry -
 * so the failure mode was a long flailing session instead of a played track.
 *
 * Order matters and is deliberate: check the device list FIRST, because the app is
 * usually already running and re-launching a live app would only reset its playback.
 * Only when no player-capable device is online do we start it, then wait for it to
 * publish itself.
 */
async function ensurePhone(ws, frames, seed = null, launched = { already: false }) {
  // latestState, NOT readState: readState takes the FIRST frame with a player_state, which is
  // the oldest one. The device list in a stale frame does not have the phone in it, so
  // findPhone came back empty, we concluded the phone was offline, and launched Yandex Music
  // into the foreground - over our own chat, leaving the user unable to type. latestState
  // reads the newest frame, which is the one the server just sent us.
  let already = findPhone(latestState(frames));
  if (already) {
    trace("phone already online:", already.id);
    return already;
  }

  // A missing device is not proof of a dead one. The list can simply be a beat behind, and
  // launching is expensive in the worst way: it takes the screen away from the chat the user
  // is typing into. Re-announce and look again before spending that.
  for (let i = 0; i < PHONE_OFFLINE_PROBES; i++) {
    await wait(PHONE_OFFLINE_PROBE_MS);
    announceDevice(ws, frames, seed);
    await wait(VERIFY_MS);
    already = findPhone(latestState(frames));
    if (already) {
      trace("phone turned up on probe", i + 1, ":", already.id);
      return already;
    }
    trace("phone still not listed on probe", i + 1);
  }

  if (!BRIDGE_TOKEN) {
    throw new Error("no Yandex Music device online, and the Android bridge is not available to start it");
  }
  // A launch resets playback, so it happens at most once per tool call. A retry after we
  // already started the app waits for it to publish itself instead of starting it again -
  // measured 30.09.2026: three cold starts in one call left the phone paused on its own
  // restored position every time.
  const skipLaunch = launched.already;
  if (!skipLaunch) {
    trace("phone genuinely absent after", PHONE_OFFLINE_PROBES, "probes; launching", MUSIC_APP_PACKAGE);
    const res = await fetch(`http://127.0.0.1:${BRIDGE_PORT}/v1/apps/launch`, {
      method: "POST",
      headers: { Authorization: `Bearer ${BRIDGE_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({ package: MUSIC_APP_PACKAGE }),
    });
    if (!res.ok) throw new Error(`could not start Yandex Music (bridge HTTP ${res.status})`);
    launched.already = true;
  } else {
    trace("app already launched earlier in this call; waiting instead of relaunching");
  }

  // The app opens its own Ynison socket asynchronously. Ask the server for a fresh
  // state each round: re-announcing ourselves is the protocol's own way to be told who is
  // online, and it costs nothing compared to reconnecting.
  //
  // Measured 30.09.2026: a cold start registers with Ynison in ~20s, and APP_ONLINE_ATTEMPTS
  // rounds already cover that with room to spare. Growing this budget was a mistake - it is
  // spent on top of the probes, handshake and write, and the whole call then runs into the
  // client's MCP timeout, which surfaces as a transport error with nothing to show for it.
  // If this ever needs to grow, raise YNISON_MCP_TIMEOUT_MS in OpencodeRuntime.kt with it.
  const onlineAttempts = APP_ONLINE_ATTEMPTS;
  for (let i = 0; i < onlineAttempts; i++) {
    await wait(APP_LAUNCH_SETTLE_MS);
    announceDevice(ws, frames, seed);
    await wait(VERIFY_MS);
    const state = latestState(frames);
    const phone = state && findPhone(state);
    if (phone) {
      trace("phone came online after", (i + 1) * APP_LAUNCH_SETTLE_MS, "ms:", phone.id);
      return phone;
    }
  }
  // A Yandex Music that never appears is usually signed out or stuck on a first-run
  // screen - neither of which a retry can fix, so say so instead of failing vaguely.
  // Retries in handoffUntil re-run this wait, so the message only has to say what happened;
  // the app is already running by the time the second attempt gets here.
  throw new Error("Yandex Music was started but did not come online in ~" +
    Math.round((onlineAttempts * (APP_LAUNCH_SETTLE_MS + VERIFY_MS)) / 1000) +
    "s (signed out, or waiting on a first-run screen?)");
}

/**
 * Take the active role, write the queue, give the role back.
 *
 * `mutate` returns the player_state to publish. `seed`, when given, is the state this call
 * is about to write - it is also what the handshake publishes, which is the only way to open
 * a session at all while the account queue is empty (see stateToAnnounce). The hand-back runs
 * in `finally` on purpose: if the write throws or the process is interrupted between claim and
 * hand-back, the session would be left with us as the active player - and the phone would
 * stop responding to Ynison entirely. Handing back to whatever was active before costs nothing
 * and makes that state impossible to leave behind.
 *
 * `launched` is carried across the caller's retries. Measured 30.09.2026: a fresh launch of
 * Yandex Music restores the app's own saved playback position and leaves it PAUSED there, so
 * every extra launch is a reset of whatever the previous attempt had just started. Three
 * WRITE_ATTEMPTS meant three cold starts inside one tool call, and playback died at the
 * restored position every time. Once we have started the app ourselves, the caller must wait
 * for it rather than start it again.
 */
async function handoff(mutate, seed = null, launched = { already: false }) {
  const { ws, frames } = await openSession(seed);
  let phoneId = null;
  try {
    const before = readState(frames);
    if (!before) throw new Error("no player_state after handshake");
    const phone = await ensurePhone(ws, frames, seed, launched);
    phoneId = phone.id;

    send(ws, { ...meta(), update_active_device: { device_id_optional: SELF_ID } });
    await wait(CLAIM_SETTLE_MS);

    send(ws, { ...meta(), update_player_state: { player_state: mutate(before) } });
    await wait(WRITE_SETTLE_MS);
    trace("wrote player_state; settling", WRITE_SETTLE_MS);
  } finally {
    // Hand the role back to whoever held it. Falling back to the phone keeps a
    // failed claim from silently taking playback away from the user.
    try {
      if (phoneId) send(ws, { ...meta(), update_active_device: { device_id_optional: phoneId } });
      await wait(CLAIM_SETTLE_MS);
    } catch { /* socket already gone; nothing left to strand */ }
    ws.close();
  }
  return phoneId;
}

// ---- Yandex Music search ------------------------------------------------------

// Bare /search, not /v1/search - the v1 and v2 paths are both 404 now. The payload
// nests the array under result.tracks.results, and the playable id is realId.
// RFC 7230 chunked body. The catalog API does not use it, but a response that claims to be
// chunked and is not decoded is a hang, and this costs ten lines to not have that failure.
function dechunk(buf) {
  const parts = [];
  let off = 0;
  for (;;) {
    const nl = buf.indexOf("\r\n", off);
    if (nl < 0) return null;
    const size = parseInt(buf.subarray(off, nl).toString("latin1").split(";")[0].trim(), 16);
    if (!Number.isFinite(size)) return null;
    off = nl + 2;
    if (size === 0) return Buffer.concat(parts);
    if (buf.length < off + size + 2) return null;
    parts.push(buf.subarray(off, off + size));
    off += size + 2;
  }
}

// null until the response is complete, so the caller can keep feeding it chunks.
function parseApiResponse(raw) {
  const end = raw.indexOf("\r\n\r\n");
  if (end < 0) return null;
  const lines = raw.subarray(0, end).toString("latin1").split("\r\n");
  const status = /^HTTP\/1\.[01] (\d{3})/.exec(lines[0] || "");
  if (!status) return null;
  const headers = {};
  for (const line of lines.slice(1)) {
    const i = line.indexOf(":");
    if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
  }
  let body = raw.subarray(end + 4);
  if ((headers["transfer-encoding"] || "").includes("chunked")) {
    body = dechunk(body);
    if (body === null) return null;
  } else if (headers["content-length"] !== undefined) {
    const need = Number(headers["content-length"]);
    if (!Number.isFinite(need) || body.length < need) return null;
    body = body.subarray(0, need);
  }
  return { status: Number(status[1]), body };
}

// One HTTPS GET to the catalog API, over Bun.connect rather than fetch.
//
// The reason is the same one the WebSocket client above documents, and it was measured on
// the device: musl-Bun cannot resolve DNS, so the app hands us the API address as an IP, and
// fetch on an IP URL puts that IP into the TLS SNI. The certificate then does not verify -
// "unknown certificate verification error" on roughly 1 call in 5, which surfaced as "the
// agent found no track". tls.servername is the fix, and Bun.connect is the only client here
// that can set it, so the REST path now uses it instead of living on a retry.
function apiGet(path, headers) {
  return new Promise((resolve, reject) => {
    let raw = Buffer.alloc(0);
    let settled = false;
    let socket = null;
    const finish = (err, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { socket?.end(); } catch { /* already gone */ }
      if (err) reject(err);
      else resolve(value);
    };
    const timer = setTimeout(() => finish(new Error("api request timed out")), 20000);
    const request = [
      `GET ${path} HTTP/1.1`,
      `Host: ${API_HOST}`,
      "Connection: close",
      "Accept: application/json",
      // identity, so the body is never compressed and needs no inflate step
      "Accept-Encoding: identity",
      "User-Agent: ynison-opencode-mobile",
      ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
      "",
      "",
    ].join("\r\n");
    const read = () => {
      const parsed = parseApiResponse(raw);
      if (!parsed) return finish(new Error("api response was truncated"));
      if (parsed.status < 200 || parsed.status >= 300) return finish(new Error(`HTTP ${parsed.status}`));
      try {
        finish(null, JSON.parse(parsed.body.toString("utf8")));
      } catch {
        finish(new Error("catalog API returned invalid JSON"));
      }
    };
    Bun.connect({
      hostname: RESOLVED_API_IP || API_HOST,
      port: 443,
      tls: { servername: API_HOST },
      socket: {
        handshake: (s) => { socket = s; s.write(request); },
        data: (s, chunk) => {
          raw = raw.length ? Buffer.concat([raw, chunk]) : Buffer.from(chunk);
          // Answer as soon as the body is whole; a chunked body may still be incomplete.
          if (parseApiResponse(raw)) read();
        },
        error: (s, err) => finish(new Error(`api socket: ${err.code || err.message}`)),
        close: () => { if (!settled) read(); },
        connectError: (s, err) => finish(new Error(`api connect: ${err.code || err.message}`)),
      },
    }).catch((err) => finish(new Error(err.message || String(err))));
  });
}

const CLIENT_ID = "YandexMusicAndroid/24023621";

// Every catalog request goes through here, so the scope story lives in one place.
//
// OAUTH authenticates the WEBSOCKET protocol and nothing else. api.music.yandex.net refuses it
// with 403 missing-required-scopes - measured on the device and reproduced off-device with the
// live token. That is NOT fixed by asking for more: the token requests "login:info
// music:api-public", that is the entire set registered for this client, and the catalog still
// answers 403. Yandex simply does not sell REST catalog access to this registration. The
// catalog's own /users/me is 403 with the token AND without it, so nothing is lost by leaving
// the token off entirely - there is no account-scoped call to make. Account identity comes from
// login.yandex.ru instead, which answers 200 with this very token.
//
// So the token is not sent, rather than sent and retried: it cannot help, and sending it turns
// every working call into a failing one first. That mattered because apiGet drops the body on
// any non-2xx, so a retry-on-403 scheme spent two round trips to arrive where one would.
//
// A 403 from here is now a real signal about a real problem, not an artefact of our own header.
async function catalogGet(path) {
  return await apiGet(path, { "X-Yandex-Music-Client": CLIENT_ID });
}

const encode = (s) => encodeURIComponent(s);

// An artist's own catalogue. This is a different id space from the Ynison playable ids, and
// the objects come back with the same field names as search results, so they drop straight
// into the rest of this file without any reshaping.
//
// per-page is not optional. Omit it and the server applies its own default of 20: measured on
// artist 4611844, whose catalogue reports total 90. We were silently seeing 20 of 90 tracks,
// which reads exactly like "this performer has no such song" for anything past the twentieth
// entry. That was the whole bug behind "play <title> by <artist>" failing.
async function artistTracks(artistId) {
  const body = await catalogGet(`/artists/${artistId}/tracks?page=0&per-page=100`);
  return Array.isArray(body?.result?.tracks) ? body.result.tracks : [];
}

// Whether this track belongs to the named performer, judged by the PRIMARY credit.
//
// It has to be judged here rather than asked of the catalog: measured, /search honours no
// artist filter. `filter=artists:<id>` returns the same total as sending nothing at all
// (18125 unfiltered against 18126 filtered on a broad query - index jitter), and a bare
// `artists=<id>` parameter likewise does nothing. A server-side filter that silently ignores
// its argument looks exactly like one that works, right up until the wrong band plays.
//
// Primary credit, not "appears anywhere in the credits". DMX is a real co-credit on Eminem
// and Limp Bizkit tracks that sit at the top of a search for "DMX", so any scan of the whole
// credit list reads those as DMX songs - the exact substitution this check exists to prevent.
function ledBy(track, name) {
  const want = (name ?? "").trim().toLowerCase();
  if (!want) return true;
  const primary = (track?.artists ?? [])[0]?.name ?? "";
  return primary.trim().toLowerCase() === want;
}

// Plain catalogue search by title. No artist filter, because the catalogue applies none.
async function catalogSearch(query) {
  const body = await catalogGet(`/search?text=${encode(query)}&type=track&page=0&per-page=20`);
  return body?.result?.tracks?.results ?? [];
}

// Resolving the name to an id first is what makes "just play DMX" work. DMX is a name shared
// by 55 accounts, and the track search cannot tell them apart because it matches the TITLE
// field: searching type=track for "DMX" returns five tracks by Locs, Primer and others, with
// the real DMX nowhere in the list.
//
// `exact` is what the caller needs to decide whether this was really a match or just the
// first name that happened to come back - guessing here is how a search for one band
// quietly returns another.
async function resolveArtist(name) {
  const body = await catalogGet(`/search?text=${encode(name)}&type=artist&page=0`);
  const found = body?.result?.artists?.results ?? [];
  if (!found.length) return null;
  const want = name.trim().toLowerCase();
  const exact = found.find((a) => (a.name ?? "").trim().toLowerCase() === want);
  return { id: (exact ?? found[0]).id, exact: Boolean(exact) };
}

// Narrows an artist's tracks by title. The caller decides what an empty result means - the
// list tool turns it into candidates, the play tool refuses to guess.
function byTitle(tracks, title) {
  const want = (title ?? "").trim().toLowerCase();
  if (!want) return tracks;
  return tracks.filter((t) => (t.title ?? "").toLowerCase().includes(want));
}

// A named performer that resolves to nothing is a fact about the catalog, not a mistyped query,
// and the two need different answers. "nothing found" reads like the agent got the name wrong,
// so it retries spelling variations forever - a real dead end dressed as a retryable mistake.
//
// The catalog is queried anonymously on purpose (see catalogGet): Yandex refuses REST catalog
// access to this client, so sending the token only turns working requests into 403s. That means
// no credential can widen the search, which is worth saying outright instead of leaving the
// caller to try harder. Measured, not assumed: the artist is absent under every spelling tried,
// while mainstream artists in the same script resolve fine, so this is a genuine gap in the
// index rather than a naming variant to guess at.
async function searchTracks(query, artist) {
  if (artist) {
    const hit = await resolveArtist(artist);
    if (!hit) {
      throw new Error(
        `Yandex's catalog has no artist named "${artist}". Not a spelling variant - the artist ` +
          `is absent from the index, and no token can widen it because this catalog is read ` +
          `anonymously (a token is refused with 403). Report this to the user instead of ` +
          `trying other spellings.`,
      );
    }
    // Search FIRST, and check the credit list ourselves, before falling back to the artist's
    // own catalogue. The fallback is not a substitute, it is a worse answer: the listing is
    // demonstrably incomplete - track 31198888 ("<Компот>") belongs to artist 4611844 and is
    // absent from that artist's 90-track listing entirely, while the plain title search finds
    // it on the first page. Leading with the listing is what made a real song look missing.
    const direct = (await catalogSearch(query)).filter((t) => ledBy(t, artist));
    if (direct.length) return direct;

    const tracks = await artistTracks(hit.id);
    // The query is frequently the performer's own name, and no track of theirs carries it.
    // Falling back to the whole catalogue is what lets the list tool offer real candidates
    // instead of an empty answer.
    const narrowed = byTitle(tracks, query);
    return narrowed.length ? narrowed : tracks;
  }

  const results = await catalogSearch(query);

  // No artist was named, so a bare performer name ("play DMX") arrives here as query alone.
  // The tell is the PRIMARY artist of the top hit: if the query is the performer's own name
  // then results[0] is some other band's song that merely has the word in the TITLE, and the
  // performer reading is the right one. Checking every artist instead does not work - DMX is
  // a credited collaborator on Eminem and Limp Bizkit tracks that sit right at the top of a
  // search for "DMX", which reads as a match and sends us back to the wrong answer.
  const want = (query ?? "").trim().toLowerCase();
  const lead = (results[0]?.artists ?? [])[0]?.name ?? "";
  if (lead.trim().toLowerCase() !== want) {
    const hit = await resolveArtist(query);
    if (hit?.exact) {
      const tracks = await artistTracks(hit.id);
      const narrowed = byTitle(tracks, query);
      if (narrowed.length) return narrowed;
      return tracks;
    }
  }
  return results;
}

// The catalog id, and not a Ynison playable id, is what a playlist needs - the two are
// different id spaces and mixing them saves the wrong track.
function trackSummary(t) {
  return {
    id: String(t.realId ?? t.id),
    title: t.title,
    artist: (t.artists ?? []).map((a) => a.name).join(", "),
    album: t.albums?.[0]?.title ?? null,
    duration_ms: t.durationMs ?? null,
  };
}

async function searchTrack(query, artist) {
  const results = await searchTracks(query, artist);
  if (!results.length) throw new Error(`nothing found for "${query}"`);

  if (artist) {
    // searchTracks has already preferred tracks whose title matches the query and, when none
    // do, has fallen back to the performer's whole catalogue so the list tool can offer
    // candidates. For PLAYBACK that fallback is not enough: picking the first of twenty
    // tracks is a guess, and a guess that starts playing music is not recoverable by the
    // caller. So refuse, and let the agent show the candidates. The exception is the
    // legitimate "play <performer>" request, where the query IS the performer.
    const asked = (query ?? "").trim().toLowerCase();
    const byName = (artist ?? "").trim().toLowerCase();
    const titleHit = results.some((t) => (t.title ?? "").toLowerCase().includes(asked));
    if (asked && asked !== byName && !titleHit) {
      throw new Error(
        `no track titled "${query}" by ${artist}; call music_search to see what they have`,
      );
    }
    const want = byName;
    return results.find((t) => (t.artists ?? []).some((a) => a.name?.toLowerCase() === want)) ?? results[0];
  }
  return results[0];
}

// Searching without starting playback: "add Drift to the playlist" must not make the
// phone start playing the track it just saved.
async function opSearchQuery(query, artist) {
  const results = await searchTracks(query, artist);
  if (!results.length) throw new Error(`nothing found for "${query}"`);
  const want = (artist ?? "").toLowerCase();
  const ordered = want
    ? [...results].sort((a, b) => {
        const hit = (t) => ((t.artists ?? []).some((x) => x.name?.toLowerCase() === want) ? 0 : 1);
        return hit(a) - hit(b);
      })
    : results;
  return { query, artist: artist ?? null, matches: ordered.slice(0, 5).map(trackSummary) };
}

// Shaped like the entries the phone itself publishes, so the server treats it as a
// real queue item rather than something it has to guess about.
function toPlayableItem(track) {
  const item = {
    playable_id: String(track.realId ?? track.id),
    playable_type: "TRACK",
    from: "mobile-own_tracks-playlist-default",
    title: track.title,
    track_info: { track_source_key: 0 },
  };
  if (track.albums?.[0]?.id) item.album_id_optional = String(track.albums[0].id);
  if (track.albums?.[0]?.coverUri) item.cover_url_optional = track.albums[0].coverUri;
  return item;
}

// ---- operations ---------------------------------------------------------------

// The server intermittently drops a well-formed update_player_state. Verified by
// diffing the bytes: a run that works and a run that does not send byte-identical
// state (same paused, progress_ms, index, item count) and differ only in the version
// timestamp, yet one resumes and the other leaves the phone paused. A write is
// therefore not a guarantee - it has to be confirmed and retried, otherwise "resume"
// fails a few times an hour and looks like a broken feature rather than a flaky server.
async function handoffUntil(mutate, expect, seed = null) {
  let phone = null;
  let after = null;
  let lastError = null;
  // Shared by every attempt of this call: once ensurePhone has started Yandex Music, no
  // later attempt starts it again. See handoff() for why that matters.
  const launched = { already: false };
  for (let i = 0; i < WRITE_ATTEMPTS; i++) {
    trace("attempt", i + 1, "of", WRITE_ATTEMPTS);
    // Measured 30.09.2026: ensurePhone throws when the app has not registered yet, and an
    // uncaught throw ended the whole tool call on attempt 1 - so the retry below never ran and
    // the app was never given a second chance. "play Eminem" failed outright while the phone
    // showed up seconds later. Failures are therefore collected, not propagated, and the last
    // one is re-thrown only if every attempt fails.
    try {
      phone = await handoff(mutate, seed, launched);
      after = await waitForState(expect);
      if (after) return { phone, after };
      lastError = null;
    } catch (e) {
      lastError = e;
      trace("attempt", i + 1, "failed:", e.message);
    }
  }
  if (lastError) throw lastError;
  return { phone, after: null };
}

// The phone refuses to resume from a non-zero position. Measured, not guessed: a track
// paused at 24286 ms or 60693 ms ignored every paused:false write, byte-identical ones
// that land when progress_ms is "0". Its own player has already dropped the audio
// session, and a remote write that claims time has elapsed does not restart it. Sending
// 0 always takes, at the cost of starting the track over - so we do that and say so,
// rather than report a resume that silently did nothing.
async function opPlay(paused) {
  const { phone, after } = await handoffUntil(
    (s) => ({
      status: {
        paused,
        progress_ms: paused ? String(s.progressMs ?? "0") : "0",
        duration_ms: "0",
        playback_speed: 1,
        version: version(),
      },
      player_queue: { ...s.queue, version: version() },
    }),
    (s) => s.paused === paused,
  );
  if (!after) throw new Error(`phone did not ${paused ? "pause" : "resume"} after ${WRITE_ATTEMPTS} attempts`);
  return { phone, via: paused ? "pause" : "restart-from-0", after: summarize(after) };
}

async function opStop() {
  // Yandex Music has no stop of its own - its own UI offers play/pause, next, previous. The
  // closest honest equivalent is pause plus rewind, so that is what this does, and the result
  // reports the resulting position: the phone accepted the pause in every run, but a rewind on
  // a paused session is not something we have measured, so we surface progress_ms instead of
  // claiming the track went back to the start.
  const { phone, after } = await handoffUntil(
    (s) => ({
      status: {
        paused: true,
        progress_ms: "0",
        duration_ms: "0",
        playback_speed: 1,
        version: version(),
      },
      player_queue: { ...s.queue, version: version() },
    }),
    (s) => s.paused === true,
  );
  if (!after) throw new Error(`phone did not stop after ${WRITE_ATTEMPTS} attempts`);
  return { phone, via: "stop", after: summarize(after) };
}

async function opSkip(direction) {
  let wanted = -1;
  const { phone, after } = await handoffUntil((s) => {
    const next = s.index + direction;
    if (next < 0 || next >= s.queue.playable_list.length) {
      throw new Error("no track in that direction");
    }
    wanted = next;
    return {
      status: { paused: s.paused, progress_ms: "0", duration_ms: "0", playback_speed: 1, version: version() },
      player_queue: { ...s.queue, current_playable_index: next, version: version() },
    };
  }, (s) => s.index === wanted);
  if (!after) throw new Error(`phone did not skip after ${WRITE_ATTEMPTS} attempts`);
  return { phone, after: summarize(after) };
}

async function opPlayQuery(query, artist) {
  const track = await searchTrack(query, artist);
  // Built once and used twice: as the state to write, and as the handshake seed. An account
  // with an empty queue cannot be opened at all - 400030001 refuses an empty playable list
  // even in the handshake - so the only thing that can open this session is the queue we are
  // about to write. It also means the handshake and the write can never disagree.
  const target = {
    status: { paused: false, progress_ms: "0", duration_ms: "0", playback_speed: 1, version: version() },
    player_queue: {
      current_playable_index: 0, entity_id: "", entity_type: "VARIOUS",
      playable_list: [toPlayableItem(track)], options: { repeat_mode: "NONE" },
      entity_context: "BASED_ON_ENTITY_BY_DEFAULT", version: version(), from_optional: "",
    },
  };
  const { after } = await handoffUntil(
    () => target,
    (s) => s.index === 0 && !s.paused,
    target,
  );
  if (!after) throw new Error(`phone did not start "${track.title}" after ${WRITE_ATTEMPTS} attempts`);
  return { picked: { id: String(track.realId ?? track.id), title: track.title, artist: (track.artists ?? []).map((a) => a.name).join(", ") }, after: summarize(after) };
}

async function opStatus() {
  const { ws, frames } = await openSession();
  const state = readState(frames);
  ws.close();
  if (!state) throw new Error("no player_state");
  const phone = findPhone(state);
  const current = state.queue?.playable_list?.[state.index];
  return {
    phone: phone ? phone.title : null,
    activeDevice: state.activeDeviceId,
    paused: state.paused,
    index: state.index,
    nowPlaying: state.title,
    // null, а не выдуманный id: очередь может быть пустой, и тогда «сейчас играет вот этот
    // трек» - ложь, на которую потом опирается добавление в плейлист.
    nowPlayingId: current?.playable_id ?? null,
    queueLength: state.queue.playable_list.length,
    devices: state.devices,
  };
}

// ---- MCP surface --------------------------------------------------------------

const str = (desc) => ({ type: "string", description: desc });

const TOOLS = [
  { name: "music_status", description: "What Yandex Music is playing right now, and which devices are online. Returns nowPlayingId, the catalog id of the playing track - that is what mobile_yandex_playlist_add_track needs, so liking or saving the current track does not require searching for it again. A phone missing from the device list means the app is closed - that is not an error, music_play_query starts it on demand.", inputSchema: { type: "object", properties: {} } },
  { name: "music_play", description: "Resume Yandex Music playback on the phone. The phone cannot resume from a paused position, so this restarts the current track from the beginning.", inputSchema: { type: "object", properties: {} } },
  { name: "music_pause", description: "Pause Yandex Music playback on the phone.", inputSchema: { type: "object", properties: {} } },
  { name: "music_stop", description: "Stop Yandex Music playback: pause it and rewind to the start. Yandex Music itself has no stop button - play/pause, next, previous is all it offers - so this is pause plus rewind, and it reports the resulting position so you can see whether the rewind landed. Use music_pause if you only want it quiet and want to keep the position.", inputSchema: { type: "object", properties: {} } },
  { name: "music_next", description: "Skip to the next track in the Yandex Music queue.", inputSchema: { type: "object", properties: {} } },
  { name: "music_prev", description: "Go back to the previous track in the Yandex Music queue.", inputSchema: { type: "object", properties: {} } },
  {
    name: "music_search",
    description:
      "Search the Yandex Music catalog and return up to 5 matching tracks with their ids, without starting " +
      "playback. Use this when the user names a track they want to save, like or add to a playlist, not to hear " +
      "right now: music_play_query searches and starts in one step, this one leaves the phone alone. The id is " +
      "the catalog id, which is exactly what mobile_yandex_playlist_add_track needs. " +
      "ALWAYS pass artist when you know it. It is not a tie-breaker, it is the whole lookup: with artist set the " +
      "call resolves the name to an artist id and reads that performer's own catalogue. Without it the " +
      "search runs over track titles, so a bare performer name comes back as other people's songs that merely " +
      "have that word in the title - searching \"DMX\" with no artist returns five tracks by Locs and Primer " +
      "and not one by DMX. An empty matches list means the catalog has nothing, so show what came back instead " +
      "of inventing an id.",
    inputSchema: {
      type: "object",
      properties: {
        query: str("Track title, or - with artist set - any text, including the performer name alone."),
        artist: str("Exact artist name. Pass it whenever you know it: it selects that performer's catalogue."),
      },
      required: ["query"],
    },
  },
  {
    name: "music_play_query",
    // This description is load-bearing, not decoration. The agent kept reaching for
    // mobile_media_play, which the mobile tool's own description admits cannot work on
    // Yandex Music: the session accepts the catalog id and then plays whatever it already
    // had. So the choice has to be made explicitly, and the cold-start behaviour has to
    // be stated - otherwise the agent "helpfully" launches the app first and loses a turn.
    description:
      "Search Yandex Music and play a specific track. This is THE tool for any \"play <track or artist>\" request, " +
      "and the only one that really starts the requested track: it writes it into the app's own queue over " +
      "Ynison, which is the sole mechanism Yandex Music honours. If the app is not running it starts the app " +
      "itself and waits for it to come online, so never call mobile_launch_app or any UI tap first - and never " +
      "reach for mobile_launch_app, ui_click or the withdrawn mobile_media_play, which reports the request " +
      "as sent and then plays whatever the phone already had. " +
      "Example: music_play_query(query=\"Toxicity\", artist=\"System of a Down\"). For a performer with no " +
      "track in mind use music_play_query(query=\"DMX\", artist=\"DMX\") - the artist is what does the work, " +
      "so the query can be the performer's name on its own.",
    inputSchema: { type: "object", properties: { query: str("Track title or free text to search for."), artist: str("Exact artist name. Pass it whenever you know it - it selects that performer's catalogue, not just a tie-breaker.") } },
  },
  {
    name: "session_status",
    description:
      "What the phone is playing RIGHT NOW, read straight from the active MediaSession, plus which apps " +
      "expose a session we can control. Use this when the music_* tools fail with the phone missing or " +
      "with 'did not come online': Android blocks our background service from starting Yandex Music " +
      "(BAL_BLOCK, result code=102) when its process was killed, and no music_* tool can fix that. " +
      "This still reports real playback while the app is alive, so it tells 'not playing' apart from " +
      "'app is gone'. It cannot pick a track - only report and control what already plays.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "session_control",
    description:
      "Drive the active MediaSession directly: play, pause, play_pause, next, previous, stop. This " +
      "is the transport that survives when Ynison cannot - the commands go to the live session and " +
      "Android starts nothing, so the background-launch block never comes into it. Use it to play, " +
      "pause or skip what is already playing when music_play/music_next fail with the phone offline. " +
      "It CANNOT choose a track by name: the session exposes only what is already queued, so for " +
      "'play Eminem' use music_play_query first and fall back to this when that cannot start the app. " +
      "There is no seek - use next if the track is wrong. Returns before and after state. " +
      "'accepted but NOT verified' means the player took the command but reported no state change " +
      "within the timeout - for next on a one-track queue that is correct behaviour, not a failure, " +
      "so do not retry it in a loop.",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["play", "pause", "play_pause", "next", "previous", "stop"],
          description:
            "play_pause toggles between play and pause. There is no seek: the phone cannot resume " +
            "from a paused position anyway, which is why music_play restarts the track instead.",
        },
      },
      required: ["action"],
    },
  },
];

// ── MediaSession-транспорт ──────────────────────────────────────────────────
// Запасной путь, который не упирается в Android BAL.
//
// Проблема, которую он обходит: Ynison умеет всё, кроме одного - поднять Яндекс.Музыку,
// если её процесса нет. Serve работает как foreground service, у него нет видимого окна,
// и Android режет любой фоновый запуск чужой activity с result code=102 (BAL_BLOCK).
// startActivity при этом возвращает успех, так что мост раньше отвечал 200 {"ok":true} на
// запуск, которого не было.
//
// MediaSession работает иначе: команды уходят в уже активную сессию через MediaController,
// и Android при этом ничего не запускает - BAL тут не участвует. Плата - управлять можно
// только тем, что уже играет, и нельзя выбрать конкретный трек. Поэтому это дополнение к
// music_*, а не замена: музыкальный запрос -> music_play_query, а вот play/pause/next/
// previous/секунды берём отсюда, когда Ynison не может поднять телефон.

/** Вызов моста: общий транспорт для /v1/media/* и /v1/launch/*. */
async function bridge(path, init = {}) {
  if (!BRIDGE_TOKEN) throw new Error("Android bridge is not available");
  const method = init.method || "GET";
  // Дедлайн обязателен: мост обслуживает запросы пулом из 2 потоков, и тяжёлые маршруты
  // (плейлисты Яндекса, опрос устройства) держат слот до 20+ секунд. Без таймаута клиент
  // ждал бы их молча, не имея возможности ни отличить зависание от медленной работы.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), BRIDGE_TIMEOUT_MS);
  let res;
  try {
    res = await fetch(`http://127.0.0.1:${BRIDGE_PORT}${path}`, {
      ...init,
      method,
      signal: controller.signal,
      headers: {
        Authorization: `Bearer ${BRIDGE_TOKEN}`,
        "Content-Type": "application/json",
        ...(init.headers || {}),
      },
      // GET/HEAD тела не имеют, и fetch это запрещает - шлём пустое тело только там, где
      // метод его допускает. Мост читает пустое тело как «параметров нет».
      body: method === "GET" || method === "HEAD" ? undefined : (init.body === undefined ? "{}" : init.body),
    });
  } catch (e) {
    clearTimeout(timer);
    if (e.name === "AbortError") {
      throw new Error(`bridge ${path} timed out after ${BRIDGE_TIMEOUT_MS}ms - the Android bridge is busy or wedged`);
    }
    throw new Error(`bridge ${path} unreachable: ${e.message}`);
  }
  clearTimeout(timer);
  const text = await res.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`bridge ${path} returned non-JSON (HTTP ${res.status}): ${text.slice(0, 200)}`);
  }
  if (!res.ok) throw new Error(`bridge ${path} failed (HTTP ${res.status}): ${body.hint || body.error || text.slice(0, 200)}`);
  return body;
}

function describePlayback(p) {
  if (!p || !p.state || p.state === "none") return "nothing is playing";
  const bits = [`"${p.title || "?"}"`, p.artist].filter(Boolean).join(" - ");
  const pos = p.position_ms == null ? "" : ` at ${Math.round(p.position_ms / 1000)}s`;
  return `${bits}${pos} (${p.state}${p.is_playing ? "" : ", not playing"})`;
}

async function opSessionStatus() {
  // Три независимых вопроса, и все три нужны в одном вызове: что играет, что ещё управляемо и
  // разрешён ли фоновый запуск. Последнее — частая причина «телефон молчит», и раньше агент
  // мог узнать о нём только из лога, что он не читает.
  const [status, blocker, apps] = await Promise.all([
    bridge("/v1/media/status", {
      method: "POST",
      body: JSON.stringify({ package: MUSIC_APP_PACKAGE }),
    }).catch((e) => ({ error: e.message })),
    bridge("/v1/launch/blocker").catch((e) => ({ error: e.message })),
    bridge("/v1/media/apps").catch((e) => ({ error: e.message })),
  ]);
  const lines = [];
  if (status.media) {
    lines.push(`Yandex Music: ${describePlayback(status.media.playback)} via ${status.media.transport}`);
  } else {
    lines.push(`Yandex Music: no active session (${status.error || "app is closed or never granted a session"})`);
  }
  if (blocker.system_alert_window_granted === true) {
    lines.push("background launch: allowed");
  } else if (blocker.system_alert_window_granted === false) {
    lines.push(
      "background launch: BLOCKED by Android - the app cannot start Yandex Music when its process " +
        "was killed, because this service has no visible window. Grant \"display over other apps\" " +
        "in the app Diagnostics -> Фоновый запуск приложений, then retry.",
    );
  } else {
    lines.push(`background launch: unknown (${blocker.error || "no answer from the bridge"})`);
  }
  if (apps.apps) {
    // Список media-приложений телефона — это 10-25 записей, и в контекст агента попадать
    // должны только управляемые: остальные (звонилка, Bluetooth, наушники) всё равно нельзя
    // рулить, и упоминать их незачем.
    const controllable = apps.apps.filter((a) => a.controlable);
    const others = controllable.filter((a) => a.package !== MUSIC_APP_PACKAGE).map((a) => a.label);
    lines.push(
      `other controllable media apps: ${others.length === 0 ? "none" : others.join(", ")}` +
        ` (${controllable.length} controllable of ${apps.apps.length} with a session)`,
    );
  }
  return lines.join("\n");
}

async function opSessionControl(action) {
  const res = await bridge("/v1/media/control", {
    method: "POST",
    body: JSON.stringify({ action, package: MUSIC_APP_PACKAGE }),
  });
  const m = res.media || {};
  // verified лежит внутри media - это ответ самого контроллера. Верхний ok лишь зеркалит его.
  const verified = m.verified === true;
  // verified=false - это не ошибка вызова, а честный ответ плеера. Например next при единственном
  // треке в очереди ничего не меняет. Поэтому такое возвращаем как есть, с пояснением причины.
  const verdict = verified ? "verified" : "accepted but NOT verified";
  const detail = m.message ? ` (${m.message})` : "";
  return `${action}: ${verdict}${detail}\nbefore: ${describePlayback(m.before)}\nafter:  ${describePlayback(m.after)}\nvia ${m.transport || "?"}`;
}

async function runTool(name, args) {
  switch (name) {
    case "music_status": return opStatus();
    case "music_play": return opPlay(false);
    case "music_pause": return opPlay(true);
    case "music_stop": return opStop();
    case "music_next": return opSkip(1);
    case "music_prev": return opSkip(-1);
    case "music_search": return opSearchQuery(args.query, args.artist);
    case "music_play_query": return opPlayQuery(args.query, args.artist);
    case "session_status": return opSessionStatus();
    case "session_control": return opSessionControl(args.action);
    default: throw new Error(`unknown tool: ${name}`);
  }
}

const SCOPE = { name: "ynison", tools: TOOLS };

async function handleMessage(msg, scope) {
  const { id, method } = msg;
  if (method === "initialize") return { id, result: { protocolVersion: "2024-11-05", capabilities: { tools: {} }, serverInfo: { name: scope.name, version: "1.0.0" } } };
  if (method === "notifications/initialized" || method === "initialized") return null;
  if (method === "tools/list") return { id, result: { tools: scope.tools } };
  if (method === "tools/call") {
    const args = msg.params?.arguments ?? {};
    try {
      const out = await runTool(msg.params?.name, args);
      return { id, result: { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] } };
    } catch (e) {
      return { id, result: { content: [{ type: "text", text: String(e && e.message ? e.message : e) }], isError: true } };
    }
  }
  return null;
}

// ---- transports ---------------------------------------------------------------

function authorized(req, url) {
  if (!MCP_TOKEN) return false;
  const header = req.headers.get("authorization") || "";
  const bearer = header.startsWith("Bearer ") ? header.slice(7) : "";
  return bearer === MCP_TOKEN || (url.searchParams.get("token") || "") === MCP_TOKEN;
}

if (TCP_PORT > 0) {
  // Fail loudly instead of answering 403 to everything: an unset bearer is a wiring
  // bug in the launcher, and a silent 403 looks exactly like "ynison is down".
  if (!MCP_TOKEN) throw new Error("MCP_YNISON_TOKEN is not set");
  const sse = new Set();
  const server = Bun.serve({
    port: TCP_PORT,
    hostname: "127.0.0.1",
    async fetch(req) {
      const url = new URL(req.url);
      if (!authorized(req, url)) return new Response("forbidden", { status: 403 });
      if (req.method === "POST") {
        try {
          const body = await req.json();
          const batch = Array.isArray(body) ? body : [body];
          const out = [];
          for (const m of batch) {
            if (!m || typeof m !== "object") continue;
            const r = await handleMessage(m, SCOPE);
            if (r !== null) out.push({ jsonrpc: "2.0", ...r });
          }
          if (out.length === 0) return new Response(null, { status: 202 });
          return new Response(JSON.stringify(out.length === 1 ? out[0] : out), {
            status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
          });
        } catch (e) {
          return new Response(JSON.stringify({ jsonrpc: "2.0", error: { code: -32700, message: String(e) } }), { status: 400 });
        }
      }
      if (req.method === "GET") {
        const stream = new ReadableStream({
          start(controller) {
            controller.enqueue(new TextEncoder().encode(`event: endpoint\ndata: ${url.pathname}\n\n`));
            sse.add(controller);
          },
          cancel() { sse.delete(controller); },
        });
        return new Response(stream, { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" } });
      }
      if (req.method === "DELETE") return new Response(null, { status: 202 });
      return new Response("method not allowed", { status: 405 });
    },
  });
  console.error("ynison MCP http on 127.0.0.1:" + server.port);
} else {
  const rl = createInterface({ input: process.stdin });
  rl.on("line", async (line) => {
    let msg;
    try { msg = JSON.parse(line); } catch { return; }
    try {
      const res = await handleMessage(msg, SCOPE);
      if (res !== null) console.log(JSON.stringify({ jsonrpc: "2.0", ...res }));
    } catch (e) {
      if (msg.id !== undefined) console.log(JSON.stringify({ jsonrpc: "2.0", id: msg.id, error: { code: -32603, message: String(e) } }));
    }
  });
}
