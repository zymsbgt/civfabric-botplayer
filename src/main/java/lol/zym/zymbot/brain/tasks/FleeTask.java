package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Run until no threat is within {@code distance}, re-aiming every second. */
public class FleeTask extends Task {
    private final Entity from;
    private final double distance;

    public FleeTask(Entity from, double distance) {
        this.from = from;
        this.distance = distance;
    }

    @Override
    public String name() {
        return "Flee " + from.getType().getDescription().getString();
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks == 1) Nav.sprint(true); // the one time the hunger is worth it
        if (!from.isAlive() || from.distanceTo(bot.player()) > distance || ticks > 20 * 30) return Status.SUCCESS;
        if (ticks % 20 == 1) {
            BlockPos threat = from.blockPosition();
            Nav.runAway(distance + 4, threat);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.sprint(false);
        Nav.stop();
    }
}
