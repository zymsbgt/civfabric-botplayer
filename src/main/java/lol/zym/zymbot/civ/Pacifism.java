package lol.zym.zymbot.civ;

import lol.zym.zymbot.Bot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pacifist-fabric on the server: players start as pacifists, and naturally spawned hostiles
 * neither target nor hurt them. Hitting a hostile turns its species (zombie and skeleton kin
 * count as one) against you for 20 s, killing one for 30 minutes. Some mobs never make peace,
 * and nothing does during a blood moon or in a village at night (the server's version 1.1.2).
 * We learn the state from {@code /pacifist status} and the server's alerts, and keep our own
 * estimate of grudges from what we hit.
 */
public final class Pacifism {
    public enum State { UNKNOWN, ON, OFF, ABSENT }

    private static final Set<EntityType<?>> ZOMBIES = Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER);
    private static final Set<EntityType<?>> SKELETONS = Set.of(EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED);
    private static final long HIT_MS = 20_000, KILL_MS = 30 * 60_000L;
    private static final Pattern ALERT = Pattern.compile("You (hit|killed) an? (.+?)! (?:(.+?) are now hostile|They're hostile) to you for (\\d+) ?(s|minutes)");
    private static final Pattern PEACE = Pattern.compile("You're at peace with (.+?) again");

    private State state = State.UNKNOWN;
    private final Map<EntityType<?>, Long> grudges = new HashMap<>();
    private boolean bloodMoon, inVillage;
    private long askedAt = -1;
    private int asks;

    public State state() {
        return state;
    }

    private long askedNight = -1;

    /** Ask the server once we're settled in; a missing command means the mod isn't there. */
    public void tick(Bot bot) {
        long t = bot.ticks();
        // and again each nightfall: a blood moon announced before we joined, or missed, still counts
        long day = bot.level().getDayTime();
        if (state == State.ON && day % 24000 >= 12600 && day % 24000 < 13600 && askedNight != day / 24000 && t > 200) {
            askedNight = day / 24000;
            bot.player().connection.sendCommand("pacifist status");
        }
        if (state == State.UNKNOWN && t > 100 && asks < 3 && (askedAt < 0 || t - askedAt > 1200)) {
            askedAt = t;
            asks++;
            bot.player().connection.sendCommand("pacifist status");
        }
        if (state == State.UNKNOWN && askedAt >= 0 && t - askedAt == 60 && bot.heard("pacifist<--[HERE]", askedAt)) state = State.ABSENT;
    }

    /** Learn from a server message; true if it was about pacifism. */
    public boolean onMessage(String text) {
        if (text.contains("Blood Moon\" rises")) {
            bloodMoon = true;
            return false; // others may want the moon too
        }
        if (text.contains("Blood Moon\" sets")) {
            bloodMoon = false;
            return false;
        }
        if (text.contains("You're entering a village. Monsters")) {
            inVillage = true;
            return true;
        }
        if (text.contains("You're leaving a village") || text.contains("The village is quiet again")) {
            inVillage = false;
            return true;
        }
        if (text.contains("but a blood moon is up") || text.contains("while a blood moon is up")) {
            bloodMoon = true;
            if (text.contains("currently a Pacifist")) state = State.ON;
            return true;
        }
        if (text.contains("You are currently a Pacifist") || text.contains("Your pacifist status has been re-enabled")
                || text.contains("Your pacifist status has been restored") || text.contains("pacifist status was restored")
                || text.contains("You are already a pacifist")) {
            if (text.contains("currently a Pacifist")) grudges.clear();
            state = State.ON;
            return true;
        }
        if (text.contains("These species are currently hostile to you")) {
            state = State.ON;
            for (String line : text.split("\n")) {
                String l = line.strip();
                if (!l.startsWith("- ")) continue;
                int c = l.indexOf(':');
                grudge(l.substring(2, c < 0 ? l.length() : c).strip(), KILL_MS);
            }
            return true;
        }
        if (text.contains("Your pacifist status is disabled") || text.contains("You have declared war") || text.contains("You're already not a pacifist")) {
            state = State.OFF;
            return true;
        }
        Matcher m = ALERT.matcher(text);
        if (m.find()) {
            long ms = Long.parseLong(m.group(4)) * (m.group(5).equals("s") ? 1000 : 60_000);
            grudge(m.group(3) != null ? m.group(3) : m.group(2), ms);
            return true;
        }
        m = PEACE.matcher(text);
        if (m.find()) {
            for (EntityType<?> t : typesNamed(m.group(1))) grudges.remove(t);
            return true;
        }
        return false;
    }

    /** We hit (or killed) this mob: its kin turn on us, whether or not the server tells us. */
    public void struck(Entity e, boolean killed) {
        if (!(e instanceof Monster) || alwaysHostile(e)) return;
        long until = System.currentTimeMillis() + (killed ? KILL_MS : HIT_MS);
        for (EntityType<?> t : kin(e.getType())) grudges.merge(t, until, Math::max);
    }

    /** Pacifism keeps this mob off us, so leave it alone (and hitting it would cost us). */
    public boolean atPeaceWith(Entity e) {
        return protecting() && e instanceof Monster && !alwaysHostile(e) && !grudged(e.getType());
    }

    /** Fighting this one would anger its whole kin (it isn't already hostile to us). */
    public boolean fightingCosts(Entity e) {
        return atPeaceWith(e);
    }

    /** A blood moon is up: nothing is at peace with anyone until dawn. */
    public boolean bloodMoon() {
        protecting(); // drops the moon once it's day
        return bloodMoon;
    }

    /** Pacifism is on and in force: not suspended by a blood moon or a village at night. */
    public boolean protecting() {
        if (bloodMoon && lol.zym.zymbot.world.WorldView.level().getDayTime() % 24000 < 12000) bloodMoon = false; // it's day: the moon has set
        return state == State.ON && !bloodMoon && !inVillage;
    }

    public boolean grudged(EntityType<?> t) {
        Long until = grudges.get(t);
        return until != null && until > System.currentTimeMillis();
    }

    /** Mobs that stay hostile whatever your status (pacifist-fabric's AlwaysHostileMobs). */
    public static boolean alwaysHostile(Entity e) {
        return e instanceof PiglinBrute || e instanceof Guardian || e instanceof Warden || e instanceof AbstractIllager
                || e instanceof Ravager || e instanceof Blaze || e instanceof WitherSkeleton;
    }

    private void grudge(String name, long ms) {
        long until = System.currentTimeMillis() + ms;
        for (EntityType<?> t : typesNamed(name)) grudges.merge(t, until, Math::max);
    }

    private static Set<EntityType<?>> kin(EntityType<?> t) {
        if (ZOMBIES.contains(t)) return ZOMBIES;
        if (SKELETONS.contains(t)) return SKELETONS;
        return Set.of(t);
    }

    private static Set<EntityType<?>> typesNamed(String name) {
        String n = name.strip();
        if (n.equalsIgnoreCase("Zombies")) return ZOMBIES;
        if (n.equalsIgnoreCase("Skeletons")) return SKELETONS;
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            if (t.getDescription().getString().equalsIgnoreCase(n)) return kin(t);
        }
        return Set.of();
    }

    public String describe() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(state.name().toLowerCase(Locale.ROOT));
        if (state == State.ON && !protecting()) sb.append(" (suspended: ").append(bloodMoon ? "blood moon" : "village at night").append(")");
        grudges.forEach((t, until) -> {
            if (until > now) sb.append(", ").append(BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath()).append(" hostile ").append((until - now) / 1000).append("s");
        });
        return sb.toString();
    }
}
