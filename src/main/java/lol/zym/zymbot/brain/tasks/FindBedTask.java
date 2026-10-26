package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find the bed CivFabric says is ours when we don't know where it is (a bed claimed before this
 * memory, as Wandersong's was). The server won't give coordinates: {@code /bed} answers "Your bed is
 * 212 blocks to the south-west", or "right here" within 4 blocks. So walk the distance that way and
 * ask again on the way, each answer closer and so truer (an eighth of the compass is wide from far
 * off), until it says right here; then a click on each bed there, nearest first, until one takes it.
 *
 * <p>Without this the progression made a new bed and settled on it, and the click that claims it
 * would have let go of the bed the player had.
 */
public class FindBedTask extends Task {
    private static final Pattern HEADING = Pattern.compile("Your bed is (\\d+) blocks to the ([a-z-]+)");
    private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
    /** Given up on recently: the progression makes a bed of its own after all, rather than keep looking. */
    private static long gaveUpAt;

    private final boolean sleepAfter;
    private long askedTick = -1;
    private int askedAt;
    private int asks;
    private BlockPos estimate;
    private int lastDistance;
    /** Beds where ours should be, still to click: head halves, nearest first. */
    private java.util.List<BlockPos> candidates;
    private long clickedTick = -1;
    private int clicks;
    private int walkSince;

    public FindBedTask(boolean sleepAfter) {
        this.sleepAfter = sleepAfter;
    }

    public static boolean gaveUpRecently() {
        return System.currentTimeMillis() - gaveUpAt < 30 * 60_000L;
    }

    @Override
    public String name() {
        return "Find our bed" + (estimate == null ? "" : " near " + estimate.getX() + ", " + estimate.getZ() + " (" + lastDistance + " off)");
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) return runChild(bot);
        if (bot.memory().bed != null) return Status.SUCCESS;
        if (candidates != null) return check(bot);
        if (ticks > 20 * 60 * 6) return giveUp("not found in 6 minutes");
        var p = bot.player();
        if (askedTick >= 0) {
            String reply = bot.lastHeard(askedTick, "Your bed is", "You have no bed", "You have a bed in the");
            if (reply == null) {
                if (ticks - askedAt > 100) askedTick = -1; // no answer: ask again
                return Status.RUNNING;
            }
            askedTick = -1;
            if (reply.contains("no bed") || reply.contains("is gone")) return giveUp("the server says we have no bed");
            if (reply.contains("a bed in the")) return giveUp("our bed is in another dimension");
            if (reply.contains("right here")) return found(bot);
            Matcher m = HEADING.matcher(reply);
            int dir = m.find() ? java.util.Arrays.asList(COMPASS).indexOf(m.group(2)) : -1;
            if (dir < 0) return giveUp("couldn't read \"" + reply + "\"");
            lastDistance = Integer.parseInt(m.group(1));
            double a = Math.toRadians(dir * 45.0);
            // north is -z; the bearing runs clockwise through east (+x), as CivFabric's Bearings does
            estimate = new BlockPos((int) Math.round(p.getX() + Math.sin(a) * lastDistance), p.getBlockY(),
                    (int) Math.round(p.getZ() - Math.cos(a) * lastDistance));
            if (asks == 1) bot.event("our bed is " + lastDistance + " blocks " + m.group(2) + ": going to look");
            Nav.goXZ(estimate.getX(), estimate.getZ());
            return Status.RUNNING;
        }
        // ask at the start, at the spot we were aiming for, halfway there (closer is truer), and when
        // the walk has stalled
        double off = estimate == null ? 0 : Math.hypot(estimate.getX() - p.getX(), estimate.getZ() - p.getZ());
        boolean ask = estimate == null || off < Math.max(4, lastDistance / 2.0) || ticks - askedAt > 20 * 30 || !Nav.busy() && ticks - askedAt > 60;
        if (ask) {
            if (++asks > 20) return giveUp("still not there after 20 asks");
            p.connection.sendCommand("bed");
            askedTick = bot.ticks();
            askedAt = ticks;
        } else Nav.goXZ(estimate.getX(), estimate.getZ());
        return Status.RUNNING;
    }

    /**
     * Within 4 blocks of it: the beds that fit, nearest first, to be clicked in turn. Another
     * player's refuses the click ("already claimed by another player"); ours lets us lie down, or
     * says it's day. An unclaimed bed takes the click as a claim, letting ours go, and the server
     * says nothing: so only beds the "right here" fits are tried, and with one there's no doubt.
     */
    private Status found(Bot bot) {
        var p = bot.player();
        BlockPos me = p.blockPosition();
        java.util.Set<BlockPos> heads = new java.util.HashSet<>();
        // the distance is flat: the bed can be a floor or a cellar away, so look well up and down
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-6, -16, -6), me.offset(6, 16, 6))) {
            var st = WorldView.state(q);
            if (!(st.getBlock() instanceof BedBlock)) continue;
            BlockPos head = st.getValue(BedBlock.PART) == BedPart.HEAD ? q.immutable() : q.relative(BedBlock.getConnectedDirection(st)).immutable();
            // CivFabric rounds the distance to the head's centre: "right here" is under 4.5
            if (Math.hypot(head.getX() + 0.5 - p.getX(), head.getZ() + 0.5 - p.getZ()) < 4.5) heads.add(head);
        }
        if (heads.isEmpty()) return giveUp("the server says it's right here, but there's no bed in sight");
        candidates = new java.util.ArrayList<>(heads);
        candidates.sort(java.util.Comparator.comparingDouble(h -> h.distSqr(me)));
        Nav.stop();
        walkSince = ticks;
        if (candidates.size() > 1) bot.event(candidates.size() + " beds where ours should be: clicking each to find it");
        return Status.RUNNING;
    }

    /** Click the next candidate and read what the server makes of it. */
    private Status check(Bot bot) {
        if (candidates.isEmpty()) return giveUp("every bed where ours should be is someone else's");
        var p = bot.player();
        BlockPos bed = candidates.get(0);
        if (clickedTick >= 0) {
            if (bot.ticks() - clickedTick < 30) return Status.RUNNING;
            String said = bot.lastHeard(clickedTick, "already claimed", "too far away");
            clickedTick = -1;
            if (said != null && said.contains("already claimed")) {
                bot.event("the bed at " + bed.toShortString() + " is someone else's");
                candidates.remove(0);
                clicks = 0;
                walkSince = ticks;
                return Status.RUNNING;
            }
            if (said == null || clicks >= 3) return accept(bot, bed);
            // too far for vanilla: step closer and click again
        }
        if (!nearBed(p.position(), bed)) {
            if (ticks - walkSince > 20 * 30) {
                bot.event("can't get to the bed at " + bed.toShortString() + "; trying the next");
                candidates.remove(0);
                clicks = 0;
                walkSince = ticks;
                return Status.RUNNING;
            }
            Nav.goNear(bed, 1);
            return Status.RUNNING;
        }
        Nav.stop();
        Placer.use(bed);
        bot.lastAction("use:bed");
        clickedTick = bot.ticks();
        clicks++;
        return Status.RUNNING;
    }

    /** Vanilla's reach for a bed: within 3 across and 2 up of either half (a little inside it). */
    private static boolean nearBed(Vec3 at, BlockPos head) {
        var st = WorldView.state(head);
        BlockPos foot = st.getBlock() instanceof BedBlock ? head.relative(BedBlock.getConnectedDirection(st)) : head;
        for (BlockPos b : new BlockPos[]{head, foot}) {
            Vec3 c = Vec3.atBottomCenterOf(b);
            if (Math.abs(at.x - c.x) <= 2.5 && Math.abs(at.y - c.y) <= 1.5 && Math.abs(at.z - c.z) <= 2.5) return true;
        }
        return false;
    }

    private Status accept(Bot bot, BlockPos best) {
        var p = bot.player();
        // a click at night lays us down: up again unless sleeping is what we came for
        if (p.isSleeping() && !sleepAfter)
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        bot.memory().bed = new BotMemory.Pos(best, bot.dim());
        bot.memory().bedClaimed = true;
        bot.memory().bedWarm = false; // WarmBedTask looks for the campfire beside it
        if (bot.memory().home == null) bot.memory().home = new BotMemory.Pos(best, bot.dim());
        bot.memory().save();
        bot.event("found our bed at " + best.toShortString() + " (it took our click)");
        if (sleepAfter) {
            setChild(bot, new SleepTask(best, false));
            return Status.RUNNING;
        }
        return Status.SUCCESS;
    }

    private Status giveUp(String why) {
        gaveUpAt = System.currentTimeMillis();
        return fail(why);
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
