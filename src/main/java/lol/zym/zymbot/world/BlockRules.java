package lol.zym.zymbot.world;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Static judgements about single block states: can a player stand in it, on it, is it dangerous,
 * is it a natural block the bot may break, or does it look like something a player built.
 *
 * <p>"Natural" matters on a Civ server: the bot only ever breaks terrain, never builds.
 */
public final class BlockRules {
    private BlockRules() {}

    /** A player's body can occupy this block (no collision, or a thin carpet-like layer). */
    public static boolean passable(BlockGetter level, BlockPos pos, BlockState state) {
        if (dangerous(state)) return false;
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) return false;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return !state.getFluidState().is(FluidTags.LAVA);
        return shape.max(net.minecraft.core.Direction.Axis.Y) <= 0.1;
    }

    /** The top of this block is something a player can stand on. */
    public static boolean floor(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || dangerous(state)) return false;
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) return false;
        if (state.getBlock() instanceof MagmaBlock) return false;
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) >= 0.5;
    }

    public static boolean climbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE);
    }

    public static boolean water(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    public static boolean dangerous(BlockState state) {
        Block b = state.getBlock();
        return state.getFluidState().is(FluidTags.LAVA)
                || b instanceof BaseFireBlock
                || b instanceof CactusBlock
                || b instanceof SweetBerryBushBlock
                || b instanceof PowderSnowBlock
                || b instanceof WitherRoseBlock
                || b instanceof CampfireBlock
                || b instanceof PointedDripstoneBlock
                || b == Blocks.COBWEB
                || b instanceof MagmaBlock;
    }

    /** Terrain and plants the bot may break. Everything else is assumed to belong to someone. */
    public static boolean natural(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof LiquidBlock) return false;
        Block b = s.getBlock();
        if (b == Blocks.BEDROCK || b == Blocks.SPAWNER || b instanceof BaseEntityBlock) return false;
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || b == Blocks.GRAVEL || b == Blocks.CLAY
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
                || s.is(ConventionalBlockTags.ORES)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.REDSTONE_ORES)
                || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.EMERALD_ORES)
                || s.is(BlockTags.SNOW) || b == Blocks.ICE || b == Blocks.PACKED_ICE
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.REPLACEABLE_BY_TREES)
                || b instanceof TallGrassBlock || b instanceof DoublePlantBlock || b instanceof DeadBushBlock
                || b instanceof MushroomBlock || b instanceof HugeMushroomBlock
                || b == Blocks.PUMPKIN || b == Blocks.MELON || b instanceof SugarCaneBlock
                || b instanceof BambooStalkBlock || b instanceof KelpBlock || b instanceof KelpPlantBlock
                || b instanceof SeagrassBlock || b instanceof VineBlock
                || s.is(BlockTags.TERRACOTTA)
                || b == Blocks.MOSS_BLOCK || b == Blocks.CALCITE || b == Blocks.TUFF || b == Blocks.DRIPSTONE_BLOCK
                || b == Blocks.NETHERRACK || b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL || b == Blocks.GLOWSTONE
                || eroded(b);
    }

    /**
     * TRMT, the erosion mod, wears paths into sand, dirt and grass: eroded blocks 10/16 tall and
     * waterloggable. Baritone can neither stand on a block that short nor walk through it, and with
     * it off the natural list it couldn't break it either: a wall (Bot4 boxed in on a riverbank).
     * They're the ground, so dig through them like the sand under them.
     */
    private static boolean eroded(Block b) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return key.getNamespace().equals("trmt") && key.getPath().startsWith("eroded_");
    }

    /**
     * Blocks that only appear because a player put them there. Used to keep a buffer around
     * other people's builds: the bot neither mines nor hunts near them.
     */
    public static boolean looksBuilt(BlockState s) {
        Block b = s.getBlock();
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS)
                || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.TRAPDOORS)
                || s.is(ConventionalBlockTags.GLASS_BLOCKS) || s.is(ConventionalBlockTags.GLASS_PANES)
                || s.is(ConventionalBlockTags.CHESTS) || s.is(ConventionalBlockTags.BARRELS)
                || s.is(BlockTags.CROPS) || b instanceof FarmBlock || b instanceof TorchBlock
                || WorldView.craftingTable(s) || b instanceof AbstractFurnaceBlock) {
            return true;
        }
        if (b == Blocks.COBBLESTONE || b == Blocks.STONE_BRICKS || b == Blocks.BRICKS || b == Blocks.SMOOTH_STONE) return true;
        // anything from a decoration/furniture mod (Macaw's, Supplementaries...) counts as built
        String ns = BuiltInRegistries.BLOCK.getKey(b).getNamespace();
        return ns.startsWith("mcw") || ns.equals("supplementaries") || ns.equals("suppsquared")
                || ns.equals("toms_storage") || ns.equals("immersive_paintings");
    }

    /** Cobblestone, dirt, netherrack... cheap blocks the bot is happy to place as scaffolding. */
    public static boolean scaffold(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.DIRT || b == Blocks.COBBLED_DEEPSLATE
                || b == Blocks.NETHERRACK || b == Blocks.ANDESITE || b == Blocks.DIORITE || b == Blocks.GRANITE
                || b == Blocks.TUFF;
    }
}
