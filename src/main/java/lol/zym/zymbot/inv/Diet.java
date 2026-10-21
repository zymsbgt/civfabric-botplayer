package lol.zym.zymbot.inv;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.ZymBotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
        return worth(f.nutrition(), timesEaten(bot, s));
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

    /**
     * What the food we carry is really worth: each extra item of a kind counts as one more
     * repeat, so a stack of one food is worth little and a mix of foods a lot.
     */
    /**
     * Raw meat as the food it will be once cooked. Stock is about what we'll eat, and raw meat
     * under Spice of Fabric's repeat penalty is worth too little to ever add up to a stock.
     */
    public static ItemStack asEaten(ItemStack s) {
        var cooked = s.is(Items.BEEF) ? Items.COOKED_BEEF : s.is(Items.PORKCHOP) ? Items.COOKED_PORKCHOP
                : s.is(Items.MUTTON) ? Items.COOKED_MUTTON : s.is(Items.CHICKEN) ? Items.COOKED_CHICKEN
                : s.is(Items.RABBIT) ? Items.COOKED_RABBIT : s.is(Items.COD) ? Items.COOKED_COD
                : s.is(Items.SALMON) ? Items.COOKED_SALMON : s.is(Items.POTATO) ? Items.BAKED_POTATO : null;
        return cooked == null ? s : new ItemStack(cooked, s.getCount());
    }

    public static double stockValue(Bot bot) {
        load();
        double total = 0;
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = asEaten(Inv.inv().getItem(i));
            FoodProperties f = Inv.food(s);
            if (f == null || Inv.riskyFood(s)) continue;
            String id = Inv.id(s.getItem());
            int past = active ? timesEaten(bot, s) : 0;
            for (int n = 0; n < s.getCount(); n++) {
                int k = seen.merge(id, 1, Integer::sum) - 1;
                total += active ? worth(f.nutrition(), Math.min(past + k, history)) : f.nutrition();
            }
        }
        return total;
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
