package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.memory.ChestIndex;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Get to know the place: note the crafting tables and furnaces about, then look in each chest and
 * barrel we haven't indexed, nearest first. Looking only: whether we may take from them is
 * {@link ChestIndex}'s call, as for any chest. ZymSB asked Wandersong to learn where everything was
 * at its base; before this a bot knew a table only once it walked past and a chest once it used it.
 */
public class SurveyTask extends Task {
    private static final int RADIUS = 24, HEIGHT = 8;
    /** Block -> the station type it's remembered as ("furnace" is what SmeltTask looks for). */
    private static final Map<Block, String> STATIONS = Map.of(
            Blocks.CRAFTING_TABLE, "crafting_table", Blocks.FURNACE, "furnace",
            Blocks.SMOKER, "smoker", Blocks.BLAST_FURNACE, "blast_furnace");

    /** Block entities that are never a workstation (paths, or the suffix of one: "oak_sign" -> "sign"). */
    private static final java.util.Set<String> OTHER_IGNORED = java.util.Set.of("sign", "bed", "banner", "head", "skull",
            "hopper", "campfire", "soul_campfire", "bell", "lectern", "jukebox", "spawner", "beehive", "bee_nest", "chiseled_bookshelf",
            "decorated_pot", "conduit", "beacon", "ender_chest", "trapped_chest", "dispenser", "dropper", "comparator", "daylight_detector");

    private ArrayDeque<BlockPos> chests;
    private final Map<String, Integer> other = new java.util.HashMap<>();
    private final List<String> found = new ArrayList<>();
    private int looked, skipped, failed;

    @Override
    public String name() {
        return "Survey the area" + (chests == null ? "" : " (" + chests.size() + " chests to look in)");
    }

    @Override
    protected Status onTick(Bot bot) {
        if (chests == null) scan(bot);
        if (child != null) {
            Status s = runChild(bot);
            if (s == Status.FAILURE) failed++;
            else if (s == Status.SUCCESS) looked++;
            return Status.RUNNING;
        }
        BlockPos next = chests.pollFirst();
        if (next == null) {
            bot.event("survey done: " + String.join(", ", found) + "; looked in " + looked + " chest" + (looked == 1 ? "" : "s")
                    + (failed > 0 ? ", " + failed + " wouldn't open" : "") + (skipped > 0 ? ", left " + skipped + " by other people's builds alone" : ""));
            return Status.SUCCESS;
        }
        setChild(bot, new LookInTask(next));
        return Status.RUNNING;
    }

    private void scan(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        var mem = bot.memory();
        Map<String, Integer> counts = new java.util.TreeMap<>();
        List<BlockPos> toLook = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(me.offset(-RADIUS, -HEIGHT, -RADIUS), me.offset(RADIUS, HEIGHT, RADIUS))) {
            if (!WorldView.loaded(q)) continue;
            var st = bot.level().getBlockState(q);
            Block b = st.getBlock();
            String type = WorldView.craftingTable(st) ? "crafting_table" : STATIONS.get(b);
            if (type != null) {
                counts.merge(type.replace('_', ' '), 1, Integer::sum);
                BlockPos at = q.immutable();
                if (mem.stations.stream().noneMatch(s -> s.at.pos().equals(at))) mem.addStation(type, at, bot.dim(), false);
                continue;
            }
            if (!(b instanceof ChestBlock || b instanceof BarrelBlock)) {
                // anything else with a block entity may be a mod's workstation: list it, so a table we
                // don't know yet shows up (ZymSB: "look for modded crafting tables too")
                if (bot.level().getBlockEntity(q) != null && !OTHER_IGNORED.contains(BuiltInRegistries.BLOCK.getKey(b).getPath().replaceAll("^.*_(sign|bed|banner|head|skull)$", "$1")))
                    other.merge(BuiltInRegistries.BLOCK.getKey(b).toString(), 1, Integer::sum);
                continue;
            }
            counts.merge(b instanceof BarrelBlock ? "barrel" : "chest", 1, Integer::sum);
            // a double chest is one store, indexed under whichever half was opened
            BlockPos at = q.immutable();
            BlockPos twin = b instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                    ? at.relative(ChestBlock.getConnectedDirection(st)) : null;
            // Lootr's are loot, not anyone's store; known ones are indexed already
            if (BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("lootr") || ChestIndex.record(bot, at) != null
                    || twin != null && (ChestIndex.record(bot, twin) != null || toLook.contains(twin))) continue;
            if (bot.nearSomeoneElsesBuild(q)) {
                skipped++;
                continue;
            }
            toLook.add(at);
        }
        toLook.sort(Comparator.comparingDouble(q -> q.distSqr(me)));
        chests = new ArrayDeque<>(toLook);
        counts.forEach((k, v) -> found.add(v + " " + k + (v == 1 ? "" : "s")));
        if (found.isEmpty()) found.add("no stations or chests within " + RADIUS);
        if (!other.isEmpty()) {
            StringBuilder sb = new StringBuilder("other blocks with an inside: ");
            other.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                    .forEach(e -> sb.append(e.getValue()).append(" ").append(e.getKey().replace("minecraft:", "")).append(", "));
            bot.event(sb.substring(0, sb.length() - 2));
        }
        bot.event("surveying: " + String.join(", ", found) + "; " + chests.size() + " to look in");
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        lol.zym.zymbot.nav.Nav.stop();
    }
}
