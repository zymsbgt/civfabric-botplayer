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
    public record Milestone(String id, BooleanSupplier done, Function<Bot, Task> make) {}

    private final List<Milestone> ladder = new ArrayList<>();
    private final Map<String, Long> snoozed = new HashMap<>();

    public Progression() {
        // ---- phase 1, everyone: something to eat ------------------------------------------
        // one we placed close by counts: crafting uses it, and making a second wastes planks
        add("crafting_table", () -> Inv.has(Items.CRAFTING_TABLE) || b().memory().nearestStation("crafting_table",
                        b().player().blockPosition(), b().dim(), 24)
                .filter(st -> lol.zym.zymbot.world.WorldView.state(st.at.pos()).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE))
                .isPresent(), b -> obtain(Items.CRAFTING_TABLE, 1));
        // stay with the team: a bot that wandered (or respawned) far from the base walks back first
        add("rejoin", () -> {
            BlockPos t = ClassPlan.teamHome(b());
            return t == null || t.closerThan(b().player().blockPosition(), 100);
        }, b -> new GoToTask(ClassPlan.teamHome(b), 12));
        // hunting with fists takes forever; a wooden sword (or any weapon) is worth the 3 food it costs
        add("wooden_sword", () -> Inv.bestWeapon() >= 0, b -> obtain(Items.WOODEN_SWORD, 1));
        // Spice of Fabric: a stack of one food is worth little, so "enough" means value and variety
        add("food_stock", Progression::stocked, Progression::food);
        add("wooden_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 0, b -> obtain(Items.WOODEN_PICKAXE, 1));
        // free to craft while classless (as are chests, torches and sticks): before the first rank
        add("furnace", () -> Inv.has(Items.FURNACE), b -> obtain(Items.FURNACE, 1));
        // food stock counts raw meat at its cooked worth, so cook it as soon as there's a furnace
        add("cook_meat", () -> rawMeat() == null || !Inv.has(Items.FURNACE), b -> cook(rawMeat()));

        // ---- phase 2: a farm. The Grower makes it; everyone else sends it their seeds -------
        add("wooden_hoe", () -> !ClassPlan.isGrower(b()) || Inv.bestTier(HoeItem.class) >= 0 || farmed(), b -> obtain(Items.WOODEN_HOE, 1));
        add("seeds", () -> !ClassPlan.isGrower(b()) || farmed() || seedKinds() >= 2 && seedCount() >= 12, Progression::seeds);
        add("farm", () -> !ClassPlan.isGrower(b()) || farmPlots(b()) >= FARM_PLOTS, b -> new LayFieldTask());
        add("share_seeds", () -> ClassPlan.isGrower(b()) || seedCount() == 0 || grower(b()) == null || teamFarm(b()) >= FARM_PLOTS,
                b -> new GiveTask(grower(b), new ItemTarget("seeds", Progression::plantable, List.of(), seedCount())));
        // a chest at home: somewhere for the chest index to point at, and free while classless
        add("storage", () -> b().memory().home == null || ownChestNearHome(b()), b -> new StorageTask());
        // pack filling up: surplus goes in the home chest, where the index will find it again
        add("stash", () -> Inv.freeSlots() >= 8 || stashChest(b()) == null || surplus() == null, Progression::stash);
        // a bed in whatever colour we have 3 wool of; shear for it (wooden shears, no class) otherwise
        add("bed", () -> b().memory().bed != null || Inv.find(s -> s.is(ItemTags.BEDS)) >= 0, Progression::bed);
        add("settle", () -> b().memory().bed != null, b -> new SettleTask());
        // CivFabric: a bed only heals with a campfire beside it, and health comes back no other way
        add("warm_bed", () -> b().memory().bed == null || b().memory().bedWarm, b -> new WarmBedTask());
        // nothing past here until the team can feed itself
        add("food_secure", () -> foodSecure(b()), Progression::feedTheTeam);

        // ---- phase 3: classes, each bot in its role's, with gear as ranks unlock it ---------
        add("level_apprentice", () -> levelled(1), b -> level(b));
        add("stone_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 1, b -> obtain(Items.STONE_PICKAXE, 1));
        add("stone_sword", () -> Inv.bestTier(SwordItem.class) >= 1, b -> obtain(Items.STONE_SWORD, 1));
        add("stone_axe", () -> Inv.bestTier(AxeItem.class) >= 1, b -> obtain(Items.STONE_AXE, 1));
        add("stone_shovel", () -> Inv.bestTier(ShovelItem.class) >= 1, b -> obtain(Items.STONE_SHOVEL, 1));
        add("coal", () -> Inv.count(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) >= 8 || Inv.count(Items.TORCH) >= 16,
                b -> new ObtainTask(new ItemTarget("coal", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), List.of(Items.COAL, Items.CHARCOAL), 8)));
        add("torches", () -> Inv.count(Items.TORCH) >= 16, b -> obtain(Items.TORCH, 16));
        add("level_journeyman", () -> levelled(2), b -> level(b));
        add("iron_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 2, b -> obtain(Items.IRON_PICKAXE, 1));
        add("shield", () -> Inv.has(Items.SHIELD), b -> obtain(Items.SHIELD, 1));
        add("iron_sword", () -> Inv.bestTier(SwordItem.class) >= 2, b -> obtain(Items.IRON_SWORD, 1));
        add("level_expert", () -> levelled(3), b -> level(b));
        add("iron_chestplate", () -> wearingOrHas(Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE), b -> obtain(Items.IRON_CHESTPLATE, 1));
        add("iron_helmet", () -> wearingOrHas(Items.IRON_HELMET, Items.DIAMOND_HELMET), b -> obtain(Items.IRON_HELMET, 1));
        add("iron_leggings", () -> wearingOrHas(Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS), b -> obtain(Items.IRON_LEGGINGS, 1));
        add("iron_boots", () -> wearingOrHas(Items.IRON_BOOTS, Items.DIAMOND_BOOTS), b -> obtain(Items.IRON_BOOTS, 1));
        add("poke_balls", () -> !BotConfig.get().catchPokemon || ItemTarget.parse("cobblemon:poke_ball", 1) == null
                        || Inv.find(CatchPokemonTask::isBall) >= 0,
                b -> new ObtainTask(ItemTarget.parse("cobblemon:poke_ball", 8)));
        add("level_master", () -> levelled(4), b -> level(b));
        add("diamond_pickaxe", () -> Inv.bestTier(PickaxeItem.class) >= 3, b -> obtain(Items.DIAMOND_PICKAXE, 1));
        add("level_grandmaster", () -> levelled(5), b -> level(b));
    }

    // ---- phases --------------------------------------------------------------------------

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

    private static int seedKinds() {
        java.util.Set<Item> k = new java.util.HashSet<>();
        for (int i = 0; i < 36; i++) if (plantable(Inv.inv().getItem(i))) k.add(Inv.inv().getItem(i).getItem());
        return k.size();
    }

    /**
     * Seeds: wild crops drop them (Farmer's Delight cabbage seeds and tomato seeds, Farm & Charm
     * barley, oat and lettuce seeds, wild potatoes and carrots plant themselves), and so does tall
     * grass (wheat seeds). Wild crops first: they're food too.
     */
    private static Task seeds(Bot bot) {
        List<net.minecraft.world.level.block.Block> wild = new ArrayList<>();
        for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (id.startsWith("wild_") && bot.seenNearby(b)) wild.add(b);
        }
        if (!wild.isEmpty()) return new GatherTask(wild, Progression::plantable, seedCount() + 6);
        return new GatherTask(List.of(net.minecraft.world.level.block.Blocks.SHORT_GRASS, net.minecraft.world.level.block.Blocks.TALL_GRASS),
                Progression::plantable, seedCount() + 6);
    }

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

    private static BlockPos stashChest(Bot bot) {
        if (bot.memory().home == null) return null;
        BlockPos home = bot.memory().home.pos();
        return bot.memory().chests.values().stream().filter(r -> r.ours && r.at != null && r.at.pos().closerThan(home, 32))
                .map(r -> r.at.pos()).findFirst().orElse(null);
    }

    /** The biggest pile of something we don't need on us: not food, tools, seeds or kept items. */
    private static Item surplus() {
        java.util.Map<Item, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = Inv.inv().getItem(i);
            if (st.isEmpty() || st.isDamageableItem() || Inv.isFood(st) || plantable(st) || st.is(ItemTags.BEDS)) continue;
            if (BotConfig.get().keepItems.contains(Inv.id(st.getItem()))) continue;
            counts.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        // keep a stack of scaffolding on us
        counts.computeIfPresent(Items.DIRT, (k, v) -> v > 64 ? v - 64 : null);
        counts.computeIfPresent(Items.COBBLESTONE, (k, v) -> v > 64 ? v - 64 : null);
        return counts.entrySet().stream().filter(e -> e.getValue() >= 8).max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    private static Task stash(Bot bot) {
        Item it = surplus();
        BlockPos chest = stashChest(bot);
        if (it == null || chest == null) return null;
        int keep = it == Items.DIRT || it == Items.COBBLESTONE ? 64 : 0;
        return new DepositTask(chest, ItemTarget.of(it, Inv.count(it) - keep));
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
        if (!foodSecure(bot)) return true; // skipped (not done) until the food phase is over
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
        ladder.add(new Milestone(id, done, make));
    }

    private static Task obtain(Item item, int n) {
        return new ObtainTask(ItemTarget.of(item, n));
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

    private static Task cook(Item raw) {
        Item cooked = switch (raw == null ? "" : Inv.id(raw)) {
            case "minecraft:beef" -> Items.COOKED_BEEF;
            case "minecraft:porkchop" -> Items.COOKED_PORKCHOP;
            case "minecraft:mutton" -> Items.COOKED_MUTTON;
            case "minecraft:chicken" -> Items.COOKED_CHICKEN;
            case "minecraft:rabbit" -> Items.COOKED_RABBIT;
            case "minecraft:cod" -> Items.COOKED_COD;
            case "minecraft:salmon" -> Items.COOKED_SALMON;
            default -> null;
        };
        if (cooked == null) return null;
        return new ObtainTask(ItemTarget.of(cooked, Inv.count(cooked) + Inv.count(raw)));
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
            String raw = Inv.id(meats[i]), done = Inv.id(cooked[i]);
            int have = Inv.count(meats[i]) + Inv.count(cooked[i]);
            int eaten = bot.memory().timesEatenRecently(raw) + bot.memory().timesEatenRecently(done);
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
     * campfire) and quiet work at home. No hunting, mining or long walks.
     */
    private static final java.util.Set<String> SAFE = java.util.Set.of("crafting_table", "rejoin", "wooden_sword", "furnace", "cook_meat",
            "wooden_hoe", "seeds", "farm", "share_seeds", "storage", "stash", "bed", "settle", "warm_bed");

    public Milestone next() {
        return next(false);
    }

    /** The next milestone, or with {@code safeOnly} the next one a fragile bot may do. */
    public Milestone next(boolean safeOnly) {
        long now = System.currentTimeMillis();
        for (Milestone m : ladder) {
            if (safeOnly && !SAFE.contains(m.id())) continue;
            if (m.done().getAsBoolean()) continue;
            Long until = snoozed.get(m.id());
            if (until != null && until > now) continue;
            // the server refused to let us craft it (a class gate) within the last hour: it's on
            // the board, and asking again on every restart just churns the crafting table
            Long refused = b().memory().refusals.get("craft:minecraft:" + m.id());
            if (refused != null && now - refused < 3600_000L) continue;
            return m;
        }
        return null;
    }

    public void snooze(String id, long millis) {
        snoozed.put(id, System.currentTimeMillis() + millis);
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Milestone m : ladder) {
            String s = m.done().getAsBoolean() ? "done" : snoozed.getOrDefault(m.id(), 0L) > now ? "snoozed" : "todo";
            out.add(m.id() + ":" + s);
        }
        return out;
    }
}
