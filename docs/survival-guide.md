# ZymCivModded survival guide

How to stay alive and get things done on the ZymCivModded server, written for the ZymBot leaders
(and Claude driving them). Read it before taking on a goal: the pack changes enough of vanilla that
ordinary Minecraft instincts get bots killed or stuck.

**Where this comes from.** These are the pack's defaults, read on 2026-10-20 from the `ZymBot Modded`
instance:

- the mod jars and configs: civfabric 0.4.4, zymlabs-rules 0.1.0, realisticplantgrowth 0.1.3,
  pacifist-fabric, Spice of Fabric, Enhanced Celestials;
- the values the integrated server logged at start-up;
- tests with Bot1 in a single-player copy of the pack.

The real server can override any of these in its own config. Things marked ✔ were seen in game.
Things marked **?** are inferred from code and need confirming on the server.

## The rules that matter most

1. **Health doesn't come back by itself.** Difficulty is Hard and natural regeneration is off ✔.
   Food fills hunger but never heals. Health only returns from:
   - a night's rest in your claimed bed, but only with a campfire within 4 blocks of it:
     "This bed feels warm and cozy" ✔. That gives +5 health per night ✔. A bed without a campfire
     "feels a bit cold" and heals nothing;
   - a Medic right-clicking you with stitches;
   - healing items: stitches, wound mush, a medkit, a first aid kit, hearty soup, golden apples;
   - potions (brewing needs Medic 4);
   - Hunter kills (a little health back per kill from Journeyman up);
   - a beacon (placing one needs Builder 5).

   Treat every half-heart as spent money.
2. **Everything useful is gated by class rank, even for a brand-new player** ✔. Gates apply while
   you're still classless ("You need Blacksmith Apprentice to craft Stone Pickaxe"). Wooden tools
   are free; stone tools need Blacksmith 1. Specialise, or trade.
3. **Death costs 50% of your class XP** ✔ ("Death cost you 50% of your class XP"). It's only 20% on a
   blood moon and 5% on a super blood moon. Being downed first is not death:
   - you have 60 s to be revived (stitches, medkit or first aid kit);
   - `/giveup` respawns you, but not if another player put you down;
   - your items wait in a CivFabric grave where you fell ✔.
4. **Players start as pacifists** ✔. Naturally spawned hostiles neither target nor hurt you; the
   server blocks their damage ✔.
   - **Grudges.** Hitting a hostile turns its kin against you for 20 s, and killing one for 30
     minutes. Zombies, husks, drowned and zombie villagers count as one kin; so do skeletons, strays
     and bogged. Your own Pokémon's hits count as yours. Dying clears every grudge.
   - **War.** `/pacifist disable` declares war on everything, for at least 5 minutes. It pays ×2.5
     XP.
   - **Always hostile, whatever your status:** blazes, wither skeletons, guardians and elder
     guardians, the Warden, illagers (except wandering patrols), ravagers and piglin brutes.
   - **Not covered:** mobs that weren't naturally spawned can still hurt you. That means spawners
     and trial spawners, jockeys, skeleton traps, raids, `/summon` and eggs. Keep out of a
     spawner's 16-block range: it only spawns while a player is that close.
   - **Pokémon are never covered.** Species that defend themselves attack whoever hurt them.
   - **Pacifism is suspended during blood moons, and in villages at night** (monsters except
     creepers). The server says "You're entering a village. Monsters & Pillagers may attack you."
   - **Stormy nights:** drowned ignore pacifism. See [Mobs, nights and the moon](#mobs-nights-and-the-moon).
   - **No pigs or hoglins spawn.** Pork comes from the butcher.
5. **Spice of Fabric remembers your last 11 meals** ✔. Each repeat of a food restores 0.7× the hunger
   and takes 1.3× as long to eat. Bread eaten 11 times takes about 28 s and restores almost
   nothing. Carry 4 or more different foods.
6. **Hunger is spent on more than walking.**
   - **Moving:** per 10 s, sprinting costs 0.75 and swimming 3.0, against 0.2 for walking. In play,
     with vanilla's own exhaustion on top, sprinting drains about **10× walking**, so bots only
     sprint to flee.
   - **Crafting:** you can't craft while hungry (floor of 4). Boats cost 3.2 hunger, buckets and
     shears 2.0.
   - **Using stations:** healing, blessing and anvil work also refuse you when hungry.
7. **Crops only grow in their biomes,** and only under open sky. The same crop can't go back on the
   same soil for 5 days (Realistic Plant Growth). See [Farming](#farming-and-biomes).
8. **Chat, `/tpa` and the team hub reach 150 blocks.** `/tpa` only works when nobody else is within
   200 blocks of you; it's for getting back from far away.
9. **There are no boats** for most players: crafting one needs Builder 5 *and* Librarian 5. Elytra
   can't be crafted at all. Crossing water means swimming, which costs a lot of hunger and runs into
   drowned.
10. **Don't steal from villages.** Taking from a village chest within 16 blocks of a villager turns the
    village on you. Lootr chests can't be broken. Iron golems spare you if you hand them an iron
    ingot.
11. **Crafting tables keep their grid** (Visual Workbench). Whatever is left in the 3×3 grid stays
    on the table when you close it, and drops when the table is broken. Take your ingredients back
    before walking away.

## Classes

There are seven classes: **Farmer, Builder, Miner, Medic, Librarian, Blacksmith and Hunter**. You can
specialise in **3** of them.

### Ranks and XP

| Rank | | XP needed (total) |
|---|---|---|
| Novice | 0 | 0 |
| Apprentice | 1 | 640 |
| Journeyman | 2 | 2,221 |
| Expert | 3 | 5,962 |
| Master | 4 | 14,852 |
| Grandmaster | 5 | 36,209 |

The formula is `2·(25n² + 5n + 200·2.45ⁿ) − 400`, so each rank needs about 2.5 times the XP of the one
before.

- **Drain.** Once any class reaches 544 XP (85% of Apprentice) the drain arms. From then on your 3
  strongest classes are your specialisations, and the other classes work against them. The server
  logs this as "drain 1.0 shared out / 0.525 each in". **?** Exactly which way the XP flows needs
  confirming in game.
- **Forbidden trio.** You can't specialise in Farmer, Miner *and* Blacksmith together.
- **Skills** (felling, digging, fishing…) are tracked separately. They never drain and aren't lost on
  death.

### Where XP comes from

| Class | Best sources (XP each) |
|---|---|
| Farmer | Harvesting a crop: 7 (any planted crop, modded ones included). Cooking meat: 5–7, plus 2–4 in a smoker. Breeding: 6–10. Placing farmland: 1. |
| Miner | Ancient debris: 100. Diamond ore: 30–35. Gold, emerald and redstone ore: 18–21. Amethyst: 20. ×2.5 with pacifism off. |
| Blacksmith | Smelting or blasting: gold 8, iron 6, copper 3. Crafting: diamond chestplate 80, iron chestplate 40, master ball 200. Repairs: 1. |
| Builder | Placing building blocks: 1–2 (diamond block 10). Crafting: cauldron 21, iron doors and bars 18, hopper 15. |
| Librarian | Crafting: map 18, writable book 12, compass 12. Villager trades. Lunar forecast guesses: up to 160, ×5 with a scroll. |
| Medic | Crafting: hearty soup 50, first aid kit 40, medkit 20. Treating players with stitches: 15 + 8.75 per rank. Golden apples: 10. |
| Hunter | Killing wild Pokémon: 2 + 0.25 per level. Killing mobs and animals. Lunar forecast guesses. Cobblemon captures and battles pay **nothing**. |

**XP multipliers:**
- Blood moons: up to ×16 on a super blood moon.
- Being under attack: ×2.5 for 45 s.
- Pacifism off: ×2.5.
- Rested: ×1.1, with Farm & Charm.
- Fancy meals: up to ×1.33 for meals cooked by a Farmer of rank 3 or more.

Grandmaster is a long road. Farmer, for example, takes about 5,200 crop harvests before multipliers.
Saving big XP actions for blood moons, and not dying, matter more than anything else.

### What each rank unlocks (the parts goals depend on)

| Need | Gate |
|---|---|
| Stone pickaxe, stone sword | Blacksmith 1 ✔ |
| Iron pickaxe, iron sword, iron axe | Blacksmith 2 |
| Iron armour, shield, bucket, flint and steel, crossbow, diamond pickaxe, diamond sword | Blacksmith 3 |
| Handling lava | Blacksmith 3 |
| Diamond armour | Blacksmith 4 |
| Netherite tools | Blacksmith 4 |
| Netherite armour | Blacksmith 5 |
| Netherite template | Librarian 5 |
| Mining iron or lapis ore | Miner 2 |
| Mining gold, emerald or redstone ore | Miner 3 |
| Mining diamond ore or ancient debris | Miner 4 |
| Using an anvil | Blacksmith 3 |
| Using a smithing table | Blacksmith 1 |
| Enchanting table | Librarian 3 to craft, Librarian 4 to use |
| Brewing stand | Medic 4 to craft and to use |
| Golden apple | Medic 3 |
| Golden carrot | Farmer 4 |
| Cobblemon healing machine | Medic 1 nearby |
| TNT | Miner 3 |
| Scaffolding | Builder 3 |
| Placing a beacon | Builder 5 |
| Dispenser, dropper | Builder 3 |
| Poké / Great / Ultra Ball | Blacksmith 1 / 2 / 3 |
| Boats | Builder 5 *and* Librarian 5 |
| Elytra | Blocked for everyone |

Torches, sticks, crafting tables, furnaces, chests, barrels, planks and boats need no class to
craft. Classless players craft those without the hunger cost.

### Class abilities

| Class | Abilities |
|---|---|
| Farmer | From Journeyman, a hoe-click harvests and replants in one go, seed taken from the off hand ✔. Blessing food. |
| Miner | Haste I–VI by rank. Discounted death loss **?**. |
| Builder | Haste I–IV by rank. Invisible lights from Expert. |
| Hunter | Speed. More damage to mobs (up to +100%) and less taken (×0.5 at Grandmaster). Up to double animal drops. Sees wounded mobs from Apprentice. Carrying or leashing downed players from Journeyman. |
| Medic | Heals and revives better with rank. |
| Librarian | Speed I–III. Class XP books. |
| Blessing | Gear can be blessed from Journeyman and bottles from Expert, drawing on an enchantment pool unlocked by rank. Mending, Soul Speed and Swift Sneak only at Grandmaster. |

## Mobs, nights and the moon

- **Spawns.** Skeletons only spawn in cold biomes (strays in the cold), and silverfish on bare rock.
  Spiders come in a smaller variant.
- **Endermen hunt skybases.** That means anyone above y 79 with 11 or more blocks of air below,
  within 64 blocks of an enderman.
- **Phantoms** come after insomnia: about 7 days without sleep.
- **Lightning.** Out in the rain with 8 or more blocks of air below you (flying, or on a pillar or
  bridge), lightning can strike you for 5 to 100 damage, through armour.
- **Stormy nights.** A thunderstorm between 13000 and 23000 is a "Stormy Night of the Drowned"
  (the server announces "The Stormy Night of the Drowned begins..."). Drowned ignore pacifism, and
  every drowned within 96 blocks of a town is walked toward it. A thundering day is not one, and
  neither is plain rain. Drowned spawn in rivers and oceans, so a base by a river is the worst place
  to be: put the farm by a pond or lake inland.
- **Moons** (Enhanced Celestials, pack defaults):

| Moon | Chance a night | Effect |
|---|---|---|
| Blood moon | 10%, 4+ nights apart | ×2.25 monsters, all on the surface; nothing is at peace; **CivFabric siege** (drowned only); big class XP bonus; death costs 20% XP |
| Super blood moon | 5% on full moons, 20+ nights apart | ×4.5 monsters; siege by every hostile; zombies, skeletons, spiders and creepers get ×1.75 speed and ×2 health; class XP up to ×16; death costs 5% XP |
| Harvest moon | 10% | ×2 crop drops; +12% CivFabric harvest chance |
| Super harvest moon | 5% (full moons) | ×4 crop drops, +100% harvest chance |
| Blue moon | 10% | Luck; enchanting and anvils at half cost; beacons ×1.5 range; vanilla XP ×1.2 |
| Super blue moon | 5% (full moons) | Luck V; enchanting and anvils at a quarter cost; beacons ×2; vanilla XP ×2 |

- **Sleeping is allowed on blood moons** (the server's datapack overrides Enhanced Celestials).
- **Rain calls a moon off** while it lasts: pacifism is back under a rained-out blood moon. When the
  sky clears the moon returns, and CivFabric announces "During a Blood Moon:" with its XP rates.
  The same notice greets anyone who joins under a moon.
- **Sieges.** On a blood moon, mobs within 48 blocks march on towns, and 25% of them are armed. On an
  ordinary blood moon only drowned march; on a super one, every hostile does. Zombies dig through
  walls up to 12 blocks thick at a third of a player's speed. A town is 3 or more claimed beds within
  64 blocks.
- **Forecasts.** `/lunarforecast` reads tonight's moon, and `/guesslunarforecast` names a coming night
  for Librarian or Hunter XP: 160 base, ×5 with a Lunar Forecast Scroll. Scrolls come from village
  libraries and cartographers, shipwrecks and galleons, or wandering traders (8 emeralds).

## Combat, death and other people

- **PvP.** Player blows are capped at 7 (melee) or 10 (ranged). Fighting tags you for 30 s, and
  logging out while tagged leaves a mannequin that can be killed for 15 s.
- **Bounties and infamy** track who kills whom, and a moderator can clear a bounty.
- **Downed.** You bleed out after 60 s. Anyone can revive you with a first aid kit; stitches and
  medkits revive better with Medic rank.
- **Graves.** Your things stay where you died. If there's no room for a grave, they scatter on the
  ground. A grave can be placed in lava: don't go back into fire or lava for one.
- **Beds.** Sleeping claims a bed, and resting heals. You may not rest if nobody keeps watch, and you
  can't rest while too hungry. Breaking someone's bed is announced.
- **Reinforcement** has 3 tiers, and a reinforced block takes far longer to break; the bots already
  treat that as "not ours". Liquids can't be placed while in combat.
- **Vaults** (ZymLabs): a village vault keeper holds 9 slots per player. If the keeper dies you lose
  access until the village gets a new one, but never the contents.
- **Exile** is offered on the summit at −88, 0, above y 240.

## Farming and biomes

Realistic Plant Growth (details in `farm/PlantRules.java`, and the bot's `crops` command):

- Each crop has biome groups with a growth rate and a death chance. Outside them it dies, and it
  dies without open sky.
- The same crop can't go back on the same soil within 5 days, so rotate crops.
- Bone meal respects the biome, at most 3 per crop.
- In plains, wheat grows but carrots, beetroot and potatoes all die ✔. In forest, carrots grow ✔.
  Modded crops (Farmer's Delight, Farm & Charm, Vinery) follow the same rules.

CivFabric farming:

- **Hoes.** Harvesting a crop needs a hoe, or yields suffer. The chance of getting crops back is
  40% + 10% per Farmer rank + 6% per hoe tier; wheat and beetroot start at 58%.
- **Coarse dirt.** Breaking a crop leaves coarse dirt, which has to be hoed back. From Journeyman,
  the replant click keeps the farmland.
- **Trampling.** Pokémon and animals trample bare farmland within seconds ✔. Fence fields and
  plant them at once.
- **Villages sell their biome's produce** (wheat, carrots, grapes, corn, rice…), so a village is a way
  to get seeds you can't grow where you live.

## Goal playbooks

### Settle in a new biome to grow more crops

1. **Choose the biome from the crops you want.** Each crop's biome groups are in the plant growth
   config; the `crops` command shows what grows where a bot stands.
2. **Get there over land.** There are no boats, so swim only short gaps, and carry food for swimming
   hunger. Travel by day, in clear weather, and not on a blood moon.
3. **Make it a town:** 3 or more claimed beds within 64 blocks. That gives `/tpa` reach and a base,
   but it also makes the place a siege target, and on stormy nights drowned within 96 blocks are
   walked toward it. Settle away from rivers and the sea. So, before the first blood moon:
   - build walls thick enough to slow diggers, or keep the beds well inside;
   - keep a lit, enclosed perimeter;
   - keep everyone home on blood moons.
4. **Farm properly.** Use fenced, watered fields under open sky, rotate crops between plots, and
   keep a hoe in hand. Trade with local villages for seeds first, and don't steal.
5. **Keep the bed claim.** Rest heals there, and the town needs the beds.

### Grandmaster in a class

- Pick a class that fits the team, without breaking the Farmer, Miner and Blacksmith trio rule. Keep
  the other two specialisations ranked too: the drain punishes doing off-class work.
- Grind the best source from the XP table above. Line up the big actions for blood moons, and do
  forecast guesses every night if you're a Librarian or Hunter.
- Don't die. One death at Master costs about 7,400 XP. Downed players should wait for a revive
  rather than `/giveup`.

### The Wither

- **Nether access.** A portal needs obsidian, which means a diamond pickaxe (Blacksmith 3 to craft,
  Miner 4 for the diamonds) or casting the frame with lava buckets (Blacksmith 3 for the bucket and
  the lava). Lighting it needs flint and steel (Blacksmith 3) or a fire charge. Ruined portals can
  save the obsidian. Piglins won't barter obsidian or pearls.
- **Materials.** 3 wither skeleton skulls (about 2.5% a kill; Looting comes from a Master blessing)
  and 4 soul sand. Wither skeletons and blazes are always hostile, so pacifism doesn't help in a
  fortress.
- **The fight.** Gear: Smite (an Expert blessing), iron or diamond armour (Blacksmith 3 or 4),
  healing potions (Medic 4) and golden apples (Medic 3). Summon it underground or in the Nether,
  away from towns, and bring a Medic for revives.

### The Ender Dragon

- **Eyes of ender (12 or more).** Ender pearls only come from killing endermen, since piglins don't
  barter them. Blaze powder comes from blazes in fortresses, which are spawner mobs.
- **Bows and arrows** aren't gated. Use slow falling and healing potions (Medic 4) and golden
  apples (Medic 3).
- **Risk.** Being downed over the void is death. A wipe at Master costs a team tens of thousands of
  XP, so go on a blood moon only if you've accepted that.
- **No elytra** to win at the end: elytra are blocked.

## Team shape

Every player can hold 3 specialisations, and no one can take all of Farmer, Miner and Blacksmith. A
team of four bots covers everything:

| Bot | Specialisations | Role |
|---|---|---|
| Smith | Blacksmith, Builder, Librarian | Tools, armour, buckets, portals, boats, enchanting. |
| Delver | Miner, Hunter, Builder | Ores and diamonds, fighting, walls. |
| Grower | Farmer, Medic, Hunter | Food, healing, revives, golden apples and carrots, potions. |
| Scholar | Librarian, Medic, Farmer | Enchanting, forecasts, XP books, back-up healer. |

Gated items move between bots on the job boards. That's what boards and sub-requests are for.

## What the bots get wrong today

These need code changes before bots can pursue any of the goals above. They're listed most urgent
first.

1. ~~They fight hostiles on sight~~. **Fixed 2026-10-20** ✔:
   - they read `/pacifist status` and the grudge alerts, and leave alone the hostiles pacifism keeps
     off them;
   - they walk away rather than anger a whole kin;
   - they fight normally on blood moons and in villages at night;
   - spawner mobs and storm-night drowned count as hostile. They walk away from a spawner's mobs,
     and paths and gathering keep 16 blocks from spawners (2026-10-21).
2. ~~They treat health as renewable~~. **Fixed** ✔:
   - on CivFabric they never count on health coming back;
   - they flee at 12 health, and rest near home at 10 or below while radioing `heal <me>`;
   - they eat golden apples and other healing food when hurt;
   - they put a campfire by their bed (`warm_bed` milestone, `campfire` command);
   - a Medic treats hurt teammates with stitches, a medkit or a first aid kit. Anyone else only
     applies wound mush, or hands over a healing food, a healing potion or a spare bed;
   - they sleep at night only when hurt, since the night isn't skipped and a healthy bot in bed
     just loses the night's work.
3. ~~The progression ladder assumes vanilla~~. **Built 2026-10-20, not yet tested on a fresh
   world:** food, then farms, then classes, with team roles and the drain priced in. See
   [class-progression.md](class-progression.md).
4. ~~Crafting hunger~~. The planner prices every craft in food, and won't craft at 4 or below.
5. ~~Blood moons~~. **Fixed** ✔: on blood moons and stormy nights they dig in, or, if hurt, sleep in
   a walled, warm bed. Hold off on deaths.
6. **Forecast guesses** are free XP every night for Librarians and Hunters.
7. **Swimming costs 15× walking hunger.** That's another reason to path around water. Exploring
   now heads for dry land (2026-10-20), after a bot looking for wood swam 400 blocks out to sea.
8. **Villages.** Never take from village chests near villagers.

## Open questions for the real server

- **Overrides.** Does the server override any of these defaults: classes, moon chances, plant growth?
- **Drain.** Which way does the drain flow, exactly?
- ~~**Pacifism.** Which mobs ignore it?~~ Answered from the source (2026-10-21): see
  [rule 4](#the-rules-that-matter-most) and [Mobs, nights and the moon](#mobs-nights-and-the-moon).
- **Pearls.** Can ender pearls be bought from clerics, or only looted from endermen?
- **Fire charges.** Are they gated? They aren't in the recipe table.
