#!/usr/bin/env bun
// Квантовать GGUF через наш libllama-bench.so (тот же llama.cpp b12554).
// Вызов: quantize-research.mjs <src.gguf> <dst.gguf> <ftype: 7=Q8_0, 15=Q4_K_M>
import { dlopen, FFIType } from "bun:ffi";
import { statSync } from "node:fs";
const [src, dst, ftypeArg] = process.argv.slice(2);
if (!src || !dst) { console.error("нужно: <src.gguf> <dst.gguf> [ftype]"); process.exit(2); }
const lib = dlopen("/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so", {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_quantize: { args: [FFIType.cstring, FFIType.cstring, FFIType.i32], returns: FFIType.i32 },
});
console.log("runtime", String(lib.symbols.lb_version()));
const ftype = Number(ftypeArg || 7);
const t0 = performance.now();
const rc = lib.symbols.lb_quantize(src, dst, ftype);
if (rc !== 0) { console.error("rc=" + rc); process.exit(3); }
console.log("готово", dst, statSync(dst).size, "б", Math.round(performance.now() - t0), "мс");
