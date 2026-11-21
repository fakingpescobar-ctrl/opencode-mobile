#!/usr/bin/env bun
// Квантовать эталонный bf16 через наш собственный .so (без внешних бинарников).
// llama_model_quantize уже внутри libllama-bench.so; lb_quantize — тонкая FFI-обёртка.
import { dlopen, FFIType } from "bun:ffi";
import { statSync, writeFileSync } from "node:fs";
import { open } from "node:fs/promises";
import { createHash } from "node:crypto";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const SRC = ROOT + "/models/hs-decide-1-flash-bf16.gguf";

const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_quantize: {
    args: [FFIType.cstring, FFIType.cstring, FFIType.i32],
    returns: FFIType.i32,
  },
});
const runtime = lib.symbols.lb_version();
const rtNum = Number(String(runtime).split("musl-")[1] || 0);
if (!(rtNum >= 9)) {
  console.error(`нужна обёртка musl-9+ с lb_quantize, сейчас ${runtime}`);
  process.exit(2);
}

async function sha(path) {
  const h = createHash("sha256");
  const fd = await open(path, "r");
  const buf = Buffer.allocUnsafe(8 * 1024 * 1024);
  for (;;) {
    const { bytesRead } = await fd.read(buf, 0, buf.length, null);
    if (!bytesRead) break;
    h.update(buf.subarray(0, bytesRead));
  }
  await fd.close();
  return h.digest("hex");
}

const jobs = [
  { file: "hs-decide-1-flash-q8_0.gguf", ftype: 7, name: "Q8_0" },
  { file: "hs-decide-1-flash-q4_k_m.gguf", ftype: 15, name: "Q4_K_M" },
];
const rows = [];
for (const job of jobs) {
  const dst = ROOT + "/models/" + job.file;
  const t0 = performance.now();
  const rc = lib.symbols.lb_quantize(SRC, dst, job.ftype);
  const ms = Math.round(performance.now() - t0);
  if (rc !== 0) {
    console.error(`${job.name}: rc=${rc}`);
    process.exit(3);
  }
  const row = {
    name: job.name,
    ftype: job.ftype,
    file: job.file,
    bytes: statSync(dst).size,
    sha256: await sha(dst),
    ms,
  };
  rows.push(row);
  console.log(`${job.name}: ${row.bytes} б, ${row.ms} мс, sha=${row.sha256.slice(0, 16)}…`);
}
writeFileSync(ROOT + "/tools/llama-bench/quantized-hs.json",
  JSON.stringify({ runtime, source: SRC.split("/").pop(), rows }, null, 2) + "\n");
console.log("готово: tools/llama-bench/quantized-hs.json");
