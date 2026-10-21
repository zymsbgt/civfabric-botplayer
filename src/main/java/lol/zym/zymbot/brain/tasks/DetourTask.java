package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.ShoppingList;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A short side trip for something on the shopping list: gather from the patch we spotted, then hand
 * back to whatever we were doing. Capped at 30 s and 16 blocks from where we turned off, so a coal
 * seam that turns out to be a cave doesn't become the afternoon.
 */
public class DetourTask extends Task {
    private static final int MAX_TICKS = 20 * 30, MAX_AWAY = 16, MAX_TAKE = 6;

    private final ShoppingList.Find find;
    private final BlockPos origin;
    private final int startCount;

    public DetourTask(ShoppingList.Find find, BlockPos origin) {
        this.find = find;
        this.origin = origin;
        this.startCount = Inv.count(find.want().match());
    }

    @Override
    public String name() {
        return "Detour for " + find.want().what() + " at " + find.at().toShortString();
    }

    /** What we came back with. */
    public int got() {
        return Inv.count(find.want().match()) - startCount;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks > MAX_TICKS || !bot.player().blockPosition().closerThan(origin, MAX_AWAY)) return Status.SUCCESS;
        if (child == null) {
            if (ticks > 1) return Status.SUCCESS; // the gather finished (or gave up): back to work
            int take = Math.min(find.want().count(), MAX_TAKE);
            setChild(bot, new GatherTask(List.of(find.block()), find.want().match(), startCount + take));
        }
        runChild(bot);
        return Status.RUNNING;
    }
}
