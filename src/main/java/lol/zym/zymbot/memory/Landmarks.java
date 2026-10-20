package lol.zym.zymbot.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.craft.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Places worth coming back to, remembered across restarts and shared on the hub: water with land
 * beside it (farms need it: no buckets before Blacksmith 3), patches of wild crops, ore, berry
 * bushes and trees, sugar cane, villages, Lootr loot, and anything named with {@code mark}.
 *
 * <p>The bot looks over the chunks around it one column per tick, keeping one landmark per kind
 * per chunk, so a big lake or ore vein is one entry, not hundreds. Gathering consults it before
 * exploring blind.
 */
public final class Landmarks {
    private static final int MAX = 600;
    private static final int RADIUS_CHUNKS = 3;
    private static int cursor;

    private Landmarks() {}

    /** The kind a block is a landmark of, or null. */
    public static String kindOf(BlockState s) {
        Block b = s.getBlock();
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        if (path.startsWith("wild_")) return "wild:" + path.substring(5);
        if (Sources.isOre(s)) return "ore:" + path.replace("deepslate_", "").replace("_ore", "");
        if (b == Blocks.SWEET_BERRY_BUSH || path.endsWith("_berry") && BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("cobblemon"))
            return "berries";
        if (path.contains("apricorn") && !path.contains("planks") && !path.contains("log") && !path.contains("wood")) return "apricorns";
        if (path.contains("grape_bush")) return "grapes";
        if (b == Blocks.SUGAR_CANE) return "sugar_cane";
        if (b == Blocks.BEE_NEST) return "bees";
        if (b == Blocks.PUMPKIN || b == Blocks.MELON) return "melons_pumpkins";
        if (path.equals("apple_leaves")) return "apples";
        if (BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("lootr")) return "lootr";
        return null;
    }

    private static String key(String kind, String dim, BlockPos p) {
        return kind + "@" + dim + " " + (p.getX() >> 4) + " " + (p.getZ() >> 4);
    }

    public static void remember(Bot bot, String kind, BlockPos pos, String name) {
        var mem = bot.memory();
        String k = name != null ? "mark:" + name.toLowerCase(Locale.ROOT) : key(kind, bot.dim(), pos);
        BotMemory.Landmark l = mem.landmarks.get(k);
        boolean fresh = l == null;
        if (fresh) {
            l = new BotMemory.Landmark();
            l.kind = kind;
            l.name = name;
            l.at = new BotMemory.Pos(pos, bot.dim());
            mem.landmarks.put(k, l);
        } else if (name != null) {
            l.at = new BotMemory.Pos(pos, bot.dim());
        }
        l.seenMillis = System.currentTimeMillis();
        if (mem.landmarks.size() > MAX) {
            // forget the oldest unnamed ones
            mem.landmarks.entrySet().stream().filter(e -> e.getValue().name == null)
                    .min(Comparator.comparingLong(e -> e.getValue().seenMillis)).map(Map.Entry::getKey)
                    .ifPresent(mem.landmarks::remove);
        }
    }

    /** Called every tick: look over one chunk column near us. */
    public static void tick(Bot bot) {
        var level = bot.level();
        var me = bot.player().blockPosition();
        int side = RADIUS_CHUNKS * 2 + 1;
        int i = cursor++ % (side * side);
        ChunkPos c = new ChunkPos((me.getX() >> 4) + i % side - RADIUS_CHUNKS, (me.getZ() >> 4) + i / side - RADIUS_CHUNKS);
        LevelChunk ch = level.getChunkSource().getChunk(c.x, c.z, ChunkStatus.FULL, false);
        if (ch == null) return;
        Set<String> found = new HashSet<>();
        int y0 = Math.max(level.getMinBuildHeight(), me.getY() - 48), y1 = Math.min(level.getMaxBuildHeight() - 1, me.getY() + 24);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = y0; y <= y1; y++) {
                    p.set(c.getMinBlockX() + x, y, c.getMinBlockZ() + z);
                    BlockState s = ch.getBlockState(p);
                    if (s.isAir()) continue;
                    String kind = kindOf(s);
                    if (kind == null && s.getFluidState().is(Fluids.WATER) && s.getFluidState().isSource() && isShore(bot, p)) kind = "water";
                    if (kind != null && found.add(kind)) remember(bot, kind, p.immutable(), null);
                }
            }
        }
        // villages: villagers about (village chests near them are off limits, so worth knowing)
        if (cursor % 200 == 0 && !level.getEntitiesOfClass(Villager.class, new AABB(me).inflate(48)).isEmpty()) {
            Villager v = level.getEntitiesOfClass(Villager.class, new AABB(me).inflate(48)).get(0);
            remember(bot, "village", v.blockPosition(), null);
        }
    }

    /** Surface water with grass or dirt right beside it, under open sky: a farm could go here. */
    private static boolean isShore(Bot bot, BlockPos p) {
        if (!bot.level().getBlockState(p.above()).isAir() || !bot.level().canSeeSky(p.above())) return false;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockState n = bot.level().getBlockState(p.relative(d));
            if (n.is(Blocks.GRASS_BLOCK) || n.is(Blocks.DIRT) || n.is(BlockTags.DIRT)) return true;
        }
        return false;
    }

    /** The nearest landmark of a kind (or kinds starting with a prefix), within range. */
    public static BotMemory.Landmark nearest(Bot bot, java.util.function.Predicate<String> kind, double range) {
        BlockPos me = bot.player().blockPosition();
        BotMemory.Landmark best = null;
        double bestD = range * range;
        for (BotMemory.Landmark l : bot.memory().landmarks.values()) {
            if (l.at == null || !Objects.equals(l.at.dim, bot.dim()) || !kind.test(l.kind)) continue;
            double d = l.at.pos().distSqr(me);
            if (d < bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }

    /** The landmark kinds that would lead to these blocks (a gather of wild onions wants "wild:onions"). */
    public static Set<String> kindsFor(Collection<Block> blocks) {
        Set<String> out = new HashSet<>();
        for (Block b : blocks) {
            String k = kindOf(b.defaultBlockState());
            if (k != null) out.add(k);
        }
        return out;
    }

    /** A named waypoint ("mark mine"), or null. */
    public static BotMemory.Landmark named(Bot bot, String name) {
        return bot.memory().landmarks.get("mark:" + name.toLowerCase(Locale.ROOT));
    }

    /** What the hub shows: nearest first, one line each. */
    public static JsonArray summary(Bot bot, int limit) {
        BlockPos me = bot.player().blockPosition();
        JsonArray out = new JsonArray();
        bot.memory().landmarks.values().stream().filter(l -> l.at != null && Objects.equals(l.at.dim, bot.dim()))
                .sorted(Comparator.comparingDouble(l -> l.at.pos().distSqr(me))).limit(limit).forEach(l -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("kind", l.kind);
                    if (l.name != null) o.addProperty("name", l.name);
                    o.addProperty("x", l.at.x);
                    o.addProperty("y", l.at.y);
                    o.addProperty("z", l.at.z);
                    o.addProperty("distance", (int) Math.sqrt(l.at.pos().distSqr(me)));
                    out.add(o);
                });
        return out;
    }
}
