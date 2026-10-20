package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.entity.player.Player;

/** Stay near a player until told otherwise. Fails if they're out of sight for a minute. */
public class FollowTask extends Task {
    private final String player;
    private int missing;
    private boolean following;

    public FollowTask(String player) {
        this.player = player;
    }

    @Override
    public String name() {
        return "Follow " + player;
    }

    @Override
    protected Status onTick(Bot bot) {
        Player target = bot.findPlayer(player);
        if (target == null) {
            following = false;
            if (++missing > 20 * 60) return fail(player + " is out of sight");
            return Status.RUNNING;
        }
        missing = 0;
        if (!following || !Nav.baritone().getFollowProcess().isActive()) {
            Nav.follow(target);
            following = true;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
