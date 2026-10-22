package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.CraftingTableBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Craft a recipe {@code times} times, in the 2x2 inventory grid or at a crafting table.
 * Uses the recipe book packet when the recipe is unlocked, otherwise fills the grid by hand
 * (modded recipes often have no unlock advancement). The server computes the result, so each
 * craft waits for the result slot to fill before taking it.
 */
public class CraftTask extends Task {
    private enum Phase { STATION, OPEN, FILL, WAIT, VERIFY, CLEANUP, RECLAIM }

    private final RecipeHolder<CraftingRecipe> recipe;
    private final int times;
    private final boolean table;
    private final Item output;
    private Phase phase;
    private int crafted;
    private int wait;
    private BlockPos tablePos;
    private boolean placedTable;
    private Breaker reclaim;
    private boolean usedBook;
    private boolean byHand;
    private int countBefore;
    private int meals;
    private boolean retriedFed;

    public CraftTask(RecipeHolder<CraftingRecipe> recipe, int times) {
        this.recipe = recipe;
        this.times = times;
        this.table = !Recipes.fits2x2(recipe.value());
        this.output = Recipes.result(recipe).getItem();
        this.phase = table ? Phase.STATION : Phase.FILL;
    }

    @Override
    public String name() {
        return "Craft " + Inv.id(output).replace("minecraft:", "") + " " + crafted + "/" + times + " (" + (phase == null ? "done" : phase.name().toLowerCase()) + ")";
    }

    private AbstractContainerMenu menu(Bot bot) {
        return table ? bot.player().containerMenu : bot.player().inventoryMenu;
    }

    private boolean menuReady(Bot bot) {
        AbstractContainerMenu m = menu(bot);
        int grid = table ? 9 : 4;
        return m.slots.size() > grid && m.getSlot(0) instanceof ResultSlot && (!table || m != bot.player().inventoryMenu);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        if (wait > 0) {
            wait--;
            return Status.RUNNING;
        }
        switch (phase) {
            case STATION -> {
                return station(bot);
            }
            case OPEN -> {
                if (menuReady(bot)) {
                    phase = Phase.FILL;
                    return Status.RUNNING;
                }
                if (!Breaker.inReach(tablePos)) {
                    Nav.goToBlock(tablePos);
                    if (ticks > 20 * 60) return fail("can't reach crafting table");
                    return Status.RUNNING;
                }
                Nav.stop();
                if (ticks % 20 == 0 || ticks < 3) Placer.use(tablePos);
                // ten seconds of clicks and no screen: most likely the table isn't where we think (a
                // placement the server put a block over from where the client showed it). Forget it
                // and look again; a real one nearby gets found by looking at the blocks.
                if (ticks > 20 * 10 && duds.size() < 3) {
                    duds.add(tablePos);
                    bot.memory().removeStation(tablePos);
                    phase = Phase.STATION;
                    ticks = 0;
                    return Status.RUNNING;
                }
                if (ticks > 20 * 75) return fail("crafting table won't open");
                return Status.RUNNING;
            }
            case FILL -> {
                if (crafted >= times) {
                    phase = Phase.CLEANUP;
                    return Status.RUNNING;
                }
                // CivFabric charges a craft to saturation first, then the bar, and silently refuses
                // one that would leave the bar under 4: eat first if that's where we are
                if (!fedEnough(bot)) {
                    if (meals < 3 && EatTask.choose(bot) >= 0 && bot.player().getFoodData().getFoodLevel() < 20) {
                        meals++;
                        clearGrid(bot);
                        setChild(bot, new EatTask());
                        return Status.RUNNING;
                    }
                    phase = Phase.CLEANUP;
                    return fail("too hungry to craft " + Inv.id(output) + " (costs " + bot.planner().hunger(output)
                            + " food, and CivFabric won't take the bar under 4) and nothing to eat");
                }
                if (table && !menuReady(bot)) {
                    phase = Phase.OPEN;
                    return Status.RUNNING;
                }
                clearGrid(bot);
                bot.lastAction("craft:" + recipe.id());
                if (!fill(bot)) {
                    clearGrid(bot);
                    return fail("missing ingredients for " + Inv.id(output));
                }
                phase = Phase.WAIT;
                wait = 1;
                ticks = 0;
                return Status.RUNNING;
            }
            case WAIT -> {
                AbstractContainerMenu m = menu(bot);
                ItemStack res = m.getSlot(0).getItem();
                if (!res.isEmpty() && res.is(output)) {
                    // a full pack leaves the result in the grid, which looks just like a refusal
                    if (!Inv.roomFor(res)) {
                        clearGrid(bot);
                        return fail("pack full: no room for " + Inv.id(output));
                    }
                    countBefore = Inv.count(output);
                    Inv.click(m, 0, 0, ClickType.QUICK_MOVE);
                    phase = Phase.VERIFY;
                    wait = 10; // the client shows its own prediction; let the server have its say
                } else if (ticks > 40) {
                    clearGrid(bot);
                    // refused for hunger rather than a gate? eat and try once more
                    if (!retriedFed && meals < 3 && bot.civ().present() && EatTask.choose(bot) >= 0
                            && bot.player().getFoodData().getFoodLevel() - bot.planner().hunger(output) < 6) {
                        retriedFed = true;
                        meals++;
                        phase = Phase.FILL;
                        setChild(bot, new EatTask());
                        return Status.RUNNING;
                    }
                    if (usedBook && !byHand) {
                        // some servers ignore recipe-book placement: lay the grid out ourselves instead
                        byHand = true;
                        phase = Phase.FILL;
                        wait = 2;
                        return Status.RUNNING;
                    }
                    return fail("server gave no result for " + Inv.id(output) + " (gated?)");
                }
                return Status.RUNNING;
            }
            case VERIFY -> {
                if (Inv.count(output) <= countBefore) {
                    clearGrid(bot);
                    return fail("server took back the " + Inv.id(output) + " (refused?)");
                }
                crafted++;
                bot.memory().count("crafts");
                phase = Phase.FILL;
                return Status.RUNNING;
            }
            case CLEANUP -> {
                clearGrid(bot);
                if (table) bot.player().closeContainer();
                phase = placedTable && BotConfig.get().reclaimStations ? Phase.RECLAIM : null;
                if (phase == null) return Status.SUCCESS;
                wait = 2;
                return Status.RUNNING;
            }
            case RECLAIM -> {
                if (reclaim == null) reclaim = new Breaker(tablePos);
                Breaker.Result r = reclaim.tick();
                if (r == Breaker.Result.DONE) {
                    bot.memory().removeStation(tablePos);
                    setChild(bot, new PickupTask(tablePos, 3));
                    placedTable = false;
                    phase = null;
                    return Status.RUNNING;
                }
                if (r != Breaker.Result.RUNNING) return Status.SUCCESS; // leave it, it's remembered
                return Status.RUNNING;
            }
            case null -> {
                return Status.SUCCESS;
            }
        }
    }

    /** Tables that wouldn't open for us this time: not tried again. */
    private final java.util.Set<BlockPos> duds = new java.util.HashSet<>();

    private Status station(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        var known = bot.memory().nearestStation("crafting_table", me, bot.dim(), 24);
        if (known.isPresent() && duds.contains(known.get().at.pos())) known = java.util.Optional.empty();
        if (known.isPresent()) {
            BlockPos p = known.get().at.pos();
            if (WorldView.state(p).getBlock() instanceof CraftingTableBlock) {
                tablePos = p;
                phase = Phase.OPEN;
                ticks = 0;
                return Status.RUNNING;
            }
            bot.memory().removeStation(p);
        }
        // a table standing right here at our own base (a teammate's): use it rather than place another
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-4, -2, -4), me.offset(4, 2, 4))) {
            if (WorldView.state(p).getBlock() instanceof CraftingTableBlock && !bot.nearSomeoneElsesBuild(p) && !duds.contains(p)) {
                tablePos = p.immutable();
                bot.memory().addStation("crafting_table", tablePos, bot.dim(), false);
                phase = Phase.OPEN;
                ticks = 0;
                return Status.RUNNING;
            }
        }
        if (!Inv.has(Items.CRAFTING_TABLE)) {
            // ours, just broken back up and still lying on the ground: fetch it. (Not someone
            // else's standing table: Visual Workbench keeps their items in its grid.)
            if (ticks < 100 && PickupTask.drops(bot, me, 6).stream().anyMatch(e -> e.getItem().is(Items.CRAFTING_TABLE))) {
                setChild(bot, new PickupTask(me, 6));
                return Status.RUNNING;
            }
            return fail("no crafting table");
        }
        BlockPos spot = Placer.spotNearPlayer();
        if (spot == null) {
            if (ticks > 300) return fail("no room to place a crafting table");
            if (ticks % 60 == 59) setChild(bot, new ExploreTask(8));
            return Status.RUNNING;
        }
        if (Placer.place(Items.CRAFTING_TABLE, spot)) {
            tablePos = spot;
            placedTable = true;
            bot.memory().addStation("crafting_table", spot, bot.dim(), true);
            phase = Phase.OPEN;
            ticks = 0;
            wait = 3;
        } else if (ticks > 20 * 20) {
            return fail("couldn't place a crafting table here");
        }
        return Status.RUNNING;
    }

    /**
     * Take what's in the grid back. Visual Workbench keeps a crafting table's grid when the menu
     * closes, so anything left there is lost to us. With the table menu shut, the player menu's
     * slots 5-8 are armour: leave it alone.
     */
    /** Enough in the stomach that CivFabric will let this craft through: saturation pays first. */
    private boolean fedEnough(Bot bot) {
        if (!bot.civ().present()) return true;
        int cost = bot.planner().hunger(output);
        if (cost <= 0) return true;
        var food = bot.player().getFoodData();
        int fromBar = Math.max(0, cost - (int) Math.floor(food.getSaturationLevel()));
        return food.getFoodLevel() - fromBar >= 4;
    }

    private void clearGrid(Bot bot) {
        if (!menuReady(bot)) return;
        AbstractContainerMenu m = menu(bot);
        int grid = table ? 9 : 4;
        for (int i = 1; i <= grid && i < m.slots.size(); i++) {
            if (!m.getSlot(i).getItem().isEmpty()) Inv.click(m, i, 0, ClickType.QUICK_MOVE);
        }
    }

    private boolean fill(Bot bot) {
        var mc = Minecraft.getInstance();
        AbstractContainerMenu m = menu(bot);
        usedBook = !byHand && bot.player().getRecipeBook().contains(recipe);
        if (usedBook) {
            mc.gameMode.handlePlaceRecipe(m.containerId, recipe, false);
            return true;
        }
        int gw = table ? 3 : 2;
        List<int[]> plan = new ArrayList<>(); // {gridSlot, ingredientIndex}
        List<Ingredient> ings = new ArrayList<>(recipe.value().getIngredients());
        if (recipe.value() instanceof ShapedRecipe s) {
            for (int r = 0; r < s.getHeight(); r++)
                for (int c = 0; c < s.getWidth(); c++) {
                    int idx = r * s.getWidth() + c;
                    if (!ings.get(idx).isEmpty()) plan.add(new int[]{1 + r * gw + c, idx});
                }
        } else {
            int k = 0;
            for (int i = 0; i < ings.size(); i++) {
                if (ings.get(i).isEmpty()) continue;
                plan.add(new int[]{1 + (k / gw) * gw + k % gw, i});
                k++;
            }
        }
        for (int[] step : plan) {
            Ingredient ing = ings.get(step[1]);
            int src = Inv.find(ing::test);
            if (src < 0) return false;
            int ms = Inv.menuSlot(m, src);
            if (ms < 0) return false;
            Inv.click(m, ms, 0, ClickType.PICKUP);     // pick up the stack
            Inv.click(m, step[0], 1, ClickType.PICKUP); // drop one into the grid
            Inv.click(m, ms, 0, ClickType.PICKUP);     // put the rest back
        }
        return true;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (reclaim != null) reclaim.cancel();
        if (phase != null && phase != Phase.STATION && phase != Phase.RECLAIM) {
            clearGrid(bot);
            if (table && bot.player().containerMenu != bot.player().inventoryMenu) bot.player().closeContainer();
        }
        Nav.stop();
    }
}
