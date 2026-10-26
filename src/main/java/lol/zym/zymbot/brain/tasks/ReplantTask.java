package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.farm.PlantRules;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * Put a sapling where a tree we cut down stood: the same kind as the log if we have one, else any.
 * Wood is fuel (charcoal) and planks for everyone, and a forest that's only ever cut runs out.
 *
 * <p>Realistic Plant Growth governs saplings as it does crops ({@link PlantRules}): each kind grows
 * only in its biomes (oak and birch in temperate ones, spruce in cold ones...) and only under open
 * sky, and one planted anywhere else dies. Natural trees stand where their saplings can't grow, so a
 * stump is no promise: only a kind that grows on the spot goes in.
 */
public class ReplantTask extends Task {
    private final BlockPos stump;
    private final String wood; // "oak" for oak_log: which sapling fits best

    public ReplantTask(BlockPos stump, String wood) {
        this.stump = stump;
        this.wood = wood;
    }

    @Override
    public String name() {
        return "Replant at " + stump.toShortString();
    }

    /** Somewhere a sapling can go: air or short grass (it's replaced), on dirt or grass. */
    public static boolean plantable(BlockPos at) {
        if (!WorldView.loaded(at)) return false;
        var s = WorldView.state(at);
        return (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) && WorldView.state(at.below()).is(BlockTags.DIRT);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (!plantable(stump)) return Status.SUCCESS; // something grew or was put there already
        Item sapling = pick(stump, wood);
        if (sapling == null) return fail(Inv.count(s -> s.is(ItemTags.SAPLINGS)) == 0 ? "no sapling"
                : "none of my saplings grows at " + stump.toShortString() + " (" + PlantRules.biomeAt(stump).getPath() + ")");
        if (!Placer.placeable(stump)) {
            Nav.goNear(stump, 2);
            return ticks > 20 * 30 ? fail("can't get to the stump") : Status.RUNNING;
        }
        Nav.stop();
        if (Placer.place(sapling, stump)) {
            bot.event("replanted " + Inv.id(sapling).replace("minecraft:", "") + " at " + stump.toShortString());
            return Status.SUCCESS;
        }
        return ticks > 20 * 40 ? fail("the sapling wouldn't go in") : Status.RUNNING;
    }

    /** A sapling on us that grows at {@code at}: {@code wood}'s own kind if it does, else the one that grows best. */
    public static Item pick(BlockPos at, String wood) {
        Item best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!s.is(ItemTags.SAPLINGS)) continue;
            var j = judge(s.getItem(), at);
            if (j != null && !j.grows()) continue;
            if (!wood.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith(wood + "_")) return s.getItem();
            double score = j == null ? 100 : j.score();
            if (score > bestScore) {
                best = s.getItem();
                bestScore = score;
            }
        }
        return best;
    }

    /** Whether any of {@code kinds} would grow at {@code at}, sky and biome both (always, with the rules off). */
    public static boolean anyGrows(BlockPos at, Collection<Item> kinds) {
        for (Item k : kinds) {
            var j = judge(k, at);
            if (j == null || j.grows()) return true;
        }
        return false;
    }

    /**
     * Whether some sapling kind suits {@code at}'s biome: else a stump there is not worth remembering.
     * The biome only: leaves still coming down over a fresh stump shade it for a while.
     */
    public static boolean anyKindGrows(BlockPos at) {
        if (!PlantRules.active()) return true;
        for (Item k : BuiltInRegistries.ITEM) {
            if (!k.builtInRegistryHolder().is(ItemTags.SAPLINGS) || !(k instanceof BlockItem bi)) continue;
            if (PlantRules.get().judgeClimate(bi.getBlock(), at).grows()) return true;
        }
        return false;
    }

    /** Realistic Plant Growth's verdict on a sapling at a spot; null with the rules off. */
    private static PlantRules.Judgement judge(Item sapling, BlockPos at) {
        if (!PlantRules.active() || !(sapling instanceof BlockItem bi)) return null;
        return PlantRules.get().judge(bi.getBlock(), at);
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
