#!/usr/bin/env bun
// Быстрый smoke: грузит GGUF через наш musl-16 мост и делает один forward pass.
// Вызов: research-smoke.mjs <model.gguf> <pooling: 1=mean|4=rank>
import { dlopen, FFIType } from "bun:ffi";
const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const model = process.argv[2] || ROOT + "/models/multilingual-e5-small-q8_0.gguf";
const pooling = Number(process.argv[3] || 1);
const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_state_init: { args: [FFIType.cstring, FFIType.i32], returns: FFIType.ptr },
  lb_state_free: { args: [FFIType.ptr], returns: FFIType.void },
  lb_emb_run: { args: [FFIType.ptr, FFIType.cstring, FFIType.ptr, FFIType.i32, FFIType.ptr], returns: FFIType.i32 },
  lb_cls_score: { args: [FFIType.ptr, FFIType.cstring, FFIType.ptr, FFIType.ptr], returns: FFIType.i32 },
});
console.log("runtime", String(lib.symbols.lb_version()));
const st = lib.symbols.lb_state_init(model, pooling);
if (!st) { console.error("state_init FAILED"); process.exit(2); }
try {
  if (pooling === 1) {
    const out = new Float32Array(2048);
    const metrics = new Float64Array(4);
    const n = lib.symbols.lb_emb_run(st, process.argv[4] || "query: куда plugin пишет отчёт", out, out.length, metrics);
    console.log("emb dim", n, "head", Array.from(out.slice(0, 5)), "ms", metrics[0], "tok", metrics[1]);
    if (n < 1) process.exit(3);
  } else {
    const score = new Float64Array(1);
    const metrics = new Float64Array(4);
    const rc = lib.symbols.lb_cls_score(st, "куда plugin пишет отчёт</s>СОСТОЯНИЕ.md пишет отчёты", score, metrics);
    console.log("cls rc", rc, "score", score[0], "ms", metrics[0], "tok", metrics[1], "ncls", metrics[2], "nemb", metrics[3]);
    if (rc !== 0) process.exit(3);
  }
} finally { lib.symbols.lb_state_free(st); }
