package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Kill animals of the given types for their drops. Skips anything that looks owned:
 * tamed, named, leashed, babies, or standing near fences (someone's pen).
 */
public class HuntTask extends Task {
    private final Set<EntityType<?>> types;
    private final Predicate<ItemStack> match;
    private final int until;
    private LivingEntity prey;
    private int explores;
    private final Set<Integer> skipped = new HashSet<>();
    private boolean pokemon;
    private LivingEntity lastPrey;
    private float lastPreyHealth;
    private int lastHitTick;
    /**
     * A Pokémon that hits back: its species defends itself in Cobblemon's data, or one has hurt us
     * before (remembered across restarts). Once hit it chases us until one of us is dead.
     */
    public static boolean fighter(Bot bot, LivingEntity e) {
        return lol.zym.zymbot.inv.PokemonFood.fightsBack(e)
                || bot.memory().fighters.containsKey(e.getName().getString().toLowerCase(java.util.Locale.ROOT));
    }

    /** Worth picking a fight with: a fighter only with a sword or axe, near-full health, and no bigger than us. */
    private static boolean mayHunt(Bot bot, LivingEntity e) {
        float hp = bot.player().getHealth();
        if (!fighter(bot, e)) return hp > 12;
        return Inv.armed() && hp >= 16 && !bot.health().fragile(bot) && e.getMaxHealth() <= hp;
    }
    /** Hunt wild Pokémon whose drops match this too (wool from Mareep, string from Spinarak). */
    private java.util.function.Predicate<ItemStack> pokemonFor;

    public HuntTask(List<EntityType<?>> types, Predicate<ItemStack> match, int until) {
        this.types = new HashSet<>(types);
        this.match = match;
        this.until = until;
    }

    /** Also hunt wild Pokémon that drop what we're after. */
    public HuntTask orPokemonFor(java.util.function.Predicate<ItemStack> drop) {
        pokemonFor = drop;
        return this;
    }

    /** Also hunt wild Pokémon whose drops are food (Pidgey for chicken, Wooloo for mutton...). */
    public HuntTask orPokemon() {
        pokemon = true;
        return this;
    }

    @Override
    public String name() {
        return "Hunt " + (prey != null ? prey.getName().getString()
                : types.isEmpty() ? "Pokémon" : types.iterator().next().getDescription().getString())
                + " " + Inv.count(match) + "/" + until;
    }

    public static boolean looksOwned(LivingEntity e) {
        if (e.hasCustomName()) return true;
        if (e instanceof OwnableEntity o && o.getOwnerUUID() != null) return true;
        if (e instanceof net.minecraft.world.entity.Mob m && m.isLeashed()) return true;
        if (e instanceof Animal a && a.isBaby()) return true;
        if (BotConfig.get().spareFencedAnimals) {
            BlockPos p = e.blockPosition();
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-6, -2, -6), p.offset(6, 2, 6))) {
                if (WorldView.loaded(q) && WorldView.state(q).is(BlockTags.FENCES)) return true;
            }
        }
        return false;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (Inv.count(match) >= until) return Status.SUCCESS;
        if (child != null) {
            runChild(bot);
            return Status.RUNNING;
        }
        if (prey == null || !prey.isAlive()) {
            if (prey != null && !prey.isAlive()) {
                BlockPos at = prey.blockPosition();
                prey = null;
                setChild(bot, new PickupTask(at, 6));
                return Status.RUNNING;
            }
            prey = bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(bot.player().blockPosition()).inflate(48),
                            e -> (types.contains(e.getType()) || mayHunt(bot, e) && (pokemon && lol.zym.zymbot.inv.PokemonFood.edible(e)
                                    || pokemonFor != null && lol.zym.zymbot.inv.PokemonFood.drops(e, pokemonFor)))
                                    && e.isAlive() && !skipped.contains(e.getId())
                                    && !WorldView.deepWater(e.blockPosition()))
                    .stream().filter(e -> {
                        // a hostile that pacifism keeps off us: killing one turns its whole kin
                        // on us. And no hostiles at all while we can't afford to be hit.
                        if (e instanceof net.minecraft.world.entity.monster.Enemy
                                && (bot.pacifism().fightingCosts(e) || bot.health().fragile(bot))) {
                            skipped.add(e.getId());
                            return false;
                        }
                        if (looksOwned(e)) {
                            skipped.add(e.getId());
                            return false;
                        }
                        return true;
                    })
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
            if (prey == null) {
                if (++explores > 5) return fail("no huntable animals around");
                setChild(bot, new ExploreTask(80));
                return Status.RUNNING;
            }
        }
        // a Pokémon that hits back stays angry until one of us is dead: walking off doesn't end it,
        // so finish it (the brain's fight reflex takes over; its kind is remembered as a fighter)
        // out of reach (a flying Combee, something across water): no damage in 30 s means give up on it
        if (prey != lastPrey) {
            lastPrey = prey;
            lastPreyHealth = prey.getHealth();
            lastHitTick = ticks;
        } else if (prey.getHealth() < lastPreyHealth) {
            lastPreyHealth = prey.getHealth();
            lastHitTick = ticks;
        } else if (ticks - lastHitTick > 20 * 30) {
            skipped.add(prey.getId());
            bot.event("can't get at that " + prey.getName().getString() + "; trying another");
            prey = null;
            return Status.RUNNING;
        }
        FightTask.engage(bot, prey);
        if (ticks % 400 == 0 && prey.distanceTo(bot.player()) > 24) {
            skipped.add(prey.getId()); // keeps running off; pick another
            prey = null;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        lol.zym.zymbot.nav.Nav.stop();
    }
}
