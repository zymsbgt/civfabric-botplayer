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
 * forecast trust decision once ("tr"), and our food when it changes, a minute apart at most ("fd").
 * Commands to teammates are ordinary "zb Bot2 come" lines. A status line heard makes its sender a
 * teammate in range, with the fields the rest of the bot reads from a hub member (role, classes,
 * bases, fields, forecasts, food), until it goes quiet. Lines count from the names in our teammates
 * list and from bots the hub has shown us.
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

    /**
     * A teammate (or we, as a leader) asked for Claude, or took a request back because it sorted
     * itself out: passed on in our state for the hub to raise. The id is the asker's, so every bot
     * that heard it passes on the same request.
     */
    private record Help(String from, String id, String text, long at) {}

    private static final long HELP_KEEP_MS = 15 * 60_000L;
    private final ArrayDeque<Help> helps = new ArrayDeque<>();
    private final ArrayDeque<Help> recalls = new ArrayDeque<>();

    private final ArrayDeque<String> outbox = new ArrayDeque<>();
    private final Map<String, Heard> heard = new LinkedHashMap<>();
    /** Forecast readings and trust decisions each teammate has said, kept as their state's fields. */
    private final Map<String, Map<String, JsonObject>> readings = new HashMap<>();
    private final Map<String, JsonObject> trusts = new HashMap<>();
    /** Ours already said: "reader night readMillis", and "player at" for trust. */
    private final Set<String> said = new HashSet<>();
    private long sentAt, beaconAt, foodAt;
    private String lastBeacon = "", lastFood = "";
    /** Each teammate's food and meals, from its last "fd" line. */
    private final Map<String, JsonObject> foods = new HashMap<>();

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
            lastFood = "";
        }
        // our food and meals for teammates' swaps: when they change, a minute apart at most (every
        // meal and every pickup changes them), and again with each status line for newcomers
        if (now - foodAt > 60_000) {
            String food = foodLine(bot);
            if (!food.equals(lastFood)) {
                foodAt = now;
                lastFood = food;
                say(food);
            }
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
        // our home: where a teammate looking for us tries when we're nowhere it last saw us
        if (mem.home != null) b.append(" hm=").append(mem.home.x).append(',').append(mem.home.y).append(',').append(mem.home.z);
        // paused, or with nothing to do: teammates tell the hub, and Claude gets a bot back to work
        if (!bot.enabled()) b.append(" en=0");
        else if (bot.brain().workless()) b.append(" wl=1");
        List<String> classes = lol.zym.zymbot.brain.ClassPlan.classes(bot);
        if (!classes.isEmpty()) b.append(" cl=").append(String.join(",", classes));
        if (!mem.teamHomes.isEmpty()) {
            List<String> hs = new ArrayList<>();
            mem.teamHomes.forEach((name, h) -> hs.add(name + "@" + h.at.x + "," + h.at.y + "," + h.at.z));
            b.append(" h=").append(String.join(";", hs));
        }
        return b.toString();
    }

    /**
     * "~zb fd s=bread:12,cooked_beef:3 m=bread*2,carrot": the food we carry and our last meals,
     * oldest first, a run of the same food as one entry. Small piles go first if it runs long.
     */
    private static String foodLine(Bot bot) {
        List<Map.Entry<String, Integer>> stock = new ArrayList<>(lol.zym.zymbot.inv.Diet.foodCounts().entrySet());
        stock.sort((x, y) -> y.getValue() - x.getValue());
        List<String> meals = new ArrayList<>();
        String run = null;
        int n = 0;
        for (String id : lol.zym.zymbot.inv.Diet.recentMeals(bot)) {
            String s = lol.zym.zymbot.brain.FoodShare.shortId(id);
            if (s.equals(run)) {
                n++;
                continue;
            }
            if (run != null) meals.add(n > 1 ? run + "*" + n : run);
            run = s;
            n = 1;
        }
        if (run != null) meals.add(n > 1 ? run + "*" + n : run);
        while (true) {
            List<String> st = new ArrayList<>();
            for (var e : stock) st.add(lol.zym.zymbot.brain.FoodShare.shortId(e.getKey()) + ":" + e.getValue());
            String line = TAG + " fd s=" + (st.isEmpty() ? "-" : String.join(",", st)) + " m=" + (meals.isEmpty() ? "-" : String.join(",", meals));
            if (line.length() <= 250) return line;
            if (meals.size() > 4) meals.remove(0);
            else if (!stock.isEmpty()) stock.remove(stock.size() - 1);
            else return line.substring(0, 250);
        }
    }

    private static void food(JsonObject into, String fields) {
        JsonObject stock = new JsonObject();
        JsonArray meals = new JsonArray();
        for (String f : fields.split(" ")) {
            if (f.startsWith("s=") && !f.equals("s=-")) {
                for (String p : f.substring(2).split(",")) {
                    int c = p.lastIndexOf(':');
                    if (c > 0) stock.addProperty(lol.zym.zymbot.brain.FoodShare.fullId(p.substring(0, c)), Integer.parseInt(p.substring(c + 1)));
                }
            } else if (f.startsWith("m=") && !f.equals("m=-")) {
                for (String p : f.substring(2).split(",")) {
                    int star = p.lastIndexOf('*');
                    String id = lol.zym.zymbot.brain.FoodShare.fullId(star > 0 ? p.substring(0, star) : p);
                    int times = star > 0 ? Integer.parseInt(p.substring(star + 1)) : 1;
                    for (int i = 0; i < times; i++) meals.add(id);
                }
            }
        }
        into.add("food_stock", stock);
        into.add("meals", meals);
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
        // named in our teammates, or vouched for by the hub (which only lets in bots with its token)
        if (from.equalsIgnoreCase(bot.name()) || !bot.isConfiguredTeammate(from) && !bot.team().hubKnows(from)) return true;
        String[] w = text.split(" ", 3);
        if (w.length < 3) return true;
        try {
            switch (w[1]) {
                case "st" -> status(bot, from, w[2]);
                case "hp" -> {
                    String[] h = helpId(w[2]);
                    heardHelp(from, h[0], h[1]);
                    bot.event(from + " is asking for help: " + h[1]);
                }
                case "hr" -> {
                    String[] h = helpId(w[2]);
                    heardRecall(from, h[0], h[1]);
                    bot.event(from + " no longer needs help: " + h[1]);
                }
                case "fc" -> {
                    JsonObject r = JsonParser.parseString(w[2]).getAsJsonObject();
                    readings.computeIfAbsent(key(from), k -> new LinkedHashMap<>())
                            .put(r.get("reader").getAsString() + " " + r.get("night").getAsLong(), r);
                    touch(from);
                }
                case "fd" -> {
                    JsonObject f = new JsonObject();
                    food(f, w[2]);
                    foods.put(key(from), f);
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
                case "hm" -> s.addProperty("home", v.replace(",", ", "));
                case "en" -> s.addProperty("enabled", !"0".equals(v));
                case "wl" -> s.addProperty("workless", "1".equals(v));
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

    /** "#id text" -> {id, text}; a line from a bot too old to send ids has none. */
    private static String[] helpId(String s) {
        if (s.startsWith("#") && s.indexOf(' ') > 1) return new String[]{s.substring(1, s.indexOf(' ')), s.substring(s.indexOf(' ') + 1)};
        return new String[]{null, s};
    }

    /** Keep a request for help to pass on (a teammate's heard on chat, or a leader's own). */
    public void heardHelp(String from, String id, String text) {
        helps.addLast(new Help(from, id, text, System.currentTimeMillis()));
        while (helps.size() > 20) helps.removeFirst();
    }

    /** A request taken back (it sorted itself out): passed on, and no longer passed on as a request. */
    public void heardRecall(String from, String id, String text) {
        if (id != null) helps.removeIf(h -> h.from().equalsIgnoreCase(from) && id.equals(h.id()));
        recalls.addLast(new Help(from, id, text, System.currentTimeMillis()));
        while (recalls.size() > 20) recalls.removeFirst();
    }

    /** Requests heard lately, for our state: the hub raises each once, for Claude. */
    public JsonArray helpHeard() {
        return json(helps);
    }

    /** Requests taken back lately, for our state: the hub tells Claude each one is over. */
    public JsonArray helpRecalled() {
        return json(recalls);
    }

    private static JsonArray json(ArrayDeque<Help> list) {
        long now = System.currentTimeMillis();
        list.removeIf(h -> now - h.at() > HELP_KEEP_MS);
        JsonArray out = new JsonArray();
        for (Help h : list) {
            JsonObject o = new JsonObject();
            o.addProperty("from", h.from());
            if (h.id() != null) o.addProperty("id", h.id());
            o.addProperty("text", h.text());
            o.addProperty("at", h.at());
            out.add(o);
        }
        return out;
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
                s.addProperty("heard_ms", pl != null ? 0 : now - h.at());
            }
            JsonArray reads = new JsonArray();
            readings.getOrDefault(k, Map.of()).values().forEach(reads::add);
            s.add("forecast_readings", reads);
            if (trusts.containsKey(k)) s.add("forecast_trust", trusts.get(k));
            if (foods.containsKey(k)) foods.get(k).entrySet().forEach(e -> s.add(e.getKey(), e.getValue()));
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
