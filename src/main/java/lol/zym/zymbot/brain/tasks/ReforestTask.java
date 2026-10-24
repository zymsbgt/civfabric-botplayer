package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.ClassPlan;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Put saplings back where the forest was: the stumps we remember (trees cut with no sapling to
 * hand), or, told where, open grass on a 5-block grid around a spot. Saplings come from our chests
 * first. The bots cleared most of a forest by the base before replanting worked at all.
 */
public class ReforestTask extends Task {
    private static final int MAX_STUMPS = 300;
    private static final int GRID = 5;

    /** Null: the remembered stumps. Else plant open ground within {@code radius} of here. */
    private final BlockPos center;
    private final int radius;
    private List<BlockPos> spots;
    private int planted;
    private boolean fetched;
    private Task lastChild;
    private String skipped = "";

    private ReforestTask(BlockPos center, int radius) {
        this.center = center;
        this.radius = radius;
    }

    public static ReforestTask stumps() {
        return new ReforestTask(null, 0);
    }

    public static ReforestTask area(BlockPos center, int radius) {
        return new ReforestTask(center.immutable(), Math.max(GRID, Math.min(64, radius)));
    }

    @Override
    public String name() {
        return "Reforest " + (center == null ? "stumps" : "around " + center.getX() + ", " + center.getZ())
                + (spots == null ? "" : " " + planted + "/" + (planted + spots.size()));
    }

    /** A tree's foot we cut and planted nothing in. */
    public static void remember(Bot bot, BlockPos stump) {
        var list = bot.memory().stumps;
        String dim = bot.dim();
        if (list.stream().anyMatch(p -> p.dim.equals(dim) && p.pos().equals(stump))) return;
        list.add(new BotMemory.Pos(stump, dim));
        while (list.size() > MAX_STUMPS) list.remove(0);
    }

    /** Stumps waiting in this dimension. */
    public static int pending(Bot bot) {
        String dim = bot.dim();
        return (int) bot.memory().stumps.stream().filter(p -> p.dim.equals(dim)).count();
    }

    /** Saplings on us or in a chest of ours we know of. */
    public static boolean saplingsAbout(Bot bot) {
        if (Inv.count(s -> s.is(ItemTags.SAPLINGS)) > 0) return true;
        return bot.memory().chests.values().stream()
                .anyMatch(r -> r.ours && r.at != null && r.at.dim.equals(bot.dim()) && r.items.keySet().stream().anyMatch(k -> k.contains("sapling")));
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            if (runChild(bot) == Status.SUCCESS && lastChild instanceof ReplantTask) planted++;
            return Status.RUNNING;
        }
        int saplings = Inv.count(s -> s.is(ItemTags.SAPLINGS));
        if (spots == null) {
            // across, not up: the centre's height is only where the order was given from (Bot1 climbed
            // for y 110 standing on the spot)
            BlockPos me = bot.player().blockPosition();
            if (center != null && Math.hypot(me.getX() - center.getX(), me.getZ() - center.getZ()) > 16) {
                Nav.goXZ(center.getX(), center.getZ());
                return ticks > 20 * 180 ? fail("can't get to " + center.toShortString()) : Status.RUNNING;
            }
            spots = center == null ? stumpSpots(bot) : openGround(bot);
            if (spots.isEmpty()) return done(bot, center == null ? "no stumps left to plant" : "no open ground to plant there (" + skipped + ")");
        }
        // saplings for the lot (16 at a time) from the chests, once
        if (!fetched) {
            fetched = true;
            int want = Math.min(16, spots.size());
            if (saplings < want) {
                lastChild = new ObtainTask(new ItemTarget("sapling", s -> s.is(ItemTags.SAPLINGS),
                        List.of(Items.OAK_SAPLING, Items.BIRCH_SAPLING, Items.SPRUCE_SAPLING), want));
                setChild(bot, lastChild);
                return Status.RUNNING;
            }
        }
        if (saplings == 0) return done(bot, "out of saplings");
        BlockPos me = bot.player().blockPosition();
        spots.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
        while (!spots.isEmpty()) {
            BlockPos next = spots.remove(0);
            forget(bot, next);
            if (!ReplantTask.plantable(next) && WorldView.loaded(next)) continue;
            lastChild = new ReplantTask(next, "");
            setChild(bot, lastChild);
            return Status.RUNNING;
        }
        return done(bot, "all planted");
    }

    /** Done for now: a success if anything went in, so a goal with no saplings to be had is snoozed. */
    private Status done(Bot bot, String why) {
        if (planted == 0) return fail(why);
        bot.event("reforested: " + planted + " saplings planted (" + why + ")");
        return Status.SUCCESS;
    }

    private static void forget(Bot bot, BlockPos p) {
        bot.memory().stumps.removeIf(s -> s.dim.equals(bot.dim()) && s.pos().equals(p));
    }

    private static List<BlockPos> stumpSpots(Bot bot) {
        List<BlockPos> out = new ArrayList<>();
        for (var s : bot.memory().stumps) if (s.dim.equals(bot.dim())) out.add(s.pos());
        return out;
    }

    /**
     * Open grass on a grid around the centre: sky above, no tree, sapling or farm close by, and
     * clear of where we live (a sapling by the base grows into a wall of leaves over the beds).
     */
    private List<BlockPos> openGround(Bot bot) {
        List<BlockPos> keepOff = new ArrayList<>();
        var mem = bot.memory();
        if (mem.home != null) keepOff.add(mem.home.pos());
        if (mem.bed != null) keepOff.add(mem.bed.pos());
        BlockPos base = ClassPlan.teamHome(bot);
        if (base != null) keepOff.add(base);
        for (var st : mem.stations) if (st.at != null && st.at.dim.equals(bot.dim())) keepOff.add(st.at.pos());
        for (var c : mem.chests.values()) if (c.at != null && c.at.dim.equals(bot.dim())) keepOff.add(c.at.pos());
        List<BlockPos> out = new ArrayList<>();
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        for (int dx = -radius; dx <= radius; dx += GRID) {
            for (int dz = -radius; dz <= radius; dz += GRID) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                BlockPos col = new BlockPos(x, 0, z);
                if (!WorldView.loaded(col)) continue;
                // the client only has the heightmaps the server sends (not NO_LEAVES): down past
                // leaves and plants to the ground
                BlockPos at = new BlockPos(x, bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                for (int i = 0; i < 24 && (WorldView.state(at.below()).is(BlockTags.LEAVES) || WorldView.state(at.below()).canBeReplaced()); i++)
                    at = at.below();
                final BlockPos spot = at;
                String no = !ReplantTask.plantable(spot) || !WorldView.state(spot.below()).is(Blocks.GRASS_BLOCK)
                        ? "not grass (" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(WorldView.state(spot.below()).getBlock()).getPath() + ")"
                        : !bot.level().canSeeSky(spot) ? "no sky"
                        : keepOff.stream().anyMatch(k -> k.closerThan(spot, 10)) ? "by the base"
                        : crowded(spot) ? "crowded" : null;
                if (no != null) {
                    why.merge(no, 1, Integer::sum);
                    continue;
                }
                out.add(spot);
            }
        }
        skipped = why.toString();
        return out;
    }

    /** A tree, sapling, crop, water or a path within 3 blocks: no room for one more tree here. */
    private static boolean crowded(BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 4, 3))) {
            var s = WorldView.state(p);
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS) || s.is(Blocks.FARMLAND)
                    || s.is(Blocks.DIRT_PATH) || !s.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
