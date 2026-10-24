package lol.zym.zymbot.control;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

import java.util.Set;

/**
 * A chest won't open under a solid block, and the bots put crafting tables on top of their own
 * chests (Bot4 and Bot5 sat at "Stash 0 stacks" for minutes): one of the blocks we place ourselves
 * up there comes off. Anyone else's block stays, and the chest is given up on as before.
 */
public final class ChestLid {
    private static final Set<Block> OURS = Set.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE,
            Blocks.DIRT, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK);
    private static Breaker breaker;
    private static BlockPos at;

    private ChestLid() {}

    /** Breaking what's on top of {@code chest}: true while that goes on (the caller waits). */
    public static boolean clearing(Bot bot, BlockPos chest) {
        BlockPos up = chest.above();
        var s = WorldView.state(up);
        if (!(WorldView.state(chest).getBlock() instanceof ChestBlock) || !s.isRedstoneConductor(bot.level(), up) || !OURS.contains(s.getBlock())) {
            breaker = null;
            return false;
        }
        if (breaker == null || !up.equals(at)) {
            breaker = new Breaker(up);
            at = up.immutable();
            bot.event("a " + BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + " on top of the chest at " + chest.toShortString()
                    + " keeps it shut: taking it off");
        }
        var r = breaker.tick();
        if (r == Breaker.Result.RUNNING) return true;
        breaker = null;
        return false;
    }

    /** A spot right on top of a chest: nothing goes there. */
    public static boolean onChest(BlockPos spot) {
        return WorldView.state(spot.below()).getBlock() instanceof ChestBlock;
    }
}
