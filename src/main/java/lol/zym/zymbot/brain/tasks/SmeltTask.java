package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.craft.ItemTarget;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/** Smelt {@code count} items in our own furnace (placed from the inventory if needed). */
public class SmeltTask extends Task {
    private enum Phase { STATION, OPEN, LOAD, COOK, CLOSE, RECLAIM, DONE }

    private final RecipeHolder<SmeltingRecipe> recipe;
    private final Ingredient input;
    private final Item output;
    private final int count;
    private final int startCount;
    private Phase phase = Phase.STATION;
    private BlockPos furnace;
    private boolean placed;
    private Breaker reclaim;
    private int wait;
    private boolean fuelChecked;

    public SmeltTask(RecipeHolder<SmeltingRecipe> recipe, int count) {
        this.recipe = recipe;
        this.input = recipe.value().getIngredients().get(0);
        this.output = Recipes.result(recipe).getItem();
        this.count = count;
        this.startCount = Inv.count(output);
    }

    private int made() {
        return Inv.count(output) - startCount;
    }

    @Override
    public String name() {
        return "Smelt " + Inv.id(output).replace("minecraft:", "") + " " + made() + "/" + count + " (" + (phase == null ? "done" : phase.name().toLowerCase()) + ")";
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        // cooking and levelling start us here directly: bring fuel along before finding we have none
        if (!fuelChecked) {
            fuelChecked = true;
            ItemTarget fuel = ObtainTask.fuelFor(bot, input, count - made());
            if (fuel != null) {
                setChild(bot, new ObtainTask(fuel));
                return Status.RUNNING;
            }
        }
        if (wait > 0) {
            wait--;
            return Status.RUNNING;
        }
        var player = bot.player();
        switch (phase) {
            case STATION -> {
                var known = bot.memory().nearestStation("furnace", player.blockPosition(), bot.dim(), 32);
                if (known.isPresent() && WorldView.state(known.get().at.pos()).getBlock() instanceof FurnaceBlock) {
                    furnace = known.get().at.pos();
                    placed = known.get().ours;
                } else {
                    known.ifPresent(s -> bot.memory().removeStation(s.at.pos()));
                    if (!Inv.has(Items.FURNACE)) return fail("no furnace");
                    BlockPos spot = Placer.spotNearPlayer();
                    if (spot == null || !Placer.place(Items.FURNACE, spot)) {
                        if (ticks > 300) return fail("no room for a furnace");
                        if (ticks % 60 == 59) setChild(bot, new ExploreTask(8)); // shuffle somewhere roomier
                        return Status.RUNNING;
                    }
                    furnace = spot;
                    placed = true;
                    bot.memory().addStation("furnace", spot, bot.dim(), true);
                    wait = 3;
                }
                phase = Phase.OPEN;
                ticks = 0;
            }
            case OPEN -> {
                if (player.containerMenu instanceof AbstractFurnaceMenu) {
                    phase = Phase.LOAD;
                    return Status.RUNNING;
                }
                if (!Breaker.inReach(furnace)) {
                    Nav.goToBlock(furnace);
                    if (ticks > 20 * 60) return fail("can't reach furnace");
                    return Status.RUNNING;
                }
                Nav.stop();
                if (ticks % 20 == 1) Placer.use(furnace);
                if (ticks > 20 * 30) return fail("furnace won't open");
            }
            case LOAD -> {
                if (!(player.containerMenu instanceof AbstractFurnaceMenu m)) {
                    phase = Phase.OPEN;
                    return Status.RUNNING;
                }
                ItemStack in = m.getSlot(0).getItem();
                int want = count - made() - in.getCount();
                if (want > 0) {
                    int src = Inv.find(input::test);
                    if (src < 0 && in.isEmpty()) return fail("nothing to smelt");
                    if (src >= 0) move(m, src, 0, want);
                }
                int fuelNeeded = (count - made()) * 200 - fuelTicks(m.getSlot(1).getItem());
                if (fuelNeeded > 0 && m.getSlot(1).getItem().getCount() < 64) {
                    // fuel first, then wood we can spare: a second crafting table, a worse wooden tool
                    int src = Inv.find(s -> isPlainFuel(s) && !input.test(s));
                    if (src < 0) src = spareWood(bot);
                    if (src >= 0) {
                        ItemStack f = Inv.inv().getItem(src);
                        int per = AbstractFurnaceBlockEntity.getFuel().getOrDefault(f.getItem(), 200);
                        move(m, src, 1, (fuelNeeded + per - 1) / per);
                    } else if (m.getSlot(1).getItem().isEmpty() && !m.isLit()) {
                        return fail("no fuel");
                    }
                }
                phase = Phase.COOK;
                ticks = 0;
            }
            case COOK -> {
                if (!(player.containerMenu instanceof AbstractFurnaceMenu m)) {
                    phase = Phase.OPEN;
                    ticks = 0;
                    return Status.RUNNING;
                }
                if (!m.getSlot(2).getItem().isEmpty()) Inv.click(m, 2, 0, ClickType.QUICK_MOVE);
                if (made() >= count) {
                    phase = Phase.CLOSE;
                } else if (m.getSlot(0).getItem().isEmpty() && m.getSlot(2).getItem().isEmpty() && ticks > 40) {
                    phase = Inv.find(input::test) >= 0 ? Phase.LOAD : Phase.CLOSE;
                } else if (!m.isLit() && ticks > 100 && m.getSlot(1).getItem().isEmpty()) {
                    phase = Phase.LOAD; // ran out of fuel
                } else if (ticks > 20 * (12 * count + 30)) {
                    return fail("smelting is taking far too long");
                }
            }
            case CLOSE -> {
                player.closeContainer();
                phase = placed && BotConfig.get().reclaimStations && Inv.canHarvest(WorldView.state(furnace)) ? Phase.RECLAIM : Phase.DONE;
                wait = 2;
            }
            case RECLAIM -> {
                if (reclaim == null) reclaim = new Breaker(furnace);
                Breaker.Result r = reclaim.tick();
                if (r == Breaker.Result.DONE) {
                    bot.memory().removeStation(furnace);
                    setChild(bot, new PickupTask(furnace, 3));
                    phase = Phase.DONE;
                } else if (r != Breaker.Result.RUNNING) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> {
                return made() >= count ? Status.SUCCESS : fail("only made " + made() + "/" + count);
            }
        }
        return Status.RUNNING;
    }

    /** Fuel worth burning: coal, wood, sticks, saplings, wooden slabs and stairs. Never wool, nor a tool or table we'd miss. */
    public static boolean isPlainFuel(ItemStack s) {
        if (s.isEmpty() || s.isDamageableItem() || !AbstractFurnaceBlockEntity.isFuel(s)) return false;
        return s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.COAL_BLOCK) || s.is(Items.STICK)
                || s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS)
                || s.is(net.minecraft.tags.ItemTags.WOODEN_SLABS) || s.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS)
                || s.is(net.minecraft.tags.ItemTags.SAPLINGS) || s.is(Items.BAMBOO) || s.is(Items.DRIED_KELP_BLOCK)
                || s.is(Items.BLAZE_ROD);
    }

    /**
     * A slot of wood we can spare to burn, or -1: a crafting table past the one we keep, a wooden tool
     * that isn't our best of its kind. Keep decides what's spare, as it does for the stash.
     */
    public static int spareWood(Bot bot) {
        for (var pile : lol.zym.zymbot.inv.Keep.surplus(bot)) {
            ItemStack s = Inv.inv().getItem(pile.slot());
            boolean woodenTool = s.getItem() instanceof net.minecraft.world.item.TieredItem t && t.getTier() == net.minecraft.world.item.Tiers.WOOD;
            if (s.is(Items.CRAFTING_TABLE) || woodenTool) return pile.slot();
        }
        return -1;
    }

    private static int fuelTicks(ItemStack s) {
        if (s.isEmpty()) return 0;
        return AbstractFurnaceBlockEntity.getFuel().getOrDefault(s.getItem(), 0) * s.getCount();
    }

    /** Move up to {@code n} items from an inventory index into a furnace slot. */
    private static void move(AbstractFurnaceMenu m, int invIndex, int slot, int n) {
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
        if (reclaim != null) reclaim.cancel();
        if (bot.player().containerMenu instanceof AbstractFurnaceMenu) bot.player().closeContainer();
        Nav.stop();
    }
}
