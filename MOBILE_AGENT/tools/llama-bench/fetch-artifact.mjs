#!/usr/bin/env bun
// Скачать артефакт CI по ID запуска через Contents-independent GitHub API.
// Вызов: fetch-artifact.mjs <run_id> <artifact_name> <out.zip>
import { readFileSync, writeFileSync } from "node:fs";
const [runId, name, out] = process.argv.slice(2);
if (!runId || !name || !out) {
  console.error("нужно: <run_id> <artifact_name> <out.zip>");
  process.exit(2);
}
const T = readFileSync("/storage/emulated/0/Documents/ghtoken_mobile_agent.txt", "utf8").trim();
const H = {
  Authorization: "Bearer " + T,
  Accept: "application/vnd.github+json",
  "X-GitHub-Api-Version": "2022-11-28",
  "User-Agent": "opencode-mobile-phone-agent",
};
const repo = "fakingpescobar-ctrl/opencode-mobile";
const j = await (await fetch(`https://api.github.com/repos/${repo}/actions/runs/${runId}/artifacts?per_page=100`, { headers: H })).json();
const a = (j.artifacts || []).find((x) => x.name === name);
if (!a) {
  console.error("артефакт не найден:", (j.artifacts || []).map((x) => x.name).join(", "));
  process.exit(3);
}
console.log(`артефакт ${a.name}: ${a.size} б, id ${a.id}`);
const r = await fetch(`https://api.github.com/repos/${repo}/actions/artifacts/${a.id}/zip`, { headers: H });
if (!r.ok) { console.error("скачка:", r.status, (await r.text()).slice(0, 200)); process.exit(4); }
const buf = Buffer.from(await r.arrayBuffer());
writeFileSync(out, buf);
console.log(`сохранено ${out}: ${buf.length} б`);
