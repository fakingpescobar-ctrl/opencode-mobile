#!/usr/bin/env bun
// Тесты data-plane адаптивного слоя: hash-замок, opt-in, кулдаун,
// delta cap, maxDrop, approve/rollback. Не трогают рабочую БД и ядро.
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const store = await import("./memory-core/memory-adaptive-store.js");

let ok = 0, bad = 0;
const failures = [];
function eq(a, b, msg) {
  if (Object.is(a, b)) ok++;
  else { bad++; failures.push(`${msg}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`); }
}
function truthy(v, msg) {
  if (v) ok++;
  else { bad++; failures.push(msg); }
}

const dir = mkdtempSync(join(tmpdir(), "adaptive-store-"));
try {
  let s = store.loadState(dir);
  eq(s.status, "new", "новое состояние");
  eq(s.state.enabled, false, "по умолчанию выключено");
  eq(s.state.params.fusion, "rrf", "дефолтный fusion");

  const hyp = {
    indexSha: "test-sha",
    baseline: { trainTop1: 0.48, heldoutTop1: 0.66 },
    candidate: {
      trainTop1: 0.52, heldoutTop1: 0.67,
      params: { marginCut: 0.0052, confCut: 0.152, weight: 0.549, fusion: "rrf" },
    },
  };

  s = store.propose(dir, hyp);
  eq(s.reason, "disabled", "неактивный слой не предлагает");

  store.setEnabled(dir, true);
  for (let i = 0; i < 18; i++) store.recordSignal(dir, { queryId: `q${i}`, kind: "rule", signal: "anchor", success: i % 2 === 0 });
  eq(store.recordSignal(dir, { queryId: "q18", kind: "rule", signal: "anchor", success: true }).eligible, false, "кулдаун 20 ещё не пройден");
  eq(store.recordSignal(dir, { queryId: "q19", kind: "rule", signal: "anchor", success: true }).eligible, true, "20 уникальных запросов открывают proposal");

  let p = store.propose(dir, {
    ...hyp,
    candidate: { ...hyp.candidate, params: { ...hyp.candidate.params, weight: 0.9 } },
  });
  eq(p.reason, "delta-cap:weight", "вес не может прыгнуть больше чем на 10%");

  p = store.propose(dir, {
    ...hyp,
    candidate: { ...hyp.candidate, heldoutTop1: 0.60 },
  });
  eq(p.reason, "heldout-drop", "просадка held-out больше 0.02 запрещена");

  p = store.propose(dir, hyp);
  eq(p.ok, true, "валидная гипотеза встала в pending");
  eq(store.propose(dir, hyp).reason, "pending-exists", "второй pending не создаётся");

  eq(store.approve(dir, { indexSha: "other", trainTop1: 0.52, heldoutTop1: 0.67 }).reason,
    "index-sha-mismatch", "чужой снимок не апрувится");
  const a = store.approve(dir, { indexSha: "test-sha", trainTop1: 0.52, heldoutTop1: 0.67 });
  eq(a.ok, true, "апрув прошёл");
  eq(a.params.weight, 0.549, "вес применён");
  eq(store.policySnapshot(dir).seen, 0, "счётчик кулдауна сброшен после approve");

  const r = store.rollback(dir, "test");
  eq(r.ok, true, "rollback доступен");
  eq(r.params.weight, 0.5, "вернулись старые параметры");

  // Порча файла: состояние обязано карантинироваться, а не довериться мусору.
  const path = store.statePath(dir);
  const raw = JSON.parse(readFileSync(path, "utf8"));
  writeFileSync(path, JSON.stringify({ ...raw, params: { ...raw.params, weight: 0.99 } }));
  s = store.loadState(dir);
  eq(s.status, "quarantined", "битый hash карантинируется");
  eq(s.state.params.weight, 0.5, "дефолт подставлен вместо испорченного веса");

  // --- raw_signals: кандидаты в gold ------------------------------------
  const sigDir = mkdtempSync(join(tmpdir(), "signal-candidates-"));
  try {
    let sc = store.loadSignalCandidates(sigDir);
    eq(sc.status, "new", "буфер сигналов изначально пуст");
    eq(store.listSignalCandidates(sigDir).length, 0, "нет кандидатов");

    const first = store.recordSignalCandidate(sigDir, {
      query: "  Какая   Температура в Печи ",
      index_sha: "idx1", gold_sha: "gold1",
      candidate_chunk_ids: ["a", "b"], expected_chunk_id: null, origin: "miss",
    });
    eq(first.ok, true, "сигнал записан");
    eq(first.count, 1, "один кандидат в буфере");
    eq(first.eligible, false, "до 10 уникальных куратор не зовётся");

    const row = store.listSignalCandidates(sigDir)[0];
    eq(JSON.stringify([row.suggested_split, row.split_policy, row.hash_salt]),
      JSON.stringify([store.computeSplit("какая температура в печи").split, "sha256-bucket-75-v1", "gold-v1"]),
      "сплит и соль зафиксированы в момент сигнала");
    eq(row.expected_chunk_id, null, "неизвестный chunk_id остаётся null");
    eq(row.origin, "miss", "origin по умолчанию miss");

    eq(store.recordSignalCandidate(sigDir, { query: "какая температура в печи", origin: "miss" }).reason,
      "duplicate", "дубль по norm_hash не попадает в буфер повторно");
    eq(store.recordSignalCandidate(sigDir, { query: "  " }).reason, "bad-query",
      "пустой запрос не записывается");

    for (let i = 0; i < 10; i++) {
      store.recordSignalCandidate(sigDir, { query: "уникальный промах " + i, origin: i % 3 ? "miss" : "control" });
    }
    eq(store.listSignalCandidates(sigDir, { status: "raw" }).length, 11, "накопилось 11 уникальных сигналов");
    eq(store.recordSignalCandidate(sigDir, { query: "ещё один", origin: "miss" }).eligible, true,
      "порог в 10 уникальных открыт");

    eq(store.setSignalCandidateStatus(sigDir, first.id, "staging", "в батче").ok, true,
      "статус staging проставлен");
    eq(store.listSignalCandidates(sigDir, { status: "staging" }).length, 1, "один в staging");
    eq(store.setSignalCandidateStatus(sigDir, first.id, "wrong", "").reason, "bad-status",
      "неизвестный статус отвергнут");

    const sigPath = store.signalCandidatesPath(sigDir);
    const tampered = JSON.parse(readFileSync(sigPath, "utf8"));
    tampered.candidates[0].expected_chunk_id = "hacked";
    writeFileSync(sigPath, JSON.stringify(tampered));
    eq(store.loadSignalCandidates(sigDir).status, "quarantined", "порча буфера карантинируется");
    eq(store.listSignalCandidates(sigDir).length, 0, "испорченный буфер не читается");
  } finally {
    rmSync(sigDir, { recursive: true, force: true });
  }
} finally {
  rmSync(dir, { recursive: true, force: true });
}

console.log("\n" + "=".repeat(56));
console.log("ТЕСТЫ ADAPTIVE STORE: OK=" + ok + " BAD=" + bad);
if (bad) {
  console.log("\nПРОВАЛЫ:\n" + failures.map((f) => "  - " + f).join("\n"));
  process.exit(1);
}
console.log("ВСЕ ТЕСТЫ ПРОШЛИ");
