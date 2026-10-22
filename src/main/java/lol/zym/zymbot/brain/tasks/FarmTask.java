package lol.zym.zymbot.brain.tasks;

import baritone.api.pathing.goals.GoalBlock;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.farm.PlantRules;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Work a field around a centre: harvest mature crops, re-till exhausted soil, and sow seeds
 * from the inventory, choosing for each plot a crop that will actually grow there.
 *
 * <p>Server rules this follows:
 * <ul>
 *   <li>Realistic Plant Growth ({@link PlantRules}): a player-planted crop grows only in its
 *       biomes and under open sky, otherwise it dies; the same crop can't go back on the same
 *       spot within the rotation period (the server refuses the seed).</li>
 *   <li>CivFabric: breaking a crop turns its farmland to coarse dirt (hoe twice to re-till),
 *       and a hoe in hand improves the harvest. A Journeyman+ Farmer can instead right-click a
 *       ripe crop with a hoe to harvest it and replant from the offhand without breaking the
 *       soil; the replant isn't a planting as far as rotation is concerned.</li>
 * </ul>
 * Only point this at our own or the team's fields: it harvests every ripe crop in range.
 */
public class FarmTask extends Task {
    private enum Kind { HARVEST, TILL, PLANT, PICKUP }

    private record Job(Kind kind, BlockPos pos, ItemEntity item) {}

    private final BlockPos center;
    private final int range;
    private final int minutes;
    private final Block only;

    private Job job;
    private int jobTicks;
    private Breaker breaker;
    /** After a click: the tick by which the block should have changed, and what it was before. */
    private int verifyBy = -1;
    private BlockState before;
    private Block sowing;
    private boolean viaReplant;
    private long clickedAt;
    private final Map<BlockPos, Integer> skip = new HashMap<>();
    private boolean replantOff;
    private boolean tillOff;
    private int hoeTries;
    /** Empty plots, and no seed we hold grows on them. */
    private boolean cantSow;
    private Item offhandWas;
    private int sinceSweep;
    private int harvested, sown, tilled;
    private String idle = "";
    private int idleTicks;
    private int shoreAt = -1000;
    private final Set<String> told = new HashSet<>();

    public FarmTask(BlockPos center, int range, int minutes) {
        this(center, range, minutes, null);
    }

    public FarmTask(BlockPos center, int range, int minutes, Block only) {
        this.center = center;
        this.range = range;
        this.minutes = minutes;
        this.only = only;
    }

    @Override
    public String name() {
        String what = only == null ? "" : " " + BuiltInRegistries.BLOCK.getKey(only).getPath();
        String did = harvested + sown + tilled == 0 ? "" : " (" + harvested + " in, " + sown + " sown)";
        return "Farm" + what + " " + center.toShortString() + " r" + range + did + (idle.isEmpty() ? "" : ": " + idle);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks > minutes * 60 * 20) return Status.SUCCESS;
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        if (bot.inventoryFull()) return Status.SUCCESS;

        if (breaker != null) return tickBreaker(bot);
        if (verifyBy >= 0) return verify(bot);

        if (job == null || !stillValid(bot, job)) {
            job = ticks % 20 == 0 || job != null ? next(bot) : null;
            jobTicks = 0;
            if (job == null) {
                Nav.stop();
                // a job can end with us in the farm's water hole: wait beside it, not in it (Bot1 sank
                // out of air every few seconds there)
                if (bot.player().isInWater() && ticks - shoreAt > 200) {
                    shoreAt = ticks;
                    setChild(bot, new ShoreTask());
                    return Status.RUNNING;
                }
                idleTicks++;
                // nothing we hold grows on the empty plots: say so and go (other seeds are the fix), not idle out the shift
                if (cantSow && idleTicks > 20 * 20) return fail("nothing I hold grows here");
                // the crops are growing and there's nothing to tend: fish meanwhile, back as soon as there's work
                if (idleTicks > 20 * 30 && FishTask.canFish(bot)) {
                    idleTicks = 0;
                    setChild(bot, new FishTask(center, () -> next(bot) != null, 5));
                }
                return Status.RUNNING;
            }
            idleTicks = 0;
            idle = "";
        }
        jobTicks++;
        if (jobTicks > 20 * 20) {
            skip(job.pos, 20 * 120);
            job = null;
            return Status.RUNNING;
        }

        if (job.kind == Kind.PICKUP) {
            if (job.item.distanceToSqr(bot.player()) > 0.8) Nav.goTo(new GoalBlock(job.item.blockPosition()));
            return Status.RUNNING;
        }
        if (!Breaker.inReach(job.pos)) {
            Nav.goNear(job.pos, 2);
            return Status.RUNNING;
        }
        Nav.stop();
        return act(bot, job);
    }

    // ---- choosing work ----------------------------------------------------------------

    private Job next(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        String dim = bot.dim();
        List<ItemEntity> drops = PickupTask.drops(bot, center, range + 2);
        Job best = null;
        double bestD = Double.MAX_VALUE;
        boolean anySeed = !seeds().isEmpty();
        String why = "";
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-range, -3, -range), center.offset(range, 3, range))) {
            if (!WorldView.loaded(p) || skipped(p)) continue;
            BlockState s = WorldView.state(p);
            Kind k = null;
            // CivFabric: a crop harvested without a hoe always fails, destroying it for nothing
            if (ripe(s, p) && (bestHoe() >= 0 || !bot.civ().present() || !(s.getBlock() instanceof CropBlock))) k = Kind.HARVEST;
            else if (ripe(s, p)) why = "no hoe: harvesting without one destroys the crop";
            else if (open(p.above())) {
                if (s.is(Blocks.FARMLAND)) {
                    rememberPlot(bot, dim, p);
                    if (anySeed) k = Kind.PLANT;
                    else why = "no seeds to sow";
                } else if (tillable(bot, dim, s, p)) {
                    if (tillOff) why = "no hoe to till with";
                    else k = Kind.TILL;
                }
            }
            if (k == null) continue;
            double d = p.distSqr(me) + (k == Kind.HARVEST ? 0 : 4);
            if (d < bestD) {
                bestD = d;
                best = new Job(k, p.immutable(), null);
            }
        }
        // gather the drops every few harvests, and when there's nothing else to do
        if (!drops.isEmpty() && (best == null || sinceSweep >= 6)) {
            ItemEntity e = drops.stream().min(Comparator.comparingDouble(i -> i.distanceToSqr(bot.player()))).get();
            sinceSweep = 0;
            return new Job(Kind.PICKUP, e.blockPosition(), e);
        }
        if (best == null) idle = why.isEmpty() ? "nothing to do" : why;
        return best;
    }

    private boolean stillValid(Bot bot, Job j) {
        if (j.kind == Kind.PICKUP) return j.item.isAlive();
        BlockState s = WorldView.state(j.pos);
        return switch (j.kind) {
            case HARVEST -> ripe(s, j.pos);
            case PLANT -> s.is(Blocks.FARMLAND) && open(j.pos.above());
            case TILL -> !tillOff && tillable(bot, bot.dim(), s, j.pos) && open(j.pos.above());
            default -> false;
        };
    }

    private static boolean ripe(BlockState s, BlockPos p) {
        if (s.getBlock() instanceof CropBlock c) return c.isMaxAge(s);
        if (s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = WorldView.state(p.relative(d));
                if (n.getBlock() instanceof AttachedStemBlock && n.getValue(AttachedStemBlock.FACING) == d.getOpposite()) return true;
            }
        }
        return false;
    }

    private static boolean open(BlockPos above) {
        BlockState s = WorldView.state(above);
        return (s.isAir() || s.canBeReplaced() && !(s.getBlock() instanceof CropBlock)) && s.getFluidState().isEmpty();
    }

    /**
     * Soil worth hoeing: exhausted farmland (coarse dirt) next to farmland, or any dirt or
     * coarse dirt we know was a plot. Never a random patch of ground.
     */
    private static boolean tillable(Bot bot, String dim, BlockState s, BlockPos p) {
        if (!(s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK))) return false;
        if (bot.memory().planting(dim, p) != null) return true;
        if (!s.is(Blocks.COARSE_DIRT)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (WorldView.state(p.relative(d)).is(Blocks.FARMLAND)) return true;
        return false;
    }

    private static void rememberPlot(Bot bot, String dim, BlockPos soil) {
        if (bot.memory().planting(dim, soil) == null) bot.memory().planted(dim, soil, null, 0);
    }

    // ---- doing it ---------------------------------------------------------------------

    private Status act(Bot bot, Job j) {
        switch (j.kind) {
            case HARVEST -> {
                BlockState s = WorldView.state(j.pos);
                boolean crop = s.getBlock() instanceof CropBlock;
                int hoe = bestHoe();
                if (crop && hoe >= 0 && !replantOff && farmerMayReplant(bot)) {
                    // Journeyman+ Farmer: harvest and resow in one click, soil stays farmland
                    Inv.select(hoe);
                    Block next = choose(bot, j.pos.below(), true);
                    putInOffhand(next == null ? null : seedOf(next));
                    before = s;
                    viaReplant = true;
                    sowing = next;
                    clickedAt = bot.ticks();
                    Placer.use(j.pos);
                    verifyBy = ticks + 12;
                    return Status.RUNNING;
                }
                if (crop && hoe >= 0) Inv.select(hoe);
                breaker = crop && hoe >= 0 ? new Breaker(j.pos).keepHand() : new Breaker(j.pos);
                bot.lastAction("mine:" + BuiltInRegistries.BLOCK.getKey(s.getBlock()));
                return tickBreaker(bot);
            }
            case TILL -> {
                int hoe = bestHoe();
                if (hoe < 0) {
                    // the hoe broke mid-field: make another (twice at most) rather than idle out the shift
                    if (hoeTries++ < 2 && bot.planner().cost(net.minecraft.world.item.Items.WOODEN_HOE) < lol.zym.zymbot.craft.Planner.INF) {
                        tell(bot, "hoe broke: making another");
                        job = null;
                        setChild(bot, new ObtainTask(lol.zym.zymbot.craft.ItemTarget.of(net.minecraft.world.item.Items.WOODEN_HOE, 1)));
                        return Status.RUNNING;
                    }
                    tillOff = true;
                    tell(bot, "need a hoe to re-till the field");
                    job = null;
                    return Status.RUNNING;
                }
                Inv.select(hoe);
                before = WorldView.state(j.pos);
                viaReplant = false;
                sowing = null;
                clickedAt = bot.ticks();
                Placer.useOn(j.pos, Direction.UP);
                verifyBy = ticks + 10;
                return Status.RUNNING;
            }
            case PLANT -> {
                Block crop = choose(bot, j.pos, false);
                if (crop == null) {
                    skip(j.pos, 20 * 60 * 2);
                    job = null;
                    return Status.RUNNING;
                }
                Item seed = seedOf(crop);
                if (Inv.find(st -> st.is(seed)) < 0) restoreOffhand(); // left there by a replant
                if (!Inv.select(Inv.find(st -> st.is(seed)))) {
                    job = null;
                    return Status.RUNNING;
                }
                before = WorldView.state(j.pos.above());
                viaReplant = false;
                sowing = crop;
                clickedAt = bot.ticks();
                Placer.useOn(j.pos, Direction.UP);
                verifyBy = ticks + 16; // allow for a round trip to a distant server
                return Status.RUNNING;
            }
            default -> {
                return Status.RUNNING;
            }
        }
    }

    /** Wait for the server to show the result of a click, and learn from refusals. */
    private Status verify(Bot bot) {
        Job j = job;
        if (j == null) {
            verifyBy = -1;
            return Status.RUNNING;
        }
        String dim = bot.dim();
        boolean late = ticks >= verifyBy;
        switch (j.kind) {
            case HARVEST -> {
                BlockState now = WorldView.state(j.pos);
                if (!ripe(now, j.pos)) {
                    harvested++;
                    sinceSweep++;
                    bot.memory().count("crops_harvested");
                    if (sowing != null && now.is(sowing)) sown++;
                    rememberPlot(bot, dim, j.pos.below());
                    done();
                } else if (late) {
                    // the server didn't take it: not a Journeyman Farmer, or no CivFabric here
                    if (bot.heard("to work a row without stopping", clickedAt))
                        bot.memory().rankBelow.merge("farmer", 2, Math::min);
                    replantOff = true;
                    restoreOffhand();
                    done();
                }
            }
            case TILL -> {
                BlockState now = WorldView.state(j.pos);
                if (now.getBlock() != before.getBlock()) {
                    tilled++;
                    rememberPlot(bot, dim, j.pos);
                    done();
                } else if (late) {
                    skip(j.pos, 20 * 60 * 5);
                    done();
                }
            }
            case PLANT -> {
                // the client shows the crop at once and the server may take it back, so only
                // judge once the server has had time to answer
                if (!late) break;
                BlockState now = WorldView.state(j.pos.above());
                boolean spent = bot.heard("ground is spent", clickedAt);
                if (sowing != null && now.is(sowing) && !spent) {
                    sown++;
                    bot.memory().planted(dim, j.pos, BuiltInRegistries.BLOCK.getKey(sowing).toString(), bot.level().getGameTime());
                    bot.memory().count("crops_sown");
                } else if (spent && sowing != null) {
                    // someone grew this crop here lately; pick another one next time
                    bot.memory().planted(dim, j.pos, BuiltInRegistries.BLOCK.getKey(sowing).toString(), bot.level().getGameTime());
                } else {
                    skip(j.pos, 20 * 60);
                }
                done();
            }
            default -> done();
        }
        return Status.RUNNING;
    }

    private void done() {
        verifyBy = -1;
        job = null;
        sowing = null;
    }

    private Status tickBreaker(Bot bot) {
        Breaker.Result r = breaker.tick();
        if (r == Breaker.Result.RUNNING) return Status.RUNNING;
        if (r == Breaker.Result.DONE) {
            harvested++;
            sinceSweep++;
            bot.memory().count("crops_harvested");
            if (job != null && WorldView.state(breaker.pos()).isAir()) rememberPlot(bot, bot.dim(), breaker.pos().below());
        } else {
            skip(breaker.pos(), 20 * 60 * 2);
        }
        breaker = null;
        job = null;
        return Status.RUNNING;
    }

    // ---- crops and seeds ---------------------------------------------------------------

    /** Crops we hold seeds for (or that the "farm <crop>" order allows). */
    private List<Block> seeds() {
        LinkedHashSet<Block> out = new LinkedHashSet<>();
        var inv = Inv.inv();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock c && (only == null || c == only)) out.add(c);
        }
        return new ArrayList<>(out);
    }

    private static Item seedOf(Block crop) {
        return crop.asItem();
    }

    /**
     * The best crop to sow on {@code soil}: one we have seed for, that survives there, that
     * Realistic Plant Growth lets grow in this biome and light, and that isn't the same crop
     * planted there inside the rotation period. Null (with a note why) if none.
     */
    private Block choose(Bot bot, BlockPos soil, boolean forReplant) {
        BlockPos at = soil.above();
        boolean rules = PlantRules.active();
        PlantRules pr = PlantRules.get();
        BotMemory.Planting last = bot.memory().planting(bot.dim(), soil);
        long now = bot.level().getGameTime();
        Block best = null;
        double bestScore = -Double.MAX_VALUE;
        List<String> no = new ArrayList<>();
        for (Block c : seeds()) {
            String id = BuiltInRegistries.BLOCK.getKey(c).toString();
            String name = BuiltInRegistries.BLOCK.getKey(c).getPath();
            // a ripe crop still stands there when choosing for a replant; judge the soil only
            if (!forReplant && !c.defaultBlockState().canSurvive(bot.level(), at)) {
                no.add(name + ": can't stand there");
                continue;
            }
            double score = 100;
            if (rules) {
                PlantRules.Judgement j = pr.judge(c, at);
                if (!j.grows()) {
                    no.add(name + ": " + j.why());
                    continue;
                }
                score = j.score();
                // replanting by hand isn't a planting to the rotation rule; sowing is
                boolean sameCrop = last != null && id.equals(last.crop);
                if (!forReplant && sameCrop && now - last.at < pr.rotationTicks() && now >= last.at) {
                    no.add(name + ": soil spent");
                    continue;
                }
                if (sameCrop) score -= 5; // rotate when it costs nothing
            }
            score += Math.min(Inv.count(seedOf(c)), 64) / 64.0;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        cantSow = best == null && !no.isEmpty() && no.stream().noneMatch(s -> s.endsWith("soil spent"));
        if (best == null) {
            String biome = rules && PlantRules.biomeAt(at) != null ? " in " + PlantRules.biomeAt(at).getPath() : "";
            boolean resting = no.stream().anyMatch(s -> s.endsWith("soil spent"));
            idle = no.isEmpty() ? "no seeds" : resting ? "soil resting; nothing else I hold grows here" : "nothing I hold grows here" + biome;
            if (!no.isEmpty()) tell(bot, "won't sow" + biome + ": " + String.join(", ", no));
        }
        return best;
    }

    private boolean farmerMayReplant(Bot bot) {
        if (!bot.civ().present()) return false;
        int r = bot.civ().rank(bot.memory(), "farmer");
        return r < 0 || r >= 2;
    }

    private static int bestHoe() {
        int best = -1, bestTier = -2;
        var inv = Inv.inv();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof HoeItem && Inv.tier(s) > bestTier) {
                bestTier = Inv.tier(s);
                best = i;
            }
        }
        return best;
    }

    /** Put {@code seed} in the offhand (the replant ability sows from there), or clear seeds out of it. */
    private void putInOffhand(Item seed) {
        var p = Bot.get().player();
        if (p.containerMenu != p.inventoryMenu) return;
        ItemStack off = p.getOffhandItem();
        if (seed != null && off.is(seed)) return;
        boolean offIsSeed = off.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
        if (seed == null && !offIsSeed) return;
        if (offhandWas == null && !offIsSeed) offhandWas = off.isEmpty() ? net.minecraft.world.item.Items.AIR : off.getItem();
        int slot = seed != null ? Inv.find(s -> s.is(seed)) : emptySlot();
        if (slot < 0) return;
        Inv.click(p.inventoryMenu, Inv.menuSlot(p.inventoryMenu, slot), 40, ClickType.SWAP);
    }

    /** Give the offhand back what it held before we sowed from it, so the seeds are in the main inventory again. */
    private void restoreOffhand() {
        var p = Bot.get() == null ? null : Bot.get().player();
        if (offhandWas == null || p == null || p.containerMenu != p.inventoryMenu || p.getOffhandItem().is(offhandWas)) return;
        int slot = offhandWas == net.minecraft.world.item.Items.AIR ? emptySlot() : Inv.find(s -> s.is(offhandWas));
        if (slot >= 0) Inv.click(p.inventoryMenu, Inv.menuSlot(p.inventoryMenu, slot), 40, ClickType.SWAP);
    }

    private static int emptySlot() {
        var inv = Inv.inv();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    private void skip(BlockPos p, int forTicks) {
        skip.put(p.immutable(), ticks + forTicks);
    }

    private boolean skipped(BlockPos p) {
        Integer until = skip.get(p);
        return until != null && until > ticks;
    }

    private void tell(Bot bot, String msg) {
        if (told.add(msg)) bot.event(msg);
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (breaker != null) breaker.cancel();
        Nav.stop();
        restoreOffhand();
        var p = bot.player();
        if (p != null) bot.memory().prunePlantings(bot.level().getGameTime(), PlantRules.get().rotationTicks() * 4);
    }

    /** "wheat", "carrots", "carrot", "beetroot_seeds", "farmersdelight:cabbages" -> the crop block. */
    public static Block cropNamed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.contains(":")) n = "minecraft:" + n;
        for (String cand : List.of(n, n + "s", n.replace("_seeds", ""), n.replace("_seeds", "s"))) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(cand);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (b instanceof CropBlock) return b;
            Item it = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (it instanceof BlockItem bi && bi.getBlock() instanceof CropBlock) return bi.getBlock();
        }
        return null;
    }
}
