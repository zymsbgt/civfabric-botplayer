package lol.zym.zymbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings stored in config/zymbot.json. Every field has a safe default so a missing or
 * partial file still works; the file is rewritten on load so new fields show up in it.
 */
public final class BotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("zymbot.json");

    /** Start playing as soon as the world loads, without pressing the toggle key. */
    public boolean autoStart = false;

    /**
     * "worker": plays on its own (progression) and takes jobs from teammates.
     * "leader": the bot Claude reaches the team through. It works on its own like a worker; a job
     * Claude gives it comes first, and it passes the help calls it hears on to Claude.
     */
    public String role = "worker";

    /** Per-player role overrides, for several bots sharing one instance: {"ZymLead": "leader"}. */
    public Map<String, String> roles = new LinkedHashMap<>();

    /**
     * Human players whose chat commands are obeyed ("zb follow", "zb Bot3 gather oak_log 64").
     * Every bot on the team bus is a teammate automatically.
     */
    public List<String> teammates = new ArrayList<>();

    /** Chat prefixes that address bots, besides each bot's own name. "all"/"bots" address everyone. */
    public List<String> chatPrefixes = new ArrayList<>(List.of("zb", "zymbot"));

    /** Answer chat commands in chat. ZymLabs local chat only reaches 150 blocks, so it stays local. */
    public boolean replyInChat = false;

    /**
     * Team hub every bot connects to (hub/zymbot-hub.mjs), on this machine or another. Several,
     * comma-separated, are tried in turn until one answers (a machine that moves between networks).
     * "off" for none: no boards or updates then (the team talks over chat either way).
     */
    public String hubUrl = "http://127.0.0.1:25599";

    /** Shared secret; must match ZYMBOT_HUB_TOKEN on the hub. */
    public String hubToken = "";

    /**
     * Take new versions of this mod from the hub: checked at startup, every half hour and on the
     * "update" command. A new jar is downloaded beside this one, checked against the hub's SHA-256,
     * and swapped in once the game has closed, so it runs from the next launch. Off by default: it
     * runs whatever jar the hub serves, so only turn it on for a hub of your own, behind its token.
     */
    public boolean autoUpdate = false;

    /**
     * A server we know that has moved: {@code "<new host:port>": "<old host:port>"}. Memory, the
     * team and the job boards are kept per server address, so without this a move starts every bot
     * over (no home, no bed, no chests) on a team of its own.
     */
    public Map<String, String> serverAliases = new LinkedHashMap<>();

    /**
     * Bots talk to teammates over server chat only (commands, status, forecasts, food, chests): chat
     * reaches 150 blocks on this server, the team's own range. The hub is for job boards, updates and
     * Claude's view of a leader. Players nearby see the lines.
     *
     * How often the status line goes out on chat; a teammate quiet for three of these is out of range. */
    public int chatBeaconSeconds = 60;

    /**
     * Radio range shown in messages. The hub enforces the real limit (ZYMBOT_RANGE, default 150,
     * matching ZymLabs local chat): bots further apart, or in different dimensions, can't talk.
     */
    public int commsRange = 150;

    /** Workers check job boards in range and take posts they can fulfil, once basic gear is done. */
    public boolean takeBoardJobs = true;

    /** When stuck on a milestone (e.g. gated by CivFabric class), ask for the item on a nearby board. */
    public boolean postRequests = true;

    /** Board jobs: deliver and report after every this many items, so progress survives a death or a quit. */
    public int boardBatch = 16;

    /** Board jobs: most of one post a bot claims at once (capped at a stack), leaving the rest for teammates. */
    public int boardShare = 64;

    /** Reconnect to the last server 30 s after being disconnected. */
    public boolean autoReconnect = true;

    /** Break natural blocks that are in the way while pathing. */
    public boolean breakWhilePathing = true;

    /** Pillar up and bridge with throwaway blocks while pathing. */
    public boolean placeWhilePathing = true;

    /** Longest drop the pathfinder will plan (blocks). 3 never hurts. */
    public int maxFall = 3;

    /**
     * How blocks get gathered. Both let Baritone walk, pick tools and break.
     * "targeted": the bot picks each block (natural only, away from others' builds, skipping
     *   blacklisted/reinforced spots) and Baritone breaks exactly that one. Civ-safe default.
     * "baritone-mine": Baritone's own mine process finds and mines veins itself. Faster, but it
     *   can't keep the buffer around other people's builds.
     * "auto" (default): baritone-mine for natural bulk blocks (logs, stone, ore, gravel, sand)
     *   out in the wilderness, with no build within ~50 blocks; targeted near any build, and it
     *   switches to targeted mid-run if one comes within 10 blocks.
     */
    public String gatherMode = "auto";

    /** Search radius for resources, in blocks. */
    public int searchRadius = 48;

    /**
     * Sprint everywhere. Off by default: on ZymLabs/CivFabric sprinting burns about 10x the hunger
     * of walking, so bots only sprint to get away from something.
     */
    public boolean sprint = false;

    /** Never break blocks within this many blocks of things that look built (planks, glass, chests...). */
    public int builtAreaBuffer = 4;

    /** Never kill animals with fences nearby (likely someone's pen). */
    public boolean spareFencedAnimals = true;

    /** When knocked out (CivFabric DBNO), wait this long for a revive before /giveup. -1 never gives up. */
    public int giveUpDownedSeconds = 90;

    /** Throw Poké Balls at wild Pokémon when the bot has some. */
    public boolean catchPokemon = true;

    /**
     * Classes whose bot plays it safe: runs sooner, rests sooner, spends nights at home and levels
     * from the base. The team's Blacksmith by default: stone tools wait on its rank, and a death
     * halves it (Bot2 fell from Apprentice back to Novice and 18 tool requests waited).
     */
    public List<String> carefulClasses = new ArrayList<>(List.of("blacksmith"));

    /** Health (half hearts) at which the bot stops fighting and runs. */
    public int fleeHealth = 7;

    /** Sleep at night when a bed is known. */
    public boolean sleep = true;

    /** Pick up and keep the crafting table/furnace the bot places for itself. */
    public boolean reclaimStations = true;

    /**
     * Fallback class ranks if the bot can't learn them from the server (0 Novice .. 5 Grandmaster).
     * Keys: miner, builder, farmer, blacksmith, medic, librarian, hunter.
     */
    public Map<String, Integer> classRanks = new LinkedHashMap<>();
    /** Specialisation slots per player on this server; 0 = read it off /class (assume 3 until then). */
    public int classSlots = 0;
    /** Pin this bot's classes (e.g. ["farmer","medic","hunter"]); empty = take a team role. */
    public List<String> classes = new ArrayList<>();

    /**
     * Realistic Plant Growth's biome, sky-light and soil-rotation rules when farming: "auto"
     * applies them when the mod is in this pack, "on"/"off" force it.
     */
    public String plantRules = "auto";

    /**
     * Screen gamma while the bot is running, so you can see what it does at night or underground
     * (the slider's "Bright" is 1.0; 0 leaves your setting alone). Paused, it's your own setting.
     */
    public double taskGamma = 8.0;

    /** Item ids the bot never throws away when its inventory is full. */
    public List<String> keepItems = new ArrayList<>(List.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:torch", "minecraft:white_bed"));

    private static BotConfig instance;

    public static BotConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        BotConfig cfg = null;
        unreadable = false;
        if (Files.exists(FILE)) {
            try {
                cfg = GSON.fromJson(Files.readString(FILE), BotConfig.class);
            } catch (Exception e) {
                unreadable = true;
                ZymBotMod.LOG.error("config/zymbot.json could not be read (a JSON mistake?), using defaults until it's fixed; "
                        + "the file is left as it is", e);
            }
        }
        instance = cfg != null ? cfg : new BotConfig();
        save();
    }

    /**
     * The file had a mistake in it: keep it for its owner to fix. Saving the defaults over a file
     * with one typo in it threw away the hub address and token typed into it, and nothing said why
     * the bot then couldn't find its team.
     */
    private static boolean unreadable;

    public static void save() {
        if (unreadable) return;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (IOException e) {
            ZymBotMod.LOG.warn("config/zymbot.json could not be written", e);
        }
    }
}
