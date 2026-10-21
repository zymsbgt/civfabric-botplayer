package lol.zym.zymbot.civ;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.memory.BotMemory;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CivFabric's class gates, read straight from the civfabric jar on the client's classpath
 * (mining.json, blocks.json, recipes.json), plus what the server tells us when it refuses.
 *
 * <p>Ranks: 0 Novice, 1 Apprentice, 2 Journeyman, 3 Expert, 4 Master, 5 Grandmaster.
 * The bot doesn't know its own ranks exactly. It trusts {@code classRanks} in the config if
 * set, otherwise it is optimistic until refused, and then remembers the refusal for a while
 * (ranks grow with play, so refusals expire).
 */
public final class CivRules {
    public static final List<String> RANKS = List.of("novice", "apprentice", "journeyman", "expert", "master", "grandmaster");
    public static final List<String> CLASSES = List.of("miner", "builder", "farmer", "blacksmith", "medic", "librarian", "hunter");
    private static final long REFUSAL_MEMORY_MS = 30L * 60 * 1000;

    /** "You need Journeyman Miner to mine", "... to use", "... to craft" (possibly "X or Y"). */
    private static final Pattern REFUSAL = Pattern.compile(
            "You need (.+?) to (mine|use|craft)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANK_CLASS = Pattern.compile(
            "(novice|apprentice|journeyman|expert|master|grandmaster)\\s+(miner|builder|farmer|blacksmith|medic|librarian|hunter)",
            Pattern.CASE_INSENSITIVE);

    private final Map<String, Integer> miningGate = new HashMap<>();
    private final Map<String, List<Req>> blockGate = new HashMap<>();
    private final Map<String, List<Req>> recipeGate = new HashMap<>();
    private final boolean present;

    public record Req(String clazz, int rank) {}

    public CivRules() {
        Optional<ModContainer> civ = FabricLoader.getInstance().getModContainer("civfabric");
        present = civ.isPresent();
        civ.ifPresent(c -> {
            read(c, "civfabric/mining.json").ifPresent(o -> o.entrySet().forEach(e -> miningGate.put(e.getKey(), e.getValue().getAsInt())));
            read(c, "civfabric/blocks.json").ifPresent(o -> o.entrySet().forEach(e -> blockGate.put(e.getKey(), reqs(e.getValue()))));
            read(c, "civfabric/recipes.json").ifPresent(o -> o.entrySet().forEach(e -> recipeGate.put(e.getKey(), reqs(e.getValue()))));
        });
        ZymBotMod.LOG.info("CivFabric {}: {} mining gates, {} station gates, {} recipe gates",
                present ? "found" : "absent", miningGate.size(), blockGate.size(), recipeGate.size());
    }

    public boolean present() {
        return present;
    }

    private static Optional<JsonObject> read(ModContainer c, String path) {
        try {
            Optional<Path> p = c.findPath(path);
            if (p.isEmpty()) return Optional.empty();
            return Optional.of(JsonParser.parseString(Files.readString(p.get())).getAsJsonObject());
        } catch (Exception e) {
            ZymBotMod.LOG.warn("Could not read {} from civfabric", path, e);
            return Optional.empty();
        }
    }

    private static List<Req> reqs(JsonElement e) {
        List<Req> out = new ArrayList<>();
        if (!e.isJsonObject() || !e.getAsJsonObject().has("requires")) return out;
        for (JsonElement r : e.getAsJsonObject().getAsJsonArray("requires")) {
            JsonObject o = r.getAsJsonObject();
            out.add(new Req(o.get("class").getAsString(), o.get("rank").getAsInt()));
        }
        return out;
    }

    /** Best guess at our rank in a class, or -1 if unknown. */
    public int rank(BotMemory mem, String clazz) {
        Integer cfg = BotConfig.get().classRanks.get(clazz);
        if (cfg != null) return cfg;
        // read off the /class screen: exact
        if (!mem.classXp.isEmpty()) return ClassBook.rankOf(mem.classXp.getOrDefault(clazz, 0.0));
        Integer below = mem.rankBelow.get(clazz);
        return below == null ? -1 : below - 1;
    }

    private boolean meets(BotMemory mem, List<Req> reqs) {
        if (reqs == null || reqs.isEmpty()) return true;
        // CivFabric gates are "any of": one satisfied requirement is enough
        for (Req r : reqs) {
            int have = rank(mem, r.clazz());
            if (have < 0 || have >= r.rank()) return true;
        }
        return false;
    }

    private boolean recentlyRefused(BotMemory mem, String key) {
        Long t = mem.refusals.get(key);
        return t != null && System.currentTimeMillis() - t < REFUSAL_MEMORY_MS;
    }

    public boolean canMine(BotMemory mem, String blockId) {
        if (recentlyRefused(mem, "mine:" + blockId)) return false;
        Integer need = miningGate.get(blockId);
        if (need == null) return true;
        int have = rank(mem, "miner");
        return have < 0 || have >= need;
    }

    /** Why {@link #canCraft} says no, as the server would put it ("Apprentice Blacksmith"), or null if it doesn't. */
    public String craftNeeds(BotMemory mem, String recipeOrItemId) {
        if (canCraft(mem, recipeOrItemId)) return null;
        List<Req> reqs = recipeGate.get(recipeOrItemId);
        if (reqs == null || reqs.isEmpty()) return "the server's leave"; // refused, gate unknown
        return String.join(" or ", reqs.stream().map(r -> rankName(r.rank()) + " " + cap(r.clazz())).toList());
    }

    /** Why {@link #canMine} says no ("Journeyman Miner"), or null if it doesn't. */
    public String mineNeeds(BotMemory mem, String blockId) {
        if (canMine(mem, blockId)) return null;
        Integer need = miningGate.get(blockId);
        return need == null ? "the server's leave" : rankName(need) + " Miner";
    }

    private static String rankName(int rank) {
        return cap(RANKS.get(Math.max(0, Math.min(rank, RANKS.size() - 1))));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public boolean canUse(BotMemory mem, String blockId) {
        return !recentlyRefused(mem, "use:" + blockId) && meets(mem, blockGate.get(blockId));
    }

    public boolean canCraft(BotMemory mem, String recipeOrItemId) {
        return !recentlyRefused(mem, "craft:" + recipeOrItemId) && meets(mem, recipeGate.get(recipeOrItemId));
    }

    /**
     * Interpret a server message. If it is a refusal, record it against the bot's last action
     * ({@code lastActionKey} like "mine:minecraft:iron_ore") and return true.
     */
    public boolean onMessage(BotMemory mem, String text, String lastActionKey) {
        if (text.contains("Nobody knows how to make that")) {
            if (lastActionKey != null) mem.refusals.put(lastActionKey, System.currentTimeMillis());
            return true;
        }
        Matcher m = REFUSAL.matcher(text);
        if (!m.find()) return false;
        Matcher rc = RANK_CLASS.matcher(m.group(1));
        while (rc.find()) {
            int rank = RANKS.indexOf(rc.group(1).toLowerCase(Locale.ROOT));
            String clazz = rc.group(2).toLowerCase(Locale.ROOT);
            // only one of an "X or Y" list needs to hold, so each is only an upper bound when it's the sole option
            if (!m.group(1).contains(" or ")) mem.rankBelow.merge(clazz, rank, Math::min);
        }
        if (lastActionKey != null) mem.refusals.put(lastActionKey, System.currentTimeMillis());
        return true;
    }
}
