# ZymBot

Autonomous players for the **1.21.1 ZymCivModded** pack (Fabric, Cobblemon, CivFabric, ZymLabs Rules…).

Each bot is a normal Minecraft client running the full modpack plus this mod, so it sees every
modded block, item and recipe exactly like a player does. Plain protocol bots (Mineflayer etc.)
can't join this server: Fabric registry sync and Cobblemon require the modded client.

```
 machine A                        machine B                       any machine
┌──────────────────────┐        ┌──────────────────────┐        ┌────────────────────────┐
│ Prism: ZymCivModded  │        │ Prism: ZymCivModded  │        │ hub/zymbot-hub.mjs     │
│  + zymbot + baritone │◀─────▶ │  + zymbot + baritone │◀─────▶ │ state · radio · boards │
│  Bot1 (worker)       │  HTTP  │  Lead1 (leader)      │  HTTP  │ enforces 150-block     │
└──────────────────────┘        └──────────────────────┘        │ range                  │
          │  plays on the Minecraft server like anyone else     └───────────▲────────────┘
          ▼                                                                 │ HTTP
   195.201.199.249:25570                                       mcp/zymbot-mcp.mjs ◀── Claude
```

**Playing on the server:** [docs/survival-guide.md](docs/survival-guide.md) covers the pack's rules:
class gates and XP, no natural regeneration, pacifism, moons and sieges, and farming by biome. It
also has playbooks for the goals (settling a biome, Grandmaster, the Wither, the Ender Dragon) and
lists what the bots still get wrong.

## What a bot does

**Layers, highest first.** The brain picks one each tick (`brain/Brain.java`):

1. **Reflexes** (every role), most urgent first. Pacifism (pacifist-fabric) and finite health
   (natural regeneration off) shape them: see "Pacifism and health" below. Get out when running
   low on air underwater
   (swim up in open water, dig and pillar only under a ceiling), flee when hurt or when a
   creeper is about to blow, fight hostiles, swim to shore when left floating in deep water,
   eat (never in deep water unless starving), recover its CivFabric grave after dying (deaths
   are caught from the server's death packet, so instant respawn doesn't hide them), sleep in
   its claimed bed at night.
2. **Job** given by a teammate, the leader/Claude, or claimed from a **job board**.
3. **Progression** (workers only), a ladder in three phases (`brain/Progression.java`; the design
   is in [docs/class-progression.md](docs/class-progression.md)):
   - **Food:** crafting table → food stock (wild crops, animals, wild Pokémon) → wooden pickaxe →
     furnace → cooked meat.
   - **Farm:** the Grower gets a wooden hoe and seeds from wild crops, then lays out a field by
     water. Everyone else hands it their seeds. Then a storage chest at home, a bed and a
     campfire. Nothing past this point until the team's food is secure (a farm of 16+ plots).
   - **Classes:** each bot levels the classes of its role, Apprentice first, using the actions
     CivFabric's XP tables pay for, with gear milestones between the ranks.

   A milestone that fails is snoozed for 10 minutes, and the bot asks the nearest job board for
   that item.
4. **Idle**: throw Poké Balls at wild Pokémon, explore.

**Classes** (`civ/ClassSheet.java`, `civ/ClassBook.java`, `brain/ClassPlan.java`). CivFabric
specialisations are a player's top 2 or 3 XP classes, and once two classes pass 544 XP any
off-class XP drains them. So each bot:

- reads its XP and the server's slot count off the `/class` screen every 10 minutes;
- takes a team role: Grower (Farmer, Medic, Hunter), Smith, Delver or Scholar. The first bot on a
  world is the Grower. Roles are published on the hub so teammates take different ones;
- has the planner charge off-class XP as a cost (heavily once the drain is armed) and charge each
  craft its CivFabric food cost. Basics are free while classless.

**Chest index** (`memory/ChestIndex.java`). Every container a bot opens is recorded with its
contents and published on the hub. Before crafting, the planner looks for the item in a known
chest it may take from (its own, a team depot or Lootr) or on the ground nearby. Village chests
near villagers and chests by other people's builds are only looked at.

**Landmarks** (`memory/Landmarks.java`). Bots remember places worth coming back to, one per kind per
chunk, as they move:
- water with land beside it (farm sites);
- wild crop patches, ores, berry bushes and Cobblemon berry trees, apricorns, grapes, sugar cane and
  bees;
- villages and Lootr loot;
- anything named with `mark <name>`.

Gathering heads for a remembered patch before exploring blind, and the Grower uses remembered water
for its farm. They're published on the hub, so leaders (and Claude through MCP) see the terrain.

**Gathering** (`gatherMode`, default `auto`). Out in the wilderness (no player-built blocks within
about 50 blocks), logs, stone, ore, gravel and sand are mined with Baritone's own `#mine`, which
clears whole trees and seams in one go: 16 logs or 32 cobblestone in about 70 s in testing. Near
any build, including the bot's own base, it picks and breaks one natural block at a time, skipping
others' builds and reinforced blocks. If a build comes within 10 blocks mid-run, or `#mine` stops
making progress, it switches to that careful mode.

**Food from Pokémon and wool from shears.** Hunts include wild Pokémon whose Cobblemon drops are the
thing wanted (Pidgey chicken, Wooloo mutton, berries). A Pokémon that hits back is fled from. Wool
comes from shearing sheep, Mareep and Wooloo with CivFabric's wooden shears (two logs, no class),
and beds are made in whatever colour there's 3 wool of.

**Diet** (`inv/Diet.java`). Spice of Fabric remembers the last 11 foods eaten: each repeat
restores 0.7x the hunger and takes 1.3x as long to eat (read from the instance's
`spiceoffabric.hjson`). The bot picks the food worth most right now, keeps chewing for as long as
it really takes, and counts its stock by that real value: "enough food" means a value of 24
across at least 3 kinds, and it hunts for the meats it's short of.

**Pacifism and health** (`civ/Pacifism.java`, `civ/Health.java`).

- **Pacifism.** The bot asks `/pacifist status` on joining and follows the server's grudge alerts.
  While pacifism protects it, it leaves naturally spawned hostiles alone, unless one has just hurt
  it. It won't hit a mob whose kin it's at peace with: it walks away instead. Blood moons and
  villages at night suspend pacifism, and then it defends itself as usual.
- **Health.** If health doesn't come back (on ZymLabs, natural regeneration is off), the bot:
  - flees at 12 health;
  - rests near its bed at 10 or below, and radios teammates `heal <name>`;
  - eats healing food;
  - puts a campfire by its bed (the `warm_bed` milestone), because CivFabric bed rest only heals
    beside a campfire: +5 health a night.

  Bots holding stitches, a medkit or wound mush treat hurt teammates within 16 blocks. The
  `pacifist` command reports all of this.
- **Moon forecast** (`civ/Forecast.java`, `civ/MoonOdds.java`; CivFabric with Enhanced Celestials).
  Each bot reads `/lunarforecast` once a night, as soon as it can: after dusk the reading is
  already about tomorrow night. Readings go into a notebook the bot publishes on the hub, and bots
  in radio range merge each other's, so readings travel from bot to bot as they meet. The bot pools
  every reading that counts with CivFabric's own odds (each reader's rank, from the confidence the
  server printed). It then picks the `/guessforecast` that pays most on those odds, priced against its
  own reading, and changes it (at most twice) as new readings arrive before dusk. The XP goes to the
  class it's levelling next. A pooled blood moon chance of 25% or more makes it check its bed claim
  and go home to its bed late in the afternoon, so the blood moon reflexes start from there.
  - Players' readings join through a leader: Claude asks in chat, reads the answers in the leader's
    `chat_heard`, and records them with `forecast note`. A pasted server sentence is taken as it
    is. The bots' teammates count; anyone else is listed only until `forecast trust` says
    otherwise. Every report is checked (a moon that can't rise that night, a figure that fits no
    rank, a stated rank the figure contradicts, two versions of one night), and `forecast players`
    scores each player's reports against what the nights turned out to be, next to how often a
    reader of their rank would have been right.

**Water.** Baritone swims, but slowly and with no thought for air. Bots don't chase Pokémon,
animals or drops into deep water (water with water under it), except their own grave's items;
travel still swims across rivers and lakes, with the air and shore reflexes as the safety net.

**Getting any item.** `ObtainTask` + `craft/Planner.java` pick the cheapest route for an item:
gather it, hunt it, craft it (recursively, using the recipes the server synced, modded ones
included) or smelt it. It prefers blocks the bot has actually seen nearby, and it moves on to
the next route when one fails.

**Movement and breaking.** [Baritone](https://github.com/cabaletta/baritone) does the pathing
and block breaking. ZymBot sets Baritone up for a Civ server (`nav/Nav.java`):

- Baritone may only break natural terrain while pathing. Every other block id goes into
  `blocksToDisallowBreaking`.
- It only scaffolds with junk blocks (dirt, cobble…).
- No parkour.

Every other break goes through Baritone too (`control/Breaker.java` hands Baritone's builder the one
block): harvesting a crop, opening a grave, digging out when stuck and picking the bot's own
crafting table or furnace back up. For that one break the block's type is let off the no-break
list, and for crops Baritone keeps the hoe in hand instead of picking a tool.

In the default `gatherMode: "targeted"`, the bot picks each block itself and Baritone breaks just
that one. A block qualifies only if it is:

- natural,
- exposed,
- not within `builtAreaBuffer` blocks of someone else's build,
- not blacklisted,
- not mining-gated.

A block that takes far longer than it should (reinforced) is blacklisted. Blocks under gravel or
sand, or next to water or lava, are skipped: mining them is how bots end up trapped. If a bot
does get stuck underground or in water (Baritone going nowhere for 20 s), it climbs out by hand:
breaking natural blocks overhead and pillaring up on junk blocks. `"baritone-mine"` lets
Baritone's mine process choose veins itself: faster, but it doesn't keep the buffer around builds.

**CivFabric awareness** (`civ/CivRules.java`):

- Gates come straight from the civfabric jar (`mining.json`, `blocks.json`, `recipes.json`).
- Refusals from the server ("You need Journeyman Miner to mine") are pinned to the action that
  triggered them, remembered, and used to bound the bot's class ranks.
- Knocked out (DBNO): wait, then `/giveup` after `giveUpDownedSeconds`.
- Bed claims: the bot places and sleeps in its own bed.
- Graves: recovered after death.

**Farming** (`brain/tasks/FarmTask.java`, `farm/PlantRules.java`). `farm` works a field by hand:
it harvests ripe crops with a hoe held, re-tills the coarse dirt CivFabric leaves behind, and sows
each empty plot with the best crop it has seeds for. With Realistic Plant Growth in the pack it
follows that mod's rules, read from the instance's own `config/realisticplantgrowth/*.yml` (a
bundled copy otherwise):

- a crop that dies in the biome, or would have no open sky, isn't planted;
- the same crop isn't put back on a plot within `soil_rotation_days` (5). The bot remembers what
  it planted where, and if the server refuses a seed ("This ground is spent") it notes that too;
- with a Journeyman Farmer rank it harvests and replants in one click (seed in the off hand),
  falling back to breaking the crop if the server says no.

`crops` lists what grows where the bot stands and how its own seeds would do there.

**Manners.**

- Never attacks players, Pokémon, villagers or anything owned, named, leashed, a baby, or
  standing near fences (a pen).
- Only uses its own crafting tables and furnaces, and picks them back up afterwards.

## Teams, range and Claude

Bots talk through the **team hub**, a small server any number of machines connect to. It
mirrors ZymLabs local chat: **bots more than 150 blocks apart, or in different dimensions, cannot
communicate.** The hub enforces this from the positions bots report. Every radio message, every
"who's near me" query and every job-board action is checked. There's no relaying.

- **Roles**: `worker` plays on its own and takes jobs. `leader` does the same, and is also the
  bot Claude reaches the team through: Claude sees what it sees, commands bots in its range, and
  hears the help calls it relays. A job Claude gives it comes before its own goals.
- **Teammates**: humans listed in `teammates` can command bots in chat (heard within 150
  blocks, as the server limits chat): `zb Bot3 get oak_log 32`, `zb all come`,
  `zb follow` (every bot that hears it).
- **Job boards**: notice boards anchored in the world, optionally with a depot chest. Bots
  can only use a board within 150 blocks of it.
  - A post is a request: `post iron_ingot 16 to chest`.
  - Workers with basic gear claim a **share** of a post they can fulfil: the open amount split
    between the workers in range, at most a stack (`boardShare`). Several bots work on one post
    at once, and a bot that finishes its share can come back for more.
  - Bots deliver in **batches** (`boardBatch`, default 16) to the depot chest or by hand, and
    report each one, so `delivered` is real progress. If a bot dies, gets distracted or can't
    find more, what it delivered still counts, it hands over what it's carrying, and the rest
    of its share goes back to open.
  - **Sub-requests**: a bot that claims something it can only make with help (an ingredient
    behind a class gate, or a slow one when other workers are around) posts that ingredient as
    a sub-post on the same board, gets the rest itself, and waits at the board for it. A
    sub-post nobody takes within 4 minutes is cancelled and made by the bot itself if it can.
    Chains stop at 3 levels and never ask for anything already further up the chain.
  - A claim is dropped when its bot has been offline for 2 minutes, reports working on
    something else (e.g. after a restart), or sends no heartbeat for 20 minutes. Dropping a
    claim also cancels the sub-posts it asked for.
  - A bot blocked by a class gate posts a request instead of looping. That's how CivFabric
    specialisation turns into teamwork.
- **Claude** connects through `mcp/zymbot-mcp.mjs` *as a leader*. It sees only what that
  leader knows (its own state, plus teammates and boards within 150 blocks) and can command
  only those bots.

## Setup

Requirements: Prism Launcher with a dedicated **ZymBot Modded** instance (a copy of `1.21.1 ZymCivModded Server Minimal`:
same server mods, plus Realistic Plant Growth from the Full pack and its config, minus Simple Voice Chat (optional on the server, useless to a bot; it's in `mods-disabled`); none of the client-only extras except Sodium, Lithium and Dynamic FPS for performance;
your own play instances stay untouched. Take Sodium out if you ever run it under HeadlessMC), Java 21
(Prism's runtime works), Node 18+ for the hub and MCP server.

1. **Build and install the mod** (copies `zymbot-*.jar` *and* `baritone-api-fabric-1.11.2.jar` into the instance):

   ```bash
   ./gradlew installToPrism
   ```

   It installs into the `ZymBot Modded` instance (`prism_instance` in `gradle.properties`); another one with
   `-Pprism_instance="<name>"`.
   On another machine, copy `build/libs/zymbot-0.1.0.jar` and `libs/baritone-api-fabric-1.11.2.jar` into its mods folder.

2. **Run the hub** on one machine everyone can reach:

   ```bash
   ZYMBOT_HUB_TOKEN=choose-a-secret node hub/zymbot-hub.mjs
   ```

   Port 25599 by default (`ZYMBOT_HUB_PORT`). Boards persist to `hub/hub-data.json`. For
   same-machine-only use, `ZYMBOT_HUB_HOST=127.0.0.1` works without a token.

3. **Configure each client** in `<instance>/minecraft/config/zymbot.json` (written with defaults on first launch):

   ```json
   {
     "autoStart": true,
     "role": "worker",
     "roles": { "Lead1": "leader" },
     "teammates": ["ZymSB"],
     "hubUrl": "http://192.168.1.20:25599",
     "hubToken": "choose-a-secret"
   }
   ```

   Other useful keys: `gatherMode`, `builtAreaBuffer`, `classRanks` (e.g. `{"miner": 2}` if the
   bot already has ranks), `catchPokemon`, `giveUpDownedSeconds`, `replyInChat`,
   `takeBoardJobs`, `postRequests`, `boardBatch`, `boardShare`, `plantRules` (`auto`: follow
Realistic Plant Growth when the mod is installed; `on`/`off` to force). `taskGamma` (default 8) brightens the screen
while the bot is running so you can watch it at night or underground; pausing restores your own
gamma, and `0` turns this off. `/zymbot reload` re-reads it.

4. **Launch bots.** Each needs its own account and Minecraft client. Prism can start one straight
   onto the server:

   ```bash
   /Applications/Prism\ Launcher.app/Contents/MacOS/prismlauncher --launch "ZymBot Modded" --server 195.201.199.249:25570 --profile Bot1
   ```

   Press **K** in game to start/pause (or `autoStart`). The HUD in the top-left shows the
   current task chain and recent events. Bots reconnect 30 s after being disconnected.

5. **Hook up Claude** (optional). `.mcp.json` registers the MCP server for Claude Code sessions
   in this folder. Export the token first:

   ```bash
   export ZYMBOT_HUB_TOKEN=choose-a-secret
   ```

   Tools: `list_leaders`, `leader_view`, `command` (as the leader, to itself / a teammate in
   range / `all`), `board_post`, `command_help`. Set `ZYMBOT_HUB_URL` if the hub isn't local and
   `ZYMBOT_LEADERS` to restrict which bots Claude may act as.

## Commands

The same language works in chat (`zb …`), in `/zymbot …`, over the radio and through Claude:

| | |
|---|---|
| `status` · `inv` · `milestones` · `team` · `help` | reports |
| `start` · `pause` · `stop` · `role worker\|leader` | control |
| `get <item> [n]` | obtain by any means (gather/craft/smelt/hunt) |
| `give <player> <item> [n]` | fetch if needed, walk over, toss |
| `hunt [animal] [n]` · `follow [player]` · `come [player]` | |
| `goto x y z` · `goto x z` · `explore [dist]` · `home` · `sethome` | |
| `farm [radius] [crop] [minutes]` | harvest, re-till and sow the field around you (radius ≤ 32, default 10 min): only point it at your own fields |
| `crops` | what grows here (biome rules) and which of the bot's seeds would |
| `catch` · `sleep` · `skipnight` · `settle` · `campfire` (warm the bed) | |
| `heal <player>` · `pacifist` | treat a teammate; report pacifism, regeneration and bed warmth |
| `forecast` · `forecast readings [night]` · `forecast players [name]` | pooled moon odds and the guess; the readings behind them; players' records |
| `forecast note <player> <tonight\|tomorrow\|day> <moon\|none> [confidence%] [rank]` · `forecast trust <player> full\|show\|ignore <reason>` | leaders: record a player's reading; decide whose readings count |
| `classes` · `classes <a> <b> [c]` · `classes role <grower\|smith\|delver\|scholar>` · `classes refresh` | show or set the bot's classes |
| `level [class]` | one round of earning XP in a class (its best-paying action) |
| `phase` · `chests` | food/farm/level phase and farm size; the chest index |
| `landmarks [kind]` · `mark <name>` · `goto <mark or kind>` | remembered places (`goto water`, `goto ore:iron`) |
| `break x y z` | break one block with Baritone |
| `teamhome` · `teamhome set <name> [x y z] [purpose]` · `teamhome assign <name>` · `teamhome remove <name>` | the team's bases. Only a leader (or Claude) may change them |
| `say <text>` | speak in local chat |
| `tell <bot\|all> <command>` | radio a bot in range |
| `board` · `board create <name>` · `board chest` · `board remove` | job boards (stand by the depot chest for `create`/`chest`) |
| `post <item> <n> [to <name>\|chest] [on <board>]` · `cancel <post>` | requests |

Append `loop` to repeat a job (`get oak_log 64 loop`).

## Development

```bash
./gradlew build                            # build/libs/zymbot-0.1.0.jar
./gradlew runClient -PdevWorld=zymbot-test # dev client straight into a survival test world
```

Use a world with cheats (`zymbot-farm`; the older `zymbot-test` has none), so test scripts can
`/give`, `/tp` and `/setblock`. `-PplantGrowth` adds Realistic Plant Growth (from the ZymBot
Modded instance) to the dev client.

To test with the whole pack (CivFabric, the ZymLabs mob rules), open a single-player world in
ZymBot Modded offline under a bot name:

```bash
/Applications/Prism\ Launcher.app/Contents/MacOS/prismlauncher --launch "ZymBot Modded" --world zymbot-farm --offline Bot1
```

The bot clicks through the "experimental settings" backup warning itself. Single-player has no `/save-all`, so
quit to the title screen (not kill the game) when the world should keep a test's changes. With the
pack, Pokémon and animals trample bare farmland: clear them (`/kill @e[type=!player,type=!item,distance=..24]`)
before laying out a test field.

The dev client is vanilla + Fabric API + Baritone (no modpack), configured by
`run/config/zymbot.json`. Memory lives in `<game dir>/zymbot/memory/<server>/<uuid>.json`,
the same place and format (schema 3, compatible with the old schema 2 files) as before.

Code map: `brain/` (Brain, Progression, Commands, tasks/), `craft/` (Planner, Recipes, Sources),
`nav/` (Baritone), `control/` (look, keys, placing, station breaking), `inv/`, `world/`, `civ/`,
`team/` (hub client), `memory/`, `chat/`, `hud/`. Hub: `hub/zymbot-hub.mjs`. MCP: `mcp/zymbot-mcp.mjs`.

## Known gaps

- A full inventory with no junk left makes the bot throw away a spare copy of a tool (it keeps
  one of each) every 30 s; it sometimes walks over it and picks it back up.
- Doesn't build houses, open doors, use boats, or fight Cobblemon battles (it catches by throwing
  balls). New farms are laid out only beside existing water.
- Logs in someone's log cabin are still "natural"; the build buffer usually catches them, but not always.
- Off CivFabric (or before `/class` has been read), class ranks are inferred from refusals, so the
  first attempt at a gated action fails once.
