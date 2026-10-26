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
 *
 * <p>The leader says "one moment" only once the hub reports that the watcher picked the line up
 * ({@link lol.zym.zymbot.team.TeamBus#chatRead}); with no word of it within {@link #ANSWER_MS}, it
 * tells the player the line didn't get through (nobody is watching, or the hub is down).
 */
public final class LeaderChat {
    private LeaderChat() {}

    /** How long a line stays in our state: the hub raises each once, the first time it sees it. */
    private static final long KEEP_MS = 10 * 60_000;
    private static final int KEEP = 10;

    /** How long Claude's watcher gets to pick a line up before we tell the player it didn't get through. */
    private static final long ANSWER_MS = 20_000;

    private record Heard(String id, String from, String text, JsonObject pos, long at, boolean[] told) {
        // told[0]: said "one moment" (Claude has it); told[1]: said it didn't get through
    }

    /** Only touched on the client thread. */
    private static final ArrayDeque<Heard> heard = new ArrayDeque<>();
    private static final Map<String, Long> askedAt = new HashMap<>();
    /** The bot the lines above were heard by: a new session starts them over. */
    private static Bot owner;

    /** On for leaders only: workers leave players' chat to the leader. */
    public static boolean enabled(Bot bot) {
        return BotConfig.get().chatToClaude && bot.role() == Bot.Role.LEADER;
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
        if (!bot.team().connected()) {
            // no hub, so no Claude: a line that is still a command goes to the command path
            if (Commands.address(text, bot.name(), bot.team().knownNames()) != null) return false;
            bot.chat("@" + sender + " I can't reach Claude right now (no hub link), so that didn't get through. zb commands still work.");
            return true;
        }

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
        heard.addLast(new Heard(sender + "-" + Long.toString(now, 36), sender, text, pos, now, new boolean[2]));
        while (heard.size() > KEEP) heard.removeFirst();
        bot.event(sender + " (chat for Claude): " + text);
        return true;
    }

    /** Tell each player whether Claude got their line: "one moment" once it has, a failure once it's late. */
    public static void tick(Bot bot) {
        if (owner != bot) return;
        long now = System.currentTimeMillis();
        for (Heard h : heard) {
            if (h.told()[0]) continue;
            if (bot.team().chatRead(h.id())) {
                h.told()[0] = true;
                bot.chat("@" + h.from() + (h.told()[1] ? " got it after all, one moment…" : " one moment…"));
            } else if (!h.told()[1] && now - h.at() > ANSWER_MS) {
                h.told()[1] = true;
                bot.event("Claude didn't pick up " + h.from() + "'s line in " + ANSWER_MS / 1000 + "s");
                bot.chat("@" + h.from() + " sorry, that request failed (nobody's reading leader chat right now). Try again later, or use zb commands.");
            }
        }
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
