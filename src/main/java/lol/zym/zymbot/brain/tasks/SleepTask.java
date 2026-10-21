package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Go to our bed and sleep in it, or with {@code claimOnly} just right-click it: CivFabric gives a
 * bed to the first player who clicks it, at any hour, and that claim is what makes it our respawn
 * point. CivFabric's bed rest ends early when the bot is too hungry or the night is nearly over;
 * those messages make the brain stop trying for the night.
 *
 * <p>Every refusal (vanilla's and CivFabric's) arrives on the action bar, so it is read back from
 * what the server said and named in the failure.
 */
public class SleepTask extends Task {
    private static final String[] REFUSALS = {"You may not rest now", "This bed is", "You can sleep only", "already claimed"};

    private final BlockPos bed;
    private final boolean claimOnly;
    private long clickedAt = -1;
    private int clicks;
    private boolean read;

    public SleepTask(BlockPos bed) {
        this(bed, false);
    }

    public SleepTask(BlockPos bed, boolean claimOnly) {
        this.bed = bed;
        this.claimOnly = claimOnly;
    }

    @Override
    public String name() {
        return (claimOnly ? "Claim bed at " : "Sleep at ") + bed.toShortString();
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (claimOnly) {
            if (Boolean.TRUE.equals(bot.memory().bedClaimed) || p.isSleeping()) return Status.SUCCESS;
        } else {
            if (!WorldView.isNight() && !p.isSleeping()) return Status.SUCCESS;
            if (p.isSleeping()) return ticks > 20 * 60 * 6 ? Status.SUCCESS : Status.RUNNING;
        }
        if (bot.memory().bed == null) return fail("bed taken"); // the server said it's someone else's
        if (!WorldView.state(bed).is(BlockTags.BEDS)) {
            bot.memory().bed = null;
            bot.memory().bedClaimed = false;
            bot.event("my bed at " + bed.toShortString() + " is gone");
            return fail("bed gone");
        }
        if (!inBedRange(p.position())) {
            Nav.goToBlock(bed);
            if (ticks > 20 * 120) return fail("can't reach bed");
            return Status.RUNNING;
        }
        Nav.stop();
        // what the server made of the last click
        if (clickedAt >= 0 && !read && bot.ticks() - clickedAt >= 20) {
            read = true;
            String said = bot.lastHeard(clickedAt, REFUSALS);
            if (said != null) {
                said = said.replace("[CivLabs] » ", "").trim();
                if (said.contains("too far away")) clickedAt = -1; // step closer: GoalGetToBlock can stop short
                else if (said.contains("sleep only")) {
                    // daytime: the click still claimed it. Ask the server to be sure
                    if (claimOnly && clicks == 1) bot.player().connection.sendCommand("bed");
                    else if (!claimOnly) return Status.SUCCESS;
                } else return fail("couldn't get into bed: \"" + said + "\"");
            } else if (claimOnly) {
                bot.player().connection.sendCommand("bed");
            }
        }
        if (clickedAt >= 0 && bot.ticks() - clickedAt < 60) return Status.RUNNING;
        if (clicks >= 4) return fail(claimOnly ? "couldn't claim bed (the server didn't confirm it)" : "couldn't get into bed (the server gave no reason)");
        if (clickedAt < 0 && !inBedRange(p.position())) return Status.RUNNING;
        Placer.use(bed);
        bot.lastAction("use:bed");
        clickedAt = bot.ticks();
        read = false;
        clicks++;
        return Status.RUNNING;
    }

    /**
     * Vanilla's own test: within 3 blocks across and 2 up or down of either half of the bed. Reach
     * isn't enough, so a bot clicking from 4 away got "too far away" every night.
     */
    private boolean inBedRange(Vec3 at) {
        var st = WorldView.state(bed);
        BlockPos other = st.getBlock() instanceof BedBlock ? bed.relative(BedBlock.getConnectedDirection(st)) : bed;
        return near(at, bed) || near(at, other);
    }

    private static boolean near(Vec3 at, BlockPos b) {
        Vec3 c = Vec3.atBottomCenterOf(b);
        return Math.abs(at.x - c.x) <= 2.5 && Math.abs(at.y - c.y) <= 1.5 && Math.abs(at.z - c.z) <= 2.5;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
