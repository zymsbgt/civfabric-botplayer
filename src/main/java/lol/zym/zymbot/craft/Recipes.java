package lol.zym.zymbot.craft;

import lol.zym.zymbot.inv.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.*;

/**
 * Index of the recipes the server synced to us (in 1.21.1 the client gets every recipe,
 * modded ones included), keyed by output item.
 */
public final class Recipes {
    private Recipes() {}

    private static Map<Item, List<RecipeHolder<CraftingRecipe>>> crafting;
    private static Map<Item, List<RecipeHolder<SmeltingRecipe>>> smelting;
    private static int seenSize = -1;

    /** One distinct ingredient of a recipe and how many of it one craft uses. */
    public record Need(Ingredient ingredient, String key, int perCraft) {
        public int have() {
            return Inv.count(ingredient::test);
        }
    }

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static void ensure() {
        RecipeManager rm = level().getRecipeManager();
        int size = rm.getRecipes().size();
        if (crafting != null && size == seenSize) return;
        seenSize = size;
        crafting = new HashMap<>();
        smelting = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> h : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (h.value().isSpecial()) continue;
            ItemStack out = result(h);
            if (!out.isEmpty()) crafting.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(h);
        }
        for (RecipeHolder<SmeltingRecipe> h : rm.getAllRecipesFor(RecipeType.SMELTING)) {
            ItemStack out = result(h);
            if (!out.isEmpty()) smelting.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(h);
        }
    }

    public static void invalidate() {
        crafting = null;
    }

    public static ItemStack result(RecipeHolder<?> h) {
        return h.value().getResultItem(level().registryAccess());
    }

    public static List<RecipeHolder<CraftingRecipe>> craftingFor(Item item) {
        ensure();
        return crafting.getOrDefault(item, List.of());
    }

    public static List<RecipeHolder<SmeltingRecipe>> smeltingFor(Item item) {
        ensure();
        return smelting.getOrDefault(item, List.of());
    }

    public static boolean fits2x2(CraftingRecipe r) {
        if (r instanceof ShapedRecipe s) return s.getWidth() <= 2 && s.getHeight() <= 2;
        return nonEmpty(r).size() <= 4;
    }

    public static List<Ingredient> nonEmpty(Recipe<?> r) {
        List<Ingredient> out = new ArrayList<>();
        for (Ingredient i : r.getIngredients()) if (!i.isEmpty()) out.add(i);
        return out;
    }

    /** Stable identity for an ingredient: its sorted item ids. */
    public static String key(Ingredient i) {
        return Arrays.stream(i.getItems()).map(s -> Inv.id(s.getItem())).distinct().sorted()
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    /** Distinct ingredients with per-craft amounts. */
    public static List<Need> needs(Recipe<?> r) {
        Map<String, Need> m = new LinkedHashMap<>();
        for (Ingredient i : nonEmpty(r)) {
            String k = key(i);
            Need prev = m.get(k);
            m.put(k, new Need(i, k, prev == null ? 1 : prev.perCraft() + 1));
        }
        return new ArrayList<>(m.values());
    }

    /** Candidate items for an ingredient, ones we already hold first. */
    public static List<Item> candidates(Ingredient i) {
        List<Item> items = new ArrayList<>();
        for (ItemStack s : i.getItems()) if (!items.contains(s.getItem())) items.add(s.getItem());
        items.sort(Comparator.comparingInt((Item it) -> -Inv.count(it)));
        return items;
    }
}
