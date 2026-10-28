package lol.zym.zymbot.brain;

import com.google.gson.JsonElement;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.civ.ClassBook;
import lol.zym.zymbot.civ.ClassSheet;
import lol.zym.zymbot.civ.CivRules;

import java.util.*;

/**
 * Which CivFabric classes this bot is working towards. There is no class to "pick" on the
 * server: specialisations are just a player's top N XP classes, so committing to a set means
 * earning XP only in it. Roles split the classes across a team so that between them they cover
 * every trade, and nobody holds the forbidden Farmer + Miner + Blacksmith trio.
 *
 * <p>Roles are claimed in order, so the first bot on a world is the Grower (the farmer): food
 * comes first. Each bot publishes its role on the hub and takes the first one no teammate in
 * radio range holds; ties between bots that joined together go to the name that sorts first.
 */
public final class ClassPlan {
    public record Role(String name, List<String> three, List<String> two) {
        public List<String> classes(int slots) {
            List<String> base = slots <= 2 ? two : three;
            return base.subList(0, Math.min(base.size(), Math.max(1, slots)));
        }
    }

    public static final List<Role> ROLES = List.of(
            new Role("grower", List.of("farmer", "medic", "hunter"), List.of("farmer", "medic")),
            // the Smith stays home, so all three level at the base: Blacksmith smelts and crafts, Builder
            // smelts stone, places bricks and planks and reinforces walls, Librarian crafts books and signs
            // and later blesses gear. Ore, stone, logs and leather come in from teammates and boards.
            // Builder is the only Builder on two slots, and boats need Builder 5 and Librarian 5 together.
            // Farmer would also level at home but takes crops from the Grower; Miner and Hunter mean going out
            new Role("smith", List.of("blacksmith", "builder", "librarian"), List.of("blacksmith", "builder")),
            new Role("delver", List.of("miner", "hunter", "builder"), List.of("miner", "hunter")),
            new Role("scholar", List.of("librarian", "medic", "farmer"), List.of("librarian", "medic")));

    /** Only one of these may be among a player's specialisations at a time... all three is refused. */
    private static final Set<String> TRIO = Set.of("farmer", "miner", "blacksmith");

    private ClassPlan() {}

    public static Role role(String name) {
        for (Role r : ROLES) if (r.name().equalsIgnoreCase(name)) return r;
        return null;
    }

    /** Medic is in our class plan: stitches and kits are ours to use on others. */
    public static boolean isMedic(Bot bot) {
        return classes(bot).contains("medic");
    }

    /** The classes we're levelling, best first. Empty only before a role is chosen. */
    public static List<String> classes(Bot bot) {
        List<String> pinned = BotConfig.get().classes;
        if (!pinned.isEmpty()) return pinned;
        return bot.memory().classPlan;
    }

    public static boolean inPlan(Bot bot, String clazz) {
        return classes(bot).contains(clazz);
    }

    public static boolean isGrower(Bot bot) {
        return inPlan(bot, "farmer") && classes(bot).indexOf("farmer") == 0;
    }

    /** Is the set legal: no forbidden trio, no more than the slots, real class names. */
    public static String check(List<String> classes, int slots) {
        if (classes.size() > slots) return "this server gives " + slots + " slots";
        for (String c : classes) if (!CivRules.CLASSES.contains(c)) return "no class called " + c;
        if (classes.containsAll(TRIO)) return "Farmer, Miner and Blacksmith can't all be specialisations";
        return null;
    }

    /** Set our classes by hand ("classes farmer medic hunter"). */
    public static String set(Bot bot, List<String> classes) {
        String bad = check(classes, ClassSheet.slots(bot));
        if (bad != null) return bad;
        bot.memory().classRole = "custom";
        bot.memory().classPlan = new ArrayList<>(classes);
        bot.memory().save();
        // a pin in the config wins over the plan: move the pin too, or the change says done and does
        // nothing (Wandersong, pinned hunter+miner, was told medic+hunter and carried on as before)
        if (!BotConfig.get().classes.isEmpty()) {
            BotConfig.get().classes = new ArrayList<>(classes);
            BotConfig.save();
        }
        return "classes now " + String.join(", ", classes);
    }

    /**
     * Choose a role if we have none, and give it up if a teammate that sorts first took the same
     * one before either of us earned anything in it. Called every few seconds; cheap.
     */
    public static void reconcile(Bot bot) {
        learnTeamHome(bot);
        if (!BotConfig.get().classes.isEmpty() || !bot.civ().present()) return;
        var mem = bot.memory();
        int slots = ClassSheet.slots(bot);
        Map<String, String> taken = new HashMap<>(); // role -> who
        for (var m : bot.team().inRange()) {
            JsonElement r = m.state().get("class_role");
            if (r != null && r.isJsonPrimitive()) taken.merge(r.getAsString(), m.name(), (a, b) -> a.compareToIgnoreCase(b) < 0 ? a : b);
        }
        if (mem.classRole != null && !mem.classRole.equals("custom")) {
            Role mine = role(mem.classRole);
            // slot count learnt or changed: trim or grow the plan to fit
            if (mine != null && !mine.classes(slots).equals(mem.classPlan)) {
                mem.classPlan = new ArrayList<>(mine.classes(slots));
                mem.save();
            }
            String rival = taken.get(mem.classRole);
            if (rival == null || rival.compareToIgnoreCase(bot.name()) > 0 || committed(bot)) return;
            // every role is held already: choosing again lands on this one, every few seconds
            if (ROLES.stream().allMatch(r -> taken.containsKey(r.name()))) return;
            bot.event("teammate " + rival + " is also " + mem.classRole + "; choosing again");
            mem.classRole = null;
        }
        if (mem.classRole != null) return;
        Role pick = fromXp(bot, slots, taken.keySet());
        if (pick == null) {
            for (Role r : ROLES) if (!taken.containsKey(r.name())) { pick = r; break; }
        }
        if (pick == null) pick = ROLES.get(0); // more bots than roles: more farmers never hurts
        mem.classRole = pick.name();
        mem.classPlan = new ArrayList<>(pick.classes(slots));
        mem.save();
        bot.event("taking the " + pick.name() + " role: " + String.join(", ", mem.classPlan));
    }

    /** XP already earned decides it: a bot that's mostly cooked is a Grower whatever's free. */
    private static Role fromXp(Bot bot, int slots, Set<String> taken) {
        var xp = bot.memory().classXp;
        if (xp.values().stream().noneMatch(v -> v >= 200)) return null;
        Role best = null;
        double bestScore = 0;
        for (Role r : ROLES) {
            double s = 0;
            for (String c : r.classes(slots)) s += xp.getOrDefault(c, 0.0);
            if (taken.contains(r.name())) s *= 0.5;
            if (s > bestScore) {
                bestScore = s;
                best = r;
            }
        }
        return best;
    }

    /** XP in the role is real enough that switching would waste it. */
    private static boolean committed(Bot bot) {
        for (String c : bot.memory().classPlan) if (bot.memory().classXp.getOrDefault(c, 0.0) >= 200) return true;
        return bot.memory().drainArmed;
    }

    /**
     * The base this bot belongs to, or null. Bases are set by a leader (or Claude) with
     * {@code teamhome set}; the one exception is a brand-new team, where the first Grower's farm
     * becomes "main" so there's somewhere to gather before anyone has decided anything.
     */
    public static net.minecraft.core.BlockPos teamHome(Bot bot) {
        var m = bot.memory();
        if (m.teamHomes.isEmpty() && isGrower(bot) && m.home != null && m.home.dim.equals(bot.dim())) {
            // bootstrap: our farm is the team's first base
            var h = new lol.zym.zymbot.memory.BotMemory.TeamHome();
            // a copy: the base moves only when someone moves it, never along with our home
            h.at = new lol.zym.zymbot.memory.BotMemory.Pos(m.home.pos(), m.home.dim);
            h.purpose = "first farm";
            h.set = bot.level().getGameTime();
            m.teamHomes.put("main", h);
            m.homeName = "main";
            m.save();
            bot.event("our farm at " + m.home.pos().toShortString() + " is the team's first base, \"main\"");
        }
        var h = m.homeName == null ? null : m.teamHomes.get(m.homeName);
        if (h == null && !m.teamHomes.isEmpty()) h = m.teamHomes.values().iterator().next();
        return h != null && h.at.dim.equals(bot.dim()) ? h.at.pos() : null;
    }

    /**
     * Bases from teammates' status lines. A bot with none takes all of them (a Grower founds its own
     * instead); after that, a base a teammate has set or moved later than our record is taken, so a
     * leader's {@code teamhome set} and the Grower moving the first farm reach the whole team.
     */
    private static void learnTeamHome(Bot bot) {
        var mem = bot.memory();
        if (mem.teamHomes.isEmpty() && isGrower(bot)) return;
        boolean first = mem.teamHomes.isEmpty(), changed = false;
        for (var m : bot.team().inRange()) {
            var homes = m.state().get("team_homes");
            if (homes == null || !homes.isJsonObject()) continue;
            for (var e : homes.getAsJsonObject().entrySet()) {
                var o = e.getValue().getAsJsonObject();
                long set = o.has("set") ? o.get("set").getAsLong() : 0;
                var ours = mem.teamHomes.get(e.getKey());
                if (!first && (ours == null ? set == 0 : set <= ours.set)) continue;
                var h = new lol.zym.zymbot.memory.BotMemory.TeamHome();
                h.at = new lol.zym.zymbot.memory.BotMemory.Pos(new net.minecraft.core.BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(),
                        o.get("z").getAsInt()), bot.dim());
                h.purpose = o.has("purpose") ? o.get("purpose").getAsString() : ours != null ? ours.purpose : null;
                h.set = set;
                mem.teamHomes.put(e.getKey(), h);
                changed = true;
                if (!first && (ours == null || !ours.at.pos().equals(h.at.pos())))
                    bot.event("base \"" + e.getKey() + "\" is at " + h.at.pos().toShortString() + " now (from " + m.name() + ")");
            }
            if (first && changed) {
                mem.homeName = mem.teamHomes.containsKey("main") ? "main" : mem.teamHomes.keySet().iterator().next();
                bot.event("joined " + m.name() + "'s base \"" + mem.homeName + "\" at " + mem.teamHomes.get(mem.homeName).at.pos().toShortString());
                first = false;
            }
        }
        if (changed) mem.save();
    }

    /** Leaders, and Claude driving a bot directly, may set bases; nobody else. */
    public static boolean mayDirect(Bot bot, String from) {
        if (from == null || from.equalsIgnoreCase(bot.name())) return true;
        var m = bot.team().member(from);
        return m.isPresent() && m.get().state().has("role") && "leader".equals(m.get().state().get("role").getAsString());
    }

    /** The planned class to level next: the lowest rank, then the earliest in the plan. */
    public static String nextToLevel(Bot bot) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String c : classes(bot)) {
            int r = ClassBook.rankOf(Math.max(0, ClassSheet.xp(bot, c)));
            if (r < bestRank) {
                bestRank = r;
                best = c;
            }
        }
        return best;
    }

    public static String describe(Bot bot) {
        List<String> cls = classes(bot);
        if (cls.isEmpty()) return "no classes chosen yet";
        StringBuilder sb = new StringBuilder(bot.memory().classRole == null ? "pinned" : bot.memory().classRole);
        sb.append(" (").append(ClassSheet.slots(bot)).append(" slots").append(bot.memory().drainArmed ? ", drain armed" : "").append("): ");
        List<String> parts = new ArrayList<>();
        for (String c : cls) {
            double x = ClassSheet.xp(bot, c);
            parts.add(c + (x < 0 ? " ?" : " " + CivRules.RANKS.get(ClassBook.rankOf(x)) + " " + Math.round(x)));
        }
        sb.append(String.join(", ", parts));
        double off = 0;
        for (var e : bot.memory().classXp.entrySet()) if (!cls.contains(e.getKey())) off += e.getValue();
        if (off > 0) sb.append("; off-class ").append(Math.round(off));
        return sb.toString();
    }
}
