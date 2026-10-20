package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.ChestIndex;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;

/**
 * Take items out of a chest the index says holds them. Cheaper than crafting in CivFabric, where
 * every craft costs food. Opening the chest refreshes its index entry, so a chest that turns out
 * to be emptier than remembered is corrected for next time.
 */
public class FetchTask extends Task {
    private final BlockPos chest;
    private final ItemTarget what;
    private boolean opened;

    public FetchTask(BlockPos chest, ItemTarget what) {
        this.chest = chest.immutable();
        this.what = what;
    }

    @Override
    public String name() {
        return "Fetch " + what.have() + "/" + what + " @" + chest.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (what.satisfied()) {
            if (p.containerMenu != p.inventoryMenu) p.closeContainer();
            return Status.SUCCESS;
        }
        if (p.containerMenu == p.inventoryMenu) {
            if (opened) return what.have() > 0 ? Status.SUCCESS : fail("the chest had none after all");
            if (bot.level().isLoaded(chest) && bot.level().getBlockState(chest).isAir()) {
                ChestIndex.forget(bot, chest);
                return fail("the chest at " + chest.toShortString() + " is gone");
            }
            if (!Breaker.inReach(chest)) {
                Nav.goToBlock(chest);
                return ticks > 20 * 120 ? fail("can't reach the chest") : Status.RUNNING;
            }
            Nav.stop();
            if (ticks % 20 == 0) Placer.use(chest);
            if (ticks > 20 * 150) return fail("the chest won't open");
            return Status.RUNNING;
        }
        opened = true;
        if (ticks % 4 != 0) return Status.RUNNING;
        var menu = p.containerMenu;
        int size = menu.slots.size() - 36; // the chest's own slots come first
        for (int i = 0; i < size; i++) {
            var s = menu.getSlot(i);
            if (s.getItem().isEmpty() || !what.match().test(s.getItem())) continue;
            int need = what.count() - what.have();
            if (s.getItem().getCount() <= need) {
                Inv.click(menu, i, 0, ClickType.QUICK_MOVE);
            } else {
                // take just enough: pick the stack up, put back the extra one at a time
                Inv.click(menu, i, 0, ClickType.PICKUP);
                for (int k = 0; k < s.getItem().getCount() - need && k < 64; k++) Inv.click(menu, i, 1, ClickType.PICKUP);
                int free = Inv.freeSlot();
                if (free >= 0) Inv.click(menu, Inv.menuSlot(menu, free), 0, ClickType.PICKUP);
            }
            return Status.RUNNING; // one stack per few ticks, so the server keeps up
        }
        p.closeContainer();
        return what.have() > 0 ? Status.SUCCESS : fail("the chest had none after all");
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Nav.stop();
    }
}
