package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ClickType;

/** Put {@code what.count()} matching items into a chest (a job board's depot). */
public class DepositTask extends Task {
    private final BlockPos chest;
    private final ItemTarget what;
    private int moved;
    private int movedBefore; // in earlier openings of the chest
    private int openHave = -1; // what we held when the chest opened; -1 while it's closed

    public DepositTask(BlockPos chest, ItemTarget what) {
        this.chest = chest;
        this.what = what;
    }

    public int moved() {
        return moved;
    }

    @Override
    public String name() {
        return "Deposit " + moved + "/" + what + " @" + chest.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        // only count what leaves us while the chest is open: Baritone may spend the same item as
        // scaffolding on the way here (cobblestone, dirt), and that never reached the chest
        if (p.containerMenu != p.inventoryMenu) {
            if (openHave < 0) openHave = what.have();
            moved = movedBefore + Math.max(0, openHave - what.have());
        } else if (openHave >= 0) {
            movedBefore = moved;
            openHave = -1;
        }
        if (moved >= what.count()) {
            if (p.containerMenu != p.inventoryMenu) p.closeContainer();
            return Status.SUCCESS;
        }
        if (p.containerMenu == p.inventoryMenu) {
            if (!Breaker.inReach(chest)) {
                Nav.goToBlock(chest);
                return ticks > 20 * 120 ? fail("can't reach depot chest") : Status.RUNNING;
            }
            Nav.stop();
            if (lol.zym.zymbot.control.ChestLid.clearing(bot, chest)) return Status.RUNNING;
            if (ticks % 20 == 0) Placer.use(chest);
            if (ticks > 20 * 150) {
                var above = WorldView.state(chest.above());
                return fail(above.isRedstoneConductor(bot.level(), chest.above())
                        ? "depot chest can't open: something is on top of it" : "depot chest won't open (locked?)");
            }
            return Status.RUNNING;
        }
        if (ticks % 4 != 0) return Status.RUNNING;
        var menu = p.containerMenu;
        int idx = Inv.find(what.match());
        if (idx < 0) {
            p.closeContainer();
            return moved > 0 ? Status.SUCCESS : fail("nothing to deposit");
        }
        int remaining = what.count() - moved;
        var stack = Inv.inv().getItem(idx);
        int ms = Inv.menuSlot(menu, idx);
        if (stack.getCount() <= remaining) {
            Inv.click(menu, ms, 0, ClickType.QUICK_MOVE);
        } else {
            // split: pick up the stack, drop the right number one by one into a free chest slot
            int free = -1;
            for (var s : menu.slots) if (s.container != Inv.inv() && s.getItem().isEmpty()) { free = s.index; break; }
            if (free < 0) {
                p.closeContainer();
                return fail("depot chest is full");
            }
            Inv.click(menu, ms, 0, ClickType.PICKUP);
            for (int i = 0; i < remaining; i++) Inv.click(menu, free, 1, ClickType.PICKUP);
            Inv.click(menu, ms, 0, ClickType.PICKUP);
        }
        if (ticks > 20 * 200) return fail("deposit took too long");
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Nav.stop();
    }
}
