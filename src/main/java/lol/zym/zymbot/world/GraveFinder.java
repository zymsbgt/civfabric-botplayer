package lol.zym.zymbot.world;

import lol.zym.zymbot.Bot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** CivFabric graves: where they are and whose. The owner's name reaches the client for the headstone. */
public final class GraveFinder {
    private static final ResourceLocation GRAVE = ResourceLocation.fromNamespaceAndPath("civfabric", "grave");

    private GraveFinder() {}

    public record Grave(BlockPos pos, String owner) {}

    public static boolean isGrave(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(GRAVE);
    }

    /** Whose grave this is ("" if unknown), or null if there's no grave here. */
    public static String owner(Bot bot, BlockPos pos) {
        var be = bot.level().getBlockEntity(pos);
        if (be == null || !isGrave(be.getBlockState())) return null;
        return be.saveWithoutMetadata(bot.level().registryAccess()).getString("owner");
    }

    /** Graves within {@code r} blocks of us, nearest first. */
    public static List<Grave> near(Bot bot, int r) {
        BlockPos me = bot.player().blockPosition();
        List<Grave> out = new ArrayList<>();
        ChunkPos c = new ChunkPos(me);
        int cr = (r >> 4) + 1;
        for (int x = c.x - cr; x <= c.x + cr; x++) {
            for (int z = c.z - cr; z <= c.z + cr; z++) {
                if (!bot.level().getChunkSource().hasChunk(x, z)) continue;
                for (var be : bot.level().getChunk(x, z).getBlockEntities().values()) {
                    if (!isGrave(be.getBlockState()) || !be.getBlockPos().closerThan(me, r)) continue;
                    out.add(new Grave(be.getBlockPos(), be.saveWithoutMetadata(bot.level().registryAccess()).getString("owner")));
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(g -> g.pos().distSqr(me)));
        return out;
    }
}
