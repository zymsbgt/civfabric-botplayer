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
    /** Stuck, not short of air: head above water isn't out yet (Bot3, a water-filled pit, ten minutes of this). */
    private final boolean escape;

    public UnstuckTask() {
        this(false);
    }

    public UnstuckTask(boolean escape) {
        this(escape, false);
    }

    /** Stranded up high in the open: step off the edge with the shortest safe fall. */
    private final boolean drop;
    private net.minecraft.core.Direction dropDir;

    public UnstuckTask(boolean escape, boolean drop) {
        this.escape = escape;
        this.drop = drop;
    }

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
            fromY = startY;
            Nav.stop();
        }
        if (drop) return stepOff(bot, feet);
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
        if (wet && openAbove(feet) && (p.isUnderWater() || !escape)) {
            // open water: swimming up is enough, and drops no blocks in someone's lake;
            // once our head is out, the shore reflex takes us to dry land
            if (!p.isUnderWater()) return Status.SUCCESS;
            Controls.jump(true);
            return Status.RUNNING;
        }
        // head out, but stuck in this water all the same: build up out of it, below
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

    /**
     * Off a pillar or a platform: of the four ways off, the one whose fall hurts least (water breaks
     * a fall; lava rules a way out), and only if it leaves us more than 6 health. Walk off and land.
     */
    private BlockPos digDown;
    private Breaker digger;
    /** Down past this and on the ground: done. Where we started, or where we stepped off. */
    private int fromY;

    private Status stepOff(Bot bot, BlockPos feet) {
        var p = bot.player();
        // landed below where we stepped off (while digging down, only once we've stepped off at the end)
        if (feet.getY() < fromY && p.onGround()) {
            Controls.forward(false);
            bot.event("down from " + startY + " to " + feet.getY());
            return Status.SUCCESS;
        }
        if (ticks > 20 * 15) {
            Controls.forward(false);
            return fail(dropDir == null ? "stranded, and every way down is too far" : "couldn't step off the edge");
        }
        if (dropDir == null) {
            double best = Double.MAX_VALUE;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos c = feet.relative(d);
                if (!passable(c) || !passable(c.above())) continue;
                int fall = 0;
                BlockPos land = null;
                for (int k = 1; k <= 40; k++) {
                    var st = WorldView.state(c.below(k));
                    if (!WorldView.loaded(c.below(k))) break;
                    if (st.getFluidState().is(FluidTags.LAVA)) break;
                    if (!st.getFluidState().isEmpty()) {
                        land = c.below(k);
                        fall = 0; // water takes the fall
                        break;
                    }
                    if (!passable(c.below(k))) {
                        land = c.below(k);
                        fall = k - 1;
                        break;
                    }
                }
                if (land == null) continue;
                double hurt = Math.max(0, fall - 3);
                if (hurt < best) {
                    best = hurt;
                    dropDir = d;
                }
            }
            if (dropDir == null || p.getHealth() - best <= 6) {
                dropDir = null;
                // on a pillar of our own: down through it a block at a time (Bot1 sat 20 up for 5
                // minutes, its pillar built for a y it never needed and nothing left to stand on)
                BlockPos under = feet.below();
                // natural ground, or any block of a lone column (a plank Baritone capped the pillar with:
                // nobody's floor has air on all four sides)
                boolean column = net.minecraft.core.Direction.Plane.HORIZONTAL.stream().allMatch(d -> passable(under.relative(d)));
                var underState = WorldView.state(under);
                boolean diggable = (BlockRules.natural(underState) || column && !(underState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock))
                        && underState.getDestroySpeed(bot.level(), under) >= 0;
                if (diggable && !passable(under.below()) && !WorldView.state(under.below()).getFluidState().is(FluidTags.LAVA)) {
                    if (digDown == null || !digDown.equals(under)) {
                        if (digDown == null) bot.event("stranded up here: digging down through the pillar");
                        digDown = under.immutable();
                        digger = new Breaker(under);
                    }
                    fromY = Integer.MIN_VALUE;
                    var r = digger.tick();
                    if (r == Breaker.Result.STUCK || r == Breaker.Result.OUT_OF_REACH) return fail("stranded, and can't dig down");
                    ticks = Math.min(ticks, 20 * 10); // each block down is progress
                    return Status.RUNNING;
                }
                return fail("stranded, and every way down is too far");
            }
            bot.event("stepping off to the " + dropDir.getName() + (best > 0 ? " (a fall costing " + (int) best + ")" : ""));
            fromY = feet.getY();
        }
        Nav.stop();
        var target = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet.relative(dropDir).relative(dropDir));
        Controls.lookAt(target.add(0, p.getEyeHeight(), 0), 180f);
        Controls.forward(true);
        return Status.RUNNING;
    }

    private static boolean passable(BlockPos at) {
        var st = WorldView.state(at);
        return (st.isAir() || st.canBeReplaced()) && !st.getFluidState().is(FluidTags.LAVA);
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
        Controls.forward(false);
    }
}
