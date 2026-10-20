package lol.zym.zymbot.team;

import com.google.gson.*;
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
 * <p>The hub is the referee for the rule that bots more than 150 blocks apart can't
 * communicate: it only delivers messages, shows teammates and lets bots use job boards within
 * range of their last reported position. This client never blocks the game thread; a
 * background loop publishes state and pulls the inbox and the "what's near me" view once a
 * second, and results are handed back on the client thread.
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
    private volatile JsonObject pendingState;
    private volatile List<Member> nearby = List.of();
    private volatile List<JsonObject> boards = List.of();
    private volatile boolean connected;
    private volatile String lastError = "not connected yet";
    private long lastSeq = -1;

    public TeamBus(String self) {
        this.self = self;
        loop.scheduleWithFixedDelay(this::cycle, 500, 1000, TimeUnit.MILLISECONDS);
        ZymBotMod.LOG.info("Team hub {} (server {})", BotConfig.get().hubUrl, server);
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
                post("/state", st);
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
                List<Member> ms = new ArrayList<>();
                for (JsonElement e : nb.getAsJsonArray("members")) {
                    JsonObject o = e.getAsJsonObject();
                    ms.add(new Member(o.get("name").getAsString(), o));
                    everSeen.add(o.get("name").getAsString());
                }
                nearby = ms;
                List<JsonObject> bs = new ArrayList<>();
                for (JsonElement e : nb.getAsJsonArray("boards")) bs.add(e.getAsJsonObject());
                boards = bs;
            }
            if (!connected) ZymBotMod.LOG.info("Connected to team hub");
            connected = true;
        } catch (Exception e) {
            if (connected || !String.valueOf(e.getMessage()).equals(lastError))
                ZymBotMod.LOG.warn("Team hub unreachable: {}", e.toString());
            connected = false;
            lastError = String.valueOf(e.getMessage());
        }
    }

    private HttpRequest.Builder req(String path, String... query) {
        StringBuilder q = new StringBuilder("?server=").append(enc(server)).append("&as=").append(enc(self));
        for (int i = 0; i + 1 < query.length; i += 2) q.append('&').append(query[i]).append('=').append(enc(query[i + 1]));
        String base = BotConfig.get().hubUrl.replaceAll("/+$", "");
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

    /** Teammates the hub says are within radio range right now. */
    public List<Member> inRange() {
        return nearby;
    }

    /** Every bot name seen in range this session (for recognising names in chat). */
    public Collection<String> knownNames() {
        return everSeen;
    }

    public Optional<Member> member(String name) {
        return nearby.stream().filter(m -> m.name().equalsIgnoreCase(name)).findFirst();
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
