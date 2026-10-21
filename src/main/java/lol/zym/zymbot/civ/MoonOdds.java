package lol.zym.zymbot.civ;

import java.util.*;

/**
 * The odds behind CivFabric's {@code /lunarforecast}, so several readings of one night can be
 * pooled. The numbers are the server's ({@code SpecConfig}, CivFabric 0.4.4): a reader of rank r
 * names the real moon with chance SENSITIVITY[r], and otherwise names a moon that could have risen
 * (weighted by how often each does) with chance NOISE[r], else says nothing. Readings are seeded per
 * player and night, so different readers' readings of a night are independent given the sky, and a
 * product of likelihoods is the whole story. The confidence the server prints is this same
 * posterior for one reading, which is how a quoted figure gives away the reader's rank.
 */
public final class MoonOdds {
    private MoonOdds() {}

    static final double[] SENSITIVITY = {0.40, 0.50, 0.60, 0.70, 0.80, 0.90};
    static final double[] NOISE = {0.10, 0.08, 0.06, 0.05, 0.04, 0.02};
    private static final double ORDINARY_FULL = 0.0491, SUPER_FULL = 0.0267, ORDINARY_OTHER = 0.0522;
    static final List<String> ORDINARY = List.of("Blood Moon", "Blue Moon", "Harvest Moon");
    static final List<String> SUPERS = List.of("Super Blood Moon", "Super Blue Moon", "Super Harvest Moon", "Super Moon");
    /** {@code /guessforecast}'s four answers. */
    public static final List<String> GUESSES = List.of("blood_moon", "blue_moon", "harvest_moon", "none");
    /** Stands for a quiet night among the outcomes. */
    static final String QUIET = "";

    public static int phase(long night) {
        return (int) Math.floorMod(night, 8L);
    }

    /** Supers only rise on a full moon (phase 0). */
    public static List<String> legal(long night) {
        List<String> l = new ArrayList<>(ORDINARY);
        if (phase(night) == 0) l.addAll(SUPERS);
        return l;
    }

    /** A reading could name this moon on this night: misreads only ever name a moon that could rise. */
    public static boolean plausible(String moon, long night) {
        return moon == null || legal(night).contains(moon);
    }

    /** How often each moon (or a quiet night) is scheduled. */
    private static Map<String, Double> prior(long night) {
        Map<String, Double> p = new LinkedHashMap<>();
        double ordinary = phase(night) == 0 ? ORDINARY_FULL : ORDINARY_OTHER, mass = 0;
        for (String m : legal(night)) {
            double v = SUPERS.contains(m) ? SUPER_FULL : ordinary;
            p.put(m, v);
            mass += v;
        }
        p.put(QUIET, 1 - mass);
        return p;
    }

    /** The weight a misread gives a moon (the server's full-moon rates, whatever the phase). */
    private static double pickWeight(String moon) {
        return SUPERS.contains(moon) ? SUPER_FULL : ORDINARY_FULL;
    }

    /** P(this reading | this sky) for a reader of this rank. {@code named} null is silence. */
    private static double likelihood(String named, String truth, int rank, long night) {
        double s = SENSITIVITY[clamp(rank)], e = NOISE[clamp(rank)];
        List<String> legal = legal(night);
        if (named != null && !legal.contains(named)) return 0;
        if (QUIET.equals(truth)) {
            if (named == null) return 1 - e;
            double total = 0;
            for (String m : legal) total += pickWeight(m);
            return e * pickWeight(named) / total;
        }
        if (named == null) return (1 - s) * (1 - e);
        if (named.equals(truth)) return s;
        double total = 0;
        for (String m : legal) if (!m.equals(truth)) total += pickWeight(m);
        return (1 - s) * e * pickWeight(named) / total;
    }

    /** One reading: who read it, at what rank, and what it named (null for silence). */
    public record Read(String moon, int rank) {}

    /** P(each of the four guesses) given all these readings of one night. */
    public static Map<String, Double> pool(Collection<Read> reads, long night) {
        Map<String, Double> post = new LinkedHashMap<>();
        double total = 0;
        for (var e : prior(night).entrySet()) {
            double p = e.getValue();
            for (Read r : reads) p *= likelihood(r.moon(), e.getKey(), r.rank(), night);
            post.merge(category(e.getKey()), p, Double::sum);
            total += p;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String g : GUESSES) out.put(g, total > 0 ? post.getOrDefault(g, 0.0) / total : 0.0);
        return out;
    }

    /** The confidence the server prints for this reading at this rank: the posterior of what it says. */
    public static double confidence(String named, int rank, long night) {
        double num = 0, total = 0;
        for (var e : prior(night).entrySet()) {
            double p = e.getValue() * likelihood(named, e.getKey(), rank, night);
            total += p;
            if (named == null ? QUIET.equals(e.getKey()) : named.equals(e.getKey())) num += p;
        }
        return total > 0 ? num / total : 0;
    }

    /** The rank whose printed confidence matches this figure (to the 0.1% it's printed at), or -1. */
    public static int rankFor(String named, double confidence, long night) {
        for (int r = 0; r < SENSITIVITY.length; r++)
            if (Math.abs(confidence(named, r, night) - confidence) < 0.0006) return r;
        return -1;
    }

    /**
     * Which of the four guesses a moon falls under: a super blood moon is a blood moon, and a plain
     * super moon (or silence, or a quiet night) is {@code none}.
     */
    public static String category(String moon) {
        if (moon == null || moon.isEmpty()) return "none";
        String name = moon.toLowerCase(Locale.ROOT).strip();
        if (name.startsWith("super ")) name = name.substring(6);
        name = name.replace(' ', '_');
        return GUESSES.contains(name) ? name : "none";
    }

    /** "super_blood_moon", "Blood Moon", "none"... as the server prints it, or null for none/silence. Throws if unknown. */
    public static String moonNamed(String written) {
        String w = written.toLowerCase(Locale.ROOT).replace('_', ' ').strip();
        if (w.equals("none") || w.equals("nothing") || w.equals("silent") || w.equals("quiet")) return null;
        for (String m : ORDINARY) if (m.equalsIgnoreCase(w)) return m;
        for (String m : SUPERS) if (m.equalsIgnoreCase(w)) return m;
        throw new IllegalArgumentException("no moon called " + written);
    }

    private static int clamp(int rank) {
        return Math.max(0, Math.min(SENSITIVITY.length - 1, rank));
    }
}
