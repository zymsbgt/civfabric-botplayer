package lol.zym.zymbot.civ;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.ClassPlan;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CivFabric's moon forecast (with Enhanced Celestials on the server). {@code /lunarforecast} names
 * the coming night's moon with a confidence, or senses nothing; the reading is fixed per player and
 * night, so once a day is enough. {@code /guessforecast <class> <moon>} is refused until that night
 * has been read, can be changed until dusk (12380), and is settled at sunset: a right guess pays
 * class XP, a wrong one costs nothing. We guess what the reading names (or {@code none} when it's
 * silent) and put the XP on the class we're levelling. A blood moon reading also lets the brain get
 * us home before dusk ({@link #braceTime}).
 */
public final class Forecast {
    /** Read and guess by day, well before CivFabric's dusk cutoff, when the question rolls to tomorrow. */
    private static final long FROM = 1000, UNTIL = 11000;
    /** From here until the moon is well up, a blood moon reading sends us home. */
    private static final long BRACE_FROM = 10800, BRACE_UNTIL = 13000;
    private static final long RETRY = 1200;
    private static final int TRIES = 3;
    private static final List<String> MOONS = List.of("blood_moon", "blue_moon", "harvest_moon");
    /** "You sense a Blood Moon rising tonight (40.1% confidence)", or "You don't sense a special moon rising...". */
    private static final Pattern READING = Pattern.compile("You (?:sense an? (.+?)|don't sense a special moon) rising (tonight|tomorrow night) \\(([\\d.,]+)% confidence");
    private static final Pattern NAMED = Pattern.compile("(?:You name|change your mind and name|You have already named) (?:the (.+?)|a quiet night) for (tonight|tomorrow night)");
    private static final Pattern PAYS = Pattern.compile("(?:you earn|You'll get) ([\\d.,]+) (\\w+) XP");
    private static final Pattern CALLED = Pattern.compile("You called it: .*?\\+([\\d.,]+) (?:(\\w+) )?XP");
    private static final Pattern TRY = Pattern.compile("No such class\\. Try (.+)\\.");

    private boolean absent;
    private long readAskedAt = -1, guessAskedAt = -1;
    private boolean readChecked;
    private int readTries, guessTries;
    private long triesDay = -1;
    /** Classes the server wouldn't take a guess on today (switched off there). */
    private final Set<String> refused = new HashSet<>();
    private String asked;

    public void tick(Bot bot) {
        if (absent || !bot.civ().present() || bot.ticks() < 400) return;
        long t = bot.ticks(), time = bot.level().getDayTime(), day = time / 24000, clock = time % 24000;
        // a missing command means Enhanced Celestials (or CivFabric's bridge to it) isn't on this server
        if (readAskedAt >= 0 && !readChecked && t - readAskedAt >= 60) {
            readChecked = true;
            if (bot.heard("lunarforecast<--[HERE]", readAskedAt)) {
                absent = true;
                bot.event("forecast: no /lunarforecast on this server");
                return;
            }
        }
        if (day != triesDay) {
            triesDay = day;
            readTries = guessTries = 0;
            refused.clear();
        }
        if (clock < FROM || clock >= UNTIL) return;
        var mem = bot.memory();
        if (mem.forecastNight != day) {
            if (readTries < TRIES && (readAskedAt < 0 || t - readAskedAt > RETRY)) {
                readTries++;
                readAskedAt = t;
                readChecked = false;
                bot.player().connection.sendCommand("lunarforecast");
            }
            return;
        }
        if (mem.guessNight == day || guessTries >= TRIES || readAskedAt >= 0 && t - readAskedAt < 40
                || guessAskedAt >= 0 && t - guessAskedAt <= RETRY) return;
        asked = guessClass(bot);
        if (asked == null) return;
        guessTries++;
        guessAskedAt = t;
        bot.player().connection.sendCommand("guessforecast " + asked + " " + category(mem.forecastMoon));
    }

    /** The class to put a guess's XP on: the one we'd level next, else the lowest in the plan. */
    private String guessClass(Bot bot) {
        String next = ClassPlan.nextToLevel(bot);
        if (next != null && !refused.contains(next)) return next;
        return ClassPlan.classes(bot).stream().filter(c -> !refused.contains(c))
                .min(Comparator.comparingDouble(c -> Math.max(0, ClassSheet.xp(bot, c)))).orElse(null);
    }

    /** Learn from a server message; true if it was about the forecast. */
    public boolean onMessage(Bot bot, String text) {
        var mem = bot.memory();
        long day = bot.level().getDayTime() / 24000;
        Matcher m = READING.matcher(text);
        if (m.find()) {
            mem.forecastMoon = m.group(1);
            mem.forecastNight = m.group(2).equals("tonight") ? day : day + 1;
            mem.forecastConfidence = number(m.group(3)) / 100;
            mem.save();
            boolean blood = "blood_moon".equals(category(mem.forecastMoon));
            bot.event("forecast: " + (mem.forecastMoon == null ? "no special moon" : mem.forecastMoon) + " " + m.group(2)
                    + " (" + m.group(3) + "%)" + (blood ? "; home with the bed claimed before dusk" : ""));
            // the claim is what wakes us at home if the night goes wrong: make sure we still hold it
            if (blood && mem.forecastNight == day) bot.checkBed();
            return true;
        }
        if (text.contains("The sky refuses to be read")) {
            bot.event("forecast: the sky refuses to be read here");
            return true;
        }
        if (text.contains("before you guess: use /lunarforecast")) {
            // the server has no reading of ours for the night: read again
            mem.forecastNight = -1;
            bot.event("forecast: the guess wants a fresh reading");
            return true;
        }
        m = TRY.matcher(text);
        if (m.find() && asked != null) {
            refused.add(asked);
            guessAskedAt = bot.ticks() - RETRY + 40; // try the next class in a moment
            bot.event("forecast: can't guess on " + asked + " here (the server takes " + m.group(1) + ")");
            return true;
        }
        m = NAMED.matcher(text);
        if (m.find()) {
            mem.guessNight = m.group(2).equals("tonight") ? day : day + 1;
            mem.guessMoon = m.group(1) == null ? "none" : category(m.group(1));
            Matcher p = PAYS.matcher(text);
            if (p.find()) {
                mem.guessReward = number(p.group(1));
                mem.guessClass = p.group(2).toLowerCase(Locale.ROOT);
            }
            mem.guessResult = null;
            mem.save();
            if (!text.contains("already named"))
                bot.event("forecast: guessed " + mem.guessMoon + " for " + m.group(2) + ", " + mem.guessReward + " " + mem.guessClass + " XP if right");
            return true;
        }
        if (text.contains("waiting for the rain to clear. You can name")) {
            bot.event("forecast: last night's guess is held for the rain; guessing later");
            return true;
        }
        m = CALLED.matcher(text);
        if (m.find()) {
            mem.guessResult = plain(text);
            mem.save();
            bot.event("forecast: " + mem.guessResult);
            bot.classSheet().refresh(bot);
            return true;
        }
        if (text.contains("you named did not rise") || text.contains("you named did not come") || text.contains("you named was not due")) {
            mem.guessResult = plain(text);
            mem.save();
            bot.event("forecast: wrong, " + mem.guessResult);
            return true;
        }
        return false;
    }

    /** Tonight's reading names a blood moon (super or not). */
    public static boolean bloodTonight(Bot bot) {
        var mem = bot.memory();
        return mem.forecastNight == bot.level().getDayTime() / 24000 && "blood_moon".equals(category(mem.forecastMoon));
    }

    /** A blood moon is forecast and dusk is near: be home, by the bed, before it rises. */
    public static boolean braceTime(Bot bot) {
        long clock = bot.level().getDayTime() % 24000;
        return clock >= BRACE_FROM && clock < BRACE_UNTIL && bloodTonight(bot);
    }

    /**
     * Which of {@code /guessforecast}'s four a moon falls under: a super blood moon is a blood moon,
     * and a plain super moon (or silence) is {@code none}.
     */
    public static String category(String moon) {
        if (moon == null) return "none";
        String name = moon.toLowerCase(Locale.ROOT).strip();
        if (name.startsWith("super ")) name = name.substring(6);
        name = name.replace(' ', '_');
        return MOONS.contains(name) ? name : "none";
    }

    public static String describe(Bot bot) {
        var mem = bot.memory();
        long day = bot.level().getDayTime() / 24000;
        StringBuilder sb = new StringBuilder();
        if (mem.forecastNight < 0) sb.append("no reading yet");
        else sb.append("reading for ").append(when(mem.forecastNight, day)).append(": ")
                .append(mem.forecastMoon == null ? "no special moon" : mem.forecastMoon)
                .append(String.format(Locale.ROOT, " (%.1f%%)", mem.forecastConfidence * 100));
        if (mem.guessNight >= 0) sb.append("; guess for ").append(when(mem.guessNight, day)).append(": ").append(mem.guessMoon)
                .append(" on ").append(mem.guessClass).append(String.format(Locale.ROOT, " for %.1f XP", mem.guessReward));
        if (mem.guessResult != null) sb.append("; last result: ").append(mem.guessResult);
        return sb.toString();
    }

    private static String when(long night, long day) {
        if (night == day) return "tonight";
        if (night == day + 1) return "tomorrow night";
        return "day " + night + " (" + (day - night) + " days ago)";
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static String plain(String text) {
        return text.replace("[CivLabs] » ", "").trim();
    }
}
