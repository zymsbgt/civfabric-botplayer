package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.entity.player.Player;

/** Stay near a player until told otherwise. Out of sight, we go looking for them ({@link SeekTask}). */
public class FollowTask extends Task {
    private final String player;
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
            if (child == null) setChild(bot, new SeekTask(player, 8));
            Task c = child;
            if (runChild(bot) == Status.FAILURE) return fail(c.failureReason());
            return Status.RUNNING;
        }
        if (child != null) {
            child.stop(bot);
            child = null;
        }
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
