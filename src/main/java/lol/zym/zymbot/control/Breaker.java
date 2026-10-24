package lol.zym.zymbot.control;

import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block by having Baritone's builder clear it: Baritone walks there, picks the tool
 * and breaks it. Blocks that pathing must never break (crops, our own crafting table) are let
 * off that list for this one break only.
 *
 * <p>CivFabric reinforcements make a block take far longer than its hardness says. If a break
 * runs well past the vanilla estimate the block is reported as {@link Result#STUCK} so the
 * caller can blacklist the position instead of chipping at it forever.
 */
public final class Breaker {
    public enum Result { RUNNING, DONE, STUCK, OUT_OF_REACH }

    private final BlockPos pos;
    private final BlockState initial;
    private int ticks;
    private int limit = -1;
    private int starts;
    private boolean keepHand;
    private boolean active;

    public Breaker(BlockPos pos) {
        this.pos = pos.immutable();
        this.initial = WorldView.state(pos);
    }

    /** Break with whatever is in hand (a hoe for crops, which CivFabric pays out for). */
    public Breaker keepHand() {
        keepHand = true;
        return this;
    }

    public BlockPos pos() {
        return pos;
    }

    public static double reach() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p.blockInteractionRange() - 0.3;
    }

    public static boolean inReach(BlockPos pos) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= reach();
    }

    public Result tick() {
        BlockState now = WorldView.state(pos);
        if (now.isAir() || now.getBlock() != initial.getBlock() || now.canBeReplaced() && !initial.canBeReplaced()) {
            finish();
            return Result.DONE;
        }
        var builder = Nav.baritone().getBuilderProcess();
        // not started yet counts as gave up: a builder still busy with someone else's clearArea left
        // the limit unset (-1), so this one was STUCK on its first tick, and so was every one after
        // (tidying the base skipped all 85 tables in a minute)
        if (!active || !builder.isActive()) {
            // Baritone gave up (or hasn't started): try again a couple of times
            if (starts >= 3) {
                finish();
                return Result.STUCK;
            }
            begin();
        }
        // 4x the vanilla time plus the walk (and slack for lag) means something server-side is resisting
        if (++ticks > limit) {
            cancel();
            return Result.STUCK;
        }
        return Result.RUNNING;
    }

    private void begin() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (limit < 0) {
            float perTick = initial.getDestroyProgress(p, p.level(), pos);
            int breakTicks = perTick <= 0 ? 20 * 60 : (int) Math.ceil(1f / perTick);
            limit = breakTicks * 4 + (int) (Math.sqrt(pos.distToCenterSqr(p.position())) * 12) + 200;
        }
        starts++;
        Nav.stop();
        Nav.allowBreaking(initial.getBlock());
        if (keepHand) Nav.autoTool(false);
        active = true;
        Nav.baritone().getBuilderProcess().clearArea(pos, pos);
    }

    private void finish() {
        if (!active) return;
        active = false;
        Nav.protectAgain(initial.getBlock());
        if (keepHand) Nav.autoTool(true);
    }

    public void cancel() {
        if (active) Nav.stop();
        finish();
    }
}
