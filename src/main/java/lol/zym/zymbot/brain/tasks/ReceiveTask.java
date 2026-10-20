package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;

/**
 * A teammate is about to toss us something: stand still and pick up what lands nearby. Without
 * this a bot on its way somewhere walks off and the giver ends up picking its own items back up.
 */
public class ReceiveTask extends Task {
    @Override
    public String name() {
        return "Receive";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        if (!bot.receiving()) return Status.SUCCESS;
        if (ticks == 1) Nav.stop();
        if (ticks % 10 == 0 && !PickupTask.drops(bot, bot.player().blockPosition(), 5).isEmpty())
            setChild(bot, new PickupTask(bot.player().blockPosition(), 5));
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
