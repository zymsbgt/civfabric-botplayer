package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Wool without killing anything: shear sheep, and the shearable wild Pokémon (Mareep, Wooloo),
 * with CivFabric's wooden shears (two logs, no class; iron shears need Blacksmith 3). The animal
 * grows its wool back, so a flock is a renewable supply where hunting would use it up.
 */
public class ShearTask extends Task {
    private static final ResourceLocation WOODEN_SHEARS = ResourceLocation.fromNamespaceAndPath("civfabric", "wooden_shears");

    private final int wantWool;
    private final Set<Integer> tried = new HashSet<>();
    private LivingEntity target;
    private int woolBefore;
    private int clickedAt = -1;
    private int explores;

    /** Shear until we carry {@code wantWool} wool of one colour. */
    public ShearTask(int wantWool) {
        this.wantWool = wantWool;
    }

    @Override
    public String name() {
        return "Shear for wool (" + bestColour() + "/" + wantWool + ")" + (target != null ? " " + target.getName().getString() : "");
    }

    /** The most wool we hold of any single colour. */
    public static int bestColour() {
        int best = 0;
        for (Item w : BuiltInRegistries.ITEM) if (new ItemStack(w).is(ItemTags.WOOL)) best = Math.max(best, Inv.count(w));
        return best;
    }

    public static boolean isShears(ItemStack s) {
        return s.is(Items.SHEARS) || BuiltInRegistries.ITEM.getKey(s.getItem()).equals(WOODEN_SHEARS);
    }

    public static Item woodenShears() {
        return BuiltInRegistries.ITEM.containsKey(WOODEN_SHEARS) ? BuiltInRegistries.ITEM.get(WOODEN_SHEARS) : Items.SHEARS;
    }

    private static int woolCount() {
        return Inv.count(s -> s.is(ItemTags.WOOL));
    }

    @Override
    protected Status onTick(Bot bot) {
        if (bestColour() >= wantWool) return Status.SUCCESS;
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.FAILURE && Inv.find(ShearTask::isShears) < 0) return fail("couldn't make wooden shears");
            return Status.RUNNING;
        }
        if (Inv.find(ShearTask::isShears) < 0) {
            setChild(bot, new ObtainTask(ItemTarget.of(woodenShears(), 1)));
            return Status.RUNNING;
        }
        var p = bot.player();
        // a click is out: did wool come of it?
        if (clickedAt >= 0) {
            if (woolCount() > woolBefore || ticks - clickedAt > 30) {
                if (target != null) tried.add(target.getId());
                BlockPos at = target != null ? target.blockPosition() : p.blockPosition();
                target = null;
                clickedAt = -1;
                setChild(bot, new PickupTask(at, 5));
            }
            return Status.RUNNING;
        }
        if (target == null || !target.isAlive()) {
            target = bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(48), e -> e.isAlive()
                            && !tried.contains(e.getId()) && shearable(e))
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
            if (target == null) {
                if (++explores > 4) return fail("nothing to shear around here");
                setChild(bot, new ExploreTask(64));
                return Status.RUNNING;
            }
        }
        if (target.distanceTo(p) > 2.8) {
            if (ticks % 10 == 0) Nav.goNear(target.blockPosition(), 1);
            if (ticks > 20 * 120) {
                tried.add(target.getId());
                target = null;
                ticks = 0;
            }
            return Status.RUNNING;
        }
        Nav.stop();
        if (!Inv.select(Inv.find(ShearTask::isShears))) return Status.RUNNING;
        if (!isShears(Inv.held())) return Status.RUNNING; // the select lands next tick
        lol.zym.zymbot.control.Controls.lookAt(target.getEyePosition().subtract(0, 0.4, 0), 90f);
        woolBefore = woolCount();
        Minecraft.getInstance().gameMode.interact(p, target, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
        clickedAt = ticks;
        return Status.RUNNING;
    }

    /** An adult sheep with its wool on, or a wild Pokémon that can be sheared (Mareep, Wooloo). */
    private static boolean shearable(LivingEntity e) {
        if (e instanceof Sheep s) return !s.isSheared() && !s.isBaby() && !HuntTask.looksOwned(s);
        if (!CatchPokemonTask.wild(e) || !(e instanceof Shearable)) return false;
        String n = e.getName().getString().toLowerCase(java.util.Locale.ROOT);
        return n.equals("mareep") || n.equals("wooloo") || n.equals("dubwool") || n.equals("flaaffy");
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
