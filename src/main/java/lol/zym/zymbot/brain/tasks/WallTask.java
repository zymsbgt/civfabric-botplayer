package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder work that's worth having: a two-high wall in a square round home, with a gap on the
 * north side to walk through. Placing pays Builder XP block by block, and the wall keeps animals
 * and Pokémon off the farmland (they trample it within seconds otherwise).
 */
public class WallTask extends Task {
    private final BlockPos center;
    private final int radius;
    private final Item block;
    private List<BlockPos> todo;
    private int placed;
    private int stuckOn;

    public WallTask(BlockPos center, int radius, Item block) {
        this.center = center.immutable();
        this.radius = radius;
        this.block = block;
    }

    @Override
    public String name() {
        return "Wall round home (" + placed + " placed)";
    }

    private List<BlockPos> plan(Bot bot) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                if (dz == -radius && Math.abs(dx) <= 1) continue; // the way in
                BlockPos col = center.offset(dx, 0, dz);
                BlockPos ground = null;
                for (int dy = 4; dy >= -5; dy--) {
                    BlockPos p = col.above(dy);
                    if (!WorldView.loaded(p)) break;
                    if (WorldView.floor(p.below()) && WorldView.state(p).canBeReplaced() && WorldView.state(p).getFluidState().isEmpty()) {
                        ground = p;
                        break;
                    }
                }
                if (ground == null || WorldView.state(ground.below()).is(Blocks.FARMLAND)) continue;
                if (bot.nearSomeoneElsesBuild(ground)) continue;
                out.add(ground);
                out.add(ground.above());
            }
        }
        return out;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (todo == null) todo = plan(bot);
        if (Inv.count(block) == 0) return placed > 0 ? Status.SUCCESS : fail("out of " + Inv.id(block));
        todo.removeIf(p -> !WorldView.state(p).canBeReplaced());
        if (todo.isEmpty() || placed >= 48) return Status.SUCCESS;
        BlockPos me = bot.player().blockPosition();
        // only spots with something under them (the second course waits for the first), nearest first
        BlockPos next = todo.stream().filter(p -> !WorldView.state(p.below()).canBeReplaced())
                .min(java.util.Comparator.comparingDouble(p -> p.distSqr(me))).orElse(null);
        if (next == null) return placed > 0 ? Status.SUCCESS : fail("nowhere left to build");
        if (next.closerThan(me, 1.5) || next.closerThan(me.above(), 1.5)) {
            // never wall ourselves in: step away first
            Nav.goNear(center, 3);
            return Status.RUNNING;
        }
        if (!Breaker.inReach(next)) {
            Nav.goNear(next, 3);
            if (++stuckOn > 20 * 30) {
                todo.remove(next);
                stuckOn = 0;
            }
            return Status.RUNNING;
        }
        Nav.stop();
        if (ticks % 4 != 0) return Status.RUNNING;
        if (Placer.place(block, next)) {
            placed++;
            stuckOn = 0;
        } else if (++stuckOn > 10) {
            todo.remove(next);
            stuckOn = 0;
        }
        if (ticks > 20 * 300) return Status.SUCCESS;
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
