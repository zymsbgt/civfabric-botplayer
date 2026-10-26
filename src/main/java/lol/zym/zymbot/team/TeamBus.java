package lol.zym.zymbot.team;

import com.google.gson.*;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.memory.BotMemory;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Client for the team hub ({@code hub/zymbot-hub.mjs}), which bots on any machine share.
 *
 * <p>Bots talk to each other over server chat only ({@link ChatRadio}): the user's rule, 2026-10-22.
 * The hub is for job boards (usable within 150 blocks of a bot's last reported position), jar
 * updates, and Claude: our published state is Claude's view of us and the source of its alerts, and
 * the inbox carries Claude's commands to a leader. It also vouches for who is on the team. This client
 * never blocks the game thread; a background loop publishes state and pulls the inbox and the
 * "what's near me" view (boards, team names) once a second, and results are handed back on the
 * client thread.
 */
public final class TeamBus {
    public record Message(String id, String from, String via, String text, long ts) {}

    public record Member(String name, JsonObject state) {
        public String dim() {
            return state.has("dim") ? state.get("dim").getAsString() : "";
        }

        public double x() {
            return state.getAsJsonObject("pos").get("x").getAsDouble();
        }

        public double y() {
            return state.getAsJsonObject("pos").get("y").getAsDouble();
        }

        public double z() {
            return state.getAsJsonObject("pos").get("z").getAsDouble();
        }

        public double distanceTo(double ox, double oy, double oz) {
            double dx = x() - ox, dy = y() - oy, dz = z() - oz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String self;
    private final ChatRadio radio = new ChatRadio();
    private final String server = BotMemory.serverKey();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "zymbot-hub");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Set<String> everSeen = ConcurrentHashMap.newKeySet();
    /** Bots the hub has shown us: they had the hub's token, so they're the team's. */
    private final Set<String> hubSeen = ConcurrentHashMap.newKeySet();
    /** Ids of our players' lines for Claude that its watcher has picked up from the hub. */
    private final Set<String> chatRead = ConcurrentHashMap.newKeySet();
    private volatile JsonObject pendingState;
    private volatile List<JsonObject> boards = List.of();
    private volatile boolean connected;
    private volatile String lastError = "not connected yet";
    private long lastSeq = -1;
    /** Which of the configured hub addresses we're on. */
    private volatile int hubAt;

    public TeamBus(String self) {
        this.self = self;
        if (hubUrls().isEmpty()) {
            lastError = "no hub (hubUrl is off): the team talks over chat";
            ZymBotMod.LOG.info("No team hub (server {}); radio over chat", server);
            return;
        }
        loop.scheduleWithFixedDelay(this::cycle, 500, 1000, TimeUnit.MILLISECONDS);
        ZymBotMod.LOG.info("Team hub {} (server {})", String.join(" or ", hubUrls()), server);
    }

    public void close() {
        loop.shutdownNow();
    }

    public boolean connected() {
        return connected;
    }

    public String lastError() {
        return lastError;
    }

    // ---- background loop ------------------------------------------------------------

    private void cycle() {
        try {
            JsonObject st = pendingState;
            if (st != null) {
                pendingState = null;
                JsonObject r = post("/state", st);
                if (r.has("chat_read")) for (JsonElement e : r.getAsJsonArray("chat_read")) chatRead.add(e.getAsString());
            }
            // first poll only learns where the inbox is; older messages were for a previous session
            JsonObject in = get("/inbox", "after", String.valueOf(Math.max(lastSeq, 0)));
            long last = in.has("last") ? in.get("last").getAsLong() : 0;
            if (lastSeq >= 0 && in.has("messages")) {
                for (JsonElement e : in.getAsJsonArray("messages")) {
                    JsonObject o = e.getAsJsonObject();
                    inbox.add(new Message(o.get("id").getAsString(), o.get("from").getAsString(),
                            o.has("via") && !o.get("via").isJsonNull() ? o.get("via").getAsString() : null,
                            o.get("text").getAsString(), o.get("ts").getAsLong()));
                }
            }
            lastSeq = last;
            JsonObject nb = get("/nearby");
            if (nb.has("ok") && nb.get("ok").getAsBoolean()) {
                // who the hub vouches for (they had its token): their chat lines are the team's
                for (JsonElement e : nb.getAsJsonArray("members")) {
                    JsonObject o = e.getAsJsonObject();
                    everSeen.add(o.get("name").getAsString());
                    hubSeen.add(o.get("name").getAsString().toLowerCase(java.util.Locale.ROOT));
                }
                List<JsonObject> bs = new ArrayList<>();
                for (JsonElement e : nb.getAsJsonArray("boards")) bs.add(e.getAsJsonObject());
                boards = bs;
            }
            if (!connected) ZymBotMod.LOG.info("Connected to team hub {}", hubUrl());
            connected = true;
        } catch (Exception e) {
            if (connected || !String.valueOf(e.getMessage()).equals(lastError))
                ZymBotMod.LOG.warn("Team hub {} unreachable: {}", hubUrl(), e.toString());
            connected = false;
            lastError = String.valueOf(e.getMessage());
            // nobody answered there (not a refusal): the next address gets the next cycle
            List<String> urls = hubUrls();
            if (e instanceof java.io.IOException && urls.size() > 1) {
                hubAt = (hubAt + 1) % urls.size();
                lastError = null; // log the next one's failure too
            }
        }
    }

    /** The configured hub addresses, in the order to try them; none when hubUrl is "off". */
    static List<String> hubUrls() {
        String cfg = BotConfig.get().hubUrl == null ? "" : BotConfig.get().hubUrl.strip();
        if (cfg.equalsIgnoreCase("off") || cfg.equalsIgnoreCase("none")) return List.of();
        List<String> out = new ArrayList<>();
        for (String u : cfg.split("[,\\s]+")) if (!u.isBlank()) out.add(u.replaceAll("/+$", ""));
        return out.isEmpty() ? List.of("http://127.0.0.1:25599") : out;
    }

    private String hubUrl() {
        List<String> urls = hubUrls();
        return urls.get(Math.floorMod(hubAt, urls.size()));
    }

    private HttpRequest.Builder req(String path, String... query) {
        StringBuilder q = new StringBuilder("?server=").append(enc(server)).append("&as=").append(enc(self));
        for (int i = 0; i + 1 < query.length; i += 2) q.append('&').append(query[i]).append('=').append(enc(query[i + 1]));
        String base = hubUrl();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + q)).timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json");
        String token = BotConfig.get().hubToken;
        if (token != null && !token.isBlank()) b.header("authorization", "Bearer " + token);
        return b;
    }

    private JsonObject get(String path, String... query) throws Exception {
        return parse(http.send(req(path, query).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        return parse(http.send(req(path).POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private static JsonObject parse(HttpResponse<String> r) {
        if (r.statusCode() == 401) throw new IllegalStateException("hub rejected token (set hubToken in config/zymbot.json)");
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Run a request off-thread; deliver the JSON (or an error object) on the client thread. */
    private void async(String path, JsonObject body, Consumer<JsonObject> done) {
        if (hubUrls().isEmpty()) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("error", lastError);
            if (done != null) Minecraft.getInstance().execute(() -> done.accept(out));
            return;
        }
        loop.execute(() -> {
            JsonObject out;
            try {
                out = post(path, body);
            } catch (Exception e) {
                out = new JsonObject();
                out.addProperty("ok", false);
                out.addProperty("error", "hub unreachable: " + e.getMessage());
            }
            JsonObject result = out;
            if (done != null) Minecraft.getInstance().execute(() -> done.accept(result));
        });
    }

    // ---- API used by the bot ---------------------------------------------------------

    public void publish(JsonObject state) {
        state.addProperty("name", self);
        pendingState = state;
    }

    /** Messages that arrived (the hub already dropped anything sent from out of range). */
    public List<Message> poll() {
        List<Message> out = new ArrayList<>();
        Message m;
        while ((m = inbox.poll()) != null) if (markSeen(m.id())) out.add(m);
        return out;
    }

    public boolean markSeen(String id) {
        if (!seen.add(id)) return false;
        while (seen.size() > 500) seen.remove(seen.iterator().next());
        return true;
    }

    public void send(String to, String text, String id, Consumer<JsonObject> done) {
        JsonObject o = new JsonObject();
        o.addProperty("from", self);
        o.addProperty("to", to);
        o.addProperty("text", text);
        if (id != null) o.addProperty("id", id);
        async("/send", o, done);
    }

    /** The radio over server chat, which works with or without the hub. */
    public ChatRadio radio() {
        return radio;
    }

    /**
     * Teammates within radio range right now: those heard on chat lately. Bots talk over chat only
     * (the user's rule, 2026-10-22), so what the hub shows of them never stands in for it; the hub
     * only vouches for who is on the team ({@link #hubKnows}).
     */
    public List<Member> inRange() {
        return radio.members(Bot.get());
    }

    /** Every bot name seen in range this session (for recognising names in chat). */
    public Collection<String> knownNames() {
        for (Member m : radio.members(Bot.get())) everSeen.add(m.name());
        return everSeen;
    }

    /** Whether Claude's watcher has picked up the player's line with this id (the hub tells us when it does). */
    public boolean chatRead(String id) {
        return chatRead.contains(id);
    }

    /** Whether the hub has shown us this bot this session. */
    public boolean hubKnows(String name) {
        return hubSeen.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public Optional<Member> member(String name) {
        return inRange().stream().filter(m -> m.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Job boards within range, nearest first, as the hub reports them. */
    public List<JsonObject> boards() {
        return boards;
    }

    public void boardOp(JsonObject op, Consumer<JsonObject> done) {
        op.addProperty("as", self);
        async("/board", op, done);
    }
}
