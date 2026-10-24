package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Progression;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.core.BlockPos;

/**
 * Put everything in the pack into our nearest chest (making one if there's none with room), then
 * pause, so nothing gets taken back out: for a bot about to be closed so a player can join instead.
 */
public class EmptyPackTask extends Task {
    private int tries;
    private boolean here;

    @Override
    public String name() {
        return "Empty the pack into a chest";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            boolean making = child instanceof StorageTask;
            if (runChild(bot) == Status.FAILURE) {
                // home too far or too full for another chest: put it down where we are (Bot5, 180 blocks off)
                if (making) here = true;
                if (++tries > 4) return fail("couldn't empty the pack (" + failureReason() + ")");
            }
            return Status.RUNNING;
        }
        int left = 0;
        for (int i = 0; i < 36; i++) if (!Inv.inv().getItem(i).isEmpty()) left++;
        if (left == 0) {
            bot.event("pack emptied into a chest: pausing");
            bot.setEnabled(false);
            return Status.SUCCESS;
        }
        // the stash chest by home, else any chest of ours with room within a walk: making one means
        // finding a tree first (Bot5 wandered for minutes with a chest of ours 20 blocks off)
        BlockPos chest = Progression.stashChest(bot);
        if (chest == null) {
            BlockPos me = bot.player().blockPosition();
            chest = bot.memory().chests.values().stream()
                    .filter(r -> r.ours && r.free != 0 && r.at != null && r.at.dim.equals(bot.dim()) && r.at.pos().closerThan(me, 96))
                    .map(r -> r.at.pos()).min(java.util.Comparator.comparingDouble(c -> c.distSqr(me))).orElse(null);
        }
        if (chest != null) {
            setChild(bot, new StashTask(chest).all());
        } else {
            BlockPos base = Progression.stashBase(bot);
            bot.event("no chest with room: making one");
            setChild(bot, new StorageTask(base != null && !here ? base : bot.player().blockPosition()));
        }
        return Status.RUNNING;
    }
}
