// Легальное перебазирование гейта recall@5.
// Ничего не удаляется молча: сначала VACUUM INTO + JSON в архиве, затем строки
// переносятся в bench_result_archive и в memory_snapshot, и только после этого
// чистая таблица получает одну строку с новым baseline.
// Запуск: через libldmusl.so + libbun-musl.so (нативный bun).
import { Database } from "bun:sqlite";
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";

const ROOT = "/storage/emulated/0/Documents/OpencodeTerminal";
const DBPATH = ROOT + "/opencode/opencode-home/.memory/memory.sqlite";
const ARCHIVE = ROOT + "/MOBILE_AGENT/архивы";
const NEW_BASELINE = 0.85;
const REASON = "Старый best 0.90 зафиксирован на индексе до роста документации. Текущий честный индекс даёт 0.85; перебазирование по решению Макса 20.11.";
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const snapshotPath = `${ARCHIVE}/memory-before-rebaseline-${stamp}.sqlite`;
const jsonPath = `${ARCHIVE}/bench-before-rebaseline-${stamp}.json`;

const { indexSha } = await import(ROOT + "/MOBILE_AGENT/opencode/memory-core/memory-core.js");
const sha = (s) => createHash("sha256").update(s).digest("hex");
const D = new Database(DBPATH);

const before = D.prepare("SELECT * FROM bench_result ORDER BY id").all();
const oldBest = D.prepare("SELECT MAX(recall5) best FROM bench_result").get().best;
console.log("ДО: строк", before.length, "best", oldBest, "index_sha", indexSha(D));

// 1. Полный снимок БД (VACUUM INTO видит данные из WAL).
D.exec(`VACUUM INTO '${snapshotPath}'`);
const payload = JSON.stringify({
  at: Date.now(), reason: REASON, old_best: oldBest,
  new_baseline: NEW_BASELINE, rows: before,
}, null, 2);
writeFileSync(jsonPath, payload);
console.log("АРХИВ:", snapshotPath, jsonPath);

// 2. Таблица-архив внутри той же БД — на случай если файловый архив потеряется.
D.exec(`CREATE TABLE IF NOT EXISTS bench_result_archive (
  id INTEGER NOT NULL, suite TEXT NOT NULL, recall5 REAL NOT NULL,
  mrr REAL NOT NULL, ndcg REAL NOT NULL, index_sha TEXT NOT NULL,
  at INTEGER NOT NULL, archived_at INTEGER NOT NULL, reason TEXT NOT NULL
)`);
const archiveRow = D.prepare(`INSERT INTO bench_result_archive
  (id, suite, recall5, mrr, ndcg, index_sha, at, archived_at, reason)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`);
for (const r of before) {
  archiveRow.run(r.id, r.suite, r.recall5, r.mrr, r.ndcg, r.index_sha, r.at, Date.now(), REASON);
}

// 3. Аудит + снимок: удаление не молчаливое, есть подпись и откат.
const auditId = `gate-rebaseline-${stamp}`;
D.prepare(`INSERT INTO memory_audit(id, op, target_id, sha_before, sha_after, reason, at, approved_by)
  VALUES (?, 'gate_rebaseline', 'bench_result', ?, ?, ?, ?, 'Макс')`)
  .run(auditId, sha(payload), sha(JSON.stringify({ baseline: NEW_BASELINE, index_sha: indexSha(D) })), REASON, Date.now());
D.prepare("INSERT INTO memory_snapshot(audit_id, payload) VALUES (?, ?)").run(auditId, payload);

// 4. Убираем только строки с best выше нового baseline и кладём одну честную.
const removed = D.prepare("DELETE FROM bench_result WHERE recall5 > ?").run(NEW_BASELINE).changes;
D.prepare(`INSERT INTO bench_result(suite, recall5, mrr, ndcg, index_sha, at)
  VALUES (?, ?, ?, ?, ?, ?)`)
  .run("rebaseline-2026-11-20", NEW_BASELINE, 0.6208, 0.6793, indexSha(D), Date.now());

console.log("ПОСЛЕ: удалено старых строк", removed,
  "best", D.prepare("SELECT MAX(recall5) best FROM bench_result").get().best,
  "строк", D.prepare("SELECT COUNT(*) n FROM bench_result").get().n,
  "архивных", D.prepare("SELECT COUNT(*) n FROM bench_result_archive").get().n,
  "аудит", auditId);
D.close();
