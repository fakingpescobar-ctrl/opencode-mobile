// Патч метаданных сконвертированных ModernBERT-GGUF без изменения размера файла.
//
// llama.cpp b12554 понимает metaspace.prepend_scheme только never/first/always,
// но при загрузке scheme=always падает. Конвертер b12554 пишет always, а править
// tokenizer.json нельзя — сменится chkhsh и get_vocab_base_pre() перестанет
// узнавать mmBERT.
//
// Меняем 6-байтный "always" на 5-байтный "first" и компенсируем 1 байт в
// general.name (добавляем пробел). Сборка буфера через concat, а не in-place,
// чтобы байты следующих ключей не перетирались. Размер хедера, data_offset и
// все tensor offsets остаются побайтно теми же, GGUF не портится.
//
//   bun patch-gguf-metaspace.mjs in.gguf out.gguf
import { readFileSync, writeFileSync } from "node:fs";

const [src, dst] = process.argv.slice(2);
if (!src || !dst) { console.error("нужно: <in.gguf> <out.gguf>"); process.exit(2); }

const buf = readFileSync(src);
const key = Buffer.from("tokenizer.ggml.metaspace.prepend_scheme", "utf8");
const pos = buf.indexOf(key);
if (pos < 0) { console.error("ключ prepend_scheme не найден"); process.exit(3); }

const at = pos + key.length;
if (buf.readUInt32LE(at) !== 8 || Number(buf.readBigUInt64LE(at + 4)) !== 6 ||
    buf.toString("utf8", at + 12, at + 18) !== "always") {
  console.error("неожиданная структура prepend_scheme (ожидали str/6/always)");
  process.exit(4);
}

const nameKey = Buffer.from("general.name", "utf8");
const np = buf.indexOf(nameKey);
if (np < 0 || np > pos) { console.error("general.name не найден до prepend_scheme"); process.exit(5); }
const na = np + nameKey.length;
if (buf.readUInt32LE(na) !== 8) { console.error("general.name не str"); process.exit(6); }
const nlen = Number(buf.readBigUInt64LE(na + 4));
const name = buf.toString("utf8", na + 12, na + 12 + nlen);
const nameVal = na + 12;

const nameLenField = Buffer.allocUnsafe(8);
nameLenField.writeBigUInt64LE(BigInt(nlen + 1));
const prepLenField = Buffer.allocUnsafe(8);
prepLenField.writeBigUInt64LE(5n);
const out = Buffer.concat([
  buf.subarray(0, na + 4),
  nameLenField,
  Buffer.from(name + " ", "utf8"),
  buf.subarray(nameVal + nlen, at + 4),
  prepLenField,
  Buffer.from("first", "utf8"),
  buf.subarray(at + 18),
]);

if (out.length !== buf.length) { console.error("размер изменился — GGUF будет испорчен"); process.exit(7); }
writeFileSync(dst, out);
console.log("patched", src, "->", dst, "size", out.length);
