package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Put a sapling where a tree we cut down stood: the same kind as the log if we have one, else any.
 * Wood is fuel (charcoal) and planks for everyone, and a forest that's only ever cut runs out.
 */
public class ReplantTask extends Task {
    private final BlockPos stump;
    private final String wood; // "oak" for oak_log: which sapling fits best

    public ReplantTask(BlockPos stump, String wood) {
        this.stump = stump;
        this.wood = wood;
    }

    @Override
    public String name() {
        return "Replant at " + stump.toShortString();
    }

    /** Somewhere a sapling can go: air, on dirt or grass. */
    public static boolean plantable(BlockPos at) {
        return WorldView.loaded(at) && WorldView.state(at).isAir() && WorldView.state(at.below()).is(BlockTags.DIRT);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (!plantable(stump)) return Status.SUCCESS; // something grew or was put there already
        Item sapling = sapling();
        if (sapling == null) return fail("no sapling");
        if (!Placer.placeable(stump)) {
            Nav.goNear(stump, 2);
            return ticks > 20 * 30 ? fail("can't get to the stump") : Status.RUNNING;
        }
        Nav.stop();
        if (Placer.place(sapling, stump)) {
            bot.event("replanted " + Inv.id(sapling).replace("minecraft:", "") + " at " + stump.toShortString());
            return Status.SUCCESS;
        }
        return ticks > 20 * 40 ? fail("the sapling wouldn't go in") : Status.RUNNING;
    }

    private Item sapling() {
        Item any = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!s.is(ItemTags.SAPLINGS)) continue;
            if (BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith(wood + "_")) return s.getItem();
            if (any == null) any = s.getItem();
        }
        return any;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
