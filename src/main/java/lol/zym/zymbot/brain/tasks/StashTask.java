package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.inv.Keep;
import lol.zym.zymbot.memory.ChestIndex;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;

/**
 * Put everything we don't need on us into a chest at home, in one visit: every pile {@link Keep}
 * says is for storing, only the part past what we keep. A chest with no room left is noted as full
 * in the index, so the next stash makes another one instead of trying this one again.
 */
public class StashTask extends Task {
    private final BlockPos chest;
    private int moved;
    /** We clicked the chest: an open container is ours to fill, not some screen left over from before. */
    private boolean clicked;
    /** Everything in the pack, not just what Keep says to store (the bot is about to log off). */
    private boolean all;

    public StashTask(BlockPos chest) {
        this.chest = chest;
    }

    public StashTask all() {
        all = true;
        return this;
    }

    @Override
    public String name() {
        return "Stash " + moved + " stacks @" + chest.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        // a screen we didn't open (left over from another task): shut it. Taking it for the chest marked
        // a chest 130 blocks off as full (Bot4)
        if (p.containerMenu != p.inventoryMenu && (!clicked || !(p.containerMenu instanceof net.minecraft.world.inventory.ChestMenu))) {
            p.closeContainer();
            clicked = false;
            return Status.RUNNING;
        }
        if (p.containerMenu == p.inventoryMenu) {
            if (!Breaker.inReach(chest)) {
                Nav.goToBlock(chest);
                return ticks > 20 * 120 ? fail("can't reach the stash chest") : Status.RUNNING;
            }
            Nav.stop();
            if (lol.zym.zymbot.control.ChestLid.clearing(bot, chest)) return Status.RUNNING;
            if (ticks % 20 == 0) {
                Placer.use(chest);
                clicked = true;
            }
            if (ticks > 20 * 150) {
                var above = WorldView.state(chest.above());
                return fail(above.isRedstoneConductor(bot.level(), chest.above())
                        ? "stash chest can't open: something is on top of it" : "stash chest won't open (locked?)");
            }
            return Status.RUNNING;
        }
        if (ticks % 4 != 0) return Status.RUNNING;
        var menu = p.containerMenu;
        Keep.Pile pile = all ? anyPile() : Keep.surplus(bot).stream().filter(x -> x.fate() == Keep.Fate.STORE).findFirst().orElse(null);
        if (pile == null) {
            p.closeContainer();
            if (moved > 0) bot.event("stashed " + moved + " stacks at " + chest.toShortString());
            return Status.SUCCESS;
        }
        int free = -1;
        for (var s : menu.slots) if (s.container != Inv.inv() && s.getItem().isEmpty()) { free = s.index; break; }
        int ms = Inv.menuSlot(menu, pile.slot());
        int before = Inv.inv().getItem(pile.slot()).getCount();
        if (pile.count() >= before) {
            // a whole stack: shift-click tops up matching stacks in the chest first, then a free slot
            Inv.click(menu, ms, 0, ClickType.QUICK_MOVE);
        } else if (free >= 0) {
            // part of a stack: pick it up, drop the surplus one by one into a free slot, put the rest back
            Inv.click(menu, ms, 0, ClickType.PICKUP);
            for (int i = 0; i < pile.count(); i++) Inv.click(menu, free, 1, ClickType.PICKUP);
            Inv.click(menu, ms, 0, ClickType.PICKUP);
        }
        if (Inv.inv().getItem(pile.slot()).getCount() < before) {
            moved++;
        } else if (free < 0) {
            // nothing moved and no free slot: the chest is full. Not a failure (that would hold off
            // stashing and let the pack start dropping things): the next stash makes a new chest
            ChestIndex.markFull(bot, chest);
            p.closeContainer();
            bot.event("stash chest at " + chest.toShortString() + " is full" + (moved > 0 ? " after " + moved + " stacks" : ""));
            return Status.SUCCESS;
        }
        if (ticks > 20 * 200) return fail("stashing took too long");
        return Status.RUNNING;
    }

    /** The first stack in the pack (main slots only: armour and the offhand don't shift-click into a chest). */
    private static Keep.Pile anyPile() {
        for (int i = 0; i < 36; i++) {
            var st = Inv.inv().getItem(i);
            if (!st.isEmpty()) return new Keep.Pile(i, st.getItem(), st.getCount(), Keep.Fate.STORE);
        }
        return null;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Nav.stop();
    }
}
