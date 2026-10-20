package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;

/** Walk to within {@code range} of a position. */
public class GoToTask extends Task {
    private final BlockPos pos;
    private final int range;
    private int idle;
    private double best = Double.MAX_VALUE;
    private int bestAt;

    public GoToTask(BlockPos pos, int range) {
        this.pos = pos;
        this.range = range;
    }

    @Override
    public String name() {
        return "Go to " + pos.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        if (bot.player().blockPosition().closerThan(pos, range + 0.5)) return Status.SUCCESS;
        // Baritone keeps a goal it can't reach active (and busy) while it retries, so also give up
        // when a minute passes without getting a block closer (detours round lakes and hills take a while)
        double d = Math.sqrt(bot.player().blockPosition().distSqr(pos));
        if (d < best - 1) {
            best = d;
            bestAt = ticks;
        } else if (ticks - bestAt > 1200) return fail("can't get closer to " + pos.toShortString() + " than " + (int) best);
        Nav.goNear(pos, range);
        if (!Nav.busy() && ticks > 40) {
            if (++idle > 100) return fail("no path to " + pos.toShortString());
        } else idle = 0;
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
