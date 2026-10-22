package lol.zym.zymbot.inv;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.ClassPlan;
import lol.zym.zymbot.world.BlockRules;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.CropBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a full pack gives up, and in what order. Everything we hold is one of three things:
 * <ul>
 * <li>kept on us: worn and best gear, food (the 80 points of provisions), a Grower's seed, a bed,
 *     and working stock: some coal, torches, wood, a stack of scaffolding</li>
 * <li>stored: everything else, into a chest at home (finds, ores, odd drops, spare stock)</li>
 * <li>junk: dropped, never carried home. altoclef's garbage order: scaffold blocks past the stack
 *     we build with, then worse copies of a tool we have a better one of, then rotten food</li>
 * </ul>
 */
public final class Keep {
    public enum Fate { STORE, JUNK }

    /** {@code count} items from inventory slot {@code slot} that can leave us, and where they go. */
    public record Pile(int slot, Item item, int count, Fate fate) {}

    /** Working stock we hold on to; past this the rest is stored. */
    private static final Map<Item, Integer> STOCK = Map.of(Items.COAL, 8, Items.CHARCOAL, 8, Items.TORCH, 16,
            Items.STICK, 8, Items.CRAFTING_TABLE, 1, Items.FURNACE, 1, Items.CHEST, 1);
    /** Of all scaffold blocks together: Baritone bridges and pillars with them (Bot2 stranded with none). */
    private static final int SCAFFOLD = 64;
    private static final int LOGS = 16, PLANKS = 16, WOOL = 3, SAPLINGS = 8;

    private Keep() {}

    /**
     * Every pile that may leave the pack: junk first, then what's stored, smallest first (altoclef:
     * "discard the one with the smallest quantity"). Earlier slots keep their share before later ones.
     */
    public static List<Pile> surplus(Bot bot) {
        Map<String, Integer> kept = new HashMap<>();
        List<Pile> out = new ArrayList<>();
        Map<Class<?>, Integer> bestTool = bestTools();
        Map<Item, Integer> bestCopy = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.isEmpty() || i == Inv.inv().selected && s.isDamageableItem()) continue;
            Item it = s.getItem();
            if (BotConfig.get().keepItems.contains(Inv.id(it)) || s.is(ItemTags.BEDS)) continue;
            if (s.isDamageableItem()) {
                // tools: keep the best of each kind (tier, then wear); worse or worn copies are junk
                Class<?> kind = toolKind(it);
                if (kind != null) {
                    if (bestTool.get(kind) != i) out.add(new Pile(i, it, s.getCount(), Fate.JUNK));
                } else if (bestCopy.putIfAbsent(it, i) != null) {
                    out.add(new Pile(i, it, s.getCount(), Fate.STORE)); // a second fishing rod, shears, armour...
                }
                continue;
            }
            if (s.is(Items.ROTTEN_FLESH) || s.is(Items.POISONOUS_POTATO)) {
                out.add(new Pile(i, it, s.getCount(), Fate.JUNK));
                continue;
            }
            // food is the provisions goal; a Grower's seed is the farm
            if (Inv.isFood(s) && !s.is(Items.SPIDER_EYE)) continue;
            if (it instanceof BlockItem bi && bi.getBlock() instanceof CropBlock) {
                if (ClassPlan.isGrower(bot)) continue;
                out.add(new Pile(i, it, s.getCount(), Fate.STORE));
                continue;
            }
            String group;
            int allow;
            Fate past = Fate.STORE;
            if (it instanceof BlockItem bi && (BlockRules.scaffold(bi.getBlock()) || s.is(Items.GRAVEL))) {
                group = "scaffold";
                allow = SCAFFOLD;
                past = Fate.JUNK;
            } else if (s.is(ItemTags.LOGS)) {
                group = "logs";
                allow = LOGS;
            } else if (s.is(ItemTags.PLANKS)) {
                group = "planks";
                allow = PLANKS;
            } else if (s.is(ItemTags.SAPLINGS)) {
                // to replant the trees we cut (ReplantTask)
                group = "saplings";
                allow = SAPLINGS;
            } else if (s.is(ItemTags.WOOL)) {
                group = "wool";
                allow = WOOL;
            } else {
                group = Inv.id(it);
                allow = STOCK.getOrDefault(it, 0);
            }
            int have = kept.getOrDefault(group, 0);
            int keepHere = Math.max(0, Math.min(s.getCount(), allow - have));
            kept.put(group, have + keepHere);
            if (s.getCount() > keepHere) out.add(new Pile(i, it, s.getCount() - keepHere, past));
        }
        out.sort((a, b) -> a.fate() != b.fate() ? (a.fate() == Fate.JUNK ? -1 : 1) : Integer.compare(a.count(), b.count()));
        return out;
    }

    public static boolean any(Bot bot, Fate fate) {
        return surplus(bot).stream().anyMatch(p -> p.fate() == fate);
    }

    /** Pickaxe, axe, shovel, hoe, sword: one of each is all a bot needs. */
    private static Class<?> toolKind(Item it) {
        for (Class<?> c : List.of(PickaxeItem.class, AxeItem.class, ShovelItem.class, HoeItem.class, SwordItem.class)) {
            if (c.isInstance(it)) return c;
        }
        return null;
    }

    /** Slot of the best tool of each kind: highest tier, then least worn, then the held one. */
    private static Map<Class<?>, Integer> bestTools() {
        Map<Class<?>, Integer> best = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            Class<?> kind = s.isEmpty() ? null : toolKind(s.getItem());
            if (kind == null) continue;
            Integer b = best.get(kind);
            if (b == null || better(s, Inv.inv().getItem(b)) || !better(Inv.inv().getItem(b), s) && i == Inv.inv().selected) best.put(kind, i);
        }
        return best;
    }

    private static boolean better(ItemStack a, ItemStack b) {
        if (Inv.tier(a) != Inv.tier(b)) return Inv.tier(a) > Inv.tier(b);
        return a.getDamageValue() < b.getDamageValue();
    }
}
