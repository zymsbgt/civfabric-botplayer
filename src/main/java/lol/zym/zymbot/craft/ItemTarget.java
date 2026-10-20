package lol.zym.zymbot.craft;

import lol.zym.zymbot.inv.Inv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.function.Predicate;

/** "N of anything matching X", with the concrete items we might make to satisfy it. */
public record ItemTarget(String name, Predicate<ItemStack> match, List<Item> candidates, int count) {
    public static ItemTarget of(Item item, int count) {
        return new ItemTarget(Inv.id(item).replace("minecraft:", ""), s -> s.is(item), List.of(item), count);
    }

    public static ItemTarget of(Ingredient ing, int count) {
        List<Item> c = Recipes.candidates(ing);
        String n = c.isEmpty() ? "?" : Inv.id(c.get(0)).replace("minecraft:", "") + (c.size() > 1 ? "*" : "");
        return new ItemTarget(n, ing::test, c, count);
    }

    /** Parse "oak_log", "minecraft:oak_log" or "cobblemon:poke_ball". Null if unknown. */
    public static ItemTarget parse(String id, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return null;
        Item it = BuiltInRegistries.ITEM.get(rl);
        if (it == Items.AIR) return null;
        return of(it, count);
    }

    public int have() {
        return Inv.count(match);
    }

    public boolean satisfied() {
        return have() >= count;
    }

    public ItemTarget withCount(int n) {
        return new ItemTarget(name, match, candidates, n);
    }

    @Override
    public String toString() {
        return count + "x " + name;
    }
}
