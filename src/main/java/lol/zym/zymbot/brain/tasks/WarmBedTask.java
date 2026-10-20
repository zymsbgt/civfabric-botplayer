package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

/**
 * CivFabric's bed rest only heals (5 health a night) when the bed is warm: a campfire within
 * 4 blocks and 2 up or down. Make or fetch a campfire and set it down beside our bed.
 */
public class WarmBedTask extends Task {
    public static final int RADIUS = 4, HEIGHT = 2;
    private BlockPos placedAt;

    @Override
    public String name() {
        return "Warm the bed (campfire)";
    }

    /** A campfire close enough to the bed for CivFabric's warmth. */
    public static BlockPos campfireNear(BlockPos bed) {
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-RADIUS, -HEIGHT, -RADIUS), bed.offset(RADIUS, HEIGHT, RADIUS))) {
            if (WorldView.loaded(p) && WorldView.state(p).getBlock() instanceof CampfireBlock) return p.immutable();
        }
        return null;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.FAILURE) return Status.FAILURE; // the child's reason stands
            return Status.RUNNING;
        }
        var bedPos = bot.memory().bed;
        if (bedPos == null || !bedPos.dim.equals(bot.dim())) return fail("no bed here");
        BlockPos bed = bedPos.pos();
        if (placedAt != null && WorldView.state(placedAt).getBlock() instanceof CampfireBlock || WorldView.loaded(bed) && campfireNear(bed) != null) {
            bot.memory().bedWarm = true;
            bot.event("bed warmed: campfire by the bed");
            return Status.SUCCESS;
        }
        if (Inv.count(Items.CAMPFIRE) == 0) {
            setChild(bot, new ObtainTask(ItemTarget.of(Items.CAMPFIRE, 1)));
            return Status.RUNNING;
        }
        if (!bed.closerThan(bot.player().blockPosition(), 4)) {
            Nav.goNear(bed, 2);
            if (ticks > 20 * 90) return fail("couldn't get back to the bed");
            return Status.RUNNING;
        }
        Nav.stop();
        // two or three blocks out, on the floor, not in the way of the bed itself
        for (int r = 2; r <= 3 && placedAt == null; r++) {
            for (BlockPos p : BlockPos.betweenClosed(bed.offset(-r, -1, -r), bed.offset(r, 1, r))) {
                if (Math.max(Math.abs(p.getX() - bed.getX()), Math.abs(p.getZ() - bed.getZ())) != r) continue;
                if (!WorldView.state(p).isAir() || !WorldView.floor(p.below()) || WorldView.state(p.below()).is(Blocks.FARMLAND)) continue;
                if (p.closerThan(bot.player().blockPosition(), 1.5)) continue; // not under our feet
                if (Placer.place(Items.CAMPFIRE, p.immutable())) {
                    placedAt = p.immutable();
                    return Status.RUNNING;
                }
            }
        }
        if (ticks > 20 * 120) return fail("nowhere to put a campfire by the bed");
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
