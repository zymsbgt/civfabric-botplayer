package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

/**
 * Go to our claimed bed and sleep. CivFabric's bed rest ends early when the bot is too hungry
 * or the night is nearly over; those messages make the brain stop trying for the night.
 */
public class SleepTask extends Task {
    private final BlockPos bed;

    public SleepTask(BlockPos bed) {
        this.bed = bed;
    }

    @Override
    public String name() {
        return "Sleep at " + bed.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (!WorldView.isNight() && !p.isSleeping()) return Status.SUCCESS;
        if (p.isSleeping()) return ticks > 20 * 60 * 6 ? Status.SUCCESS : Status.RUNNING;
        if (!WorldView.state(bed).is(BlockTags.BEDS)) {
            bot.memory().bed = null;
            bot.event("my bed at " + bed.toShortString() + " is gone");
            return fail("bed gone");
        }
        if (!Breaker.inReach(bed)) {
            Nav.goToBlock(bed);
            if (ticks > 20 * 120) return fail("can't reach bed");
            return Status.RUNNING;
        }
        Nav.stop();
        if (ticks % 40 == 0) {
            Placer.use(bed);
            bot.lastAction("use:bed");
        }
        if (ticks > 20 * 30) return fail("couldn't get into bed");
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
