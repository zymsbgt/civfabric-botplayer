package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.GraveFinder;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;

/**
 * Empty someone else's grave into our pack: it opens like a chest for anyone and goes once it's
 * empty and shut. A teammate's we take on our own (their things stay in the team, and the
 * headstone stops blocking paths); anyone else's only when a player tells us to.
 */
public class LootGraveTask extends Task {
    private final BlockPos grave;
    private String owner;
    private int took;
    private boolean clicked;

    public LootGraveTask(BlockPos grave) {
        this.grave = grave.immutable();
    }

    @Override
    public String name() {
        return "Loot grave " + grave.toShortString() + (owner != null && !owner.isEmpty() ? " (" + owner + ")" : "");
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (owner == null) owner = GraveFinder.owner(bot, grave);
        if (p.containerMenu == p.inventoryMenu) {
            if (!GraveFinder.isGrave(WorldView.state(grave))) return done(bot);
            if (clicked && took > 0) return done(bot); // shut after a pass: what didn't fit stays
            if (!Breaker.inReach(grave)) {
                Nav.goToBlock(grave);
                return ticks > 20 * 90 ? fail("can't reach the grave at " + grave.toShortString()) : Status.RUNNING;
            }
            Nav.stop();
            if (ticks % 20 == 0) clicked = Placer.use(grave) || clicked;
            return ticks > 20 * 120 ? fail("the grave at " + grave.toShortString() + " won't open") : Status.RUNNING;
        }
        if (ticks % 3 != 0) return Status.RUNNING;
        var menu = p.containerMenu;
        for (var s : menu.slots) {
            if (s.container == Inv.inv() || s.getItem().isEmpty()) continue;
            if (Inv.freeSlots() == 0) {
                bot.event("pack full: left the rest in the grave at " + grave.toShortString());
                p.closeContainer();
                return done(bot);
            }
            took += s.getItem().getCount();
            Inv.click(menu, s.index, 0, ClickType.QUICK_MOVE);
            return Status.RUNNING;
        }
        p.closeContainer(); // empty: the grave goes on close
        return done(bot);
    }

    private Status done(Bot bot) {
        if (took > 0) {
            bot.event("took " + took + " items from " + (owner == null || owner.isEmpty() ? "a" : owner + "'s") + " grave at " + grave.toShortString());
            if (owner != null && !owner.isEmpty() && bot.isTeammate(owner) && !owner.equalsIgnoreCase(bot.name()))
                bot.chat(owner + ": I have your things from your grave at " + grave.toShortString() + ", ask me for them");
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Nav.stop();
    }
}
