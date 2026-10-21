package lol.zym.zymbot.craft;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Picks the cheapest way to get one unit of an item: gather it from a block, hunt it, craft it
 * or smelt it, recursing through ingredients. Costs are rough "effort" numbers; what matters is
 * the ordering. Anything CivFabric would refuse us costs infinity.
 */
public final class Planner {
    public static final double INF = 1e9;

    public enum Kind { HAVE, GATHER, HUNT, CRAFT, SMELT, FETCH, PICKUP }

    /** Effort a food point is worth: CivFabric charges crafts in hunger, and hunger is scarce. */
    public static final double HUNGER_COST = 6;
    /** Effort an XP point in a class we're not levelling is worth, once the drain is armed. */
    public static final double OFF_CLASS_COST = 3;

    public record Method(Kind kind, double cost, Item item, List<Block> blocks, List<EntityType<?>> mobs,
                         RecipeHolder<CraftingRecipe> craft, RecipeHolder<SmeltingRecipe> smelt,
                         net.minecraft.core.BlockPos at) {
        public Method(Kind kind, double cost, Item item, List<Block> blocks, List<EntityType<?>> mobs,
                      RecipeHolder<CraftingRecipe> craft, RecipeHolder<SmeltingRecipe> smelt) {
            this(kind, cost, item, blocks, mobs, craft, smelt, null);
        }

        @Override
        public String toString() {
            return kind + " " + Inv.id(item) + " (" + (int) cost + ")";
        }
    }

    private static final List<Item> TOOLS = List.of(
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.SHEARS);

    private final Bot bot;
    private final Map<Item, Double> memo = new HashMap<>();
    private final Set<Item> visiting = new HashSet<>();
    private Set<String> exclude = Set.of();

    public Planner(Bot bot) {
        this.bot = bot;
    }

    public double cost(Item item) {
        Method m = best(item, 0);
        return m == null ? INF : m.cost();
    }

    public Method best(Item item) {
        return best(item, 0);
    }

    /**
     * Best method for an item, skipping top-level methods keyed "KIND namespace:id" and never
     * routing through {@code ancestors} (items the calling tasks are already trying to make —
     * otherwise coal -> coal_block -> coal loops forever).
     */
    public Method best(Item item, Set<String> exclude, Set<Item> ancestors) {
        this.exclude = exclude;
        visiting.addAll(ancestors);
        visiting.remove(item);
        try {
            return best(item, 0);
        } finally {
            this.exclude = Set.of();
            visiting.clear();
        }
    }

    /**
     * The class rank that stands between us and an item we have no way to get, as "Apprentice
     * Blacksmith to craft stone_shovel" or "Journeyman Miner to mine iron_ore" (a step down the
     * chain). Null when there is a way, or when what's missing isn't a rank (no source known).
     */
    public String gate(Item item) {
        return gate(item, 0, new HashSet<>());
    }

    private String gate(Item item, int depth, Set<Item> seen) {
        if (depth > 3 || !seen.add(item) || Inv.has(item) || cost(item) < INF) return null;
        String itemId = Inv.id(item), name = itemId.replace("minecraft:", "");
        var civ = bot.civ();
        var recipes = Recipes.craftingFor(item);
        if (!recipes.isEmpty()) {
            String own = civ.craftNeeds(bot.memory(), itemId);
            if (own != null) return own + " to craft " + name;
            List<RecipeHolder<CraftingRecipe>> open = recipes.stream().filter(h -> civ.canCraft(bot.memory(), h.id().toString())).toList();
            if (open.isEmpty()) return civ.craftNeeds(bot.memory(), recipes.get(0).id().toString()) + " to craft " + name;
            for (RecipeHolder<CraftingRecipe> h : open)
                for (Recipes.Need n : Recipes.needs(h.value())) {
                    String why = gate(n.ingredient(), depth, seen);
                    if (why != null) return why;
                }
        }
        for (RecipeHolder<SmeltingRecipe> h : Recipes.smeltingFor(item)) {
            String why = gate(h.value().getIngredients().get(0), depth, seen);
            if (why != null) return why;
        }
        List<Block> blocks = Sources.blocksFor(item);
        if (!blocks.isEmpty() && blocks.stream().noneMatch(b -> civ.canMine(bot.memory(), BuiltInRegistries.BLOCK.getKey(b).toString()))) {
            String id = BuiltInRegistries.BLOCK.getKey(blocks.get(0)).toString();
            return civ.mineNeeds(bot.memory(), id) + " to mine " + id.replace("minecraft:", "");
        }
        return null;
    }

    /** An ingredient is only the blocker if none of its candidates can be had. */
    private String gate(Ingredient ing, int depth, Set<Item> seen) {
        List<Item> items = Arrays.stream(ing.getItems()).map(ItemStack::getItem).distinct().toList();
        if (items.stream().anyMatch(it -> Inv.has(it) || cost(it) < INF)) return null;
        for (Item it : items) {
            String why = gate(it, depth + 1, seen);
            if (why != null) return why;
        }
        return null;
    }

    /** Cheapest candidate for an ingredient/target, with its method. */
    public Method bestOf(List<Item> candidates) {
        Method best = null;
        for (Item c : candidates) {
            Method m = best(c, 0);
            if (m != null && (best == null || m.cost() < best.cost())) best = m;
        }
        return best;
    }

    private Method best(Item item, int depth) {
        if (depth > 6 || visiting.contains(item)) return null;
        visiting.add(item);
        try {
            List<Method> options = new ArrayList<>();
            String itemId = Inv.id(item);

            // already made and sitting somewhere: a known chest, or on the ground nearby
            var chest = lol.zym.zymbot.memory.ChestIndex.find(bot, item, 64);
            if (chest != null) {
                double d = Math.sqrt(chest.at.pos().distSqr(bot.player().blockPosition()));
                options.add(new Method(Kind.FETCH, 2 + d / 8, item, null, null, null, null, chest.at.pos()));
            }
            var lying = lol.zym.zymbot.brain.tasks.PickupTask.drops(bot, bot.player().blockPosition(), 16).stream()
                    .filter(e -> e.getItem().is(item)).min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
            if (lying != null) options.add(new Method(Kind.PICKUP, 1 + Math.sqrt(lying.distanceToSqr(bot.player())) / 8,
                    item, null, null, null, null, lying.blockPosition()));

            List<Block> blocks = new ArrayList<>();
            for (Block b : Sources.blocksFor(item)) {
                if (bot.civ().canMine(bot.memory(), BuiltInRegistries.BLOCK.getKey(b).toString())) blocks.add(b);
            }
            if (!blocks.isEmpty()) {
                double c = gatherBase(item, blocks) + toolCost(blocks, depth)
                        + offClass(lol.zym.zymbot.civ.ClassBook.get().award("breaking", BuiltInRegistries.BLOCK.getKey(blocks.get(0)).toString()));
                options.add(new Method(Kind.GATHER, c, item, blocks, null, null, null));
            }

            List<EntityType<?>> mobs = Sources.mobsFor(item);
            if (!mobs.isEmpty() || lol.zym.zymbot.inv.PokemonFood.anyDrops(item)) {
                // a hostile under pacifism turns its whole kin on us when killed: last resort
                boolean hostile = !mobs.isEmpty() && mobs.stream().allMatch(t -> t.getCategory() == net.minecraft.world.entity.MobCategory.MONSTER);
                var peace = bot.pacifism().state();
                boolean peaceful = bot.pacifism().protecting() || peace == lol.zym.zymbot.civ.Pacifism.State.UNKNOWN;
                // a Pokémon that drops it only counts as a source if one is about: species are
                // patchy, and exploring for a rare one (a stone axe off a Kleavor) is a long walk
                double c = mobs.isEmpty() ? (pokemonNearby(item) ? 40 : INF) : hostile ? (peaceful ? 400 : 90) : 30;
                if (c < INF) options.add(new Method(Kind.HUNT, c, item, null, mobs, null, null));
            }

            for (RecipeHolder<CraftingRecipe> h : Recipes.craftingFor(item)) {
                if (!bot.civ().canCraft(bot.memory(), h.id().toString()) || !bot.civ().canCraft(bot.memory(), itemId)) continue;
                int out = Math.max(1, Recipes.result(h).getCount());
                // on a tie, vanilla recipes (oak log to planks) over modded ones (Vinery's apple wood)
                double c = h.id().getNamespace().equals("minecraft") ? 2 : 2.5;
                for (Recipes.Need n : Recipes.needs(h.value())) {
                    double ic = ingredientCost(n.ingredient(), depth);
                    if (ic >= INF) {
                        c = INF;
                        break;
                    }
                    c += ic * n.perCraft() / out;
                }
                if (c < INF && !Recipes.fits2x2(h.value()) && !Inv.has(Items.CRAFTING_TABLE)) c += 4;
                if (c < INF) {
                    c += hunger(item) * HUNGER_COST / out;
                    c += offClass(lol.zym.zymbot.civ.ClassBook.get().award("crafting", itemId));
                }
                if (c < INF) options.add(new Method(Kind.CRAFT, c, item, null, null, h, null));
            }

            for (RecipeHolder<SmeltingRecipe> h : Recipes.smeltingFor(item)) {
                Ingredient in = h.value().getIngredients().get(0);
                double ic = ingredientCost(in, depth);
                if (ic >= INF) continue;
                double c = ic + 6 + (Inv.has(Items.FURNACE) ? 0 : 6)
                        + offClass(lol.zym.zymbot.civ.ClassBook.get().award("smelting", itemId));
                options.add(new Method(Kind.SMELT, c, item, null, null, null, h));
            }
            if (depth == 0 && !exclude.isEmpty()) options.removeIf(o -> exclude.contains(o.kind() + " " + itemId));
            return options.stream().min(Comparator.comparingDouble(Method::cost)).orElse(null);
        } finally {
            visiting.remove(item);
        }
    }

    private double ingredientCost(Ingredient ing, int depth) {
        double best = INF;
        for (ItemStack s : ing.getItems()) {
            Item it = s.getItem();
            if (visiting.contains(it)) continue; // would be a cycle
            if (Inv.has(it)) return 0.5; // already holding some; good enough for planning
            Double cached = memo.get(it);
            double c;
            if (cached != null) c = cached;
            else {
                Method m = best(it, depth + 1);
                c = m == null ? INF : m.cost();
                memo.put(it, c);
            }
            best = Math.min(best, c);
        }
        return best;
    }

    /** Food points one craft of this costs us right now (CivFabric crafting hunger). */
    public int hunger(Item item) {
        if (!bot.civ().present()) return 0;
        return lol.zym.zymbot.civ.ClassBook.get().craftHunger(item, c -> bot.civ().rank(bot.memory(), c),
                lol.zym.zymbot.civ.ClassSheet.classless(bot));
    }

    /**
     * What an action's class XP costs us. XP in a class we're levelling is free. XP elsewhere is
     * harmless until the drain arms, but it can still push the wrong class into our top N, so it
     * costs a little before and a lot after (each point then takes 0.525 from every specialisation).
     */
    private double offClass(lol.zym.zymbot.civ.ClassBook.Award a) {
        if (a == null) return 0;
        List<String> plan = lol.zym.zymbot.brain.ClassPlan.classes(bot);
        if (plan.isEmpty() || plan.contains(a.clazz())) return 0;
        double held = bot.memory().classXp.getOrDefault(a.clazz(), 0.0);
        if (bot.memory().drainArmed) return a.xp() * lol.zym.zymbot.civ.ClassBook.DRAIN_PER_SPEC * plan.size() * OFF_CLASS_COST;
        return a.xp() * (held > lol.zym.zymbot.civ.ClassBook.DRAIN_ARMS_AT * 0.7 ? OFF_CLASS_COST : 0.3);
    }

    /** Cheap if the block is in the latest survey of the surroundings; otherwise we'd have to go looking. */
    private double gatherBase(Item item, List<Block> blocks) {
        boolean seen = blocks.stream().anyMatch(bot::seenNearby);
        BlockState s = blocks.get(0).defaultBlockState();
        double base;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND)) base = 3;
        else if (Sources.isOre(s)) base = 12;
        else base = 10;
        if (!BuiltInRegistries.BLOCK.getKey(blocks.get(0)).getNamespace().equals("minecraft")) base += 0.5;
        return seen ? base : base + 25;
    }

    /** Extra cost if none of our tools can harvest any of these blocks. */
    private double toolCost(List<Block> blocks, int depth) {
        double best = INF;
        for (Block b : blocks) {
            BlockState s = b.defaultBlockState();
            if (!s.requiresCorrectToolForDrops() || Inv.canHarvest(s)) return 0;
            for (Item tool : TOOLS) {
                if (!new ItemStack(tool).isCorrectToolForDrops(s)) continue;
                Method m = best(tool, depth + 1);
                if (m != null) best = Math.min(best, m.cost());
            }
        }
        return best;
    }

    /** Above this total effort, an ingredient is worth handing to a teammate when one is around. */
    private static final double EXPENSIVE = 100;

    /** Making {@code crafts} crafts of a recipe: ingredients we get ourselves, and ones teammates should bring. */
    public record Split(int crafts, List<Recipes.Need> mine, List<Recipes.Need> gaps) {}

    /**
     * How to make {@code count} of an item when teammates bring some ingredients: the ones we
     * can't get at all (a class gate, say), plus — if {@code helpers} — ones that would take us
     * far longer than the rest. Null when no recipe needs help, or none would work even with it.
     * Never asks for anything in {@code ancestors} (the request chain above us).
     */
    public Split split(Item item, int count, Set<Item> ancestors, boolean helpers) {
        visiting.addAll(ancestors);
        visiting.add(item);
        try {
            Split best = null;
            double bestScore = INF;
            String itemId = Inv.id(item);
            List<Map.Entry<List<Recipes.Need>, Integer>> recipes = new ArrayList<>();
            if (bot.civ().canCraft(bot.memory(), itemId)) {
                for (RecipeHolder<CraftingRecipe> h : Recipes.craftingFor(item)) {
                    if (!bot.civ().canCraft(bot.memory(), h.id().toString())) continue;
                    int out = Math.max(1, Recipes.result(h).getCount());
                    recipes.add(Map.entry(Recipes.needs(h.value()), (count + out - 1) / out));
                }
            }
            for (RecipeHolder<SmeltingRecipe> h : Recipes.smeltingFor(item)) {
                recipes.add(Map.entry(Recipes.needs(h.value()), count));
            }
            recipe:
            for (var r : recipes) {
                int crafts = r.getValue();
                List<Recipes.Need> mine = new ArrayList<>(), gaps = new ArrayList<>();
                double score = 0;
                for (Recipes.Need n : r.getKey()) {
                    int short_ = n.perCraft() * crafts - n.have();
                    if (short_ <= 0) {
                        mine.add(n);
                        continue;
                    }
                    if (Recipes.candidates(n.ingredient()).stream().allMatch(visiting::contains)) continue recipe; // it's what we're making
                    double c = ingredientCost(n.ingredient(), 1);
                    if (c >= INF || helpers && c * short_ >= EXPENSIVE) {
                        gaps.add(n);
                        score += 40; // waiting for someone
                    } else {
                        mine.add(n);
                        score += c * short_;
                    }
                }
                if (!gaps.isEmpty() && score < bestScore) {
                    bestScore = score;
                    best = new Split(crafts, mine, gaps);
                }
            }
            return best;
        } finally {
            visiting.clear();
        }
    }

    /** Cheapest tool we could make that harvests this block, or null. */
    public Item toolFor(BlockState s) {
        Item best = null;
        double bc = INF;
        for (Item tool : TOOLS) {
            if (!new ItemStack(tool).isCorrectToolForDrops(s)) continue;
            double c = cost(tool);
            if (c < bc) {
                bc = c;
                best = tool;
            }
        }
        return best;
    }

    /** A wild Pokémon in sight (64 blocks) that would drop this. */
    private boolean pokemonNearby(Item item) {
        var p = bot.player();
        return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(64),
                e -> e.isAlive() && lol.zym.zymbot.inv.PokemonFood.drops(e, st -> st.is(item))).isEmpty();
    }
}
