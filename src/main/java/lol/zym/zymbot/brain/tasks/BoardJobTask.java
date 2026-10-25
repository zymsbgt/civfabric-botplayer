package lol.zym.zymbot.brain.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Planner;
import lol.zym.zymbot.craft.Recipes;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Work on our share of a job-board post. Several bots can share one post: each delivers in
 * batches of {@code boardBatch} and reports every batch, so the board shows real progress and
 * nothing already delivered is lost if we die or give up.
 * <p>
 * If an ingredient is out of our reach (a CivFabric class gate, say) — or, with other workers
 * around, far slower than the rest — we post it as a sub-request on the same board, get the
 * other ingredients ourselves, and wait at the board for it to arrive. Sub-requests nobody
 * picks up in a few minutes are taken back if we can make them after all.
 */
public class BoardJobTask extends Task {
    private enum Phase { PLAN, PREP, WAIT, OBTAIN, DELIVER, REPORT }

    public static final int MAX_CHAIN = 3;
    private static final int UNCLAIMED_TICKS = 20 * 60 * 4;
    private static final int WAIT_TICKS = 20 * 60 * 20;

    /** An ingredient we asked teammates for. */
    private static final class Sub {
        final Recipes.Need need;
        final Item item;
        final int want; // how many we need to hold
        String id;
        boolean rejected, takenBack;
        int unclaimedSince = -1;
        int doneSince = -1;

        Sub(Recipes.Need need, Item item, int want) {
            this.need = need;
            this.item = item;
            this.want = want;
        }

        boolean here() {
            return takenBack || need.have() >= want;
        }
    }

    private final String boardId;
    private final String boardName;
    private final BlockPos boardPos;
    private final BlockPos chest;
    private final String postId;
    private final ItemTarget item; // count = our whole share
    private final String deliverTo;
    private final Set<Item> chain; // items further up the request chain, never to be asked for or made from
    private Phase phase = Phase.PLAN;
    private int delivered;
    private Planner.Split split;
    private final List<Sub> subs = new ArrayList<>();
    private int waitStart;
    private String failing; // couldn't get more: deliver what we have, then give the rest back
    private boolean finished;

    public BoardJobTask(JsonObject board, JsonObject post, int share, ItemTarget item) {
        this.boardId = board.get("id").getAsString();
        this.boardName = board.get("name").getAsString();
        JsonObject bp = board.getAsJsonObject("pos");
        this.boardPos = new BlockPos(bp.get("x").getAsInt(), bp.get("y").getAsInt(), bp.get("z").getAsInt());
        JsonObject c = board.has("chest") && board.get("chest").isJsonObject() ? board.getAsJsonObject("chest") : null;
        this.chest = c == null ? null : new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        this.postId = post.get("id").getAsString();
        this.item = item.withCount(Math.max(1, share));
        this.deliverTo = post.get("deliverTo").getAsString();
        this.chain = chainOf(post);
    }

    /** The items above a post in its request chain (empty for a top-level post). */
    public static Set<Item> chainOf(JsonObject post) {
        Set<Item> out = new HashSet<>();
        if (post.has("chain") && post.get("chain").isJsonArray()) {
            for (JsonElement e : post.getAsJsonArray("chain")) {
                ItemTarget t = ItemTarget.parse(e.getAsString(), 1);
                if (t != null) out.addAll(t.candidates());
            }
        }
        return out;
    }

    public String postId() {
        return postId;
    }

    /** The item the post asks for ("cobblemon:poke_ball"). */
    public String postItem() {
        return Inv.id(item.candidates().get(0));
    }

    @Override
    public String name() {
        return "Board " + boardName + " #" + postId + ": " + delivered + "/" + item + " → " + deliverTo + " (" + (phase == null ? "done" : phase.name().toLowerCase()) + ")";
    }

    private boolean toChest() {
        return deliverTo.equalsIgnoreCase("chest") || deliverTo.equalsIgnoreCase("depot");
    }

    private void op(Bot bot, String op, int add) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.addProperty("board", boardId);
        o.addProperty("post", postId);
        if (add > 0) o.addProperty("add", add);
        bot.team().boardOp(o, r -> {
            if (!r.get("ok").getAsBoolean()) bot.event("board " + op + " failed: " + r.get("error"));
        });
    }

    /** Count items that left our hands towards the post. */
    private void credit(Bot bot, int n) {
        if (n <= 0) return;
        delivered += n;
        op(bot, "progress", n);
        bot.event("delivered " + n + "x " + item.name() + " for #" + postId + " (" + delivered + "/" + item.count() + ")");
    }

    private Status giveUp(Bot bot, String why) {
        op(bot, "release", 0); // the hub also cancels our sub-requests
        finished = true;
        return fail(why);
    }

    private JsonObject board(Bot bot) {
        for (JsonObject b : bot.team().boards()) if (b.get("id").getAsString().equals(boardId)) return b;
        return null;
    }

    private static JsonObject post(JsonObject board, String id) {
        if (board == null || id == null) return null;
        for (JsonElement e : board.getAsJsonArray("posts")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("id").getAsString().equals(id)) return p;
        }
        return null;
    }

    private static boolean claimedBy(JsonObject post, String name) {
        if (!post.has("claims")) return false;
        for (JsonElement e : post.getAsJsonArray("claims")) if (e.getAsJsonObject().get("by").getAsString().equals(name)) return true;
        return false;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (ticks % (20 * 120) == 0) op(bot, "progress", 0); // heartbeat
        if (ticks % 100 == 0) {
            // the board is only visible within range; when it is, check the post is still ours
            JsonObject b = board(bot);
            JsonObject p = post(b, postId);
            if (b != null) {
                String st = p == null ? "gone" : p.get("status").getAsString();
                if (st.equals("done")) {
                    finished = true;
                    return Status.SUCCESS; // teammates finished it
                }
                if (st.equals("cancelled") || st.equals("gone")) {
                    finished = true;
                    return fail("post #" + postId + " was " + (p == null ? "removed" : "cancelled"));
                }
                if (!claimedBy(p, bot.name())) {
                    finished = true;
                    return fail("lost our claim on #" + postId);
                }
            }
        }
        if (child != null) {
            Status s = child.tick(bot);
            if (s == Status.RUNNING) return Status.RUNNING;
            Task done = child;
            child.stop(bot);
            child = null;
            return afterChild(bot, done, s);
        }
        return switch (phase) {
            case PLAN -> plan(bot);
            case PREP -> prep(bot);
            case WAIT -> waitForSubs(bot);
            case OBTAIN -> obtain(bot);
            case DELIVER -> deliver(bot);
            case REPORT -> {
                op(bot, "done", 0);
                finished = true;
                bot.memory().count("board_jobs");
                yield Status.SUCCESS;
            }
        };
    }

    private Status afterChild(Bot bot, Task done, Status s) {
        if (done instanceof DepositTask d) credit(bot, d.moved());
        if (done instanceof GiveTask g) credit(bot, g.given());
        if (s == Status.SUCCESS) {
            if (phase == Phase.DELIVER && (done instanceof DepositTask || done instanceof GiveTask)) {
                phase = delivered >= item.count() ? Phase.REPORT : Phase.OBTAIN;
                if (phase == Phase.OBTAIN && failing != null) return giveUp(bot, failing);
            }
            return Status.RUNNING;
        }
        String why = done.failureReason();
        if (phase == Phase.OBTAIN && item.have() > 0) {
            failing = why; // hand over what we did get before giving up the rest
            phase = Phase.DELIVER;
            return Status.RUNNING;
        }
        if (done instanceof GoToTask && phase == Phase.WAIT) return Status.RUNNING; // wait wherever we are
        return giveUp(bot, why);
    }

    // ---- phases ----------------------------------------------------------------------

    private Status plan(Bot bot) {
        phase = Phase.OBTAIN;
        if (!BotConfig.get().postRequests || chain.size() >= MAX_CHAIN) return Status.RUNNING;
        Item it = item.candidates().get(0);
        double direct = bot.planner().cost(it) * item.count();
        boolean helpers = bot.team().inRange().stream()
                .anyMatch(m -> m.state().has("role") && "worker".equals(m.state().get("role").getAsString()));
        if (direct < Planner.INF && !(helpers && direct >= 150)) return Status.RUNNING; // we'll just do it
        split = bot.planner().split(it, item.count(), chain, helpers);
        if (split == null) return Status.RUNNING;
        for (Recipes.Need n : split.gaps()) {
            Item ask = Recipes.candidates(n.ingredient()).stream().filter(c -> !chain.contains(c) && c != it).findFirst().orElse(null);
            if (ask == null) continue;
            Sub sub = new Sub(n, ask, n.perCraft() * split.crafts());
            subs.add(sub);
            JsonObject o = new JsonObject();
            o.addProperty("op", "post");
            o.addProperty("board", boardId);
            o.addProperty("parent", postId);
            o.addProperty("item", Inv.id(ask));
            o.addProperty("count", sub.want - n.have());
            o.addProperty("deliver_to", bot.name());
            o.addProperty("note", "ingredient for #" + postId);
            bot.team().boardOp(o, r -> {
                if (r.get("ok").getAsBoolean()) {
                    sub.id = r.getAsJsonObject("post").get("id").getAsString();
                    bot.event("asked for " + (sub.want - n.have()) + "x " + Inv.id(ask) + " (sub-post #" + sub.id + " for #" + postId + ")");
                } else {
                    sub.rejected = true;
                    bot.event("couldn't post " + Inv.id(ask) + ": " + r.get("error"));
                }
            });
        }
        phase = subs.isEmpty() ? Phase.OBTAIN : Phase.PREP;
        return Status.RUNNING;
    }

    /** Get the ingredients we're making ourselves while teammates bring the rest. */
    private Status prep(Bot bot) {
        Set<Item> above = new HashSet<>(chain);
        above.addAll(item.candidates());
        for (Recipes.Need n : split.mine()) {
            int want = n.perCraft() * split.crafts();
            if (n.have() < want) {
                child = new ObtainTask(ItemTarget.of(n.ingredient(), want), above);
                return Status.RUNNING;
            }
        }
        phase = Phase.WAIT;
        waitStart = ticks;
        return Status.RUNNING;
    }

    private Status waitForSubs(Bot bot) {
        if (subs.stream().allMatch(Sub::here)) {
            phase = Phase.OBTAIN;
            return Status.RUNNING;
        }
        if (ticks - waitStart > WAIT_TICKS) return giveUp(bot, "waited too long for " + missing());
        if (!bot.player().blockPosition().closerThan(boardPos, 5)) {
            child = new GoToTask(boardPos, 3); // where helpers come to hand things over
            return Status.RUNNING;
        }
        Nav.stop();
        if (ticks % 100 != 0) return Status.RUNNING;
        JsonObject b = board(bot);
        if (b == null) return Status.RUNNING;
        for (Sub s : subs) {
            if (s.here()) continue;
            if (s.id == null && !s.rejected) continue; // still posting
            JsonObject p = post(b, s.id);
            String st = s.rejected || p == null ? "gone" : p.get("status").getAsString();
            boolean unclaimed = st.equals("open") && p.getAsJsonArray("claims").isEmpty();
            if (unclaimed && s.unclaimedSince < 0) s.unclaimedSince = ticks;
            if (!unclaimed) s.unclaimedSince = -1;
            boolean stale = unclaimed && ticks - s.unclaimedSince > UNCLAIMED_TICKS;
            if ((st.equals("open") || st.equals("taken")) && !stale) continue;
            if (st.equals("done")) {
                if (s.doneSince < 0) s.doneSince = ticks;
                if (ticks - s.doneSince < 20 * 30) continue; // items may still be on the ground next to us
            }
            // nobody is bringing it (cancelled, gone, unclaimed for ages, or "done" but it never reached us)
            if (bot.planner().cost(s.item) >= Planner.INF) {
                if (st.equals("open") || st.equals("taken")) continue; // can't make it: keep waiting until the timeout
                return giveUp(bot, "nobody can bring " + Inv.id(s.item));
            }
            if (s.id != null && (st.equals("open") || st.equals("taken"))) cancelSub(bot, s.id);
            s.takenBack = true;
            bot.event("making " + Inv.id(s.item) + " myself after all");
        }
        return Status.RUNNING;
    }

    private void cancelSub(Bot bot, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "cancel");
        o.addProperty("board", boardId);
        o.addProperty("post", id);
        bot.team().boardOp(o, r -> {});
    }

    private String missing() {
        List<String> m = new ArrayList<>();
        for (Sub s : subs) if (!s.here()) m.add((s.want - s.need.have()) + "x " + Inv.id(s.item));
        return String.join(", ", m);
    }

    private Status obtain(Bot bot) {
        int left = item.count() - delivered;
        if (left <= 0) {
            phase = Phase.REPORT;
            return Status.RUNNING;
        }
        int batch = Math.min(left, Math.max(1, BotConfig.get().boardBatch));
        if (item.have() >= batch) {
            phase = Phase.DELIVER;
            return Status.RUNNING;
        }
        child = new ObtainTask(item.withCount(batch), chain);
        return Status.RUNNING;
    }

    private Status deliver(Bot bot) {
        int n = Math.min(item.have(), item.count() - delivered);
        if (n <= 0) {
            if (failing != null) return giveUp(bot, failing);
            phase = Phase.OBTAIN;
            return Status.RUNNING;
        }
        if (toChest()) {
            if (chest == null) return giveUp(bot, "board " + boardName + " has no depot chest");
            child = new DepositTask(chest, item.withCount(n));
        } else if (bot.findPlayer(deliverTo) == null && !bot.player().blockPosition().closerThan(boardPos, 6)) {
            child = new GoToTask(boardPos, 3); // recipient not in sight: meet them at the board
        } else {
            child = new GiveTask(deliverTo, item.withCount(n));
        }
        return Status.RUNNING;
    }

    /** Called when the job is dropped for good (cleared or replaced): free the rest of our share. */
    public void abandon(Bot bot) {
        if (!finished) {
            op(bot, "release", 0);
            finished = true;
        }
    }

    @Override
    public void stop(Bot bot) {
        // paused mid-delivery (a reflex took over): count what already left our hands
        if (child instanceof DepositTask d) credit(bot, d.moved());
        if (child instanceof GiveTask g) credit(bot, g.given());
        super.stop(bot);
        Nav.stop();
    }
}
