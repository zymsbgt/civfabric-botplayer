package lol.zym.zymbot.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.memory.BotMemory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The team's radio over server chat. ZymLabs Rules carries a chat line to the players within 150
 * blocks in the same dimension and no further, which is the team's own rule for who can talk to
 * whom, enforced by the server instead of by the hub. So a bot that can join the server can work
 * with its team with no hub at all; the hub, when there is one, adds Claude's view of a leader,
 * job boards and updates.
 *
 * <p>Lines for bots start with {@value #TAG}. Everyone nearby sees them, players too, so there are
 * few: a status line about once a minute ("st"), each forecast reading of our own once ("fc"), each
 * forecast trust decision once ("tr"). Commands to teammates are ordinary "zb Bot2 come" lines. A
 * status line heard makes its sender a teammate in range, with the fields the rest of the bot reads
 * from a hub member (role, classes, bases, fields, forecasts), until it goes quiet.
 *
 * <p>Vanilla kicks a player for more than about one chat line a second, and the bot also sends
 * commands (/bed, /pacifist...) through the same counter: lines go out one every two seconds.
 */
public final class ChatRadio {
    public static final String TAG = "~zb";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long SPACING_MS = 2000;
    /** Readings from more than this many nights back aren't worth anyone's chat. */
    private static final int FORECAST_NIGHTS_BACK = 1;

    private record Heard(JsonObject state, long at) {}

    private final ArrayDeque<String> outbox = new ArrayDeque<>();
    private final Map<String, Heard> heard = new LinkedHashMap<>();
    /** Forecast readings and trust decisions each teammate has said, kept as their state's fields. */
    private final Map<String, Map<String, JsonObject>> readings = new HashMap<>();
    private final Map<String, JsonObject> trusts = new HashMap<>();
    /** Ours already said: "reader night readMillis", and "player at" for trust. */
    private final Set<String> said = new HashSet<>();
    private long sentAt, beaconAt;
    private String lastBeacon = "";

    public void say(String line) {
        if (outbox.contains(line)) return;
        outbox.addLast(line);
        while (outbox.size() > 20) outbox.removeFirst();
    }

    /** Send what's waiting, and our status and forecasts when they're due. On the client thread. */
    public void tick(Bot bot) {
        if (!BotConfig.get().chatRadio) return;
        long now = System.currentTimeMillis();
        String beacon = beacon(bot);
        // the position is the one part that changes all the time: not a reason to speak early
        String stable = beacon.replaceFirst(" p=\\S+", "");
        // on time, or soon after something changed (a new class role, a base)
        long every = BotConfig.get().chatBeaconSeconds * 1000L;
        if (now - beaconAt > every || !stable.equals(lastBeacon) && now - beaconAt > 15_000) {
            beaconAt = now;
            lastBeacon = stable;
            say(beacon);
            sayForecasts(bot);
        }
        if (!outbox.isEmpty() && now - sentAt > SPACING_MS) {
            sentAt = now;
            bot.chat(outbox.removeFirst());
        }
    }

    // ---- what we say ------------------------------------------------------------------

    private static String beacon(Bot bot) {
        var p = bot.player();
        var mem = bot.memory();
        StringBuilder b = new StringBuilder(TAG).append(" st");
        b.append(" r=").append(bot.role().name().toLowerCase(Locale.ROOT));
        if (mem.classRole != null) b.append(" cr=").append(mem.classRole);
        b.append(" d=").append(bot.dim());
        b.append(" p=").append(p.getBlockX()).append(',').append(p.getBlockY()).append(',').append(p.getBlockZ());
        b.append(" fp=").append(lol.zym.zymbot.brain.Progression.farmPlots(bot));
        List<String> classes = lol.zym.zymbot.brain.ClassPlan.classes(bot);
        if (!classes.isEmpty()) b.append(" cl=").append(String.join(",", classes));
        if (!mem.teamHomes.isEmpty()) {
            List<String> hs = new ArrayList<>();
            mem.teamHomes.forEach((name, h) -> hs.add(name + "@" + h.at.x + "," + h.at.y + "," + h.at.z));
            b.append(" h=").append(String.join(";", hs));
        }
        return b.toString();
    }

    /** Our own readings (and players' told to us) of nights not over yet, and trust we set: each once. */
    private void sayForecasts(Bot bot) {
        long today = bot.level().getDayTime() / 24000;
        for (BotMemory.Reading r : bot.memory().notebook.values()) {
            // only first hand, no relaying: our own, or a player's that they told us. A teammate's own
            // reading that came through the hub has no "via" either, so the reader decides
            boolean ours = bot.name().equalsIgnoreCase(r.reader) || "player".equals(r.source) && r.via == null;
            if (!ours || r.night < today - FORECAST_NIGHTS_BACK) continue;
            if (!said.add(r.reader + " " + r.night + " " + r.readMillis)) continue;
            String line = TAG + " fc " + GSON.toJson(r);
            if (line.length() <= 250) say(line);
        }
        bot.memory().forecastTrust.forEach((player, t) -> {
            if (bot.name().equalsIgnoreCase(t.by) && said.add(player + " " + t.at)) {
                String line = TAG + " tr " + player + " " + GSON.toJson(t);
                if (line.length() <= 250) say(line);
            }
        });
    }

    /** A teammate is new to us: say our current forecasts again, since they missed them. */
    private void repeatForecasts() {
        said.clear();
        beaconAt = 0;
    }

    // ---- what we hear -----------------------------------------------------------------

    /** A line of ours from teammate {@code from} (the server's "Name » text"); true if it was one. */
    public boolean hear(Bot bot, String from, String text) {
        if (!text.startsWith(TAG + " ")) return false;
        if (from.equalsIgnoreCase(bot.name()) || !bot.isConfiguredTeammate(from)) return true;
        String[] w = text.split(" ", 3);
        if (w.length < 3) return true;
        try {
            switch (w[1]) {
                case "st" -> status(bot, from, w[2]);
                case "fc" -> {
                    JsonObject r = JsonParser.parseString(w[2]).getAsJsonObject();
                    readings.computeIfAbsent(key(from), k -> new LinkedHashMap<>())
                            .put(r.get("reader").getAsString() + " " + r.get("night").getAsLong(), r);
                    touch(from);
                }
                case "tr" -> {
                    String[] t = w[2].split(" ", 2);
                    if (t.length == 2) {
                        trusts.computeIfAbsent(key(from), k -> new JsonObject()).add(t[0], JsonParser.parseString(t[1]));
                        touch(from);
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            ZymBotMod.LOG.debug("Unreadable radio line from {}: {}", from, text, e);
        }
        return true;
    }

    private void status(Bot bot, String from, String fields) {
        JsonObject s = new JsonObject();
        s.addProperty("name", from);
        s.addProperty("via_chat", true);
        for (String f : fields.split(" ")) {
            int eq = f.indexOf('=');
            if (eq <= 0) continue;
            String k = f.substring(0, eq), v = f.substring(eq + 1);
            switch (k) {
                case "r" -> s.addProperty("role", v);
                case "cr" -> s.addProperty("class_role", v);
                case "d" -> s.addProperty("dim", v);
                case "p" -> s.add("pos", xyz(v));
                case "fp" -> s.addProperty("farm_plots", Integer.parseInt(v));
                case "cl" -> {
                    JsonArray a = new JsonArray();
                    for (String c : v.split(",")) a.add(c);
                    s.add("classes", a);
                }
                case "h" -> {
                    JsonObject homes = new JsonObject();
                    for (String h : v.split(";")) {
                        int at = h.indexOf('@');
                        if (at > 0) homes.add(h.substring(0, at), xyz(h.substring(at + 1)));
                    }
                    s.add("team_homes", homes);
                }
                default -> { }
            }
        }
        if (!s.has("pos")) return;
        boolean isNew = !heard.containsKey(key(from));
        heard.put(key(from), new Heard(s, System.currentTimeMillis()));
        if (isNew) {
            bot.event("radio: " + from + " is in range (heard on chat)");
            repeatForecasts();
        }
    }

    private void touch(String from) {
        Heard h = heard.get(key(from));
        if (h != null) heard.put(key(from), new Heard(h.state(), System.currentTimeMillis()));
    }

    /** Teammates heard lately, as hub-style members: status plus the forecasts they've said. */
    List<TeamBus.Member> members(Bot bot) {
        long quiet = BotConfig.get().chatBeaconSeconds * 3000L;
        long now = System.currentTimeMillis();
        List<TeamBus.Member> out = new ArrayList<>();
        heard.values().removeIf(h -> now - h.at() > quiet);
        heard.forEach((k, h) -> {
            JsonObject s = h.state().deepCopy();
            String name = s.get("name").getAsString();
            // where they stand now, when we can see them, beats where they were at the last status line
            if (bot != null && bot.level() != null) {
                var pl = bot.findPlayer(name);
                if (pl != null) s.add("pos", xyz(pl.getBlockX() + "," + pl.getBlockY() + "," + pl.getBlockZ()));
            }
            JsonArray reads = new JsonArray();
            readings.getOrDefault(k, Map.of()).values().forEach(reads::add);
            s.add("forecast_readings", reads);
            if (trusts.containsKey(k)) s.add("forecast_trust", trusts.get(k));
            out.add(new TeamBus.Member(name, s));
        });
        return out;
    }

    private static JsonObject xyz(String v) {
        String[] c = v.split(",");
        JsonObject o = new JsonObject();
        o.addProperty("x", Integer.parseInt(c[0]));
        o.addProperty("y", Integer.parseInt(c[1]));
        o.addProperty("z", Integer.parseInt(c[2]));
        return o;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
