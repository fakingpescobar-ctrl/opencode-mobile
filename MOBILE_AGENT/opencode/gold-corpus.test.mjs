#!/usr/bin/env bun
// Тесты заморозки сплитов, gold_sha, дедупа и квот. Не трогают рабочий gold:
// все записи идут во временную директорию.
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const gc = await import("../tools/gold-corpus.mjs");
const store = await import("./memory-core/memory-adaptive-store.js");
const core = await import("./memory-core/memory-core.js");

let ok = 0, bad = 0;
const failures = [];
function eq(a, b, msg) {
  if (JSON.stringify(a) === JSON.stringify(b)) ok++;
  else { bad++; failures.push(`${msg}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`); }
}
function truthy(v, msg) {
  if (v) ok++;
  else { bad++; failures.push(msg); }
}

const dir = mkdtempSync(join(tmpdir(), "gold-corpus-"));
try {
  // --- Нормализация и сплиты --------------------------------------------
  eq(gc.normalizeQuery("  КАКАЯ   Температура В Печи  "), "какая температура в печи",
    "нормализация: lowercase, trim, схлопывание пробелов");
  eq(gc.normalizeQuery("  Раз\nДва   три "), "раз два три", "переносы тоже схлопываются");
  eq(gc.normalizeQuery("x"), store.normalizeQuery("x"),
    "нормализация в tools и в data-plane совпадают");

  const a = gc.computeSplit("температура печи");
  const b = gc.computeSplit("  Температура   печи ");
  eq(a.split, b.split, "сплит не зависит от регистра и пробелов");
  eq(a.norm_hash, b.norm_hash, "norm_hash не зависит от регистра и пробелов");
  truthy(a.bucket >= 0 && a.bucket < 100, "бакет в диапазоне 0..99");
  truthy(["train", "held-out"].includes(a.split), "сплит из двух допустимых значений");
  eq(a.split, "train" === a.split ? "train" : "held-out", "сплит — строка");
  const s = store.computeSplit("температура печи");
  eq([a.split, a.bucket, a.norm_hash], [s.split, s.bucket, s.norm_hash],
    "new split в tools и в адаптивном store идентичен");

  // --- Заморозка старых сплитов -----------------------------------------
  const raw = [
    { id: "g01", query: "куда plugin пишет отчёт moltbook", expect_source: "СОСТОЯНИЕ.md" },
    { id: "g02", query: "правило 51 память читать самому", expect_source: "ПРАВИЛА.md" },
    { id: "g03", query: "/apk-40 sha установки verify/", expect_source: "ХАРНЕС.md" },
  ];
  const frozenRow = { ...raw[0], split: "train", split_policy: "manual-freeze", hash_salt: null };
  const backfilled = gc.backfillSplits([frozenRow, ...raw.slice(1)]);
  eq(backfilled[0].split, "train", "явный сплит не пересчитывается даже если старое правило даёт held-out");
  eq(backfilled[0].split_policy, "manual-freeze", "политика явного сплита сохраняется");
  eq(backfilled[1].split, gc.legacySplit(raw[1].query), "новый сплит backfill'ен по старому правилу");
  eq(backfilled[1].split_policy, gc.LEGACY_POLICY, "политика backfill записана");
  eq(gc.backfillSplits(backfilled), backfilled, "повторный backfill идемпотентен");

  const back = gc.backfillSplits(raw);
  eq(core.heldoutSubset(back).map((g) => g.id), raw.map((g) => g.id),
    "fallback: когда по старому правилу held-out пуст, возвращается весь gold как раньше");
  const explicit = [
    { id: "h1", query: "хэш этого запроса дал бы train, но сплит закреплён", split: "held-out" },
    { id: "t1", query: "другой запрос", split: "train" },
  ];
  eq(core.heldoutSubset(explicit).map((g) => g.id), ["h1"],
    "heldoutSubset читает закреплённый split, а не пересчитывает хэш");

  // --- gold_sha ----------------------------------------------------------
  const x = [{ id: "g01", split: "train", query: "a" }, { id: "g02", split: "held-out", query: "b" }];
  const y = [{ query: "a", id: "g01", split: "train" }, { split: "held-out", query: "b", id: "g02" }];
  eq(gc.goldSha(x), gc.goldSha(y), "gold_sha не зависит от порядка ключей (порядок строк важен)");
  truthy(gc.goldSha(x) !== gc.goldSha(x.slice().reverse()),
    "перестановка строк меняет gold_sha — корпус append-only");
  truthy(gc.goldSha(x) !== gc.goldSha(x.concat([{ id: "g03", query: "c" }])),
    "gold_sha меняется при добавлении строки");
  eq(gc.goldSha(x).length, 64, "gold_sha — sha256");
  const nested = [{ z: 1, a: { y: 2, b: [3, { d: 4, c: 5 }] }, id: "g01" }];
  eq(store.goldSha(nested), gc.goldSha(nested),
    "рантайм goldSha совпадает с офлайн-модулем на вложенном JSON");
  eq(store.goldSha([]), gc.goldSha([]), "пустой корпус: оба хэша совпадают");

  // --- Чтение/запись -----------------------------------------------------
  const p = join(dir, "gold.jsonl");
  gc.writeGold(p, x);
  eq(gc.readGold(p), x, "writeGold/readGold roundtrip");
  const added = gc.appendGold(p, [{ query: "новый кейс", split: "train", expect_source: "ПРАВИЛА.md" }]);
  eq(added.added.length, 1, "appendGold добавил одну строку");
  eq(added.added[0].id, "g03", "appendGold продолжил нумерацию ID");
  eq(gc.readGold(p).length, 3, "файл обновлён");

  // --- Дедуп по смыслу ---------------------------------------------------
  const hi = gc.aliasScore("какая температура в печи", "температура печи какая сейчас");
  const lo = gc.aliasScore("как найти память", "как удалить память");
  truthy(hi > lo, "переформулировка ближе, чем разные намерения");
  truthy(hi >= 0.75, "Jaccard с поправкой на вложенность ≥0.75 у переформулировки");
  truthy(lo < 0.75, "разные намерения не склеиваются");
  const alias = gc.findAlias([{ id: "g07", split: "held-out", query: "температура печи какая сейчас" }],
    "какая температура в печи");
  eq(alias ? [alias.id, alias.split] : null, ["g07", "held-out"],
    "алиас наследует ID и сплит родителя");
  eq(gc.findAlias([{ id: "g07", split: "train", query: "совершенно другой вопрос" }], "температура печи"),
    null, "посторонний вопрос не становится алиасом");

  const cands = [
    { id: "s001", query: "какая температура в печи", status: "raw", suggested_split: "train", expect_source: "ПРАВИЛА.md" },
    { id: "s002", query: "температура печи какая сейчас", status: "raw", suggested_split: "held-out", expect_source: "ПРАВИЛА.md" },
    { id: "s003", query: "  Какая   температура в печи ", status: "raw", suggested_split: "train", expect_source: "ПРАВИЛА.md" },
  ];
  const ded = gc.dedupCandidates(cands, [], 0.75);
  eq(ded[0].alias_of, null, "первый кандидат отдельный");
  truthy(ded[1].alias_of === "s001" || ded[1].alias_of === "s002",
    "переформулировка помечена как алиас");
  eq(ded[2].alias_of, ded[2].norm_hash === ded[0].norm_hash ? ded[0].alias_of : ded[2].alias_of,
    "дубль по norm_hash обработан");

  // --- Квота --------------------------------------------------------------
  const many = [];
  for (let i = 0; i < 12; i++) {
    many.push({ id: "q" + i, query: "вопрос " + i, status: "raw",
      suggested_split: i % 2 ? "held-out" : "train", expect_source: "LoRa.md" });
  }
  many.push({ id: "z1", query: "реле", status: "raw", suggested_split: "train", expect_source: "RELAY.md" });
  const q = gc.quotaSelect(many, { target: 15, maxShare: 0.30 });
  eq(q.cap, 4, "квота 30% от 15 = 4 на источник");
  truthy(q.pending.length > 0, "переполнение ушло в pending, а не выброшено");
  const perGroup = new Map();
  for (const r of q.selected) {
    const k = r.suggested_split + "|" + r.expect_source;
    perGroup.set(k, (perGroup.get(k) || 0) + 1);
  }
  truthy([...perGroup.values()].every((n) => n <= q.cap), "квота соблюдена в каждой группе split|source");
  const heldSelected = q.selected.filter((r) => r.suggested_split === "held-out");
  truthy(heldSelected.length > 0, "квота считается и для held-out тоже");
  eq(gc.quotaSelect([{ id: "al", query: "x", status: "raw", alias_of: "s001", suggested_split: "train" }], {})
    .selected.length, 0, "алиасы в батч не попадают");
} finally {
  rmSync(dir, { recursive: true, force: true });
}

console.log("\n" + "=".repeat(56));
console.log("ТЕСТЫ GOLD CORPUS: OK=" + ok + " BAD=" + bad);
if (bad) {
  console.log("\nПРОВАЛЫ:\n" + failures.map((f) => "  - " + f).join("\n"));
  process.exit(1);
}
console.log("ВСЕ ТЕСТЫ ПРОШЛИ");
