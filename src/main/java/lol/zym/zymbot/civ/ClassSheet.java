package lol.zym.zymbot.civ;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads our own classes off CivFabric's {@code /class} screen: each class card is named after its
 * class and carries "XP: 1,234" in its lore; the specialisation row repeats the top N cards; and
 * once the drain is armed an off-class card says "costs 0.525 from each specialisation - 1.575 in
 * all", which gives the slot count. XP decides rank exactly, so gates stop being guesswork.
 */
public final class ClassSheet {
    private static final Pattern XP = Pattern.compile("^XP: ([\\d,.]+)");
    private static final Pattern COST_EACH = Pattern.compile("costs ([\\d.]+) from");
    private static final Pattern COST_ALL = Pattern.compile("- ([\\d.]+) in all");
    private static final long EVERY_MS = 10 * 60_000L;
    private static final String TITLE = "Your Classes";

    private long askedAt = -1;
    private long nextAsk = 20 * 15; // bot ticks: let the join settle first
    private int tries;

    /** Ask for a fresh read soon (after a rank change, a death). */
    public void refresh(Bot bot) {
        nextAsk = Math.min(nextAsk, bot.ticks() + 60);
    }

    public void tick(Bot bot) {
        if (!bot.civ().present()) return;
        Minecraft mc = Minecraft.getInstance();
        var p = bot.player();
        if (mc.screen instanceof AbstractContainerScreen<?> s && s.getTitle().getString().equals(TITLE)
                && p.containerMenu instanceof ChestMenu menu) {
            // the items arrive a tick or two after the screen opens
            if (bot.ticks() - askedAt > 3 && read(bot, menu)) {
                p.closeContainer();
                askedAt = -1;
                tries = 0;
                nextAsk = bot.ticks() + EVERY_MS / 50;
            } else if (bot.ticks() - askedAt > 60) {
                p.closeContainer();
                askedAt = -1;
            }
            return;
        }
        if (askedAt >= 0 && bot.ticks() - askedAt > 100) askedAt = -1; // the command didn't open anything
        if (askedAt < 0 && bot.ticks() >= nextAsk && p.containerMenu == p.inventoryMenu && mc.screen == null) {
            if (++tries > 3) {
                nextAsk = bot.ticks() + EVERY_MS / 50; // not this server's /class, or it isn't ours to open
                tries = 0;
                return;
            }
            askedAt = bot.ticks();
            nextAsk = bot.ticks() + 400;
            p.connection.sendCommand("class");
        }
    }

    private boolean read(Bot bot, ChestMenu menu) {
        Map<String, Double> xp = new LinkedHashMap<>();
        Map<String, Integer> cards = new HashMap<>();
        double each = -1, all = -1;
        int boardSlots = menu.getRowCount() * 9;
        for (int i = 0; i < boardSlots; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            String name = s.getHoverName().getString().toLowerCase(Locale.ROOT).trim();
            if (!CivRules.CLASSES.contains(name)) continue;
            var lore = s.get(DataComponents.LORE);
            if (lore == null) continue;
            Double value = null;
            for (Component line : lore.lines()) {
                String t = line.getString().trim();
                Matcher m = XP.matcher(t);
                if (m.find()) value = Double.parseDouble(m.group(1).replace(",", ""));
                Matcher e = COST_EACH.matcher(t);
                if (e.find()) each = Double.parseDouble(e.group(1));
                Matcher a = COST_ALL.matcher(t);
                if (a.find()) all = Double.parseDouble(a.group(1));
            }
            if (value == null) continue;
            xp.put(name, value);
            cards.merge(name, 1, Integer::sum);
        }
        if (xp.isEmpty()) return false;
        var mem = bot.memory();
        Map<String, Double> before = new HashMap<>(mem.classXp);
        mem.classXp.clear();
        mem.classXp.putAll(xp);
        mem.classReadAt = System.currentTimeMillis();
        // the specialisation row repeats the top N cards once there's any XP
        int repeats = cards.values().stream().mapToInt(c -> c - 1).sum();
        if (each > 0 && all > 0) mem.classSlots = (int) Math.round(all / each);
        else if (repeats > 0) mem.classSlots = repeats;
        long armed = xp.values().stream().filter(v -> v >= ClassBook.DRAIN_ARMS_AT).count();
        mem.drainArmed = armed >= 2;
        for (var e : xp.entrySet()) {
            int was = ClassBook.rankOf(before.getOrDefault(e.getKey(), 0.0)), now = ClassBook.rankOf(e.getValue());
            if (now != was && before.containsKey(e.getKey())) bot.event(e.getKey() + " is now " + CivRules.RANKS.get(now));
        }
        // ranks are known exactly now; a refusal-based guess would only be in the way
        mem.rankBelow.clear();
        mem.save();
        return true;
    }

    /** Slots per player: the config's, else what the server showed, else 3 (the current default). */
    public static int slots(Bot bot) {
        int cfg = BotConfig.get().classSlots;
        if (cfg > 0) return cfg;
        Integer seen = bot.memory().classSlots;
        return seen != null && seen > 0 ? seen : 3;
    }

    /** Our XP in a class, or -1 if the screen hasn't been read yet. */
    public static double xp(Bot bot, String clazz) {
        if (bot.memory().classXp.isEmpty()) return -1;
        return bot.memory().classXp.getOrDefault(clazz, 0.0);
    }

    /** Still classless: no class at Apprentice, so CivFabric's free crafts apply. */
    public static boolean classless(Bot bot) {
        return bot.memory().classXp.values().stream().noneMatch(v -> v >= ClassBook.RANK_XP[0]);
    }
}
