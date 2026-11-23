#!/usr/bin/env bun
// Добавляет отсутствующую запись bert.token_type_count в GGUF BERT-модели.
// cstr-конвертация multilingual-e5-small её не пишет, а llama.cpp b12554
// отказывается грузить bert-модель без этого ключа ("bert model needs to
// define token type count"). Тензор token_types.weight имеет форму [384,2],
// значит правильное значение — 2.
//
// Вставка идёт строго в конец KV-секции, до tensor infos. Смещения тензоров
// в GGUF относительны начала данных, поэтому сдвиг начала данных на длину
// новой записи не портит GGUF. Размер файла растёт ровно на len(entry).
//
//   bun patch-gguf-token-types.mjs in.gguf out.gguf
import { readFileSync, writeFileSync } from "node:fs";
const [src, dst] = process.argv.slice(2);
if (!src || !dst) { console.error("нужно: <in.gguf> <out.gguf>"); process.exit(2); }
const buf = readFileSync(src);
if (buf.toString("ascii", 0, 4) !== "GGUF") { console.error("не GGUF"); process.exit(3); }
const nKv = Number(buf.readBigUInt64LE(16));
let p = 24;
const SZ = { 0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8 };
function skipVal(type) {
  if (type === 8) { const n = Number(buf.readBigUInt64LE(p)); p += 8 + n; return; }
  if (type === 9) {
    const et = buf.readUInt32LE(p); p += 4;
    const n = Number(buf.readBigUInt64LE(p)); p += 8;
    if (et === 8) { for (let i=0;i<n;i++) skipVal(8); }
    else { if (SZ[et] === undefined) throw new Error("array element type " + et); p += n * SZ[et]; }
    return;
  }
  if (SZ[type] === undefined) throw new Error("value type " + type);
  p += SZ[type];
}
for (let i = 0; i < nKv; i++) {
  const n = Number(buf.readBigUInt64LE(p)); p += 8;
  const key = buf.toString("utf8", p, p + n); p += n;
  if (key === "tokenizer.ggml.token_type_count") { console.error("ключ уже есть"); process.exit(4); }
  const type = buf.readUInt32LE(p); p += 4;
  skipVal(type);
}
const kvEnd = p;
const key = Buffer.from("tokenizer.ggml.token_type_count", "utf8");
const lenField = Buffer.allocUnsafe(8); lenField.writeBigUInt64LE(BigInt(key.length));
const typeField = Buffer.allocUnsafe(4); typeField.writeUInt32LE(4, 0); // GGUF_TYPE_UINT32
const count = process.argv[3] ? Number(process.argv[3]) : 2;
const valField = Buffer.allocUnsafe(4); valField.writeUInt32LE(count, 0); // token_types.weight [384,2] → 2
const entry = Buffer.concat([lenField, key, typeField, valField]);
const out = Buffer.concat([buf.subarray(0, kvEnd), entry, buf.subarray(kvEnd)]);
const nk = Buffer.allocUnsafe(8); nk.writeBigUInt64LE(BigInt(nKv + 1));
nk.copy(out, 16); // число KV-записей в заголовке тоже incremented
if (out.length !== buf.length + entry.length) { console.error("размер вырос не так"); process.exit(5); }
writeFileSync(dst, out);
console.log("patched", src, "->", dst, "kvEnd", kvEnd, "entry", entry.length, "size", out.length);
