package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;

/**
 * Lie low while hurt on a server where health doesn't come back by itself: go home (by the bed,
 * where the sleep reflex can rest us by the campfire at night) and wait there for a medic.
 */
public class RestTask extends Task {
    @Override
    public String name() {
        return "Rest (hurt)";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (!bot.health().fragile(bot)) return Status.SUCCESS;
        if (ticks > 20 * 60) return Status.SUCCESS; // look again at what to do
        BotMemory.Pos h = bot.memory().bed != null ? bot.memory().bed : bot.memory().home;
        if (h != null && h.dim.equals(bot.dim()) && !h.pos().closerThan(bot.player().blockPosition(), 4)) {
            if (ticks % 40 == 1) Nav.goNear(h.pos(), 2);
        } else {
            Nav.stop();
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
