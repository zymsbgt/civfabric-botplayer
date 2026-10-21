#!/usr/bin/env node
// ZymBot MCP server: lets Claude act *as a leader bot* through the team hub.
//
// Claude sees exactly what the leader could know: its own full state, plus the teammates and
// job boards within radio range (same dimension, within the hub's range, default 150 blocks —
// the ZymLabs local chat radius). Commands go out under the leader's name and the hub refuses
// to deliver them to anyone out of range.
//
// No dependencies: speaks MCP (JSON-RPC 2.0, newline-delimited) over stdio, and HTTP to the hub.
//
// Env:
//   ZYMBOT_HUB_URL    hub address (default http://127.0.0.1:25599)
//   ZYMBOT_HUB_TOKEN  shared secret (same as the hub's)
//   ZYMBOT_SERVER     server key (default: the only/first server the hub knows)
//   ZYMBOT_LEADERS    comma-separated bot names Claude may act as (default: any bot with role "leader")

import readline from "node:readline";

const HUB = (process.env.ZYMBOT_HUB_URL || "http://127.0.0.1:25599").replace(/\/+$/, "");
const TOKEN = process.env.ZYMBOT_HUB_TOKEN || "";
const LEADERS = (process.env.ZYMBOT_LEADERS || "").split(",").map((s) => s.trim()).filter(Boolean);
let SERVER = process.env.ZYMBOT_SERVER || "";

async function hub(method, path, query = {}, body) {
  if (!SERVER && path !== "/servers") {
    const s = await hub("GET", "/servers");
    SERVER = (s.servers || [])[0] || "default";
  }
  const q = new URLSearchParams({ server: SERVER, ...query });
  const res = await fetch(`${HUB}${path}?${q}`, {
    method,
    headers: { "content-type": "application/json", ...(TOKEN ? { authorization: `Bearer ${TOKEN}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({ ok: false, error: `hub HTTP ${res.status}` }));
  if (res.status === 401) throw new Error("Hub rejected the token (set ZYMBOT_HUB_TOKEN).");
  return json;
}

async function leaderView(name) {
  const v = await hub("GET", "/view", { as: name });
  if (!v.ok) throw new Error(v.error || `No bot named ${name}. Use list_leaders.`);
  const allowed = LEADERS.length ? LEADERS.some((n) => n.toLowerCase() === v.leader.name.toLowerCase()) : v.leader.role === "leader";
  if (!allowed) throw new Error(`${v.leader.name} is not a leader (set its role to leader, or add it to ZYMBOT_LEADERS).`);
  if (!v.leader.online) throw new Error(`${v.leader.name} is offline.`);
  return v;
}

async function awaitReplies(ids, seconds) {
  const deadline = Date.now() + seconds * 1000;
  let got = [];
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 500));
    got = (await hub("GET", "/replies", { ids: ids.join(",") })).replies || [];
    if (got.length >= ids.length) break;
  }
  return got;
}

const HELP = `Commands (same as in-game "zb ..."):
  status | inv | milestones | team | help
  start | pause | stop (clears job) | role worker|leader
  get <item> [n]            obtain items by any means (gather/craft/smelt/hunt)
  give <player> <item> [n]  fetch if needed, walk over, toss them
  hunt [animal] [n] | follow [player] | come [player] | goto x y z | goto x z
  explore [dist] | farm [radius] | catch | home | sethome | sleep | skipnight | settle
  say <text>                speak in local chat (heard within 150 blocks)
  tell <bot|all> <command>  radio another bot (in range only)
  board | board create <name> | board chest | post <item> <n> [to <name>|chest] | cancel <post>
Append "loop" to repeat a job. Item ids: oak_log, minecraft:iron_ingot, cobblemon:poke_ball ...
Workers take shares of open board posts on their own once they have stone tools, food and a furnace.
Several workers can share one post; each delivers in batches, so a post's "delivered" is real progress.
A worker that can't make an ingredient posts a sub-post for it ("parent" = the post it's for) and waits at the board.`;

const tools = [
  {
    name: "list_leaders",
    description: "List the leader bots Claude can act as, with online status and position.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "leader_view",
    description: "Everything the leader knows: its full state (inventory, task, events, milestones), teammates within radio range, and job boards within range. Anything further away is invisible to it.",
    inputSchema: { type: "object", properties: { leader: { type: "string" } }, required: ["leader"] },
  },
  {
    name: "command",
    description: "Send a command as the leader. target = the leader itself, a teammate in radio range, or \"all\". Waits for replies.",
    inputSchema: {
      type: "object",
      properties: {
        leader: { type: "string" },
        target: { type: "string", description: "leader name, teammate name, or all" },
        text: { type: "string", description: "e.g. \"get oak_log 32\", \"follow Zym\", \"post iron_ingot 16 to chest\"" },
        wait_seconds: { type: "number", default: 6 },
      },
      required: ["leader", "target", "text"],
    },
  },
  {
    name: "board_post",
    description: "Post a resource request on a job board within range of the leader. Workers nearby claim shares of it (big requests are split between them) and deliver in batches.",
    inputSchema: {
      type: "object",
      properties: {
        leader: { type: "string" },
        item: { type: "string", description: "item id, e.g. minecraft:iron_ingot" },
        count: { type: "number" },
        deliver_to: { type: "string", description: "a player/bot name, or \"chest\" for the board's depot chest" },
        board: { type: "string", description: "board name or id (default: nearest)" },
        note: { type: "string" },
      },
      required: ["leader", "item", "count"],
    },
  },
  {
    name: "command_help",
    description: "The bot command language.",
    inputSchema: { type: "object", properties: {} },
  },
];

async function callTool(name, args) {
  switch (name) {
    case "list_leaders": {
      const r = await hub("GET", "/leaders");
      const ls = (r.leaders || []).filter((l) => !LEADERS.length || LEADERS.some((n) => n.toLowerCase() === l.name.toLowerCase()));
      if (!ls.length) return "No leader bots. Give a bot role \"leader\" (config roles, or command \"role leader\").";
      return JSON.stringify(ls, null, 2);
    }
    case "leader_view":
      return JSON.stringify(await leaderView(args.leader), null, 2);
    case "command": {
      const v = await leaderView(args.leader);
      const r = await hub("POST", "/send", {}, { from: v.leader.name, to: args.target, text: String(args.text), via: "claude" });
      if (!r.sent || !r.sent.length) return JSON.stringify({ delivered: false, dropped: r.dropped, error: r.error }, null, 2);
      const replies = await awaitReplies([r.id], Math.min(30, Number(args.wait_seconds ?? 6)));
      return JSON.stringify({ delivered_to: r.sent, dropped: r.dropped, replies }, null, 2);
    }
    case "board_post": {
      const v = await leaderView(args.leader);
      const boards = v.boards_in_range || [];
      const b = args.board ? boards.find((x) => x.name.toLowerCase() === String(args.board).toLowerCase() || x.id === args.board) : boards[0];
      if (!b) return `No job board within range of ${v.leader.name}. Create one: command leader "board create <name>".`;
      const r = await hub("POST", "/board", {}, {
        op: "post", as: v.leader.name, board: b.id, item: args.item, count: args.count,
        deliver_to: args.deliver_to || v.leader.name, note: args.note || "posted by Claude",
      });
      return JSON.stringify(r, null, 2);
    }
    case "command_help":
      return HELP;
    default:
      throw new Error(`Unknown tool ${name}`);
  }
}

// ---- JSON-RPC over stdio ----------------------------------------------------------------

function write(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

const rl = readline.createInterface({ input: process.stdin });
rl.on("line", async (line) => {
  if (!line.trim()) return;
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  const { id, method, params } = msg;
  if (id === undefined) return; // notification
  try {
    if (method === "initialize") {
      write({
        jsonrpc: "2.0", id, result: {
          protocolVersion: params?.protocolVersion || "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "zymbot", version: "0.2.0" },
          instructions: "You lead ZymBots in the ZymCivModded Minecraft pack through a leader bot. You only know what that leader knows and can only reach bots and job boards within 150 blocks of it. Start with list_leaders, then leader_view. The bots pool their /lunarforecast readings of coming nights (the command `forecast`); players' readings help too: ask nearby players in chat (`say`) what /lunarforecast told them, read their answers in the leader's chat_heard, and record each with `forecast note <player> <tonight|tomorrow|day> <moon|none> [confidence%] [rank]`. Players misremember, ranks included, so `forecast players` scores each player's past reports against what the nights turned out to be: decide with `forecast trust <player> full|show|ignore <reason>` whose readings count.",
        },
      });
    } else if (method === "tools/list") {
      write({ jsonrpc: "2.0", id, result: { tools } });
    } else if (method === "tools/call") {
      try {
        const text = await callTool(params.name, params.arguments || {});
        write({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text }] } });
      } catch (e) {
        write({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: String(e.message || e) }], isError: true } });
      }
    } else if (method === "ping") {
      write({ jsonrpc: "2.0", id, result: {} });
    } else {
      write({ jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
    }
  } catch (e) {
    write({ jsonrpc: "2.0", id, error: { code: -32603, message: String(e.message || e) } });
  }
});
