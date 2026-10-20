package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.world.WorldView;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Throw Poké Balls at wild Pokémon. Cobblemon's PokemonEntity is a tameable animal, so the
 * vanilla {@link OwnableEntity} owner tells wild from someone's partner without compiling
 * against Cobblemon.
 */
public class CatchPokemonTask extends Task {
    private static final ResourceLocation POKEMON = ResourceLocation.fromNamespaceAndPath("cobblemon", "pokemon");
    private static final Map<Integer, Integer> attempts = new HashMap<>();
    private LivingEntity target;
    private int cooldown;

    @Override
    public String name() {
        return "Catch " + (target == null ? "Pokémon" : target.getName().getString());
    }

    public static boolean isPokemon(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).equals(POKEMON);
    }

    public static boolean wild(Entity e) {
        return isPokemon(e) && !(e instanceof OwnableEntity o && o.getOwnerUUID() != null);
    }

    public static boolean isBall(ItemStack s) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id.getNamespace().equals("cobblemon") && id.getPath().endsWith("_ball");
    }

    public static LivingEntity findWild(Bot bot, double radius) {
        return bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(bot.player().blockPosition()).inflate(radius),
                        e -> e.isAlive() && wild(e) && attempts.getOrDefault(e.getId(), 0) < 4
                                && !WorldView.deepWater(e.blockPosition())) // not worth a swim
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (Inv.find(CatchPokemonTask::isBall) < 0) return Status.SUCCESS;
        if (target == null || !target.isAlive()) {
            if (target != null) bot.memory().count("pokemon_gone"); // caught or fled
            target = findWild(bot, 24);
            if (target == null) return Status.SUCCESS;
        }
        if (attempts.getOrDefault(target.getId(), 0) >= 4 || ticks > 20 * 90) return Status.SUCCESS;
        double d = target.distanceTo(bot.player());
        if (d > 7 || !bot.player().hasLineOfSight(target)) {
            if (ticks % 20 == 1) Nav.goNear(target.blockPosition(), 4);
            return Status.RUNNING;
        }
        Nav.stop();
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        Inv.select(CatchPokemonTask::isBall);
        if (!Controls.lookAt(target.getBoundingBox().getCenter(), 40f)) return Status.RUNNING;
        Minecraft.getInstance().gameMode.useItem(bot.player(), InteractionHand.MAIN_HAND);
        bot.player().swing(InteractionHand.MAIN_HAND);
        attempts.merge(target.getId(), 1, Integer::sum);
        bot.memory().count("balls_thrown");
        cooldown = 60;
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
