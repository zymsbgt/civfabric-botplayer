package lol.zym.zymbot.farm;

import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.world.WorldView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Realistic Plant Growth's rules, as the server applies them to player-planted crops: each
 * crop grows only in some biomes (at a rate, with a chance to die each growth tick), dies
 * without open sky unless it is a dark-growing plant, and the same crop can't be replanted
 * on the same spot within {@code soil_rotation_days}.
 *
 * <p>Read from the mod's own config files in this instance ({@code config/realisticplantgrowth})
 * so the bot follows whatever the pack ships; falls back to a bundled copy. The lookup mirrors
 * the mod: the first of the crop's biome groups that contains the biome wins, then the
 * crop's Default biome list, otherwise the crop dies there.
 */
public final class PlantRules {
    public record Rate(double growth, double death) {
        static final Rate DEATH = new Rate(0, 100);

        public boolean dies() {
            return growth <= 0 || death >= 100;
        }
    }

    /** What happens to {@code crop} planted at a spot: ungoverned crops grow the vanilla way. */
    public record Judgement(boolean governed, Rate rate, String why) {
        public boolean grows() {
            return !governed || !rate.dies();
        }

        /** Rough value of planting here: growth pace, minus a lot for each % of death chance. */
        public double score() {
            return governed ? rate.growth - 8 * rate.death : 100;
        }
    }

    private record Plant(List<String> groupOrder, Map<String, Rate> byGroup, Rate fallback, boolean fallbackAll, Set<ResourceLocation> fallbackBiomes) {
        Rate forBiome(ResourceLocation biome, Map<String, Set<ResourceLocation>> groups) {
            for (String g : groupOrder) {
                Set<ResourceLocation> members = groups.get(g);
                if (members != null && members.contains(biome)) return byGroup.getOrDefault(g, Rate.DEATH);
            }
            if (fallback == null) return Rate.DEATH;
            return fallbackAll || fallbackBiomes.contains(biome) ? fallback : Rate.DEATH;
        }
    }

    private static PlantRules instance;

    private final Map<String, Set<ResourceLocation>> groups = new LinkedHashMap<>();
    private final Map<ResourceLocation, Plant> plants = new LinkedHashMap<>();
    private final Set<ResourceLocation> growInDark = new HashSet<>();
    private int minNaturalLight = 15;
    private boolean onlyPlayerPlanted = true;
    private int rotationDays = 5;
    private String source = "bundled";

    public static PlantRules get() {
        if (instance == null) instance = new PlantRules();
        return instance;
    }

    public static void reload() {
        instance = null;
    }

    /** Whether to apply the rules: "auto" means only when the mod is in this pack. */
    public static boolean active() {
        String mode = BotConfig.get().plantRules;
        if ("on".equalsIgnoreCase(mode)) return true;
        if ("off".equalsIgnoreCase(mode)) return false;
        return FabricLoader.getInstance().isModLoaded("realisticplantgrowth");
    }

    private PlantRules() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("realisticplantgrowth");
        if (Files.isRegularFile(dir.resolve("GrowthModifiers.yml"))) source = dir.toString();
        readSettings(load(dir, "Config.yml"));
        readGroups(load(dir, "BiomeGroups.yml"));
        readPlants(load(dir, "GrowthModifiers.yml"));
        ZymBotMod.LOG.info("Plant rules from {}: {} plants, {} biome groups, sky light {}, rotation {} days",
                source, plants.size(), groups.size(), minNaturalLight, rotationDays);
    }

    private static Map<String, Object> load(Path dir, String name) {
        try {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) return MiniYaml.parse(Files.readString(f));
            try (InputStream in = PlantRules.class.getResourceAsStream("/zymbot/plants/" + name)) {
                if (in != null) return MiniYaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            ZymBotMod.LOG.warn("Could not read plant rules {}", name, e);
        }
        return Map.of();
    }

    private void readSettings(Map<String, Object> m) {
        minNaturalLight = (int) num(m.get("min_natural_light"), 15);
        rotationDays = (int) num(m.get("soil_rotation_days"), 5);
        onlyPlayerPlanted = !"false".equalsIgnoreCase(String.valueOf(m.get("only_player_planted")));
        for (Object o : list(m.get("grow_in_dark"))) {
            ResourceLocation id = id(String.valueOf(o));
            if (id != null) growInDark.add(id);
        }
    }

    private void readGroups(Map<String, Object> m) {
        m.forEach((g, v) -> {
            Set<ResourceLocation> s = new HashSet<>();
            for (Object o : list(v)) {
                ResourceLocation id = id(String.valueOf(o));
                if (id != null) s.add(id);
            }
            groups.put(g, s);
        });
    }

    private void readPlants(Map<String, Object> m) {
        m.forEach((name, v) -> {
            ResourceLocation id = id(name);
            if (id == null || !(v instanceof Map<?, ?> body)) return;
            List<String> order = new ArrayList<>();
            Map<String, Rate> byGroup = new LinkedHashMap<>();
            if (body.get("BiomeGroup") instanceof Map<?, ?> bg) {
                for (Object g : list(bg.get("Groups"))) {
                    String gn = String.valueOf(g);
                    if (bg.get(gn) instanceof Map<?, ?> rates && !rates.isEmpty()) {
                        order.add(gn);
                        byGroup.put(gn, rate(rates));
                    }
                }
            }
            Rate fallback = null;
            boolean all = false;
            Set<ResourceLocation> biomes = new HashSet<>();
            if (body.get("Default") instanceof Map<?, ?> d) {
                if (d.containsKey("GrowthRate")) fallback = rate(d);
                for (Object b : list(d.get("Biome"))) {
                    if ("ALL".equalsIgnoreCase(String.valueOf(b))) all = true;
                    else {
                        ResourceLocation bid = id(String.valueOf(b));
                        if (bid != null) biomes.add(bid);
                    }
                }
            }
            plants.put(id, new Plant(order, byGroup, fallback, all, biomes));
        });
    }

    private static Rate rate(Map<?, ?> m) {
        return new Rate(num(m.get("GrowthRate"), 100), num(m.get("NaturalDeathChance"), 0));
    }

    private static double num(Object o, double dflt) {
        if (o == null) return dflt;
        try {
            return Double.parseDouble(String.valueOf(o).strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    /** "WHEAT" means minecraft:wheat; anything with a namespace is taken as written. */
    private static ResourceLocation id(String name) {
        String s = name.strip();
        if (s.isEmpty()) return null;
        return ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s.toLowerCase(Locale.ROOT));
    }

    // ---- queries ------------------------------------------------------------------------

    public long rotationTicks() {
        return rotationDays * 24000L;
    }

    public boolean onlyPlayerPlanted() {
        return onlyPlayerPlanted;
    }

    public boolean governs(Block crop) {
        return plants.containsKey(BuiltInRegistries.BLOCK.getKey(crop));
    }

    public static ResourceLocation biomeAt(BlockPos pos) {
        return WorldView.level().getBiome(pos).unwrapKey().map(k -> k.location()).orElse(null);
    }

    /** Biome groups the biome belongs to, in file order ("Temperate", ...). */
    public List<String> groupsOf(ResourceLocation biome) {
        List<String> out = new ArrayList<>();
        groups.forEach((g, s) -> {
            if (s.contains(biome)) out.add(g);
        });
        return out;
    }

    /** How {@code crop} would fare planted (by a player) at {@code cropPos}. */
    public Judgement judge(Block crop, BlockPos cropPos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(crop);
        Plant plant = plants.get(id);
        if (plant == null) return new Judgement(false, new Rate(100, 0), "not governed");
        if (!growInDark.contains(id) && WorldView.level().getBrightness(LightLayer.SKY, cropPos.above()) < minNaturalLight)
            return new Judgement(true, Rate.DEATH, "needs open sky");
        ResourceLocation biome = biomeAt(cropPos);
        if (biome == null) return new Judgement(false, new Rate(100, 0), "unknown biome");
        Rate r = plant.forBiome(biome, groups);
        String why = r.dies() ? "dies in " + biome.getPath()
                : String.format(Locale.ROOT, "%.0f%% pace, %.2f%% death in %s", r.growth, r.death, biome.getPath());
        return new Judgement(true, r, why);
    }

    /** Every governed plant's rate in a biome, best first: what grows here. */
    public List<String> growingIn(ResourceLocation biome) {
        List<Map.Entry<ResourceLocation, Rate>> ok = new ArrayList<>();
        plants.forEach((id, p) -> {
            Rate r = p.forBiome(biome, groups);
            if (!r.dies()) ok.add(Map.entry(id, r));
        });
        ok.sort(Comparator.comparingDouble((Map.Entry<ResourceLocation, Rate> e) -> -(e.getValue().growth - 8 * e.getValue().death)));
        List<String> out = new ArrayList<>();
        for (var e : ok) {
            String n = e.getKey().getNamespace().equals("minecraft") ? e.getKey().getPath() : e.getKey().toString();
            out.add(String.format(Locale.ROOT, "%s %.0f%%", n, e.getValue().growth));
        }
        return out;
    }
}
