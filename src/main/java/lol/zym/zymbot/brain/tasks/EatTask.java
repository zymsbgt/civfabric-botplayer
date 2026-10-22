package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Diet;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Eat something. Spice of Fabric shrinks the value of foods eaten recently and makes them slower
 * to eat, so the choice follows {@link lol.zym.zymbot.inv.MealPlan}: the meal that leaves the pack
 * worth the most, which goes round the foods we carry. We keep chewing for as long as the food
 * really takes. Foods with bad side effects only when starving.
 */
public class EatTask extends Task {
    private int slot = -1;
    private String foodId;
    private int startFood;
    private int startCount;
    private int limit;
    /** Foods that wouldn't go down lately (food id -> millis until we try again), so one doesn't loop. */
    private static final java.util.Map<String, Long> WONT_EAT = new java.util.HashMap<>();

    @Override
    public String name() {
        return "Eat " + (foodId == null ? "" : foodId.replace("minecraft:", ""));
    }

    /** A healing food we can eat now, for when health won't come back by itself. */
    public static int chooseHealing(Bot bot) {
        var p = bot.player();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            FoodProperties f = Inv.food(s);
            if (f != null && Inv.healingFood(s) && (f.canAlwaysEat() || p.canEat(false))) return i;
        }
        return -1;
    }

    /** Food level we eat at regardless, wasteful or not: still above sprinting's 6. */
    public static final int MUST_EAT = 8;

    /**
     * Something we carry fits the gap in the food bar now, with at most a point spilling over.
     * Above {@link #MUST_EAT} we wait for one: health doesn't come back from a full bar here.
     */
    public static boolean fitsNow(Bot bot) {
        int gap = 20 - bot.player().getFoodData().getFoodLevel();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (Inv.food(s) == null || Inv.riskyFood(s)) continue;
            double v = Diet.nutrition(bot, s);
            if (v >= 0.5 && v <= gap + 1) return true;
        }
        return false;
    }

    public static int choose(Bot bot) {
        if (bot.health().hurt(bot) && !bot.health().naturalRegen(bot)) {
            int heal = chooseHealing(bot);
            if (heal >= 0) return heal;
        }
        int food = bot.player().getFoodData().getFoodLevel();
        boolean starving = food <= 4;
        // what the whole pack restores with each food eaten now and the rest in the best order:
        // going round the foods, and a raw one eaten raw when that pays more than a tired cooked one
        var plan = Diet.ifEatenFirst(bot);
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            FoodProperties f = Inv.food(s);
            if (f == null) continue;
            if (Inv.riskyFood(s) && !starving) continue;
            String id = Inv.id(s.getItem());
            if (WONT_EAT.getOrDefault(id, 0L) > System.currentTimeMillis() && !starving) continue;
            // what it restores after Spice of Fabric's repeats, and how long it takes to get it
            double value = Diet.nutrition(bot, s);
            // eaten so often it's worth nothing: not even starving, since it fills nothing (its
            // saturation stops at the food level, which is next to none by then). Foraging will
            if (value < 0.5) continue;
            // what it actually adds: hunger up to the gap, saturation up to the new food level (where
            // vanilla caps it), less what spills over: a porkchop at 14 throws 2 points away
            int gap = 20 - food;
            double hunger = Math.min(value, gap);
            double newFood = Math.min(20, food + value);
            double sat = Math.min(f.saturation(), Math.max(0, newFood - bot.player().getFoodData().getSaturationLevel()));
            Double pack = plan.get(id);
            // a risky food (starving) only after every safe one: it's in no plan
            double score = pack == null ? -1000 + value : pack + sat * 0.5 - (value - hunger) * 1.5;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected Status onTick(Bot bot) {
        var p = bot.player();
        if (ticks == 1) {
            // a crafting table (or any container) left open by the task we interrupted: the hotbar
            // swap below would go to its menu instead and silently fail. Take the grid back, close.
            if (p.containerMenu != p.inventoryMenu) {
                if (p.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu cm) {
                    for (int i = 1; i <= 9; i++) if (!cm.getSlot(i).getItem().isEmpty()) Inv.click(cm, i, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
                }
                p.closeContainer();
                ticks = 0; // select next tick, once the server has the menu shut
                return Status.RUNNING;
            }
            slot = choose(bot);
            if (slot < 0) return fail("no food");
            ItemStack s = Inv.inv().getItem(slot);
            foodId = Inv.id(s.getItem());
            startFood = p.getFoodData().getFoodLevel();
            startCount = Inv.count(s.getItem());
            // vanilla time times Spice of Fabric's slowdown, with slack for lag
            limit = (int) (s.getUseDuration(p) * Diet.slowdown(bot, s)) + 60;
            Nav.stop();
            if (!Inv.select(slot)) return fail("can't hold food");
            return Status.RUNNING;
        }
        FoodProperties held = Inv.food(Inv.held());
        if (!p.canEat(held != null && held.canAlwaysEat()) && !p.isUsingItem()) return Status.SUCCESS;
        if (!Inv.isFood(Inv.held())) {
            // the server can throw a hotbar swap back (it happens on CivFabric): ask it to move the
            // food to the hotbar itself, the way middle-click does, and give that a moment
            boolean stillThere = slot >= 0 && Inv.id(Inv.inv().getItem(slot).getItem()).equals(foodId);
            if (stillThere && ticks < 30) {
                // the server ignores inventory clicks while it thinks a container is still open
                // for us (one the client already forgot): closing shuts whatever it has open
                if (ticks == 3) {
                    p.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(p.containerMenu.containerId));
                    bot.event("hotbar swap was refused; telling the server to close any open container");
                }
                if (ticks == 6) Inv.select(slot);
                if (ticks == 12 && slot >= 9) Minecraft.getInstance().gameMode.handlePickItem(slot);
                return Status.RUNNING;
            }
            // eating the last piece empties the hand too
            if (Inv.count(s -> Inv.id(s.getItem()).equals(foodId)) < startCount || p.getFoodData().getFoodLevel() > startFood) {
                Controls.use(false);
                bot.memory().ateFood(foodId);
                bot.memory().count("meals");
                return Status.SUCCESS;
            }
            return fail("food left the hand (holding " + Inv.id(Inv.held().getItem()) + " in slot " + Inv.inv().selected
                    + ", wanted slot " + slot + " which has " + Inv.id(Inv.inv().getItem(slot).getItem()) + ")");
        }
        // holding "use" right-clicks whatever we're looking at first (a crafting table we just
        // placed, a Pokémon), and an open screen stops items being used at all: close it, look
        // at the sky and start eating directly
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) {
            // Visual Workbench keeps a crafting table's grid on the table: take it back first
            if (p.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu cm) {
                for (int i = 1; i <= 9; i++) if (!cm.getSlot(i).getItem().isEmpty()) Inv.click(cm, i, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
            }
            p.closeContainer();
        }
        if (!p.isUsingItem()) {
            p.setXRot(-90);
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
        Controls.use(true);
        // done when a piece is gone (a much-repeated food may restore almost nothing)
        boolean ate = Inv.count(Inv.held().getItem()) < startCount || p.getFoodData().getFoodLevel() > startFood;
        // some modded foods take longer than the vanilla time Spice of Fabric scales: if we're
        // still chewing, keep going rather than drop it and start over
        if (!ate && ticks > limit && p.isUsingItem() && ticks < limit * 3) return Status.RUNNING;
        if (ate || ticks > limit) {
            Controls.use(false);
            if (!ate) {
                WONT_EAT.put(foodId, System.currentTimeMillis() + 120_000);
                return fail("couldn't finish the " + foodId.replace("minecraft:", "") + (mc.screen != null
                        ? " (" + mc.screen.getClass().getSimpleName() + " open)" : p.isUsingItem() ? " (still chewing)" : " (never started)"));
            }
            bot.memory().ateFood(foodId);
            bot.memory().count("meals");
            if (Inv.healingFood(Inv.inv().getItem(slot)) || foodId.contains("golden_apple") || foodId.equals("civfabric:hearty_soup")) bot.healed();
            return Status.SUCCESS;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Controls.use(false);
    }
}
