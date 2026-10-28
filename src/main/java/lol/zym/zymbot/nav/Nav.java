package lol.zym.zymbot.nav;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.*;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.world.BlockRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin wrapper over Baritone. The bot decides *where*; Baritone works out *how* to get there.
 *
 * <p>{@link #configure()} is what keeps Baritone civil: it may only break natural terrain on
 * the way (every other block id goes into {@code blocksToDisallowBreaking}), it only scaffolds
 * with junk blocks, and it does no parkour.
 */
public final class Nav {
    private Nav() {}

    private static boolean configured;
    /** Block types let off the no-break list for the one break that is in progress. */
    private static final java.util.Set<Object> breakNow = new java.util.HashSet<>();
    private static Goal lastGoal;

    public static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Apply bot-safe Baritone settings. Call once tags are bound (after joining a world). */
    public static void configure() {
        BotConfig cfg = BotConfig.get();
        Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = cfg.breakWhilePathing;
        s.allowPlace.value = cfg.placeWhilePathing;
        s.allowSprint.value = cfg.sprint;
        s.allowParkour.value = false;
        s.allowParkourPlace.value = false;
        s.allowWaterBucketFall.value = false;
        s.allowInventory.value = true;
        s.autoTool.value = true;
        s.maxFallHeightNoWater.value = Math.max(2, cfg.maxFall);
        s.avoidance.value = true;
        // spawners: what they make attacks pacifists, so paths give them a wide berth (Baritone's default is 2x)
        s.mobSpawnerAvoidanceRadius.value = 16;
        s.mobSpawnerAvoidanceCoefficient.value = 5.0;
        // a lit campfire burns whoever walks over it, and every warm bed has one beside it: Bot1 lost
        // health on the way into its own bed. Baritone avoids fire, not campfires or magma
        for (var b : java.util.List.of(net.minecraft.world.level.block.Blocks.CAMPFIRE, net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE,
                net.minecraft.world.level.block.Blocks.MAGMA_BLOCK))
            if (!s.blocksToAvoid.value.contains(b)) s.blocksToAvoid.value.add(b);
        // TRMT's eroded sand: worn down it's 10/16 tall, too high to step onto, and Baritone takes a
        // short block for one it can walk through, so bots walked into it and stuck (the user's call:
        // keep off it for now). Avoided, it is a wall to go around, or to dig through like sand
        BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trmt", "eroded_sand"))
                .filter(b -> !s.blocksToAvoid.value.contains(b)).ifPresent(s.blocksToAvoid.value::add);
        // CivFabric's grave is the same: a mound and a headstone, 13/16 at most, walked into as if air
        // (Bot5 stood against one for minutes exploring for logs)
        BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("civfabric", "grave"))
                .filter(b -> !s.blocksToAvoid.value.contains(b)).ifPresent(s.blocksToAvoid.value::add);
        s.antiCheatCompatibility.value = true;
        s.renderPath.value = true;
        s.chatDebug.value = false;
        s.primaryTimeoutMS.value = 1500L;
        s.failureTimeoutMS.value = 4000L;
        s.logger.value = c -> ZymBotMod.LOG.info("[baritone] {}", c.getString());

        List<Block> forbidden = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!BlockRules.natural(b.defaultBlockState())) forbidden.add(b);
        }
        s.blocksToDisallowBreaking.value = new FastContainsList<>(forbidden);

        List<Item> throwaway = new ArrayList<>(List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
                Items.NETHERRACK, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF));
        // planks too: a bot that bridged out on its last cobblestone stood on it in mid-air for ten
        // minutes with a stack of wood on it (Bot2), Baritone finding no way down. They're cheap from logs
        for (Item i : BuiltInRegistries.ITEM) if (new net.minecraft.world.item.ItemStack(i).is(net.minecraft.tags.ItemTags.PLANKS)) throwaway.add(i);
        s.acceptableThrowawayItems.value = throwaway;
        configured = true;
        ZymBotMod.LOG.info("Baritone configured: {} block types protected from pathing breaks", forbidden.size());
    }

    public static boolean configured() {
        return configured;
    }

    public static void resetConfigured() {
        configured = false;
    }

    /** Baritone's own cost for a block broken on the way, kept while mining. */
    private static final double MINING_BREAK_PENALTY = 2;

    /** Path to a goal; repeated calls with an equal goal don't restart the search. */
    public static void goTo(Goal goal) {
        IBaritone b = baritone();
        if (goal.equals(lastGoal) && b.getCustomGoalProcess().isActive()) return;
        lastGoal = goal;
        mining = false;
        travelGoal = goal instanceof baritone.api.utils.interfaces.IGoalRenderPos r ? r.getGoalPos() : null;
        travelRules();
        b.getCustomGoalProcess().setGoalAndPath(goal);
    }

    /** Go and break the block at {@code pos} (it must be off the no-break list: {@link #allowBreaking}). */
    public static void mine(BlockPos pos) {
        mining = true;
        BaritoneAPI.getSettings().allowBreak.value = BotConfig.get().breakWhilePathing;
        breakPenalty(MINING_BREAK_PENALTY);
        baritone().getBuilderProcess().clearArea(pos, pos);
    }

    /**
     * Travelling, walk round rather than dig through: ZymSB asked that bots heading somewhere don't
     * break blocks on the way. Mining a block, the digging to it is the job, so it keeps the default.
     */
    private static boolean mining;
    private static BlockPos travelGoal;

    /**
     * No breaking at all on a trip into, out of or through the no-break zone: a high penalty still
     * let Wandersong dig a stair from its bed up to ZymSB when there was no walk, and ZymSB had to
     * ask for the hole to be patched. Boxed in there, a trip fails rather than digs.
     */
    private static void travelRules() {
        var bot = lol.zym.zymbot.Bot.get();
        boolean zone = bot != null && (bot.inNoBreakZone(bot.player().blockPosition(), 0) || bot.inNoBreakZone(travelGoal, 0));
        BaritoneAPI.getSettings().allowBreak.value = BotConfig.get().breakWhilePathing && !zone;
        breakPenalty(BotConfig.get().travelBreakPenalty);
    }

    /**
     * Every few ticks: a trip that set off outside the zone (a follow) comes into it, and its next
     * path is planned without breaks.
     */
    public static void guardZone() {
        if (!mining) travelRules();
    }

    private static void breakPenalty(double p) {
        BaritoneAPI.getSettings().blockBreakAdditionalPenalty.value = p;
    }

    public static void goNear(BlockPos pos, int range) {
        goTo(new GoalNear(pos, range));
    }

    /** Stand next to a block (adjacent or on top) so it can be used or mined. */
    public static void goToBlock(BlockPos pos) {
        goTo(new GoalGetToBlock(pos));
    }

    public static void goXZ(int x, int z) {
        goTo(new GoalXZ(x, z));
    }

    public static void runAway(double distance, BlockPos... from) {
        goTo(new GoalRunAway(distance, from));
    }

    public static void follow(UUID player) {
        mining = false;
        travelGoal = null;
        travelRules();
        baritone().getFollowProcess().follow(e -> e.getUUID().equals(player));
    }

    public static void follow(Entity e) {
        follow(e.getUUID());
    }

    /** Baritone is computing or walking a path for any process. */
    /** Let Baritone sprint (fleeing) or not (everything else: sprinting costs ~10x walking's hunger). */
    public static void sprint(boolean on) {
        baritone.api.BaritoneAPI.getSettings().allowSprint.value = on || lol.zym.zymbot.BotConfig.get().sprint;
    }

    public static boolean busy() {
        IBaritone b = baritone();
        return b.getPathingBehavior().isPathing() || b.getCustomGoalProcess().isActive()
                || b.getFollowProcess().isActive() || b.getMineProcess().isActive()
                || b.getExploreProcess().isActive() || b.getFarmProcess().isActive()
                || b.getBuilderProcess().isActive();
    }

    /** Baritone is trying to get somewhere or mine something (not following/farming, which may stand still on purpose). */
    public static boolean travelling() {
        IBaritone b = baritone();
        return b.getCustomGoalProcess().isActive() || b.getMineProcess().isActive() || b.getBuilderProcess().isActive();
    }

    /** Where Baritone is trying to get to, when its goal is a place (not "away from" or a column). */
    /** The first block of water on the path we're walking, within the next {@code steps} steps, or null. */
    public static BlockPos waterAhead(int steps) {
        var ex = baritone().getPathingBehavior().getCurrent();
        if (ex == null) return null;
        var path = ex.getPath().positions();
        int at = Math.max(0, ex.getPosition());
        for (int i = at; i < Math.min(path.size(), at + steps); i++) {
            if (lol.zym.zymbot.world.WorldView.water(path.get(i))) return path.get(i);
        }
        return null;
    }

    public static BlockPos goalPos() {
        Goal g = baritone().getPathingBehavior().getGoal();
        if (g == null) g = lastGoal;
        return g instanceof baritone.api.utils.interfaces.IGoalRenderPos r ? r.getGoalPos() : null;
    }

    private static long erodedLiftedUntil;

    /**
     * Standing in eroded sand: kept off it, Baritone finds no way out when it's all around (Bot4 sat
     * at its bed, eroded sand on three sides, for half an hour). From inside, the next block of it is
     * level ground, so for 20 s it's walkable again; once we're out it's avoided as before.
     */
    public static void unhem(BlockPos feet) {
        var eroded = BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trmt", "eroded_sand")).orElse(null);
        if (eroded == null) return;
        var avoid = BaritoneAPI.getSettings().blocksToAvoid.value;
        long now = System.currentTimeMillis();
        if (hopUntil > 0 && now > hopUntil) {
            lol.zym.zymbot.control.Controls.forward(false);
            lol.zym.zymbot.control.Controls.jump(false);
            hopUntil = 0;
            hemSince = now;
        }
        if (lol.zym.zymbot.world.WorldView.state(feet).is(eroded)) {
            if (now > erodedLiftedUntil) ZymBotMod.LOG.info("standing in eroded sand: walkable for a while");
            erodedLiftedUntil = now + 20_000L;
            avoid.remove(eroded);
            hemAt = null;
            return;
        }
        // every way off this block leads onto (or into) it: walkable for a while, as above. Bot4 stood
        // on a lone sand block in sugar cane, eroded sand all round but for a crafting table
        int exits = 0, onto = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var n = feet.relative(d);
            var st = lol.zym.zymbot.world.WorldView.state(n);
            boolean open = (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty())
                    && lol.zym.zymbot.world.WorldView.state(n.above()).isAir();
            if (st.is(eroded) || open && lol.zym.zymbot.world.WorldView.state(n.below()).is(eroded)) onto++;
            else if (open) exits++;
        }
        if (exits == 0 && onto > 0) {
            if (now > erodedLiftedUntil) ZymBotMod.LOG.info("every way out of {} is eroded sand: walkable for a while", feet.toShortString());
            erodedLiftedUntil = now + 20_000L;
            avoid.remove(eroded);
        }
        if (now > erodedLiftedUntil && !avoid.contains(eroded)) avoid.add(eroded);
        // in a hole in it, eroded sand on two sides or more and nothing to path over: a player would
        // hop up onto it (10/16 high). Bot4 sat in a flooded one by its bed after the next block was dug
        net.minecraft.core.BlockPos up = null;
        int around = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var n = feet.relative(d);
            if (!lol.zym.zymbot.world.WorldView.state(n).is(eroded)) continue;
            around++;
            if (lol.zym.zymbot.world.WorldView.state(n.above()).isAir() && lol.zym.zymbot.world.WorldView.state(n.above(2)).isAir()) up = n;
        }
        if (around < 2 || up == null) {
            hemAt = null;
            return;
        }
        if (!feet.equals(hemAt)) {
            hemAt = feet.immutable();
            hemSince = now;
            return;
        }
        if (hopUntil == 0 && now - hemSince > 10_000L) {
            ZymBotMod.LOG.info("boxed in by eroded sand at {}: hopping up onto {}", feet.toShortString(), up.toShortString());
            stop();
            var top = net.minecraft.world.phys.Vec3.atCenterOf(up).add(0, 0.6, 0);
            lol.zym.zymbot.control.Controls.lookAt(top, 180f);
            lol.zym.zymbot.control.Controls.forward(true);
            lol.zym.zymbot.control.Controls.jump(true);
            hopUntil = now + 800L;
        }
    }

    private static net.minecraft.core.BlockPos hemAt;
    private static long hemSince, hopUntil;

    public static void stop() {
        lastGoal = null;
        IBaritone b = baritone();
        b.getPathingBehavior().cancelEverything();
        b.getFollowProcess().cancel();
        b.getMineProcess().cancel();
    }

    /**
     * Let Baritone break this block type for one deliberate break (a ripe crop, our own crafting
     * table), even though pathing must never break it. Undo with {@link #protectAgain}.
     */
    public static void allowBreaking(Block b) {
        breakNow.add(b);
    }

    public static void protectAgain(Block b) {
        breakNow.remove(b);
    }

    /** Off: Baritone breaks with whatever is in hand (a hoe on crops); on: it picks the best tool. */
    public static void autoTool(boolean on) {
        BaritoneAPI.getSettings().autoTool.value = on;
    }

    /** Baritone calls contains() on this list for every block it considers; keep that O(1). */
    private static final class FastContainsList<T> extends ArrayList<T> {
        private final java.util.HashSet<Object> set;

        FastContainsList(List<T> items) {
            super(items);
            set = new java.util.HashSet<>(items);
        }

        @Override
        public boolean contains(Object o) {
            return set.contains(o) && !breakNow.contains(o);
        }
    }

    public static String describe() {
        IBaritone b = baritone();
        Goal g = b.getPathingBehavior().getGoal();
        if (g == null) return "idle";
        return b.getPathingBehavior().isPathing() ? "→ " + g : "planning " + g;
    }
}
