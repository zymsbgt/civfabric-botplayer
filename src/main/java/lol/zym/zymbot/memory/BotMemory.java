package lol.zym.zymbot.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lol.zym.zymbot.ZymBotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * What the bot remembers between sessions, per server and per player:
 * {@code <game dir>/zymbot/memory/<server hash>/<player uuid>.json}.
 *
 * <p>Schema 3 keeps the {@code roster} and {@code recent_foods} fields of the earlier schema 2
 * files, so existing memory carries over.
 */
public final class BotMemory {
    public static final int SCHEMA = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Pos {
        public int x, y, z;
        public String dim;

        public Pos() {}

        public Pos(BlockPos p, String dim) {
            x = p.getX();
            y = p.getY();
            z = p.getZ();
            this.dim = dim;
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public static final class Station {
        public String type; // crafting_table, furnace, chest, bed
        public Pos at;
        public boolean ours;
    }

    public static final class Planting {
        public String crop; // block id, or null for a plot we know of but haven't planted
        public long at;     // level game time when planted
    }

    /** A team base, and what it's for ("wheat and potatoes", "mine"). */
    public static final class TeamHome {
        public Pos at;
        public String purpose;
    }

    /** One remembered place. */
    public static final class Landmark {
        public String kind; // water, wild:onions, ore:iron, berries, village, lootr, mark...
        public String name; // for marks
        public Pos at;
        public long seenMillis;
    }

    /** One container in the chest index. */
    public static final class ChestRecord {
        public Pos at;
        /** Item id -> count when last seen. */
        public Map<String, Integer> items = new LinkedHashMap<>();
        public long seenMillis;
        /** We placed it, or it's a team depot: ours to take from. */
        public boolean ours;
        /** A Lootr chest (per-player loot), fair game. */
        public boolean lootr;
        /** Next to villagers or someone else's build: look, don't take. */
        public boolean offLimits;
        /** Empty slots when last seen open; -1 if never counted. 0: full, a stash goes elsewhere. */
        public int free = -1;
    }

    public static final class RosterEntry {
        public String name;
        public long last_seen_millis;
        public int x, z;
        public String phase;
    }

    public int schema_version = SCHEMA;
    public Map<String, RosterEntry> roster = new LinkedHashMap<>();
    public List<String> recent_foods = new ArrayList<>();
    /** Seen on this server: does health come back on its own? Null until we know. */
    public Boolean naturalRegen;
    /** A campfire sits by our bed, so resting in it heals (CivFabric warmth). */
    public boolean bedWarm;
    public Pos home;
    /** The team's bases by name ("main", "wheat_plains"...). Only a leader or Claude adds or moves them. */
    public Map<String, TeamHome> teamHomes = new LinkedHashMap<>();
    /** Which base this bot belongs to; it stays within radio range of it. */
    public String homeName;
    public Pos bed;
    /**
     * CivFabric holds our claim on {@link #bed}: false once the server says we have none (null until
     * asked). Without a claim a death scatters us across the island instead of waking us at home.
     */
    public Boolean bedClaimed;
    public List<Pos> deaths = new ArrayList<>();
    /** Pokémon species (lower case) that have hurt us, mapped to when: they're not easy prey. */
    public Map<String, Long> fighters = new HashMap<>();
    /** Our graves still to be emptied, oldest first: they survive a restart and a second death. */
    public List<Pos> graves = new ArrayList<>();
    /**
     * Where lava or fire killed us, or burned us on the way to a grave. A grave near one isn't worth
     * going back for: Bot4 died at the same lava cave four times, each death leaving the next grave.
     */
    public List<Pos> hazards = new ArrayList<>();

    /** Within 6 blocks of somewhere lava or fire got us. */
    public boolean nearHazard(Pos p) {
        return hazards.stream().anyMatch(h -> h.dim.equals(p.dim) && h.pos().closerThan(p.pos(), 6));
    }

    public void addHazard(Pos p) {
        if (nearHazard(p)) return;
        hazards.add(p);
        while (hazards.size() > 20) hazards.remove(0);
    }
    /** The single grave older memories kept; moved into {@link #graves} on load. */
    public Pos grave;
    public List<Station> stations = new ArrayList<>();
    /** Block positions not to touch again (reinforced, unreachable), mapped to expiry millis. */
    public Map<Long, Long> blacklist = new HashMap<>();
    /** CivFabric refusals: "mine:minecraft:iron_ore" -> millis when refused. */
    public Map<String, Long> refusals = new HashMap<>();
    /** Highest rank the server has told us we lack, per class: "miner" -> 2 means rank < 2. */
    public Map<String, Integer> rankBelow = new HashMap<>();
    public Map<String, Long> stats = new LinkedHashMap<>();
    /** What was last planted on each field plot ("dim x y z" of the soil block), for soil rotation. */
    public Map<String, Planting> plantings = new HashMap<>();
    /** Role set at runtime by a "role" command; wins over the config. */
    public String role;
    /** CivFabric class XP as last read off the /class screen ("farmer" -> 812.5). Empty until read. */
    public Map<String, Double> classXp = new LinkedHashMap<>();
    public long classReadAt;
    /** Specialisation slots this server gives a player, once the class screen has shown it. */
    public Integer classSlots;
    public boolean drainArmed;
    /** Our class role (grower, smith, delver, scholar) and its classes; kept once chosen. */
    public String classRole;
    public List<String> classPlan = new ArrayList<>();
    /** One {@code /lunarforecast} reading, ours or someone else's, as it reached us. */
    public static final class Reading {
        public String reader;
        public long night;
        /** The moon it named, as the server prints it ("Super Blood Moon"); null when it sensed nothing. */
        public String moon;
        /** The confidence printed with it (0..1), or -1 if we weren't told. */
        public double confidence = -1;
        /** The reader's forecast rank (best of Librarian and Hunter), from the printed confidence; -1 unknown. */
        public int rank = -1;
        /** The rank a player said they had, which may be misremembered; -1 if not said. */
        public int statedRank = -1;
        /** "bot", or "player" for a player's report (told to a leader, or their pasted reading). */
        public String source;
        /** Who passed it on to us; null when it's ours or the reader told us. */
        public String via;
        public long readMillis;
        /** Another version of the same reader's night that also reached us. */
        public String conflict;
    }

    /** Forecast readings of coming (and, for players, recent) nights: "reader night" -> reading. */
    public Map<String, Reading> notebook = new LinkedHashMap<>();
    /** What each night turned out to be, as a guess (blood_moon...none), by night. */
    public Map<Long, String> moonOutcomes = new LinkedHashMap<>();

    /** How far a player's forecast reports are trusted: full (they count), show (listed only), ignore. */
    public static final class Trust {
        public String level;
        public String reason;
        public String by;
        public long at;
    }

    public Map<String, Trust> forecastTrust = new LinkedHashMap<>();
    /** Our {@code /guessforecast} for {@link #guessNight}: blood_moon/blue_moon/harvest_moon/none, the class and its XP if right. */
    public long guessNight = -1;
    public String guessMoon, guessClass;
    public double guessReward;
    /** Times we've changed the guess for {@link #guessNight}. */
    public int guessChanges;
    /** How the last guess turned out, as the server put it at sunset. */
    public String guessResult;

    /** Places worth coming back to (water, wild crops, ore, villages, named marks). */
    public Map<String, Landmark> landmarks = new LinkedHashMap<>();
    /** Containers we've looked inside: "dim x y z" -> what was there. */
    public Map<String, ChestRecord> chests = new LinkedHashMap<>();

    private transient Path file;

    public static BotMemory load(UUID player) {
        Path f = dir().resolve(player + ".json");
        BotMemory m = null;
        if (Files.exists(f)) {
            try {
                JsonObject raw = GSON.fromJson(Files.readString(f), JsonObject.class);
                m = GSON.fromJson(raw, BotMemory.class);
                m.schema_version = SCHEMA;
            } catch (Exception e) {
                ZymBotMod.LOG.warn("Memory file {} unreadable, starting fresh", f, e);
            }
        }
        if (m == null) m = new BotMemory();
        m.file = f;
        if (m.graves == null) m.graves = new ArrayList<>();
        if (m.grave != null) {
            m.graves.add(m.grave);
            m.grave = null;
        }
        long now = System.currentTimeMillis();
        m.blacklist.values().removeIf(exp -> exp < now);
        return m;
    }

    public void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            ZymBotMod.LOG.warn("Could not save memory to {}", file, e);
        }
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("zymbot").resolve("memory").resolve(serverKey());
    }

    /** Short stable id for the current server (address) or singleplayer world. */
    public static String serverKey() {
        Minecraft mc = Minecraft.getInstance();
        String key;
        ServerData sd = mc.getCurrentServer();
        // "host" and "host:25565" are the same server: one key, so bots joined either way share memory and the hub
        if (sd != null) key = sd.ip.contains(":") || sd.ip.startsWith("[") ? sd.ip : sd.ip + ":25565";
        else if (mc.getSingleplayerServer() != null) key = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else key = "unknown";
        for (var a : lol.zym.zymbot.BotConfig.get().serverAliases.entrySet()) {
            String from = a.getKey().contains(":") ? a.getKey() : a.getKey() + ":25565";
            if (from.equalsIgnoreCase(key)) key = a.getValue().contains(":") ? a.getValue() : a.getValue() + ":25565";
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    public void ateFood(String id) {
        recent_foods.add(id);
        while (recent_foods.size() > lol.zym.zymbot.inv.Diet.history()) recent_foods.remove(0);
    }

    public int timesEatenRecently(String id) {
        return (int) recent_foods.stream().filter(id::equals).count();
    }

    public void blacklist(BlockPos pos, long forMillis) {
        blacklist.put(pos.asLong(), System.currentTimeMillis() + forMillis);
    }

    public boolean blacklisted(BlockPos pos) {
        Long exp = blacklist.get(pos.asLong());
        return exp != null && exp > System.currentTimeMillis();
    }

    public Set<Long> blacklistKeys() {
        return blacklist.keySet();
    }

    public void addStation(String type, BlockPos pos, String dim, boolean ours) {
        stations.removeIf(s -> s.at.pos().equals(pos));
        Station s = new Station();
        s.type = type;
        s.at = new Pos(pos, dim);
        s.ours = ours;
        stations.add(s);
    }

    public void removeStation(BlockPos pos) {
        stations.removeIf(s -> s.at.pos().equals(pos));
    }

    public Optional<Station> nearestStation(String type, BlockPos from, String dim, double maxDist) {
        return stations.stream()
                .filter(s -> s.type.equals(type) && Objects.equals(s.at.dim, dim))
                .filter(s -> s.at.pos().distSqr(from) <= maxDist * maxDist)
                .min(Comparator.comparingDouble(s -> s.at.pos().distSqr(from)));
    }

    public static String plotKey(String dim, BlockPos soil) {
        return dim + " " + soil.getX() + " " + soil.getY() + " " + soil.getZ();
    }

    public Planting planting(String dim, BlockPos soil) {
        return plantings.get(plotKey(dim, soil));
    }

    public void planted(String dim, BlockPos soil, String crop, long gameTime) {
        Planting p = new Planting();
        p.crop = crop;
        p.at = gameTime;
        plantings.put(plotKey(dim, soil), p);
    }

    /** Forget plantings older than {@code maxAge} game ticks (a clock that went backwards counts as old). */
    public void prunePlantings(long gameTime, long maxAge) {
        plantings.values().removeIf(p -> p.crop != null && (gameTime - p.at > maxAge || p.at > gameTime));
        while (plantings.size() > 4000) plantings.remove(plantings.keySet().iterator().next());
    }

    public void count(String stat) {
        stats.merge(stat, 1L, Long::sum);
    }
}
