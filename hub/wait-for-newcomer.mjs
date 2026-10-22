#!/usr/bin/env node
// Wait until a teammate reports a bot that needs Claude, print it, and exit: one the team has never
// had before, a known one sitting paused (back from a restart) or with nothing to do, or a bot asking
// for help with something it isn't built to handle (relayed by whoever heard it on chat), or one
// taking such a request back because it sorted itself out (RESOLVED: nothing to do).
//
// For Claude: run this in the background while leading the team. Its exit is the ping; start the
// bot or give it work (through a leader in range), then run this again. It remembers the last alert it
// printed in alerts-seen.json beside it, so a restart neither repeats nor misses one.
//
//   node hub/wait-for-newcomer.mjs <server key> [hub url]
//
// The hub url defaults to http://127.0.0.1:25599 (no token needed from this machine; otherwise
// ZYMBOT_HUB_TOKEN).

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const server = process.argv[2];
const hub = (process.argv[3] || "http://127.0.0.1:25599").replace(/\/$/, "");
if (!server) {
  console.error("usage: node hub/wait-for-newcomer.mjs <server key> [hub url]");
  process.exit(2);
}
const SEEN = path.join(path.dirname(fileURLToPath(import.meta.url)), "alerts-seen.json");

function seen() {
  try {
    return JSON.parse(fs.readFileSync(SEEN, "utf8"))[server];
  } catch {
    return undefined;
  }
}

function remember(last) {
  let all = {};
  try { all = JSON.parse(fs.readFileSync(SEEN, "utf8")); } catch { /* first time */ }
  all[server] = last;
  fs.writeFileSync(SEEN, JSON.stringify(all, null, 2));
}

async function poll(after, wait) {
  const headers = process.env.ZYMBOT_HUB_TOKEN ? { authorization: "Bearer " + process.env.ZYMBOT_HUB_TOKEN } : {};
  const r = await fetch(`${hub}/alerts?server=${encodeURIComponent(server)}&after=${after}&wait=${wait}`, { headers });
  const body = await r.json();
  if (!body.ok) throw new Error(body.error || "hub said no");
  return body;
}

let after = seen();
if (after === undefined) {
  // first run: start from now, not from every newcomer the hub has ever had
  const all = (await poll(0, 0)).alerts;
  after = all.length ? all[all.length - 1].seq : 0;
  remember(after);
}
for (;;) {
  let body;
  try {
    body = await poll(after, 300);
  } catch (e) {
    // the hub restarting, or not up yet: try again shortly
    await new Promise((r) => setTimeout(r, 10_000));
    continue;
  }
  if (!body.alerts.length) continue;
  for (const a of body.alerts) {
    const where = a.pos ? ` at ${Math.round(a.pos.x)},${Math.round(a.pos.y)},${Math.round(a.pos.z)}` : "";
    const when = new Date(a.at).toLocaleTimeString();
    if (a.kind === "help") {
      console.log(`HELP from ${a.name}${a.via === "self" ? "" : ` (relayed by ${a.heard_by})`}${where}: ${a.text} (${when})`);
      continue;
    }
    if (a.kind === "resolved") {
      console.log(`RESOLVED ${a.name} no longer needs help${where}: ${a.text} (${when})`);
      continue;
    }
    const what = a.kind === "paused" ? "PAUSED" : a.kind === "idle" ? "IDLE (nothing to do)" : "NEW BOT";
    console.log(`${what} ${a.name}: heard by ${a.heard_by} ${a.via === "chat" ? "on chat" : "on the hub"}${where} (${when})`);
  }
  remember(body.last);
  process.exit(0);
}
