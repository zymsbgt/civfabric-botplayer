package lol.zym.zymbot.inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Inventory helpers. Inventory indices are {@link Inventory} slots (0-8 hotbar, 9-35 main);
 * clicks go through whatever menu is open, translated with {@link #menuSlot}.
 */
public final class Inv {
    private Inv() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static LocalPlayer player() {
        return mc().player;
    }

    public static Inventory inv() {
        return player().getInventory();
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static int count(Predicate<ItemStack> p) {
        Inventory inv = inv();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && p.test(s)) n += s.getCount();
        }
        ItemStack off = inv.offhand.get(0);
        if (!off.isEmpty() && p.test(off)) n += off.getCount();
        return n;
    }

    public static int count(Item item) {
        return count(s -> s.is(item));
    }

    public static boolean has(Item item) {
        return count(item) > 0;
    }

    /** First inventory index holding a matching stack, hotbar first; -1 if none. */
    public static int find(Predicate<ItemStack> p) {
        Inventory inv = inv();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && p.test(s)) return i;
        }
        return -1;
    }

    /** First empty main-inventory slot, or -1. */
    public static int freeSlot() {
        for (int i = 0; i < 36; i++) if (inv().getItem(i).isEmpty()) return i;
        return -1;
    }

    public static int freeSlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv().getItem(i).isEmpty()) n++;
        return n;
    }

    /** Somewhere in the pack for {@code s}: an empty slot, or a stack of the same thing with room. */
    public static boolean roomFor(ItemStack s) {
        for (int i = 0; i < 36; i++) {
            ItemStack in = inv().getItem(i);
            if (in.isEmpty() || ItemStack.isSameItemSameComponents(in, s) && in.getCount() + s.getCount() <= in.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Menu slot index that shows the given player-inventory index, or -1. */
    public static int menuSlot(AbstractContainerMenu menu, int invIndex) {
        for (Slot s : menu.slots) {
            if (s.container == inv() && s.getContainerSlot() == invIndex) return s.index;
        }
        return -1;
    }

    public static void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
        mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player());
    }

    /**
     * Put the stack at {@code invIndex} in the hand. Hotbar items are selected directly; others
     * are swapped into the least valuable hotbar slot. Only works with no container open.
     */
    public static boolean select(int invIndex) {
        if (invIndex < 0) return false;
        Inventory inv = inv();
        if (invIndex < 9) {
            inv.selected = invIndex;
            return true;
        }
        if (player().containerMenu != player().inventoryMenu) return false;
        int hot = swapTarget();
        var menu = player().inventoryMenu;
        // three plain clicks (pick it up, swap with the hotbar slot, put that down where it was)
        // rather than a SWAP click: some servers throw a SWAP back, but never ordinary clicks
        int from = menuSlot(menu, invIndex), to = menuSlot(menu, hot);
        if (from < 0 || to < 0 || !menu.getCarried().isEmpty()) return false;
        click(menu, from, 0, ClickType.PICKUP);
        click(menu, to, 0, ClickType.PICKUP);
        if (!menu.getCarried().isEmpty()) click(menu, from, 0, ClickType.PICKUP);
        inv.selected = hot;
        return true;
    }

    public static boolean select(Predicate<ItemStack> p) {
        return select(find(p));
    }

    /**
     * The hotbar slot to bring an item into. Never slot 8: Baritone (allowInventory) keeps a
     * throwaway block there and swaps it straight back if anything else takes its place.
     */
    private static int swapTarget() {
        Inventory inv = inv();
        for (int i = 7; i >= 0; i--) if (inv.getItem(i).isEmpty()) return i;
        // keep tools/weapons/food in slots 0-4, rotate through 5-7
        int best = 7;
        int worst = Integer.MAX_VALUE;
        for (int i = 5; i <= 7; i++) {
            int v = value(inv.getItem(i));
            if (v < worst) {
                worst = v;
                best = i;
            }
        }
        return best;
    }

    private static int value(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.getItem() instanceof DiggerItem || s.getItem() instanceof SwordItem) return 100;
        if (s.has(DataComponents.FOOD)) return 50;
        return 10;
    }

    public static ItemStack held() {
        return player().getMainHandItem();
    }

    // ---- tools -------------------------------------------------------------------------

    /** Mining speed of a stack on a block, counting whether it can harvest it at all. */
    public static float digScore(ItemStack s, BlockState state) {
        float speed = s.isEmpty() ? 1f : s.getDestroySpeed(state);
        boolean ok = !state.requiresCorrectToolForDrops() || (!s.isEmpty() && s.isCorrectToolForDrops(state));
        if (!ok) speed *= 0.01f;
        // don't burn the last durability point
        if (!s.isEmpty() && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2) speed *= 0.001f;
        return speed;
    }

    /** Inventory index of the best tool for a block, or -1 for "bare hand is as good". */
    public static int bestTool(BlockState state) {
        Inventory inv = inv();
        int best = -1;
        float bestScore = digScore(ItemStack.EMPTY, state);
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            float sc = digScore(s, state);
            if (sc > bestScore + 0.01f) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    public static boolean canHarvest(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        return find(s -> s.isCorrectToolForDrops(state)) >= 0;
    }

    /** Attack damage an item adds (from its attribute modifiers); 1 for fists. */
    public static double attackDamage(ItemStack s) {
        if (s.isEmpty()) return 1;
        ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double[] dmg = {1};
        mods.forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(Attributes.ATTACK_DAMAGE)) dmg[0] += mod.amount();
        });
        return dmg[0];
    }

    public static int bestWeapon() {
        Inventory inv = inv();
        int best = -1;
        double bestDmg = 1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() instanceof BlockItem) continue;
            double d = attackDamage(s);
            // swords swing faster than axes; prefer them at equal-ish damage
            if (s.getItem() instanceof SwordItem) d += 1.5;
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        return best;
    }

    /** A sword or an axe: a pickaxe swings, but it's no way to take on something that hits back. */
    public static boolean armed() {
        return find(s -> s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem) >= 0;
    }

    /** Tool tier rank for comparing "do I already have something at least this good". */
    public static int tier(ItemStack s) {
        if (!(s.getItem() instanceof TieredItem t)) return -1;
        Tier tier = t.getTier();
        if (tier == Tiers.WOOD || tier == Tiers.GOLD) return 0;
        if (tier == Tiers.STONE) return 1;
        if (tier == Tiers.IRON) return 2;
        if (tier == Tiers.DIAMOND) return 3;
        if (tier == Tiers.NETHERITE) return 4;
        return Math.round(tier.getSpeed() / 2f) - 1; // modded tiers: guess from speed
    }

    public static int bestTier(Class<? extends Item> kind) {
        int best = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv().getItem(i);
            if (kind.isInstance(s.getItem())) best = Math.max(best, tier(s));
        }
        return best;
    }

    // ---- food --------------------------------------------------------------------------

    public static FoodProperties food(ItemStack s) {
        return s.get(DataComponents.FOOD);
    }

    public static boolean isFood(ItemStack s) {
        return !s.isEmpty() && s.has(DataComponents.FOOD);
    }

    /** Foods with only bad side effects (rotten flesh, spider eye, raw chicken, pufferfish...). */
    /** Food that gives health back (regeneration, instant health), or CivFabric's hearty soup. */
    public static boolean healingFood(ItemStack s) {
        FoodProperties f = food(s);
        if (f == null) return false;
        if (id(s.getItem()).equals("civfabric:hearty_soup")) return true;
        return f.effects().stream().anyMatch(e -> e.effect().getEffect().equals(net.minecraft.world.effect.MobEffects.REGENERATION)
                || e.effect().getEffect().equals(net.minecraft.world.effect.MobEffects.HEAL));
    }

    /** Something a hurt teammate can use on themselves: healing food (hearty soup too) or a healing/regeneration potion, splash or not. */
    public static boolean healingItem(ItemStack s) {
        if (healingFood(s)) return true;
        var pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        for (var e : pc.getAllEffects())
            if (e.getEffect().equals(net.minecraft.world.effect.MobEffects.HEAL) || e.getEffect().equals(net.minecraft.world.effect.MobEffects.REGENERATION)) return true;
        return false;
    }

    public static boolean riskyFood(ItemStack s) {
        FoodProperties f = food(s);
        if (f == null) return true;
        return !f.effects().isEmpty() && f.effects().stream().anyMatch(e -> !e.effect().getEffect().value().isBeneficial())
                || s.is(Items.POISONOUS_POTATO);
    }

    public static int totalNutrition() {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv().getItem(i);
            FoodProperties f = food(s);
            if (f != null && !riskyFood(s)) n += f.nutrition() * s.getCount();
        }
        return n;
    }
}
