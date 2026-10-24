package lol.zym.zymbot.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Commands;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.regex.Pattern;

/**
 * A leader passing players' plain English on to Claude ({@code chatToClaude}). A player the bots
 * obey says something to the leader (its name, "bots" or "leader") without the {@code zb} prefix;
 * the leader puts the line in its published state ({@code chat_to_claude}), the hub raises it as a
 * "chat" alert, and the Claude session watching the alerts answers through the leader: a
 * {@code say @<player> ...} and the commands the player asked for.
 */
public final class LeaderChat {
    private LeaderChat() {}

    /** How long a line stays in our state: the hub raises each once, the first time it sees it. */
    private static final long KEEP_MS = 10 * 60_000;
    private static final int KEEP = 10;

    private record Heard(String id, String from, String text, JsonObject pos, long at) {}

    /** Only touched on the client thread. */
    private static final ArrayDeque<Heard> heard = new ArrayDeque<>();
    private static final Map<String, Long> askedAt = new HashMap<>();
    /** The bot the lines above were heard by: a new session starts them over. */
    private static Bot owner;

    /** On for leaders only: workers leave players' chat to the leader. Needs the hub, to reach Claude. */
    public static boolean enabled(Bot bot) {
        return BotConfig.get().chatToClaude && bot.role() == Bot.Role.LEADER && bot.team().connected();
    }

    /**
     * A chat line from {@code sender}: if it's plain English to this leader, pass it on and return
     * true. False leaves it to the {@code zb} command path.
     */
    public static boolean offer(Bot bot, String sender, String message) {
        if (!enabled(bot) || !obeyedHuman(bot, sender)) return false;
        String text = message.strip();
        String[] w = text.split("\\s+", 3);
        if (w.length == 0 || BotConfig.get().chatPrefixes.stream().anyMatch(p -> p.equalsIgnoreCase(w[0]))) return false;
        if (!addressed(text, bot.name())) return false;
        // "Bot2 get oak_log 16" is still a command; "Bot2, could you get some logs?" isn't
        if (w.length >= 2 && w[0].equalsIgnoreCase(bot.name()) && Commands.WORDS.contains(w[1].toLowerCase(Locale.ROOT)))
            return false;
        // the same line can arrive as both chat and system text; take it once
        if (!bot.team().markSeen("nl:" + sender + ":" + text + ":" + System.currentTimeMillis() / 2000)) return true;

        String key = sender.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = askedAt.get(key);
        if (last != null && now - last < BotConfig.get().chatToClaudeCooldownSeconds * 1000L) return true;
        askedAt.put(key, now);

        if (owner != bot) {
            heard.clear();
            owner = bot;
        }
        JsonObject pos = null;
        Player p = bot.findPlayer(sender);
        if (p != null) {
            pos = new JsonObject();
            pos.addProperty("x", p.blockPosition().getX());
            pos.addProperty("y", p.blockPosition().getY());
            pos.addProperty("z", p.blockPosition().getZ());
        }
        heard.addLast(new Heard(sender + "-" + Long.toString(now, 36), sender, text, pos, now));
        while (heard.size() > KEEP) heard.removeFirst();
        bot.event(sender + " (chat for Claude): " + text);
        bot.chat("@" + sender + " one moment…");
        return true;
    }

    /** Players' lines for Claude heard lately, for our published state. */
    public static JsonArray heard(Bot bot) {
        JsonArray out = new JsonArray();
        if (owner != bot) return out;
        long now = System.currentTimeMillis();
        heard.removeIf(h -> now - h.at() > KEEP_MS);
        for (Heard h : heard) {
            JsonObject o = new JsonObject();
            o.addProperty("id", h.id());
            o.addProperty("from", h.from());
            o.addProperty("text", h.text());
            if (h.pos() != null) o.add("pos", h.pos());
            o.addProperty("at", h.at());
            out.add(o);
        }
        return out;
    }

    private static boolean obeyedHuman(Bot bot, String name) {
        if (name == null || name.equalsIgnoreCase(bot.name()) || !bot.isConfiguredTeammate(name)) return false;
        for (String b : bot.team().knownNames()) if (b.equalsIgnoreCase(name)) return false;
        return true;
    }

    /** Names the leader, "bots" or "leader" as a word anywhere in the line ("hey Bot2, ...", "... @leader"). */
    static boolean addressed(String text, String self) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])(" + Pattern.quote(self) + "|bots|leader)(?![A-Za-z0-9_])")
                .matcher(text).find();
    }
}
