package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Planner;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Get {@code count} of something by whatever means the planner thinks cheapest, recursing into
 * ingredients. Re-plans every time a step finishes, so lost or extra items are handled
 * naturally. Methods that fail are excluded and the next best is tried.
 */
public class ObtainTask extends Task {
    private final ItemTarget target;
    private final Set<String> failedMethods = new HashSet<>();
    private int failures;
    private String lastMethod;
    private boolean triedShear;
    private int depth;

    /** Items the tasks above us are already trying to make; never plan a route through them. */
    private final Set<Item> ancestors;

    public ObtainTask(ItemTarget target) {
        this(target, Set.of());
    }

    public ObtainTask(ItemTarget target, Set<Item> parentAncestors) {
        this.target = target;
        Set<Item> a = new HashSet<>(parentAncestors);
        a.addAll(target.candidates());
        this.ancestors = a;
    }

    /** Make it, rather than fetching one from a chest or the ground (levelling a class wants the craft). */
    public ObtainTask makeIt() {
        for (Item c : target.candidates()) {
            failedMethods.add("FETCH " + Inv.id(c));
            failedMethods.add("PICKUP " + Inv.id(c));
        }
        return this;
    }

    /**
     * Only by smelting (levelling a smelting class wants the furnace to pay): otherwise coal comes
     * the cheap way, out of the ore with a pickaxe, and the XP goes to the Miner instead.
     */
    public ObtainTask smeltIt() {
        for (Item c : target.candidates()) failedMethods.addAll(notSmelting(c));
        return this;
    }

    /** Every top-level method for {@code item} but smelting, as {@link Planner#best} exclusions. */
    public static Set<String> notSmelting(Item item) {
        Set<String> out = new HashSet<>();
        for (Planner.Kind k : Planner.Kind.values()) if (k != Planner.Kind.SMELT) out.add(k + " " + Inv.id(item));
        return out;
    }

    private ObtainTask sub(ItemTarget t) {
        ObtainTask o = new ObtainTask(t, ancestors);
        o.depth = depth + 1;
        return o;
    }

    public ItemTarget target() {
        return target;
    }

    @Override
    public String name() {
        return "Obtain " + target.have() + "/" + target;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (target.satisfied()) return Status.SUCCESS;
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.FAILURE) {
                bot.event(target.name() + ": " + lastMethod + " failed — " + failureReason());
                if (lastMethod != null) failedMethods.add(lastMethod);
                if (++failures > 6) return fail("too many failed attempts (" + failureReason() + ")");
            }
            return Status.RUNNING;
        }

        // wool: shear for it before thinking about string or killing sheep
        if (!triedShear && target.candidates().stream().anyMatch(c -> new net.minecraft.world.item.ItemStack(c).is(net.minecraft.tags.ItemTags.WOOL))) {
            triedShear = true;
            lastMethod = "SHEAR wool";
            setChild(bot, new ShearTask(target.count()));
            return Status.RUNNING;
        }
        // depth, not the ancestor count: one "any log" ingredient alone brings 40+ candidates
        if (depth > 10) return fail("plan too deep for " + target.name());
        Planner planner = bot.planner();
        Planner.Method best = null;
        for (Item c : target.candidates()) {
            Planner.Method m = planner.best(c, failedMethods, ancestors);
            if (m == null) continue;
            if (best == null || m.cost() < best.cost()) best = m;
        }
        if (best == null || best.cost() >= Planner.INF) {
            String why = target.candidates().isEmpty() ? null : planner.gate(target.candidates().get(0));
            return fail(why != null ? "needs " + why : "no known way to get " + target.name());
        }

        int missing = target.count() - target.have();
        Task next = switch (best.kind()) {
            case HAVE -> null;
            case GATHER -> gatherStep(bot, best, missing);
            case HUNT -> new HuntTask(best.mobs(), target.match(), target.count()).orPokemonFor(target.match());
            case FETCH -> new FetchTask(best.at(), target.withCount(target.count()));
            case PICKUP -> new PickupTask(best.at(), 4);
            case CRAFT -> craftStep(bot, best, missing);
            case SMELT -> smeltStep(bot, best, missing);
        };
        if (next == null) {
            failedMethods.add(key(best));
            return Status.RUNNING;
        }
        lastMethod = key(best);
        setChild(bot, next);
        return Status.RUNNING;
    }

    private static String key(Planner.Method m) {
        return m.kind() + " " + Inv.id(m.item());
    }

    private Task gatherStep(Bot bot, Planner.Method m, int missing) {
        // need a tool first?
        var state = m.blocks().get(0).defaultBlockState();
        boolean anyHarvestable = m.blocks().stream().anyMatch(b -> Inv.canHarvest(b.defaultBlockState()));
        if (!anyHarvestable) {
            Item tool = bot.planner().toolFor(state);
            if (tool == null) return null;
            return sub(ItemTarget.of(tool, 1));
        }
        return new GatherTask(m.blocks(), target.match(), target.have() + missing);
    }

    private Task craftStep(Bot bot, Planner.Method m, int missing) {
        var recipe = m.craft();
        int per = Math.max(1, Recipes.result(recipe).getCount());
        int crafts = (missing + per - 1) / per;
        for (Recipes.Need n : Recipes.needs(recipe.value())) {
            int need = n.perCraft() * crafts;
            if (n.have() < need) return sub(ItemTarget.of(n.ingredient(), need));
        }
        if (!Recipes.fits2x2(recipe.value()) && !Inv.has(Items.CRAFTING_TABLE) && !bot.hasStationNearby("crafting_table")) {
            return sub(ItemTarget.of(Items.CRAFTING_TABLE, 1));
        }
        return new CraftTask(recipe, crafts);
    }

    private Task smeltStep(Bot bot, Planner.Method m, int missing) {
        var recipe = m.smelt();
        Ingredient in = recipe.value().getIngredients().get(0);
        int haveIn = Inv.count(in::test);
        if (haveIn < missing) return sub(ItemTarget.of(in, missing));
        if (!Inv.has(Items.FURNACE) && !bot.hasStationNearby("furnace")) return sub(ItemTarget.of(Items.FURNACE, 1));
        ItemTarget fuel = fuelFor(bot, in, missing);
        if (fuel != null) return sub(fuel);
        return new SmeltTask(recipe, missing);
    }

    /** What to fetch to smelt {@code n} items, or null if the fuel we carry will do. */
    public static ItemTarget fuelFor(Bot bot, Ingredient in, int n) {
        int fuelTicks = 0;
        for (int i = 0; i < 36; i++) {
            var s = Inv.inv().getItem(i);
            if (SmeltTask.isPlainFuel(s) && !in.test(s)) {
                fuelTicks += AbstractFurnaceBlockEntity.getFuel().getOrDefault(s.getItem(), 0) * s.getCount();
            }
        }
        if (fuelTicks >= n * 200) return null;
        // charcoal/coal preferred; planks are the fallback everyone has
        return Inv.count(Items.COAL) > 0 || bot.planner().cost(Items.COAL) < 20
                ? ItemTarget.of(Items.COAL, Inv.count(Items.COAL) + (n + 7) / 8)
                : new ItemTarget("planks", s -> s.is(net.minecraft.tags.ItemTags.PLANKS),
                java.util.List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS),
                Inv.count(s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) + (n * 2 + 2) / 3);
    }
}
