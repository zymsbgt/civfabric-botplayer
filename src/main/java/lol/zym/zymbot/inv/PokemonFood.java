package lol.zym.zymbot.inv;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lol.zym.zymbot.ZymBotMod;
import lol.zym.zymbot.brain.tasks.CatchPokemonTask;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Which wild Pokémon drop food when killed (Pidgey: chicken, Wooloo: mutton, Lechonk: porkchop,
 * Magikarp: salmon, most of them some berry). Read from Cobblemon's own species data, so it
 * follows whatever version the pack runs. Where animals are scarce they're most of the meat.
 */
public final class PokemonFood {
    private static Map<String, Set<Item>> bySpecies;
    /** Everything each species drops (wool, coal, string...), not just food. */
    private static Map<String, Set<Item>> allBySpecies;
    private static Set<Item> anyDrop;
    /** Species with willDefendSelf: hit one and it stays angry at us until one of us dies. */
    private static Set<String> fightsBack;

    private static synchronized void load() {
        if (bySpecies != null) return;
        bySpecies = new HashMap<>();
        allBySpecies = new HashMap<>();
        anyDrop = new HashSet<>();
        fightsBack = new HashSet<>();
        FabricLoader.getInstance().getModContainer("cobblemon").ifPresent(c -> {
            for (Path root : c.getRootPaths()) {
                Path dir = root.resolve("data/cobblemon/species");
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(f -> f.toString().endsWith(".json")).forEach(PokemonFood::read);
                } catch (Exception e) {
                    ZymBotMod.LOG.warn("Couldn't read Cobblemon species", e);
                }
            }
        });
        ZymBotMod.LOG.info("Pokémon food: {} species drop something edible; {} species fight back", bySpecies.size(), fightsBack.size());
    }

    private static void read(Path f) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (!o.has("name")) return;
            String name = o.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (o.has("behaviour") && o.getAsJsonObject("behaviour").has("combat")) {
                JsonObject combat = o.getAsJsonObject("behaviour").getAsJsonObject("combat");
                if (combat.has("willDefendSelf") && combat.get("willDefendSelf").getAsBoolean()) fightsBack.add(name);
            }
            if (!o.has("drops")) return;
            JsonObject drops = o.getAsJsonObject("drops");
            if (!drops.has("entries")) return;
            Set<Item> food = new HashSet<>(), all = new HashSet<>();
            for (JsonElement e : drops.getAsJsonArray("entries")) {
                JsonObject d = e.getAsJsonObject();
                if (!d.has("item")) continue;
                ResourceLocation id = ResourceLocation.tryParse(d.get("item").getAsString());
                if (id == null) continue;
                Item it = BuiltInRegistries.ITEM.get(id);
                if (it == Items.AIR) continue;
                all.add(it);
                if (it.components().has(DataComponents.FOOD)) food.add(it);
            }
            if (!food.isEmpty()) bySpecies.put(name, food);
            if (!all.isEmpty()) allBySpecies.put(name, all);
            anyDrop.addAll(all);
        } catch (Exception ignored) {
            // one odd file shouldn't cost us the rest
        }
    }

    /** What this Pokémon would drop that we could eat (empty if it isn't one, or drops none). */
    public static Set<Item> drops(Entity e) {
        if (!CatchPokemonTask.isPokemon(e)) return Set.of();
        load();
        return bySpecies.getOrDefault(e.getName().getString().toLowerCase(Locale.ROOT), Set.of());
    }

    /** Some species drops this (so hunting wild Pokémon is a way to get it). */
    public static boolean anyDrops(Item item) {
        load();
        return anyDrop.contains(item);
    }

    /** A wild Pokémon that would drop something matching. */
    public static boolean drops(Entity e, java.util.function.Predicate<net.minecraft.world.item.ItemStack> match) {
        if (!CatchPokemonTask.wild(e)) return false;
        load();
        for (Item it : allBySpecies.getOrDefault(e.getName().getString().toLowerCase(Locale.ROOT), Set.of()))
            if (match.test(new net.minecraft.world.item.ItemStack(it))) return true;
        return false;
    }

    /**
     * A species that retaliates (Combee, Taillow...). Cobblemon's anger at an attacker never
     * wears off: it chases until the attacker or it is dead, so running away doesn't end it.
     */
    public static boolean fightsBack(Entity e) {
        if (!CatchPokemonTask.isPokemon(e)) return false;
        load();
        return fightsBack.contains(e.getName().getString().toLowerCase(Locale.ROOT));
    }

    /** A wild Pokémon worth hunting for food. */
    public static boolean edible(Entity e) {
        return CatchPokemonTask.wild(e) && !drops(e).isEmpty();
    }
}
