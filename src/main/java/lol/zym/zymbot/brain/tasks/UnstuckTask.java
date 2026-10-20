package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.BlockRules;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;

/**
 * Climb out of a hole or flooded pocket that Baritone can't path out of (it won't pillar in
 * water, and gravel that caved in around a mined block often boxes the bot in): break the
 * natural blocks overhead and pillar straight up on junk blocks until we can see the sky.
 */
public class UnstuckTask extends Task {
    private int startY = Integer.MIN_VALUE;
    private BlockPos stand;
    private Breaker breaker;
    private int badBreaks;

    @Override
    public String name() {
        return "Climb out" + (startY == Integer.MIN_VALUE ? "" : " (+" + (Bot.get().player().blockPosition().getY() - startY) + ")");
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        BlockPos feet = p.blockPosition();
        if (startY == Integer.MIN_VALUE) {
            startY = feet.getY();
            Nav.stop();
        }
        if (ticks > 20 * 90) return fail("couldn't climb out");
        boolean free = bot.level().canSeeSky(feet.above()) && p.onGround() && !p.isInWater();
        if (free || feet.getY() - startY > 24) return Status.SUCCESS;

        if (breaker != null) {
            Controls.jump(false);
            Breaker.Result r = breaker.tick();
            if (r == Breaker.Result.RUNNING) return Status.RUNNING;
            if (r != Breaker.Result.DONE && ++badBreaks > 3) return fail("can't dig upwards");
            breaker = null;
            return Status.RUNNING;
        }
        boolean wet = p.isInWater();
        if (wet && openAbove(feet)) {
            // open water: swimming up is enough, and drops no blocks in someone's lake;
            // once our head is out, the shore reflex takes us to dry land
            if (!p.isUnderWater()) return Status.SUCCESS;
            Controls.jump(true);
            return Status.RUNNING;
        }
        // clear head room first, from solid footing (mining while swimming is 25x slower);
        // in water, standing on a block we placed counts. Gravel may keep falling in; we just break it again
        boolean footing = p.onGround() || wet && !WorldView.state(feet.below()).canBeReplaced();
        if (footing) {
            for (BlockPos up : new BlockPos[]{feet.above(), feet.above(2)}) {
                var s = WorldView.state(up);
                if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) continue;
                if (!BlockRules.natural(s)) return fail("boxed in under " + Inv.id(s.getBlock().asItem()));
                if (WorldView.state(up.above()).getFluidState().is(FluidTags.LAVA)) return fail("lava above");
                breaker = new Breaker(up);
                return Status.RUNNING;
            }
        }
        int slot = Inv.find(s -> s.getItem() instanceof BlockItem bi && BlockRules.scaffold(bi.getBlock()));
        if (slot < 0) return fail("no junk blocks to climb on");
        var block = Inv.inv().getItem(slot).getItem();
        Controls.jump(true);
        if (wet && !p.onGround()) {
            // swimming: fill the water under us so there's something to stand on
            BlockPos below = feet.below();
            if (WorldView.state(below).canBeReplaced()) Placer.place(block, below);
            stand = null;
            return Status.RUNNING;
        }
        // pillar: jump from solid ground, and once we're above our old spot, put a junk block there
        if (p.onGround()) stand = feet;
        if (stand != null && p.getY() > stand.getY() + 1.05 && WorldView.state(stand).canBeReplaced()) {
            if (Placer.place(block, stand)) stand = null;
        }
        return Status.RUNNING;
    }

    /** Only water between us and open air, within a few blocks: we can just swim up. */
    private static boolean openAbove(BlockPos feet) {
        for (int i = 1; i <= 8; i++) {
            var st = WorldView.state(feet.above(i));
            if (st.isAir()) return true;
            if (!st.getFluidState().is(FluidTags.WATER)) return false;
        }
        return false;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (breaker != null) breaker.cancel();
        Controls.jump(false);
    }
}
