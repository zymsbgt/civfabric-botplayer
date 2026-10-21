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
 * pacifist-fabric on the server: players start as pacifists, and hostiles that arrived on their
 * own (natural spawns, chunk generation, patrols, reinforcements) neither target nor hurt them;
 * structure, spawner and conversion spawns do (see {@link lol.zym.zymbot.Bot#huntedBy}). Hitting a
 * hostile turns its species (zombie and skeleton kin count as one) against you for 20 s, killing
 * one for 30 minutes, or 20 s if it dies within 15 s of the first swing. Some mobs never make peace,
 * and nothing does during a blood moon or in a village at night (the server's version 1.1.2).
 * We learn the state from {@code /pacifist status} and the server's alerts, and keep our own
 * estimate of grudges from what we hit.
 */
public final class Pacifism {
    public enum State { UNKNOWN, ON, OFF, ABSENT }

    private static final Set<EntityType<?>> ZOMBIES = Set.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER);
    private static final Set<EntityType<?>> SKELETONS = Set.of(EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED);
    private static final long HIT_MS = 20_000, KILL_MS = 30 * 60_000L, GRACE_MS = 15_000;
    private static final Pattern ALERT = Pattern.compile("You (hit|killed) an? (.+?)! (?:(.+?) are now hostile|They're hostile) to you for (\\d+) ?(s|minutes)");
    private static final Pattern PEACE = Pattern.compile("You're at peace with (.+?) again");
    private static final Pattern MOON = Pattern.compile("During an? (Super )?Blood Moon:");

    private State state = State.UNKNOWN;
    private final Map<EntityType<?>, Long> grudges = new HashMap<>();
    /** pacifist-fabric's engagement grace: a kill within 15 s of the first swing from peace costs a hit's grudge. */
    private final Map<EntityType<?>, Long> graceUntil = new HashMap<>();
    private boolean bloodMoon, superMoon, inVillage;
    /** When we last heard the moon was up: the clock can lag the announcement by a moment. */
    private long moonSeenAt;
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
        // Enhanced Celestials' own line, or CivFabric's "During a Blood Moon:" notice, which also goes out
        // when a moon rained out at dusk comes back once the sky clears, and to anyone joining under one
        Matcher moon = MOON.matcher(text);
        if (text.contains("Blood Moon\" rises") || moon.find()) {
            bloodMoon = true;
            superMoon = text.contains("Super Blood Moon");
            moonSeenAt = System.currentTimeMillis();
            return false; // others may want the moon too
        }
        if (text.contains("Blood Moon\" sets")) {
            bloodMoon = superMoon = false;
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
            moonSeenAt = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
        // a blood moon leaves no grudges behind
        if (bloodMoon()) return;
        if (!killed && !grudged(e.getType())) for (EntityType<?> t : kin(e.getType())) graceUntil.put(t, now + GRACE_MS);
        boolean forgiven = graceUntil.getOrDefault(e.getType(), 0L) > now;
        long until = now + (killed && !forgiven ? KILL_MS : HIT_MS);
        for (EntityType<?> t : kin(e.getType())) grudges.merge(t, until, Math::max);
    }

    /** Pacifism keeps this mob off us, so leave it alone (and hitting it would cost us). */
    public boolean atPeaceWith(Entity e) {
        return protecting() && e instanceof Monster && !alwaysHostile(e) && !grudged(e.getType())
                // CivFabric's stormy night: drowned hunt pacifists from 13000 to 23000 in a thunderstorm
                && !(e instanceof Drowned && stormNight())
                // a spawner's mobs aren't natural spawns, and pacifism only covers those
                && !lol.zym.zymbot.world.Spawners.near(e.blockPosition(), SPAWNED_RANGE);
    }

    /** How far from a spawner a mob is taken for one of its own. Spawners place mobs within 4 blocks; they wander. */
    public static final int SPAWNED_RANGE = 16;

    /** CivFabric's StormNight test: thunder between 13000 and 23000. */
    public static boolean stormNight() {
        var l = lol.zym.zymbot.world.WorldView.level();
        if (l == null) return false;
        long t = l.getDayTime() % 24000L;
        return l.isThundering() && t >= 13000 && t < 23000;
    }

    /** Fighting this one would anger its whole kin (it isn't already hostile to us). */
    public boolean fightingCosts(Entity e) {
        return atPeaceWith(e);
    }

    /**
     * A blood moon is up: nothing is at peace with anyone until dawn. Rain calls it off while it
     * lasts (pacifist-fabric asks Enhanced Celestials what is happening, not what was scheduled),
     * and it's back when the sky clears.
     */
    public boolean bloodMoon() {
        protecting(); // drops the moon once it's day
        return bloodMoon && !rainedOut();
    }

    private static boolean rainedOut() {
        var l = lol.zym.zymbot.world.WorldView.level();
        return l != null && l.isRaining();
    }

    /**
     * A Super Blood Moon: on top of the blood moon's hostility, mobs hit harder, take more killing and
     * run faster than we do. Neither fighting nor running works; only being underground does.
     */
    public boolean superBloodMoon() {
        return bloodMoon() && superMoon;
    }

    /** Pacifism is on and in force: not suspended by a blood moon or a village at night. */
    public boolean protecting() {
        // it's day: the moon has set (but not on a clock that hasn't caught up with the announcement yet)
        if (bloodMoon && lol.zym.zymbot.world.WorldView.level() != null && lol.zym.zymbot.world.WorldView.level().getDayTime() % 24000 < 12000
                && System.currentTimeMillis() - moonSeenAt > 60_000) bloodMoon = superMoon = false;
        return state == State.ON && !(bloodMoon && !rainedOut()) && !inVillage;
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
        if (state == State.ON && !protecting()) sb.append(" (suspended: ").append(bloodMoon() ? (superMoon ? "super blood moon" : "blood moon") : "village at night").append(")");
        grudges.forEach((t, until) -> {
            if (until > now) sb.append(", ").append(BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath()).append(" hostile ").append((until - now) / 1000).append("s");
        });
        return sb.toString();
    }
}
