package lol.zym.zymbot.chat;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.team.ChatRadio;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds server text to the bot: CivFabric notices (refusals, knocked out...) and teammates'
 * chat commands. ZymLabs local chat re-formats player messages as system text, so the sender
 * is recovered from the text itself when there's no signed sender.
 */
public final class ChatListener {
    private ChatListener() {}

    private static final Pattern NAME = Pattern.compile("^[^A-Za-z0-9_]*([A-Za-z0-9_]{3,16})\\W{1,4}\\s*(.+)$");
    /**
     * A radio line exactly as the server prints chat ("Bot2 » ~zb ...", "Bot2 >> ~zb ..." from ZymLabs
     * Rules before 2026-10-21, or vanilla's "<Bot2> ~zb ..."):
     * the sender has to be the very first thing on the line, so nobody can type a line that passes
     * for a bot's.
     */
    private static final Pattern RADIO = Pattern.compile("^(?:<([A-Za-z0-9_]{3,16})>|([A-Za-z0-9_]{3,16}) (?:\u00bb|>>)) (" + ChatRadio.TAG + " .+)$");

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            Bot bot = Bot.get();
            if (bot == null) return;
            String name = sender != null ? sender.getName() : null;
            String body = signed != null ? signed.signedContent() : message.getString();
            if (name != null && bot.team().radio().hear(bot, name, body)) return;
            if (radio(bot, message.getString())) return;
            if (name == null) {
                String[] parsed = split(message.getString(), bot);
                if (parsed == null) return;
                name = parsed[0];
                body = parsed[1];
            }
            bot.onChat(name, body);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Bot bot = Bot.get();
            if (bot == null) return;
            String text = message.getString();
            if (!overlay && radio(bot, text)) return;
            bot.onServerMessage(text);
            if (!overlay) {
                String[] parsed = split(text, bot);
                if (parsed != null) bot.onChat(parsed[0], parsed[1]);
            }
        });
    }

    private static boolean radio(Bot bot, String text) {
        Matcher m = RADIO.matcher(text);
        if (!m.matches()) return false;
        bot.team().radio().hear(bot, m.group(1) != null ? m.group(1) : m.group(2), m.group(3));
        return true;
    }

    /**
     * Find "[sender, message]" in a formatted line like "<Zym> zb come", "Zym: zb come" or
     * "[Local] Zym » zb come". Only known teammates count as senders.
     */
    static String[] split(String text, Bot bot) {
        List<String> names = new ArrayList<>(BotConfig.get().teammates);
        names.addAll(bot.team().knownNames());
        String lower = text.toLowerCase(Locale.ROOT);
        int bestAt = Integer.MAX_VALUE;
        String best = null;
        for (String n : names) {
            int at = indexOfWord(lower, n.toLowerCase(Locale.ROOT));
            if (at >= 0 && at < bestAt && at < 40) {
                bestAt = at;
                best = n;
            }
        }
        if (best == null) {
            Matcher m = NAME.matcher(text);
            return m.matches() ? new String[]{m.group(1), m.group(2)} : null;
        }
        String rest = text.substring(bestAt + best.length()).replaceFirst("^[\\s>:»\\]\\)\\-|]+", "");
        return new String[]{best, rest};
    }

    private static int indexOfWord(String hay, String word) {
        int from = 0;
        while (true) {
            int i = hay.indexOf(word, from);
            if (i < 0) return -1;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1)) && hay.charAt(i - 1) != '_';
            int end = i + word.length();
            boolean endOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end)) && hay.charAt(end) != '_';
            if (startOk && endOk) return i;
            from = i + 1;
        }
    }
}
