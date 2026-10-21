package lol.zym.zymbot.team;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * New versions of this mod from the team hub, for bots on machines we can't copy files to.
 *
 * <p>With {@code autoUpdate} on, we ask the hub ({@code GET /mod/info}) for the SHA-256 of the jar it
 * serves, at startup, every half hour and on the "update" command. When it isn't ours, we download
 * it ({@code GET /mod}, with the hub token), check it against that hash, and leave it beside our jar
 * as {@code <jar>.update}, a name Fabric doesn't load. A running game can't replace its own jar, so
 * {@link Swap}, started once in a JVM of its own (from a temporary copy, so a newer download can
 * still replace {@code .update}), waits for the game to close and copies in whatever is there then. The
 * new version runs from the next launch; "update" closes the game straight away to get there.
 */
public final class Updater {
    private static final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "zymbot-update");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static Path jar;
    private static String ownSha;
    /** The hash of the jar waiting in {@code .update}, or null. */
    private static volatile String staged;
    private static boolean swapStarted;
    private static volatile String status = "off (autoUpdate in config/zymbot.json)";

    private Updater() {}

    /** At startup: clear what the last update left, then check now and every half hour. */
    public static void start() {
        if (!BotConfig.get().autoUpdate) return;
        jar = ownJar();
        if (jar == null) {
            status = "not running from a jar (a dev build): nothing to update";
            return;
        }
        loop.execute(() -> {
            try {
                Files.deleteIfExists(update());
                ownSha = sha256(Files.readAllBytes(jar));
                status = "running " + ownSha.substring(0, 12) + "; not checked yet";
            } catch (Exception e) {
                status = "can't read our own jar: " + e;
            }
        });
        loop.scheduleWithFixedDelay(Updater::check, 5, 30 * 60, TimeUnit.SECONDS);
    }

    public static String status() {
        return status;
    }

    /** Check now (the "update" command); with {@code quit}, close the game once a new version is waiting. */
    public static void checkNow(boolean quit) {
        loop.execute(() -> {
            check();
            if (quit && staged != null && swapStarted) {
                say("closing the game to install " + staged.substring(0, 12) + "; launch it again to run the new version");
                Minecraft mc = Minecraft.getInstance();
                loop.schedule(() -> mc.execute(mc::stop), 3, TimeUnit.SECONDS); // let the reply reach the hub first
            }
        });
    }

    private static void check() {
        if (jar == null || ownSha == null) return;
        if (TeamBus.hubUrls().isEmpty()) {
            status = "no hub to update from (hubUrl is off)";
            return;
        }
        Exception last = null;
        for (String base : TeamBus.hubUrls()) {
            try {
                JsonObject info = JsonParser.parseString(fetch(base + "/mod/info", HttpResponse.BodyHandlers.ofString())).getAsJsonObject();
                if (!info.has("sha256")) {
                    status = "the hub has no jar to serve: " + (info.has("error") ? info.get("error").getAsString() : info);
                    return;
                }
                String want = info.get("sha256").getAsString();
                if (want.equals(ownSha)) {
                    if (staged != null) Files.deleteIfExists(update()); // the hub went back to ours: don't install
                    staged = null;
                    status = "up to date (" + ownSha.substring(0, 12) + ")";
                    return;
                }
                if (want.equals(staged)) return;
                byte[] body = fetch(base + "/mod", HttpResponse.BodyHandlers.ofByteArray());
                String got = sha256(body);
                if (!got.equals(want)) {
                    status = "download didn't match the hub's SHA-256 (" + got.substring(0, 12) + " for " + want.substring(0, 12) + "); not installed";
                    ZymBotMod.LOG.warn("Update: {}", status);
                    return;
                }
                try {
                    Path tmp = jar.resolveSibling(jar.getFileName() + ".part");
                    Files.write(tmp, body);
                    Files.move(tmp, update(), StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException e) {
                    // the hub answered: this is our disk, not the network
                    status = "couldn't save version " + want.substring(0, 12) + " beside our jar: " + e;
                    ZymBotMod.LOG.warn("Update: {}", status);
                    return;
                }
                staged = want;
                if (!swapStarted) startSwap();
                status = "version " + want.substring(0, 12) + " downloaded; installs when the game closes";
                say("update " + want.substring(0, 12) + " downloaded from the hub; it installs when the game closes");
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        status = "couldn't reach the hub for updates: " + last;
    }

    private static <T> T fetch(String url, HttpResponse.BodyHandler<T> as) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET();
        String token = BotConfig.get().hubToken;
        if (token != null && !token.isBlank()) b.header("authorization", "Bearer " + token);
        HttpResponse<T> r = http.send(b.build(), as);
        if (r.statusCode() == 401) throw new IllegalStateException("hub rejected token");
        if (r.statusCode() != 200) throw new IllegalStateException("hub answered " + r.statusCode() + " for " + url);
        return r.body();
    }

    private static void startSwap() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow(() -> new IllegalStateException("can't find our java"));
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("zymbot-update.log");
        Files.createDirectories(log.getParent());
        // the helper runs from a copy of its own: Windows locks a jar on a running JVM's classpath, and
        // a helper run from .update kept a newer version from being saved over it (found on Bot3)
        Path helper = Files.createTempFile("zymbot-swap", ".jar");
        Files.copy(update(), helper, StandardCopyOption.REPLACE_EXISTING);
        new ProcessBuilder(java, "-cp", helper.toString(), Swap.class.getName(),
                String.valueOf(ProcessHandle.current().pid()), update().toString(), jar.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        swapStarted = true;
    }

    private static Path update() {
        return jar.resolveSibling(jar.getFileName() + ".update");
    }

    /** The jar Fabric loaded us from, or null in a dev run (classes straight from the build). */
    private static Path ownJar() {
        var origin = FabricLoader.getInstance().getModContainer("zymbot").map(c -> c.getOrigin()).orElse(null);
        if (origin == null || origin.getKind() != ModOrigin.Kind.PATH) return null;
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1) return null;
        Path p = paths.get(0);
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p : null;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void say(String text) {
        ZymBotMod.LOG.info("Update: {}", text);
        Minecraft.getInstance().execute(() -> {
            Bot bot = Bot.get();
            if (bot != null) bot.event(text);
        });
    }
}
