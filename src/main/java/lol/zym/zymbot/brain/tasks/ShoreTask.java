package lol.zym.zymbot.brain.tasks;

import baritone.api.pathing.goals.GoalBlock;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Get out of deep water onto the nearest dry ground. Baritone swims, but slowly and with no
 * thought for air or drowned, so a bot left floating (a job ended mid-lake, knocked in) leaves.
 */
public class ShoreTask extends Task {
    private BlockPos shore;

    @Override
    public String name() {
        return "Swim to shore" + (shore == null ? "" : " " + shore.toShortString());
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (!p.isInWater() && p.onGround()) return Status.SUCCESS;
        if (ticks > 20 * 45) return fail("couldn't reach the shore");
        if (shore == null || ticks % 100 == 0) {
            shore = nearestShore(p.blockPosition(), 24);
            if (shore == null) return fail("no dry land near the water");
            Nav.goTo(new GoalBlock(shore));
        }
        return Status.RUNNING;
    }

    /** Closest spot to stand on dry ground: solid floor, two clear blocks, no water. */
    private static BlockPos nearestShore(BlockPos from, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-r, -4, -r), from.offset(r, 4, r))) {
            if (WorldView.water(p) || WorldView.water(p.below()) || !WorldView.standable(p)) continue;
            double d = p.distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
