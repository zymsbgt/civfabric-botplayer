package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.ClassPlan;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Sit out a blood moon. Pacifism is off for the night and everything comes for us (a spider pack
 * killed an unarmed bot in five seconds), so: dig a 1x1 pit three deep in plain ground, put a block
 * over our head, and wait for dawn there. The cover goes at ground level, where it has solid ground
 * on every side to be placed against. Only when the base is close and nothing has found us yet do we
 * walk home first (a bot that walked home through a Super Blood Moon died on the way, every time);
 * otherwise we dig in where we stand. With no spot to dig, stand by the base with the team.
 *
 * <p>Also a thunderstorm night, when CivFabric lets drowned ignore pacifism: they killed a bot at
 * full health by the river in 25 s. Out again when the storm or the night ends.
 */
public class ShelterTask extends Task {
    private enum Phase { GO, PICK, WALK, DIG, COVER, HOLD, OUT }

    private Phase phase = Phase.GO;
    private BlockPos spot;   // where we stood before digging: the pit is spot-1..spot-3, the cover spot-1
    private final Set<BlockPos> bad = new HashSet<>();
    private int phaseTicks;
    private BlockPos digging;
    private boolean shoreTried;
    private int walkLimit = 20 * 30;
    private boolean danger(Bot bot) {
        return bot.pacifism().bloodMoon() || stormNight(bot) || bot.brain().hideNight(bot);
    }

    /**
     * Thunder between 13000 and 23000, CivFabric's own test (StormNight.isStormNight): drowned hunt
     * pacifists then. Not {@link WorldView#isNight()}, which counts any thunderstorm as night (beds
     * work in one), so a thundering afternoon would send everyone into the ground.
     */
    public static boolean stormNight(Bot bot) {
        long t = bot.level().getDayTime() % 24000L;
        return bot.level().isThundering() && t >= 13000 && t < 23000;
    }

    private String why(Bot bot) {
        return bot.pacifism().bloodMoon() ? "blood moon" : stormNight(bot) ? "storm night" : bot.brain().hideWhy();
    }

    @Override
    public String name() {
        return "Shelter from the " + (phase == Phase.GO ? "night" : "storm or moon") + " (" + phase.name().toLowerCase() + ")";
    }

    private void to(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    private static BlockPos base(Bot bot) {
        BlockPos t = ClassPlan.teamHome(bot);
        if (t != null) return t;
        BotMemory.Pos h = bot.memory().bed != null ? bot.memory().bed : bot.memory().home;
        return h != null && h.dim.equals(bot.dim()) ? h.pos() : null;
    }

    @Override
    protected Status onTick(Bot bot) {
        phaseTicks++;
        var p = bot.player();
        if (!danger(bot)) {
            if (phase == Phase.HOLD || phase == Phase.COVER || phase == Phase.DIG) to(Phase.OUT);
            else if (phase != Phase.OUT) return Status.SUCCESS; // still on our way: nothing to undo
        }
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        switch (phase) {
            case GO -> {
                BlockPos b = base(bot);
                // not home past a trident drowned: home is the river it came out of
                boolean walk = b != null && b.closerThan(p.blockPosition(), 48) && !bot.pacifism().superBloodMoon()
                        && !(bot.brain().hideNight(bot) && !bot.pacifism().bloodMoon() && !stormNight(bot))
                        && bot.ticks() - bot.lastHurtTick > 200;
                if (!walk || b.closerThan(p.blockPosition(), 10) || phaseTicks > 20 * 60) {
                    Nav.stop();
                    if (!walk && b != null && !b.closerThan(p.blockPosition(), 10))
                        bot.event(why(bot) + ": " + (bot.pacifism().superBloodMoon() ? "a super one" : "too far from home or under attack")
                                + "; digging in here");
                    to(Phase.PICK);
                } else if (phaseTicks % 40 == 1) Nav.goNear(b, 4);
            }
            case PICK -> {
                spot = pickSpot(p.blockPosition(), 8);
                // caught by a river (water, sand and gravel all round): holding there walked us back
                // in towards the base, out of air, and starting over every few seconds (Bot1, a super
                // moon). Dig in on firmer ground further off, swimming to shore first if none is in sight
                if (spot == null) spot = pickSpot(p.blockPosition(), 24);
                if (spot == null && p.isInWater() && !shoreTried) {
                    shoreTried = true;
                    bot.event(why(bot) + ": in the water, nowhere to dig in; heading for shore");
                    setChild(bot, new ShoreTask());
                    break;
                }
                if (spot == null) {
                    bot.event(why(bot) + ": nowhere to dig in here; staying by the base");
                    to(Phase.HOLD);
                } else {
                    walkLimit = 20 * (30 + (int) Math.sqrt(spot.distSqr(p.blockPosition())));
                    to(Phase.WALK);
                }
            }
            case WALK -> {
                if (p.blockPosition().equals(spot) && p.onGround()) {
                    Nav.stop();
                    to(Phase.DIG);
                } else if (phaseTicks > walkLimit) {
                    bad.add(spot);
                    to(Phase.PICK);
                } else if (phaseTicks % 20 == 1) Nav.goTo(new baritone.api.pathing.goals.GoalBlock(spot));
            }
            case DIG -> {
                int feet = p.blockPosition().getY();
                if (feet <= spot.getY() - 3) {
                    stopDigging();
                    to(Phase.COVER);
                    break;
                }
                // drifted off the column (knocked about): start over somewhere
                if (p.blockPosition().getX() != spot.getX() || p.blockPosition().getZ() != spot.getZ() || phaseTicks > 20 * 60) {
                    stopDigging();
                    bad.add(spot);
                    to(Phase.PICK);
                    break;
                }
                // standing across two blocks, digging out one of them drops us nowhere: step onto the
                // middle of the column first (no sneaking, so we fall in once it's open)
                Vec3 mid = Vec3.atBottomCenterOf(spot);
                if (Math.abs(p.getX() - mid.x) > 0.2 || Math.abs(p.getZ() - mid.z) > 0.2) {
                    Controls.lookAt(mid.add(0, p.getEyeHeight(), 0), 180f);
                    Controls.forward(true);
                    break;
                }
                Controls.forward(false);
                dig(p.blockPosition().below());
            }
            case COVER -> {
                BlockPos cover = spot.below();
                if (!WorldView.passable(cover)) {
                    bot.event(why(bot) + ": dug in at " + spot.below(3).toShortString() + " until dawn");
                    to(Phase.HOLD);
                    break;
                }
                Item block = coverBlock();
                if (block == null || phaseTicks > 20 * 15) {
                    bot.event(why(bot) + ": couldn't cover the pit; waiting in it");
                    to(Phase.HOLD);
                    break;
                }
                if (phaseTicks % 5 == 0) Placer.place(block, cover);
            }
            case HOLD -> {
                Nav.stop();
                // knocked (or fought) out of the pit: dig in again
                if (spot != null && !inPit(p.blockPosition())) {
                    bad.add(spot);
                    to(Phase.PICK);
                    break;
                }
                // hungry in the hole: eat (the eat reflex can't take over from us)
                int food = p.getFoodData().getFoodLevel();
                if ((food <= 14 && EatTask.fitsNow(bot) || food <= EatTask.MUST_EAT) && EatTask.choose(bot) >= 0 && phaseTicks % 200 == 0) {
                    setChild(bot, new EatTask());
                    break;
                }
                // the cover went (someone broke it, or it never went on): put it back
                if (spot != null && phaseTicks % 100 == 0 && WorldView.passable(spot.below())
                        && p.blockPosition().getY() <= spot.getY() - 3 && coverBlock() != null) {
                    to(Phase.COVER);
                    break;
                }
                // no pit: stay close to the base and the team
                if (spot == null && phaseTicks % 60 == 0) {
                    BlockPos b = base(bot);
                    if (b != null && !b.closerThan(p.blockPosition(), 6)) Nav.goNear(b, 3);
                }
            }
            case OUT -> {
                // dawn: take the cover off and climb out (Baritone pillars up from the pit)
                if (spot == null || p.blockPosition().getY() >= spot.getY()) {
                    Nav.stop();
                    bot.event("dawn: out of the shelter");
                    return Status.SUCCESS;
                }
                if (!WorldView.passable(spot.below())) {
                    dig(spot.below());
                    break;
                }
                stopDigging();
                if (phaseTicks > 20 * 60) return Status.SUCCESS; // let the normal pathing sort it out
                if (phaseTicks % 20 == 1) Nav.goTo(new baritone.api.pathing.goals.GoalBlock(spot));
            }
        }
        return Status.RUNNING;
    }

    /** In the pit, under its cover: nothing can reach us but an arrow through a gap. */
    public boolean dugIn() {
        return phase == Phase.HOLD && spot != null && inPit(Minecraft.getInstance().player.blockPosition())
                && !WorldView.passable(spot.below());
    }

    private boolean inPit(BlockPos at) {
        return at.getX() == spot.getX() && at.getZ() == spot.getZ() && at.getY() <= spot.getY() - 2;
    }

    /**
     * Plain ground near us that a pit can go into: three diggable blocks under a clear, dry standing spot,
     * solid all round them (no water or air pockets), something solid under the bottom, and nothing
     * of ours or the farm's in the way.
     */
    private BlockPos pickSpot(BlockPos from, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // a river bed sits a few blocks below its banks
        int dy = r > 8 ? 5 : 3;
        for (BlockPos c : BlockPos.betweenClosed(from.offset(-r, -dy, -r), from.offset(r, dy, r))) {
            BlockPos s = c.immutable();
            if (bad.contains(s) || !WorldView.standable(s)) continue;
            // standable counts a lake bed: a pit there floods, and walking out to it drowned us
            if (WorldView.water(s) || WorldView.water(s.above())) continue;
            if (!fits(s)) continue;
            double d = s.distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static boolean fits(BlockPos s) {
        if (WorldView.nearBuilt(s, 2)) return false;
        // bare hands take ages on stone, and it drops nothing to cover the pit with
        boolean pick = Inv.bestTier(net.minecraft.world.item.PickaxeItem.class) >= 0;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos b = s.below(dy);
            if (!WorldView.loaded(b) || !diggable(WorldView.state(b))) return false;
            if (!pick && WorldView.state(b).is(BlockTags.BASE_STONE_OVERWORLD)) return false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = WorldView.state(b.relative(d));
                if (n.isAir() || !n.getFluidState().isEmpty() || n.canBeReplaced() || n.is(BlockTags.LEAVES)) return false;
            }
        }
        BlockState bottom = WorldView.state(s.below(4));
        return WorldView.floor(s.below(4)) && bottom.getFluidState().isEmpty();
    }

    /** Natural, quick to dig, and won't fall in on us. */
    private static boolean diggable(BlockState s) {
        if (s.getBlock() instanceof FallingBlock) return false;
        if (!s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
        if (s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) return false;
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT);
    }

    /** A plain block to put over our head: dirt or stone we just dug, else any full solid block. */
    private static Item coverBlock() {
        int slot = Inv.find(s -> s.getItem() instanceof BlockItem bi && plain(bi.getBlock().defaultBlockState()));
        return slot < 0 ? null : Inv.inv().getItem(slot).getItem();
    }

    private static boolean plain(BlockState s) {
        return !s.hasBlockEntity() && !(s.getBlock() instanceof FallingBlock) && s.isCollisionShapeFullBlock(WorldView.level(), BlockPos.ZERO)
                && (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                || s.is(BlockTags.PLANKS) || s.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE));
    }

    /** Mine one block by hand (Baritone won't dig out the block it stands on for us). */
    private void dig(BlockPos pos) {
        var mc = Minecraft.getInstance();
        BlockState st = WorldView.state(pos);
        if (st.isAir()) return;
        int tool = Inv.bestTool(st);
        if (tool >= 0) Inv.select(tool);
        Controls.lookAt(Vec3.atCenterOf(pos).add(0, 0.5, 0), 180f);
        if (!pos.equals(digging)) {
            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            digging = pos;
        } else {
            mc.gameMode.continueDestroyBlock(pos, Direction.UP);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void stopDigging() {
        Controls.forward(false);
        if (digging != null) Minecraft.getInstance().gameMode.stopDestroyBlock();
        digging = null;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        stopDigging();
        Nav.stop();
    }
}
