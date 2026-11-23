#!/usr/bin/env bun
// Список имён тензоров из GGUF (после KV-секции). Нужно, чтобы выбрать
// bert.token_type_count: 1 без token_type_embeddings, 2 с ними.
import { openSync, readSync, closeSync, statSync } from "node:fs";
const p0 = process.argv[2];
if (!p0) process.exit(2);
const fd = openSync(p0, "r"); const b = Buffer.alloc(Math.min(statSync(p0).size, 32 * 1024 * 1024));
readSync(fd, b, 0, b.length, 0); closeSync(fd);
let p = 24;
const nTensors = Number(b.readBigUInt64LE(8));
const nKv = Number(b.readBigUInt64LE(16));
const SZ = { 0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8 };
function skipVal(type) {
  if (type === 8) { const n = Number(b.readBigUInt64LE(p)); p += 8 + n; return; }
  if (type === 9) { const et = b.readUInt32LE(p); p += 4; const n = Number(b.readBigUInt64LE(p)); p += 8;
    if (et === 8) { for (let i=0;i<n;i++) skipVal(8); } else p += n * SZ[et]; return; }
  p += SZ[type];
}
for (let i=0;i<nKv;i++) { const kLen = Number(b.readBigUInt64LE(p)); p += 8 + kLen; const t = b.readUInt32LE(p); p += 4; skipVal(t); }
const names = [];
const shapes = {};
for (let i=0;i<nTensors;i++) {
  const n = Number(b.readBigUInt64LE(p)); p += 8;
  const name = b.toString("utf8", p, p + n); p += n;
  const nd = b.readUInt32LE(p); p += 4;
  const dims = [];
  for (let d=0;d<nd;d++) { dims.push(Number(b.readBigUInt64LE(p))); p += 8; }
  p += 4 + 8;
  names.push(name); shapes[name] = dims;
}
console.log("tensors", names.length, "first", names.slice(0,6), "last", names.slice(-4));
console.log("token_type", names.filter(x=>/token_type/i.test(x)), "shape", shapes[names.find(x=>/token_type/i.test(x))] || null);
