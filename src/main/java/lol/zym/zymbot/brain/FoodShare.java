package lol.zym.zymbot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.inv.Diet;
import lol.zym.zymbot.inv.MealPlan;
import lol.zym.zymbot.team.TeamBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Food swaps between teammates. Under Spice of Fabric a pile of one food is worth little to the
 * bot carrying it (each repeat restores 0.7 of the last) and a lot to one that has none of it, so
 * a few loaves handed over can be worth more to the team than they were in our pack. Each bot tells
 * the teammates in range what food it carries and what it ate lately ({@code food_stock} and
 * {@code meals} in its state, or an "fd" line on the chat radio); we plan both packs with
 * {@link MealPlan} and give when they'd gain more than we'd lose. Every gift raises the team's
 * total, so the same food can't go back and forth.
 */
public final class FoodShare {
    private FoodShare() {}

    public record Gift(String to, String item, int count, double gain, double loss) {
        public double net() {
            return gain - loss;
        }
    }

    private static final int[] SIZES = {1, 2, 3, 4, 6, 8, 12, 16};
    /** Least the team must come out ahead to make the walk over worth it. */
    public static final double MIN_NET = 5;
    /** How far we walk to hand food over. */
    public static final int REACH = 48;
    /** Not the same teammate again for a while: what they told us is stale until they speak again. */
    private static final long PER_MATE_MS = 5 * 60_000L;
    private static final Map<String, Long> lastGift = new HashMap<>();

    /** Our food and meals for our state, ids as the game names them. */
    public static void publish(Bot bot, JsonObject state) {
        JsonObject stock = new JsonObject();
        Diet.foodCounts().forEach(stock::addProperty);
        state.add("food_stock", stock);
        JsonArray meals = new JsonArray();
        Diet.recentMeals(bot).forEach(meals::add);
        state.add("meals", meals);
    }

    /** The best gift to a teammate in reach now, or null if none is worth the walk. */
    public static Gift best(Bot bot) {
        Map<String, Integer> ours = Diet.foodCounts();
        if (ours.isEmpty()) return null;
        MealPlan ourPlan = Diet.planner(Diet.recentMeals(bot));
        double ourBase = ourPlan.quick(Diet.piles(ours));
        var me = bot.player();
        long now = System.currentTimeMillis();
        Gift best = null;
        for (TeamBus.Member m : bot.team().inRange()) {
            if (!m.state().has("food_stock") || !m.dim().equals(bot.dim())) continue;
            if (m.distanceTo(me.getX(), me.getY(), me.getZ()) > REACH) continue;
            if (now - lastGift.getOrDefault(key(m.name()), 0L) < PER_MATE_MS) continue;
            Map<String, Integer> theirs = stock(m.state());
            MealPlan theirPlan = Diet.planner(meals(m.state()));
            double theirBase = theirPlan.quick(Diet.piles(theirs));
            for (var e : ours.entrySet()) {
                if (Diet.describe(e.getKey()) == null) continue;
                for (int k : SIZES) {
                    if (k > e.getValue()) break;
                    double loss = ourBase - ourPlan.quick(Diet.piles(with(ours, e.getKey(), -k)));
                    double gain = theirPlan.quick(Diet.piles(with(theirs, e.getKey(), k))) - theirBase;
                    Gift g = new Gift(m.name(), e.getKey(), k, gain, loss);
                    if (g.net() >= MIN_NET && (best == null || g.net() > best.net())) best = g;
                }
            }
        }
        return best;
    }

    /** We're off to give it: leave this teammate be for a while. */
    public static void gave(Gift g) {
        lastGift.put(key(g.to()), System.currentTimeMillis());
    }

    private static Map<String, Integer> with(Map<String, Integer> counts, String id, int delta) {
        Map<String, Integer> out = new LinkedHashMap<>(counts);
        out.merge(id, delta, Integer::sum);
        return out;
    }

    private static Map<String, Integer> stock(JsonObject state) {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonElement s = state.get("food_stock");
        if (s != null && s.isJsonObject()) s.getAsJsonObject().entrySet().forEach(e -> out.put(fullId(e.getKey()), e.getValue().getAsInt()));
        return out;
    }

    private static List<String> meals(JsonObject state) {
        List<String> out = new ArrayList<>();
        JsonElement s = state.get("meals");
        if (s != null && s.isJsonArray()) s.getAsJsonArray().forEach(e -> out.add(fullId(e.getAsString())));
        return out;
    }

    /** "bread" as heard on the chat radio is "minecraft:bread". */
    public static String fullId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** "minecraft:bread" as said on the chat radio: "bread". */
    public static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
