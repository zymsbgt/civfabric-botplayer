package lol.zym.zymbot.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.ZymBotMod;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * What happened to the bot, kept on disk: one JSON line per event, next to its memory file
 * ({@code <uuid>.history.jsonl}). The game's own logs keep a few files a day and roll over at every
 * restart, so "how did Bot3 get 777 Librarian XP" and "why did Bot2 keep dying" were hard to answer
 * from them. Each line has the time, the game day and time of day, where we were and what we were
 * doing; deaths and knock-outs add what did it and who was near. 8 MB, then the older half is
 * kept as {@code .1}.
 */
public final class History {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long ROLL_BYTES = 8L * 1024 * 1024;
    /** Players this close when we go down could have reached us to help. */
    public static final double NEAR = 48;

    private History() {}

    /** One of {@link Bot#event}'s lines. */
    public static void event(Bot bot, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("msg", msg);
        write(bot, "event", o);
    }

    /** A record of its own kind ("death", "downed", "revived"), with the usual context added. */
    public static void write(Bot bot, String type, JsonObject data) {
        Path f = bot.memory() == null ? null : bot.memory().historyFile();
        if (f == null || bot.player() == null || bot.level() == null) return;
        var p = bot.player();
        JsonObject o = new JsonObject();
        o.addProperty("t", System.currentTimeMillis());
        o.addProperty("type", type);
        long time = bot.level().getDayTime();
        o.addProperty("day", time / 24000);
        o.addProperty("tod", time % 24000);
        o.addProperty("dim", bot.dim());
        JsonArray pos = new JsonArray();
        pos.add(p.getBlockX());
        pos.add(p.getBlockY());
        pos.add(p.getBlockZ());
        o.add("pos", pos);
        o.addProperty("health", Math.round(p.getHealth() * 10) / 10.0);
        o.addProperty("food", p.getFoodData().getFoodLevel());
        if (!"event".equals(type)) o.addProperty("task", bot.brain().describe());
        data.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        try {
            if (Files.exists(f) && Files.size(f) > ROLL_BYTES)
                Files.move(f, f.resolveSibling(f.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            ZymBotMod.LOG.warn("Could not write history to {}", f, e);
        }
    }

    /** Every other player we can see or hear on the radio, with how far off they are, nearest first. */
    public static JsonArray around(Bot bot) {
        var me = bot.player();
        java.util.Map<String, Double> d = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Player o : bot.level().players()) if (o != me) d.put(o.getGameProfile().getName(), (double) o.distanceTo(me));
        for (var m : bot.team().inRange()) d.putIfAbsent(m.name(), m.distanceTo(me.getX(), me.getY(), me.getZ()));
        JsonArray out = new JsonArray();
        d.entrySet().stream().sorted(java.util.Map.Entry.comparingByValue()).forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getKey());
            o.addProperty("dist", Math.round(e.getValue()));
            out.add(o);
        });
        return out;
    }
}
