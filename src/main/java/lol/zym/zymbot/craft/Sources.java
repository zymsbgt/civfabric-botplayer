package lol.zym.zymbot.craft;

import lol.zym.zymbot.world.BlockRules;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Where raw items come from. The client has no loot tables, so this is a best guess:
 * explicit vanilla knowledge, an ore-name heuristic that also covers modded ores
 * ("deepslate_tin_ore" -> "raw_tin"), and "natural blocks drop themselves" for the rest.
 */
public final class Sources {
    private static Map<Item, List<Block>> blockIndex;
    private static final Map<Item, List<EntityType<?>>> MOBS = new HashMap<>();

    static {
        mob(Items.BEEF, EntityType.COW, EntityType.MOOSHROOM);
        mob(Items.LEATHER, EntityType.COW, EntityType.MOOSHROOM, EntityType.HORSE);
        mob(Items.PORKCHOP, EntityType.PIG);
        mob(Items.CHICKEN, EntityType.CHICKEN);
        mob(Items.FEATHER, EntityType.CHICKEN);
        mob(Items.MUTTON, EntityType.SHEEP);
        mob(Items.WHITE_WOOL, EntityType.SHEEP);
        mob(Items.RABBIT, EntityType.RABBIT);
        mob(Items.RABBIT_HIDE, EntityType.RABBIT);
        mob(Items.COD, EntityType.COD);
        mob(Items.SALMON, EntityType.SALMON);
        mob(Items.INK_SAC, EntityType.SQUID);
        mob(Items.STRING, EntityType.SPIDER);
        mob(Items.BONE, EntityType.SKELETON);
        mob(Items.GUNPOWDER, EntityType.CREEPER);
    }

    private static void mob(Item item, EntityType<?>... types) {
        MOBS.computeIfAbsent(item, k -> new ArrayList<>()).addAll(List.of(types));
    }

    private Sources() {}

    public static List<EntityType<?>> mobsFor(Item item) {
        return MOBS.getOrDefault(item, List.of());
    }

    public static List<Block> blocksFor(Item item) {
        if (blockIndex == null) build();
        return blockIndex.getOrDefault(item, List.of());
    }

    /** Rebuild after tags change (joining a server). */
    public static void invalidate() {
        blockIndex = null;
    }

    private static void build() {
        Map<Item, List<Block>> idx = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            if (!BlockRules.natural(s)) continue;
            for (Item drop : dropsOf(b, s)) {
                if (drop != Items.AIR) idx.computeIfAbsent(drop, k -> new ArrayList<>()).add(b);
            }
        }
        // leaves are natural but drop saplings/apples/sticks, not themselves
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!b.defaultBlockState().is(BlockTags.LEAVES)) continue;
            idx.computeIfAbsent(Items.STICK, k -> new ArrayList<>()).add(b);
            if (b == Blocks.OAK_LEAVES || b == Blocks.DARK_OAK_LEAVES)
                idx.computeIfAbsent(Items.APPLE, k -> new ArrayList<>()).add(b);
        }
        blockIndex = idx;
    }

    private static Map<Block, List<Item>> modLoot;

    /**
     * Mod blocks' own loot tables, read from their jars (they ship them, and they're on the
     * client's classpath even though the server is what rolls them): a Farmer's Delight
     * wild_potatoes drops potatoes, a Vinery apple_leaves drops apples. Only the items, not the
     * odds. The block itself (a shears or silk-touch drop) is left out.
     */
    private static synchronized Map<Block, List<Item>> modLoot() {
        if (modLoot != null) return modLoot;
        Map<Block, List<Item>> out = new HashMap<>();
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String ns = mod.getMetadata().getId();
            if (ns.equals("minecraft") || ns.startsWith("fabric")) continue;
            for (java.nio.file.Path root : mod.getRootPaths()) {
                java.nio.file.Path data = root.resolve("data");
                if (!java.nio.file.Files.isDirectory(data)) continue;
                try (var nss = java.nio.file.Files.list(data)) {
                    for (java.nio.file.Path nsDir : nss.toList()) {
                        for (String folder : List.of("loot_table", "loot_tables")) {
                            java.nio.file.Path dir = nsDir.resolve(folder).resolve("blocks");
                            if (!java.nio.file.Files.isDirectory(dir)) continue;
                            try (var files = java.nio.file.Files.list(dir)) {
                                for (java.nio.file.Path f : files.toList()) {
                                    String name = f.getFileName().toString();
                                    if (!name.endsWith(".json")) continue;
                                    ResourceLocation id = ResourceLocation.tryParse(nsDir.getFileName().toString().replace("/", "")
                                            + ":" + name.substring(0, name.length() - 5));
                                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
                                    Block block = BuiltInRegistries.BLOCK.get(id);
                                    Set<Item> items = new LinkedHashSet<>();
                                    lootItems(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)), items);
                                    items.remove(block.asItem());
                                    items.remove(Items.AIR);
                                    if (!items.isEmpty()) out.put(block, new ArrayList<>(items));
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // a mod with odd data shouldn't cost us everyone else's
                }
            }
        }
        modLoot = out;
        lol.zym.zymbot.ZymBotMod.LOG.info("Mod loot tables: {} blocks with their own drops", out.size());
        return out;
    }

    /** What a mod block's loot table can drop (empty for vanilla blocks and blocks without one). */
    public static List<Item> lootOf(Block b) {
        return modLoot().getOrDefault(b, List.of());
    }

    private static void lootItems(com.google.gson.JsonElement e, Set<Item> acc) {
        if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").getAsString().equals("minecraft:item") && o.has("name")) {
                ResourceLocation id = ResourceLocation.tryParse(o.get("name").getAsString());
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) acc.add(BuiltInRegistries.ITEM.get(id));
            }
            for (var v : o.entrySet()) lootItems(v.getValue(), acc);
        } else if (e.isJsonArray()) {
            for (var v : e.getAsJsonArray()) lootItems(v, acc);
        }
    }

    private static List<Item> dropsOf(Block b, BlockState s) {
        List<Item> loot = BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft") ? null : modLoot().get(b);
        if (loot != null && !isOre(s)) return loot;
        if (b == Blocks.STONE) return List.of(Items.COBBLESTONE);
        if (b == Blocks.DEEPSLATE) return List.of(Items.COBBLED_DEEPSLATE);
        if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.DIRT_PATH)
            return List.of(Items.DIRT);
        if (b == Blocks.GRAVEL) return List.of(Items.GRAVEL, Items.FLINT);
        if (b == Blocks.CLAY) return List.of(Items.CLAY_BALL);
        if (b == Blocks.SNOW || b == Blocks.SNOW_BLOCK) return List.of(Items.SNOWBALL);
        if (b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN) return List.of(Items.WHEAT_SEEDS);
        if (b == Blocks.DEAD_BUSH) return List.of(Items.STICK);
        if (b == Blocks.GLOWSTONE) return List.of(Items.GLOWSTONE_DUST);
        if (b == Blocks.MELON) return List.of(Items.MELON_SLICE);
        if (s.is(BlockTags.LEAVES) || b == Blocks.ICE) return List.of();
        if (isOre(s)) return List.of(oreDrop(b));
        return List.of(b.asItem());
    }

    public static boolean isOre(BlockState s) {
        return s.is(ConventionalBlockTags.ORES) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES)
                || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.DIAMOND_ORES)
                || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.EMERALD_ORES);
    }

    /** "minecraft:deepslate_iron_ore" -> raw_iron; coal_ore -> coal; lapis_ore -> lapis_lazuli. */
    private static Item oreDrop(Block b) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        String base = id.getPath().replace("deepslate_", "").replace("nether_", "").replace("_ore", "");
        if (base.equals("lapis")) base = "lapis_lazuli";
        if (base.equals("quartz")) base = "quartz";
        if (base.equals("gold") && id.getPath().startsWith("nether_")) return Items.GOLD_NUGGET;
        for (String cand : new String[]{"raw_" + base, base}) {
            for (String ns : new String[]{id.getNamespace(), "minecraft"}) {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(ns, cand));
                if (it != Items.AIR) return it;
            }
        }
        return b.asItem();
    }
}
