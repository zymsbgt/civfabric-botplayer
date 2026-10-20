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
 * to eat, so the choice favours whatever hasn't been eaten lately, and we keep chewing for as long
 * as the food really takes. Foods with bad side effects only when starving.
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

    public static int choose(Bot bot) {
        if (bot.health().hurt(bot) && !bot.health().naturalRegen(bot)) {
            int heal = chooseHealing(bot);
            if (heal >= 0) return heal;
        }
        int food = bot.player().getFoodData().getFoodLevel();
        boolean starving = food <= 4;
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            FoodProperties f = Inv.food(s);
            if (f == null) continue;
            if (Inv.riskyFood(s) && !starving) continue;
            String id = Inv.id(s.getItem());
            if (WONT_EAT.getOrDefault(id, 0L) > System.currentTimeMillis() && !starving) continue;
            // what it restores after Spice of Fabric's repeats, and how long it takes to get it
            double value = Diet.nutrition(bot, s);
            if (value < 0.5 && !starving) continue; // eaten so often it's worth nothing
            double score = (value + f.saturation() * 2 * value / Math.max(1, f.nutrition())) / Math.sqrt(Diet.slowdown(bot, s));
            // don't waste a big meal on a small gap
            int gap = 20 - food;
            if (f.nutrition() > gap + 4) score *= 0.6;
            if (Inv.riskyFood(s)) score *= 0.2;
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
