package lol.zym.zymbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lol.zym.zymbot.brain.Brain;
import lol.zym.zymbot.brain.Commands;
import lol.zym.zymbot.civ.CivRules;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.craft.Planner;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.craft.Sources;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.memory.BotMemory;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.team.TeamBus;
import lol.zym.zymbot.world.BlockRules;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** One bot = this client's player. Owns memory, rules, brain and the team radio for the session. */
public final class Bot {
    public enum Role {
        WORKER, LEADER;

        public boolean autonomous() {
            return this == WORKER;
        }

        public static Role parse(String s) {
            return switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
                case "worker", "auto", "autonomous" -> WORKER;
                case "leader", "directed" -> LEADER;
                default -> null;
            };
        }
    }

    private static Bot instance;

    private final Minecraft mc = Minecraft.getInstance();
    private final BotMemory memory;
    private final CivRules civ = new CivRules();
    private final lol.zym.zymbot.civ.Pacifism pacifism = new lol.zym.zymbot.civ.Pacifism();
    private final lol.zym.zymbot.civ.Health health = new lol.zym.zymbot.civ.Health();
    private final lol.zym.zymbot.civ.ClassSheet classSheet = new lol.zym.zymbot.civ.ClassSheet();
    private final lol.zym.zymbot.civ.Forecast forecast = new lol.zym.zymbot.civ.Forecast();
    private long lastHealedAt = -10_000;
    /** A teammate is handing us something until this tick: hold still and pick it up. */
    private long receiveUntil;
    private final Brain brain = new Brain();
    private final TeamBus team;
    private final Random random = new Random();
    private final Deque<String> events = new ArrayDeque<>();
    private final Deque<JsonObject> replies = new ArrayDeque<>();
    private final Set<Block> surveyed = new HashSet<>();
    private boolean enabled;
    private long tick;
    /** When to ask CivFabric ({@code /bed}) whether we still hold our bed: after joining, after dying. */
    private long bedCheckAt = 200;
    private String lastActionKey;
    private long lastActionTick;
    private BlockPos pendingGrave;
    private boolean wasDead;
    private Long downedSince;
    private boolean gaveUp;
    private final Deque<String> heard = new ArrayDeque<>();
    private final Deque<Long> heardAt = new ArrayDeque<>();

    private Bot(LocalPlayer player) {
        this.memory = BotMemory.load(player.getUUID());
        this.team = new TeamBus(player.getGameProfile().getName());
        Recipes.invalidate();
        Sources.invalidate();
        Nav.configure();
        event("joined " + BotMemory.serverKey() + " as " + role().name().toLowerCase(Locale.ROOT));
    }

    public static Bot get() {
        return instance;
    }

    /** Called when a world is joined. */
    public static void start(LocalPlayer player) {
        instance = new Bot(player);
        if (BotConfig.get().autoStart) instance.setEnabled(true);
    }

    /** Called on disconnect. */
    public static void end() {
        if (instance != null) {
            instance.brain.stopAll(instance);
            instance.memory.save();
            instance.team.close();
            instance = null;
        }
    }

    // ---- accessors -------------------------------------------------------------------

    public LocalPlayer player() {
        return mc.player;
    }

    public ClientLevel level() {
        return mc.level;
    }

    public BotMemory memory() {
        return memory;
    }

    public CivRules civ() {
        return civ;
    }

    public lol.zym.zymbot.civ.ClassSheet classSheet() {
        return classSheet;
    }

    public lol.zym.zymbot.civ.Forecast forecast() {
        return forecast;
    }

    public lol.zym.zymbot.civ.Pacifism pacifism() {
        return pacifism;
    }

    public lol.zym.zymbot.civ.Health health() {
        return health;
    }

    /** Tick of the last time something healed us on purpose (food, a teammate's stitches). */
    public void receive(int seconds) {
        receiveUntil = tick + seconds * 20L;
    }

    public boolean receiving() {
        return tick < receiveUntil;
    }

    public long lastHealedAt() {
        return lastHealedAt;
    }

    public void healed() {
        lastHealedAt = tick;
    }

    /** Ask CivFabric ({@code /bed}) in a moment whether we still hold our bed. */
    public void checkBed() {
        bedCheckAt = tick + 20;
    }

    public Brain brain() {
        return brain;
    }

    public TeamBus team() {
        return team;
    }

    private BlockPos stuckAnchor;
    private int stuckTicks;

    /** Count ticks Baritone has been trying to travel without us getting anywhere. */
    private float lastHealth = -1;
    /** Who or what last hurt us (entity id), for tasks that should back off from it. */
    public int lastAttackerId = -1;
    public long lastHurtTick = -1000;
    /** What last hurt us ("lava", "onFire", "mob"...): a death takes its cause from it. */
    private String lastHurtBy;
    /** Every mob that has hurt us lately (entity id, tick of its last hit), for {@link #huntedBy}. */
    private final Map<Integer, Long> attackers = new HashMap<>();
    private static final long HUNTED_TICKS = 20 * 60 * 5;

    /**
     * This mob has hurt us in the last five minutes: it's hunting us, whatever pacifism says.
     * pacifist-fabric only covers mobs that arrived on their own (natural spawns, chunk generation,
     * patrols, reinforcements). An ocean ruin's drowned (a structure spawn), a zombie that drowned in
     * the river (a conversion) or a spawner's mob looks the same to us but hunts us all the same; the
     * first hit is how we find out. A covered mob can't land one.
     */
    public boolean huntedBy(net.minecraft.world.entity.Entity e) {
        Long at = attackers.get(e.getId());
        return at != null && tick - at < HUNTED_TICKS;
    }

    /** Health is spent money with regeneration off: say what took it. */
    private void noteDamage() {
        var p = player();
        float h = p.getHealth();
        if (lastHealth >= 0 && h < lastHealth - 0.01f) {
            var src = p.getLastDamageSource();
            String what = src == null ? "unknown" : src.getEntity() != null
                    ? src.getEntity().getName().getString() + " (" + src.type().msgId() + ")" : src.type().msgId();
            lastAttackerId = src != null && src.getEntity() != null ? src.getEntity().getId() : -1;
            lastHurtTick = tick;
            lastHurtBy = src == null ? null : src.type().msgId();
            if (src != null && src.getEntity() instanceof net.minecraft.world.entity.Mob m) {
                attackers.values().removeIf(at -> tick - at > HUNTED_TICKS);
                attackers.put(m.getId(), tick);
            }
            event(String.format("hurt %.1f by %s, health %.1f", lastHealth - h, what, h));
            // a wild Pokémon that hits us stays angry until one of us dies: remember its kind
            if (src != null && src.getEntity() != null && lol.zym.zymbot.brain.tasks.CatchPokemonTask.isPokemon(src.getEntity())) {
                memory.fighters.put(src.getEntity().getName().getString().toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis());
                memory.save();
            }
        }
        lastHealth = h;
    }

    private Map<String, Integer> keptBefore = new HashMap<>();

    /** A bed or tool vanishing unasked has bitten us before: say when, and during what. */
    private void noteLosses() {
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < Inv.inv().getContainerSize(); i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.isEmpty()) continue;
            String id = Inv.id(s.getItem());
            // beds and tools: things that never leave the pack on purpose except by placing a bed
            if (s.is(net.minecraft.tags.ItemTags.BEDS) || s.isDamageableItem()) now.merge(id, s.getCount(), Integer::sum);
        }
        for (var e : keptBefore.entrySet()) {
            int left = now.getOrDefault(e.getKey(), 0);
            // a bed that became our placed bed, or a tool worn out, isn't a loss
            if (e.getKey().endsWith("_bed") && memory.bed != null) continue;
            if (left < e.getValue()) {
                var menu = player().containerMenu;
                event("lost " + (e.getValue() - left) + "x " + e.getKey().replace("minecraft:", "") + " during "
                        + brain.describe() + (menu != player().inventoryMenu ? " [" + menu.getClass().getSimpleName() + " open]" : ""));
            }
        }
        keptBefore = now;
    }

    private void trackStuck() {
        var p = player();
        // not moving because we're breaking a block is work, not being stuck (the gather's own
        // time limit gives up on one that won't break)
        if (!Nav.travelling() || Minecraft.getInstance().gameMode != null && Minecraft.getInstance().gameMode.isDestroying()) {
            stuckAnchor = null;
            stuckTicks = 0;
        } else if (stuckAnchor == null || p.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(stuckAnchor)) > 2) {
            stuckAnchor = p.blockPosition();
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
    }

    /** Underground or in water and going nowhere for 20 s: time to climb out by hand. */
    public boolean stuck() {
        var p = player();
        return stuckTicks > 400 && (p.isInWater() || !level().canSeeSky(p.blockPosition().above()));
    }

    public Planner planner() {
        return new Planner(this);
    }

    public Random random() {
        return random;
    }

    public long ticks() {
        return tick;
    }

    /** Bots we could radio right now, as the hub sees it. */
    public List<String> inRadioRange() {
        var me = player().position();
        return team.inRange().stream().map(m -> m.name() + " (" + (int) m.distanceTo(me.x, me.y, me.z) + "m)").toList();
    }

    public boolean enabled() {
        return enabled;
    }

    public String name() {
        return player().getGameProfile().getName();
    }

    public String dim() {
        return level().dimension().location().toString();
    }

    public Role role() {
        Role r = Role.parse(memory.role);
        if (r == null) r = Role.parse(BotConfig.get().roles.get(name()));
        if (r == null) r = Role.parse(BotConfig.get().role);
        return r == null ? Role.WORKER : r;
    }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            mc.options.pauseOnLostFocus = false;
            if (!Nav.configured()) Nav.configure();
        } else {
            brain.stopAll(this);
        }
        event(on ? "started" : "paused");
    }

    // ---- events & messages -----------------------------------------------------------

    public void event(String msg) {
        ZymBotMod.LOG.info("[{}] {}", mc.player != null ? name() : "zymbot", msg);
        events.addLast(String.format("%tT %s", System.currentTimeMillis(), msg));
        while (events.size() > 40) events.removeFirst();
    }

    public List<String> recentEvents() {
        return new ArrayList<>(events);
    }

    /** Note what we just tried, so a CivFabric refusal arriving next can be pinned on it. */
    public void lastAction(String key) {
        lastActionKey = key;
        lastActionTick = tick;
    }

    public void reply(String to, String text) {
        reply(to, null, text);
    }

    public void reply(String to, String id, String text) {
        JsonObject r = new JsonObject();
        if (id != null) r.addProperty("id", id);
        r.addProperty("to", to == null ? "" : to);
        r.addProperty("text", text);
        r.addProperty("ts", System.currentTimeMillis());
        replies.addLast(r);
        while (replies.size() > 30) replies.removeFirst();
        if (to != null && BotConfig.get().replyInChat && isTeammate(to) && !team.knownNames().contains(to)
                && !to.equals(name())) chat(text);
    }

    /** Say something in server chat (heard within 150 blocks under ZymLabs Rules). */
    public void chat(String text) {
        if (text.startsWith("/")) player().connection.sendCommand(text.substring(1));
        else player().connection.sendChat(text);
    }

    /**
     * Send a command over the team radio to one bot or "all" within range: as a chat line (the server
     * carries it 150 blocks), or through the hub (which enforces the same range) with chatRadio off.
     */
    public String radio(String to, String text) {
        boolean all = to.equalsIgnoreCase("all") || to.equalsIgnoreCase("bots");
        if (!all && team.member(to).isEmpty())
            return to + " is out of radio range (" + BotConfig.get().commsRange + " blocks) or offline";
        if (BotConfig.get().chatRadio) {
            String target = all ? "all" : team.member(to).get().name();
            team.radio().say("zb " + target + " " + text);
            event("radio '" + text + "' -> " + target + " (chat)");
            return "sending to " + target;
        }
        if (!team.connected()) return "team hub unreachable: " + team.lastError();
        team.send(all ? "all" : team.member(to).get().name(), text, null, r -> {
            if (r.has("sent")) event("radio '" + text + "' -> " + r.get("sent") + (r.has("dropped") && !r.getAsJsonArray("dropped").isEmpty() ? " dropped " + r.get("dropped") : ""));
            else event("radio failed: " + r.get("error"));
        });
        return "sending to " + to;
    }

    /** Named in our config's teammates: the only ones whose radio lines we take in. */
    public boolean isConfiguredTeammate(String name) {
        for (String t : BotConfig.get().teammates) if (t.equalsIgnoreCase(name)) return true;
        return false;
    }

    public boolean isTeammate(String name) {
        if (name == null) return false;
        for (String t : BotConfig.get().teammates) if (t.equalsIgnoreCase(name)) return true;
        for (String b : team.knownNames()) if (b.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Whether the server sent text containing {@code needle} since {@code sinceTick}. */
    public boolean heard(String needle, long sinceTick) {
        Iterator<String> t = heard.iterator();
        Iterator<Long> at = heardAt.iterator();
        while (t.hasNext()) {
            String s = t.next();
            if (at.next() >= sinceTick && s.contains(needle)) return true;
        }
        return false;
    }

    /** Chat or overlay text from the server. */
    public void onServerMessage(String text) {
        heard.addLast(text);
        heardAt.addLast(tick);
        while (heard.size() > 20) {
            heard.removeFirst();
            heardAt.removeFirst();
        }
        if (onBedMessage(text)) return;
        if (lastActionKey != null && tick - lastActionTick < 60 && civ.onMessage(memory, text, lastActionKey)) {
            event("refused: " + text + " (" + lastActionKey + ")");
            lastActionKey = null;
            return;
        }
        // CivFabric tells us how warm our bed is when we lie in it: believe it over our memory
        if (text.contains("This bed feels")) {
            boolean warm = text.contains("warm") || text.contains("cozy") || text.contains("cosy");
            if (memory.bedWarm != warm) {
                memory.bedWarm = warm;
                memory.save();
                event(warm ? "bed is warm: resting will heal" : "bed is cold (\"" + text.replace("[CivLabs] » ", "").trim()
                        + "\"): needs a campfire within 4 blocks");
            }
        }
        if (civ.onMessage(memory, text, null)) return;
        if (pacifism.onMessage(text)) return;
        if (forecast.onMessage(this, text)) return;
        if (text.contains("You're knocked out")) {
            downedSince = System.currentTimeMillis();
            gaveUp = false;
            event("knocked out");
        } else if (text.contains("You're back on your feet") || text.contains("You bled out")) {
            downedSince = null;
        } else if (text.contains("too hungry to rest") || text.contains("not enough night left")
                || text.contains("woke up from a nightmare")) {
            brain.skipNight();
        }
    }

    /**
     * CivFabric's bed claims: one bed per player, taken by the first right-click on it, and refused
     * to everyone else (on the action bar). The claim is what makes a bed a respawn point.
     */
    private boolean onBedMessage(String text) {
        boolean claimed;
        if (text.contains("You have no bed. Sleep in one") || text.contains("Your bed is gone. Sleep in another")
                || text.contains("You have unclaimed your bed")) {
            claimed = false;
        } else if (text.contains("Your bed was destroyed")) {
            if (memory.bed != null) event("CivFabric: our bed at " + memory.bed.pos().toShortString() + " was destroyed");
            memory.bed = null;
            memory.bedWarm = false;
            claimed = false;
        } else if (text.contains("This bed is already claimed by another player")) {
            // only ever our own bed gets clicked, so ours has been taken: settle afresh
            if (memory.bed != null && "use:bed".equals(lastActionKey) && tick - lastActionTick < 60) {
                event("our bed at " + memory.bed.pos().toShortString() + " is claimed by another player; need a new one");
                memory.bed = null;
                memory.bedWarm = false;
                memory.save();
            }
            return true;
        } else if (text.contains("Your bed is right here") || text.contains("Your bed is ") && text.contains(" blocks")
                || text.contains("You have a bed in the")) {
            claimed = true;
        } else {
            return false;
        }
        if (!Boolean.valueOf(claimed).equals(memory.bedClaimed)) {
            memory.bedClaimed = claimed;
            memory.save();
            event(claimed ? "CivFabric: our bed claim holds (we respawn at it)"
                    : "CivFabric: we hold no bed claim" + (memory.bed != null ? "; claiming ours at " + memory.bed.pos().toShortString() : ""));
        }
        return true;
    }

    /** The latest server text since {@code sinceTick} containing any of {@code needles}, or null. */
    public String lastHeard(long sinceTick, String... needles) {
        String found = null;
        Iterator<String> t = heard.iterator();
        Iterator<Long> at = heardAt.iterator();
        while (t.hasNext()) {
            String s = t.next();
            if (at.next() < sinceTick) continue;
            for (String n : needles) if (s.contains(n)) found = s;
        }
        return found;
    }

    /** A chat line that may be a command from a teammate within earshot. */
    public void onChat(String sender, String message) {
        if (sender != null) forecast.onPlayerChat(this, sender, message);
        if (sender == null || sender.equalsIgnoreCase(name()) || !isTeammate(sender)) return;
        String[] addr = Commands.address(message, name(), team.knownNames());
        if (addr == null) return;
        String target = addr[0];
        if (!(target.equalsIgnoreCase(name()) || target.equalsIgnoreCase("all") || target.equalsIgnoreCase("bots"))) return;
        // the same line can arrive as both chat and system text; handle it once
        if (!team.markSeen("chat:" + sender + ":" + message + ":" + tick / 40)) return;
        String reply = Commands.execute(this, sender, addr[1]);
        event(sender + " (chat): " + addr[1] + " -> " + reply);
        reply(sender, reply);
    }

    // ---- queries used by tasks -------------------------------------------------------

    public Player findPlayer(String name) {
        if (name == null) return null;
        for (Player p : level().players()) if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        return null;
    }

    public Player nearestOtherPlayer(double r) {
        Player best = null;
        for (Player p : level().players()) {
            if (p == player()) continue;
            double d = p.distanceTo(player());
            if (d <= r && (best == null || d < best.distanceTo(player()))) best = p;
        }
        return best;
    }

    /** Where a teammate bot last said it was (only if within radio range). */
    public BlockPos lastKnownPosition(String name) {
        return team.member(name).map(m -> BlockPos.containing(m.x(), m.y(), m.z())).orElse(null);
    }

    public boolean inventoryFull() {
        return Inv.freeSlots() == 0;
    }

    public boolean hasStationNearby(String type) {
        // remembered isn't enough: it may have been broken (or picked back up) since
        return memory.nearestStation(type, player().blockPosition(), dim(), 24)
                .filter(st -> !level().isLoaded(st.at.pos()) || !level().getBlockState(st.at.pos()).isAir()
                        && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level().getBlockState(st.at.pos()).getBlock()).getPath().contains(type))
                .isPresent();
    }

    /** Is this spot close to something built that isn't ours? Our own home/stations don't count. */
    public boolean nearSomeoneElsesBuild(BlockPos pos) {
        int buffer = BotConfig.get().builtAreaBuffer;
        if (memory.home != null && memory.home.pos().closerThan(pos, 24)) return false;
        // the team's base is ours too
        BlockPos base = lol.zym.zymbot.brain.ClassPlan.teamHome(this);
        if (base != null && base.closerThan(pos, 32)) return false;
        for (BotMemory.Station s : memory.stations) if (s.ours && s.at.pos().closerThan(pos, buffer + 2)) return false;
        return WorldView.nearBuilt(pos, buffer);
    }

    public boolean seenNearby(Block b) {
        return surveyed.contains(b);
    }

    /** The nearest of our graves in this dimension still to empty (they're kept across restarts). */
    public BlockPos pendingGrave() {
        if (pendingGrave == null) {
            BlockPos me = player().blockPosition();
            pendingGrave = memory.graves.stream().filter(g -> g.dim.equals(dim()) && !memory.nearHazard(g)).map(BotMemory.Pos::pos)
                    .min(java.util.Comparator.comparingDouble(g -> g.distSqr(me))).orElse(null);
        }
        return pendingGrave;
    }

    /** Done with (or giving up on) the grave we were after; any others stay. */
    public void clearPendingGrave() {
        if (pendingGrave != null) {
            BlockPos done = pendingGrave;
            memory.graves.removeIf(g -> g.dim.equals(dim()) && g.pos().equals(done));
        }
        pendingGrave = null;
        memory.save();
    }

    /** Leave every grave where it is. */
    public void forgetGraves() {
        memory.graves.clear();
        pendingGrave = null;
        memory.save();
    }

    /** Damage from lava or fire, by the server's damage type id. */
    public static boolean burns(String msgId) {
        return msgId != null && java.util.Set.of("lava", "inFire", "onFire", "hotFloor").contains(msgId);
    }

    /** Somewhere lava or fire got us: no graves near it. */
    public void markHazard(BlockPos at) {
        memory.addHazard(new BotMemory.Pos(at, dim()));
        memory.graves.removeIf(g -> memory.nearHazard(g));
        pendingGrave = null;
        memory.save();
    }

    /** Graves still to empty within 96 of a death away from home: 0 by our bed, where dying twice proves nothing. */
    private int guardedGraves(BlockPos at) {
        if (memory.bed != null && memory.bed.dim.equals(dim()) && memory.bed.pos().closerThan(at, 48)) return 0;
        return (int) memory.graves.stream().filter(g -> g.dim.equals(dim()) && g.pos().closerThan(at, 96)).count();
    }

    public void addGrave(BlockPos at) {
        memory.graves.removeIf(g -> g.dim.equals(dim()) && g.pos().equals(at));
        memory.graves.add(new BotMemory.Pos(at, dim()));
        pendingGrave = null; // pick the nearest again
        memory.save();
    }

    // ---- tick ------------------------------------------------------------------------

    public void tick() {
        LocalPlayer p = player();
        if (p == null || level() == null) return;
        tick++;

        if (tick % 40 == 0) publishState();
        if (tick % 10 == 0) handleRadio();
        team.radio().tick(this);
        if (tick % 1200 == 0) memory.save();
        // right after joining the chunks may not be in yet, so keep looking until something shows up
        if (tick % 600 == 1 || surveyed.isEmpty() && tick % 40 == 0) survey();

        if (handleDeath()) return;
        if (!enabled) {
            // paused isn't a reason to drown (a bot can rejoin underwater): keep its head up
            var pl = player();
            boolean sinking = pl.isInWater() && (pl.isUnderWater() || pl.getAirSupply() < pl.getMaxAirSupply());
            if (downedSince == null) {
                // nor to be killed: answer whatever attacks us, then stand still again. Bots join
                // paused, and two respawned at their beds beside an ocean ruin's drowned and died
                // there over and over, never hitting back
                noteDamage();
                if (brain.guard(this)) return;
            }
            lol.zym.zymbot.control.Controls.jump(sinking);
            if (sinking && tick % 100 == 0) event("paused but in water: treading water");
            return;
        }

        if (mc.screen instanceof PauseScreen) mc.setScreen(null);
        else if (mc.screen != null && !(mc.screen instanceof ChatScreen) && !(mc.screen instanceof AbstractContainerScreen<?>)
                && !(mc.screen instanceof DeathScreen)) {
            // anything modal (welcome screens, Cobblemon prompts) would block eating and attacking
            mc.setScreen(null);
        }

        if (downedSince != null) {
            brain.stopAll(this);
            int limit = BotConfig.get().giveUpDownedSeconds;
            if (limit >= 0 && !gaveUp && System.currentTimeMillis() - downedSince > limit * 1000L) {
                gaveUp = true;
                event("no revive came; giving up");
                p.connection.sendCommand("giveup");
            }
            return;
        }

        if (tick % 100 == 0 && p.containerMenu == p.inventoryMenu) equipArmor();
        if (tick % 40 == 0 && inventoryFull() && p.containerMenu == p.inventoryMenu) dropJunk();

        trackStuck();
        noteDamage();
        if (tick % 5 == 0) noteLosses();
        pacifism.tick(this);
        if (bedCheckAt > 0 && tick >= bedCheckAt && civ.present()) {
            bedCheckAt = -1;
            p.connection.sendCommand("bed");
        }
        classSheet.tick(this);
        forecast.tick(this);
        lol.zym.zymbot.memory.ChestIndex.tick(this);
        lol.zym.zymbot.memory.Landmarks.tick(this);
        if (tick % 100 == 50) lol.zym.zymbot.brain.ClassPlan.reconcile(this);
        health.tick(this);
        try {
            brain.tick(this);
        } catch (Exception e) {
            ZymBotMod.LOG.error("Brain crashed; resetting tasks", e);
            event("error: " + e);
            brain.stopAll(this);
            brain.clearJob();
        }
    }

    /** The server's death packet: the only sign of a death when the server respawns us at once. */
    public void onKilled() {
        recordDeath();
    }

    private void recordDeath() {
        if (wasDead) return;
        wasDead = true;
        BlockPos at = player().blockPosition();
        memory.deaths.add(new BotMemory.Pos(at, dim()));
        while (memory.deaths.size() > 10) memory.deaths.remove(0);
        memory.count("deaths");
        event("died at " + at.toShortString());
        brain.stopAll(this);
        Controls.releaseAll();
        // burned to death (knocked out by it and bled out counts): the lava or fire is still there, and
        // walking back for the grave is how we'd die there again
        if (burns(lastHurtBy)) {
            memory.addHazard(new BotMemory.Pos(at, dim()));
            event("died to " + lastHurtBy + " at " + at.toShortString() + ": leaving that grave, and the place marked");
        } else if ("trident".equals(lastHurtBy) || !level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player().getBoundingBox().inflate(24), lol.zym.zymbot.brain.FightOdds::tridentDrowned).isEmpty()) {
            // a trident drowned keeps to its water, there or nearby, even by our bed (Bot5, killed by one
            // in the river beside its farm, then hit again going back for the grave): leave the graves
            memory.graves.removeIf(g -> g.dim.equals(dim()) && g.pos().closerThan(at, 32));
            pendingGrave = null;
            memory.save();
            event("killed with a trident drowned about, at " + at.toShortString() + ": leaving the graves there");
        } else if (guardedGraves(at) > 0) {
            // died again out by a grave we were going back for: what killed us is still there (Bot1 and
            // a trident drowned on the coast, three lives running). Leave them all rather than a fourth
            int n = guardedGraves(at);
            memory.graves.removeIf(g -> g.dim.equals(dim()) && g.pos().closerThan(at, 96));
            pendingGrave = null;
            memory.save();
            event("died near " + n + " grave(s) not yet emptied: leaving them and this one at " + at.toShortString());
        } else {
            addGrave(at);
        }
        lastHurtBy = null;
        downedSince = null;
        bedCheckAt = tick + 200; // a death with no claim scatters us: find out if that's what happened

    }

    private boolean handleDeath() {
        LocalPlayer p = player();
        boolean dead = p.isDeadOrDying() || mc.screen instanceof DeathScreen;
        if (dead) {
            recordDeath();
            if (enabled && tick % 40 == 0) {
                p.respawn();
                mc.setScreen(null);
            }
            return true;
        }
        wasDead = false;
        return false;
    }

    private void handleRadio() {
        for (TeamBus.Message m : team.poll()) {
            String who = m.via() != null ? m.from() + " (" + m.via() + ")" : m.from();
            String reply = Commands.execute(this, m.from(), m.text());
            event(who + ": " + m.text() + " -> " + reply);
            reply(m.from(), m.id(), reply);
        }
    }

    // ---- housekeeping ----------------------------------------------------------------

    private void equipArmor() {
        LocalPlayer p = player();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!(s.getItem() instanceof ArmorItem armor)) continue;
            ItemStack worn = p.getItemBySlot(armor.getEquipmentSlot());
            int wornDef = worn.getItem() instanceof ArmorItem w ? w.getDefense() : -1;
            if (armor.getDefense() <= wornDef) continue;
            var menu = p.inventoryMenu;
            int ms = Inv.menuSlot(menu, i);
            if (!worn.isEmpty()) {
                // swap: pick up the new piece, click the armour slot, put the old piece back
                int armorSlot = 8 - armor.getEquipmentSlot().getIndex();
                Inv.click(menu, ms, 0, ClickType.PICKUP);
                Inv.click(menu, armorSlot, 0, ClickType.PICKUP);
                Inv.click(menu, ms, 0, ClickType.PICKUP);
            } else {
                Inv.click(menu, ms, 0, ClickType.QUICK_MOVE);
            }
            event("equipped " + Inv.id(s.getItem()));
            return;
        }
    }

    private static final Set<String> JUNK = Set.of("minecraft:dirt", "minecraft:gravel", "minecraft:rotten_flesh",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:netherrack",
            "minecraft:wheat_seeds", "minecraft:poisonous_potato", "minecraft:spider_eye", "minecraft:cobbled_deepslate");

    private long nextSpareDrop;

    private void dropJunk() {
        var menu = player().inventoryMenu;
        // keep one stack of scaffolding; toss the rest of the junk
        boolean keptScaffold = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.isEmpty()) continue;
            String id = Inv.id(s.getItem());
            if (BotConfig.get().keepItems.contains(id)) continue;
            boolean junk = JUNK.contains(id) || s.is(Items.COBBLESTONE) && Inv.count(Items.COBBLESTONE) > 128;
            if (!junk) continue;
            if (!keptScaffold && (s.is(Items.DIRT) || s.is(Items.COBBLED_DEEPSLATE))) {
                keptScaffold = true;
                continue;
            }
            Inv.click(menu, Inv.menuSlot(menu, i), 1, ClickType.THROW);
            return; // one stack per call
        }
        // no junk left: toss the most worn spare copy of a tool or weapon (we keep one of each).
        // Rarely: what we throw often lands where we walk and gets picked straight back up.
        if (tick < nextSpareDrop) return;
        nextSpareDrop = tick + 600;
        Map<net.minecraft.world.item.Item, Integer> copies = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.isDamageableItem()) copies.merge(s.getItem(), 1, Integer::sum);
        }
        int worst = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!s.isDamageableItem() || copies.get(s.getItem()) < 2 || i == Inv.inv().selected) continue;
            if (worst < 0 || s.getDamageValue() > Inv.inv().getItem(worst).getDamageValue()) worst = i;
        }
        if (worst >= 0) {
            event("inventory full: dropping a spare " + Inv.id(Inv.inv().getItem(worst).getItem()).replace("minecraft:", ""));
            Inv.click(menu, Inv.menuSlot(menu, worst), 1, ClickType.THROW);
            return;
        }
        // no chest near home to stash in (a base just moved, say), and a chest takes a free slot to
        // craft: the smallest pile of odds and ends goes, or the pack stays full for good
        BlockPos home = memory.home == null ? null : memory.home.pos();
        if (home == null || memory.chests.values().stream().anyMatch(r -> r.ours && r.at != null && r.at.pos().closerThan(home, 32))) return;
        int least = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.isEmpty() || s.isDamageableItem() || Inv.isFood(s) || s.is(net.minecraft.tags.ItemTags.BEDS) || s.is(net.minecraft.tags.ItemTags.WOOL)
                    || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE) || s.is(Items.CHEST) || s.is(Items.TORCH)
                    || s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS) || s.is(Items.STICK)
                    || s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock
                    || BotConfig.get().keepItems.contains(Inv.id(s.getItem()))) continue;
            if (least < 0 || s.getCount() < Inv.inv().getItem(least).getCount()) least = i;
        }
        if (least >= 0) {
            event("inventory full, no chest at home: dropping " + Inv.inv().getItem(least).getCount() + " " + Inv.id(Inv.inv().getItem(least).getItem()).replace("minecraft:", ""));
            Inv.click(menu, Inv.menuSlot(menu, least), 1, ClickType.THROW);
        }
    }

    /** Which natural blocks are around (palette scan of nearby chunk sections). Feeds the planner. */
    /** Player-built blocks counted in the last survey (about 50 blocks round us). */
    private int builtNearby;

    /**
     * Nothing anyone built close by: safe to let Baritone mine in bulk, since there's no cabin to
     * strip or wall to tunnel through. Our own base counts as built, so the bot is careful at home too.
     */
    public boolean wilderness() {
        return builtNearby < 8;
    }

    /** Someone's build within {@code r} of here (ours excepted): for checks while mining. */
    public boolean builtWithin(BlockPos pos, int r) {
        if (memory.home != null && memory.home.pos().closerThan(pos, 24)) return false;
        return WorldView.nearBuilt(pos, r);
    }

    private void survey() {
        surveyed.clear();
        int[] built = {0};
        ClientLevel level = level();
        ChunkPos c = player().chunkPosition();
        int minSec = level.getSectionIndex(player().getBlockY() - 32), maxSec = level.getSectionIndex(player().getBlockY() + 32);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                LevelChunk ch = level.getChunkSource().getChunk(c.x + dx, c.z + dz, ChunkStatus.FULL, false);
                if (ch == null) continue;
                LevelChunkSection[] secs = ch.getSections();
                for (int i = Math.max(0, minSec); i <= Math.min(secs.length - 1, maxSec); i++) {
                    if (secs[i] == null || secs[i].hasOnlyAir()) continue;
                    secs[i].getStates().count((state, n) -> {
                        if (BlockRules.natural(state)) surveyed.add(state.getBlock());
                        else if (BlockRules.looksBuilt(state)) built[0] += n;
                    });
                }
            }
        builtNearby = built[0];
    }

    // ---- reporting -------------------------------------------------------------------

    public String statusLine() {
        LocalPlayer p = player();
        return String.format("%s [%s%s] hp %.0f food %d @ %s | %s", name(), role().name().toLowerCase(Locale.ROOT),
                enabled ? "" : ", paused", p.getHealth(), p.getFoodData().getFoodLevel(),
                p.blockPosition().toShortString(), brain.describe());
    }

    public Map<String, Integer> inventoryCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (!s.isEmpty()) m.merge(Inv.id(s.getItem()).replace("minecraft:", ""), s.getCount(), Integer::sum);
        }
        return m;
    }

    public String inventorySummary() {
        StringBuilder b = new StringBuilder();
        inventoryCounts().forEach((k, v) -> b.append(k).append(" x").append(v).append(", "));
        return b.isEmpty() ? "empty" : b.substring(0, b.length() - 2);
    }

    private void publishState() {
        LocalPlayer p = player();
        JsonObject o = new JsonObject();
        o.addProperty("uuid", p.getUUID().toString());
        o.addProperty("role", role().name().toLowerCase(Locale.ROOT));
        o.addProperty("enabled", enabled);
        o.addProperty("dim", dim());
        JsonObject pos = new JsonObject();
        pos.addProperty("x", Math.round(p.getX() * 10) / 10.0);
        pos.addProperty("y", Math.round(p.getY() * 10) / 10.0);
        pos.addProperty("z", Math.round(p.getZ() * 10) / 10.0);
        o.add("pos", pos);
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("downed", downedSince != null);
        o.addProperty("time_of_day", level().getDayTime() % 24000);
        o.addProperty("task", brain.describe());
        o.addProperty("job_from", brain.jobSource());
        // the board post we're really working on; the hub drops any other claim of ours (e.g. after a restart)
        o.addProperty("claim", brain.job() instanceof lol.zym.zymbot.brain.tasks.BoardJobTask b ? b.postId() : "");
        JsonArray ms = new JsonArray();
        brain.progression().status().forEach(ms::add);
        o.add("milestones", ms);
        JsonObject inv = new JsonObject();
        inventoryCounts().forEach(inv::addProperty);
        o.add("inventory", inv);
        JsonArray armor = new JsonArray();
        for (ItemStack a : p.getArmorSlots()) if (!a.isEmpty()) armor.add(Inv.id(a.getItem()));
        o.add("armor", armor);
        if (memory.home != null) o.addProperty("home", memory.home.pos().toShortString());
        if (memory.bed != null) o.addProperty("bed", memory.bed.pos().toShortString());
        o.add("ranks_known", new com.google.gson.Gson().toJsonTree(memory.rankBelow));
        if (memory.classRole != null) o.addProperty("class_role", memory.classRole);
        o.add("classes", new com.google.gson.Gson().toJsonTree(lol.zym.zymbot.brain.ClassPlan.classes(this)));
        o.add("class_xp", new com.google.gson.Gson().toJsonTree(memory.classXp));
        o.addProperty("class_slots", lol.zym.zymbot.civ.ClassSheet.slots(this));
        o.addProperty("drain_armed", memory.drainArmed);
        lol.zym.zymbot.civ.Forecast.publish(this, o);
        // a leader's driver asks players things in chat (their forecast readings): it needs the answers
        if (role() == Role.LEADER) {
            JsonArray heard = new JsonArray();
            forecast.chatHeard().forEach(heard::add);
            o.add("chat_heard", heard);
        }
        o.addProperty("phase", brain.progression().phase(this));
        o.addProperty("farm_plots", lol.zym.zymbot.brain.Progression.farmPlots(this));
        o.addProperty("food_points", lol.zym.zymbot.brain.Progression.foodPoints());
        o.add("chests", lol.zym.zymbot.memory.ChestIndex.summary(this));
        o.add("landmarks", lol.zym.zymbot.memory.Landmarks.summary(this, 40));
        JsonObject homes = new JsonObject();
        lol.zym.zymbot.brain.ClassPlan.teamHome(this); // makes the bootstrap base if it's due
        memory.teamHomes.forEach((name, h) -> {
            JsonObject t = new JsonObject();
            t.addProperty("x", h.at.x);
            t.addProperty("y", h.at.y);
            t.addProperty("z", h.at.z);
            if (h.purpose != null) t.addProperty("purpose", h.purpose);
            homes.add(name, t);
        });
        o.add("team_homes", homes);
        if (memory.homeName != null) o.addProperty("base", memory.homeName);
        if (memory.home != null) {
            JsonObject h = new JsonObject();
            h.addProperty("x", memory.home.x);
            h.addProperty("y", memory.home.y);
            h.addProperty("z", memory.home.z);
            o.add("home", h);
        }

        JsonObject around = new JsonObject();
        JsonArray players = new JsonArray();
        for (Player other : level().players()) {
            if (other != p && other.distanceTo(p) < 64) players.add(other.getGameProfile().getName());
        }
        around.add("players", players);
        AABB box = new AABB(p.blockPosition()).inflate(24);
        around.addProperty("hostiles", level().getEntitiesOfClass(LivingEntity.class, box, e -> e instanceof Enemy).size());
        around.addProperty("wild_pokemon", level().getEntitiesOfClass(LivingEntity.class, box,
                lol.zym.zymbot.brain.tasks.CatchPokemonTask::wild).size());
        JsonArray blocks = new JsonArray();
        surveyed.stream().map(b -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString())
                .filter(id -> !id.contains("air")).sorted().limit(80).forEach(blocks::add);
        around.add("natural_blocks", blocks);
        o.add("around", around);

        JsonArray ev = new JsonArray();
        events.forEach(ev::add);
        o.add("events", ev);
        JsonArray rep = new JsonArray();
        replies.forEach(rep::add);
        o.add("replies", rep);
        team.publish(o);
    }

    public void feedback(String text) {
        if (player() != null) player().displayClientMessage(Component.literal("§b[ZymBot]§r " + text), false);
    }
}
