package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.inv.Keep;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.world.inventory.ClickType;

/**
 * Throw out what {@link Keep} calls junk, a pile every few ticks: scaffold past a stack, worse tools,
 * rotten food. Asked for room as well, it then drops the smallest piles we'd otherwise store until
 * there is that much: a chest is crafted in the pack, so a full one can't make the chest it needs.
 */
public class DropJunkTask extends Task {
    private int dropped;
    private final int room;

    public DropJunkTask() {
        this(0);
    }

    public DropJunkTask(int room) {
        this.room = room;
    }

    @Override
    public String name() {
        return "Drop junk (" + dropped + ")";
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        Nav.stop();
        // turn away from the base first, throw a few ticks on (the server throws along the facing it
        // last heard): what we drop lands behind us as we go home, not where we'll walk over it again
        if (ticks % 6 == 0) faceAway(bot);
        if (ticks % 6 != 3) return Status.RUNNING;
        Keep.Pile pile = Keep.surplus(bot).stream()
                .filter(x -> x.fate() == Keep.Fate.JUNK || Inv.freeSlots() < room).findFirst().orElse(null);
        if (pile == null || ticks > 20 * 30) {
            if (dropped > 0) bot.event("dropped " + dropped + " piles of junk");
            return Status.SUCCESS;
        }
        if (pile.fate() == Keep.Fate.STORE) bot.event("no room to make a chest: dropping " + pile.count() + " " + Inv.id(pile.item()).replace("minecraft:", ""));
        throwPile(bot, pile);
        dropped++;
        return Status.RUNNING;
    }

    private static void faceAway(Bot bot) {
        var p = bot.player();
        var base = bot.memory().home != null ? bot.memory().home.pos() : lol.zym.zymbot.brain.ClassPlan.teamHome(bot);
        net.minecraft.world.phys.Vec3 away;
        if (base != null && !base.closerThan(p.blockPosition(), 2)) {
            away = p.position().subtract(net.minecraft.world.phys.Vec3.atCenterOf(base)).multiply(1, 0, 1).normalize();
        } else {
            away = p.getLookAngle().multiply(-1, 0, -1).normalize();
        }
        lol.zym.zymbot.control.Controls.lookAt(p.getEyePosition().add(away.scale(4)).add(0, 0.5, 0), 180f);
    }

    /** Ctrl-Q for a whole stack; Q, once per item, for part of one. */
    public static void throwPile(Bot bot, Keep.Pile pile) {
        var menu = bot.player().inventoryMenu;
        int ms = Inv.menuSlot(menu, pile.slot());
        int have = Inv.inv().getItem(pile.slot()).getCount();
        if (pile.count() >= have) Inv.click(menu, ms, 1, ClickType.THROW);
        else for (int i = 0; i < pile.count(); i++) Inv.click(menu, ms, 0, ClickType.THROW);
    }
}
