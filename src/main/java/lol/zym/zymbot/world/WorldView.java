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

    /**
     * A crafting table: vanilla's, or Visual Workbench's stand-in, which every table placed in the
     * pack turns into (visualworkbench:minecraft/crafting_table, a BaseEntityBlock with a normal
     * crafting menu). Taken for vanilla's alone, no bot ever saw a table at the base, and each craft
     * put down another.
     */
    public static boolean craftingTable(net.minecraft.world.level.block.state.BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock
                || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace().equals("visualworkbench");
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

    /**
     * The bed is in a closed room: walk the air around it (anything a mob could walk or step through)
     * and never reach the open sky or get more than 8 blocks out. Wooden doors count as gaps: on Hard,
     * zombies break them. Fences, glass and iron doors hold.
     */
    public static boolean enclosed(BlockPos bed) {
        BlockState st = state(bed);
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) return false;
        BlockPos other = bed.relative(net.minecraft.world.level.block.BedBlock.getConnectedDirection(st));
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(java.util.List.of(bed.above(), other.above()));
        java.util.Set<BlockPos> seen = new java.util.HashSet<>(todo);
        while (!todo.isEmpty()) {
            BlockPos c = todo.poll();
            if (!loaded(c) || Math.abs(c.getX() - bed.getX()) > 8 || Math.abs(c.getZ() - bed.getZ()) > 8
                    || Math.abs(c.getY() - bed.getY()) > 4 || level().canSeeSky(c) || seen.size() > 3000) return false;
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (seen.contains(n) || !mobGap(n)) continue;
                seen.add(n);
                todo.add(n);
            }
        }
        return true;
    }

    /** A mob gets through (or steps over) this block. */
    private static boolean mobGap(BlockPos pos) {
        if (!loaded(pos)) return true;
        BlockState s = state(pos);
        if (s.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)) return true;
        if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) return false;
        var shape = s.getCollisionShape(level(), pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.6;
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
