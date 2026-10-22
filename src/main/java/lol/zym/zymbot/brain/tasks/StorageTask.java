package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.ChestIndex;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/**
 * Put a chest down at home and enter it in the chest index as ours. Chests cost nothing to craft
 * while we're classless, and a chest the index knows is the first place we look before crafting.
 */
public class StorageTask extends Task {
    private BlockPos placed;
    private BlockPos spot;
    /** Where the chest goes near; null: home. */
    private final BlockPos near;

    public StorageTask() {
        this(null);
    }

    public StorageTask(BlockPos near) {
        this.near = near;
    }

    @Override
    public String name() {
        return "Storage chest at home";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            return s == Status.FAILURE ? s : Status.RUNNING;
        }
        var home = bot.memory().home;
        if (near == null && home == null) return fail("no home yet");
        if (placed != null) {
            if (WorldView.state(placed).is(net.minecraft.world.level.block.Blocks.CHEST)) {
                ChestIndex.markOurs(bot, placed);
                bot.memory().addStation("chest", placed, bot.dim(), true);
                bot.memory().save();
                bot.event("storage chest at " + placed.toShortString());
                return Status.SUCCESS;
            }
            if (ticks > 40) return fail("the chest didn't place");
            return Status.RUNNING;
        }
        if (!Inv.has(Items.CHEST)) {
            // planks, then the chest, are crafted in the pack: a full one makes room first
            setChild(bot, Inv.freeSlots() < 2 ? new DropJunkTask(2) : new ObtainTask(ItemTarget.of(Items.CHEST, 1)));
            return Status.RUNNING;
        }
        BlockPos h = near != null ? near : home.pos();
        if (spot == null || !WorldView.state(spot).isAir()) {
            spot = null;
            // nearest dry, open spot that isn't the field: home may be the farm's water itself
            double best = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(h.offset(-8, -2, -8), h.offset(8, 3, 8))) {
                if (!WorldView.state(p).isAir() || !WorldView.floor(p.below()) || !WorldView.state(p.above()).isAir()) continue;
                var below = WorldView.state(p.below());
                if (below.is(net.minecraft.world.level.block.Blocks.FARMLAND) || !below.getFluidState().isEmpty()) continue;
                // not on the bed (home is usually the bed: a chest there blocks it, and placing one
                // means clicking the bed) nor on anything else that does something when clicked
                if (below.is(net.minecraft.tags.BlockTags.BEDS) || below.hasBlockEntity()) continue;
                if (bot.memory().planting(bot.dim(), p.below()) != null) continue;
                double d = p.distSqr(h);
                if (d < best) {
                    best = d;
                    spot = p.immutable();
                }
            }
            if (spot == null) return fail("nowhere to put a chest at home");
        }
        // close enough to click the face we'd place against, not just the spot's middle: half a block
        // short of that, Bot2 stood a minute failing to place ("nowhere to put a chest at home")
        if (!Placer.placeable(spot)) {
            Nav.goNear(spot, 1);
            if (ticks > 20 * 120) return fail("couldn't get to the chest spot");
            return Status.RUNNING;
        }
        Nav.stop();
        BlockPos me = bot.player().blockPosition();
        if (!spot.closerThan(me, 1.5) && !spot.closerThan(me.above(), 1.5) && Placer.place(Items.CHEST, spot)) {
            placed = spot;
            ticks = 0;
            return Status.RUNNING;
        }
        if (ticks > 20 * 60) return fail("nowhere to put a chest at home");
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
