#!/usr/bin/env bun
// Скачать GGUF из models.json с потоковой sha256-сверкой.
import { readFileSync, openSync, writeSync, closeSync, mkdirSync, statSync, renameSync } from "node:fs";
import { createHash } from "node:crypto";
const DIR = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT/models";
mkdirSync(DIR, { recursive: true });
const manifest = JSON.parse(readFileSync("/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT/tools/llama-bench/models.json", "utf8"));
const wanted = process.argv.slice(2);
const items = wanted.length ? manifest.filter((m) => wanted.includes(m.id)) : manifest;
for (const m of items) {
  const dest = `${DIR}/${m.file}`;
  console.log(`\n=== ${m.id} → ${dest} (${(m.size / 1e6).toFixed(1)} МБ) ===`);
  const res = await fetch(m.url, { headers: { "User-Agent": "phone-agent" } });
  if (!res.ok || !res.body) { console.error("HTTP " + res.status); process.exit(2); }
  const fd = openSync(dest, "w");
  const hash = createHash("sha256");
  let got = 0, nextMark = 64 * 1024 * 1024;
  for await (const chunk of res.body) {
    const buf = Buffer.from(chunk);
    writeSync(fd, buf);
    hash.update(buf);
    got += buf.length;
    if (got >= nextMark) { console.log(`  ${(got / 1e6).toFixed(0)} / ${(m.size / 1e6).toFixed(0)} МБ`); nextMark += 64 * 1024 * 1024; }
  }
  closeSync(fd);
  const sha = hash.digest("hex");
  const actual = statSync(dest).size;
  const ok = actual === m.size && sha === m.sha256;
  console.log(`  результат: ${actual} б, sha=${sha}`);
  if (!ok) {
    renameSync(dest, dest + ".bad");
    console.error(`  НЕ СОВПАЛО (ожидалось ${m.size} б / ${m.sha256}); файл → .bad`);
    process.exit(3);
  }
  console.log("  OK");
}
