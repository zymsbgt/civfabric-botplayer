package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.civ.Forecast;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;

/**
 * The forecast names a blood moon tonight: be by the bed before dusk, and wait there. When it rises
 * the blood moon reflexes take over from home, resting in a walled bed or digging in by the base,
 * rather than from wherever the afternoon's work had taken us. Done once the moon is up, or once
 * the night has begun without one (readings can be wrong).
 */
public class BraceTask extends Task {
    @Override
    public String name() {
        return "Home before the forecast blood moon";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (bot.pacifism().bloodMoon() || !Forecast.braceTime(bot)) return Status.SUCCESS;
        BotMemory.Pos h = home(bot);
        if (h == null) return Status.SUCCESS;
        if (!h.pos().closerThan(bot.player().blockPosition(), 4)) {
            if (ticks % 40 == 1) Nav.goNear(h.pos(), 2);
        } else {
            Nav.stop();
        }
        return Status.RUNNING;
    }

    /** Our bed, else our home, if it's in this dimension. */
    public static BotMemory.Pos home(Bot bot) {
        BotMemory.Pos h = bot.memory().bed != null ? bot.memory().bed : bot.memory().home;
        return h != null && h.dim.equals(bot.dim()) ? h : null;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
