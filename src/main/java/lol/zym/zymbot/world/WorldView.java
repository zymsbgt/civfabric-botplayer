package lol.zym.zymbot.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Convenience queries against the client world, all safe against unloaded chunks. */
public final class WorldView {
    private WorldView() {}

    public static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    public static boolean loaded(BlockPos pos) {
        ClientLevel l = level();
        return l != null && l.isLoaded(pos) && pos.getY() >= l.getMinBuildHeight() && pos.getY() < l.getMaxBuildHeight();
    }

    public static BlockState state(BlockPos pos) {
        return level().getBlockState(pos);
    }

    public static boolean passable(BlockPos pos) {
        return loaded(pos) && BlockRules.passable(level(), pos, state(pos));
    }

    public static boolean floor(BlockPos pos) {
        return loaded(pos) && BlockRules.floor(level(), pos, state(pos));
    }

    public static boolean water(BlockPos pos) {
        return loaded(pos) && BlockRules.water(state(pos));
    }

    /** Water with more water under it: swimming, not wading. Bots stay out unless they must go in. */
    public static boolean deepWater(BlockPos pos) {
        return water(pos) && water(pos.below());
    }

    /** A player can stand with feet at {@code feet}: two clear blocks and support (floor, water or ladder). */
    public static boolean standable(BlockPos feet) {
        if (!passable(feet) || !passable(feet.above())) return false;
        BlockState here = state(feet);
        if (BlockRules.water(here) || BlockRules.climbable(here)) return true;
        return floor(feet.below());
    }

    /** True if a block is exposed to air/water on at least one side, so it can be reached and mined. */
    public static boolean exposed(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (loaded(n) && (state(n).isAir() || water(n))) return true;
        }
        return false;
    }

    /** Is anything that looks player-built within {@code r} blocks of {@code pos}? */
    public static boolean nearBuilt(BlockPos pos, int r) {
        if (r <= 0) return false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    m.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (loaded(m) && BlockRules.looksBuilt(state(m))) return true;
                }
        return false;
    }

    public static Vec3 center(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    public static boolean isNight() {
        ClientLevel l = level();
        if (l == null) return false;
        long t = l.getDayTime() % 24000L;
        return t >= 12542 && t <= 23460 || l.isThundering();
    }
}
