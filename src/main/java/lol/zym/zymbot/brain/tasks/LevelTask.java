package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.civ.ClassBook;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Planner;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.world.BlockRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.*;

/**
 * Earn XP in one CivFabric class with the best-paying action we can actually do right now,
 * chosen from the server's own XP tables: blocks to break that we've seen nearby and may mine,
 * items to craft (priced in effort plus the craft's food cost), things to smelt, blocks to place
 * (Builder), the farm (Farmer) and hunting (Hunter). One round, then the milestone re-plans.
 */
public class LevelTask extends Task {
    private final String clazz;
    private String doing = "choosing";

    public LevelTask(String clazz) {
        this.clazz = clazz;
    }

    @Override
    public String name() {
        return "Level " + clazz + ": " + doing;
    }

    private record Option(String what, double score, java.util.function.Supplier<Task> make) {}

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.RUNNING) return Status.RUNNING;
            return s;
        }
        Option best = choose(bot);
        if (best == null) return fail("nothing pays " + clazz + " XP that we can do here");
        doing = best.what();
        Task t = best.make().get();
        if (t == null) return fail("couldn't start " + best.what());
        setChild(bot, t);
        return Status.RUNNING;
    }

    private Option choose(Bot bot) {
        ClassBook book = ClassBook.get();
        Planner planner = bot.planner();
        List<Option> options = new ArrayList<>();

        // the farm: harvesting a crop is 7 Farmer XP, and it feeds everyone
        if (clazz.equals("farmer") && bot.memory().home != null && lol.zym.zymbot.brain.Progression.farmPlots(bot) > 0) {
            BlockPos home = bot.memory().home.pos();
            int ripe = 0;
            for (BlockPos p : BlockPos.betweenClosed(home.offset(-10, -3, -10), home.offset(10, 3, 10))) {
                var st = lol.zym.zymbot.world.WorldView.state(p);
                if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock c && c.isMaxAge(st)) ripe++;
            }
            if (Inv.bestTier(net.minecraft.world.item.HoeItem.class) < 0)
                options.add(new Option("making a hoe for the farm", 1.0, () -> new ObtainTask(ItemTarget.of(Items.WOODEN_HOE, 1))));
            else if (ripe > 0 || !LayFieldTask.seedCrops().isEmpty())
                options.add(new Option("working the farm (" + ripe + " ripe)", 7.0 / 4, () -> new FarmTask(home, 10, 8)));
        }
        // cooking meat pays 5-7 Farmer XP a piece, and the team eats it
        if (clazz.equals("farmer")) {
            for (Item raw : List.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.COD, Items.SALMON, Items.RABBIT)) {
                if (Inv.count(raw) == 0) continue;
                for (var r : bot.level().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING)) {
                    if (!r.value().getIngredients().get(0).test(new net.minecraft.world.item.ItemStack(raw))) continue;
                    var holder = r;
                    int n = Inv.count(raw);
                    options.add(new Option("cooking " + Inv.id(raw).replace("minecraft:", ""), 6.0 / 3, () -> new SmeltTask(holder, n)));
                    break;
                }
                break;
            }
        }
        if (clazz.equals("hunter") && bot.player().getHealth() > 12) {
            List<EntityType<?>> prey = List.of(EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT, EntityType.PIG);
            options.add(new Option("hunting", 3.0 / 30, () -> new HuntTask(prey, s -> Inv.isFood(s) || s.is(Items.LEATHER), Inv.count(s -> Inv.isFood(s)) + 6).orPokemon()));
        }

        // breaking: natural blocks around us whose break pays this class
        for (var e : book.awardsFor("breaking", clazz)) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
            if (!bot.seenNearby(b) || !BlockRules.natural(b.defaultBlockState())) continue;
            if (!bot.civ().canMine(bot.memory(), e.getKey())) continue;
            // crops only pay when player-planted, and that's the farm's job
            if (clazz.equals("farmer") && !e.getKey().contains("berry") && !e.getKey().contains("leaves")) continue;
            double cost = 6 + (Inv.canHarvest(b.defaultBlockState()) ? 0 : 20);
            options.add(new Option("breaking " + id.getPath(), e.getValue().xp() / cost,
                    () -> Inv.canHarvest(b.defaultBlockState()) ? new GatherTask(List.of(b), s -> true, Inv.count(s -> true) + 8)
                            : toolFirst(bot, b)));
        }

        // crafting: what it pays per craft against what it costs to make, food included
        int looked = 0;
        for (var e : book.awardsFor("crafting", clazz)) {
            if (++looked > 40) break;
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item it = BuiltInRegistries.ITEM.get(id);
            if (!bot.civ().canCraft(bot.memory(), e.getKey())) continue;
            var recipes = Recipes.craftingFor(it);
            if (recipes.isEmpty()) continue;
            int yield = Math.max(1, Recipes.result(recipes.get(0)).getCount());
            double cost = planner.cost(it) * yield + planner.hunger(it) * Planner.HUNGER_COST;
            if (cost >= 300) continue;
            options.add(new Option("crafting " + id.getPath(), e.getValue().xp() * yield / Math.max(1, cost),
                    () -> new ObtainTask(ItemTarget.of(it, Inv.count(it) + yield)).makeIt()));
        }

        // smelting: the furnace pays on collection
        for (var e : book.awardsFor("smelting", clazz)) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item out = BuiltInRegistries.ITEM.get(id);
            if (Recipes.smeltingFor(out).isEmpty()) continue;
            double cost = planner.cost(out);
            if (cost >= 400) continue;
            options.add(new Option("smelting " + id.getPath(), e.getValue().xp() / Math.max(1, cost),
                    () -> new ObtainTask(ItemTarget.of(out, Inv.count(out) + 4)).makeIt()));
        }

        // paper trains Medic as well as Librarian (CivFabric's one companion craft): the way a
        // Novice Medic starts, since stitches need Apprentice and wound mush needs mob drops
        if (clazz.equals("medic")) {
            double cost = planner.cost(Items.PAPER) * 3 + planner.hunger(Items.PAPER) * Planner.HUNGER_COST;
            if (cost < 400) options.add(new Option("crafting paper", 3.0 * 3 / Math.max(1, cost),
                    () -> new ObtainTask(ItemTarget.of(Items.PAPER, Inv.count(Items.PAPER) + 3)).makeIt()));
        }

        // placing (Builder): a wall round home out of what we carry that pays
        if (clazz.equals("builder") && bot.memory().home != null) {
            for (int i = 0; i < 36; i++) {
                var s = Inv.inv().getItem(i);
                if (!(s.getItem() instanceof BlockItem bi) || s.getCount() < 8) continue;
                var a = book.award("placing", BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
                if (a == null || !a.clazz().equals("builder")) continue;
                Item block = s.getItem();
                options.add(new Option("walling home with " + Inv.id(block).replace("minecraft:", ""), a.xp() / 2,
                        () -> new WallTask(bot.memory().home.pos(), 12, block)));
                break;
            }
            if (options.stream().noneMatch(o -> o.what().startsWith("walling"))) {
                // logs pay to place, and cost nothing but a walk to gather
                options.add(new Option("gathering logs to build with", 1.0 / 8,
                        () -> new ObtainTask(ItemTarget.of(Items.OAK_LOG, Inv.count(Items.OAK_LOG) + 32))));
            }
        }

        return options.stream().max(Comparator.comparingDouble(Option::score)).orElse(null);
    }

    private static Task toolFirst(Bot bot, Block b) {
        Item tool = bot.planner().toolFor(b.defaultBlockState());
        return tool == null ? null : new ObtainTask(ItemTarget.of(tool, 1));
    }
}
