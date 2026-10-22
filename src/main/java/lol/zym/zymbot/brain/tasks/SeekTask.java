package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Find a player who's out of sight: walk to where they were last seen, by us or a teammate, and
 * ask each teammate that comes into radio range whether it has seen them since ("whereis"). A
 * newer sighting comes back as a "seen" command and we head there instead. Done once they're
 * within {@code near} blocks.
 */
public class SeekTask extends Task {
    private final String player;
    private final double near;
    /** Teammate -> the sighting we had when we asked it, so each is asked again only when that changes. */
    private final Map<String, Long> asked = new HashMap<>();
    private long goalTick = Long.MIN_VALUE;
    private boolean arrived;

    public SeekTask(String player, double near) {
        this.player = player;
        this.near = near;
    }

    @Override
    public String name() {
        return "Find " + player;
    }

    @Override
    protected Status onTick(Bot bot) {
        Player target = bot.findPlayer(player);
        if (target != null) {
            if (target.distanceTo(bot.player()) <= near) return Status.SUCCESS;
            if (ticks % 20 == 1) Nav.goNear(target.blockPosition(), (int) Math.max(1, near - 1));
            return Status.RUNNING;
        }
        if (ticks > 20 * 300) return fail("couldn't find " + player + " in 5 minutes");
        var s = bot.memory().sighting(player);
        long known = s == null ? -1 : s.tick;
        if (ticks % 40 == 1) {
            for (var m : bot.team().inRange()) {
                String k = m.name().toLowerCase(Locale.ROOT);
                if (m.name().equalsIgnoreCase(player) || asked.containsKey(k) && asked.get(k) == known) continue;
                asked.put(k, known);
                bot.radio(m.name(), "whereis " + player + " " + known);
            }
        }
        if (s == null) {
            // a minute for the teammates in earshot to answer
            return ticks > 20 * 60 ? fail("nobody around has seen " + player) : Status.RUNNING;
        }
        if (!s.at.dim.equals(bot.dim())) return fail(player + " was last seen in " + s.at.dim);
        if (s.tick != goalTick) {
            goalTick = s.tick;
            arrived = false;
            long ago = (bot.level().getGameTime() - s.tick) / 1200;
            bot.event("looking for " + player + ": seen at " + s.at.pos().toShortString() + " " + ago + " min ago"
                    + (s.by == null || s.by.equalsIgnoreCase(bot.name()) ? "" : " (by " + s.by + ")"));
        }
        if (!arrived) {
            if (bot.player().blockPosition().closerThan(s.at.pos(), 6)) {
                arrived = true;
                Nav.stop();
                bot.event(player + " isn't at " + s.at.pos().toShortString() + " any more; asking teammates who pass");
            } else if (ticks % 40 == 1) {
                Nav.goNear(s.at.pos(), 3);
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
