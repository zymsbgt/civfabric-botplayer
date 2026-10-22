package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Find a player who's out of sight: walk to where they were last seen, by us or a teammate, then
 * to their home if they told us one, asking each teammate that comes into radio range whether it has
 * seen them since ("whereis"). A newer sighting comes back as a "seen" command and we head there
 * instead. Done once they're within {@code near} blocks.
 *
 * <p>Nowhere to be found: a job (a delivery someone asked for) is set aside as an errand, and the
 * brain goes back to work, keeping an eye out, until they're seen. Anything else fails.
 */
public class SeekTask extends Task {
    private enum Stage { SIGHTING, BASE, OUT_OF_LEADS }

    private final String player;
    private final double near;
    /** Teammate -> the sighting we had when we asked it, so each is asked again only when that changes. */
    private final Map<String, Long> asked = new HashMap<>();
    private long goalTick = Long.MIN_VALUE;
    private Stage stage = Stage.SIGHTING;
    private int stageAt;
    /** When the newest lead came in: five minutes on one lead is enough. */
    private int leadAt;

    public SeekTask(String player, double near) {
        this.player = player;
        this.near = near;
    }

    @Override
    public String name() {
        return "Find " + player + (stage == Stage.BASE ? " at their home" : stage == Stage.OUT_OF_LEADS ? " (out of leads)" : "");
    }

    @Override
    protected Status onTick(Bot bot) {
        Player target = bot.findPlayer(player);
        if (target != null) {
            if (target.distanceTo(bot.player()) <= near) return Status.SUCCESS;
            if (ticks % 20 == 1) Nav.goNear(target.blockPosition(), (int) Math.max(1, near - 1));
            return Status.RUNNING;
        }
        var s = bot.memory().sighting(player);
        long known = s == null ? -1 : s.tick;
        if (ticks % 40 == 1) askAround(bot, player, known, asked);
        // word of somewhere newer: start over from there
        if (s != null && s.tick != goalTick && s.at.dim.equals(bot.dim())) {
            goalTick = s.tick;
            leadAt = ticks;
            to(Stage.SIGHTING);
            long ago = (bot.level().getGameTime() - s.tick) / 1200;
            bot.event("looking for " + player + ": seen at " + s.at.pos().toShortString() + " " + ago + " min ago"
                    + (s.by == null || s.by.equalsIgnoreCase(bot.name()) ? "" : " (by " + s.by + ")"));
        }
        switch (stage) {
            case SIGHTING -> {
                if (s == null || !s.at.dim.equals(bot.dim())) {
                    // nothing to go on (or they were in another dimension): a moment for teammates to answer
                    if (ticks - stageAt > 20 * 30) to(Stage.BASE);
                } else if (walk(s.at.pos())) {
                    bot.event(player + " isn't at " + s.at.pos().toShortString() + " any more");
                    to(Stage.BASE);
                }
            }
            case BASE -> {
                BotMemory.Pos home = bot.memory().playerHomes.get(player.toLowerCase(Locale.ROOT));
                if (home == null || !home.dim.equals(bot.dim()) || s != null && home.pos().closerThan(s.at.pos(), 8)) {
                    to(Stage.OUT_OF_LEADS);
                } else {
                    if (ticks - stageAt == 1) bot.event("trying " + player + "'s home at " + home.pos().toShortString());
                    if (walk(home.pos())) {
                        bot.event(player + " isn't home either");
                        to(Stage.OUT_OF_LEADS);
                    }
                }
            }
            case OUT_OF_LEADS -> {
                // a job waits as an errand while we work; anything else gives up
                if (bot.brain().setAside(this, player, known)) return Status.RUNNING;
                return fail("couldn't find " + player + (s == null ? ": nobody around has seen them" : ""));
            }
        }
        if (ticks - leadAt > 20 * 300) {
            if (bot.brain().setAside(this, player, known)) return Status.RUNNING;
            return fail("couldn't find " + player + " in 5 minutes");
        }
        return Status.RUNNING;
    }

    private void to(Stage next) {
        stage = next;
        stageAt = ticks;
    }

    /** Head for a place; true once there. */
    private boolean walk(BlockPos at) {
        if (lol.zym.zymbot.Bot.get().player().blockPosition().closerThan(at, 6)) {
            Nav.stop();
            return true;
        }
        if ((ticks - stageAt) % 40 == 1) Nav.goNear(at, 3);
        return false;
    }

    /** Ask each teammate in range where the player is, once for each thing we know already. */
    public static void askAround(Bot bot, String player, long known, Map<String, Long> asked) {
        for (var m : bot.team().inRange()) {
            String k = m.name().toLowerCase(Locale.ROOT);
            if (m.name().equalsIgnoreCase(player) || asked.containsKey(k) && asked.get(k) == known) continue;
            asked.put(k, known);
            bot.radio(m.name(), "whereis " + player + " " + known);
        }
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
