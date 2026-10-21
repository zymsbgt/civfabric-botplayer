#!/usr/bin/env node
// ZymBot team hub: the one place bots on any machine meet.
//
// It holds each bot's latest state, delivers radio messages, and keeps the job boards — and it
// is the referee for the game's rule that bots more than RANGE blocks apart (or in different
// dimensions) cannot communicate. Every message and every board action is checked against the
// positions bots last reported, so no client can talk or read past that range.
//
// No dependencies. Run:   ZYMBOT_HUB_TOKEN=secret node hub/zymbot-hub.mjs
// Env: ZYMBOT_HUB_PORT (25599), ZYMBOT_HUB_HOST (0.0.0.0), ZYMBOT_HUB_TOKEN (required for non-local
//      hosts; connections from this machine itself don't need it), ZYMBOT_RANGE (150),
//      ZYMBOT_HUB_DATA (hub-data.json next to this file),
//      ZYMBOT_HUB_MOD (the mod jar bots with autoUpdate take; default the newest build/libs/zymbot-*.jar)

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const PORT = Number(process.env.ZYMBOT_HUB_PORT || 25599);
const HOST = process.env.ZYMBOT_HUB_HOST || "0.0.0.0";
const TOKEN = process.env.ZYMBOT_HUB_TOKEN || "";
const RANGE = Number(process.env.ZYMBOT_RANGE || 150);
const DATA = process.env.ZYMBOT_HUB_DATA || path.join(path.dirname(fileURLToPath(import.meta.url)), "hub-data.json");
const ONLINE_MS = 15_000;
const CLAIM_TTL_MS = 20 * 60_000;
const OFFLINE_RELEASE_MS = 2 * 60_000; // a claimer offline this long loses its share
const INBOX_CAP = 200;
const MOD = process.env.ZYMBOT_HUB_MOD || "";
const LIBS = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "build", "libs");

if (!TOKEN && HOST !== "127.0.0.1" && HOST !== "localhost") {
  console.error("Refusing to listen on " + HOST + " without ZYMBOT_HUB_TOKEN. Set a token, or ZYMBOT_HUB_HOST=127.0.0.1 for local-only.");
  process.exit(1);
}

// ---- state ---------------------------------------------------------------------------

/** server key -> { members: Map<name,{state,updated}>, inboxes: Map<name,[]>, boards: Map<id,board> } */
const servers = new Map();
// From the clock, not 0: bots poll "messages after N" and keep N across a hub restart, so numbers
// that started over were all below it and nothing new reached them until the count caught up
let seq = Date.now();
let dirty = false;

function server(key) {
  key = String(key || "default");
  if (!servers.has(key)) servers.set(key, { members: new Map(), inboxes: new Map(), boards: new Map() });
  return servers.get(key);
}

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(DATA, "utf8"));
    for (const [key, s] of Object.entries(raw.servers || {})) {
      const sv = server(key);
      for (const b of s.boards || []) sv.boards.set(b.id, b);
    }
    console.log(`Loaded boards from ${DATA}`);
  } catch { /* first run */ }
}

function save() {
  if (!dirty) return;
  dirty = false;
  const out = { servers: {} };
  for (const [key, s] of servers) out.servers[key] = { boards: [...s.boards.values()] };
  fs.writeFileSync(DATA + ".tmp", JSON.stringify(out, null, 2));
  fs.renameSync(DATA + ".tmp", DATA);
}

// ---- range ---------------------------------------------------------------------------

function member(sv, name) {
  if (!name) return null;
  for (const [n, m] of sv.members) if (n.toLowerCase() === String(name).toLowerCase()) return m;
  return null;
}

function online(m) {
  return m && Date.now() - m.updated < ONLINE_MS;
}

function dist(a, b) {
  const dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

/** Can a bot at state `a` reach point `p` in dimension `dim`? */
function reaches(a, p, dim) {
  return a && a.pos && a.dim === dim && dist(a.pos, p) <= RANGE;
}

function inRangeOf(sv, me) {
  const out = [];
  for (const m of sv.members.values()) {
    if (m.state.name === me.state.name || !online(m)) continue;
    if (reaches(me.state, m.state.pos, m.state.dim)) out.push({ m, d: dist(me.state.pos, m.state.pos) });
  }
  return out.sort((a, b) => a.d - b.d);
}

function summary(st, d) {
  return {
    name: st.name, role: st.role, distance: Math.round(d || 0), pos: st.pos, dim: st.dim,
    health: st.health, food: st.food, downed: st.downed, enabled: st.enabled, task: st.task,
    inventory: st.inventory, armor: st.armor, recent_events: (st.events || []).slice(-8),
    // class roles and the food phase, so teammates split roles and know when the farm is up
    class_role: st.class_role, classes: st.classes, class_xp: st.class_xp, class_slots: st.class_slots,
    drain_armed: st.drain_armed, phase: st.phase, farm_plots: st.farm_plots, home: st.home, chests: st.chests,
    landmarks: st.landmarks, team_homes: st.team_homes, base: st.base,
    // moon forecast readings, passed from bot to bot as they meet, and whose players' readings count
    forecast: st.forecast, forecast_readings: st.forecast_readings, forecast_trust: st.forecast_trust,
  };
}

// ---- boards --------------------------------------------------------------------------
//
// A post asks for `count` of an item. Several bots can work on one post at once: each claims a
// share, delivers in batches and reports every batch, so `delivered` is real progress and
// nothing is lost if a bot quits halfway. `open` is what nobody has claimed yet. A claimer that
// can't make some ingredient posts a sub-post (`parent`) for it; sub-posts go away with the
// claim that spawned them.

const MAX_CHAIN = 3;

/** Bring a post up to date: drop stale claims, recompute open/status. Old single-claim posts are migrated. */
function refresh(sv, board, p, now) {
  if (!p.claims) {
    p.claims = [];
    if (p.status === "claimed") p.status = "open";
    delete p.claimedBy;
    dirty = true;
  }
  if (p.status === "cancelled" || p.status === "done") return;
  for (const c of [...p.claims]) {
    const m = sv.members.get(c.by);
    const offlineFor = m ? now - m.updated : 0; // unknown (hub restarted): only the heartbeat TTL applies
    // a bot that reports working on a different post (or none) has lost this job, e.g. it restarted
    const abandoned = m && online(m) && typeof m.state.claim === "string" && m.state.claim !== p.id && now - c.claimedAt > 60_000;
    if (now - c.heartbeat > CLAIM_TTL_MS || offlineFor > OFFLINE_RELEASE_MS || abandoned) dropClaim(board, p, c.by, now);
  }
  p.delivered = Math.min(p.count, p.delivered || 0);
  if (p.delivered >= p.count) {
    for (const c of [...p.claims]) dropClaim(board, p, c.by, now);
    Object.assign(p, { status: "done", open: 0, updatedAt: now });
    dirty = true;
    return;
  }
  const outstanding = p.claims.reduce((n, c) => n + Math.max(0, c.count - c.delivered), 0);
  p.open = Math.max(0, p.count - p.delivered - outstanding);
  p.status = p.open > 0 ? "open" : "taken";
}

/** Remove `by`'s claim on a post and cancel the sub-posts it asked for. */
function dropClaim(board, p, by, now) {
  const before = p.claims.length;
  p.claims = p.claims.filter((c) => c.by !== by);
  if (p.claims.length === before) return;
  for (const sub of board.posts) {
    if (sub.parent === p.id && sub.by === by && sub.status !== "done" && sub.status !== "cancelled") {
      cancelPost(board, sub, now);
    }
  }
  p.updatedAt = now;
  dirty = true;
}

function cancelPost(board, p, now) {
  for (const c of [...(p.claims || [])]) dropClaim(board, p, c.by, now);
  Object.assign(p, { status: "cancelled", open: 0, updatedAt: now });
  dirty = true;
}

function expireClaims(sv, board) {
  const now = Date.now();
  for (const p of board.posts) refresh(sv, board, p, now);
  // finished posts linger for an hour so requesters can see them, then go
  const before = board.posts.length;
  board.posts = board.posts.filter((p) => p.status !== "done" && p.status !== "cancelled" || now - p.updatedAt < 3600_000);
  if (board.posts.length !== before) dirty = true;
}

function boardsFor(sv, me) {
  const out = [];
  for (const b of sv.boards.values()) {
    if (!reaches(me.state, b.pos, b.dim)) continue;
    expireClaims(sv, b);
    out.push({ ...b, distance: Math.round(dist(me.state.pos, b.pos)) });
  }
  return out.sort((a, b) => a.distance - b.distance);
}

function boardOp(sv, body) {
  const me = member(sv, body.as);
  if (!online(me)) return { ok: false, error: `${body.as} is offline` };
  const now = Date.now();
  if (body.op === "create") {
    const id = crypto.randomUUID().slice(0, 8);
    const b = {
      id, name: String(body.name || `${me.state.name}'s board`), dim: me.state.dim,
      pos: { x: Math.round(me.state.pos.x), y: Math.round(me.state.pos.y), z: Math.round(me.state.pos.z) },
      chest: body.chest || null, createdBy: me.state.name, createdAt: now, posts: [],
    };
    sv.boards.set(id, b);
    dirty = true;
    return { ok: true, board: b };
  }
  const b = sv.boards.get(body.board) || [...sv.boards.values()].find((x) => x.name.toLowerCase() === String(body.board || "").toLowerCase());
  if (!b) return { ok: false, error: `no board ${body.board}` };
  if (!reaches(me.state, b.pos, b.dim)) return { ok: false, error: `${me.state.name} is too far from board ${b.name} (must be within ${RANGE} blocks)` };
  expireClaims(sv, b);
  const post = body.post ? b.posts.find((p) => p.id === body.post) : null;
  const mine = post && post.claims ? post.claims.find((c) => c.by === me.state.name) : null;
  const finish = (r) => {
    if (post) refresh(sv, b, post, now);
    dirty = true;
    return r;
  };
  switch (body.op) {
    case "set_chest":
      b.chest = body.chest || null;
      dirty = true;
      return { ok: true, board: b };
    case "remove":
      if (b.createdBy !== me.state.name) return { ok: false, error: "only the creator can remove a board" };
      sv.boards.delete(b.id);
      dirty = true;
      return { ok: true };
    case "post": {
      const count = Math.max(1, Math.min(4096, Number(body.count || 1)));
      let parent = null;
      if (body.parent) {
        // a sub-post: only someone holding a share of the parent may ask for its ingredients
        const pp = b.posts.find((p) => p.id === body.parent);
        if (!pp || !(pp.claims || []).some((c) => c.by === me.state.name)) return { ok: false, error: "sub-posts need a claim on the parent post" };
        const chain = [...(pp.chain || []), pp.item];
        if (chain.length > MAX_CHAIN) return { ok: false, error: "request chain too deep" };
        if (chain.includes(String(body.item))) return { ok: false, error: `${body.item} is already further up this request chain` };
        parent = { post: pp.id, chain };
      }
      const dup = b.posts.find((p) => p.status !== "done" && p.status !== "cancelled" && p.by === me.state.name
        && p.item === body.item && (p.parent || null) === (parent ? parent.post : null));
      if (dup) return { ok: true, post: dup, note: "already posted" };
      const p = {
        id: crypto.randomUUID().slice(0, 6), item: String(body.item), count, delivered: 0, open: count,
        deliverTo: String(body.deliver_to || me.state.name), by: me.state.name, note: body.note || "",
        status: "open", claims: [], parent: parent ? parent.post : null, chain: parent ? parent.chain : [],
        createdAt: now, updatedAt: now,
      };
      b.posts.push(p);
      dirty = true;
      return { ok: true, post: p };
    }
    case "claim": {
      // take a share (default: everything still open)
      if (!post) return { ok: false, error: "no such post" };
      if (post.status === "done" || post.status === "cancelled") return { ok: false, error: `post is ${post.status}` };
      if (mine) return { ok: false, error: `${me.state.name} already has a share of ${mine.count}` };
      if (post.open <= 0) return { ok: false, error: `nothing left to claim (${post.claims.map((c) => c.by).join(", ")} on it)` };
      const share = Math.max(1, Math.min(post.open, Number(body.count || post.open)));
      post.claims.push({ by: me.state.name, count: share, delivered: 0, claimedAt: now, heartbeat: now });
      post.updatedAt = now;
      return finish({ ok: true, post, share });
    }
    case "progress":
    case "release":
    case "done": {
      // progress: heartbeat, optionally `add` = items just delivered.
      // done: finished our share. release: giving up the rest of our share.
      if (!post) return { ok: false, error: "no such post" };
      // our last batch may have completed the post (which drops every claim): nothing left to finish
      if (!mine && body.op !== "progress" && (post.status === "done" || post.status === "cancelled")) return { ok: true, post };
      if (!mine) return { ok: false, error: "not your claim" };
      const add = Math.max(0, Math.floor(Number(body.add || 0)));
      if (add) {
        mine.delivered += add;
        post.delivered = Math.min(post.count, (post.delivered || 0) + add);
      }
      mine.heartbeat = now;
      post.updatedAt = now;
      if (body.op !== "progress") dropClaim(b, post, me.state.name, now);
      return finish({ ok: true, post });
    }
    case "cancel":
      if (!post) return { ok: false, error: "no such post" };
      if (post.by !== me.state.name) return { ok: false, error: "only the poster can cancel" };
      cancelPost(b, post, now);
      return finish({ ok: true, post });
    default:
      return { ok: false, error: `unknown op ${body.op}` };
  }
}

// ---- messages ------------------------------------------------------------------------

function send(sv, body) {
  const from = member(sv, body.from);
  if (!online(from)) return { ok: false, error: `${body.from} is offline` };
  const id = body.id || crypto.randomUUID();
  const msg = { seq: 0, id, from: from.state.name, via: body.via || null, text: String(body.text || ""), ts: Date.now() };
  const targets = [];
  const dropped = [];
  const to = String(body.to || "");
  if (to.toLowerCase() === from.state.name.toLowerCase()) {
    targets.push(from.state.name); // a leader's controller talking to its own bot
  } else if (to === "all" || to === "bots") {
    for (const { m } of inRangeOf(sv, from)) targets.push(m.state.name);
  } else {
    const t = member(sv, to);
    if (!online(t)) dropped.push({ name: to, reason: "offline or unknown" });
    else if (!reaches(from.state, t.state.pos, t.state.dim)) dropped.push({ name: t.state.name, reason: `out of range (${Math.round(dist(from.state.pos, t.state.pos))} blocks)` });
    else targets.push(t.state.name);
  }
  for (const name of targets) {
    const box = sv.inboxes.get(name) || [];
    box.push({ ...msg, seq: ++seq });
    while (box.length > INBOX_CAP) box.shift();
    sv.inboxes.set(name, box);
  }
  return { ok: targets.length > 0, id, sent: targets, dropped };
}

// ---- mod updates ---------------------------------------------------------------------

/** The jar bots with autoUpdate should run: ZYMBOT_HUB_MOD, else the newest zymbot-<version>.jar the build left. */
function modPath() {
  if (MOD) return MOD;
  const jars = fs.readdirSync(LIBS).filter((f) => /^zymbot-[\d.]+\.jar$/.test(f))
    .map((f) => path.join(LIBS, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  if (!jars.length) throw new Error("no zymbot jar in " + LIBS);
  return jars[0];
}

let modCache = null;
/** The jar's bytes and SHA-256, read again whenever the file changes (a build mid-write is caught by the bot's hash check). */
function mod() {
  const file = modPath();
  const st = fs.statSync(file);
  if (!modCache || modCache.file !== file || modCache.mtime !== st.mtimeMs || modCache.size !== st.size) {
    const bytes = fs.readFileSync(file);
    modCache = { file, mtime: st.mtimeMs, size: bytes.length, bytes, sha256: crypto.createHash("sha256").update(bytes).digest("hex") };
  }
  return modCache;
}

// ---- http ----------------------------------------------------------------------------

const refusedAt = new Map();
const seenFrom = new Set();

function authorized(req) {
  if (!TOKEN) return true;
  // this machine's own bots and the MCP server: the token is for other machines on the LAN
  const from = req.socket.remoteAddress || "";
  if (from === "127.0.0.1" || from === "::1" || from === "::ffff:127.0.0.1") return true;
  const h = req.headers["authorization"] || "";
  const given = Buffer.from(h.replace(/^Bearer\s+/i, ""));
  const want = Buffer.from(TOKEN);
  return given.length === want.length && crypto.timingSafeEqual(given, want);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > 1_000_000) reject(new Error("body too large"));
    });
    req.on("end", () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(e); }
    });
  });
}

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://hub");
  const reply = (code, obj) => {
    res.writeHead(code, { "content-type": "application/json" });
    res.end(JSON.stringify(obj));
  };
  if (!authorized(req)) {
    // a bot on another machine that never shows up: this says whether it reached us at all
    const from = req.socket.remoteAddress || "?";
    if (!refusedAt.has(from) || Date.now() - refusedAt.get(from) > 60_000) {
      refusedAt.set(from, Date.now());
      console.log(`Refused ${req.method} ${url.pathname} from ${from}: ${req.headers["authorization"] ? "wrong token" : "no token"}`);
    }
    return reply(401, { ok: false, error: "bad token" });
  }
  if (!seenFrom.has(req.socket.remoteAddress)) {
    seenFrom.add(req.socket.remoteAddress);
    console.log(`First request from ${req.socket.remoteAddress}: ${req.method} ${url.pathname}${url.search}`);
  }
  try {
    // the mod jar, for bots with autoUpdate on: behind the token like everything else
    if (req.method === "GET" && url.pathname === "/mod/info") {
      const m = mod();
      return reply(200, { ok: true, name: path.basename(m.file), size: m.size, sha256: m.sha256, built: new Date(m.mtime).toISOString() });
    }
    if (req.method === "GET" && url.pathname === "/mod") {
      const m = mod();
      res.writeHead(200, { "content-type": "application/java-archive", "content-length": m.size, "x-sha256": m.sha256 });
      return res.end(m.bytes);
    }
    const sv = server(url.searchParams.get("server") || req.headers["x-zymbot-server"]);
    const as = url.searchParams.get("as");
    if (req.method === "POST" && url.pathname === "/state") {
      const st = await readBody(req);
      if (!st.name || !st.pos) return reply(400, { ok: false, error: "state needs name and pos" });
      sv.members.set(st.name, { state: st, updated: Date.now() });
      return reply(200, { ok: true });
    }
    if (req.method === "GET" && url.pathname === "/inbox") {
      const after = Number(url.searchParams.get("after") || 0);
      const box = sv.inboxes.get(as) || [];
      const msgs = box.filter((m) => m.seq > after);
      return reply(200, { ok: true, messages: msgs, last: box.length ? box[box.length - 1].seq : after });
    }
    if (req.method === "POST" && url.pathname === "/send") return reply(200, send(sv, await readBody(req)));
    if (req.method === "GET" && url.pathname === "/nearby") {
      // what `as` can perceive: teammates and boards within range
      const me = member(sv, as);
      if (!online(me)) return reply(200, { ok: false, error: `${as} offline` });
      return reply(200, {
        ok: true, range: RANGE,
        members: inRangeOf(sv, me).map(({ m, d }) => summary(m.state, d)),
        boards: boardsFor(sv, me),
      });
    }
    if (req.method === "GET" && url.pathname === "/view") {
      // the leader's full picture, for Claude
      const me = member(sv, as);
      if (!me) return reply(200, { ok: false, error: `no bot ${as}` });
      const full = { ...me.state, online: online(me) };
      delete full.replies;
      return reply(200, {
        ok: true, range: RANGE, leader: full,
        teammates_in_range: online(me) ? inRangeOf(sv, me).map(({ m, d }) => summary(m.state, d)) : [],
        boards_in_range: online(me) ? boardsFor(sv, me) : [],
      });
    }
    if (req.method === "GET" && url.pathname === "/replies") {
      // replies to message ids, readable by the sender
      const ids = new Set((url.searchParams.get("ids") || "").split(",").filter(Boolean));
      const out = [];
      for (const m of sv.members.values()) for (const r of m.state.replies || []) if (ids.has(r.id)) out.push({ bot: m.state.name, ...r });
      return reply(200, { ok: true, replies: out });
    }
    if (req.method === "GET" && url.pathname === "/leaders") {
      const out = [];
      for (const m of sv.members.values()) if (m.state.role === "leader") out.push({ name: m.state.name, online: online(m), dim: m.state.dim, pos: m.state.pos, task: m.state.task });
      return reply(200, { ok: true, leaders: out });
    }
    if (req.method === "GET" && url.pathname === "/servers") return reply(200, { ok: true, servers: [...servers.keys()] });
    if (req.method === "POST" && url.pathname === "/board") return reply(200, boardOp(sv, await readBody(req)));
    return reply(404, { ok: false, error: "not found" });
  } catch (e) {
    return reply(400, { ok: false, error: String(e.message || e) });
  }
});

load();
setInterval(save, 10_000).unref();
process.on("SIGINT", () => { save(); process.exit(0); });
process.on("SIGTERM", () => { save(); process.exit(0); });
httpServer.listen(PORT, HOST, () => console.log(`ZymBot hub on http://${HOST}:${PORT} (range ${RANGE}, token ${TOKEN ? "on" : "off"})`));
