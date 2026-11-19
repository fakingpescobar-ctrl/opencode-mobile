#!/usr/bin/env bun
// Реальный замер latency на устройстве: 7 моделей × 3 повтора, CPU-only, n_threads=8.
// Методология v3: нейтральный промпт, ровно 24 токена декодирования, EOG не прерывает.
import { dlopen, FFIType } from "bun:ffi";
import { readFileSync, writeFileSync } from "node:fs";
const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT";
const SO = "/data/user/0/org.opencode.mobile.debug/files/musl/libllama-bench.so";
const REPEATS = Number(process.argv[2] || 3);
const lib = dlopen(SO, {
  lb_version: { args: [], returns: FFIType.cstring },
  lb_bench: { args: [FFIType.cstring, FFIType.i32, FFIType.i32, FFIType.ptr], returns: FFIType.i32 },
});
const manifest = JSON.parse(readFileSync(`${ROOT}/tools/llama-bench/models.json`, "utf8"));
const results = [];
const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
const median = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
const runtime = lib.symbols.lb_version();
console.log("рантайм:", runtime, "| повторов:", REPEATS, "| n_ctx=512, n_predict=24, threads=8\n");
for (const m of manifest) {
  const path = `${ROOT}/models/${m.file}`;
  const runs = [];
  // один прогревочный прогон (модель не учитываем)
  { const out = new Float64Array(7); lib.symbols.lb_bench(path, 512, 24, out); }
  for (let i = 0; i < REPEATS; i++) {
    const out = new Float64Array(7);
    const rc = lib.symbols.lb_bench(path, 512, 24, out);
    if (rc !== 0) { console.error(`${m.id} повтор ${i + 1}: rc=${rc}`); continue; }
    runs.push({ decode: out[0], prefill: out[1], ttft: out[2], total: out[3], tokens: out[4], promptTokens: out[5], eosAt: out[6] });
  }
  if (!runs.length) { console.error(`нет валидных замеров: ${m.id}`); continue; }
  const avg = {
    decode: mean(runs.map((r) => r.decode)),
    prefill: mean(runs.map((r) => r.prefill)),
    ttft: mean(runs.map((r) => r.ttft)),
    total: mean(runs.map((r) => r.total)),
    tokens: mean(runs.map((r) => r.tokens)),
    promptTokens: mean(runs.map((r) => r.promptTokens)),
    eosAt: mean(runs.map((r) => r.eosAt)),
  };
  const complete = runs.every((r) => r.tokens === 24);
  if (!complete) console.error(`ВНИМАНИЕ: ${m.id} не добрал 24 токена в каждом прогоне`);
  results.push({ ...m, avg, median: { decode: median(runs.map((r) => r.decode)), total: median(runs.map((r) => r.total)) }, complete, runs });
  console.log(
    `${m.id.padEnd(20)} ${(m.size / 1e6).toFixed(0).padStart(4)} МБ | промпт ${avg.promptTokens.toFixed(0).padStart(2)} ток | ` +
    `prefill ${avg.prefill.toFixed(1).padStart(7)} мс | TTFT ${avg.ttft.toFixed(1).padStart(7)} мс | ` +
    `decode ${avg.decode.toFixed(1).padStart(6)} t/s | 24 токена ${avg.total.toFixed(1).padStart(7)} мс`
  );
}
writeFileSync(`${ROOT}/tools/llama-bench/bench-results.json`, JSON.stringify({ runtime, method: 3, repeats: REPEATS, results }, null, 2));
const md = [
  "# РЕАЛЬНЫЙ БЕНЧ LLM НА ONEPLUS CPH2747 (21.11.2026)",
  "",
  `Рантайм: ${runtime} (llama.cpp b12554, musl arm64, статическая сборка, CPU-only, 8 потоков).`,
  `Повторов: ${REPEATS} на модель (плюс 1 прогрев, не учитывается). n_ctx=512, ровно 24 токена декодирования.`,
  "Методология v3: нейтральный текстовый промпт без чат-спецтокенов — одинаковый для всех моделей;",
  "EOG не прерывает замер, поэтому «всего» соизмеримо у всех моделей. Позиция EOG приведена отдельно.",
  "Метрики: prefill — обработка промпта; TTFT — от начала промпта до первого токена;",
  "decode — токенов/секунду после первого токена; всего — полный цикл. Загрузка модели в время не входит.",
  "",
  "| Модель | Размер | Промпт, ток | Prefill, мс | TTFT, мс | Decode, t/s | 24 токена, мс | EOG на |",
  "|---|---:|---:|---:|---:|---:|---:|---:|",
  ...results.map((r) => `| ${r.id} | ${(r.size / 1e6).toFixed(0)} МБ | ${r.avg.promptTokens.toFixed(0)} | ${r.avg.prefill.toFixed(1)} | ${r.avg.ttft.toFixed(1)} | ${r.avg.decode.toFixed(1)} | ${r.avg.total.toFixed(1)} | ${r.avg.eosAt ? r.avg.eosAt.toFixed(0) : "—"} |`),
  "",
  "Сырые прогоны: `tools/llama-bench/bench-results.json`.",
  "",
].join("\n");
writeFileSync(`${ROOT}/БЕНЧМАРК_ЛЛМ.md`, md);
console.log("\nзаписано: БЕНЧМАРК_ЛЛМ.md и tools/llama-bench/bench-results.json");
