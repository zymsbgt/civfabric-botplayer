package lol.zym.zymbot.control;

import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Placing blocks and right-clicking blocks (opening tables, furnaces, chests, beds, graves). */
public final class Placer {
    private Placer() {}

    /**
     * A spot near the player to put a station: replaceable, dry, in reach, not inside our own
     * hitbox, with at least one solid face to click. Spots on a floor come first, but in a
     * one-wide mined tunnel a block on the wall or ceiling works just as well.
     */
    public static BlockPos spotNearPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 2, 3))) {
            var st = WorldView.state(c);
            if (!st.canBeReplaced() || !st.getFluidState().isEmpty() || !Breaker.inReach(c)) continue;
            if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(c))) continue;
            boolean solidNeighbour = false;
            for (Direction d : Direction.values()) {
                var n = WorldView.state(c.relative(d));
                if (!n.isAir() && !n.canBeReplaced() && n.getFluidState().isEmpty()) {
                    solidNeighbour = true;
                    break;
                }
            }
            if (!solidNeighbour) continue;
            double score = c.distSqr(feet) + (WorldView.floor(c.below()) ? 0 : 6);
            if (score < bestScore) {
                bestScore = score;
                best = c.immutable();
            }
        }
        return best;
    }

    /** Place {@code item} so it ends up at {@code target}, clicking a solid neighbour. */
    public static boolean place(Item item, BlockPos target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!WorldView.state(target).canBeReplaced()) return why("target " + target.toShortString() + " not replaceable");
        int slot = Inv.find(s -> s.is(item));
        if (slot < 0) return why("no " + Inv.id(item) + " in inventory");
        if (!Inv.select(slot)) return why("couldn't put " + Inv.id(item) + " in hand");
        if (!Inv.held().is(item)) return why("hand holds " + Inv.id(Inv.held().getItem()) + " after selecting");
        String last = "no solid neighbour in reach";
        for (Direction d : Direction.values()) {
            BlockPos against = target.relative(d);
            BlockState s = WorldView.state(against);
            // clicking a chest/table would open it instead of placing; sneak state isn't synced
            // until the next tick, so just pick another face
            if (s.isAir() || s.canBeReplaced() || interactive(s)) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
            if (p.getEyePosition().distanceTo(hit) > Breaker.reach()) continue;
            Controls.lookAt(hit, 180f);
            InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
            if (r.consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
                return true;
            }
            last = "click on " + against.toShortString() + " " + face + " gave " + r;
        }
        return why(last);
    }

    private static long lastWhy;

    private static boolean why(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastWhy > 2000) {
            lastWhy = now;
            lol.zym.zymbot.ZymBotMod.LOG.info("place failed: {}", reason);
        }
        return false;
    }

    /** Right-click a block (open a container, sleep in a bed...). */
    public static boolean use(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 d = eye.subtract(c);
        Direction face = Direction.getNearest(d.x, d.y, d.z);
        Vec3 hit = c.add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        if (eye.distanceTo(hit) > Breaker.reach() + 0.3) return false;
        Controls.lookAt(hit, 180f);
        lol.zym.zymbot.memory.ChestIndex.used(pos, lol.zym.zymbot.Bot.get() == null ? 0 : lol.zym.zymbot.Bot.get().ticks());
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
        return r.consumesAction();
    }

    /**
     * Right-click one face of a block with whatever is in the main hand (till with a hoe, sow
     * a seed on farmland). Returns whether the click was sent; the server may still refuse it,
     * so callers check the block afterwards.
     */
    public static boolean useOn(BlockPos pos, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
        if (p.getEyePosition().distanceTo(hit) > Breaker.reach() + 0.3) return false;
        Controls.lookAt(hit, 180f);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean interactive(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock;
    }
}
