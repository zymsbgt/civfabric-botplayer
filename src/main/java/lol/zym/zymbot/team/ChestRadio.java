package lol.zym.zymbot.team;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.memory.ChestIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chest records over the chat radio. Bots talk over chat only, a line holds 256 characters, and a
 * bot gets about one line every two seconds for everything it says, so chests go as changes and
 * answers, never whole inventories on a timer:
 * <ul>
 *   <li>{@code cc}: a chest's contents as counts, {@code ~zb cc main+3,0,-2 t=k2f9 f=12 bread=8,coal=0}.
 *       Without {@code p=} it updates only the items it names (0: none left), so it can be split
 *       anywhere; with {@code p=1/3} it's one line of a full listing, which replaces the record once
 *       every part is in. Said after we change a chest of ours, and in answer to the two below.</li>
 *   <li>{@code wh? <item> <n>}: a teammate after an item it knows of no chest for. Whoever has one on
 *       record answers with a one-item {@code cc} line, after a moment; the rest hold back once they
 *       hear an answer.</li>
 *   <li>{@code ls <place>}: the full listing of a chest, asked by a bot at a base it has no record of.</li>
 * </ul>
 * Places are counted from the nearest team base by name ("main+3,0,-2"), absolute only with no base
 * near, so an overheard line tells a stranger little. The {@code t=} is the game time the chest was
 * seen (base 36), the same clock for every bot: the newest record wins. Chest lines go out only when
 * nothing else is waiting, and wait while anyone not on the team is in sight.
 */
public final class ChestRadio {
    private ChestRadio() {}

    private record Pending(String line, String cancel, long notBefore, long at) {}

    private static final int LINE = 250;
    private static final long KEEP_MS = 5 * 60_000L;
    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    /** What we've asked about lately (item ids, places), so it isn't asked again at once. */
    private static final Map<String, Long> asked = new HashMap<>();
    /** Parts of full listings coming in: "from|place|t" -> part number -> items. */
    private static final Map<String, Map<Integer, Map<String, Integer>>> parts = new HashMap<>();

    // ---- what we say ------------------------------------------------------------------

    /** We changed a team chest (stashed, fetched): tell earshot what it holds now of what changed. */
    public static void changed(Bot bot, BotMemory.ChestRecord r, Map<String, Integer> now) {
        if (now.isEmpty() || bot.team().inRange().isEmpty()) return;
        String place = place(bot, r.at.pos());
        for (String l : lines(place, r.seenTick, r.free, now, false)) queue.addLast(new Pending(l, null, 0, System.currentTimeMillis()));
    }

    /** We need an item and know of no chest with it: ask earshot, once in a while per item. */
    public static void ask(Bot bot, Item item, int n) {
        String id = ChestIndex.shortId(lol.zym.zymbot.inv.Inv.id(item));
        if (bot.team().inRange().isEmpty() || recently("wh " + id, 5 * 60_000L)) return;
        queue.addLast(new Pending(ChatRadio.TAG + " wh? " + id + " " + n, null, 0, System.currentTimeMillis()));
    }

    /** A chest at a team base we know nothing of: ask for its listing. Called every ten seconds. */
    public static void lookAround(Bot bot) {
        if (bot.team().inRange().isEmpty() || bot.memory().teamHomes.isEmpty()) return;
        BlockPos me = bot.player().blockPosition();
        boolean atBase = bot.memory().teamHomes.values().stream()
                .anyMatch(h -> h.at != null && h.at.dim.equals(bot.dim()) && h.at.pos().closerThan(me, 32));
        if (!atBase) return;
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-10, -4, -10), me.offset(10, 4, 10))) {
            var b = bot.level().getBlockState(q).getBlock();
            if (!(b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock)) continue;
            if (ChestIndex.record(bot, q) != null || bot.nearSomeoneElsesBuild(q)) continue;
            String place = place(bot, q);
            if (recently("ls " + place, 30 * 60_000L)) continue;
            queue.addLast(new Pending(ChatRadio.TAG + " ls " + place, null, 0, System.currentTimeMillis()));
            return; // one at a time
        }
    }

    /**
     * The next chest line to say, if one is due, nothing else is waiting (the caller checks) and no
     * stranger is in sight to overhear it.
     */
    public static String next(Bot bot) {
        long now = System.currentTimeMillis();
        queue.removeIf(p -> now - p.at() > KEEP_MS);
        if (queue.isEmpty() || strangerInSight(bot)) return null;
        for (var it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.notBefore() > now) continue;
            it.remove();
            return p.line();
        }
        return null;
    }

    private static boolean strangerInSight(Bot bot) {
        for (var o : bot.level().players()) {
            if (o != bot.player() && !bot.isTeammate(o.getGameProfile().getName())) return true;
        }
        return false;
    }

    private static boolean recently(String what, long within) {
        long now = System.currentTimeMillis();
        Long at = asked.get(what);
        if (at != null && now - at < within) return true;
        asked.put(what, now);
        return false;
    }

    /** Contents as cc lines of at most {@link #LINE} characters; numbered parts when it's the whole chest. */
    static List<String> lines(String place, long tick, int free, Map<String, Integer> items, boolean full) {
        String head = ChatRadio.TAG + " cc " + place + " t=" + Long.toString(tick, 36) + (free >= 0 ? " f=" + free : "");
        List<List<String>> groups = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int len = 0;
        // room for " p=99/99 " on a full listing's lines
        int room = LINE - head.length() - (full ? 9 : 1);
        for (var e : items.entrySet()) {
            String it = ChestIndex.shortId(e.getKey()) + "=" + e.getValue();
            if (!cur.isEmpty() && len + 1 + it.length() > room) {
                groups.add(cur);
                cur = new ArrayList<>();
                len = 0;
            }
            cur.add(it);
            len += (cur.size() > 1 ? 1 : 0) + it.length();
        }
        if (!cur.isEmpty() || full) groups.add(cur);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            String p = full ? " p=" + (i + 1) + "/" + groups.size() : "";
            out.add(head + p + (groups.get(i).isEmpty() ? " -" : " " + String.join(",", groups.get(i))));
        }
        return out;
    }

    // ---- what we hear -----------------------------------------------------------------

    /** A chest line from teammate {@code from}: "cc", "wh?" or "ls" and the rest of it. */
    public static void hear(Bot bot, String from, String kind, String rest) {
        switch (kind) {
            case "cc" -> contents(bot, from, rest);
            case "wh?" -> {
                String[] a = rest.split(" ");
                Item item = item(a[0]);
                if (item == null) return;
                var r = ChestIndex.find(bot, item, 150);
                if (r == null || r.at == null) return;
                // after a moment: if a teammate answers first, we hold back
                int n = r.items.getOrDefault(lol.zym.zymbot.inv.Inv.id(item), 0);
                long wait = System.currentTimeMillis() + 1000 + bot.random().nextInt(4000);
                for (String l : lines(place(bot, r.at.pos()), r.seenTick, -1, Map.of(lol.zym.zymbot.inv.Inv.id(item), n), false))
                    queue.addLast(new Pending(l, "item " + ChestIndex.shortId(lol.zym.zymbot.inv.Inv.id(item)), wait, System.currentTimeMillis()));
            }
            case "ls" -> {
                BlockPos pos = pos(bot, rest.trim());
                if (pos == null) return;
                var r = ChestIndex.record(bot, pos);
                if (r == null || r.partial || !ChestIndex.takeable(r)) return;
                long wait = System.currentTimeMillis() + 1000 + bot.random().nextInt(4000);
                String place = place(bot, pos);
                for (String l : lines(place, r.seenTick, r.free, r.items, true))
                    queue.addLast(new Pending(l, "place " + place, wait, System.currentTimeMillis()));
            }
            default -> { }
        }
    }

    private static void contents(Bot bot, String from, String rest) {
        String[] w = rest.split(" ");
        if (w.length < 3) return;
        BlockPos pos = pos(bot, w[0]);
        if (pos == null) return;
        long tick = -1;
        int free = -1, part = 0, of = 0;
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 1; i < w.length; i++) {
            String f = w[i];
            if (f.startsWith("t=")) tick = Long.parseLong(f.substring(2), 36);
            else if (f.startsWith("f=")) free = Integer.parseInt(f.substring(2));
            else if (f.startsWith("p=")) {
                String[] po = f.substring(2).split("/");
                part = Integer.parseInt(po[0]);
                of = Integer.parseInt(po[1]);
            } else if (!f.equals("-")) {
                for (String it : f.split(",")) {
                    int eq = it.lastIndexOf('=');
                    if (eq > 0) items.put(ChestIndex.fullId(it.substring(0, eq)), Integer.parseInt(it.substring(eq + 1)));
                }
            }
        }
        if (tick < 0) return;
        // someone answered what we were about to: hold ours back
        String place = place(bot, pos);
        queue.removeIf(p -> p.cancel() != null && (p.cancel().equals("place " + place)
                || items.keySet().stream().anyMatch(id -> p.cancel().equals("item " + ChestIndex.shortId(id)))));
        if (of > 0) {
            String key = from.toLowerCase() + "|" + place + "|" + tick;
            var got = parts.computeIfAbsent(key, k -> new HashMap<>());
            got.put(part, items);
            if (got.size() < of) return;
            parts.remove(key);
            Map<String, Integer> all = new LinkedHashMap<>();
            got.values().forEach(all::putAll);
            ChestIndex.heard(bot, from, pos, tick, free, all, true);
        } else {
            ChestIndex.heard(bot, from, pos, tick, free, items, false);
        }
        while (parts.size() > 20) parts.remove(parts.keySet().iterator().next());
    }

    private static Item item(String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(ChestIndex.fullId(id));
        return rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    // ---- places -----------------------------------------------------------------------

    /** "main+3,0,-2" from the nearest team base within 64 blocks, else "x,y,z". */
    static String place(Bot bot, BlockPos p) {
        String best = null;
        double bestD = 64 * 64;
        BlockPos base = null;
        for (var e : bot.memory().teamHomes.entrySet()) {
            var at = e.getValue().at;
            if (at == null || !at.dim.equals(bot.dim()) || !e.getKey().matches("[A-Za-z0-9_-]+")) continue;
            double d = at.pos().distSqr(p);
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
                base = at.pos();
            }
        }
        if (best == null) return p.getX() + "," + p.getY() + "," + p.getZ();
        return best + "+" + (p.getX() - base.getX()) + "," + (p.getY() - base.getY()) + "," + (p.getZ() - base.getZ());
    }

    /** The other way; null for a base we don't know. */
    static BlockPos pos(Bot bot, String s) {
        try {
            int plus = s.indexOf('+');
            String[] c = (plus >= 0 ? s.substring(plus + 1) : s).split(",");
            if (c.length != 3) return null;
            BlockPos off = new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
            if (plus < 0) return off;
            var h = bot.memory().teamHomes.get(s.substring(0, plus));
            return h == null || h.at == null || !h.at.dim.equals(bot.dim()) ? null : h.at.pos().offset(off);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
