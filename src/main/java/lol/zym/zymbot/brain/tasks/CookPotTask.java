package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cook {@code count} of a meal in a Farmer's Delight cooking pot near us (ZymSB placed one by the
 * base and asked for apple cider). The pot has to be on heat (a stove, a lit campfire, fire, magma).
 * Ingredients and the meal's container (a bottle, a bowl) are got first, the usual way; then into
 * the pot: slots 0-5 take the ingredients, 6 shows the meal, 7 takes containers, 8 holds what's served.
 */
public class CookPotTask extends Task {
    private enum Phase { GATHER, FIND, OPEN, LOAD, COOK, DONE }

    private static final int MEAL = 6, CONTAINER = 7, OUTPUT = 8, INGREDIENT_SLOTS = 6;

    private final RecipeHolder<?> recipe;
    private final Item output;
    private final Item container;
    private final int count;
    private final int startCount;
    private final List<Ingredient> ingredients;
    private Phase phase = Phase.GATHER;
    private int gathered;
    private BlockPos pot;

    public CookPotTask(RecipeHolder<?> recipe, int count) {
        this.recipe = recipe;
        this.output = Recipes.result(recipe).getItem();
        this.count = count;
        this.startCount = Inv.count(output);
        this.ingredients = new ArrayList<>(recipe.value().getIngredients());
        this.ingredients.removeIf(Ingredient::isEmpty);
        this.container = containerOf(recipe, output);
    }

    /** The pot recipe making {@code item}, or null. */
    public static RecipeHolder<?> recipeFor(Bot bot, Item item) {
        for (RecipeHolder<?> h : bot.level().getRecipeManager().getRecipes()) {
            var type = BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType());
            if (type != null && type.toString().equals("farmersdelight:cooking") && Recipes.result(h).is(item)) return h;
        }
        return null;
    }

    /** What the meal is served in: the recipe's own container, else the meal's crafting remainder (a bottle for cider). */
    private static Item container(Object recipe) {
        try {
            Object c = recipe.getClass().getMethod("getOutputContainer").invoke(recipe);
            if (c instanceof ItemStack s && !s.isEmpty()) return s.getItem();
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Item containerOf(RecipeHolder<?> recipe, Item output) {
        Item c = container(recipe.value());
        if (c != null) return c;
        Item rest = output.getCraftingRemainingItem();
        return rest;
    }

    private int made() {
        return Inv.count(output) - startCount;
    }

    @Override
    public String name() {
        return "Cook " + Inv.id(output).replace("minecraft:", "") + " " + made() + "/" + count + " (" + phase.name().toLowerCase() + ")";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        var player = bot.player();
        switch (phase) {
            case GATHER -> {
                // each ingredient (and the container) in hand before the walk to the pot
                int left = count - made();
                Map<String, ItemTarget> needs = new LinkedHashMap<>();
                for (Ingredient ing : ingredients) {
                    String key = ingredientKey(ing);
                    ItemTarget t = needs.get(key);
                    needs.put(key, t == null ? ItemTarget.of(ing, left) : t.withCount(t.count() + left));
                }
                if (container != null) needs.merge("container", ItemTarget.of(container, left), (x, y) -> x.withCount(x.count() + y.count()));
                List<ItemTarget> list = new ArrayList<>(needs.values());
                while (gathered < list.size()) {
                    ItemTarget t = list.get(gathered++);
                    if (t.have() < t.count()) {
                        setChild(bot, new ObtainTask(t));
                        return Status.RUNNING;
                    }
                }
                phase = Phase.FIND;
            }
            case FIND -> {
                pot = nearestHeatedPot(bot);
                if (pot == null) return fail(potMissing ? "no cooking pot within 24 blocks" : "the cooking pot isn't on heat (a stove or a lit campfire under it)");
                phase = Phase.OPEN;
                ticks = 0;
            }
            case OPEN -> {
                if (isPotMenu(player.containerMenu)) {
                    phase = Phase.LOAD;
                    return Status.RUNNING;
                }
                if (!Breaker.inReach(pot)) {
                    Nav.goToBlock(pot);
                    if (ticks > 20 * 60) return fail("can't reach the cooking pot");
                    return Status.RUNNING;
                }
                Nav.stop();
                if (ticks % 20 == 1) Placer.use(pot);
                if (ticks > 20 * 30) return fail("the cooking pot won't open");
            }
            case LOAD -> {
                AbstractContainerMenu m = player.containerMenu;
                if (!isPotMenu(m)) {
                    phase = Phase.OPEN;
                    return Status.RUNNING;
                }
                // whatever's in the slots already is someone else's cooking: leave it and say so
                for (int s = 0; s < INGREDIENT_SLOTS; s++) {
                    ItemStack in = m.getSlot(s).getItem();
                    if (!in.isEmpty() && ingredients.stream().noneMatch(i -> i.test(in)))
                        return fail("the pot has someone else's " + Inv.id(in.getItem()).replace("minecraft:", "") + " in it");
                }
                int left = count - made();
                // one slot per ingredient line of the recipe (two apples: two slots), each holding a meal's worth per meal
                for (int s = 0; s < ingredients.size() && s < INGREDIENT_SLOTS; s++) {
                    Ingredient ing = ingredients.get(s);
                    int want = left - m.getSlot(s).getItem().getCount();
                    if (want <= 0) continue;
                    int src = Inv.find(ing::test);
                    if (src < 0) {
                        phase = Phase.GATHER;
                        gathered = 0;
                        player.closeContainer();
                        return Status.RUNNING;
                    }
                    move(m, src, s, want);
                }
                if (container != null) {
                    int want = left - m.getSlot(CONTAINER).getItem().getCount();
                    int src = Inv.find(st -> st.is(container));
                    if (want > 0 && src >= 0) move(m, src, CONTAINER, want);
                }
                phase = Phase.COOK;
                ticks = 0;
            }
            case COOK -> {
                AbstractContainerMenu m = player.containerMenu;
                if (!isPotMenu(m)) {
                    phase = Phase.OPEN;
                    ticks = 0;
                    return Status.RUNNING;
                }
                if (!m.getSlot(OUTPUT).getItem().isEmpty()) Inv.click(m, OUTPUT, 0, ClickType.QUICK_MOVE);
                if (made() >= count) {
                    player.closeContainer();
                    phase = Phase.DONE;
                } else if (!m.getSlot(MEAL).getItem().isEmpty() && m.getSlot(CONTAINER).getItem().isEmpty() && container != null) {
                    // served but nothing to serve it in: the bottle ran out
                    int src = Inv.find(st -> st.is(container));
                    if (src < 0) return fail("no " + Inv.id(container).replace("minecraft:", "") + " to serve it in");
                    move(m, src, CONTAINER, count - made());
                } else if (ticks > 20 * (30 * count + 30)) {
                    return fail("cooking is taking far too long (is the pot still on heat?)");
                }
            }
            case DONE -> {
                return made() >= count ? Status.SUCCESS : fail("only made " + made() + "/" + count);
            }
        }
        return Status.RUNNING;
    }

    private boolean potMissing;

    private BlockPos nearestHeatedPot(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        BlockPos best = null;
        potMissing = true;
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-24, -8, -24), me.offset(24, 8, 24))) {
            var id = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(q).getBlock());
            if (!id.toString().equals("farmersdelight:cooking_pot")) continue;
            potMissing = false;
            if (!heated(bot, q)) continue;
            if (best == null || q.distSqr(me) < best.distSqr(me)) best = q.immutable();
        }
        return best;
    }

    /** The pot's own word on whether it's on heat. */
    private static boolean heated(Bot bot, BlockPos pos) {
        var be = bot.level().getBlockEntity(pos);
        if (be == null) return false;
        try {
            return (Boolean) be.getClass().getMethod("isHeated").invoke(be);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    private static boolean isPotMenu(AbstractContainerMenu m) {
        return m != null && m.getClass().getName().endsWith("CookingPotMenu");
    }

    private static String ingredientKey(Ingredient ing) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : ing.getItems()) sb.append(Inv.id(s.getItem())).append(',');
        return sb.toString();
    }

    /** Move up to {@code n} items from an inventory index into a pot slot. */
    private static void move(AbstractContainerMenu m, int invIndex, int slot, int n) {
        int ms = Inv.menuSlot(m, invIndex);
        if (ms < 0 || n <= 0) return;
        int have = Inv.inv().getItem(invIndex).getCount();
        Inv.click(m, ms, 0, ClickType.PICKUP);
        if (n >= have) {
            Inv.click(m, slot, 0, ClickType.PICKUP);
        } else {
            for (int i = 0; i < n; i++) Inv.click(m, slot, 1, ClickType.PICKUP);
        }
        if (!m.getCarried().isEmpty()) Inv.click(m, ms, 0, ClickType.PICKUP);
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (isPotMenu(bot.player().containerMenu)) bot.player().closeContainer();
        Nav.stop();
    }
}
