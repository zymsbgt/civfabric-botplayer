package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.mixin.FishingHookAccessor;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Fish from the bank of a pond or lake near {@code near}: food while the crops grow, and
 * CivFabric's Fishing skill (8 XP a catch). Casts at open water (3x3 at the surface, so never the
 * farm's irrigation blocks), reels in when the bobber says a fish is biting, and recasts. Stops
 * when {@code until} says so (a crop is ripe), after {@code maxMinutes}, or when the rod breaks.
 */
public class FishTask extends Task {
    private enum Phase { ROD, SPOT, WALK, CAST, WAIT, REEL }

    private final BlockPos near;
    private final BooleanSupplier until;
    private final int maxTicks;
    private Phase phase = Phase.ROD;
    private int phaseTicks;
    private BlockPos water, stand;
    private final Set<BlockPos> badWater = new HashSet<>();
    private int catches, casts, misses;
    private int foodBefore;

    public FishTask(BlockPos near, BooleanSupplier until, int maxMinutes) {
        this.near = near;
        this.until = until;
        this.maxTicks = maxMinutes * 60 * 20;
    }

    @Override
    public String name() {
        return "Fish (" + phase.name().toLowerCase() + ", " + catches + " caught)";
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    /** Not after a recent failure (no rod to be had, no water): don't try again for 10 minutes. */
    private static long noFishUntil;

    /** Worth trying: we hold a rod, or one is cheap to make, and fishing hasn't just failed. */
    public static boolean canFish(Bot bot) {
        if (System.currentTimeMillis() < noFishUntil) return false;
        return Inv.find(FishTask::isRod) >= 0 || bot.planner().cost(Items.FISHING_ROD) < 120;
    }

    @Override
    protected Status fail(String why) {
        noFishUntil = System.currentTimeMillis() + 10 * 60_000L;
        if (Bot.get() != null) Bot.get().event("not fishing: " + why);
        return super.fail(why);
    }

    private static FishingHook hook() {
        return Minecraft.getInstance().player.fishing;
    }

    private static boolean isRod(net.minecraft.world.item.ItemStack s) {
        return s.is(Items.FISHING_ROD);
    }

    @Override
    protected Status onTick(Bot bot) {
        phaseTicks++;
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.FAILURE && phase == Phase.ROD) return fail("couldn't make a fishing rod");
            return Status.RUNNING;
        }
        boolean waiting = phase == Phase.WAIT || phase == Phase.REEL;
        if (!waiting && (ticks > maxTicks || ticks % 100 == 0 && until.getAsBoolean())) return done(bot);
        switch (phase) {
            case ROD -> {
                if (Inv.find(FishTask::isRod) >= 0) {
                    to(Phase.SPOT);
                    break;
                }
                if (bot.planner().cost(Items.FISHING_ROD) >= 120) return fail("no easy way to a fishing rod (string is the hard part)");
                setChild(bot, new ObtainTask(ItemTarget.of(Items.FISHING_ROD, 1)));
            }
            case SPOT -> {
                if (!pickSpot(bot)) return fail("no pond or lake to fish in near " + near.toShortString());
                to(Phase.WALK);
            }
            case WALK -> {
                var p = bot.player();
                if (p.blockPosition().equals(stand) && p.onGround()) {
                    Nav.stop();
                    to(Phase.CAST);
                } else if (phaseTicks > 20 * 40) {
                    badWater.add(water);
                    to(Phase.SPOT);
                } else if (phaseTicks % 20 == 1) Nav.goTo(new baritone.api.pathing.goals.GoalBlock(stand));
            }
            case CAST -> {
                if (!Inv.select(Inv.find(FishTask::isRod))) break;
                if (!isRod(Inv.held())) break; // the select lands next tick
                if (hook() != null) { // a line still out from before: wind it in first
                    use();
                    break;
                }
                // aim a little above the water's surface: the cast arcs down onto it
                Controls.lookAt(Vec3.atBottomCenterOf(water.above()).add(0, 0.6, 0), 180f);
                if (phaseTicks < 4) break; // let the look settle
                foodBefore = Inv.count(Inv::isFood);
                use();
                casts++;
                to(Phase.WAIT);
            }
            case WAIT -> {
                FishingHook h = hook();
                if (phaseTicks > 40 && h == null) { // the line snapped back (too far, or the rod broke)
                    if (Inv.find(FishTask::isRod) < 0) return done(bot);
                    to(Phase.CAST);
                    break;
                }
                if (h == null) break;
                // landed on the bank, or caught on something: wind in and aim elsewhere
                if (phaseTicks == 60 && !h.isInWater()) {
                    if (++misses > 3) {
                        badWater.add(water);
                        misses = 0;
                        use();
                        to(Phase.SPOT);
                        break;
                    }
                    use();
                    to(Phase.CAST);
                    break;
                }
                if (((FishingHookAccessor) h).zymbot$biting()) {
                    use();
                    to(Phase.REEL);
                    break;
                }
                // nothing for a long while (a cast too shallow or too near the bank): try again
                if (phaseTicks > 20 * 60) {
                    use();
                    to(Phase.CAST);
                }
            }
            case REEL -> {
                if (phaseTicks < 30) break; // the catch flies to us
                if (Inv.count(Inv::isFood) > foodBefore) {
                    catches++;
                    bot.memory().count("fish_caught");
                } else {
                    bot.memory().count("fish_other"); // junk or treasure, still the skill's XP
                }
                to(Phase.CAST);
            }
        }
        return Status.RUNNING;
    }

    private Status done(Bot bot) {
        if (hook() != null && isRod(Inv.held())) use();
        Nav.stop();
        if (casts > 0) bot.event("fished " + casts + " casts, " + catches + " food caught");
        return Status.SUCCESS;
    }

    private static void use() {
        var mc = Minecraft.getInstance();
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * Open water within 24 of {@code near} (a source block with water on all eight sides at the
     * surface and below it, so a pond, not a farm's channel) and dry ground 2-4 blocks from it to
     * stand on. Nearest to us first.
     */
    private boolean pickSpot(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        BlockPos bestW = null, bestS = null;
        double best = Double.MAX_VALUE;
        // farms sit on rises above their pond: look well below as well
        for (BlockPos c : BlockPos.betweenClosed(near.offset(-24, -16, -24), near.offset(24, 6, 24))) {
            if (!openWater(c) || badWater.contains(c)) continue;
            BlockPos w = c.immutable();
            for (BlockPos s : BlockPos.betweenClosed(w.offset(-4, 0, -4), w.offset(4, 2, 4))) {
                int d = Math.max(Math.abs(s.getX() - w.getX()), Math.abs(s.getZ() - w.getZ()));
                if (d < 2 || !WorldView.loaded(s) || WorldView.water(s) || !WorldView.standable(s)) continue;
                if (WorldView.water(s.below())) continue;
                double score = s.distSqr(me) + s.distSqr(near) * 0.5;
                if (score < best) {
                    best = score;
                    bestW = w;
                    bestS = s.immutable();
                }
            }
        }
        water = bestW;
        stand = bestS;
        return water != null;
    }

    private static boolean openWater(BlockPos c) {
        if (!WorldView.loaded(c) || !WorldView.water(c) || !WorldView.state(c.above()).isAir()) return false;
        if (!WorldView.water(c.below())) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!WorldView.water(c.offset(dx, 0, dz))) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!WorldView.state(c.relative(d).above()).isAir()) return false;
        return true;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (hook() != null && isRod(Inv.held())) use();
        Nav.stop();
    }
}
