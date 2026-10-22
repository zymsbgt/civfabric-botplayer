package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/**
 * Hand items to a teammate: get them if we don't have enough, find them ({@link SeekTask}), walk
 * over, and toss them at their feet one stack at a time.
 */
public class GiveTask extends Task {
    private int askedAt = -1000;
    private final String player;
    private final ItemTarget what;
    private int given;
    private boolean carriedOnly;

    public GiveTask(String player, ItemTarget what) {
        this.player = player;
        this.what = what;
    }

    /** Only what we carry now: when it runs out, that's what there was to give (a food swap). */
    public GiveTask carriedOnly() {
        carriedOnly = true;
        return this;
    }

    public int given() {
        return given;
    }

    @Override
    public String name() {
        return "Give " + player + " " + given + "/" + what;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (given >= what.count()) return Status.SUCCESS;
        if (child != null) {
            Task c = child;
            if (runChild(bot) == Status.FAILURE) return fail(c instanceof SeekTask ? c.failureReason() : "couldn't get " + what);
            return Status.RUNNING;
        }
        int remaining = what.count() - given;
        if (what.have() == 0) {
            if (carriedOnly) return given > 0 ? Status.SUCCESS : fail("none left to give");
            setChild(bot, new ObtainTask(what.withCount(remaining)));
            return Status.RUNNING;
        }
        Player target = bot.findPlayer(player);
        if (target == null) {
            // out of sight: where they were last seen, asking the teammates we pass
            setChild(bot, new SeekTask(player, 24));
            return Status.RUNNING;
        }
        // ask a teammate bot to stop and catch (re-asked every 10 s; players just get the toss)
        if (target.distanceTo(bot.player()) < 16 && bot.team().member(player).isPresent() && ticks - askedAt > 200) {
            askedAt = ticks;
            bot.radio(player, "receive 12");
        }
        if (target.distanceTo(bot.player()) > 3.5) {
            if (ticks % 20 == 1) Nav.goNear(target.blockPosition(), 2);
            return Status.RUNNING;
        }
        Nav.stop();
        if (askedAt > 0 && ticks - askedAt < 30) return Status.RUNNING; // give it a moment to stop
        if (!Controls.lookAt(target.getEyePosition().subtract(0, 1.2, 0), 60f)) return Status.RUNNING;
        if (ticks % 6 != 0) return Status.RUNNING;
        int idx = Inv.find(what.match());
        if (idx < 0) return Status.RUNNING;
        var stack = Inv.inv().getItem(idx);
        var menu = bot.player().inventoryMenu;
        int ms = Inv.menuSlot(menu, idx);
        if (stack.getCount() <= remaining) {
            given += stack.getCount();
            Inv.click(menu, ms, 1, ClickType.THROW); // ctrl-Q: whole stack
        } else {
            given++;
            Inv.click(menu, ms, 0, ClickType.THROW); // Q: one item
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
