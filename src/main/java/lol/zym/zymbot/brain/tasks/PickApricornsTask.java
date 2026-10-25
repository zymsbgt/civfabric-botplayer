package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.Sources;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.BlockSearch;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pick ripe apricorns off Cobblemon's apricorn trees by right-click, as a player does: the apricorn
 * grows back, where breaking it off would leave the tree bare. Every pick rolls on the picker's
 * Farmer rank under CivFabric, and a failed roll drops nothing, so we count what we hold, not picks.
 * Until this, a Poké Ball had "no known way" to be made and its posts went round the team.
 */
public class PickApricornsTask extends Task {
    private final Set<Block> blocks;
    private final Predicate<ItemStack> match;
    private final int until;
    private final String label;
    private BlockSearch search;
    private BlockPos target;
    private int targetSince;
    private int clickedAt = -1;
    private int explores;
    /** Unripe ones seen on the last search: the trees are here, the fruit isn't yet. */
    private int unripe;

    public PickApricornsTask(List<Block> blocks, Predicate<ItemStack> match, int until) {
        this.blocks = new HashSet<>(blocks);
        this.match = match;
        this.until = until;
        this.label = BuiltInRegistries.BLOCK.getKey(blocks.get(0)).getPath() + (blocks.size() > 1 ? "+" : "");
    }

    @Override
    public String name() {
        return "Pick " + label + " " + Inv.count(match) + "/" + until + (target != null ? " @" + target.toShortString() : "");
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            if (runChild(bot) != Status.RUNNING) search = null;
            return Status.RUNNING;
        }
        if (Inv.count(match) >= until) return Status.SUCCESS;
        if (target == null) return findTarget(bot);

        BlockState s = WorldView.state(target);
        if (!blocks.contains(s.getBlock()) || !Sources.ripe(s)) {
            // picked (by us or anyone): the fruit pops out beside it, if the roll let it
            Nav.stop();
            bot.memory().count("apricorns_picked");
            setChild(bot, new PickupTask(target, 4));
            target = null;
            clickedAt = -1;
            return Status.RUNNING;
        }
        if (ticks - targetSince > 20 * 40) {
            bot.memory().blacklist(target, 10 * 60_000L);
            bot.event("couldn't pick the " + label + " at " + target.toShortString() + ": leaving it");
            Nav.stop();
            target = null;
            return Status.RUNNING;
        }
        if (!Breaker.inReach(target)) {
            // they hang under the canopy: stand on the ground beneath, and only climb if that's short
            BlockPos ground = groundUnder(target);
            if (ground != null && !bot.player().blockPosition().closerThan(ground, 1.5)) Nav.goNear(ground, 1);
            else Nav.goToBlock(target);
            return Status.RUNNING;
        }
        Nav.stop();
        // one click, then give the server a moment to answer before clicking again
        if (clickedAt < 0 || ticks - clickedAt > 20) {
            var eye = bot.player().getEyePosition();
            var c = net.minecraft.world.phys.Vec3.atCenterOf(target);
            if (Placer.useOn(target, Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z))) clickedAt = ticks;
        }
        return Status.RUNNING;
    }

    private Status findTarget(Bot bot) {
        if (search == null) {
            unripe = 0;
            search = new BlockSearch(bot.player().blockPosition(), BotConfig.get().searchRadius, 32,
                    st -> blocks.contains(st.getBlock()), bot.memory().blacklistKeys());
        }
        if (!search.step(24)) return Status.RUNNING;
        BlockPos me = bot.player().blockPosition();
        BlockPos best = null;
        for (BlockPos p : search.results()) {
            if (!Sources.ripe(WorldView.state(p))) {
                unripe++;
                continue;
            }
            if (bot.nearSomeoneElsesBuild(p)) continue;
            if (best == null || p.distSqr(me) < best.distSqr(me)) best = p;
        }
        search = null;
        if (best != null) {
            target = best.immutable();
            targetSince = ticks;
            clickedAt = -1;
            bot.lastAction("pick:" + BuiltInRegistries.BLOCK.getKey(WorldView.state(target).getBlock()));
            return Status.RUNNING;
        }
        if (unripe > 0 && explores == 0) bot.event(unripe + " " + label + " about, none ripe yet: looking further");
        if (++explores > 6) return fail(unripe > 0 ? "no ripe " + label + " (" + unripe + " still growing)" : "no " + label + " found after exploring");
        // somewhere we've seen apricorn trees beats wandering, if it's within radio range of the base
        var known = lol.zym.zymbot.memory.Landmarks.nearest(bot, "apricorns"::equals, 256);
        BlockPos anchor = ExploreTask.anchor(bot);
        if (known != null && anchor != null && !known.at.pos().closerThan(anchor, 130)) known = null;
        if (known != null && !known.at.pos().closerThan(me, 24)) {
            bot.event("heading for apricorns seen at " + known.at.pos().toShortString());
            setChild(bot, new GoToTask(known.at.pos(), 6));
            return Status.RUNNING;
        }
        setChild(bot, new ExploreTask(64));
        return Status.RUNNING;
    }

    /** The first spot to stand on below an apricorn, or null if there's none within 8 blocks. */
    private static BlockPos groundUnder(BlockPos p) {
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos q = p.below(dy);
            BlockState s = WorldView.state(q);
            if (s.isAir() || s.canBeReplaced()) continue;
            if (s.is(net.minecraft.tags.BlockTags.LEAVES)) return null; // inside the canopy
            return q.above();
        }
        return null;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
