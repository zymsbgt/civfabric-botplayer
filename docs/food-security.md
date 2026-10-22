# Food security: research and proposed rework

Written 2026-10-22. It covers:

- what the team can eat and how to count it;
- the per-player goal you set: a warm, claimed bed and 80 food points each;
- which food and healing items players can most easily get on this pack.

Sources:
- the pack's configs: Realistic Plant Growth, Spice of Fabric and CivFabric's recipes and gates;
- CivFabric's source;
- Bot1's and Bot2's memory files from the test server (key `bb4d24e3`).

## 1. What the goal counts today, and why it falls short

- `provisions` (each bot) is the bot's own pack, valued with `MealPlan`: everything eaten in the
  best order under Spice of Fabric's repeat penalty. It needs 80 points.
- `food_secure` (gate to class levelling) needs a farm of 16 planted plots anywhere in radio range,
  plus either a stocked pack (24 points, 3 kinds) or a hunger bar of 14 or more.

The gaps:

1. **Chests count for nothing.** A bot with an empty pack hunts even when the base chest holds 40
   cooked fish.
2. **A planted farm counts the same as a harvested one.** Plots are counted as soon as they're
   planted, whether or not they grow, and before any harvest.
3. **It's per bot, not per team.** One bot can pass while the bot beside it starves, and nothing
   moves food between them. The new food swaps help, but only between two packs.
4. **16 plots is too small a farm.** At vanilla speeds a crop takes about 1.2 game days to ripen,
   and on this server a harvest returns seed and crop only part of the time: 40% + 10% per Farmer rank
   + 6% per hoe tier, with wheat and beetroot starting at 58%. Sixteen plots of wheat come to roughly
   **13 hunger a day** as bread. A busy bot burns several times that. Sixteen plots of carrots in a
   forest come to about **60 a day**, but Spice of Fabric makes a one-crop diet worth less and less.

## 2. The proposed goal

**For every player on the team: one claimed, warm bed, and 80 food points they can count on.**

A player's 80 points come from three places, counted in this order:

| Source | How it's counted |
|---|---|
| Their pack | `MealPlan` value given their own last 11 meals (as now). |
| Team chests | Food in our chests and team depots, shared out one item at a time to whichever teammate it's worth most to under `MealPlan`. The same marginal-value maths as the food swaps, so the variety a bot lacks goes to that bot. |
| The farm, over the next 3 game days | Expected harvest: for each planted plot, ripe chance from its crop's `GrowthRate` in that biome, then harvest chance by Farmer rank and hoe tier, then what the crop is worth eaten, shared out like chest food. Plots whose crop dies in that biome (rate 0, or a "BAD" biome) count as 0. |

The team milestone `food_secure` passes when every player in radio range (bots and players who've
told us) has a claimed warm bed and at least 80 points from those three sources. A bot's own
`provisions` passes on the same sum: a bot whose share of the chest is enough fetches it rather than
going hunting.

When the team is short, the shortfall goes to the bot best placed to fix it:
- **The Grower** plants more plots and more kinds, using `crops` to choose what grows here.
- **Bots with hunting or fishing nearby** bring in meat and fish (the most common foods in practice;
  see §3).
- **A bot whose share sits in a chest** fetches it.
- **A bot short on variety** gets a swap.

Beds are already handled: the `claim_bed` and `warm_bed` milestones exist per bot. The team check
adds "every player", so a bot with a spare bed and a campfire can help a teammate who has neither.

### Decisions this needs from you

1. **Horizon for farm yield.** I'd count 3 game days, matching "food to last a few days".
2. **Players who aren't bots.** Count them only once they tell a bot their food, or leave them out
   of the team sum? I'd leave them out until they opt in, since bots can't see a player's pack.
3. **Chest food that's spoken for.** Should team-depot food be reserved for the player who put it
   there, or shared? I'd share it.

### Measuring consumption

The new history log (`<uuid>.history.jsonl`) records every meal, so after a night of play we can
measure what each bot actually eats per game day. That is the right number for sizing farms, not my
estimate above.

## 3. Which food and healing items are easiest to get

### Food

Ranked by what the bots actually got, then by what grows here:

1. **Meat, cooked.** By far the most eaten:
   - Bot1's last meals were porkchop, mutton, chicken, salmon and cod; Bot2's chicken, porkchop and
     rabbit.
   - Animals are everywhere and pacifism doesn't stop hunting them.
   - Cooking pays Farmer XP (5–7 each) and the bots smelt with charcoal now.
2. **Fish** (cod, salmon), from the water landmarks the bots know (Bot1 knows 19 spots, Bot2 18).
   Fishing is quiet work, safe while hurt.
3. **Honey bottles.** Both bots know bee nests (2 each). Honey is new to the diet, so worth a lot
   under Spice of Fabric.
4. **Apples and sweet berries.** Apples come from oak leaves. Berries grow in temperate biomes at a
   60% growth rate, and breaking a bush pricks you below Journeyman Farmer.
5. **Crops by biome** (Realistic Plant Growth, as configured):

| Where | Grows at 100% | Also grows |
|---|---|---|
| Plains, meadow | wheat; barley, lettuce, onion, strawberry (Farm & Charm); onions (Farmer's Delight); grape bushes; apple and cherry trees (Vinery) | corn and cabbage at 75%; pumpkin at 85%; tomato and oats at 60%; sweet berries at 60% |
| Forest, birch, flower forest | carrots, melon, the same Farm & Charm and Farmer's Delight crops, grapes | as plains, but no wheat |
| River, beach | rice | sugar cane at 40% |
| Stony shore | potatoes, rice | sugar cane at 40% |
| Ocean | kelp | |
| Swamp, jungle | sugar cane, rice, melon, bamboo, mushrooms | wheat at 50% (swamp) |

   Wheat grows in plains and meadows only. Carrots grow in forests only. **Potatoes grow on stony
   shores only**, which is why they've been hard to find.
6. **Kelp** is in chests already (9), and grows in any ocean. It's worth little per item but is a new
   food for Spice of Fabric.

### Healing (natural regeneration is off, so these are the only way back)

| Item | Heals | Made from | Who can make or use it | How easy |
|---|---|---|---|---|
| Warm bed | +5 a night | bed + campfire within 4 | anyone, once per night | **Easiest.** Every bot should have one. |
| Hearty soup | Regeneration II for 5 s (about 4 health) plus 6 food; 5 s cooldown | fermented spider eye + bowl. The eye is spider eye + sugar + brown mushroom | **anyone**, ungated (and 50 Medic XP to craft) | **Good.** Spider eyes are already in chests (1 with Bot1, 2 with Bot2); sugar cane is known; brown mushrooms grow in swamps, jungles, dark forests and taigas. |
| Wound mush | 2 at Novice, as stitches from Apprentice | coal + 2 bones + 2 rotten flesh | anyone; heals yourself or others | **Moderate.** Bones and rotten flesh come from zombies and skeletons, which costs pacifism to kill. Drops found lying around do the job. |
| Stitches | 4 + 1.5 per Medic rank; revives too | 8 paper + sugar cane | Medic 1 to make and use | **Moderate.** Sugar cane on beaches and rivers at 40%, so slow. Needs a Medic. |
| Medkit | 1.5 × stitches | 2 stitches + copper ingot + stick | Medic 3 | Copper is plentiful (Bot1 knows 120 copper ores); the rank is the bottleneck. |
| Golden apple | regeneration | apple + 8 gold | Medic 3 | Gold is scarce (6 gold ores known). |
| First aid kit | revives anyone (full health by a Medic, 4 health by anyone else) | medkit + golden apple + book | Medic 3 | Late game. |

**In short:**
- Cooked meat, fish and honey are the foods the team can most easily get.
- Warm beds and hearty soup are the healing the team can most easily get, and both work without a
  Medic. Hearty soup is also the only way into Medic.
- Stitches, and with them revives, need a Medic.
- The new death and knock-out tallies (`deaths` command, `downed_near` in each bot's state) show who
  is usually near when someone goes down, and so who should level Medic.
