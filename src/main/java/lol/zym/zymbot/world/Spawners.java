package lol.zym.zymbot.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Mob spawners and trial spawners in the chunks round us. pacifist-fabric only covers natural
 * spawns: whatever a spawner makes attacks a pacifist like any other player, so a bot treats every
 * mob near one as hostile and keeps its distance (Baritone's spawner avoidance does the paths).
 * The client has the block entities of every loaded chunk; we look again every two seconds.
 */
public final class Spawners {
    private static final int CHUNKS = 4;
    private static final long EVERY_MS = 2000;

    private static List<BlockPos> known = List.of();
    private static ClientLevel scannedIn;
    private static long scannedAt;

    private Spawners() {}

    /** A spawner within {@code r} blocks of {@code pos}. */
    public static boolean near(BlockPos pos, int r) {
        return nearest(pos, r) != null;
    }

    /** The nearest spawner within {@code r} blocks of {@code pos}, or null. */
    public static BlockPos nearest(BlockPos pos, int r) {
        refresh();
        BlockPos best = null;
        for (BlockPos s : known)
            if (s.closerThan(pos, r) && (best == null || s.distSqr(pos) < best.distSqr(pos))) best = s;
        return best;
    }

    private static void refresh() {
        ClientLevel l = WorldView.level();
        long now = System.currentTimeMillis();
        if (l == null || l == scannedIn && now - scannedAt < EVERY_MS) return;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;
        scannedIn = l;
        scannedAt = now;
        List<BlockPos> out = new ArrayList<>();
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        for (int x = cx - CHUNKS; x <= cx + CHUNKS; x++) {
            for (int z = cz - CHUNKS; z <= cz + CHUNKS; z++) {
                LevelChunk c = l.getChunkSource().getChunk(x, z, false);
                if (c == null) continue;
                for (var be : c.getBlockEntities().values())
                    if (be instanceof SpawnerBlockEntity || be instanceof TrialSpawnerBlockEntity) out.add(be.getBlockPos().immutable());
            }
        }
        known = out;
    }
}
