package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.tasks.*;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The bot's long-term plan when nobody has given it a job: an ordered ladder of milestones.
 * The first one not yet met is worked on; one that fails is snoozed for a while so a single
 * blocker (no iron nearby, a CivFabric gate) doesn't stall everything after it.
 */
public final class Progression {
    /** {@code wants}: what making it takes, for the shopping list to pick up on the way. */
    public record Milestone(String id, BooleanSupplier done, Function<Bot, Task> make, Map<Item, Integer> wants) {}

    /** A milestone we can't do ourselves until a class rank comes (or a teammate brings the item). */
    public record Wait(Milestone milestone, Item item, int count, String why) {}

    private final List<Milestone> ladder = new ArrayList<>();
    private final Map<String, Long> snoozed = new HashMap<>();
    private final Map<String, Wait> waiting = new HashMap<>();
    private final Map<String, Long> gateCheckedAt = new HashMap<>();
    private final List<Wait> newWaits = new ArrayList<>();

    public Progression() {
        // ---- phase 1, everyone: something to eat ------------------------------------------
        // one we know of close by counts: crafting walks to it, and making a second wastes planks
        add("crafting_table", () -> Inv.has(Items.CRAFTING_TABLE) || b().memory().nearestStation("crafting_table",
                        b().player().blockPosition(), b().dim(), 48)
                .filter(st -> lol.zym.zymbot.world.WorldView.state(st.at.pos()).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))
                .isPresent(), b -> obtain(Items.CRAFTING_TABLE, 1), Map.of(Items.CRAFTING_TABLE, 1));
        // a sword before any long walk (rejoining the team, a grave): a spider pack killed an unarmed bot
        // in seconds, and a Pokémon that fights back has to be finished. Worth the 3 food it costs (a
        // pickaxe doesn't count, though it hits a little harder than a fist)
        add("wooden_sword", Inv::armed, b -> obtain(Items.WOODEN_SWORD, 1), Map.of(Items.WOODEN_SWORD, 1));
        // stay with the team: a bot that wandered (or respawned) far from the base walks back first
        add("rejoin", () -> {
            BlockPos t = ClassPlan.teamHome(b());
            return t == null || t.closerThan(b().player().blockPosition(), 100);
        }, b -> new GoToTask(ClassPlan.teamHome(b), 12));
        // Spice of Fabric: a stack of one food is worth little, so "enough" means value and variety
        add("food_stock", Progression::stocked, Progression::food);
        add("wooden_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 0, b -> obtain(Items.WOODEN_PICKAXE, 1), Map.of(Items.WOODEN_PICKAXE, 1));
        // free to craft while classless (as are chests, torches and sticks): before the first rank
        add("furnace", () -> Inv.has(Items.FURNACE), b -> obtain(Items.FURNACE, 1), Map.of(Items.FURNACE, 1));
        // food stock counts raw meat at its cooked worth, so cook it as soon as there's a furnace
        add("cook_meat", () -> rawMeat() == null || !Inv.has(Items.FURNACE), b -> cook(rawMeat()));

        // ---- phase 2: a farm. The Grower makes it; everyone else sends it their seeds -------
        // the Grower always carries a hoe: CivFabric destroys any crop harvested without one
        add("wooden_hoe", () -> !ClassPlan.isGrower(b()) || Inv.bestTier(HoeItem.class) >= 0, b -> obtain(Items.WOODEN_HOE, 1), Map.of(Items.WOODEN_HOE, 1));
        // seed for 3 crops that grow at the base: Realistic Plant Growth won't put the same crop back
        // in the same soil for days, so a one-crop farm empties itself. And one that grows in each biome
        // the land we could farm reaches into (see bareBiome)
        add("seeds", () -> !ClassPlan.isGrower(b()) || seedKinds() >= 3 && seedCount() >= 12 && bareBiome(b()) == null, Progression::seeds);
        add("farm", () -> !ClassPlan.isGrower(b()) || farmPlots(b()) >= FARM_PLOTS, b -> new LayFieldTask());
        add("share_seeds", () -> ClassPlan.isGrower(b()) || seedCount() == 0 || grower(b()) == null || teamFarm(b()) >= FARM_PLOTS,
                b -> new GiveTask(grower(b), new ItemTarget("seeds", Progression::plantable, List.of(), seedCount())));
        // a chest at home: somewhere for the chest index to point at, and free while classless
        add("storage", () -> b().memory().home == null || ownChestNearHome(b()), b -> new StorageTask());
        // pack filling up: junk out, the rest into a chest at home (made if there's none with room),
        // where the index will find it again. Keep says which is which
        add("stash", () -> Inv.freeSlots() >= 8 || lol.zym.zymbot.inv.Keep.surplus(b()).isEmpty(), Progression::stash);
        // a bed in whatever colour we have 3 wool of; shear for it (wooden shears, no class) otherwise
        add("bed", () -> b().memory().bed != null || Inv.find(s -> s.is(ItemTags.BEDS)) >= 0, Progression::bed);
        add("settle", () -> b().memory().bed != null, b -> new SettleTask());
        // CivFabric gives a bed to whoever clicks it first, and only a claimed bed is a respawn point:
        // without one every death washes us up somewhere random on the island. Claim it by day too
        add("claim_bed", () -> b().memory().bed == null || !Boolean.FALSE.equals(b().memory().bedClaimed),
                b -> new SleepTask(b.memory().bed.pos(), true));
        // CivFabric: a bed only heals with a campfire beside it, and health comes back no other way
        add("warm_bed", () -> b().memory().bed == null || b().memory().bedWarm, b -> new WarmBedTask());
        // food to last a few days away from the farm: 80 hunger points each (raw meat at its cooked worth)
        add("provisions", () -> foodPoints() >= FOOD_POINTS, Progression::provision);
        // nothing past here until the team can feed itself
        add("food_secure", () -> foodSecure(b()), Progression::feedTheTeam);

        // ---- phase 3: classes, each bot in its role's, with gear as ranks unlock it ---------
        add("level_apprentice", () -> levelled(1), b -> level(b));
        add("stone_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 1, b -> obtain(Items.STONE_PICKAXE, 1), Map.of(Items.STONE_PICKAXE, 1));
        add("stone_sword", () -> Inv.bestTier(SwordItem.class) >= 1, b -> obtain(Items.STONE_SWORD, 1), Map.of(Items.STONE_SWORD, 1));
        // a full set of leather (7 armour): what a bot can take on rises from what its sword is worth.
        // Each piece is done once its slot holds any armour; Bot.equipArmor puts on what we carry
        add("leather_helmet", () -> armoured(net.minecraft.world.entity.EquipmentSlot.HEAD, Items.LEATHER_HELMET),
                b -> obtain(Items.LEATHER_HELMET, 1), Map.of(Items.LEATHER_HELMET, 1));
        add("leather_chestplate", () -> armoured(net.minecraft.world.entity.EquipmentSlot.CHEST, Items.LEATHER_CHESTPLATE),
                b -> obtain(Items.LEATHER_CHESTPLATE, 1), Map.of(Items.LEATHER_CHESTPLATE, 1));
        add("leather_leggings", () -> armoured(net.minecraft.world.entity.EquipmentSlot.LEGS, Items.LEATHER_LEGGINGS),
                b -> obtain(Items.LEATHER_LEGGINGS, 1), Map.of(Items.LEATHER_LEGGINGS, 1));
        add("leather_boots", () -> armoured(net.minecraft.world.entity.EquipmentSlot.FEET, Items.LEATHER_BOOTS),
                b -> obtain(Items.LEATHER_BOOTS, 1), Map.of(Items.LEATHER_BOOTS, 1));
        add("stone_axe", () -> Inv.bestTier(AxeItem.class) >= 1, b -> obtain(Items.STONE_AXE, 1), Map.of(Items.STONE_AXE, 1));
        add("stone_shovel", () -> Inv.bestTier(ShovelItem.class) >= 1, b -> obtain(Items.STONE_SHOVEL, 1), Map.of(Items.STONE_SHOVEL, 1));
        add("coal", () -> Inv.count(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) >= 8 || Inv.count(Items.TORCH) >= 16,
                b -> new ObtainTask(new ItemTarget("coal", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), List.of(Items.COAL, Items.CHARCOAL), 8)),
                Map.of(Items.COAL, 8));
        add("torches", () -> Inv.count(Items.TORCH) >= 16, b -> obtain(Items.TORCH, 16), Map.of(Items.TORCH, 16));
        add("level_journeyman", () -> levelled(2), b -> level(b));
        add("iron_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 2, b -> obtain(Items.IRON_PICKAXE, 1), Map.of(Items.IRON_PICKAXE, 1));
        add("shield", () -> Inv.has(Items.SHIELD), b -> obtain(Items.SHIELD, 1), Map.of(Items.SHIELD, 1));
        add("iron_sword", () -> Inv.bestTier(SwordItem.class) >= 2, b -> obtain(Items.IRON_SWORD, 1), Map.of(Items.IRON_SWORD, 1));
        add("level_expert", () -> levelled(3), b -> level(b));
        add("iron_chestplate", () -> wearingOrHas(Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE), b -> obtain(Items.IRON_CHESTPLATE, 1), Map.of(Items.IRON_CHESTPLATE, 1));
        add("iron_helmet", () -> wearingOrHas(Items.IRON_HELMET, Items.DIAMOND_HELMET), b -> obtain(Items.IRON_HELMET, 1), Map.of(Items.IRON_HELMET, 1));
        add("iron_leggings", () -> wearingOrHas(Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS), b -> obtain(Items.IRON_LEGGINGS, 1), Map.of(Items.IRON_LEGGINGS, 1));
        add("iron_boots", () -> wearingOrHas(Items.IRON_BOOTS, Items.DIAMOND_BOOTS), b -> obtain(Items.IRON_BOOTS, 1), Map.of(Items.IRON_BOOTS, 1));
        add("poke_balls", () -> !BotConfig.get().catchPokemon || ItemTarget.parse("cobblemon:poke_ball", 1) == null
                        || Inv.find(CatchPokemonTask::isBall) >= 0,
                b -> new ObtainTask(ItemTarget.parse("cobblemon:poke_ball", 8)),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.parse("cobblemon:poke_ball"))
                        .map(ball -> Map.of(ball, 8)).orElse(Map.of()));
        add("level_master", () -> levelled(4), b -> level(b));
        add("diamond_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 3, b -> obtain(Items.DIAMOND_PICKAXE, 1), Map.of(Items.DIAMOND_PICKAXE, 1));
        add("level_grandmaster", () -> levelled(5), b -> level(b));
    }

    // ---- phases --------------------------------------------------------------------------

    /** Hunger points of food every bot should carry. */
    public static final int FOOD_POINTS = 80;

    /** Hunger points of the safe food we carry, each at the game's own value, raw meat as cooked. */
    /**
     * Hunger our food will really give us: Spice of Fabric cuts each repeat of a food by 0.7 (going by
     * what this player ate lately), so eight cooked rabbit are worth about 15, not 40. A mix is worth
     * more than a pile; the provisions goal counts what we'd actually get.
     */
    public static int foodPoints() {
        return (int) Math.round(lol.zym.zymbot.inv.Diet.stockValue(b()));
    }

    private static boolean farmsAtHome(Bot bot) {
        return ClassPlan.isGrower(bot) && bot.memory().home != null && farmPlots(bot) > 0;
    }

    /** The Grower takes its provisions from the farm; everyone else hunts and forages. */
    private static Task provision(Bot bot) {
        long now = System.currentTimeMillis();
        // a shift at the farm that didn't fill the pack means it's growing or its soil is resting
        // (Bot5's wheat): food from elsewhere meanwhile, not the same empty field every 10 minutes
        if (farmsAtHome(bot) && now - farmedAt > 15 * 60_000L) {
            farmedAt = now;
            return new FarmTask(bot.memory().home.pos(), 10, 8);
        }
        // hurt: no hunting, but fishing by the farm is as quiet as farming it
        if (bot.health().fragile(bot)) {
            return FishTask.canFish(bot) && bot.memory().home != null
                    ? new FishTask(bot.memory().home.pos(), () -> foodPoints() >= FOOD_POINTS, 8) : null;
        }
        return food(bot);
    }

    private static long farmedAt;

    /** Planted plots that make a farm worth calling one. */
    public static final int FARM_PLOTS = 16;

    public String phase(Bot bot) {
        if (foodSecure(bot)) return "level";
        if (teamFarm(bot) > 0 || ClassPlan.isGrower(bot) && stocked()) return "farm";
        return "food";
    }

    private static boolean stocked() {
        return lol.zym.zymbot.inv.Diet.stockValue(b()) >= 24 && lol.zym.zymbot.inv.Diet.kinds() >= 3;
    }

    /** We're fed, and so is every teammate in radio range (their status lines say "fs=1"). */
    public static boolean teamFoodSecure(Bot bot) {
        if (!foodSecure(bot)) return false;
        for (var m : bot.team().inRange()) {
            var fs = m.state().get("food_secure");
            if (fs == null || !fs.getAsBoolean()) return false;
        }
        return true;
    }

    /** A farm feeds the team (ours or a teammate's) and we're carrying enough to eat meanwhile. */
    public static boolean foodSecure(Bot bot) {
        return teamFarm(bot) >= FARM_PLOTS && (stocked() || bot.player().getFoodData().getFoodLevel() >= 14);
    }

    /** Plots we've planted near home (or here, without one). */
    public static int farmPlots(Bot bot) {
        BlockPos at = bot.memory().home != null ? bot.memory().home.pos() : bot.player().blockPosition();
        String prefix = bot.dim() + " ";
        int n = 0;
        for (var e : bot.memory().plantings.entrySet()) {
            if (e.getValue().crop == null || !e.getKey().startsWith(prefix)) continue;
            String[] xyz = e.getKey().substring(prefix.length()).split(" ");
            if (xyz.length != 3) continue;
            BlockPos p = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (p.closerThan(at, 64)) n++;
        }
        return n;
    }

    /** The biggest farm anyone in radio range reports, ours included. */
    public static int teamFarm(Bot bot) {
        int best = farmPlots(bot);
        for (var m : bot.team().inRange()) {
            var f = m.state().get("farm_plots");
            if (f != null && f.isJsonPrimitive()) best = Math.max(best, f.getAsInt());
        }
        return best;
    }

    private static boolean farmed() {
        return farmPlots(b()) >= FARM_PLOTS;
    }

    /** The teammate in radio range whose role is Grower (farmer first), or null. */
    static String grower(Bot bot) {
        for (var m : bot.team().inRange()) {
            var c = m.state().get("classes");
            if (c != null && c.isJsonArray() && !c.getAsJsonArray().isEmpty() && c.getAsJsonArray().get(0).getAsString().equals("farmer"))
                return m.name();
        }
        return null;
    }

    static boolean plantable(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
    }

    private static int seedCount() {
        return Inv.count(Progression::plantable);
    }

    /** Seeds we carry that will grow at our base's biome. */
    private static int growingSeeds(Bot bot) {
        return Inv.count(s -> plantable(s) && growsAtBase(bot, ((BlockItem) s.getItem()).getBlock()));
    }

    /** Different seeds we carry that will actually grow at our base's biome. */
    private static int seedKinds() {
        java.util.Set<Item> k = new java.util.HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = Inv.inv().getItem(i);
            if (plantable(st) && growsAtBase(b(), ((BlockItem) st.getItem()).getBlock())) k.add(st.getItem());
        }
        return k.size();
    }

    /**
     * Realistic Plant Growth lets this crop grow at our base (or here, without one). While part of
     * the land we could farm has no seed of ours that grows there, it's that part's climate that
     * counts: the seeds still wanted are seeds for it.
     */
    static boolean growsAtBase(Bot bot, net.minecraft.world.level.block.Block crop) {
        if (!lol.zym.zymbot.farm.PlantRules.active()) return true;
        var pr = lol.zym.zymbot.farm.PlantRules.get();
        BlockPos bare = bareBiome(bot);
        if (bare != null) return pr.governs(crop) && pr.judgeClimate(crop, bare).grows();
        if (!pr.governs(crop)) return true;
        BlockPos at = ClassPlan.teamHome(bot);
        if (at == null) at = bot.player().blockPosition();
        // the climate there: the base itself may be under trees or a roof, the field won't be
        return pr.judgeClimate(crop, at.above()).grows();
    }

    /** Plots a biome needs before its seeds are worth a hunt: not for a corner of the field over the line. */
    private static final int BIOME_PLOTS = 4;
    private static List<BlockPos> field = List.of();
    private static long fieldAt;

    /**
     * Land we could farm around our home, one spot per biome it lies in, the biome with the most
     * plots first (rechecked every 30 s while the home is in sight). A plot is what a field gets laid
     * on: farmland, grass or dirt with water within 4 blocks at its level or one above (so farmland
     * there stays wet), open to the sky. Bot1's home was in forest a few blocks from savanna: its
     * forest seeds passed, and the plots over the line took none of them.
     */
    static List<BlockPos> fieldBiomes(Bot bot) {
        var home = bot.memory().home;
        if (home == null || !home.dim.equals(bot.dim())) return List.of();
        long now = System.currentTimeMillis();
        BlockPos h = home.pos();
        if (now - fieldAt < 30_000 || !lol.zym.zymbot.world.WorldView.loaded(h)) return field;
        fieldAt = now;
        int r = 10;
        java.util.Set<BlockPos> water = new java.util.HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(h.offset(-r - 4, -3, -r - 4), h.offset(r + 4, 4, r + 4)))
            if (lol.zym.zymbot.world.WorldView.water(p)) water.add(p.immutable());
        Map<net.minecraft.resources.ResourceLocation, int[]> count = new HashMap<>();
        Map<net.minecraft.resources.ResourceLocation, BlockPos> spot = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(h.offset(-r, -3, -r), h.offset(r, 3, r))) {
            var s = lol.zym.zymbot.world.WorldView.state(p);
            if (!(s.is(net.minecraft.world.level.block.Blocks.FARMLAND) || s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || s.is(net.minecraft.world.level.block.Blocks.DIRT) || s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT))) continue;
            BlockPos above = p.above();
            var a = lol.zym.zymbot.world.WorldView.state(above);
            if (!(a.isAir() || a.canBeReplaced()) || lol.zym.zymbot.world.WorldView.water(above) || !bot.level().canSeeSky(above)) continue;
            if (!wet(water, p)) continue;
            var biome = lol.zym.zymbot.farm.PlantRules.biomeAt(above);
            if (biome == null) continue;
            count.computeIfAbsent(biome, k -> new int[1])[0]++;
            spot.putIfAbsent(biome, above.immutable());
        }
        List<net.minecraft.resources.ResourceLocation> biomes = new ArrayList<>(count.keySet());
        biomes.removeIf(b -> count.get(b)[0] < BIOME_PLOTS);
        biomes.sort(java.util.Comparator.comparingInt(b -> -count.get(b)[0]));
        List<BlockPos> out = new ArrayList<>();
        for (var b : biomes) out.add(spot.get(b));
        return field = out;
    }

    private static boolean wet(java.util.Set<BlockPos> water, BlockPos soil) {
        for (int dy = 0; dy <= 1; dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    if (water.contains(soil.offset(dx, dy, dz))) return true;
        return false;
    }

    /**
     * A spot in a biome of the land we could farm where none of our seeds grows, or null. Only
     * crops the plant rules know count: one they don't (Cobblemon's medicinal leek) has needs of
     * its own, and the field couldn't take Bot1's.
     */
    static BlockPos bareBiome(Bot bot) {
        if (!lol.zym.zymbot.farm.PlantRules.active()) return null;
        var pr = lol.zym.zymbot.farm.PlantRules.get();
        for (BlockPos at : fieldBiomes(bot)) {
            boolean grows = false;
            for (int i = 0; i < 36 && !grows; i++) {
                ItemStack st = Inv.inv().getItem(i);
                if (!plantable(st)) continue;
                var crop = ((BlockItem) st.getItem()).getBlock();
                grows = pr.governs(crop) && pr.judgeClimate(crop, at).grows();
            }
            if (!grows) return at;
        }
        return null;
    }

    /**
     * Seeds: wild crops drop them (Farmer's Delight cabbage seeds and tomato seeds, Farm & Charm
     * barley, oat and lettuce seeds, wild potatoes and carrots plant themselves), and so does tall
     * grass (wheat seeds). Wild crops first: they're food too.
     */
    /** Wild crops that would give us a seed we don't carry yet, of a crop that grows at the base. */
    public static List<net.minecraft.world.level.block.Block> newSeedSources(Bot bot) {
        List<net.minecraft.world.level.block.Block> wild = new ArrayList<>();
        for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (!id.startsWith("wild_")) continue;
            if (lol.zym.zymbot.craft.Sources.lootOf(b).stream().anyMatch(d -> d instanceof BlockItem bi
                    && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock && growsAtBase(bot, bi.getBlock()) && Inv.count(d) == 0))
                wild.add(b);
        }
        return wild;
    }

    /** Seeds are still wanted: fewer than three kinds, too few to sow, or none for part of the field. */
    public static boolean wantSeeds(Bot bot) {
        return ClassPlan.isGrower(bot) && (seedKinds() < 3 || seedCount() < 12 || bareBiome(bot) != null);
    }

    private static Task seeds(Bot bot) {
        BlockPos bare = bareBiome(bot);
        if (bare != null) bot.event("none of my seeds grows in the " + lol.zym.zymbot.farm.PlantRules.biomeAt(bare).getPath()
                + " part of the field (" + bare.toShortString() + "): looking for one that does");
        // Farm & Charm makes seeds of crops we carry as food (1 lettuce: 3 seeds): no hunt needed
        Item crafted = seedFromCrop(bot);
        if (crafted != null) return new ObtainTask(ItemTarget.of(crafted, 6)).makeIt();
        // wild crops whose seed grows at the base and that we don't carry yet
        List<net.minecraft.world.level.block.Block> wild = new ArrayList<>();
        for (var b : newSeedSources(bot)) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (bot.seenNearby(b) || lol.zym.zymbot.memory.Landmarks.nearest(bot, ("wild:" + id.substring(5))::equals, 120) != null) wild.add(b);
        }
        if (!wild.isEmpty()) return new GatherTask(wild, Progression::plantable, seedCount() + 4);
        // grass only ever gives wheat seeds: once we have enough to sow, or if wheat won't grow at
        // the base anyway, more is no help (a bot carried 70 still looking for a third kind, and
        // none of them would grow). Go looking for wild crops twice in half an hour, then make do
        // with the kinds we have; the shopping list still picks one up on the way
        if (growingSeeds(bot) >= 12 || !growsAtBase(bot, net.minecraft.world.level.block.Blocks.WHEAT)) {
            long now = System.currentTimeMillis();
            seedHunts.removeIf(t -> now - t > 30 * 60_000L);
            if (seedHunts.size() >= 2) {
                bot.event("no wild crops of a new kind about; farming with the " + seedKinds() + " we have");
                return null;
            }
            seedHunts.add(now);
            return new ExploreTask(96);
        }
        return new GatherTask(List.of(net.minecraft.world.level.block.Blocks.SHORT_GRASS, net.minecraft.world.level.block.Blocks.TALL_GRASS),
                Progression::plantable, seedCount() + 6);
    }

    /** A seed we don't carry, that grows at the base, and that we can craft out of what we hold. */
    private static Item seedFromCrop(Bot bot) {
        for (Item seed : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            ItemStack st = new ItemStack(seed);
            if (!plantable(st) || Inv.count(seed) > 0 || !growsAtBase(bot, ((BlockItem) seed).getBlock())) continue;
            if (!bot.civ().canCraft(bot.memory(), Inv.id(seed))) continue;
            for (var h : lol.zym.zymbot.craft.Recipes.craftingFor(seed)) {
                if (!bot.civ().canCraft(bot.memory(), h.id().toString())) continue;
                if (lol.zym.zymbot.craft.Recipes.nonEmpty(h.value()).stream().allMatch(i -> Inv.count(i::test) > 0)) return seed;
            }
        }
        return null;
    }

    /** When we last went exploring for a new kind of seed. */
    private static final List<Long> seedHunts = new ArrayList<>();

    private static boolean ownChestNearHome(Bot bot) {
        BlockPos home = bot.memory().home.pos();
        return bot.memory().chests.values().stream().anyMatch(r -> r.ours && r.at != null && r.at.pos().closerThan(home, 32));
    }

    private static Task bed(Bot bot) {
        for (Item w : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!new ItemStack(w).is(ItemTags.WOOL) || Inv.count(w) < 3) continue;
            String colour = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(w).getPath().replace("_wool", "");
            ItemTarget bed = ItemTarget.parse("minecraft:" + colour + "_bed", 1);
            if (bed != null) return new ObtainTask(bed);
        }
        return new ShearTask(3);
    }

    /** Where our things are kept: home, else the team's base, else nowhere yet (a chest goes where we stand). */
    private static BlockPos stashBase(Bot bot) {
        if (bot.memory().home != null && bot.memory().home.dim.equals(bot.dim())) return bot.memory().home.pos();
        return ClassPlan.teamHome(bot);
    }

    /** A chest of ours by the base (or by us, with no base) that isn't known to be full. */
    private static BlockPos stashChest(Bot bot) {
        BlockPos base = stashBase(bot);
        BlockPos at = base != null ? base : bot.player().blockPosition();
        int reach = base != null ? 32 : 48;
        return bot.memory().chests.values().stream()
                .filter(r -> r.ours && r.free != 0 && r.at != null && r.at.dim.equals(bot.dim()) && r.at.pos().closerThan(at, reach))
                .map(r -> r.at.pos()).min(java.util.Comparator.comparingDouble(c -> c.distSqr(at))).orElse(null);
    }

    /**
     * Stashing failed lately (no chest, can't make one, can't reach it): only then may stored things be
     * dropped. A failure snoozes it for 10 minutes; the 1-minute snooze of a stash that ended at once
     * (dropping junk takes a second) is no sign of trouble.
     */
    public boolean stashStuck() {
        return snoozed.getOrDefault("stash", 0L) - System.currentTimeMillis() > 2 * 60_000L;
    }

    private static Task stash(Bot bot) {
        // junk needs no walk home: drop it here
        if (lol.zym.zymbot.inv.Keep.any(bot, lol.zym.zymbot.inv.Keep.Fate.JUNK)) return new DropJunkTask();
        BlockPos chest = stashChest(bot);
        if (chest != null) return new StashTask(chest);
        // none, or every one full: another chest by the base, or here
        BlockPos base = stashBase(bot);
        bot.event("no chest with room" + (base != null ? " at the base" : "") + ": making one");
        return new StorageTask(base != null ? base : bot.player().blockPosition());
    }

    /** While the farm isn't there yet: the Grower makes or tends it, everyone else fills their pack. */
    private static Task feedTheTeam(Bot bot) {
        if (ClassPlan.isGrower(bot)) {
            if (farmPlots(bot) < FARM_PLOTS) return new LayFieldTask();
            return new FarmTask(bot.memory().home.pos(), 6, 8);
        }
        if (seedCount() > 0 && grower(bot) != null)
            return new GiveTask(grower(bot), new ItemTarget("seeds", Progression::plantable, List.of(), seedCount()));
        // gather seeds for the Grower (wild crops are food as well), else stock up
        if (grower(bot) != null && teamFarm(bot) < FARM_PLOTS) return seeds(bot);
        return food(bot);
    }

    /** Every class in our role at {@code rank} or better. */
    private static boolean levelled(int rank) {
        Bot bot = b();
        if (!foodSecure(bot)) return false; // and deferred() keeps it waiting until the food phase is over
        List<String> plan = ClassPlan.classes(bot);
        if (plan.isEmpty()) return true;
        for (String c : plan) {
            double xp = lol.zym.zymbot.civ.ClassSheet.xp(bot, c);
            if (xp < 0 || lol.zym.zymbot.civ.ClassBook.rankOf(xp) < rank) return false;
        }
        return true;
    }

    private static Task level(Bot bot) {
        String c = ClassPlan.nextToLevel(bot);
        return c == null ? null : new LevelTask(c);
    }

    private static Bot b() {
        return Bot.get();
    }

    private void add(String id, BooleanSupplier done, Function<Bot, Task> make) {
        add(id, done, make, Map.of());
    }

    private void add(String id, BooleanSupplier done, Function<Bot, Task> make, Map<Item, Integer> wants) {
        ladder.add(new Milestone(id, done, make, wants));
    }

    private static Task obtain(Item item, int n) {
        return new ObtainTask(ItemTarget.of(item, n));
    }

    /** Some armour in this slot already, or the piece in our bag waiting to go on. */
    private static boolean armoured(net.minecraft.world.entity.EquipmentSlot slot, Item piece) {
        return b().player().getItemBySlot(slot).getItem() instanceof ArmorItem || Inv.has(piece);
    }

    private static boolean wearingOrHas(Item... items) {
        var p = b().player();
        for (Item i : items) {
            if (Inv.has(i)) return true;
            for (ItemStack a : p.getArmorSlots()) if (a.is(i)) return true;
        }
        return false;
    }

    private static Item rawMeat() {
        for (Item i : List.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT, Items.COD, Items.SALMON)) {
            if (Inv.count(i) >= 3) return i;
        }
        return null;
    }

    /** Cook the raw meat we're carrying: exactly what's there (never hunt for more to make up a count). */
    private static Task cook(Item raw) {
        if (raw == null) return null;
        int n = Inv.count(raw);
        if (n == 0) return null;
        for (var r : b().level().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING)) {
            if (r.value().getIngredients().get(0).test(new ItemStack(raw))) return new SmeltTask(r, n);
        }
        return null;
    }

    /**
     * Food: hunt animals for meats we're short of (Spice of Fabric wants variety), any of them
     * if we have some of every meat.
     */
    static Task food(Bot bot) {
        Item[] meats = {Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT};
        Item[] cooked = {Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN, Items.COOKED_RABBIT};
        EntityType<?>[] from = {EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT};
        List<EntityType<?>> want = new ArrayList<>();
        for (int i = 0; i < meats.length; i++) {
            int have = Inv.count(meats[i]) + Inv.count(cooked[i]);
            int eaten = lol.zym.zymbot.inv.Diet.timesEaten(bot, new ItemStack(meats[i])) + lol.zym.zymbot.inv.Diet.timesEaten(bot, new ItemStack(cooked[i]));
            if (have < 4 && eaten < 3) want.add(from[i]);
        }
        // only chase what's actually around (pacifist servers have no pigs at all), else anything
        List<EntityType<?>> around = new ArrayList<>();
        for (var e : bot.level().getEntities(bot.player(), bot.player().getBoundingBox().inflate(64))) {
            if (!around.contains(e.getType()) && List.of(from).contains(e.getType())) around.add(e.getType());
        }
        List<EntityType<?>> seenWanted = new ArrayList<>(want);
        seenWanted.retainAll(around);
        if (!seenWanted.isEmpty()) want = seenWanted;
        else if (!around.isEmpty()) want = around;
        if (want.isEmpty()) want = List.of(from);
        // wild crops first: they're food, they don't run, they don't fight back, and they drop seed
        // only wild crops whose food we're short of: Spice of Fabric wants variety, not 50 onions
        List<net.minecraft.world.level.block.Block> wild = new ArrayList<>();
        for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().startsWith("wild_") || !bot.seenNearby(b)) continue;
            boolean wanted = lol.zym.zymbot.craft.Sources.lootOf(b).stream()
                    .anyMatch(d -> Inv.isFood(new ItemStack(d)) && !Inv.riskyFood(new ItemStack(d)) && Inv.count(d) < 4);
            if (wanted) wild.add(b);
        }
        if (!wild.isEmpty() && (around.isEmpty() || bot.random().nextBoolean())) {
            java.util.function.Predicate<ItemStack> food = s -> Inv.isFood(s) && !Inv.riskyFood(s);
            return new GatherTask(wild, food, Inv.count(food) + 6);
        }
        // wild Pokémon count too, and what they drop (berries, fish, honey) is food as good as meat
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> eats = s -> {
            for (Item m : meats) if (s.is(m)) return true;
            return Inv.isFood(s) && !Inv.riskyFood(s);
        };
        return new HuntTask(want, eats, Inv.count(eats) + 4).orPokemon();
    }

    private static final List<String> BASICS = List.of("crafting_table", "wooden_pickaxe", "food_stock", "furnace");

    /**
     * Basic tools and food in hand: from here on, team board jobs come before further progression.
     * A basic the server won't let us craft (CivFabric class gates stone tools) counts as settled:
     * it's been asked for on the board, and holding back from board jobs until it arrives would
     * leave a team of classless bots all asking and none helping.
     */
    public boolean basicsDone() {
        for (Milestone m : ladder) {
            if (!BASICS.contains(m.id()) || m.done().getAsBoolean()) continue;
            if (b().memory().refusals.containsKey("craft:minecraft:" + m.id())) continue;
            return false;
        }
        return true;
    }

    /** Next milestone to work on, or null when everything reachable is done. */
    /**
     * Milestones a bot too hurt to risk anything may still work on: the ones that heal it (bed,
     * campfire), the claim that makes its bed a respawn point (a hurt bot is the likeliest to die),
     * and quiet work at home. No hunting, mining or long walks.
     */
    private static final java.util.Set<String> SAFE = java.util.Set.of("crafting_table", "rejoin", "wooden_sword", "furnace", "cook_meat",
            "wooden_hoe", "seeds", "farm", "share_seeds", "storage", "stash", "bed", "settle", "claim_bed", "warm_bed");

    public Milestone next() {
        return next(false);
    }

    /** The next milestone, or with {@code safeOnly} the next one a fragile bot may do. */
    public Milestone next(boolean safeOnly) {
        long now = System.currentTimeMillis();
        // a full pack fails every craft and pickup, whatever comes first on the ladder: room first
        if (Inv.freeSlots() == 0) {
            for (Milestone m : ladder) {
                if (m.id().equals("stash") && !m.done().getAsBoolean() && snoozed.getOrDefault("stash", 0L) <= now) return m;
            }
        }
        for (Milestone m : ladder) {
            // a Grower's provisions come from its own fields at home: as quiet as work gets, and a hurt
            // Grower with nothing to eat beside a planted farm was going hungry (Bot1)
            if (safeOnly && !SAFE.contains(m.id()) && !(m.id().equals("provisions") && farmsAtHome(b()))) continue;
            if (m.done().getAsBoolean()) continue;
            Long until = snoozed.get(m.id());
            if (until != null && until > now) continue;
            // the server refused to let us craft it (a class gate) within the last hour: it's on
            // the board, and asking again on every restart just churns the crafting table
            Long refused = b().memory().refusals.get("craft:minecraft:" + m.id());
            if (refused != null && now - refused < 3600_000L) continue;
            // gated by a rank we haven't got: asked for once, then left until the rank or the item comes
            if (gate(m) != null || deferred(m) != null) continue;
            return m;
        }
        return null;
    }

    /**
     * What rank stands between us and a milestone, if any (rechecked every 30 s: the planner is
     * not free). A newly gated one is queued for {@link #newWaits} so it's asked for once, not
     * failed and re-posted every snooze.
     */
    private Wait gate(Milestone m) {
        if (m.wants().isEmpty()) return null;
        long now = System.currentTimeMillis();
        Long at = gateCheckedAt.get(m.id());
        if (at != null && now - at < 30_000L) return waiting.get(m.id());
        gateCheckedAt.put(m.id(), now);
        Wait w = null;
        for (var e : m.wants().entrySet()) {
            if (Inv.count(s -> s.is(e.getKey()) && !ToolShare.worn(s)) >= e.getValue()) continue;
            String why = b().planner().gate(e.getKey());
            if (why != null) {
                w = new Wait(m, e.getKey(), e.getValue(), why);
                break;
            }
        }
        Wait was = w == null ? waiting.remove(m.id()) : waiting.put(m.id(), w);
        if (w != null && was == null) newWaits.add(w);
        if (w == null && was != null) b().event(m.id() + ": no longer waiting on " + was.why());
        return w;
    }

    /** Why a milestone isn't to be started yet, or null: levelling classes waits for the farm. */
    private static String deferred(Milestone m) {
        if (m.id().startsWith("level_") && !foodSecure(b())) return "until food is secure";
        for (Item it : m.wants().keySet()) {
            String why = ToolShare.pickaxesFirst(b(), it);
            if (why != null) return why;
        }
        return null;
    }

    /** Milestones that became gated since the last call: each wants one board post. */
    public List<Wait> newWaits() {
        if (newWaits.isEmpty()) return List.of();
        List<Wait> out = new ArrayList<>(newWaits);
        newWaits.clear();
        return out;
    }

    /**
     * The next {@code k} milestones still to do. Snoozed ones count (their materials still help);
     * gated ones don't (iron for an iron pickaxe we may not craft is a teammate's errand).
     */
    public List<Milestone> upcoming(int k) {
        List<Milestone> out = new ArrayList<>();
        for (Milestone m : ladder) {
            if (out.size() >= k) break;
            if (!m.done().getAsBoolean() && gate(m) == null && deferred(m) == null) out.add(m);
        }
        return out;
    }

    public void snooze(String id, long millis) {
        snoozed.put(id, System.currentTimeMillis() + millis);
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Milestone m : ladder) {
            Wait w = waiting.get(m.id());
            String d = deferred(m);
            String s = m.done().getAsBoolean() ? "done" : d != null ? "waiting (" + d + ")" : w != null ? "waiting (" + w.why() + ")"
                    : snoozed.getOrDefault(m.id(), 0L) > now ? "snoozed" : "todo";
            out.add(m.id() + ":" + s);
        }
        return out;
    }
}
