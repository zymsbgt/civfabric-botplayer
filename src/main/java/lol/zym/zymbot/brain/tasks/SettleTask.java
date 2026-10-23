package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;

/**
 * Put a bed down (making this spot home) and click it, so CivFabric's bed claim registers it as
 * ours and it becomes our respawn point. Picks a flat 2-block spot away from anyone else's build,
 * the nearest to the team's base: on a slope the four spots around wherever the walk there stopped
 * were often all uneven (Bot1, on the hill above Bot2's bed), so it walks to one that fits.
 */
public class SettleTask extends Task {
    private BlockPos placedAt;
    private boolean claiming;
    /** Standing spots already walked to (or tried to), so a spot that didn't work isn't picked again. */
    private final java.util.Set<BlockPos> tried = new java.util.HashSet<>();

    @Override
    public String name() {
        return "Settle (place bed)";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (claiming) {
            // a click claims it, at any hour; if that didn't take, the claim_bed step tries again
            return runChild(bot) == Status.RUNNING ? Status.RUNNING : Status.SUCCESS;
        }
        if (placedAt != null) {
            if (WorldView.state(placedAt).is(net.minecraft.tags.BlockTags.BEDS)) {
                bot.memory().bed = new BotMemory.Pos(placedAt, bot.dim());
                bot.memory().bedClaimed = false; // not until the server confirms the click
                bot.memory().bedWarm = false;
                if (bot.memory().home == null) bot.memory().home = new BotMemory.Pos(placedAt, bot.dim());
                bot.event("settled: bed at " + placedAt.toShortString());
                bot.memory().save();
                setChild(bot, new SleepTask(placedAt, true));
                claiming = true;
                return Status.RUNNING;
            }
            if (ticks > 40) return fail("bed didn't place");
            return Status.RUNNING;
        }
        if (Inv.find(s -> s.is(ItemTags.BEDS)) < 0) return fail("no bed item");
        if (bot.nearSomeoneElsesBuild(bot.player().blockPosition())) {
            setChild(bot, new ExploreTask(32));
        }
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        // beds go at the team's base, so the team sleeps (and keeps watch) together
        BlockPos team = lol.zym.zymbot.brain.ClassPlan.teamHome(bot);
        if (team != null && !team.closerThan(bot.player().blockPosition(), 16)) {
            setChild(bot, new GoToTask(team, 8));
            return Status.RUNNING;
        }
        BlockPos feet = bot.player().blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos a = feet.relative(d, 2), b = a.relative(d);
            if (bedFits(feet, d)) {
                lol.zym.zymbot.control.Controls.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(b), 180f);
                var bedItem = Inv.inv().getItem(Inv.find(s -> s.is(ItemTags.BEDS))).getItem();
                if (Placer.place(bedItem, a)) {
                    placedAt = a;
                    ticks = 0;
                    return Status.RUNNING;
                }
            }
        }
        if (tried.size() < 6) {
            BlockPos spot = spot(bot, team != null ? team : feet);
            if (spot != null) {
                tried.add(spot);
                setChild(bot, new GoToTask(spot, 0));
                return Status.RUNNING;
            }
        }
        if (ticks > 200) return fail("nowhere flat for a bed");
        if (ticks % 60 == 0) setChild(bot, new ExploreTask(12));
        return Status.RUNNING;
    }

    /** A bed fits two blocks ahead of {@code feet}: two replaceable blocks on floor, with room above. */
    private static boolean bedFits(BlockPos feet, Direction d) {
        BlockPos a = feet.relative(d, 2), b = a.relative(d);
        return WorldView.state(a).canBeReplaced() && WorldView.state(b).canBeReplaced()
                && WorldView.floor(a.below()) && WorldView.floor(b.below())
                && WorldView.passable(a.above()) && WorldView.passable(b.above());
    }

    /** The nearest place to {@code around} to stand and put a bed down from, or null. */
    private BlockPos spot(Bot bot, BlockPos around) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos s : BlockPos.betweenClosed(around.offset(-10, -4, -10), around.offset(10, 4, 10))) {
            double dd = s.distSqr(around);
            if (dd >= bestD || tried.contains(s) || !WorldView.standable(s) || WorldView.water(s)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (bedFits(s, d) && !bot.nearSomeoneElsesBuild(s.relative(d, 2))) {
                    best = s.immutable();
                    bestD = dd;
                    break;
                }
            }
        }
        return best;
    }
}
