package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.craft.Planner;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.world.BlockRules;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.Predicate;

/**
 * What we'd pick up if we walked past it. A standing stock (logs, cobblestone, coal: wanted by
 * nearly everything), seeds of crops we don't have yet while the farm still wants them, and the raw
 * materials the next few milestones will need, traced through their recipes by the planner (a
 * furnace is cobblestone, a stone pickaxe is cobblestone and logs). {@link #find} looks a few steps
 * around us for a source of any of it, so a bot out for seeds also brings back the coal seam and
 * the wild cabbage it passed.
 */
public final class ShoppingList {
    /** Something to pick up: what counts, the blocks it comes out of, and how many more we want. */
    public record Want(String what, Predicate<ItemStack> match, Set<Block> sources, int count) {}

    /** A source in reach. */
    public record Find(Want want, Block block, BlockPos at) {}

    private static final int REACH = 12, UP_DOWN = 6, MILESTONES = 4;

    private List<Want> cached = List.of();
    private long computedAt = -100_000;
    /** Spots we already went for (or gave up on): not again for a while. */
    private final Map<BlockPos, Long> tried = new HashMap<>();

    public List<Want> wants(Bot bot) {
        if (bot.ticks() - computedAt >= 600) {
            computedAt = bot.ticks();
            cached = compute(bot);
        }
        return cached;
    }

    /** Recompute on the next look (we just picked something up, or a milestone changed). */
    public void stale() {
        computedAt = -100_000;
    }

    public void tried(BlockPos pos) {
        tried.put(pos, System.currentTimeMillis() + 5 * 60_000L);
    }

    private List<Want> compute(Bot bot) {
        Map<String, Want> out = new LinkedHashMap<>();
        // the standing stock
        int logs = 16 - Inv.count(s -> s.is(ItemTags.LOGS));
        if (logs > 0) {
            Set<Block> trees = new HashSet<>();
            for (Block b : BuiltInRegistries.BLOCK)
                if (b.defaultBlockState().is(BlockTags.LOGS) && !BuiltInRegistries.BLOCK.getKey(b).getPath().startsWith("stripped_")) trees.add(b);
            out.put("logs", new Want("logs", s -> s.is(ItemTags.LOGS), trees, logs));
        }
        Planner planner = bot.planner();
        raw(planner, Items.COBBLESTONE, 16 - Inv.count(Items.COBBLESTONE), out);
        raw(planner, Items.COAL, 8 - Inv.count(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)), out);
        // seeds of a crop we don't have yet, while the farm still wants seeds
        if (Progression.wantSeeds(bot)) {
            List<Block> wild = Progression.newSeedSources(bot);
            if (!wild.isEmpty()) out.put("new seeds", new Want("new seeds", Progression::plantable, new HashSet<>(wild), 4));
        }
        // what the next few milestones will be made of
        for (Progression.Milestone m : bot.brain().progression().upcoming(MILESTONES))
            for (var e : m.wants().entrySet()) raw(planner, e.getKey(), e.getValue() - Inv.count(e.getKey()), out);
        return new ArrayList<>(out.values());
    }

    /** Trace {@code n} of {@code item} back through the planner's chosen recipes to what's gathered. */
    private static void raw(Planner planner, Item item, int n, Map<String, Want> out) {
        raw(planner, item, n, out, new HashSet<>(), 0);
    }

    private static void raw(Planner planner, Item item, int n, Map<String, Want> out, Set<Item> seen, int depth) {
        if (n <= 0 || depth > 5 || !seen.add(item)) return;
        Planner.Method m = planner.best(item);
        if (m == null) return;
        switch (m.kind()) {
            case GATHER -> {
                if (m.blocks() == null || m.blocks().isEmpty()) return;
                String key = Inv.id(item);
                Want had = out.get(key);
                Set<Block> blocks = new HashSet<>(m.blocks());
                if (had != null) blocks.addAll(had.sources());
                out.put(key, new Want(key.replace("minecraft:", ""), s -> s.is(item), blocks, n + (had == null ? 0 : had.count())));
            }
            case CRAFT -> {
                int yield = Math.max(1, Recipes.result(m.craft()).getCount());
                int crafts = (n + yield - 1) / yield;
                for (Recipes.Need need : Recipes.needs(m.craft().value())) {
                    Planner.Method in = planner.bestOf(Arrays.stream(need.ingredient().getItems()).map(ItemStack::getItem).distinct().toList());
                    if (in == null) continue;
                    raw(planner, in.item(), need.perCraft() * crafts - Inv.count(in.item()), out, seen, depth + 1);
                }
            }
            case SMELT -> {
                Planner.Method in = planner.bestOf(Arrays.stream(m.smelt().value().getIngredients().get(0).getItems())
                        .map(ItemStack::getItem).distinct().toList());
                if (in != null) raw(planner, in.item(), n - Inv.count(in.item()), out, seen, depth + 1);
            }
            default -> {
            }
        }
    }

    /**
     * The nearest source of something we want within a few steps: natural, in the open, breakable
     * with what we carry and allowed to us, not on someone else's build, and not one of {@code
     * skip} (what the current errand is already gathering).
     */
    public Find find(Bot bot, Predicate<Block> skip) {
        List<Want> wants = wants(bot);
        if (wants.isEmpty()) return null;
        Map<Block, Want> byBlock = new HashMap<>();
        for (Want w : wants) for (Block b : w.sources()) byBlock.putIfAbsent(b, w);
        long now = System.currentTimeMillis();
        tried.values().removeIf(t -> t < now);

        BlockPos me = bot.player().blockPosition();
        List<BlockPos> hits = new ArrayList<>();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -REACH; dx <= REACH; dx++)
            for (int dz = -REACH; dz <= REACH; dz++)
                for (int dy = -UP_DOWN; dy <= UP_DOWN; dy++) {
                    c.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (!WorldView.loaded(c)) continue;
                    Block b = WorldView.state(c).getBlock();
                    if (byBlock.containsKey(b) && !skip.test(b)) hits.add(c.immutable());
                }
        hits.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
        int checked = 0;
        for (BlockPos p : hits) {
            if (tried.containsKey(p) || bot.memory().blacklisted(p)) continue;
            BlockState st = WorldView.state(p);
            // wild crops aren't "terrain", but they're nobody's
            if (!BlockRules.natural(st) && !byBlock.get(st.getBlock()).what().equals("new seeds")) continue;
            if (!WorldView.exposed(p) || !Inv.canHarvest(st)) continue;
            if (!bot.civ().canMine(bot.memory(), BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString())) continue;
            // the costly checks last, and only for the nearest few
            if (++checked > 6) break;
            if (bot.nearSomeoneElsesBuild(p)) continue;
            return new Find(byBlock.get(st.getBlock()), st.getBlock(), p);
        }
        return null;
    }
}
