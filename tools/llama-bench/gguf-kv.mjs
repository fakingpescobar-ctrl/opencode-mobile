#!/usr/bin/env bun
// Минимальный дампер GGUF-метаданных: читает заголовок и KV, показывает ключи.
// Нужно, чтобы понять, почему llama.cpp b12554 отказывает в загрузке BERT-GGUF.
import { openSync, readSync, closeSync, statSync } from "node:fs";
const path = process.argv[2];
if (!path) { console.error("нужно: gguf-kv.mjs <file.gguf>"); process.exit(2); }
const fd = openSync(path, "r");
const size = Math.min(statSync(path).size, 8 * 1024 * 1024);
const b = Buffer.alloc(size);
readSync(fd, b, 0, b.length, 0);
closeSync(fd);
let p = 0;
const magic = b.toString("ascii", 0, 4); p += 4;
const ver = b.readUInt32LE(p); p += 4;
const nTensors = Number(b.readBigUInt64LE(p)); p += 8;
const nKv = Number(b.readBigUInt64LE(p)); p += 8;
console.log({ magic, ver, nTensors, nKv, kvStart: p });
const SZ = { 0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8 };
const TN = ["u8","i8","u16","i16","u32","i32","f32","bool","str","arr","u64","i64","f64"];
function val(type, depth = 0) {
  if (type === 8) { const n = Number(b.readBigUInt64LE(p)); p += 8; const s = b.toString("utf8", p, p + n); p += n; return s; }
  if (type === 9) {
    const et = b.readUInt32LE(p); p += 4;
    const n = Number(b.readBigUInt64LE(p)); p += 8;
    const out = [];
    for (let i = 0; i < Math.min(n, 8); i++) out.push(val(et, depth + 1));
    if (n > 8) { for (let i = 8; i < n; i++) val(et, depth + 1); out.push(`… +${n - 8}`); }
    return { type: TN[et], n, out };
  }
  const s = SZ[type]; if (s === undefined) throw new Error("unknown type " + type);
  let x;
  if (type === 6) x = b.readFloatLE(p);
  else if (type === 12) x = b.readDoubleLE(p);
  else if (type === 7) x = b.readUInt8(p) !== 0;
  else if (s === 8) x = Number(b.readBigUInt64LE(p));
  else x = b.readUIntLE(p, s);
  p += s;
  return x;
}
for (let i = 0; i < nKv; i++) {
  const key = val(8);
  const type = b.readUInt32LE(p); p += 4;
  const start = p;
  const v = val(type);
  console.log(String(i).padStart(3), TN[type] || type, key, "=", typeof v === "string" && v.length > 90 ? v.slice(0, 90) + "…" : v);
  if (key === "bert.token_type_count" || key === "general.architecture") console.log("   ^ raw bytes at", start);
}
console.log("kvEnd", p, "tensorInfosStart", p);
