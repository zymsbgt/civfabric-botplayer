package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.farm.PlantRules;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.material.Fluids;

import java.util.*;

/**
 * Break new ground for the team's first farm: pick a spot beside water we didn't have to carry
 * (buckets need Blacksmith 3), under open sky, where Realistic Plant Growth lets our seeds grow,
 * mark the plots round it and hand over to {@link FarmTask} to till and sow them. The spot
 * becomes home if we don't have one.
 */
public class LayFieldTask extends Task {
    /** Plots one water block keeps wet: 4 out in every direction, at its own level. */
    private static final int WET = 4;
    public static final int PLOTS = 24;

    private BlockPos water;
    private int explores;

    @Override
    public String name() {
        return water == null ? "Lay out a field: looking for water" : "Lay out a field by " + water.toShortString();
    }

    public static List<Block> seedCrops() {
        LinkedHashSet<Block> out = new LinkedHashSet<>();
        for (int i = 0; i < 36; i++) {
            var s = Inv.inv().getItem(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock c) out.add(c);
        }
        return new ArrayList<>(out);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.RUNNING || water == null) return Status.RUNNING; // explored: look again
            return s; // a round of tilling and sowing is done; the milestone checks the plot count
        }
        List<Block> crops = seedCrops();
        if (crops.isEmpty()) return fail("no seeds to plant");
        if (water == null) {
            // the base a leader assigned us to, else our own home, else here
            BlockPos base = lol.zym.zymbot.brain.ClassPlan.teamHome(bot);
            BlockPos from = base != null ? base : bot.memory().home != null ? bot.memory().home.pos() : bot.player().blockPosition();
            water = findSite(bot, from, crops);
            // back from a walk to water we'd seen: look around here, not the base again (the base can
            // sit on a hill 60 blocks above its river), so long as it's a field the base can use
            if (water == null && explores > 0 && bot.player().blockPosition().closerThan(from, 96))
                water = findSite(bot, bot.player().blockPosition(), crops);
            if (water == null) {
                var shore = lol.zym.zymbot.memory.Landmarks.nearest(bot, "water"::equals, 200);
                if (shore != null && !shore.at.pos().closerThan(bot.player().blockPosition(), 30) && explores < 3) {
                    explores++;
                    bot.event("going to look at the water seen at " + shore.at.pos().toShortString());
                    setChild(bot, new GoToTask(shore.at.pos(), 4));
                    return Status.RUNNING;
                }
                if (++explores > 4) return fail("no water with open ground round it nearby");
                setChild(bot, new ExploreTask(48));
                return Status.RUNNING;
            }
            var mem = bot.memory();
            if (mem.home == null) mem.home = new BotMemory.Pos(water, bot.dim());
            int marked = 0;
            for (BlockPos p : plots(bot, water)) {
                if (marked >= PLOTS) break;
                if (mem.planting(bot.dim(), p) == null) mem.planted(bot.dim(), p, null, 0);
                marked++;
            }
            mem.save();
            bot.event("laying out a field of " + marked + " plots by the water at " + water.toShortString());
        }
        setChild(bot, new FarmTask(water, WET, 6));
        return Status.RUNNING;
    }

    /** Grass or dirt at the water's level, wet from it, open to the sky, nobody else's. Nearest the water first. */
    private static List<BlockPos> plots(Bot bot, BlockPos water) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(water.offset(-WET, 0, -WET), water.offset(WET, 0, WET))) {
            var s = WorldView.state(p);
            if (!(s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.COARSE_DIRT))) continue;
            BlockPos above = p.above();
            if (!(WorldView.state(above).isAir() || WorldView.state(above).canBeReplaced()) || !bot.level().canSeeSky(above)) continue;
            if (bot.nearSomeoneElsesBuild(p)) continue;
            out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(water)));
        return out;
    }

    private static BlockPos findSite(Bot bot, BlockPos from, List<Block> crops) {
        BlockPos best = null;
        double bestScore = 0;
        boolean rules = PlantRules.active();
        // well below as well: a base on a rise has its water down the slope
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-40, -20, -40), from.offset(40, 8, 40))) {
            if (!WorldView.loaded(p) || !WorldView.state(p).getFluidState().is(Fluids.WATER)) continue;
            if (!bot.level().canSeeSky(p.above())) continue;
            // a pond or river edge, not open sea: some land right beside it
            int land = 0;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
                if (WorldView.state(p.relative(d)).is(Blocks.GRASS_BLOCK) || WorldView.state(p.relative(d)).is(Blocks.DIRT)) land++;
            if (land == 0) continue;
            BlockPos wp = p.immutable();
            List<BlockPos> plots = plots(bot, wp);
            if (plots.size() < 8) continue;
            if (rules) {
                PlantRules pr = PlantRules.get();
                boolean grows = crops.stream().anyMatch(c -> !pr.governs(c) || pr.judge(c, plots.get(0).above()).grows());
                if (!grows) continue;
            }
            double score = Math.min(plots.size(), PLOTS) - Math.sqrt(wp.distSqr(from)) / 6;
            if (score > bestScore) {
                bestScore = score;
                best = wp;
            }
        }
        return best;
    }
}
