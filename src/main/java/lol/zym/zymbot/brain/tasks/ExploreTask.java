package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Walk somewhere new. Keeps heading roughly the same way between calls (with some wobble) so
 * repeated exploring spirals outward instead of pacing back and forth.
 */
public class ExploreTask extends Task {
    private static double heading = new Random().nextDouble() * Math.PI * 2;
    /** Last job board we saw: keeps a bot with no bed or home in reach of its team. */
    private static BlockPos lastBoard;
    /** How far exploring may take us from home: the team hub only reaches 150 blocks. */
    private static final int TETHER = 120;
    private final int distance;
    private int tx, tz;

    public ExploreTask(int distance) {
        this.distance = distance;
    }

    @Override
    public String name() {
        return "Explore → " + tx + "," + tz;
    }

    @Override
    protected Status onTick(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        if (ticks == 1) pick(bot, me);
        double dx = me.getX() - tx, dz = me.getZ() - tz;
        if (dx * dx + dz * dz < 9 * 9) return Status.SUCCESS;
        if (ticks > 20 * 90) return Status.SUCCESS; // good enough; look around from here
        if (ticks > 60 && !Nav.busy()) {
            heading += Math.PI / 2; // blocked that way; turn
            pick(bot, me);
        }
        Nav.goXZ(tx, tz);
        return Status.RUNNING;
    }

    private void pick(Bot bot, BlockPos me) {
        heading += (bot.random().nextDouble() - 0.5) * 0.9;
        BlockPos anchor = anchor(bot);
        if (anchor != null && Math.hypot(me.getX() - anchor.getX(), me.getZ() - anchor.getZ()) + distance > TETHER) {
            // near the edge of radio range: swing round to head back towards home
            double back = Math.atan2(anchor.getZ() - me.getZ(), anchor.getX() - me.getX());
            heading = back + (bot.random().nextDouble() - 0.5) * 1.2;
        }
        // don't head out to sea: swimming burns 15x the hunger and there's nothing to gather out
        // there. Try other headings (and a bit further) for one that ends on dry land we can see.
        for (int tries = 0; tries < 24; tries++) {
            double h = heading + (tries / 2) * (Math.PI / 6) * (tries % 2 == 0 ? 1 : -1);
            int d = tries < 12 ? distance : distance * 2;
            int x = me.getX() + (int) (Math.cos(h) * d), z = me.getZ() + (int) (Math.sin(h) * d);
            if (dryLand(bot, x, z)) {
                heading = h;
                tx = x;
                tz = z;
                return;
            }
        }
        tx = me.getX() + (int) (Math.cos(heading) * distance);
        tz = me.getZ() + (int) (Math.sin(heading) * distance);
    }

    /** Where the bot belongs: its bed, its home, or the last job board it saw. */
    public static BlockPos anchor(Bot bot) {
        var m = bot.memory();
        BlockPos team = lol.zym.zymbot.brain.ClassPlan.teamHome(bot);
        if (team != null) return team;
        if (m.bed != null && bot.dim().equals(m.bed.dim)) return m.bed.pos();
        if (m.home != null && bot.dim().equals(m.home.dim)) return m.home.pos();
        for (var b : bot.team().boards()) {
            if (b.has("pos")) {
                var p = b.getAsJsonObject("pos");
                lastBoard = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
                break;
            }
        }
        return lastBoard;
    }

    /** Loaded, and the surface there isn't water. */
    private static boolean dryLand(Bot bot, int x, int z) {
        var level = bot.level();
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        return y > level.getMinBuildHeight() && level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty();
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
