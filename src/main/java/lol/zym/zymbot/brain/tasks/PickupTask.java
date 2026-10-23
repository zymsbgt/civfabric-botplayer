package lol.zym.zymbot.brain.tasks;

import baritone.api.pathing.goals.GoalBlock;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Walk over item drops near a point until none are left (or it takes too long). Drops floating
 * in deep water are left alone, except a grave's: those are worth a swim. A drop still lying there
 * when time runs out is out of reach and is left alone from then on: Bot2 spent two minutes on a
 * log in a gap under leaves, the planner sending it back to pick it up every 15 s.
 */
public class PickupTask extends Task {
    private final BlockPos around;
    private final double radius;
    private boolean intoWater;
    private ItemEntity chasing;
    /** Drops a pickup timed out on; few enough that clearing it now and then is fine (drops despawn). */
    private static final Set<UUID> outOfReach = new HashSet<>();

    public PickupTask(BlockPos around, double radius) {
        this.around = around;
        this.radius = radius;
    }

    /** Also swim for drops in deep water (our own scattered grave). */
    public PickupTask intoWater() {
        intoWater = true;
        return this;
    }

    @Override
    public String name() {
        return "Pick up drops";
    }

    /** Drops near a point that can be picked up without swimming. */
    public static List<ItemEntity> drops(Bot bot, BlockPos around, double radius) {
        return drops(bot, around, radius, false);
    }

    private static List<ItemEntity> drops(Bot bot, BlockPos around, double radius, boolean intoWater) {
        return bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(radius),
                e -> e.isAlive() && !outOfReach.contains(e.getUUID()) && (intoWater || !WorldView.deepWater(e.blockPosition())));
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks > 20 * 15) {
            if (chasing != null && chasing.isAlive()) {
                if (outOfReach.size() > 256) outOfReach.clear();
                outOfReach.add(chasing.getUUID());
                bot.event("can't reach the " + chasing.getItem().getHoverName().getString() + " at "
                        + chasing.blockPosition().toShortString() + ": leaving it");
            }
            return Status.SUCCESS;
        }
        if (bot.inventoryFull()) return Status.SUCCESS;
        List<ItemEntity> items = drops(bot, around, radius, intoWater);
        if (items.isEmpty()) return ticks > 10 ? Status.SUCCESS : Status.RUNNING;
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).get();
        chasing = nearest;
        if (nearest.distanceToSqr(bot.player()) < 0.8) return Status.RUNNING; // being picked up
        Nav.goTo(new GoalBlock(nearest.blockPosition()));
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
