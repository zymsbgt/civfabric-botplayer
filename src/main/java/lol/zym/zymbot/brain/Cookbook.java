package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.tasks.ObtainTask;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Diet;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.ChestIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Food we can make right now from what we already have: the ingredients on us and in our chests,
 * with no gathering or hunting for the rest. Bread from the wheat sitting in a chest beats a walk
 * out to hunt, and the bots went hungry beside two chests of wheat before this. The best meal is
 * the one worth most to eat now (Spice of Fabric cuts what we've eaten lately).
 */
public final class Cookbook {
    /** How far down a meal's chain we look: rabbit stew &lt;- bowl &lt;- planks &lt;- logs. */
    private static final int DEPTH = 3;
    /** As far as the planner fetches from a chest ({@code Planner} FETCH). */
    private static final double CHEST_RANGE = 64;
    /** Crafts of one meal per round: enough to eat, not the whole chest at once. */
    private static final int MAX_CRAFTS = 4;

    private Cookbook() {}

    /** {@code count} of {@code item} to make, and the hunger it gains us over eating what goes into it as it is. */
    public record Meal(Item item, int count, double gain) {}

    /**
     * The meal to make now, or null when nothing on hand makes one worth it. Each is scored by what
     * it fills after Spice of Fabric's cut for repeats, less what its ingredients would fill eaten as
     * they are (a carrot and a baked potato are food already) and the hunger CivFabric charges for
     * the craft. {@code chests}: count what's in our chests nearby too, not just what's on us.
     */
    public static Meal best(Bot bot, boolean chests) {
        var all = options(bot, chests);
        return all.isEmpty() || all.get(0).gain() <= 0 ? null : all.get(0);
    }

    /** Every meal we could make now, each at its best count, best gain first (losing ones too: the "meals" command). */
    public static java.util.List<Meal> options(Bot bot, boolean chests) {
        Map<Item, Integer> stock = stock(bot, chests);
        int foodLevel = bot.player().getFoodData().getFoodLevel();
        java.util.List<Meal> out = new java.util.ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) {
            ItemStack s = new ItemStack(it);
            if (!Inv.isFood(s) || Inv.riskyFood(s)) continue;
            if (Recipes.craftingFor(it).isEmpty() && (Recipes.smeltingFor(it).isEmpty() || !Inv.has(Items.FURNACE))) continue;
            // CivFabric charges a craft in hunger and refuses one that leaves us below 4
            // (and the hunger of every craft on the way, the bowl's too)
            int made = 0;
            double[] spent = {0};
            Meal best = null;
            Map<Item, Integer> left = new HashMap<>(stock);
            for (int c = 0; c < MAX_CRAFTS; c++) {
                Map<Item, Integer> trial = new HashMap<>(left);
                double[] trialSpent = {spent[0]};
                int got = make(bot, it, trial, 0, trialSpent);
                if (got == 0 || bot.civ().present() && foodLevel - trialSpent[0] < 4) break;
                left = trial;
                spent[0] = trialSpent[0];
                made += got;
                // more of the same fills less each time: keep the count that gains most
                double gain = Diet.nutritionOfNext(bot, s, made) - asTheyAre(bot, stock, left) - spent[0];
                if (best == null || gain > best.gain()) best = new Meal(it, made, gain);
            }
            if (best != null) out.add(best);
        }
        out.sort(java.util.Comparator.comparingDouble(m -> -m.gain()));
        return out;
    }

    /** Why we can or can't make {@code item} now ("meals rabbit_stew"): each step's gate, in order. */
    public static String explain(Bot bot, Item item) {
        String id = Inv.id(item);
        StringBuilder sb = new StringBuilder(id.replace("minecraft:", "")).append(": ");
        explainCraft(bot, item, sb, 0, new java.util.HashSet<>());
        int food = bot.player().getFoodData().getFoodLevel();
        double[] spent = {0};
        int got = make(bot, item, stock(bot, true), 0, spent);
        sb.append(got == 0 ? " => can't be made from what we have" : " => makes " + got + ", costing " + String.format(java.util.Locale.ROOT, "%.1f", spent[0]) + " hunger (food " + food + "; CivFabric wants 4 left)");
        return sb.toString();
    }

    private static void explainCraft(Bot bot, Item item, StringBuilder sb, int depth, java.util.Set<Item> seen) {
        if (depth > DEPTH || !seen.add(item)) return;
        String id = Inv.id(item);
        var recipes = Recipes.craftingFor(item);
        if (recipes.isEmpty()) return;
        if (!bot.civ().canCraft(bot.memory(), id)) sb.append("[").append(id.replace("minecraft:", "")).append(" gated: ").append(bot.civ().craftNeeds(bot.memory(), id)).append("] ");
        for (var h : recipes) {
            if (!bot.civ().canCraft(bot.memory(), h.id().toString()))
                sb.append("[recipe ").append(h.id()).append(" gated: ").append(bot.civ().craftNeeds(bot.memory(), h.id().toString())).append("] ");
            for (Recipes.Need n : Recipes.needs(h.value())) {
                var cands = Recipes.candidates(n.ingredient());
                if (cands.isEmpty()) continue;
                Item first = cands.get(0);
                if (Inv.count(first) < n.perCraft()) explainCraft(bot, first, sb, depth + 1, seen);
            }
        }
        sb.append(id.replace("minecraft:", "")).append(" costs ").append(bot.planner().hunger(item)).append(" hunger; ");
    }

    /** The meals command: what we could make and what each gains over eating its makings as they are. */
    public static String describe(Bot bot) {
        var all = options(bot, true);
        if (all.isEmpty()) return "nothing I could cook from what's on me and in our chests";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, all.size()); i++) {
            Meal m = all.get(i);
            if (i > 0) sb.append("; ");
            sb.append(m.count()).append(" ").append(Inv.id(m.item()).replace("minecraft:", ""))
                    .append(String.format(java.util.Locale.ROOT, " %+.0f", m.gain()));
        }
        return sb.append(" (hunger gained over eating the makings as they are)").toString();
    }

    /** What the ingredients a meal used up ({@code before} less {@code after}) would fill eaten as they are. */
    private static double asTheyAre(Bot bot, Map<Item, Integer> before, Map<Item, Integer> after) {
        double sum = 0;
        for (var e : before.entrySet()) {
            int used = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (used <= 0) continue;
            ItemStack s = new ItemStack(e.getKey());
            if (Inv.isFood(s) && !Inv.riskyFood(s)) sum += Diet.nutritionOfNext(bot, s, used);
        }
        return sum;
    }

    /**
     * A job making the best meal, or null: when it gains at least {@code minGain} hunger over eating
     * the ingredients as they are. Hungry with nothing to eat, anything goes and our chests count;
     * with food on us, only what we carry, and only for a real gain (no walk while we could eat).
     */
    public static ObtainTask task(Bot bot, boolean chests, double minGain) {
        return task(bot, best(bot, chests), minGain);
    }

    /** A job making {@code m} (from {@link #best}), or null if it gains less than {@code minGain}. */
    public static ObtainTask task(Bot bot, Meal m, double minGain) {
        if (m == null || m.gain() < minGain) return null;
        bot.event(String.format(java.util.Locale.ROOT, "hungry: making %d %s from what we have (%.0f more hunger than eating it all as it is)",
                m.count(), Inv.id(m.item()).replace("minecraft:", ""), m.gain()));
        return new ObtainTask(ItemTarget.of(m.item(), Inv.count(m.item()) + m.count())).makeIt();
    }

    /** What's on us and in the chests we may take from nearby. */
    private static Map<Item, Integer> stock(Bot bot, boolean chests) {
        Map<Item, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (!chests) return out;
        var me = bot.player().blockPosition();
        for (var r : bot.memory().chests.values()) {
            if (r.at == null || !Objects.equals(r.at.dim, bot.dim()) || !ChestIndex.takeable(r)) continue;
            if (r.at.pos().distSqr(me) > CHEST_RANGE * CHEST_RANGE) continue;
            for (var e : r.items.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(ChestIndex.fullId(e.getKey()));
                if (id == null || e.getValue() <= 0 || !BuiltInRegistries.ITEM.containsKey(id)) continue;
                out.merge(BuiltInRegistries.ITEM.get(id), e.getValue(), Integer::sum);
            }
        }
        return out;
    }

    /**
     * One craft (or smelt) of {@code item} out of {@code stock}, taking what it uses: how many it
     * makes, or 0 (and {@code stock} unspecified) if it can't be made from what's there.
     */
    private static int make(Bot bot, Item item, Map<Item, Integer> stock, int depth, double[] spent) {
        if (depth > DEPTH) return 0;
        String itemId = Inv.id(item);
        for (var h : Recipes.craftingFor(item)) {
            if (!bot.civ().canCraft(bot.memory(), h.id().toString()) || !bot.civ().canCraft(bot.memory(), itemId)) continue;
            Map<Item, Integer> trial = new HashMap<>(stock);
            double[] trialSpent = {spent[0] + bot.planner().hunger(item)};
            boolean ok = true;
            for (Recipes.Need n : Recipes.needs(h.value())) {
                if (!take(bot, n.ingredient(), n.perCraft(), trial, depth + 1, trialSpent)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                stock.clear();
                stock.putAll(trial);
                spent[0] = trialSpent[0];
                return Math.max(1, Recipes.result(h).getCount());
            }
        }
        if (Inv.has(Items.FURNACE)) {
            for (var h : Recipes.smeltingFor(item)) {
                Map<Item, Integer> trial = new HashMap<>(stock);
                double[] trialSpent = {spent[0]};
                if (take(bot, h.value().getIngredients().get(0), 1, trial, depth + 1, trialSpent)) {
                    stock.clear();
                    stock.putAll(trial);
                    spent[0] = trialSpent[0];
                    return 1;
                }
            }
        }
        return 0;
    }

    /** Take {@code n} of an ingredient from {@code stock}, making what's short where we can. */
    private static boolean take(Bot bot, net.minecraft.world.item.crafting.Ingredient ing, int n, Map<Item, Integer> stock, int depth, double[] spent) {
        for (Item c : Recipes.candidates(ing)) {
            Map<Item, Integer> trial = new HashMap<>(stock);
            double[] trialSpent = {spent[0]};
            int need = n;
            int have = trial.getOrDefault(c, 0);
            int used = Math.min(have, need);
            trial.put(c, have - used);
            need -= used;
            // short: make more of it from the stock (planks from a log), a few crafts at most
            for (int tries = 0; need > 0 && tries < 4; tries++) {
                double before = trialSpent[0];
                int got = make(bot, c, trial, depth, trialSpent);
                if (got == 0) break;
                int use = Math.min(got, need);
                // a craft makes more than we use (4 bowls, 4 planks): the rest is still ours, so this
                // meal pays only for its share of that craft's hunger
                trialSpent[0] = before + (trialSpent[0] - before) * use / got;
                trial.merge(c, got - use, Integer::sum);
                need -= use;
            }
            if (need <= 0) {
                stock.clear();
                stock.putAll(trial);
                spent[0] = trialSpent[0];
                return true;
            }
        }
        return false;
    }
}
