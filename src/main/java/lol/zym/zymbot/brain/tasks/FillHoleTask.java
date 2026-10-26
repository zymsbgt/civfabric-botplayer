package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.BlockRules;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fill in the hole we're standing in: a pit dug for stone, or a shelter left open. Ground level is
 * the terrain around it; every open block below that, joined to where we stand and within 4 across,
 * gets a block, the bottom first, and the last ones go under our own feet as we build up out of it.
 * It won't start without blocks enough for all of it: half done, it can't climb out.
 *
 * <p>On a slope the ground around doesn't say where the hole ends (it opens downhill into the air),
 * so a box can be given instead: every open block in it, with one block for all of it if named.
 */
public class FillHoleTask extends Task {
    private static final int RADIUS = 4;
    private static final int MOST = 64;

    private List<BlockPos> hole;
    private final Set<BlockPos> skipped = new HashSet<>();
    private BlockPos trying;
    private int tryingSince;
    private BlockPos stand;
    private int placed;
    private final BlockPos boxFrom, boxTo;
    private final Item only;

    public FillHoleTask() {
        this(null, null, null);
    }

    public FillHoleTask(BlockPos from, BlockPos to, Item only) {
        this.boxFrom = from;
        this.boxTo = to;
        this.only = only;
    }

    @Override
    public String name() {
        return "Fill in the hole" + (hole == null ? "" : " (" + placed + "/" + (placed + hole.size()) + ")");
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        BlockPos feet = p.blockPosition();
        if (hole == null) {
            Nav.stop();
            String where;
            if (boxFrom != null) {
                hole = new ArrayList<>();
                for (BlockPos q : BlockPos.betweenClosed(boxFrom, boxTo)) if (open(q)) hole.add(q.immutable());
                if (hole.size() > MOST) return fail(hole.size() + " open blocks in that box: more than a hole");
                if (hole.isEmpty()) return fail("nothing open in that box");
                where = "in " + boxFrom.toShortString() + " to " + boxTo.toShortString();
            } else {
                Integer ground = groundLevel(feet);
                if (ground == null) return fail("can't tell where the ground is here");
                hole = holeAt(feet, ground);
                if (hole == null) return fail("this opening is too big to be our hole (a cave, or a dent in a slope: give a box)");
                if (hole.isEmpty()) return fail("not in a hole: we're at ground level");
                where = "up to ground level at y " + ground;
            }
            int have = only != null ? Inv.count(only) : Inv.count(FillHoleTask::filler);
            if (have < hole.size()) return fail(hole.size() + " blocks to fill but only " + have + " "
                    + (only != null ? Inv.id(only) : "dirt and junk blocks") + " to fill it with");
            bot.event(hole.size() + " blocks to fill, " + where);
        }
        hole.removeIf(c -> !open(c));
        if (hole.isEmpty()) {
            Controls.jump(false);
            bot.event("hole filled (" + placed + " blocks)");
            return Status.SUCCESS;
        }
        if (ticks > 20 * 180) return done(bot, "ran out of time");
        Item block = only != null ? (Inv.has(only) ? only : null) : filler();
        if (block == null) return done(bot, "out of " + (only != null ? Inv.id(only) : "dirt and junk blocks"));

        // the lowest still open, the furthest from us first, leaving our own column for last
        BlockPos next = null;
        for (BlockPos c : hole) {
            if (skipped.contains(c)) continue;
            if (next == null || c.getY() < next.getY()
                    || c.getY() == next.getY() && mine(next, feet) && !mine(c, feet)
                    || c.getY() == next.getY() && mine(next, feet) == mine(c, feet) && c.distSqr(feet) > next.distSqr(feet)) next = c;
        }
        if (next == null) return done(bot, "couldn't reach the rest");
        if (!next.equals(trying)) {
            trying = next;
            tryingSince = ticks;
        } else if (ticks - tryingSince > 20 * 8) {
            skipped.add(next);
            trying = null;
            return Status.RUNNING;
        }

        if (mine(next, feet)) {
            // under us: pillar, a block where we stood once we're up off it
            Controls.jump(true);
            if (p.onGround()) stand = feet;
            if (stand != null && p.getY() > stand.getY() + 1.05 && open(stand) && Placer.place(block, stand)) {
                placed++;
                stand = null;
            }
            return Status.RUNNING;
        }
        Controls.jump(false);
        if (!Placer.placeable(next)) {
            Nav.goNear(next, 2);
            return Status.RUNNING;
        }
        Nav.stop();
        if (ticks % 4 == 0 && Placer.place(block, next)) placed++;
        return Status.RUNNING;
    }

    /** Our own column: the block at our feet or our head, which only pillaring can fill. */
    private static boolean mine(BlockPos c, BlockPos feet) {
        return c.getX() == feet.getX() && c.getZ() == feet.getZ() && (c.getY() == feet.getY() || c.getY() == feet.getY() + 1);
    }

    private static boolean open(BlockPos c) {
        var s = WorldView.state(c);
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty();
    }

    /** The middle of the surface heights 3 to 5 blocks out: the ground the hole was dug into. */
    private static Integer groundLevel(BlockPos at) {
        List<Integer> tops = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 3) continue;
                for (int y = at.getY() + 12; y >= at.getY() - 4; y--) {
                    BlockPos q = at.offset(dx, y - at.getY(), dz);
                    if (!open(q) && !tree(q) && open(q.above())) {
                        tops.add(y);
                        break;
                    }
                }
            }
        if (tops.size() < 10) return null;
        tops.sort(null);
        return tops.get(tops.size() / 2);
    }

    private static boolean tree(BlockPos q) {
        var s = WorldView.state(q);
        return s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS) || !s.isCollisionShapeFullBlock(net.minecraft.client.Minecraft.getInstance().level, q);
    }

    /** The open blocks joined to where we stand, at or below ground level; null if it runs on too far. */
    private static List<BlockPos> holeAt(BlockPos start, int ground) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            if (c.getY() > ground || !open(c)) continue;
            if (Math.abs(c.getX() - start.getX()) > RADIUS || Math.abs(c.getZ() - start.getZ()) > RADIUS) continue;
            out.add(c);
            if (out.size() > MOST) return null;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (seen.add(n)) queue.add(n);
            }
        }
        return out;
    }

    /** What a hole can be filled with: dirt, gravel and the junk blocks pathing builds with. */
    private static boolean filler(net.minecraft.world.item.ItemStack s) {
        return s.is(Blocks.DIRT.asItem()) || s.is(Blocks.COARSE_DIRT.asItem()) || s.is(Blocks.GRAVEL.asItem())
                || s.getItem() instanceof BlockItem bi && BlockRules.scaffold(bi.getBlock());
    }

    /** Dirt first (what a hole in the ground was), then the rest. */
    private static Item filler() {
        for (Item i : new Item[]{Blocks.DIRT.asItem(), Blocks.COARSE_DIRT.asItem()})
            if (Inv.find(s -> s.is(i)) >= 0) return i;
        int slot = Inv.find(FillHoleTask::filler);
        return slot < 0 ? null : Inv.inv().getItem(slot).getItem();
    }

    private Status done(Bot bot, String why) {
        Controls.jump(false);
        return fail(why + " with " + hole.size() + " left to fill");
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Controls.jump(false);
        Nav.stop();
    }
}
