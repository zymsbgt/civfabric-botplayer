package lol.zym.zymbot.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.tasks.*;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import lol.zym.zymbot.farm.PlantRules;

import java.util.*;

/**
 * The one command language, whether it arrives by chat from a teammate, by /zymbot from the
 * local player, or over the team bus from another bot or Claude.
 *
 * <p>Jobs replace the current job. Append "loop" to repeat a job when it finishes.
 */
public final class Commands {
    private Commands() {}

    /** Command words for tab completion (main names only, not aliases). */
    public static final List<String> WORDS = List.of("status", "inv", "milestones", "team", "help",
            "start", "pause", "stop", "role", "heal", "pacifist", "forecast", "campfire", "get", "give", "hunt", "follow", "come", "whereis", "errands", "deaths", "goto", "home",
            "sethome", "explore", "farm", "crops", "catch", "sleep", "skipnight", "settle", "say", "ask", "tell",
            "board", "post", "cancel", "blocks", "map", "navdebug", "receive", "classes", "phase", "chests", "level", "landmarks", "mark", "break", "teamhome", "grave", "drops", "cost");

    /** Likely second words after a command word, for tab completion. */
    public static final Map<String, List<String>> NEXT_WORDS = Map.of(
            "role", List.of("worker", "leader"),
            "board", List.of("create", "chest", "remove"),
            "tell", List.of("all"),
            "teamhome", List.of("set", "assign", "remove"),
            "classes", List.of("role", "refresh"),
            "forecast", List.of("readings", "players", "note", "trust"));

    public static final String HELP = String.join("\n",
            "status | inv | diet (what each food is worth now) | milestones | team | help",
            "start | pause | stop (clears job) | role worker|leader",
            "get <item> [n] | give <player> <item> [n] | hunt [animal] [n]",
            "follow [player] | come [player] | whereis <player> | goto x y z | goto x z | explore [dist]",
            "farm [radius] [crop] [minutes] | crops (what grows here) | catch | home | sethome | sleep | skipnight",
            "heal <player> (stitches/medkit/wound mush) | campfire (warm the bed) | pacifist (pacifism, regen and bed warmth)",
            "forecast [readings [night] | players [name]] (the pooled moon odds, our guess, readings, players' records)",
            "forecast note <player> <tonight|tomorrow|day> <moon|none> [confidence%] [rank] | forecast trust <player> full|show|ignore <reason>  (leaders)",
            "receive [seconds] (stand still and catch what a teammate throws; givers send it themselves)",
            "classes [a b c | role grower|smith|delver|scholar | refresh] | phase (food, farm or level) | chests (the chest index) | level [class]",
            "landmarks [kind] | mark <name> | goto <mark or kind> (water, wild:onions, ore:iron, village, lootr...) | break x y z",
            "teamhome | teamhome set <name> [x y z] [purpose] | assign <name> | remove <name>  (leaders only: the team's bases)",
            "say <text> | tell <bot|all> <command>  (bots within 150 blocks only)",
            "board | board create <name> | board chest | post <item> <n> [to <name>|chest] | cancel <post>",
            "update [check|status] (autoUpdate: install the hub's jar, closing the game; check only downloads it)",
            "append 'loop' to repeat a job");

    /** What grows where the bot stands, under Realistic Plant Growth, and how its own seeds would do. */
    static String crops(Bot bot) {
        BlockPos at = bot.player().blockPosition();
        ResourceLocation biome = PlantRules.biomeAt(at);
        if (biome == null) return "can't tell the biome here";
        PlantRules pr = PlantRules.get();
        StringBuilder sb = new StringBuilder(biome.getPath());
        List<String> groups = pr.groupsOf(biome);
        if (!groups.isEmpty()) sb.append(" (").append(String.join(", ", groups)).append(")");
        if (!PlantRules.active()) sb.append(" - plant rules are off (Realistic Plant Growth not in this pack)");
        List<String> grows = pr.growingIn(biome);
        sb.append(": ").append(grows.isEmpty() ? "nothing governed grows here" : String.join(", ", grows.subList(0, Math.min(grows.size(), 14))));
        List<String> mine = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        var inv = Inv.inv();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.BlockItem bi
                    && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock c && seen.add(c))
                mine.add(BuiltInRegistries.BLOCK.getKey(c).getPath() + " " + pr.judgeClimate(c, at).why());
        }
        if (!mine.isEmpty()) sb.append(". My seeds here: ").append(String.join("; ", mine));
        return sb.toString();
    }

    /** Run a command. {@code from} is who sent it (player name, bot name, "claude", "local"). */
    public static String execute(Bot bot, String from, String text) {
        String[] a = text.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return "?";
        boolean loop = a[a.length - 1].equalsIgnoreCase("loop");
        if (loop) a = Arrays.copyOf(a, a.length - 1);
        String cmd = a[0].toLowerCase(Locale.ROOT);
        try {
            switch (cmd) {
                case "help", "?" -> {
                    return HELP;
                }
                case "status" -> {
                    return bot.statusLine();
                }
                case "blocks" -> {
                    // debugging aid: what's around us, layer by layer (centre + 4 sides), feet-2 .. feet+10
                    BlockPos me = bot.player().blockPosition();
                    StringBuilder sb = new StringBuilder("at " + me.toShortString() + " inWater=" + bot.player().isInWater()
                            + " onGround=" + bot.player().onGround());
                    for (int dy = 10; dy >= -2; dy--) {
                        sb.append("\n").append(dy >= 0 ? "+" : "").append(dy).append(':');
                        for (BlockPos p : new BlockPos[]{me.offset(0, dy, 0), me.offset(1, dy, 0), me.offset(-1, dy, 0), me.offset(0, dy, 1), me.offset(0, dy, -1)}) {
                            sb.append(' ').append(BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(p).getBlock()).getPath());
                        }
                    }
                    return sb.toString();
                }
                case "navdebug" -> {
                    // debugging aid: Baritone's path calculations and failures, into the game log as [baritone]
                    boolean on = a.length < 2 || !a[1].equalsIgnoreCase("off");
                    baritone.api.BaritoneAPI.getSettings().chatDebug.value = on;
                    return "Baritone debug output " + (on ? "on (game log, [baritone])" : "off");
                }
                case "map" -> {
                    // debugging aid: a slice from above per layer, y-2 .. y+3, around a spot (default us).
                    // Rows run north to south (z), columns west to east (x)
                    BlockPos c = bot.player().blockPosition();
                    int r = 4;
                    try {
                        if (a.length >= 4) c = new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                        if (a.length == 2 || a.length == 5) r = Math.min(8, Integer.parseInt(a[a.length - 1]));
                    } catch (NumberFormatException e) {
                        return "map [x y z] [radius up to 8]";
                    }
                    BlockPos me = bot.player().blockPosition();
                    StringBuilder sb = new StringBuilder("map at " + c.toShortString() + " (# solid . air ~ water B bed C chest D door"
                            + " L ladder S stairs/slab T trapdoor F fence/wall c crop f farmland e eroded (TRMT) @ us)");
                    for (int dy = 3; dy >= -2; dy--) {
                        sb.append("\ny").append(c.getY() + dy).append(':');
                        for (int dz = -r; dz <= r; dz++) {
                            sb.append(dz == -r ? " " : "|");
                            for (int dx = -r; dx <= r; dx++) {
                                BlockPos q = c.offset(dx, dy, dz);
                                sb.append(q.equals(me) ? '@' : mapChar(bot.level().getBlockState(q)));
                            }
                        }
                    }
                    return sb.toString();
                }
                case "inv", "inventory" -> {
                    return bot.inventorySummary();
                }
                case "diet", "foods" -> {
                    // each food we carry: times in Spice of Fabric's recent meals, and the hunger it restores now
                    if (!lol.zym.zymbot.inv.Diet.active()) return "no Spice of Fabric: every food is worth its full value";
                    List<String> out = new ArrayList<>();
                    Set<net.minecraft.world.item.Item> seen = new HashSet<>();
                    for (int i = 0; i < 36; i++) {
                        ItemStack s = Inv.inv().getItem(i);
                        var f = Inv.food(s);
                        if (f == null || !seen.add(s.getItem())) continue;
                        out.add(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath() + " x" + Inv.count(s.getItem())
                                + ": eaten " + lol.zym.zymbot.inv.Diet.timesEaten(bot, s) + ", worth "
                                + Math.round(lol.zym.zymbot.inv.Diet.nutrition(bot, s)) + "/" + lol.zym.zymbot.inv.Diet.baseHunger(s)
                                + " (stack says " + f.nutrition() + ")"
                                + (Inv.riskyFood(s) ? " (risky)" : ""));
                    }
                    var plan = lol.zym.zymbot.inv.Diet.ifEatenFirst(bot);
                    String next = plan.entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(e -> "; next meal " + e.getKey().replace("minecraft:", "")).orElse("");
                    return "last " + lol.zym.zymbot.inv.Diet.history() + " meals; " + (out.isEmpty() ? "carrying no food" : String.join("; ", out))
                            + "; pack worth " + Math.round(lol.zym.zymbot.inv.Diet.stockValue(bot)) + " eaten in the best order" + next;
                }
                case "milestones", "progress" -> {
                    return String.join(", ", bot.brain().progression().status());
                }
                case "team" -> {
                    List<String> near = bot.inRadioRange();
                    return near.isEmpty() ? "no bots in radio range" : String.join(", ", near);
                }
                case "start", "resume", "on" -> {
                    bot.setEnabled(true);
                    return "running";
                }
                case "pause", "off" -> {
                    bot.setEnabled(false);
                    return "paused";
                }
                case "stop", "clear" -> {
                    bot.brain().clearJob();
                    int n = bot.brain().clearErrands(bot);
                    return "job cleared" + (n > 0 ? ", and " + n + " set aside" : "");
                }
                case "deaths" -> {
                    // what kills and knocks us out, and who's been near when we went down (who'd make a good Medic)
                    var m = bot.memory();
                    java.util.function.Function<Map<String, Integer>, String> top = t -> t.isEmpty() ? "none" : t.entrySet().stream()
                            .sorted((x, y) -> y.getValue() - x.getValue()).limit(8).map(e -> e.getKey() + " " + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", "));
                    return "died to: " + top.apply(m.deathCauses) + "; knocked out by: " + top.apply(m.downedCauses)
                            + "; near when knocked out (within " + (int) lol.zym.zymbot.memory.History.NEAR + "): " + top.apply(m.downedNear)
                            + "; history in " + m.historyFile().getFileName();
                }
                case "errands" -> {
                    // jobs set aside until a player turns up (or a more urgent errand is done)
                    List<String> es = bot.brain().errands();
                    return es.isEmpty() ? "nothing set aside" : String.join("; ", es);
                }
                case "role" -> {
                    Bot.Role r = Bot.Role.parse(a.length > 1 ? a[1] : "");
                    if (r == null) return "role worker|leader";
                    bot.memory().role = r.name().toLowerCase(Locale.ROOT);
                    bot.brain().stopAll(bot);
                    return "role " + r.name().toLowerCase(Locale.ROOT);
                }
                case "ask" -> {
                    // ask Claude (through a teammate in earshot, or the hub if we lead)
                    if (a.length < 2) return "ask <what you need help with>";
                    bot.askForHelp(text.trim().substring(4));
                    return "asked";
                }
                case "say" -> {
                    if (a.length < 2) return "say <text>";
                    bot.chat(text.trim().substring(4));
                    return "said";
                }
                case "tell" -> {
                    if (a.length < 3) return "tell <bot|all> <command>";
                    String rest = text.trim().split("\\s+", 3)[2];
                    return bot.radio(a[1], rest);
                }
                case "classes", "class" -> {
                    if (a.length == 1) return ClassPlan.describe(bot);
                    if (a[1].equalsIgnoreCase("refresh")) {
                        bot.classSheet().refresh(bot);
                        return "reading /class again";
                    }
                    if (a[1].equalsIgnoreCase("role") && a.length > 2) {
                        var r = ClassPlan.role(a[2]);
                        if (r == null) return "roles: grower, smith, delver, scholar";
                        bot.memory().classRole = r.name();
                        bot.memory().classPlan = new java.util.ArrayList<>(r.classes(lol.zym.zymbot.civ.ClassSheet.slots(bot)));
                        bot.memory().save();
                        return ClassPlan.describe(bot);
                    }
                    List<String> want = new java.util.ArrayList<>();
                    for (int i = 1; i < a.length; i++) want.add(a[i].toLowerCase(java.util.Locale.ROOT));
                    return ClassPlan.set(bot, want);
                }
                case "phase" -> {
                    var pr = bot.brain().progression();
                    return "phase " + pr.phase(bot) + "; farm " + Progression.farmPlots(bot) + " plots (team best "
                            + Progression.teamFarm(bot) + ", secure at " + Progression.FARM_PLOTS + "); food stock "
                            + Math.round(lol.zym.zymbot.inv.Diet.stockValue(bot)) + " in " + lol.zym.zymbot.inv.Diet.kinds() + " kinds"
                            + (bot.civ().present() ? "; crafting " + (lol.zym.zymbot.civ.ClassSheet.classless(bot) ? "free for basics (classless)" : "costs food") : "");
                }
                case "debugslot" -> {
                    // diagnostics: does a click on the player inventory apply, and does it stick?
                    int slotNo = a.length > 1 ? Integer.parseInt(a[1]) : 9;
                    var p = bot.player();
                    var menu = p.inventoryMenu;
                    String before = "menu " + p.containerMenu.getClass().getSimpleName() + "#" + p.containerMenu.containerId
                            + " state " + menu.getStateId() + " carried " + Inv.id(menu.getCarried().getItem())
                            + " slot" + slotNo + "=" + Inv.id(Inv.inv().getItem(slotNo).getItem()) + " hot8=" + Inv.id(Inv.inv().getItem(8).getItem());
                    int ms = Inv.menuSlot(menu, slotNo);
                    Inv.click(menu, ms, 8, net.minecraft.world.inventory.ClickType.SWAP);
                    String after = "slot" + slotNo + "=" + Inv.id(Inv.inv().getItem(slotNo).getItem()) + " hot8=" + Inv.id(Inv.inv().getItem(8).getItem())
                            + " menuSlot " + ms + " gameMode " + net.minecraft.client.Minecraft.getInstance().gameMode.getPlayerMode();
                    return "before: " + before + " | after click: " + after;
                }
                case "landmarks", "waypoints" -> {
                    String want = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
                    var me = bot.player().blockPosition();
                    var list = bot.memory().landmarks.values().stream()
                            .filter(l -> l.at != null && l.at.dim.equals(bot.dim()) && (want.isEmpty() || l.kind.startsWith(want) || want.equals(l.name)))
                            .sorted(java.util.Comparator.comparingDouble(l -> l.at.pos().distSqr(me))).limit(12).toList();
                    if (list.isEmpty()) return "no landmarks" + (want.isEmpty() ? " yet" : " of " + want);
                    StringBuilder sb = new StringBuilder();
                    for (var l : list) sb.append(l.name != null ? l.name : l.kind).append(" ").append(l.at.pos().toShortString())
                            .append(" (").append((int) Math.sqrt(l.at.pos().distSqr(me))).append("m) | ");
                    return sb.toString();
                }
                case "teamhome", "base" -> {
                    var mem = bot.memory();
                    if (a.length == 1) {
                        BlockPos t = ClassPlan.teamHome(bot);
                        if (mem.teamHomes.isEmpty()) return "no team bases yet (the first Grower's farm becomes \"main\")";
                        StringBuilder sb = new StringBuilder("bases: ");
                        mem.teamHomes.forEach((n, h) -> sb.append(n.equals(mem.homeName) ? "*" : "").append(n).append(" ")
                                .append(h.at.pos().toShortString()).append(h.purpose != null ? " (" + h.purpose + ")" : "").append(" | "));
                        if (t != null) sb.append((int) Math.sqrt(t.distSqr(bot.player().blockPosition()))).append("m from ours");
                        return sb.toString();
                    }
                    // setting bases is the leader's call (or Claude's): the team stays within radio range of them
                    if (!ClassPlan.mayDirect(bot, from)) return "only a leader decides where the team's bases are";
                    String op = a[1].toLowerCase(java.util.Locale.ROOT);
                    if (op.equals("set") && a.length >= 3) {
                        BlockPos at = bot.player().blockPosition();
                        int next = 3;
                        if (a.length >= 6) {
                            try {
                                at = new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
                                next = 6;
                            } catch (NumberFormatException ignored) {
                                // no coordinates: here
                            }
                        }
                        var h = new BotMemory.TeamHome();
                        h.at = new BotMemory.Pos(at, bot.dim());
                        h.purpose = a.length > next ? String.join(" ", java.util.Arrays.copyOfRange(a, next, a.length)) : null;
                        h.set = bot.level().getGameTime();
                        mem.teamHomes.put(a[2], h);
                        if (mem.homeName == null) mem.homeName = a[2];
                        mem.save();
                        return "base " + a[2] + " at " + at.toShortString() + (h.purpose != null ? " for " + h.purpose : "");
                    }
                    if (op.equals("assign") && a.length >= 3) {
                        if (!mem.teamHomes.containsKey(a[2])) return "no base called " + a[2] + " (teamhome set it first)";
                        mem.homeName = a[2];
                        mem.save();
                        return "now based at " + a[2] + " " + mem.teamHomes.get(a[2]).at.pos().toShortString();
                    }
                    if (op.equals("remove") && a.length >= 3) {
                        if (mem.teamHomes.remove(a[2]) == null) return "no base called " + a[2];
                        if (a[2].equals(mem.homeName)) mem.homeName = mem.teamHomes.isEmpty() ? null : mem.teamHomes.keySet().iterator().next();
                        mem.save();
                        return "removed base " + a[2];
                    }
                    return "teamhome | teamhome set <name> [x y z] [purpose] | teamhome assign <name> | teamhome remove <name>";
                }
                case "drops" -> {
                    // what's lying about within 16 blocks, and whether it's in water
                    var me = bot.player();
                    var items = bot.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, me.getBoundingBox().inflate(16));
                    if (items.isEmpty()) return "no drops within 16 blocks";
                    StringBuilder sb = new StringBuilder(items.size() + " drops: ");
                    for (var e : items.stream().limit(20).toList())
                        sb.append(e.getItem().getCount()).append(" ").append(Inv.id(e.getItem().getItem()).replace("minecraft:", ""))
                                .append(" @").append(e.blockPosition().toShortString()).append(e.isInWater() ? " (water)" : "").append(" | ");
                    return sb.toString();
                }
                case "cost" -> {
                    // what the planner thinks an item costs us, and how it would get it
                    if (a.length < 2) return "cost <item>";
                    ItemTarget t = ItemTarget.parse(a[1], 1);
                    if (t == null || t.candidates().isEmpty()) return "don't know " + a[1];
                    var it = t.candidates().get(0);
                    double c = bot.planner().cost(it);
                    return Inv.id(it) + ": " + (c >= lol.zym.zymbot.craft.Planner.INF ? "no known way" : String.format("%.0f", c));
                }
                case "grave" -> {
                    // point the bot at a grave of its own to empty (one it forgot), or list the ones it knows
                    if (a.length == 2 && a[1].equalsIgnoreCase("forget")) {
                        bot.forgetGraves();
                        return "leaving every grave where it is";
                    }
                    if (a.length < 4) {
                        if (bot.memory().graves.isEmpty()) return "no graves to recover";
                        StringBuilder sb = new StringBuilder("graves: ");
                        for (var g : bot.memory().graves) sb.append(g.pos().toShortString()).append(" | ");
                        return sb.toString();
                    }
                    bot.addGrave(new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                    return "will recover the grave at " + a[1] + " " + a[2] + " " + a[3] + " (" + bot.memory().graves.size() + " known)";
                }
                case "mark" -> {
                    if (a.length < 2) return "mark <name>: remember this spot by name";
                    lol.zym.zymbot.memory.Landmarks.remember(bot, "mark", bot.player().blockPosition(), a[1]);
                    bot.memory().save();
                    return "marked " + a[1] + " at " + bot.player().blockPosition().toShortString();
                }
                case "chests" -> {
                    var idx = bot.memory().chests;
                    if (idx.isEmpty()) return "no chests indexed yet";
                    StringBuilder sb = new StringBuilder();
                    for (var r : idx.values()) {
                        if (r.at == null) continue;
                        sb.append(r.at.pos().toShortString()).append(r.ours ? " ours" : r.lootr ? " lootr" : r.offLimits ? " not ours" : "")
                                .append(": ").append(r.items.size()).append(" kinds");
                        r.items.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(4)
                                .forEach(e -> sb.append(", ").append(e.getValue()).append(" ").append(e.getKey().replace("minecraft:", "")));
                        sb.append(" | ");
                    }
                    return sb.toString();
                }
                case "whereis" -> {
                    // where a player was last seen. A teammate looking for one sends the game time of
                    // what it knows already, and gets ours as a "seen" command if it's newer
                    if (a.length < 2) return "whereis <player> [game tick of what you know]";
                    var s = bot.memory().sighting(a[1]);
                    long after = a.length > 2 ? Long.parseLong(a[2]) : Long.MIN_VALUE;
                    if (s == null || s.tick <= after) return "haven't seen " + a[1] + (s == null ? "" : " since");
                    if (a.length > 2 && bot.team().member(from).isPresent())
                        bot.radio(from, "seen " + a[1] + " " + s.at.dim + " " + s.at.x + " " + s.at.y + " " + s.at.z + " " + s.tick);
                    return a[1] + " was at " + s.at.pos().toShortString() + " in " + s.at.dim + ", "
                            + (bot.level().getGameTime() - s.tick) / 1200 + " min ago"
                            + (s.by == null || s.by.equalsIgnoreCase(bot.name()) ? "" : " (" + s.by + " saw them)");
                }
                case "seen" -> {
                    // a teammate's answer to our "whereis"
                    if (a.length < 7) return "seen <player> <dim> <x> <y> <z> <game tick>";
                    boolean newer = bot.memory().saw(a[1], new BlockPos(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5])),
                            a[2], Long.parseLong(a[6]), from);
                    return newer ? "noted where " + a[1] + " was" : "already knew newer";
                }
                case "receive" -> {
                    if (!bot.enabled() || bot.brain().focused()) return "not now: " + (bot.enabled() ? "emptying my pack" : "paused");
                    int secs = a.length > 1 ? Math.max(1, Math.min(30, Integer.parseInt(a[1]))) : 8;
                    bot.receive(secs);
                    return "holding still " + secs + " s to catch what " + from + " throws";
                }
                case "pacifist", "peace" -> {
                    return "pacifism " + bot.pacifism().describe() + "; natural regeneration "
                            + (bot.civ().present() ? "off (none in this pack)" : bot.memory().naturalRegen == null ? "unknown (assumed " + (bot.health().naturalRegen(bot) ? "on" : "off") + ")" : bot.memory().naturalRegen ? "on" : "off")
                            + "; bed " + (bot.memory().bed == null ? "none" : (bot.memory().bedWarm ? "warm" : "cold (no campfire)")
                            + (Boolean.FALSE.equals(bot.memory().bedClaimed) ? ", not claimed" : ""));
                }
                case "forecast", "moon" -> {
                    return lol.zym.zymbot.civ.Forecast.command(bot, from, a);
                }
                case "update", "version" -> {
                    // update: take the hub's jar and close the game to install it; check: download only
                    if (cmd.equals("version") || a.length > 1 && a[1].equalsIgnoreCase("status"))
                        return lol.zym.zymbot.team.Updater.status();
                    if (!BotConfig.get().autoUpdate) return "autoUpdate is off in config/zymbot.json";
                    boolean quit = !(a.length > 1 && a[1].equalsIgnoreCase("check"));
                    lol.zym.zymbot.team.Updater.checkNow(quit);
                    return quit ? "asking the hub for a new version; if there is one I'll close the game to install it"
                            : "asking the hub for a new version; it installs when the game next closes";
                }
                case "sethome" -> {
                    bot.memory().home = new BotMemory.Pos(bot.player().blockPosition(), bot.dim());
                    return "home set at " + bot.player().blockPosition().toShortString();
                }
                case "board", "boards" -> {
                    return board(bot, a);
                }
                case "post", "request" -> {
                    // post <item> <n> [to <name|chest>] [on <board>]
                    if (a.length < 2) return "post <item> [n] [to <name|chest>] [on <board>]";
                    ItemTarget t = ItemTarget.parse(a[1], 1);
                    if (t == null) return "unknown item " + a[1];
                    int n = a.length > 2 && a[2].matches("\\d+") ? Integer.parseInt(a[2]) : 1;
                    String to = from, on = null;
                    for (int i = 2; i + 1 < a.length; i++) {
                        if (a[i].equalsIgnoreCase("to")) to = a[i + 1];
                        if (a[i].equalsIgnoreCase("on")) on = a[i + 1];
                    }
                    JsonObject b = pickBoard(bot, on);
                    if (b == null) return "no job board within range";
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "post");
                    op.addProperty("board", b.get("id").getAsString());
                    op.addProperty("item", Inv.id(t.candidates().get(0)));
                    op.addProperty("count", n);
                    op.addProperty("deliver_to", to.equalsIgnoreCase("me") ? from : to);
                    op.addProperty("note", "posted by " + from);
                    bot.team().boardOp(op, r -> report(bot, from, "post", r));
                    return "posting " + n + "x " + a[1] + " on " + b.get("name").getAsString();
                }
                case "cancel" -> {
                    if (a.length < 2) {
                        bot.brain().clearJob();
                        return "job cleared";
                    }
                    JsonObject b = boardWithPost(bot, a[1]);
                    if (b == null) return "no post " + a[1] + " on boards in range";
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "cancel");
                    op.addProperty("board", b.get("id").getAsString());
                    op.addProperty("post", a[1]);
                    bot.team().boardOp(op, r -> report(bot, from, "cancel", r));
                    return "cancelling #" + a[1];
                }
                case "crops", "plants" -> {
                    return crops(bot);
                }
                case "skipnight" -> {
                    bot.brain().skipNight();
                    return "won't sleep tonight";
                }
                case "campfire", "warmbed" -> {
                    if (bot.memory().bed == null) return "no bed yet: settle first";
                }
                case "heal", "treat" -> {
                    String who = a.length > 1 ? a[1] : from;
                    if (build(bot, from, a) == null) return ClassPlan.isMedic(bot) ? "nothing to heal " + who + " with"
                            : "not a medic, and no wound mush, healing food or potion, or spare bed for " + who;
                    if (who == null || bot.level().players().stream().noneMatch(o -> o.getGameProfile().getName().equalsIgnoreCase(who) && o != bot.player()))
                        return "can't see " + who + " to treat";
                }
                default -> {
                }
            }
            Task job = build(bot, from, a);
            if (job == null) return "unknown command '" + cmd + "' (try help)";
            // a teammate's errand waits while we empty the pack; a player or Claude may still redirect us
            if (bot.brain().focused() && bot.isTeammate(from)) return "not now: emptying my pack";
            bot.brain().assign(job, from, loop ? String.join(" ", a) : null);
            bot.setEnabled(true);
            return "ok: " + job.name();
        } catch (NumberFormatException e) {
            return "bad number in '" + text + "'";
        } catch (RuntimeException e) {
            lol.zym.zymbot.ZymBotMod.LOG.warn("Command '{}' failed", text, e);
            return "error running '" + text + "': " + e.getMessage();
        }
    }

    /** Build a job task from command words, or null if it isn't a job command. */
    public static Task build(Bot bot, String from, String[] a) {
        String cmd = a[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "get", "gather", "obtain", "make", "craft" -> {
                if (a.length < 2) return null;
                ItemTarget t = ItemTarget.parse(a[1], 1);
                if (t == null) return null;
                int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                return new ObtainTask(t.withCount(t.have() + n));
            }
            case "feed" -> {
                // a hungry teammate: half our biggest pile of safe food, 8 at most, so we keep some
                if (a.length < 2) return null;
                Map<net.minecraft.world.item.Item, Integer> piles = new HashMap<>();
                for (int i = 0; i < 36; i++) {
                    var s = Inv.inv().getItem(i);
                    if (Inv.isFood(s) && !Inv.riskyFood(s)) piles.merge(s.getItem(), s.getCount(), Integer::sum);
                }
                var best = piles.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
                if (best == null) return null;
                int n = Math.min(8, (best.getValue() + 1) / 2);
                return new GiveTask(a[1].equalsIgnoreCase("me") ? from : a[1], ItemTarget.of(best.getKey(), n));
            }
            case "give", "bring" -> {
                if (a.length < 3) return null;
                ItemTarget t = ItemTarget.parse(a[2], 1);
                if (t == null) return null;
                return new GiveTask(a[1].equalsIgnoreCase("me") ? from : a[1], t.withCount(a.length > 3 ? Integer.parseInt(a[3]) : 1));
            }
            case "hunt" -> {
                List<EntityType<?>> types = new ArrayList<>();
                if (a.length > 1 && !a[1].matches("\\d+")) {
                    ResourceLocation id = ResourceLocation.tryParse(a[1].contains(":") ? a[1] : "minecraft:" + a[1]);
                    EntityType<?> t = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                    if (t == null) return null;
                    types.add(t);
                } else {
                    types.addAll(List.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN));
                }
                int n = a.length > 2 ? Integer.parseInt(a[2]) : a.length > 1 && a[1].matches("\\d+") ? Integer.parseInt(a[1]) : 5;
                int before = Inv.count(Commands::isMobDrop);
                return new HuntTask(types, Commands::isMobDrop, before + n);
            }
            case "follow" -> {
                return new FollowTask(a.length > 1 ? a[1] : from);
            }
            case "come" -> {
                String who = a.length > 1 ? a[1] : from;
                Player p = bot.findPlayer(who);
                BlockPos at = p != null ? p.blockPosition() : bot.lastKnownPosition(who);
                // nowhere in sight or radio range: where they were last seen, asking teammates on the way
                return at == null ? new SeekTask(who, 3) : new GoToTask(at, 2);
            }
            case "goto" -> {
                if (a.length == 2) {
                    var l = lol.zym.zymbot.memory.Landmarks.named(bot, a[1]);
                    if (l == null) l = lol.zym.zymbot.memory.Landmarks.nearest(bot, k -> k.startsWith(a[1].toLowerCase(java.util.Locale.ROOT)), 1000);
                    return l == null ? null : new GoToTask(l.at.pos(), 2);
                }
                if (a.length == 4) return new GoToTask(new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])), 1);
                if (a.length == 3) {
                    int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                    return new GoToTask(new BlockPos(x, bot.player().getBlockY(), z), 2) {
                        @Override
                        protected Status onTick(Bot b) {
                            BlockPos me = b.player().blockPosition();
                            if (Math.abs(me.getX() - x) <= 2 && Math.abs(me.getZ() - z) <= 2) return Status.SUCCESS;
                            lol.zym.zymbot.nav.Nav.goXZ(x, z);
                            return ticks > 20 * 600 ? fail("took too long") : Status.RUNNING;
                        }
                    };
                }
                return null;
            }
            case "break" -> {
                // break the block at x y z with Baritone (it walks there and picks the tool)
                if (a.length < 4) return null;
                BlockPos at = new BlockPos(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                return new Task() {
                    private final lol.zym.zymbot.control.Breaker breaker = new lol.zym.zymbot.control.Breaker(at);

                    @Override
                    public String name() {
                        return "Break " + at.toShortString();
                    }

                    @Override
                    protected Status onTick(Bot b) {
                        return switch (breaker.tick()) {
                            case DONE -> Status.SUCCESS;
                            case RUNNING -> Status.RUNNING;
                            default -> fail("couldn't break it (reinforced or out of reach)");
                        };
                    }

                    @Override
                    public void stop(Bot b) {
                        super.stop(b);
                        breaker.cancel();
                    }
                };
            }
            case "level" -> {
                String c = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : ClassPlan.nextToLevel(bot);
                return c == null || !lol.zym.zymbot.civ.CivRules.CLASSES.contains(c) ? null : new LevelTask(c);
            }
            case "campfire", "warmbed" -> {
                return bot.memory().bed == null ? null : new WarmBedTask();
            }
            case "heal", "treat" -> {
                // heal <player> [bed]: a teammate is hurt. A Medic treats; anyone may apply wound mush,
                // or hand over a healing food or potion, or a spare bed if they've none
                String who = a.length > 1 ? a[1] : from;
                var target = bot.level().players().stream().filter(o -> o.getGameProfile().getName().equalsIgnoreCase(who)).findFirst().orElse(null);
                if (target == null || target == bot.player()) return null;
                if (TreatTask.treatmentSlot(bot) >= 0) return new TreatTask(target);
                int slot = Inv.find(Inv::healingItem);
                if (slot >= 0) return new GiveTask(who, ItemTarget.of(Inv.inv().getItem(slot).getItem(), 1));
                boolean wantsBed = a.length > 2 && a[2].equalsIgnoreCase("bed");
                int bed = Inv.find(s -> s.is(net.minecraft.tags.ItemTags.BEDS));
                if (wantsBed && bed >= 0 && bot.memory().bed != null) return new GiveTask(who, ItemTarget.of(Inv.inv().getItem(bed).getItem(), 1));
                return null;
            }
            case "loot" -> {
                // a player's word: empty a grave that isn't a teammate's (the nearest, or the one at x y z)
                if (a.length < 2 || !a[1].equalsIgnoreCase("grave")) return null;
                if (a.length >= 5)
                    return new LootGraveTask(new BlockPos(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])));
                var near = lol.zym.zymbot.world.GraveFinder.near(bot, 32);
                return near.isEmpty() ? null : new LootGraveTask(near.get(0).pos());
            }
            case "stash", "store" -> {
                return a.length > 1 && a[1].equalsIgnoreCase("all") ? new EmptyPackTask() : null;
            }
            case "home" -> {
                BotMemory.Pos h = bot.memory().home != null ? bot.memory().home : bot.memory().bed;
                return h == null ? null : new GoToTask(h.pos(), 2);
            }
            case "explore" -> {
                return new ExploreTask(a.length > 1 ? Integer.parseInt(a[1]) : 96);
            }
            case "farm" -> {
                int r = 12, minutes = 10;
                Block crop = null;
                List<Integer> nums = new ArrayList<>();
                for (int i = 1; i < a.length; i++) {
                    if (a[i].matches("\\d+")) nums.add(Integer.parseInt(a[i]));
                    else if ((crop = FarmTask.cropNamed(a[i])) == null) throw new IllegalArgumentException("unknown crop " + a[i]);
                }
                if (nums.size() > 0) r = Math.min(nums.get(0), 32);
                if (nums.size() > 1) minutes = nums.get(1);
                Player p = bot.findPlayer(from);
                BlockPos c = p != null && p.distanceTo(bot.player()) < 32 ? p.blockPosition() : bot.player().blockPosition();
                return new FarmTask(c, r, minutes, crop);
            }
            case "catch" -> {
                return new CatchPokemonTask();
            }
            case "sleep" -> {
                // "sleep claim": just click it, which claims it at any hour (a claimed bed is our respawn point)
                boolean claim = a.length > 1 && a[1].equalsIgnoreCase("claim");
                return bot.memory().bed == null ? null : new SleepTask(bot.memory().bed.pos(), claim);
            }
            case "settle" -> {
                return new SettleTask();
            }
            default -> {
                return null;
            }
        }
    }

    /** One character per block for the "map" command. */
    private static char mapChar(net.minecraft.world.level.block.state.BlockState s) {
        var b = s.getBlock();
        if (s.isAir()) return '.';
        if (s.is(net.minecraft.tags.BlockTags.BEDS)) return 'B';
        if (b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock) return 'C';
        if (s.is(net.minecraft.tags.BlockTags.DOORS)) return 'D';
        if (s.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return 'L';
        if (s.is(net.minecraft.tags.BlockTags.STAIRS) || s.is(net.minecraft.tags.BlockTags.SLABS)) return 'S';
        if (s.is(net.minecraft.tags.BlockTags.TRAPDOORS)) return 'T';
        if (s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS) || s.is(net.minecraft.tags.BlockTags.FENCE_GATES)) return 'F';
        if (b instanceof net.minecraft.world.level.block.FarmBlock) return 'f';
        if (BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("trmt")) return 'e';
        if (b instanceof net.minecraft.world.level.block.CropBlock) return 'c';
        if (!s.getFluidState().isEmpty()) return '~';
        return s.blocksMotion() ? '#' : ',';
    }

    private static String board(Bot bot, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "create", "new" -> {
                JsonObject op = new JsonObject();
                op.addProperty("op", "create");
                op.addProperty("name", a.length > 2 ? a[2] : bot.name() + "-board");
                JsonObject chest = nearestChest(bot);
                if (chest != null) op.add("chest", chest);
                bot.team().boardOp(op, r -> report(bot, null, "board create", r));
                return "creating board here" + (chest != null ? " with depot chest" : " (no chest nearby; use 'board chest' later)");
            }
            case "chest" -> {
                JsonObject b = pickBoard(bot, a.length > 2 ? a[2] : null);
                JsonObject chest = nearestChest(bot);
                if (b == null) return "no board in range";
                if (chest == null) return "stand within 5 blocks of the depot chest";
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_chest");
                op.addProperty("board", b.get("id").getAsString());
                op.add("chest", chest);
                bot.team().boardOp(op, r -> report(bot, null, "board chest", r));
                return "setting depot chest of " + b.get("name").getAsString();
            }
            case "remove" -> {
                JsonObject b = pickBoard(bot, a.length > 2 ? a[2] : null);
                if (b == null) return "no board in range";
                JsonObject op = new JsonObject();
                op.addProperty("op", "remove");
                op.addProperty("board", b.get("id").getAsString());
                bot.team().boardOp(op, r -> report(bot, null, "board remove", r));
                return "removing " + b.get("name").getAsString();
            }
            default -> {
                if (bot.team().boards().isEmpty()) return "no job boards within range";
                StringBuilder sb = new StringBuilder();
                for (JsonObject b : bot.team().boards()) {
                    sb.append(b.get("name").getAsString()).append(" (").append(b.get("distance")).append("m)");
                    for (JsonElement e : b.getAsJsonArray("posts")) {
                        JsonObject p = e.getAsJsonObject();
                        sb.append("\n  #").append(p.get("id").getAsString()).append(' ')
                                .append(p.get("delivered")).append('/').append(p.get("count")).append(' ')
                                .append(p.get("item").getAsString().replace("minecraft:", ""))
                                .append(" -> ").append(p.get("deliverTo").getAsString())
                                .append(" [").append(p.get("status").getAsString());
                        if (p.has("open") && "open".equals(p.get("status").getAsString()))
                            sb.append(' ').append(p.get("open")).append(" unclaimed");
                        if (p.has("claims")) for (JsonElement ce : p.getAsJsonArray("claims")) {
                            JsonObject c = ce.getAsJsonObject();
                            sb.append(", ").append(c.get("by").getAsString()).append(' ').append(c.get("delivered")).append('/').append(c.get("count"));
                        }
                        if (p.has("parent") && !p.get("parent").isJsonNull()) sb.append(", for #").append(p.get("parent").getAsString());
                        sb.append(']');
                    }
                    sb.append('\n');
                }
                return sb.toString().trim();
            }
        }
    }

    private static JsonObject pickBoard(Bot bot, String name) {
        for (JsonObject b : bot.team().boards()) {
            if (name == null || b.get("name").getAsString().equalsIgnoreCase(name) || b.get("id").getAsString().equals(name)) return b;
        }
        return null;
    }

    private static JsonObject boardWithPost(Bot bot, String postId) {
        for (JsonObject b : bot.team().boards())
            for (JsonElement e : b.getAsJsonArray("posts"))
                if (e.getAsJsonObject().get("id").getAsString().equals(postId)) return b;
        return null;
    }

    static JsonObject nearestChest(Bot bot) {
        BlockPos me = bot.player().blockPosition();
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-5, -2, -5), me.offset(5, 2, 5))) {
            if (lol.zym.zymbot.world.WorldView.state(p).getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && (best == null || p.distSqr(me) < best.distSqr(me))) best = p.immutable();
        }
        if (best == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("x", best.getX());
        o.addProperty("y", best.getY());
        o.addProperty("z", best.getZ());
        return o;
    }

    private static void report(Bot bot, String to, String what, JsonObject r) {
        String msg = r.get("ok").getAsBoolean() ? what + " ok" : what + " failed: " + r.get("error").getAsString();
        bot.event(msg);
        bot.reply(to, msg);
        bot.feedback(msg);
    }

    private static boolean isMobDrop(ItemStack s) {
        String id = Inv.id(s.getItem());
        return id.equals("minecraft:beef") || id.equals("minecraft:porkchop") || id.equals("minecraft:mutton")
                || id.equals("minecraft:chicken") || id.equals("minecraft:rabbit") || id.equals("minecraft:leather")
                || id.equals("minecraft:feather") || id.endsWith("_wool") || id.equals("minecraft:rabbit_hide");
    }

    /** Is this text addressed to bots at all? Returns [target, command] or null. */
    public static String[] address(String text, String self, Collection<String> botNames) {
        String t = text.trim();
        String[] w = t.split("\\s+", 3);
        if (w.length < 2) return null;
        List<String> prefixes = BotConfig.get().chatPrefixes;
        boolean prefixed = prefixes.stream().anyMatch(p -> p.equalsIgnoreCase(w[0]));
        if (prefixed) {
            // "zb Bot3 get logs" | "zb all come" | "zb come" (= every bot that hears it)
            if (w.length == 3 && (isBot(w[1], self, botNames) || w[1].equalsIgnoreCase("all") || w[1].equalsIgnoreCase("bots")))
                return new String[]{w[1], w[2]};
            return new String[]{"all", t.split("\\s+", 2)[1]};
        }
        if (isBot(w[0], self, botNames)) return new String[]{w[0], t.split("\\s+", 2)[1]};
        return null;
    }

    private static boolean isBot(String name, String self, Collection<String> bots) {
        if (name.equalsIgnoreCase(self)) return true;
        for (String b : bots) if (b.equalsIgnoreCase(name)) return true;
        return false;
    }
}
