package lol.zym.zymbot.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Every container the bot has looked inside, and what was in it: a per-player storage index,
 * kept in memory across restarts and published on the hub. Crafting costs food in CivFabric, so
 * "is one sitting in a chest nearby?" comes before "make one".
 *
 * <p>The client can't see inside a chest without opening it, so the index is what the bot saw
 * last time; a fetch that finds less corrects it. Which chests may be taken from is decided here:
 * our own, team depots, and Lootr chests (per-player loot) yes; a village chest near villagers
 * (that turns the village hostile) or one by someone else's build, never.
 */
public final class ChestIndex {
    private static BlockPos lastUsed;
    private static long lastUsedTick;

    private ChestIndex() {}

    /** {@link lol.zym.zymbot.control.Placer#use} tells us which block a screen opening belongs to. */
    public static void used(BlockPos pos, long tick) {
        lastUsed = pos.immutable();
        lastUsedTick = tick;
    }

    private static String key(String dim, BlockPos p) {
        return dim + " " + p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Called every tick: while a container we opened is showing, keep its record current. */
    public static void tick(Bot bot) {
        var p = bot.player();
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu || lastUsed == null || bot.ticks() - lastUsedTick > 20 * 60) return;
        int size;
        if (menu instanceof ChestMenu c) size = c.getRowCount() * 9;
        else if (menu instanceof ShulkerBoxMenu) size = 27;
        else return;
        var screen = Minecraft.getInstance().screen;
        if (screen != null && screen.getTitle().getString().equals("Your Classes")) return;
        if (bot.ticks() % 5 != 0) return;
        record(bot, lastUsed, menu, size);
    }

    private static void record(Bot bot, BlockPos pos, AbstractContainerMenu menu, int size) {
        var mem = bot.memory();
        String k = key(bot.dim(), pos);
        BotMemory.ChestRecord r = mem.chests.computeIfAbsent(k, x -> new BotMemory.ChestRecord());
        boolean fresh = r.at == null;
        r.at = new BotMemory.Pos(pos, bot.dim());
        r.items.clear();
        r.free = 0;
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) r.items.merge(Inv.id(s.getItem()), s.getCount(), Integer::sum);
            else r.free++;
        }
        r.seenMillis = System.currentTimeMillis();
        r.touchedBy = null;
        String block = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(pos).getBlock()).toString();
        r.lootr = block.startsWith("lootr:");
        r.ours = r.ours || mem.stations.stream().anyMatch(st -> st.ours && st.at.pos().equals(pos))
                || bot.team().boards().stream().anyMatch(b -> b.has("chest") && b.get("chest").isJsonObject()
                && b.getAsJsonObject("chest").get("x").getAsInt() == pos.getX()
                && b.getAsJsonObject("chest").get("y").getAsInt() == pos.getY()
                && b.getAsJsonObject("chest").get("z").getAsInt() == pos.getZ());
        if (fresh) {
            boolean villagers = !bot.level().getEntitiesOfClass(Villager.class, new AABB(pos).inflate(16)).isEmpty();
            r.offLimits = !r.ours && !r.lootr && (villagers || bot.nearSomeoneElsesBuild(pos));
            bot.event("indexed a " + block.replace("minecraft:", "") + " at " + pos.toShortString() + " (" + r.items.size()
                    + " kinds" + (r.ours ? ", ours" : r.lootr ? ", lootr" : r.offLimits ? ", not ours to take from" : "") + ")");
        }
    }

    /** Our chests a player (not one of us) has open right now: chest key -> who. */
    private static final Map<String, String> openBy = new HashMap<>();

    /**
     * Watch our chests within sight for a player opening one: once they shut it, what we remember
     * of it is out of date. The bot of us nearest the chest goes and looks in again, as an errand
     * ahead of its own goals. Called every half second.
     */
    public static void watch(Bot bot) {
        var p = bot.player();
        BlockPos me = p.blockPosition();
        for (BotMemory.ChestRecord r : bot.memory().chests.values()) {
            if (r.at == null || !r.ours || !Objects.equals(r.at.dim, bot.dim()) || !r.at.pos().closerThan(me, 32)) continue;
            BlockPos pos = r.at.pos();
            if (!bot.level().isLoaded(pos)) continue;
            String k = key(bot.dim(), pos);
            boolean ourOwn = p.containerMenu != p.inventoryMenu && pos.equals(lastUsed);
            boolean open = !ourOwn && isOpen(bot, pos);
            String who = openBy.get(k);
            if (open && who == null) {
                var o = bot.level().players().stream().filter(x -> x != p && x.blockPosition().closerThan(pos, 6))
                        .min(Comparator.comparingDouble(x -> x.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)))
                        .orElse(null);
                String name = o == null ? null : o.getGameProfile().getName();
                // our bots keep their own records: it's a person's visit that leaves ours stale
                if (name != null && bot.team().knownNames().stream().noneMatch(name::equalsIgnoreCase)) openBy.put(k, name);
            } else if (!open && who != null) {
                openBy.remove(k);
                r.touchedBy = who;
                // the bot of us nearest the chest looks; the rest just know it's stale
                double mine = pos.distSqr(me);
                boolean nearest = bot.team().inRange().stream().noneMatch(m -> m.dim().equals(bot.dim())
                        && BlockPos.containing(m.x(), m.y(), m.z()).distSqr(pos) < mine);
                bot.event(who + " used our chest at " + pos.toShortString() + (nearest ? ": going to look in again" : ""));
                if (nearest) bot.brain().addErrand(new lol.zym.zymbot.brain.tasks.LookInTask(pos), "chest watch", 40, 10 * 60_000L);
            }
        }
    }

    /** A chest's lid is up (the server shows us that for anyone's) or a barrel stands open. */
    private static boolean isOpen(Bot bot, BlockPos pos) {
        if (bot.level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.LidBlockEntity lid && lid.getOpenNess(1f) > 0)
            return true;
        var st = bot.level().getBlockState(pos);
        return st.hasProperty(net.minecraft.world.level.block.BarrelBlock.OPEN) && st.getValue(net.minecraft.world.level.block.BarrelBlock.OPEN);
    }

    /** One we placed ourselves (or a team depot). */
    public static void markOurs(Bot bot, BlockPos pos) {
        var r = bot.memory().chests.computeIfAbsent(key(bot.dim(), pos), x -> new BotMemory.ChestRecord());
        if (r.at == null) {
            r.at = new BotMemory.Pos(pos, bot.dim());
            r.seenMillis = System.currentTimeMillis();
        }
        r.ours = true;
        r.offLimits = false;
    }

    /** Opened, and no room left in it: the next stash makes another chest. */
    public static void markFull(Bot bot, BlockPos pos) {
        var r = bot.memory().chests.get(key(bot.dim(), pos));
        if (r != null) r.free = 0;
        bot.memory().save();
    }

    public static boolean takeable(BotMemory.ChestRecord r) {
        return (r.ours || r.lootr) && !r.offLimits;
    }

    /** The nearest chest we may take from that held at least one of {@code item}, or null. */
    public static BotMemory.ChestRecord find(Bot bot, Item item, double maxDist) {
        String id = Inv.id(item);
        BlockPos me = bot.player().blockPosition();
        BotMemory.ChestRecord best = null;
        double bestD = maxDist * maxDist;
        for (BotMemory.ChestRecord r : bot.memory().chests.values()) {
            if (r.at == null || !Objects.equals(r.at.dim, bot.dim()) || !takeable(r) || r.items.getOrDefault(id, 0) <= 0) continue;
            double d = r.at.pos().distSqr(me);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /** A fetch found the chest gone or changed: forget it, or correct the count. */
    public static void forget(Bot bot, BlockPos pos) {
        bot.memory().chests.remove(key(bot.dim(), pos));
    }

    /** What the hub shows teammates and leaders: each known chest and its top contents. */
    public static JsonArray summary(Bot bot) {
        JsonArray out = new JsonArray();
        for (BotMemory.ChestRecord r : bot.memory().chests.values()) {
            if (r.at == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("x", r.at.x);
            o.addProperty("y", r.at.y);
            o.addProperty("z", r.at.z);
            o.addProperty("dim", r.at.dim);
            o.addProperty("ours", r.ours);
            if (r.lootr) o.addProperty("lootr", true);
            if (r.offLimits) o.addProperty("off_limits", true);
            if (r.touchedBy != null) o.addProperty("touched_by", r.touchedBy);
            o.addProperty("seen_min_ago", (System.currentTimeMillis() - r.seenMillis) / 60_000);
            JsonObject items = new JsonObject();
            r.items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(16)
                    .forEach(e -> items.addProperty(e.getKey().replace("minecraft:", ""), e.getValue()));
            o.add("items", items);
            out.add(o);
            if (out.size() >= 24) break;
        }
        return out;
    }
}
