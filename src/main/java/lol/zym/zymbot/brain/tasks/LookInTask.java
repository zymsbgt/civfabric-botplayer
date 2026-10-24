package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.memory.ChestIndex;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;

/**
 * Open a chest just to see what's in it now ({@link ChestIndex} records it while it's open), then
 * close it: after someone else has been into one of ours, the index can't be trusted.
 */
public class LookInTask extends Task {
    private final BlockPos chest;
    private int openedAt = -1;

    public LookInTask(BlockPos chest) {
        this.chest = chest.immutable();
    }

    @Override
    public String name() {
        return "Look in the chest @" + chest.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) {
            if (openedAt < 0) openedAt = ticks;
            // a few index passes (every 5 ticks) once the contents have arrived
            if (ticks - openedAt < 15) return Status.RUNNING;
            p.closeContainer();
            return Status.SUCCESS;
        }
        if (openedAt >= 0) return Status.SUCCESS;
        if (bot.level().isLoaded(chest) && bot.level().getBlockState(chest).isAir()) {
            ChestIndex.forget(bot, chest);
            return fail("the chest at " + chest.toShortString() + " is gone");
        }
        if (!Breaker.inReach(chest)) {
            Nav.goToBlock(chest);
            return ticks > 20 * 90 ? fail("can't reach the chest") : Status.RUNNING;
        }
        Nav.stop();
        if (lol.zym.zymbot.control.ChestLid.clearing(bot, chest)) return Status.RUNNING;
        if (ticks % 20 == 0) Placer.use(chest);
        return ticks > 20 * 120 ? fail("the chest won't open") : Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
