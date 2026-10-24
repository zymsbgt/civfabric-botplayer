package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.ClassPlan;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pick up the spare crafting tables at the base, keeping the {@code keep} nearest the job board.
 * Until the bots knew Visual Workbench's tables for tables, every craft put down another, and the
 * base filled with them: Bot1 ended up boxed in on top of three, and Bot2 couldn't reach its chest.
 */
public class TidyTablesTask extends Task {
    private static final int RADIUS = 24;
    private final int keep;
    private BlockPos anchor;
    private List<BlockPos> spares;
    private BlockPos current;
    private BlockPos digging;
    private int currentSince;
    private int taken, skipped;

    public TidyTablesTask(int keep) {
        this.keep = Math.max(0, keep);
    }

    @Override
    public String name() {
        return "Tidy tables" + (spares == null ? "" : " " + taken + "/" + (taken + skipped + spares.size()));
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        BlockPos me = bot.player().blockPosition();
        if (anchor == null) {
            anchor = boardPos(bot);
            if (anchor == null) anchor = ClassPlan.teamHome(bot);
            if (anchor == null && bot.memory().home != null) anchor = bot.memory().home.pos();
            if (anchor == null) return fail("no board or base to tidy");
        }
        if (spares == null) {
            if (Math.hypot(me.getX() - anchor.getX(), me.getZ() - anchor.getZ()) > RADIUS / 2.0) {
                Nav.goNear(anchor, 4);
                return ticks > 20 * 180 ? fail("can't get to the base") : Status.RUNNING;
            }
            List<BlockPos> tables = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-RADIUS, -8, -RADIUS), anchor.offset(RADIUS, 8, RADIUS))) {
                if (WorldView.craftingTable(WorldView.state(p))) tables.add(p.immutable());
            }
            tables.sort(Comparator.comparingDouble(p -> p.distSqr(anchor)));
            spares = new ArrayList<>(tables.subList(Math.min(keep, tables.size()), tables.size()));
            bot.event(tables.size() + " crafting tables at the base: keeping " + Math.min(keep, tables.size())
                    + " by " + anchor.toShortString() + ", picking up " + spares.size());
        }
        if (current == null) {
            if (spares.isEmpty()) {
                bot.event("base tidied: picked up " + taken + " tables" + (skipped > 0 ? ", " + skipped + " out of reach" : ""));
                return Status.SUCCESS;
            }
            spares.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
            current = spares.remove(0);
            currentSince = ticks;
        }
        if (!WorldView.craftingTable(WorldView.state(current))) {
            // broken (by us or anyone): the table item is on the ground there
            bot.memory().removeStation(current);
            taken++;
            setChild(bot, new PickupTask(current, 4));
            current = null;
            return Status.RUNNING;
        }
        if (ticks - currentSince > 20 * 40) {
            skip(bot, "couldn't get to it in 40 s");
            return Status.RUNNING;
        }
        if (!Breaker.inReach(current)) {
            Nav.goToBlock(current);
            return Status.RUNNING;
        }
        // in reach: mine it by hand, as a player would. Baritone's builder wouldn't take these on (each
        // was given up at once), with the base too cluttered to plan a path through
        Nav.stop();
        var mc = net.minecraft.client.Minecraft.getInstance();
        var st = WorldView.state(current);
        int tool = lol.zym.zymbot.inv.Inv.bestTool(st);
        if (tool >= 0) lol.zym.zymbot.inv.Inv.select(tool);
        var eye = bot.player().getEyePosition();
        var c = net.minecraft.world.phys.Vec3.atCenterOf(current);
        var face = net.minecraft.core.Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        lol.zym.zymbot.control.Controls.lookAt(c.add(net.minecraft.world.phys.Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), 180f);
        if (!current.equals(digging)) {
            mc.gameMode.startDestroyBlock(current, face);
            digging = current;
        } else {
            mc.gameMode.continueDestroyBlock(current, face);
        }
        bot.player().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return Status.RUNNING;
    }

    /** Leave this one standing; the first few say why, for the log. */
    private void skip(Bot bot, String why) {
        if (skipped < 3) bot.event("left the table at " + current.toShortString() + ": " + why);
        skipped++;
        current = null;
    }

    /** The nearest job board's position within 64 blocks, from the hub's board list. */
    private static BlockPos boardPos(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        BlockPos best = null;
        for (var b : bot.team().boards()) {
            if (!b.has("pos") || !b.get("pos").isJsonObject()) continue;
            var p = b.getAsJsonObject("pos");
            BlockPos at = new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
            if (at.closerThan(me, 64) && (best == null || at.distSqr(me) < best.distSqr(me))) best = at;
        }
        return best;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (digging != null) net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
        digging = null;
        Nav.stop();
    }
}
