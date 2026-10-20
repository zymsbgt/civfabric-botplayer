package lol.zym.zymbot.civ;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lol.zym.zymbot.ZymBotMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What every action is worth to which CivFabric class, and what a craft costs the stomach, read
 * from the tables the civfabric jar ships ({@code civfabric/xp/*.json}, {@code craft_hunger.json},
 * {@code classless_items.json}). The server uses the same files, so the bot's picture matches.
 */
public final class ClassBook {
    /** XP an action pays, and to whom. */
    public record Award(String clazz, double xp) {}

    /** Rank thresholds: Apprentice, Journeyman, Expert, Master, Grandmaster. */
    public static final double[] RANK_XP = {640, 2221, 5962, 14852, 36209};
    /** The drain arms once two classes each hold this much (85% of Apprentice). */
    public static final double DRAIN_ARMS_AT = 544;
    /** XP each specialisation loses per off-class point once the drain is armed. */
    public static final double DRAIN_PER_SPEC = 0.525;

    private static ClassBook instance;

    private final Map<String, Map<String, Award>> tables = new HashMap<>();
    private final Map<String, Double> hungerBase = new HashMap<>();
    private final Map<String, Integer> hungerFlat = new HashMap<>();
    private final Set<String> classlessItems = new HashSet<>();
    private final List<TagKey<Item>> classlessTags = new ArrayList<>();
    private final boolean present;

    public static synchronized ClassBook get() {
        if (instance == null) instance = new ClassBook();
        return instance;
    }

    private ClassBook() {
        var civ = FabricLoader.getInstance().getModContainer("civfabric");
        present = civ.isPresent();
        civ.ifPresent(c -> {
            for (String t : List.of("breaking", "crafting", "smelting", "smoking", "blasting", "placing")) {
                Map<String, Award> m = new HashMap<>();
                read(c.findPath("civfabric/xp/" + t + ".json")).ifPresent(o -> o.entrySet().forEach(e -> {
                    JsonObject v = e.getValue().getAsJsonObject();
                    if (v.has("class") && v.has("xp")) m.put(e.getKey(), new Award(v.get("class").getAsString(), v.get("xp").getAsDouble()));
                }));
                tables.put(t, m);
            }
            read(c.findPath("civfabric/craft_hunger.json")).ifPresent(o -> o.entrySet().forEach(e -> {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) hungerBase.put(e.getKey(), v.getAsDouble());
                else if (v.isJsonObject() && v.getAsJsonObject().has("flat")) hungerFlat.put(e.getKey(), v.getAsJsonObject().get("flat").getAsInt());
            }));
            read(c.findPath("civfabric/classless_items.json")).ifPresent(o -> {
                if (o.has("items")) o.getAsJsonArray("items").forEach(e -> classlessItems.add(e.getAsString()));
                if (o.has("tags")) o.getAsJsonArray("tags").forEach(e -> {
                    ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
                    if (id != null) classlessTags.add(TagKey.create(net.minecraft.core.registries.Registries.ITEM, id));
                });
            });
        });
        ZymBotMod.LOG.info("CivFabric class tables: {} breaking, {} crafting, {} smelting, {} placing awards; {} craft costs",
                size("breaking"), size("crafting"), size("smelting"), size("placing"), hungerBase.size() + hungerFlat.size());
    }

    private int size(String t) {
        return tables.getOrDefault(t, Map.of()).size();
    }

    private static Optional<JsonObject> read(Optional<Path> p) {
        try {
            if (p.isEmpty()) return Optional.empty();
            return Optional.of(JsonParser.parseString(Files.readString(p.get())).getAsJsonObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean present() {
        return present;
    }

    /** What doing {@code table} ("breaking", "crafting", "smelting"...) to {@code id} pays, or null. */
    public Award award(String table, String id) {
        return tables.getOrDefault(table, Map.of()).get(id);
    }

    /** Every action in a table that trains a class, best paying first. */
    public List<Map.Entry<String, Award>> awardsFor(String table, String clazz) {
        List<Map.Entry<String, Award>> out = new ArrayList<>();
        for (var e : tables.getOrDefault(table, Map.of()).entrySet()) if (e.getValue().clazz().equals(clazz)) out.add(e);
        out.sort((a, b) -> Double.compare(b.getValue().xp(), a.getValue().xp()));
        return out;
    }

    public static int rankOf(double xp) {
        int r = 0;
        while (r < RANK_XP.length && xp >= RANK_XP[r]) r++;
        return r;
    }

    /** Free while nobody's classes have reached Apprentice yet. */
    public boolean classlessFree(Item item) {
        if (classlessItems.contains(BuiltInRegistries.ITEM.getKey(item).toString())) return true;
        ItemStack s = new ItemStack(item);
        for (TagKey<Item> t : classlessTags) if (s.is(t)) return true;
        return false;
    }

    /**
     * Food points one craft of {@code item} costs, the way CivFabric's CraftingHunger works it out:
     * {@code max(1, base × (5 − rank/1.5))}, where the rank is the better of the class the craft
     * trains and 1.5× Blacksmith. Zero if it's free (classless, or a fancy meal running).
     */
    public int craftHunger(Item item, java.util.function.ToIntFunction<String> rank, boolean classless) {
        if (!present) return 0;
        if (classless && classlessFree(item)) return 0;
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        Integer flat = hungerFlat.get(id);
        if (flat != null) return flat;
        Award a = award("crafting", id);
        int own = a == null ? 0 : Math.max(0, rank.applyAsInt(a.clazz()));
        int smith = (int) (Math.max(0, rank.applyAsInt("blacksmith")) * 1.5);
        int paired = id.equals("minecraft:paper") ? Math.max(0, rank.applyAsInt("medic")) : 0;
        int r = Math.min(5, Math.max(Math.max(own, paired), smith));
        return (int) Math.max(1, baseCost(item, id) * (5 - r / 1.5));
    }

    private double baseCost(Item item, String id) {
        Double listed = hungerBase.get(id);
        if (listed != null) return listed;
        ItemStack s = new ItemStack(item);
        if (s.is(ItemTags.PLANKS)) return 0.25;
        if (s.is(ItemTags.TRAPDOORS) || s.is(ItemTags.WOODEN_PRESSURE_PLATES)) return 0.33;
        if (s.is(ItemTags.STAIRS) || s.is(ItemTags.SLABS) || s.is(ItemTags.FENCES) || s.is(ItemTags.FENCE_GATES)
                || s.is(ItemTags.WALLS) || s.is(ItemTags.BUTTONS) || s.is(ItemTags.SIGNS) || s.is(ItemTags.HANGING_SIGNS)
                || s.is(ItemTags.TERRACOTTA) || s.is(ItemTags.WOOL) || s.is(ItemTags.BANNERS)) return 0.5;
        String n = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
        double v = 1.0;
        if (n.contains("_HELMET") || n.contains("_BOOTS")) v += 0.5;
        else if (n.contains("_LEGGINGS") || n.contains("_CHESTPLATE")) v += 1.5;
        else if (n.contains("_AXE") || n.contains("_SWORD") || n.contains("_PICKAXE") || n.contains("_SHOVEL") || n.contains("_HOE")) v += 1.0;
        if (n.contains("WOODEN_")) return v * 0.35;
        if (n.contains("LEATHER_")) return v * 0.5;
        if (n.contains("STONE_")) return v * 0.75;
        if (n.contains("IRON_")) return v * 1.25;
        return n.contains("DIAMOND_") ? v * 2.0 : v;
    }
}
