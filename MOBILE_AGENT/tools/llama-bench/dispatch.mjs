#!/usr/bin/env bun
// Запустить Build llama.cpp musl bench (arm64) через workflow_dispatch.
import { readFileSync } from "node:fs";
const TOK = readFileSync("/storage/emulated/0/Documents/ghtoken_mobile_agent.txt", "utf8").trim();
const REPO = "fakingpescobar-ctrl/opencode-mobile";
const H = {
  Authorization: "Bearer " + TOK,
  Accept: "application/vnd.github+json",
  "User-Agent": "phone-agent",
  "Content-Type": "application/json",
};
const res = await fetch(`https://api.github.com/repos/${REPO}/actions/workflows/build-llama-musl.yml/dispatches`, {
  method: "POST", headers: H,
  body: JSON.stringify({ ref: "main", inputs: { llama_ref: "b12554" } }),
});
const body = await res.text();
console.log(`dispatch → ${res.status} ${body.slice(0, 200)}`);
if (res.status !== 204) process.exit(3);

for (let i = 0; i < 6; i++) {
  await new Promise((r) => setTimeout(r, 10000));
  const runs = await (await fetch(`https://api.github.com/repos/${REPO}/actions/runs?per_page=10&_=${Date.now()}`, { headers: H })).json();
  const hit = (runs.workflow_runs || []).find((r) => r.name.includes("llama.cpp musl"));
  if (hit) {
    console.log(`run ${hit.id} ${hit.status}/${hit.conclusion} sha=${hit.head_sha.slice(0, 10)}`);
    process.exit(0);
  }
  console.log(`  попытка ${i + 1}: прогона нет`);
}
process.exit(4);
