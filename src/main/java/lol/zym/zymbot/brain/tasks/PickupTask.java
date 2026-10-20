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
import java.util.List;

/**
 * Walk over item drops near a point until none are left (or it takes too long). Drops floating
 * in deep water are left alone, except a grave's: those are worth a swim.
 */
public class PickupTask extends Task {
    private final BlockPos around;
    private final double radius;
    private boolean intoWater;

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
                e -> e.isAlive() && (intoWater || !WorldView.deepWater(e.blockPosition())));
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks > 20 * 15) return Status.SUCCESS;
        if (bot.inventoryFull()) return Status.SUCCESS;
        List<ItemEntity> items = drops(bot, around, radius, intoWater);
        if (items.isEmpty()) return ticks > 10 ? Status.SUCCESS : Status.RUNNING;
        ItemEntity nearest = items.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).get();
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
