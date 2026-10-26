package lol.zym.zymbot.inv;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.ZymBotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spice of Fabric's diet rules: every time a food shows up in the last {@code history-length}
 * meals, eating it again restores less ({@code hungerValue * 0.7^timesEaten}) and takes longer
 * ({@code consumeDuration * 1.3^timesEaten}). Read from the instance's spiceoffabric.hjson when
 * the mod is there; without the mod, food is worth what vanilla says.
 */
public final class Diet {
    private Diet() {}

    private static boolean loaded;
    private static boolean active;
    private static int history = 20;
    private static double hungerBase = 0.7;
    private static double durationBase = 1.3;

    private static void load() {
        if (loaded) return;
        loaded = true;
        active = FabricLoader.getInstance().isModLoaded("spiceoffabric");
        if (!active) return;
        Path f = FabricLoader.getInstance().getConfigDir().resolve("spiceoffabric.hjson");
        try {
            if (Files.isRegularFile(f)) {
                String s = Files.readString(f);
                Matcher m = Pattern.compile("(?m)^\\s*history-length:\\s*(\\d+)").matcher(s);
                if (m.find()) history = Integer.parseInt(m.group(1));
                m = Pattern.compile("(?m)^\\s*hunger:\\s*\"hungerValue \\* power\\(([\\d.]+), timesEaten\\)\"").matcher(s);
                if (m.find()) hungerBase = Double.parseDouble(m.group(1));
                m = Pattern.compile("(?m)^\\s*consume-duration:\\s*\"consumeDuration \\* power\\(([\\d.]+), timesEaten\\)\"").matcher(s);
                if (m.find()) durationBase = Double.parseDouble(m.group(1));
            }
        } catch (Exception e) {
            ZymBotMod.LOG.warn("Could not read spiceoffabric.hjson; using its defaults", e);
        }
        ZymBotMod.LOG.info("Diet rules: last {} meals, {}x hunger and {}x eating time per repeat", history, hungerBase, durationBase);
    }

    /** How many past meals Spice of Fabric remembers. */
    public static int history() {
        load();
        return history;
    }

    /** Hunger this food restores now, given what we ate lately: the mod rounds it to whole points. */
    public static double nutrition(Bot bot, ItemStack s) {
        FoodProperties f = Inv.food(s);
        if (f == null) return 0;
        load();
        if (!active) return f.nutrition();
        return worth(baseHunger(s), timesEaten(bot, s));
    }

    /**
     * What the next {@code count} of this food restore, eaten one after another from now: Spice of
     * Fabric counts each as eaten for the next, so the fourth bread in a row fills less than the first.
     */
    public static double nutritionOfNext(Bot bot, ItemStack s, int count) {
        FoodProperties f = Inv.food(s);
        if (f == null || count <= 0) return 0;
        load();
        if (!active) return (double) f.nutrition() * count;
        int base = baseHunger(s), times = timesEaten(bot, s);
        double sum = 0;
        for (int k = 0; k < count; k++) sum += worth(base, times + k);
        return sum;
    }

    /**
     * The game's own hunger value. Spice of Fabric (through Capsaicin) hands out a stack's food
     * already cut for repeats, so cutting that again counted a twice-eaten food as worth nothing.
     */
    public static int baseHunger(ItemStack s) {
        FoodProperties own = s.getItem().components().get(net.minecraft.core.component.DataComponents.FOOD);
        FoodProperties f = own != null ? own : Inv.food(s);
        return f == null ? 0 : f.nutrition();
    }

    /** Spice of Fabric's {@code max(0, round(hungerValue * base^timesEaten))}. */
    private static double worth(int hunger, int times) {
        return Math.max(0, Math.round(hunger * Math.pow(hungerBase, times)));
    }

    /** How much longer than normal this food takes to eat now. */
    public static double slowdown(Bot bot, ItemStack s) {
        load();
        if (!active) return 1;
        return Math.pow(durationBase, timesEaten(bot, s));
    }

    private static java.lang.reflect.Method historyOf, timesRecently;
    private static boolean reflectFailed;

    /**
     * Times this food is in the last meals, as the server counts them: Spice of Fabric syncs the
     * player's history to the client, so read it there. Our own record is only a fallback: it starts
     * empty on every server and misses anything not eaten through EatTask (a bot that thought a potato
     * was fresh ate one worth nothing).
     */
    public static int timesEaten(Bot bot, ItemStack s) {
        if (!reflectFailed) {
            try {
                if (historyOf == null) {
                    Class<?> fh = Class.forName("de.siphalor.spiceoffabric.foodhistory.FoodHistory");
                    historyOf = fh.getMethod("get", net.minecraft.world.entity.player.Player.class);
                    timesRecently = fh.getMethod("getTimesRecentlyEaten", ItemStack.class);
                }
                Object h = historyOf.invoke(null, bot.player());
                if (h != null) return (Integer) timesRecently.invoke(h, s);
            } catch (ReflectiveOperationException | RuntimeException e) {
                reflectFailed = true;
                ZymBotMod.LOG.warn("Can't read Spice of Fabric's food history; counting meals ourselves", e);
            }
        }
        return bot.memory().timesEatenRecently(Inv.id(s.getItem()));
    }

    /** The cooked food a raw one becomes, or the stack itself. */
    public static ItemStack asEaten(ItemStack s) {
        var cooked = s.is(Items.BEEF) ? Items.COOKED_BEEF : s.is(Items.PORKCHOP) ? Items.COOKED_PORKCHOP
                : s.is(Items.MUTTON) ? Items.COOKED_MUTTON : s.is(Items.CHICKEN) ? Items.COOKED_CHICKEN
                : s.is(Items.RABBIT) ? Items.COOKED_RABBIT : s.is(Items.COD) ? Items.COOKED_COD
                : s.is(Items.SALMON) ? Items.COOKED_SALMON : s.is(Items.POTATO) ? Items.BAKED_POTATO : null;
        return cooked == null ? s : new ItemStack(cooked, s.getCount());
    }

    /**
     * The food ids of this player's last meals, oldest first, as the server counts them (Spice of
     * Fabric syncs them to the client); our own record when that can't be read.
     */
    public static List<String> recentMeals(Bot bot) {
        load();
        if (!reflectFailed) {
            try {
                if (historyOf == null) {
                    Class<?> fh = Class.forName("de.siphalor.spiceoffabric.foodhistory.FoodHistory");
                    historyOf = fh.getMethod("get", net.minecraft.world.entity.player.Player.class);
                    timesRecently = fh.getMethod("getTimesRecentlyEaten", ItemStack.class);
                }
                if (mealCount == null) {
                    mealCount = historyOf.getDeclaringClass().getMethod("getRecentlyEatenCount");
                    mealAt = historyOf.getDeclaringClass().getMethod("getStackFromRecentlyEaten", int.class);
                }
                Object h = historyOf.invoke(null, bot.player());
                if (h != null) {
                    int n = (Integer) mealCount.invoke(h);
                    List<String> out = new ArrayList<>(n);
                    // index 0 is the head of Spice of Fabric's queue: the oldest meal
                    for (int i = 0; i < n; i++) {
                        Object s = mealAt.invoke(h, i);
                        if (s instanceof ItemStack st && !st.isEmpty()) out.add(Inv.id(st.getItem()));
                    }
                    return out;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                reflectFailed = true;
                ZymBotMod.LOG.warn("Can't read Spice of Fabric's food history; counting meals ourselves", e);
            }
        }
        return new ArrayList<>(bot.memory().recent_foods);
    }

    private static java.lang.reflect.Method mealCount, mealAt;

    /** The planner for a player who ate {@code meals} last (oldest first); food at face value without the mod. */
    public static MealPlan planner(List<String> meals) {
        load();
        return active ? new MealPlan(meals, history, hungerBase) : new MealPlan(List.of(), 0, 1);
    }

    /**
     * The ways to eat an item: as it is (unless it does harm, like raw chicken) and as what it cooks
     * into. Null for anything that isn't a food worth counting.
     */
    public static MealPlan.Pile describe(String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        var item = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null) return null;
        ItemStack s = new ItemStack(item);
        if (Inv.food(s) == null) return null;
        List<MealPlan.Form> forms = new ArrayList<>();
        MealPlan.Form now = Inv.riskyFood(s) ? null : new MealPlan.Form(Inv.id(item), baseHunger(s));
        if (now != null) forms.add(now);
        ItemStack cooked = asEaten(s);
        if (cooked.getItem() != item && Inv.food(cooked) != null && !Inv.riskyFood(cooked))
            forms.add(new MealPlan.Form(Inv.id(cooked.getItem()), baseHunger(cooked)));
        return forms.isEmpty() ? null : new MealPlan.Pile(Inv.id(item), 1, now, forms);
    }

    /** The food we carry, id -> count. */
    public static Map<String, Integer> foodCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (Inv.food(s) != null) out.merge(Inv.id(s.getItem()), s.getCount(), Integer::sum);
        }
        return out;
    }

    /** Piles for a pack of ids and counts. */
    public static List<MealPlan.Pile> piles(Map<String, Integer> counts) {
        return MealPlan.piles(counts, Diet::describe);
    }

    private static String cachedFor;
    private static double cachedValue;

    /**
     * What the food we carry is really worth: everything eaten in the best order, going round the
     * foods so repeats cost as little as they can, each raw food eaten raw or cooked, whichever pays.
     * A stack of one food is worth little and a mix of foods a lot.
     */
    public static double stockValue(Bot bot) {
        Map<String, Integer> counts = foodCounts();
        List<String> meals = recentMeals(bot);
        String key = counts + "|" + meals;
        if (key.equals(cachedFor)) return cachedValue;
        cachedFor = key;
        cachedValue = planner(meals).total(piles(counts));
        return cachedValue;
    }

    private static String firstFor;
    private static Map<String, Double> firstPlan;

    /** For each food we carry that's edible as it is: what the pack restores eating it first. */
    public static Map<String, Double> ifEatenFirst(Bot bot) {
        // asked every tick while hungry: plan again only when the pack or the meals change
        Map<String, Integer> counts = foodCounts();
        List<String> meals = recentMeals(bot);
        String key = counts + "|" + meals;
        if (!key.equals(firstFor)) {
            firstFor = key;
            firstPlan = planner(meals).ifFirst(piles(counts));
        }
        return firstPlan;
    }

    /** Different safe foods we carry. */
    public static int kinds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = asEaten(Inv.inv().getItem(i));
            if (Inv.food(s) != null && !Inv.riskyFood(s)) ids.add(Inv.id(s.getItem()));
        }
        return ids.size();
    }

    public static boolean active() {
        load();
        return active;
    }
}
