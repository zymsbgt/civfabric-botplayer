package lol.zym.zymbot.civ;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.ClassPlan;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.memory.BotMemory.Reading;
import net.minecraft.client.Minecraft;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CivFabric's moon forecast (with Enhanced Celestials on the server), read as a town. Each bot reads
 * {@code /lunarforecast} once a night, as soon as it may: after dusk the question is already about
 * tomorrow night. Every reading goes into a notebook that the bot publishes on the hub, and bots in
 * radio range merge each other's notebooks, so readings travel from bot to bot over the day as they
 * meet. Players' readings join through a leader ({@code forecast note}, usually Claude after asking
 * in chat) or by pasting the server's sentence in chat; a player's report counts once the player is
 * trusted ({@code forecast trust}), and each one is checked and scored against what the night turned
 * out to be, so whoever decides trust has a record to go on.
 *
 * <p>The guess ({@code /guessforecast}, open until dusk, paid at sunset) is whichever answer pays
 * most on the pooled odds. The server prices it against our own reading: agreeing pays the base times
 * one less its confidence, being right against it pays the base times the confidence. A pooled chance
 * of a blood moon tonight also sends the brain home before dusk ({@link #braceTime}).
 */
public final class Forecast {
    private static final String PREFIX = "[CivLabs] » ";
    /** CivFabric's dusk is 12380: before it a reading is about tonight, after it about tomorrow night. Keep clear of it. */
    private static final long BEFORE_DUSK = 12200, AFTER_DUSK = 12450;
    /** The leader's nightly report: our own reading is in just after dusk, teammates' come over chat after it. */
    private static final long REPORT_AT = AFTER_DUSK + 900;
    /** From here until the moon is well up, a likely blood moon sends us home. */
    private static final long BRACE_FROM = 10800, BRACE_UNTIL = 13000;
    /** The pooled chance of a blood moon tonight worth going home early for. */
    private static final double BRACE_AT = 0.25;
    private static final double GUESS_XP = 160;
    private static final int TRIES = 3, CHANGES = 2;
    private static final long RETRY = 1200;
    private static final Gson GSON = new Gson();
    private static final Pattern READING = Pattern.compile("You (?:sense an? (.+?)|don't sense a special moon) rising (tonight|tomorrow night) \\(([\\d.,]+)% confidence");
    private static final Pattern NAMED = Pattern.compile("(?:You name|change your mind and name|You have already named) (?:the (.+?)|a quiet night) for (tonight|tomorrow night)");
    private static final Pattern PAYS = Pattern.compile("(?:you earn|You'll get) ([\\d.,]+) (\\w+) XP");
    private static final Pattern CALLED = Pattern.compile("You called it: (?:the (.+?) (?:rises|was due)|a quiet night)");
    private static final Pattern TRY = Pattern.compile("No such class\\. Try (.+)\\.");
    private static final Pattern RISES = Pattern.compile("^The \"(.+?)\" rises");

    private boolean absent;
    private long readAskedAt = -1, guessAskedAt = -1;
    private boolean readChecked;
    private long readFor = -1, guessFor = -1, rainHeldFor = -1;
    private int readTries, guessTries;
    /** The class-screen rank we last re-read at: if the server still reads us lower, once is enough. */
    private int rereadAt = -1;
    /** Classes the server wouldn't take a guess on (switched off there). */
    private final Set<String> refused = new HashSet<>();
    private String asked;
    private long duskWatched = -1, duskMax, clearedAt = -1, bedCheckedFor = -1;
    private boolean rainyDusk;
    private final Deque<String> chat = new ArrayDeque<>();

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
        watchSky(bot, day, clock);
        if (t % 100 == 0) {
            merge(bot);
            prune(bot, day);
        }
        report(bot, day, clock);
        // claimed bed checked once a likely blood moon is on its way: the claim wakes us at home if it goes wrong
        if (bedCheckedFor != day && clock < BRACE_UNTIL && bloodOdds(bot, day) >= BRACE_AT) {
            bedCheckedFor = day;
            bot.event("forecast: a blood moon tonight looks " + percent(bloodOdds(bot, day)) + " likely; home with the bed claimed before dusk");
            bot.checkBed();
        }
        long night = clock < BEFORE_DUSK ? day : clock >= AFTER_DUSK ? day + 1 : -1;
        if (night < 0) return;
        var mem = bot.memory();
        Reading own = mem.notebook.get(key(bot.name(), night));
        if (night != readFor) {
            readFor = night;
            readTries = guessTries = 0;
            refused.clear();
        }
        // read once a night, and again after ranking up in a forecast class: the same roll, judged better
        int rank = forecastRank(bot);
        if (own == null || rank > own.rank && own.rank >= 0 && rank != rereadAt) {
            if (readTries < TRIES && (readAskedAt < 0 || t - readAskedAt > RETRY)) {
                if (own != null) rereadAt = rank;
                readTries++;
                readAskedAt = t;
                readChecked = false;
                bot.player().connection.sendCommand("lunarforecast");
            }
            return;
        }
        if (rainHeldFor == night && day < night || t - readAskedAt < 40 || guessAskedAt >= 0 && t - guessAskedAt <= RETRY) return;
        String best = bestGuess(bot, own);
        boolean first = mem.guessNight != night;
        // a change of mind is free until dusk, but only worth a command when it pays clearly more
        if (first ? guessTries >= TRIES : best.equals(mem.guessMoon) || mem.guessChanges >= CHANGES || guessTries >= TRIES + CHANGES
                || expected(bot, own, best) < expected(bot, own, mem.guessMoon) * 1.1) return;
        asked = guessClass(bot);
        if (asked == null) return;
        guessTries++;
        guessFor = night;
        guessAskedAt = t;
        bot.player().connection.sendCommand("guessforecast " + asked + " " + best);
    }

    /** The class to put a guess's XP on: the one we'd level next, else the lowest in the plan. */
    private String guessClass(Bot bot) {
        String next = ClassPlan.nextToLevel(bot);
        if (next != null && !refused.contains(next)) return next;
        return ClassPlan.classes(bot).stream().filter(c -> !refused.contains(c))
                .min(Comparator.comparingDouble(c -> Math.max(0, ClassSheet.xp(bot, c)))).orElse(null);
    }

    /** Our forecast rank from the class screen (best of Librarian and Hunter), or -1 before it's read. */
    static int forecastRank(Bot bot) {
        if (bot.memory().classXp.isEmpty()) return -1;
        return Math.max(ClassBook.rankOf(Math.max(0, ClassSheet.xp(bot, "librarian"))),
                ClassBook.rankOf(Math.max(0, ClassSheet.xp(bot, "hunter"))));
    }

    // ---- the server's replies -----------------------------------------------------------

    /** Learn from a server message; true if it was about the forecast. */
    public boolean onMessage(Bot bot, String text) {
        var mem = bot.memory();
        long day = bot.level().getDayTime() / 24000;
        Matcher m = RISES.matcher(text);
        if (m.find()) {
            outcome(bot, day, MoonOdds.category(m.group(1)));
            return false; // pacifism wants the blood moon too
        }
        // our own replies only: a player's pasted reading comes as chat, often re-formatted as server text
        if (!text.startsWith(PREFIX)) return false;
        m = READING.matcher(text);
        if (m.find()) {
            Reading r = new Reading();
            r.reader = bot.name();
            r.night = m.group(2).equals("tonight") ? day : day + 1;
            r.moon = m.group(1);
            r.confidence = number(m.group(3)) / 100;
            r.rank = MoonOdds.rankFor(r.moon, r.confidence, r.night);
            r.source = "bot";
            r.readMillis = System.currentTimeMillis();
            mem.notebook.put(key(r.reader, r.night), r);
            mem.save();
            bot.event("forecast: " + (r.moon == null ? "no special moon" : r.moon) + " " + m.group(2) + " (" + m.group(3) + "%)");
            return true;
        }
        if (text.contains("The sky refuses to be read")) {
            bot.event("forecast: the sky refuses to be read here");
            return true;
        }
        if (text.contains("before you guess: use /lunarforecast")) {
            // the server has no reading of ours for that night: read again
            if (guessFor >= 0) mem.notebook.remove(key(bot.name(), guessFor));
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
            long night = m.group(2).equals("tonight") ? day : day + 1;
            String moon = m.group(1) == null ? "none" : MoonOdds.category(m.group(1));
            if (text.contains("change your mind") && night == mem.guessNight) mem.guessChanges++;
            else if (night != mem.guessNight) mem.guessChanges = 0;
            mem.guessNight = night;
            mem.guessMoon = moon;
            Matcher p = PAYS.matcher(text);
            if (p.find()) {
                mem.guessReward = number(p.group(1));
                mem.guessClass = p.group(2).toLowerCase(Locale.ROOT);
            }
            mem.guessResult = null;
            mem.save();
            if (!text.contains("already named")) {
                var odds = odds(bot, night);
                bot.event("forecast: " + (text.contains("change your mind") ? "changed the guess to " : "guessed ") + moon + " for " + m.group(2)
                        + " (" + counted(bot, night).size() + " readings: " + oddsLine(odds) + "), " + mem.guessReward + " " + mem.guessClass + " XP if right");
            }
            return true;
        }
        if (text.contains("waiting for the rain to clear. You can name")) {
            // the held guess settles once the night is over: try again in the morning
            rainHeldFor = guessFor;
            bot.event("forecast: tonight's guess is held for the rain; naming tomorrow's in the morning");
            return true;
        }
        m = CALLED.matcher(text);
        if (m.find()) {
            mem.guessResult = text.substring(PREFIX.length()).trim();
            outcome(bot, mem.guessNight, m.group(1) == null ? "none" : MoonOdds.category(m.group(1)));
            mem.save();
            bot.event("forecast: " + mem.guessResult);
            bot.classSheet().refresh(bot);
            return true;
        }
        if (text.contains("you named did not rise") || text.contains("you named did not come") || text.contains("you named was not due")) {
            mem.guessResult = text.substring(PREFIX.length()).trim();
            mem.save();
            bot.event("forecast: wrong, " + mem.guessResult);
            return true;
        }
        return false;
    }

    // ---- what the night turned out to be ------------------------------------------------

    /**
     * Enhanced Celestials announces a moon as it rises; a night with none is quiet, but only if we
     * were here for dusk and the sky was clear (rain holds every moon back, without a word). The
     * announcement comes the moment dusk falls (12542 under a clear sky), at least 100 ticks before a
     * night can be slept away, so a clear dusk seen through without one was quiet, skipped or not.
     * A moon held back by rain is announced as soon as the sky clears, so ten clear seconds later in
     * the night without one say the same.
     */
    private void watchSky(Bot bot, long day, long clock) {
        if (clock >= 12300 && clock < 12500 && duskWatched != day) {
            duskWatched = day;
            duskMax = clock;
            rainyDusk = false;
        }
        if (duskWatched == day) {
            duskMax = Math.max(duskMax, clock);
            if (clock < 13200 && bot.level().isRaining()) rainyDusk = true;
            if (bot.level().isRaining()) clearedAt = -1;
            else if (clearedAt < 0) clearedAt = clock;
        }
        boolean cleared = duskWatched == day && rainyDusk && clearedAt >= 12542 && clock - clearedAt >= 200 && clock < 23000;
        if (cleared && !bot.memory().moonOutcomes.containsKey(day)) outcome(bot, day, "none");
        boolean seenThrough = duskWatched == day && clock >= 12700 || duskWatched == day - 1 && duskMax >= 12600;
        if (seenThrough && !rainyDusk && !bot.memory().moonOutcomes.containsKey(duskWatched)) outcome(bot, duskWatched, "none");
    }

    private void outcome(Bot bot, long night, String what) {
        if (night < 0 || what.equals(bot.memory().moonOutcomes.put(night, what))) return;
        bot.memory().save();
        long players = bot.memory().notebook.values().stream().filter(r -> r.night == night && "player".equals(r.source)).count();
        bot.event("forecast: the night turned out " + (what.equals("none") ? "quiet" : what) + (players > 0 ? " (" + players + " player reports to score)" : ""));
    }

    // ---- the notebook -------------------------------------------------------------------

    static String key(String reader, long night) {
        return reader.toLowerCase(Locale.ROOT) + " " + night;
    }

    /**
     * Put a reading in the notebook. A reader has one reading per night, which only changes when a
     * rank-up judges the same roll better: the higher rank replaces the lower. Anything else that
     * disagrees is two versions of one night, kept as a conflict for whoever weighs the reader.
     */
    static boolean store(BotMemory mem, Reading x) {
        String k = key(x.reader, x.night);
        Reading e = mem.notebook.get(k);
        if (e == null) {
            mem.notebook.put(k, x);
            return true;
        }
        if (Objects.equals(e.moon, x.moon) && (x.rank == e.rank || x.rank < 0)) {
            boolean changed = false;
            if (e.conflict == null && x.conflict != null) {
                e.conflict = x.conflict;
                changed = true;
            }
            if (e.confidence < 0 && x.confidence >= 0) {
                e.confidence = x.confidence;
                e.rank = x.rank;
                changed = true;
            }
            return changed;
        }
        if (x.rank >= 0 && e.rank >= 0 && x.rank != e.rank) {
            if (x.rank < e.rank) return false;
            mem.notebook.put(k, x);
            return true;
        }
        Reading newer = x.readMillis >= e.readMillis ? x : e, older = newer == x ? e : x;
        newer.conflict = describe(older);
        mem.notebook.put(k, newer);
        return true;
    }

    /** Take in the notebooks and trust lists of teammates in radio range. */
    private void merge(Bot bot) {
        var mem = bot.memory();
        boolean changed = false;
        for (var m : bot.team().inRange()) {
            JsonElement reads = m.state().get("forecast_readings");
            if (reads != null && reads.isJsonArray()) {
                for (JsonElement el : reads.getAsJsonArray()) {
                    Reading x = GSON.fromJson(el, Reading.class);
                    if (x.reader == null || x.reader.equalsIgnoreCase(bot.name()) || "ignore".equals(trust(bot, x.reader))) continue;
                    x.via = x.reader.equalsIgnoreCase(m.name()) ? null : m.name();
                    if (store(mem, x)) {
                        changed = true;
                        if (x.night >= bot.level().getDayTime() / 24000)
                            bot.event("forecast: " + m.name() + " passed on " + describe(x));
                    }
                }
            }
            JsonElement trusts = m.state().get("forecast_trust");
            if (trusts != null && trusts.isJsonObject()) {
                for (var e : trusts.getAsJsonObject().entrySet()) {
                    BotMemory.Trust x = GSON.fromJson(e.getValue(), BotMemory.Trust.class);
                    BotMemory.Trust mine = mem.forecastTrust.get(e.getKey());
                    if (mine == null || x.at > mine.at) {
                        mem.forecastTrust.put(e.getKey(), x);
                        changed = true;
                    }
                }
            }
        }
        if (changed) mem.save();
    }

    /** Bots' readings matter until their night is over; players' stay a month, for their record. */
    private static void prune(Bot bot, long day) {
        var mem = bot.memory();
        mem.notebook.values().removeIf(r -> r.night < day - ("player".equals(r.source) ? 30 : 1));
        mem.moonOutcomes.keySet().removeIf(n -> n < day - 30);
    }

    /** What we publish for teammates: the notebook and the trust list. */
    public static void publish(Bot bot, JsonObject o) {
        var mem = bot.memory();
        if (mem.notebook.isEmpty() && mem.forecastTrust.isEmpty()) return;
        JsonArray reads = new JsonArray();
        mem.notebook.values().stream().sorted(Comparator.comparingLong((Reading r) -> -r.night)).limit(80)
                .forEach(r -> reads.add(GSON.toJsonTree(r)));
        o.add("forecast_readings", reads);
        o.add("forecast_trust", GSON.toJsonTree(mem.forecastTrust));
        o.addProperty("forecast", describe(bot));
    }

    // ---- trust and the odds ---------------------------------------------------------------

    /** full, show or ignore. Bots are full; players are what a leader set, else full for the bots' teammates and show for anyone else. */
    static String trust(Bot bot, String player) {
        BotMemory.Trust t = bot.memory().forecastTrust.get(player.toLowerCase(Locale.ROOT));
        if (t != null) return t.level;
        if (bot.team().knownNames().stream().anyMatch(player::equalsIgnoreCase)) return "full";
        return BotConfig.get().teammates.stream().anyMatch(player::equalsIgnoreCase) ? "full" : "show";
    }

    /** A reading counts towards the odds: possible on its night, and a bot's or a trusted player's. */
    private static boolean counts(Bot bot, Reading r) {
        return MoonOdds.plausible(r.moon, r.night) && ("bot".equals(r.source) || "full".equals(trust(bot, r.reader)));
    }

    static List<Reading> counted(Bot bot, long night) {
        List<Reading> out = new ArrayList<>();
        for (Reading r : bot.memory().notebook.values()) if (r.night == night && counts(bot, r)) out.add(r);
        return out;
    }

    /** P(each guess) for that night from every reading that counts. A reader of unknown rank is taken as a Novice. */
    public static Map<String, Double> odds(Bot bot, long night) {
        List<MoonOdds.Read> reads = new ArrayList<>();
        for (Reading r : counted(bot, night)) reads.add(new MoonOdds.Read(r.moon, Math.max(0, r.rank)));
        return MoonOdds.pool(reads, night);
    }

    static double bloodOdds(Bot bot, long night) {
        return counted(bot, night).isEmpty() ? 0 : odds(bot, night).get("blood_moon");
    }

    /** The XP a guess is worth on the pooled odds, priced against our own reading as the server does. */
    private static double expected(Bot bot, Reading own, String guess) {
        if (guess == null) return 0;
        double c = Math.max(0, own.confidence);
        boolean agrees = guess.equals(MoonOdds.category(own.moon));
        return odds(bot, own.night).get(guess) * GUESS_XP * (agrees ? 1 - c : c);
    }

    private static String bestGuess(Bot bot, Reading own) {
        String best = MoonOdds.category(own.moon);
        for (String g : MoonOdds.GUESSES) if (expected(bot, own, g) > expected(bot, own, best) + 1e-9) best = g;
        return best;
    }

    /** A likely blood moon tonight and dusk is near: be home, by the bed, before it rises. */
    public static boolean braceTime(Bot bot) {
        long time = bot.level().getDayTime(), clock = time % 24000;
        return clock >= BRACE_FROM && clock < BRACE_UNTIL && bloodOdds(bot, time / 24000) >= BRACE_AT;
    }

    // ---- players --------------------------------------------------------------------------

    /**
     * A chat line from a player (not a bot). A leader keeps the last few for whoever drives it, who
     * asks players for their readings in chat; a pasted reading is taken as it is.
     */
    public void onPlayerChat(Bot bot, String player, String text) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || conn.getPlayerInfo(player) == null || player.equalsIgnoreCase(bot.name())
                || bot.team().knownNames().stream().anyMatch(player::equalsIgnoreCase)) return;
        String line = String.format("%tT %s: %s", System.currentTimeMillis(), player, text);
        // the same line can arrive as chat and as server text
        if (!chat.isEmpty() && chat.peekLast().substring(9).equals(line.substring(9))) return;
        chat.addLast(line);
        while (chat.size() > 20) chat.removeFirst();
        Matcher m = READING.matcher(text);
        if (m.find()) {
            long time = bot.level().getDayTime(), day = time / 24000;
            // "tonight" was read before a dusk, so it's tonight's; "tomorrow night" was read after one: still to come after dusk, today's by morning
            long night = m.group(2).equals("tonight") ? day : time % 24000 >= 12380 ? day + 1 : day;
            bot.event("forecast: " + player + " pasted their reading: " + note(bot, player, night, m.group(1), number(m.group(3)) / 100, -1, null));
        }
    }

    public List<String> chatHeard() {
        return new ArrayList<>(chat);
    }

    /** Record a player's reading (from a leader, or pasted). Checked here; counted only if the player is trusted. */
    static String note(Bot bot, String player, long night, String moon, double confidence, int statedRank, String via) {
        if ("ignore".equals(trust(bot, player))) return player + "'s readings are ignored";
        Reading r = new Reading();
        r.reader = player;
        r.night = night;
        r.moon = moon;
        r.confidence = confidence;
        r.rank = confidence >= 0 ? MoonOdds.rankFor(moon, confidence, night) : -1;
        r.statedRank = statedRank;
        r.source = "player";
        r.via = via;
        r.readMillis = System.currentTimeMillis();
        store(bot.memory(), r);
        bot.memory().save();
        List<String> flags = flags(r);
        return describe(r) + (counts(bot, r) ? "; counts" : "; listed only (" + trust(bot, player) + ")")
                + (flags.isEmpty() ? "" : "; check: " + String.join(", ", flags));
    }

    /** What's wrong with a report by the server's own rules. */
    static List<String> flags(Reading r) {
        List<String> f = new ArrayList<>();
        if (!MoonOdds.plausible(r.moon, r.night)) f.add(r.moon + " can't rise on a phase-" + MoonOdds.phase(r.night) + " night");
        else if (r.confidence >= 0 && r.rank < 0) f.add(String.format(Locale.ROOT, "%.1f%% fits no rank that night", r.confidence * 100));
        if (r.statedRank >= 0 && r.rank >= 0 && r.statedRank != r.rank)
            f.add("said " + CivRules.RANKS.get(r.statedRank) + ", the figure says " + CivRules.RANKS.get(r.rank));
        if (r.conflict != null) f.add("also reported " + r.conflict);
        return f;
    }

    /**
     * A player's record: each report scored against what its night turned out to be, next to how
     * often a reader of that rank would have been right (the printed confidence is exactly that
     * chance), so a few honest misses don't look like lies and a run of misses does.
     */
    static String record(Bot bot, String player) {
        var outs = bot.memory().moonOutcomes;
        List<Reading> mine = bot.memory().notebook.values().stream()
                .filter(r -> "player".equals(r.source) && r.reader.equalsIgnoreCase(player))
                .sorted(Comparator.comparingLong(r -> r.night)).toList();
        if (mine.isEmpty()) return player + ": no reports";
        int named = 0, rose = 0, silent = 0, quiet = 0, flagged = 0;
        double namedExp = 0, silentExp = 0;
        for (Reading r : mine) {
            if (!flags(r).isEmpty()) flagged++;
            String out = outs.get(r.night);
            if (out == null || !MoonOdds.plausible(r.moon, r.night)) continue;
            double c = MoonOdds.confidence(r.moon, Math.max(0, r.rank), r.night);
            boolean right = out.equals(MoonOdds.category(r.moon));
            if (r.moon == null) {
                silent++;
                silentExp += c;
                if (right) quiet++;
            } else {
                named++;
                namedExp += c;
                if (right) rose++;
            }
        }
        BotMemory.Trust t = bot.memory().forecastTrust.get(player.toLowerCase(Locale.ROOT));
        return String.format(Locale.ROOT, "%s (%s%s): %d reports, %d flagged; named a moon %d times, %d came (%.1f expected at their rank); sensed nothing %d times, %d quiet (%.1f expected)",
                player, trust(bot, player), t == null ? "" : ", " + t.reason + " - " + t.by, mine.size(), flagged, named, rose, namedExp, silent, quiet, silentExp);
    }

    // ---- reports ---------------------------------------------------------------------------

    static String describe(Reading r) {
        return r.reader + ": " + (r.moon == null ? "nothing" : r.moon) + " for day " + r.night
                + (r.confidence >= 0 ? String.format(Locale.ROOT, " (%.1f%%", r.confidence * 100) + (r.rank >= 0 ? ", " + CivRules.RANKS.get(r.rank) : "") + ")" : "")
                + (r.via != null ? " via " + r.via : "");
    }

    public static String describe(Bot bot) {
        var mem = bot.memory();
        long time = bot.level().getDayTime(), day = time / 24000;
        long night = time % 24000 >= 12380 ? day + 1 : day;
        StringBuilder sb = new StringBuilder();
        for (long n : time % 24000 >= 12380 ? new long[]{day, night} : new long[]{night}) {
            if (!sb.isEmpty()) sb.append("; ");
            List<Reading> used = counted(bot, n);
            long shown = mem.notebook.values().stream().filter(r -> r.night == n).count() - used.size();
            Reading own = mem.notebook.get(key(bot.name(), n));
            sb.append(when(n, day)).append(": ");
            if (used.isEmpty()) sb.append("no readings");
            else sb.append(oddsLine(odds(bot, n))).append(" from ").append(used.size()).append(used.size() == 1 ? " reading" : " readings");
            if (shown > 0) sb.append(" (+").append(shown).append(" listed only)");
            if (own != null) sb.append("; ours ").append(own.moon == null ? "nothing" : own.moon).append(String.format(Locale.ROOT, " %.1f%%", own.confidence * 100));
            if (mem.guessNight == n) sb.append("; guess ").append(mem.guessMoon).append(" on ").append(mem.guessClass)
                    .append(String.format(Locale.ROOT, " for %.1f XP", mem.guessReward));
        }
        if (mem.guessResult != null) sb.append("; last result: ").append(mem.guessResult);
        return sb.toString();
    }

    /** As leader, once a night after sunset: everyone's readings of the coming night, to the players who asked. */
    private static void report(Bot bot, long day, long clock) {
        var mem = bot.memory();
        if (mem.forecastReportTo.isEmpty() || bot.role() != Bot.Role.LEADER || clock < REPORT_AT || mem.forecastReportedFor == day + 1) return;
        mem.forecastReportedFor = day + 1;
        String line = reportLine(bot, day + 1, day);
        bot.event("forecast: nightly report to " + String.join(", ", mem.forecastReportTo) + ": " + line);
        for (String p : mem.forecastReportTo) bot.chat("@" + p + " " + line);
    }

    /** Each reader's forecast of {@code night} (the same reading by several put together), then the pooled odds. */
    static String reportLine(Bot bot, long night, long day) {
        Map<String, List<String>> by = new LinkedHashMap<>();
        for (Reading r : bot.memory().notebook.values()) {
            if (r.night != night) continue;
            String what = (r.moon == null ? "nothing" : r.moon) + (r.confidence >= 0 ? " " + percent(r.confidence) : "");
            by.computeIfAbsent(what, k -> new ArrayList<>()).add(r.reader);
        }
        if (by.isEmpty()) return "forecasts for " + when(night, day) + ": no readings yet";
        List<String> parts = new ArrayList<>();
        by.forEach((what, who) -> parts.add(String.join(", ", who) + " " + what));
        return "forecasts for " + when(night, day) + ": " + String.join("; ", parts)
                + (counted(bot, night).isEmpty() ? "" : ". Together: " + oddsLine(odds(bot, night)));
    }

    /** The forecast command: summary, readings, a player's note, trust, players' records. */
    public static String command(Bot bot, String from, String[] a) {
        var mem = bot.memory();
        long time = bot.level().getDayTime(), day = time / 24000;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> {
                return describe(bot);
            }
            case "readings" -> {
                long night = a.length > 2 ? night(a[2], time) : time % 24000 >= 12380 ? day + 1 : day;
                List<String> out = new ArrayList<>();
                for (Reading r : mem.notebook.values()) {
                    if (r.night != night) continue;
                    List<String> f = flags(r);
                    out.add(describe(r) + (counts(bot, r) ? "" : " [listed only]") + (f.isEmpty() ? "" : " [" + String.join(", ", f) + "]"));
                }
                return when(night, day) + ": " + (out.isEmpty() ? "no readings" : String.join("; ", out));
            }
            case "report" -> {
                // forecast report <player|off>: a player may sign themselves up; a leader (or Claude) anyone
                if (a.length < 3) return mem.forecastReportTo.isEmpty() ? "no nightly report; forecast report <player|off>"
                        : "each night after sunset I tell " + String.join(", ", mem.forecastReportTo) + " everyone's forecasts; now: "
                        + reportLine(bot, time % 24000 >= 12380 ? day + 1 : day, day);
                String who = a[2].equalsIgnoreCase("me") ? from : a[2];
                boolean off = who.equalsIgnoreCase("off") || a.length > 3 && a[3].equalsIgnoreCase("off");
                if (off && a.length <= 3) {
                    if (!ClassPlan.mayDirect(bot, from) && !(mem.forecastReportTo.size() == 1 && mem.forecastReportTo.get(0).equalsIgnoreCase(from)))
                        return "only a leader (or Claude) turns everyone's report off; forecast report <you> off stops yours";
                    mem.forecastReportTo.clear();
                    return "nightly forecast report off";
                }
                if (!ClassPlan.mayDirect(bot, from) && !who.equalsIgnoreCase(from)) return "you can sign yourself up (forecast report me); a leader signs up others";
                if (off) {
                    mem.forecastReportTo.removeIf(n -> n.equalsIgnoreCase(who));
                    return "no more nightly forecasts for " + who;
                }
                if (mem.forecastReportTo.stream().noneMatch(n -> n.equalsIgnoreCase(who))) mem.forecastReportTo.add(who);
                // signed up after tonight's report time: the first comes tomorrow evening (they're asking now, so know now)
                if (time % 24000 >= REPORT_AT) mem.forecastReportedFor = day + 1;
                return "each night after sunset I'll tell " + who + " everyone's forecasts" + (bot.role() == Bot.Role.LEADER ? "" : " (while I'm the leader)");
            }
            case "note" -> {
                if (!ClassPlan.mayDirect(bot, from)) return "only a leader (or Claude) records players' readings";
                if (a.length < 5) return "forecast note <player> <tonight|tomorrow|day> <moon|none> [confidence%] [rank]";
                String moon;
                try {
                    moon = MoonOdds.moonNamed(a[4]);
                } catch (IllegalArgumentException e) {
                    return e.getMessage() + " (blood_moon, super_blood_moon, blue_moon, super_blue_moon, harvest_moon, super_harvest_moon, super_moon or none)";
                }
                double conf = -1;
                int stated = -1;
                for (int i = 5; i < a.length; i++) {
                    String w = a[i].toLowerCase(Locale.ROOT).replace("%", "");
                    if (w.matches("[\\d.,]+")) conf = number(w) / 100;
                    else if (CivRules.RANKS.contains(w)) stated = CivRules.RANKS.indexOf(w);
                }
                return note(bot, a[2], night(a[3], time), moon, conf, stated, from == null || from.equalsIgnoreCase(bot.name()) ? null : from);
            }
            case "trust" -> {
                if (!ClassPlan.mayDirect(bot, from)) return "only a leader (or Claude) decides whose readings count";
                if (a.length < 4 || !List.of("full", "show", "ignore").contains(a[3].toLowerCase(Locale.ROOT)))
                    return "forecast trust <player> <full|show|ignore> <reason>";
                BotMemory.Trust t = new BotMemory.Trust();
                t.level = a[3].toLowerCase(Locale.ROOT);
                t.reason = a.length > 4 ? String.join(" ", Arrays.copyOfRange(a, 4, a.length)) : "no reason given";
                t.by = from == null ? bot.name() : from;
                t.at = System.currentTimeMillis();
                mem.forecastTrust.put(a[2].toLowerCase(Locale.ROOT), t);
                if (t.level.equals("ignore")) mem.notebook.values().removeIf(r -> "player".equals(r.source) && r.reader.equalsIgnoreCase(a[2]));
                mem.save();
                return a[2] + "'s readings: " + t.level + " (" + t.reason + ")";
            }
            case "players" -> {
                if (a.length > 2) return record(bot, a[2]);
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Reading r : mem.notebook.values()) if ("player".equals(r.source)) names.add(r.reader);
                names.addAll(mem.forecastTrust.keySet());
                if (names.isEmpty()) return "no players' readings yet";
                List<String> out = new ArrayList<>();
                for (String n : names) out.add(record(bot, n));
                return String.join(" | ", out);
            }
            default -> {
                return "forecast | forecast readings [tonight|tomorrow|day] | forecast report <player|me|off> [off] | forecast note <player> <tonight|tomorrow|day> <moon|none> [confidence%] [rank]"
                        + " | forecast trust <player> <full|show|ignore> <reason> | forecast players [name]";
            }
        }
    }

    /** "tonight", "tomorrow" or a day number, as said now. */
    private static long night(String w, long time) {
        long day = time / 24000;
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "tonight" -> day;
            case "tomorrow" -> day + 1;
            default -> Long.parseLong(w);
        };
    }

    private static String when(long night, long day) {
        if (night == day) return "tonight";
        if (night == day + 1) return "tomorrow night";
        return "day " + night;
    }

    private static String oddsLine(Map<String, Double> odds) {
        List<String> parts = new ArrayList<>();
        odds.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> parts.add((e.getKey().equals("none") ? "quiet" : e.getKey()) + " " + percent(e.getValue())));
        return String.join(", ", parts);
    }

    private static String percent(double p) {
        return String.format(Locale.ROOT, "%.0f%%", p * 100);
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
