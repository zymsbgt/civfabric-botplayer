# Class-aware progression

How ZymBot plays a fresh CivFabric world: **food first, then farms, then classes.** Everything here
is read from the pack itself (the CivFabric, Cobblemon and crop-mod jars on the client), so it
follows whatever version a server runs.

## How CivFabric classes actually work

- **There is no "pick a class" command.** A player's specialisations are simply their N highest-XP
  classes. N is the server's slot count: 3 on the current test world, 2 on some variants.
- **The drain.** Once two classes each hold 544 XP the drain arms. From then on, each XP earned
  *outside* the specialisations costs 0.525 from *each* specialisation, so off-class work
  actively destroys progress. Before it arms, any XP is harmless.
- **Ranks** come from XP alone: 640 / 2,221 / 5,962 / 14,852 / 36,209.
- **Forbidden trio:** Farmer, Miner and Blacksmith can't all be specialisations together.
- **Crafting costs food:** `max(1, base × (5 − rank/1.5))` per craft, from `craft_hunger.json`
  (planks 0.25, signs and wool 0.5, bread 0.5, a wooden pickaxe ≈ 0.7). A craft that would leave
  the bar under 4 is refused. **While classless** (no class at Apprentice yet) torches, sticks,
  planks, crafting tables, furnaces, chests and barrels are free: stock up on them before the
  first rank.
- **What trains what** comes from `civfabric/xp/*.json`. For example, breaking a crop or cooking
  meat trains Farmer, breaking ore trains Miner, placing blocks trains Builder, crafting paper and
  signs trains Librarian, and crafting beds trains Farmer. Kills train Hunter.

## Reading the bot's own classes

The bot runs `/class` (every 10 minutes, and after a rank change) and reads the screen:

- Each class card is named after its class, with `XP: 1,234` in its lore.
- The specialisation row shows the top N cards once a player has any XP.
- Once the drain arms, an off-class card says *"costs 0.525 from each specialisation - 1.575 in
  all"*. All ÷ each gives the slot count.

`classSlots` in the config overrides the slot count (0 means auto). Until the server shows it, the
bot assumes 3.

## Who takes which classes

Each bot takes one **role**, a set of specialisations chosen so that the team covers everything
and nobody holds the forbidden trio. Roles are claimed in this order, so the first bot on a new
world is always the Grower, which makes it the farmer:

| Role | 3 slots | 2 slots | Job |
|---|---|---|---|
| Grower | Farmer, Medic, Hunter | Farmer, Medic | Farms, cooking, healing |
| Smith | Blacksmith, Builder, Librarian | Blacksmith, Builder | Tools, armour, walls |
| Delver | Miner, Hunter, Builder | Miner, Hunter | Ore, fighting |
| Scholar | Librarian, Medic, Farmer | Librarian, Medic | Enchanting, forecasts, backup healer |

A bot claims the first role no teammate in radio range has claimed (it publishes its role on the
hub), and keeps it: the choice is saved, because XP already earned can't be moved. `classes
<a> <b> [c]` sets one by hand, and the `classes` config entry pins it.

## Phases

### 1. Food first (every bot)

Until it has a food stock (24 worth after Spice of Fabric repeats, in 3 or more kinds), a bot
forages. In rough order of cost:

- **Wild crops,** which also drop seeds:

  | Mod | Wild crops |
  |---|---|
  | Farmer's Delight | cabbages, carrots, onions, potatoes, rice, tomatoes, beetroots |
  | Farm & Charm | barley, corn, emmer (wheat), lettuce, oat, potatoes, strawberries, tomatoes, onions, carrots, beetroots |
  | Ube's Delight | garlic, ginger, lemongrass, ube |

  Breaking them needs no hoe, they have no Farmer failure roll, and they always drop.
- **Fruit:** Vinery apple leaves (apples), oak and dark oak leaves, sweet berries, Cobblemon berry
  trees, and melons and pumpkins.
- **Animals and wild Pokémon:**
  - Pidgey and the other birds drop chicken.
  - Wooloo and Mareep drop mutton.
  - Lechonk and Swinub drop porkchop.
  - Tauros and Miltank drop beef.
  - Magikarp and Seel drop fish.
  - Most species drop berries.

  There are no pigs on pacifist servers. A Pokémon that fights back is left alone, and none are
  hunted below 12 health.
- **Cooking:** meat in a furnace or smoker. It's free fuel-wise with planks or logs, and it also
  trains Farmer.

### 2. Farms (the Grower first)

The Grower collects seeds from wild crops (at least 2 kinds). It then makes a wooden hoe; stone
hoes need Blacksmith 1, and harvesting without a hoe always fails. It lays out a field:

- next to existing water, because buckets need Blacksmith 3;
- under open sky;
- with crops that Realistic Plant Growth allows in that biome (the `crops` command).

Other bots hand over spare seeds and help till. **The team's food is secure** once a farm of 16 or
more planted plots with 2 or more crops exists and the bot's own stock is full.

### 3. Levelling (each bot, in its role's classes)

Each bot works on the class in its role with the lowest rank, using the actions that pay that class
and that its ranks allow:

- **Farmer:** work the farm, cook meat, pick berries.
- **Miner:** coal and Cobblemon stone ores; copper at Apprentice, iron at Expert.
- **Builder:** place blocks (walls around the farm and base).
- **Librarian:** signs, paper, books.
- **Medic:** paper, then wound mush and stitches.
- **Blacksmith:** smelt ore the Delver supplies.
- **Hunter:** hunt.

Once the drain arms, the planner charges off-class XP as a real cost. So a Smith asks the board for
cobblestone rather than mining it, and a Grower doesn't dig for ore.

## Look before you craft: the chest index

Crafting costs food, so before crafting, a bot looks for the item:

1. its inventory;
2. items lying on the ground nearby;
3. **chests it knows the contents of.**

Each bot keeps a **chest index** in its memory: every container it opens, with what was inside and
when it saw it. It's per player, persists across restarts, and is published on the hub so leaders
and teammates can see what each bot has stored where.

The planner treats "fetch from a known chest" as one more way to get an item, priced by distance.
That usually beats crafting. It only takes from chests that are safe to take from:

- its own chests;
- team depot chests;
- Lootr chests, which are per-player loot.

It never takes from a village chest within 16 blocks of a villager, which would turn the village
hostile, or from chests near someone else's build.

## Team bases

The team lives around one or more **bases**, and each bot belongs to one. Bots stay within radio
range of their base:
- exploring turns back 120 blocks out;
- waypoint trips are capped at 130 blocks;
- a bot more than 100 blocks away walks back first;
- beds go down there.

- **Only a leader decides** where bases are, or Claude through a leader:
  - `teamhome set <name> [x y z] [purpose]` adds or moves one (`teamhome set plains 400 70 -120
    wheat and potatoes`);
  - `teamhome assign <name>` moves a bot to it;
  - `teamhome remove <name>` deletes one.

  Several bases let the team grow different crops in the biomes they need: Realistic Plant Growth
  only grows a crop in its own biomes. A Grower assigned to a base lays its field there.
- **The one automatic base:** on a brand-new team, the first Grower's farm becomes `main`, and
  teammates with no base yet join it when they're in radio range of the Grower. After that, nothing
  moves a base but a leader.
