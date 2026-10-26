package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.entity.player.Player;

/**
 * A player called us over to be fed ("zb all dinner"): go to them, ask, and pick up what they
 * throw. Done as soon as we hold more food than we came with; the eat reflex takes it from there.
 */
public class DinnerTask extends Task {
    private final String player;
    private int startFood = -1;
    private boolean asked;

    public DinnerTask(String player) {
        this.player = player;
    }

    @Override
    public String name() {
        return "Fetch food from " + player;
    }

    @Override
    protected Status onTick(Bot bot) {
        int food = Inv.count(Inv::isFood);
        if (startFood < 0) startFood = food;
        if (food > startFood) {
            bot.clearFoodOffer();
            bot.chat("@" + player + " got it, thank you!");
            return Status.SUCCESS;
        }
        if (bot.foodOffer() == null) return fail("no food came from " + player);
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        Player p = bot.findPlayer(player);
        if (p == null) {
            bot.clearFoodOffer();
            return fail(player + " isn't in sight");
        }
        if (!p.blockPosition().closerThan(bot.player().blockPosition(), 3)) {
            if (ticks % 20 == 1) Nav.goNear(p.blockPosition(), 2);
            return Status.RUNNING;
        }
        if (!asked) {
            asked = true;
            Nav.stop();
            bot.chat("@" + player + " here! Toss me some food, please.");
        }
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
