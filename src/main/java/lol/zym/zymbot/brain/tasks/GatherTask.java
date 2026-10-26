package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.BlockRules;
import lol.zym.zymbot.world.BlockSearch;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Mine blocks of the given types until the inventory holds {@code until} matching items.
 * Baritone does the walking, tool choice and breaking in both modes (see
 * {@link BotConfig#gatherMode}):
 * <ul>
 *   <li>targeted — we choose each block (natural, away from others' builds, not blacklisted,
 *       not mining-gated) and hand exactly that one to Baritone's builder as a 1-block clear</li>
 *   <li>baritone-mine — Baritone's mine process picks and mines veins on its own</li>
 * </ul>
 */
public class GatherTask extends Task {
    private final Set<Block> blocks;
    private final Predicate<ItemStack> match;
    private final int until;
    private final String label;
    private BlockSearch search;
    private BlockPos target;
    private int targetTicks;
    private int limitTicks;
    private int retries;
    private int explores;
    private int pickupWait;
    private boolean mining;
    /** Switched to targeted for the rest of this gather (a build is near). */
    private boolean careful;
    private int lastCount, lastGainTick;
    /** What the target was while it stood: a log on dirt was a tree's foot, to replant. */
    private BlockState targetWas;
    /** Trees we cut down, foot of the trunk -> its wood ("oak"): a sapling goes back in each. */
    private final java.util.LinkedHashMap<BlockPos, String> stumps = new java.util.LinkedHashMap<>();
    /** Stumps whose fallen saplings we've already gone round to pick up. */
    private final Set<BlockPos> gleaned = new java.util.HashSet<>();
    private int idleMining;
    /** Leaving cherry, fruit and apricorn trees standing while other wood will do ({@link #spared}). */
    private boolean sparing;

    public GatherTask(List<Block> blocks, Predicate<ItemStack> match, int until) {
        this.blocks = new HashSet<>(blocks);
        this.match = match;
        this.until = until;
        this.label = BuiltInRegistries.BLOCK.getKey(blocks.get(0)).getPath() + (blocks.size() > 1 ? "+" : "");
        sparing = this.blocks.stream().anyMatch(GatherTask::spared) && this.blocks.stream().anyMatch(b -> !spared(b));
    }

    /**
     * Wood we cut last (the user's wish, 2026-10-26): cherry trees (vanilla's and Vinery's dark
     * cherry), Vinery's apple orchard trees and Cobblemon's apricorn trees. Fruit and apricorns grow
     * on them, and cherry is scarce; any other log makes the same planks.
     */
    public static boolean spared(Block b) {
        if (!b.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) return false;
        var id = BuiltInRegistries.BLOCK.getKey(b);
        String path = id.getPath();
        return path.contains("cherry") || path.contains("apricorn") || id.getNamespace().equals("vinery") && path.contains("apple");
    }

    /** What we're after just now: everything, or with the spared trees left out while we spare them. */
    private boolean wanted(Block b) {
        return blocks.contains(b) && !(sparing && spared(b));
    }

    /** Nothing but spared trees found after a look round: they're what there is, so cut them too. */
    private boolean stopSparing(Bot bot) {
        if (!sparing || explores < 2) return false;
        sparing = false;
        search = null;
        bot.event("no other wood about: cutting cherry, fruit or apricorn trees for " + label + " after all");
        return true;
    }

    public boolean gathers(Block b) {
        return blocks.contains(b);
    }

    @Override
    public String name() {
        return "Gather " + label + " " + Inv.count(match) + "/" + until + (target != null ? " @" + target.toShortString() : "");
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            if (s != Status.RUNNING) search = null;
            return Status.RUNNING;
        }
        if (Inv.count(match) >= until) return replant(bot) ? Status.RUNNING : Status.SUCCESS;
        if (mining || bulk(bot)) return mineMode(bot);
        return targetedMode(bot);
    }

    // ---- targeted: we pick, Baritone breaks -------------------------------------------

    private Status targetedMode(Bot bot) {
        if (pickupWait > 0) {
            if (--pickupWait == 0) {
                setChild(bot, new PickupTask(target, 5));
                target = null;
            }
            return Status.RUNNING;
        }
        if (target == null) return findTarget(bot);

        if (!blocks.contains(WorldView.state(target).getBlock())) {
            // gone: broken by Baritone (or someone else) — collect what dropped
            Nav.stop();
            if (targetWas != null && targetWas.is(net.minecraft.tags.BlockTags.LOGS) && WorldView.state(target.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
                String wood = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(targetWas.getBlock()).getPath()
                        .replace("stripped_", "").replace("_log", "").replace("_wood", "");
                stumps.put(target.immutable(), wood);
            }
            targetWas = null;
            bot.memory().count("blocks_mined");
            pickupWait = 6;
            return Status.RUNNING;
        }
        targetWas = WorldView.state(target);
        var builder = Nav.baritone().getBuilderProcess();
        targetTicks++;
        if (!builder.isActive()) {
            if (++retries > 3) {
                bot.memory().blacklist(target, 10 * 60_000L);
                bot.event("Baritone couldn't break " + label + " at " + target.toShortString());
                target = null;
                return Status.RUNNING;
            }
            Nav.mine(target);
        }
        // far past what a break should take: something (reinforcement) is resisting
        if (targetTicks > limitTicks) {
            Nav.stop();
            bot.memory().blacklist(target, 6 * 3600_000L);
            bot.event(label + " at " + target.toShortString() + " won't break (reinforced or unreachable)");
            target = null;
        }
        return Status.RUNNING;
    }

    /**
     * Done gathering: a sapling back where each tree stood (ones we're near, while we have saplings).
     * The leaves come down a little after the trunk (LeafFall on the server), so first go round and
     * pick up what fell. True while there's replanting to do.
     */
    private boolean replant(Bot bot) {
        var it = stumps.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos stump = e.getKey();
            if (!ReplantTask.plantable(stump) || !stump.closerThan(bot.player().blockPosition(), 32)) {
                ReforestTask.remember(bot, stump);
                it.remove();
                continue;
            }
            if (gleaned.add(stump)) {
                setChild(bot, new PickupTask(stump, 8));
                return true;
            }
            if (Inv.count(st -> st.is(net.minecraft.tags.ItemTags.SAPLINGS)) == 0) {
                for (BlockPos st : stumps.keySet()) ReforestTask.remember(bot, st);
                stumps.clear();
                return false;
            }
            it.remove();
            // Realistic Plant Growth: none of ours grows here (the biome, or leaves still shading it)
            if (ReplantTask.pick(stump, e.getValue()) == null) {
                ReforestTask.remember(bot, stump);
                continue;
            }
            setChild(bot, new ReplantTask(stump, e.getValue()));
            return true;
        }
        return false;
    }

    /**
     * A branch log high up a big tree: with nothing to pillar on, Baritone can't get to it and we'd
     * spend half a minute finding that out per log.
     */
    private static boolean outOfReachAboveGround(BlockPos p) {
        if (Inv.find(st -> st.getItem() instanceof net.minecraft.world.item.BlockItem bi && BlockRules.scaffold(bi.getBlock())) >= 0) return false;
        for (int dy = 1; dy <= 12; dy++) {
            BlockState below = WorldView.state(p.below(dy));
            if (below.isAir() || below.is(net.minecraft.tags.BlockTags.LEAVES) || below.canBeReplaced()) continue;
            // stood on whatever's below (or the block next to it), the eye is ~2.6 up and reach ~4.2
            return dy > 6;
        }
        return true;
    }

    /** Mining this would drop gravel/sand on us or let water or lava in: that's how bots get trapped. */
    private static boolean cavesIn(BlockPos p, BlockState s) {
        if (!(s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                && WorldView.state(p.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return true;
        for (var d : net.minecraft.core.Direction.values()) {
            if (d != net.minecraft.core.Direction.DOWN && !WorldView.state(p.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Hangs over water (a flooded cave's ceiling, a lake's overhang): we'd mine it swimming, 25x
     * slower, and the stuck check keeps pulling us out before it breaks. Leave it for later.
     */
    private static boolean overWater(BlockPos p) {
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos b = p.below(dy);
            if (WorldView.water(b)) return true;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (WorldView.water(b.relative(d))) return true;
        }
        return false;
    }

    private Status findTarget(Bot bot) {
        BotConfig cfg = BotConfig.get();
        if (search == null) {
            search = new BlockSearch(bot.player().blockPosition(), cfg.searchRadius, 32,
                    s -> wanted(s.getBlock()), bot.memory().blacklistKeys());
        }
        if (!search.step(24)) return Status.RUNNING;
        for (BlockPos p : search.results()) {
            BlockState s = WorldView.state(p);
            if (!BlockRules.natural(s) || !WorldView.exposed(p) || cavesIn(p, s) || overWater(p)) continue;
            if (!bot.civ().canMine(bot.memory(), BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString())) continue;
            if (bot.nearSomeoneElsesBuild(p) || bot.inNoBreakZone(p, 0)) continue;
            if (outOfReachAboveGround(p)) continue;
            target = p;
            targetTicks = 0;
            retries = 0;
            // walking there plus 4x the vanilla break time, with generous slack
            float perTick = s.getDestroyProgress(bot.player(), bot.level(), p);
            int breakTicks = perTick <= 0 ? 1200 : (int) Math.ceil(1f / perTick);
            double dist = Math.sqrt(p.distSqr(bot.player().blockPosition()));
            limitTicks = (int) (dist * 12) + breakTicks * 4 + 400;
            bot.lastAction("mine:" + BuiltInRegistries.BLOCK.getKey(s.getBlock()));
            Nav.stop();
            Nav.mine(p);
            search = null;
            return Status.RUNNING;
        }
        search = null;
        if (stopSparing(bot)) return Status.RUNNING;
        if (++explores > 6) return fail("no " + label + " found after exploring");
        // somewhere we've seen it before beats wandering
        var kinds = lol.zym.zymbot.memory.Landmarks.kindsFor(blocks);
        var known = kinds.isEmpty() ? null : lol.zym.zymbot.memory.Landmarks.nearest(bot, kinds::contains, 256);
        // not out past radio range of the team's base
        BlockPos anchor = ExploreTask.anchor(bot);
        if (known != null && anchor != null && !known.at.pos().closerThan(anchor, 130)) known = null;
        if (known != null && !known.at.pos().closerThan(bot.player().blockPosition(), 24)) {
            bot.event("heading for " + known.kind + " seen at " + known.at.pos().toShortString());
            setChild(bot, new GoToTask(known.at.pos(), 6));
            return Status.RUNNING;
        }
        setChild(bot, new ExploreTask(64));
        return Status.RUNNING;
    }

    /** Hand the whole job to Baritone's #mine? Only natural bulk blocks, and only out in the wild. */
    private boolean bulk(Bot bot) {
        if (careful) return false;
        // Baritone's mine picks its own blocks, and would take them in the no-break zone too
        if (bot.inNoBreakZone(bot.player().blockPosition(), BotConfig.get().searchRadius)) return false;
        String mode = BotConfig.get().gatherMode;
        if ("baritone-mine".equalsIgnoreCase(mode)) return true;
        if (!"auto".equalsIgnoreCase(mode)) return false;
        return blocks.stream().allMatch(GatherTask::bulkMineable) && bot.wilderness();
    }

    private static boolean bulkMineable(Block b) {
        var s = b.defaultBlockState();
        return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || lol.zym.zymbot.craft.Sources.isOre(s) || b == net.minecraft.world.level.block.Blocks.GRAVEL
                || s.is(net.minecraft.tags.BlockTags.SAND) || b == net.minecraft.world.level.block.Blocks.CLAY
                || b == net.minecraft.world.level.block.Blocks.DEEPSLATE;
    }

    // ---- baritone-mine: Baritone picks and breaks -------------------------------------

    private Status mineMode(Bot bot) {
        var mine = Nav.baritone().getMineProcess();
        // a build came into view: stop bulk mining and finish one careful block at a time
        if (mining && !"baritone-mine".equalsIgnoreCase(BotConfig.get().gatherMode) && ticks % 20 == 0
                && (!bot.wilderness() || bot.builtWithin(bot.player().blockPosition(), 10))) {
            Nav.stop();
            mining = false;
            careful = true;
            bot.event("someone's build nearby: gathering " + label + " carefully from here");
            return Status.RUNNING;
        }
        // Baritone's own mining doesn't skip a spawner's room: pick blocks ourselves (away from it) from here
        if (mining && ticks % 20 == 0 && lol.zym.zymbot.world.Spawners.near(bot.player().blockPosition(), lol.zym.zymbot.civ.Pacifism.SPAWNED_RANGE)) {
            Nav.stop();
            mining = false;
            careful = true;
            bot.event("a spawner nearby: gathering " + label + " away from it");
            return Status.RUNNING;
        }
        if (!mining) {
            Nav.stop();
            mine.mine(blocks.stream().filter(this::wanted).toArray(Block[]::new));
            bot.lastAction("mine:" + BuiltInRegistries.BLOCK.getKey(blocks.iterator().next()));
            mining = true;
            idleMining = 0;
            lastCount = Inv.count(match);
            lastGainTick = ticks;
            bot.event("mining " + label + " with Baritone (no builds about)");
            return Status.RUNNING;
        }
        // nothing gained in a minute: likely a reinforced block Baritone keeps chipping at
        int have = Inv.count(match);
        if (have > lastCount) {
            lastCount = have;
            lastGainTick = ticks;
        } else if (ticks - lastGainTick > 20 * 60 && !"baritone-mine".equalsIgnoreCase(BotConfig.get().gatherMode)) {
            Nav.stop();
            mining = false;
            careful = true;
            bot.event("Baritone's mining stalled on " + label + "; going block by block");
            return Status.RUNNING;
        }
        if (!mine.isActive()) {
            // Baritone gave up: none known nearby
            if (++idleMining > 40) {
                mining = false;
                if (stopSparing(bot)) return Status.RUNNING;
                if (++explores > 6) return fail("Baritone found no " + label);
                setChild(bot, new ExploreTask(96));
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        // cut short (a reflex, a failure, a parent done with us): the trees still want saplings
        if (Minecraft.getInstance().player != null) for (BlockPos st : stumps.keySet()) ReforestTask.remember(bot, st);
        stumps.clear();
        mining = false;
        if (Minecraft.getInstance().player != null) Nav.stop();
    }
}
