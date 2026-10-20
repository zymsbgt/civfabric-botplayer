package lol.zym.zymbot.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds blocks matching a predicate around a point by walking loaded chunk sections.
 * Sections whose palette can't contain a match are skipped, so a full 48-block scan is cheap,
 * but it is still spread over several ticks via {@link #step(int)}.
 */
public final class BlockSearch {
    private final Predicate<BlockState> match;
    private final BlockPos origin;
    private final int radius;
    private final int vertical;
    private final Set<Long> exclude;
    private final List<int[]> sections = new ArrayList<>();
    private final List<BlockPos> found = new ArrayList<>();
    private int cursor;

    public BlockSearch(BlockPos origin, int radius, int vertical, Predicate<BlockState> match, Set<Long> exclude) {
        this.origin = origin.immutable();
        this.radius = radius;
        this.vertical = vertical;
        this.match = match;
        this.exclude = exclude;
        ClientLevel level = WorldView.level();
        int cr = (radius >> 4) + 1;
        ChunkPos oc = new ChunkPos(origin);
        int minSec = Math.max(level.getMinSection(), (origin.getY() - vertical) >> 4);
        int maxSec = Math.min(level.getMaxSection() - 1, (origin.getY() + vertical) >> 4);
        for (int dx = -cr; dx <= cr; dx++)
            for (int dz = -cr; dz <= cr; dz++)
                for (int sy = minSec; sy <= maxSec; sy++)
                    sections.add(new int[]{oc.x + dx, oc.z + dz, sy});
        // nearest sections first so early results are the closest ones
        sections.sort(Comparator.comparingInt(s -> {
            int ddx = s[0] - oc.x, ddz = s[1] - oc.z, ddy = s[2] - (origin.getY() >> 4);
            return ddx * ddx + ddz * ddz + ddy * ddy;
        }));
    }

    /** Scan up to {@code budget} sections. Returns true when the whole area has been scanned. */
    public boolean step(int budget) {
        ClientLevel level = WorldView.level();
        if (level == null) return true;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long r2 = (long) radius * radius;
        while (budget-- > 0 && cursor < sections.size()) {
            int[] s = sections.get(cursor++);
            LevelChunk chunk = level.getChunkSource().getChunk(s[0], s[1], ChunkStatus.FULL, false);
            if (chunk == null) continue;
            int idx = level.getSectionIndexFromSectionY(s[2]);
            LevelChunkSection[] secs = chunk.getSections();
            if (idx < 0 || idx >= secs.length) continue;
            LevelChunkSection sec = secs[idx];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(match)) continue;
            int bx = s[0] << 4, by = s[2] << 4, bz = s[1] << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (!match.test(st)) continue;
                        m.set(bx + x, by + y, bz + z);
                        long dx = m.getX() - origin.getX(), dz = m.getZ() - origin.getZ();
                        if (dx * dx + dz * dz > r2 || Math.abs(m.getY() - origin.getY()) > vertical) continue;
                        if (exclude != null && exclude.contains(m.asLong())) continue;
                        found.add(m.immutable());
                    }
        }
        return cursor >= sections.size();
    }

    public boolean done() {
        return cursor >= sections.size();
    }

    /** Matches so far, closest first (by path-ish distance: vertical counts double). */
    public List<BlockPos> results() {
        found.sort(Comparator.comparingDouble(p -> {
            double dx = p.getX() - origin.getX(), dy = (p.getY() - origin.getY()) * 2.0, dz = p.getZ() - origin.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return found;
    }
}
