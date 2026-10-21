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
 */
public class ShelterTask extends Task {
    private enum Phase { GO, PICK, WALK, DIG, COVER, HOLD, OUT }

    private Phase phase = Phase.GO;
    private BlockPos spot;   // where we stood before digging: the pit is spot-1..spot-3, the cover spot-1
    private final Set<BlockPos> bad = new HashSet<>();
    private int phaseTicks;
    private BlockPos digging;

    @Override
    public String name() {
        return "Shelter from the blood moon (" + phase.name().toLowerCase() + ")";
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
        if (!bot.pacifism().bloodMoon()) {
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
                boolean walk = b != null && b.closerThan(p.blockPosition(), 48) && !bot.pacifism().superBloodMoon()
                        && bot.ticks() - bot.lastHurtTick > 200;
                if (!walk || b.closerThan(p.blockPosition(), 10) || phaseTicks > 20 * 60) {
                    Nav.stop();
                    if (!walk && b != null && !b.closerThan(p.blockPosition(), 10))
                        bot.event("blood moon: " + (bot.pacifism().superBloodMoon() ? "a super one" : "too far from home or under attack")
                                + "; digging in here");
                    to(Phase.PICK);
                } else if (phaseTicks % 40 == 1) Nav.goNear(b, 4);
            }
            case PICK -> {
                spot = pickSpot(p.blockPosition());
                if (spot == null) {
                    bot.event("blood moon: nowhere to dig in here; staying by the base");
                    to(Phase.HOLD);
                } else to(Phase.WALK);
            }
            case WALK -> {
                if (p.blockPosition().equals(spot) && p.onGround()) {
                    Nav.stop();
                    to(Phase.DIG);
                } else if (phaseTicks > 20 * 30) {
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
                    bot.event("blood moon: dug in at " + spot.below(3).toShortString() + " until dawn");
                    to(Phase.HOLD);
                    break;
                }
                Item block = coverBlock();
                if (block == null || phaseTicks > 20 * 15) {
                    bot.event("blood moon: couldn't cover the pit; waiting in it");
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
                if (p.getFoodData().getFoodLevel() <= 14 && EatTask.choose(bot) >= 0 && phaseTicks % 200 == 0) {
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
                    bot.event("blood moon over; out of the shelter");
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
     * Plain ground near us that a pit can go into: three diggable blocks under a clear standing spot,
     * solid all round them (no water or air pockets), something solid under the bottom, and nothing
     * of ours or the farm's in the way.
     */
    private BlockPos pickSpot(BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : BlockPos.betweenClosed(from.offset(-8, -3, -8), from.offset(8, 3, 8))) {
            BlockPos s = c.immutable();
            if (bad.contains(s) || !WorldView.standable(s)) continue;
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
