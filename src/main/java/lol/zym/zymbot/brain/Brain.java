package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.tasks.*;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.WorldView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lol.zym.zymbot.craft.ItemTarget;
import lol.zym.zymbot.craft.Planner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.Set;

/**
 * Chooses what to do each tick, in layers:
 * <ol>
 *   <li>reflexes: flee, fight, eat, sleep, recover a grave — always, for every role</li>
 *   <li>the job a teammate or the leader assigned (gather, follow, give, farm, goto...)</li>
 *   <li>autonomous progression ({@link Progression}) — only for the "worker" role</li>
 *   <li>idle: catch Pokémon, wander</li>
 * </ol>
 * Lower layers are paused (stopped, but kept) while a higher one runs, then resumed.
 */
public final class Brain {
    private enum Layer { REFLEX, JOB, PROGRESS, IDLE }

    private Task reflex;
    private String reflexKind;
    private Task job;
    private String jobSource;
    private String repeatCmd;
    private Task progress;
    private Progression.Milestone milestone;
    private Task idle;
    private Layer active;
    private final Progression progression = new Progression();
    private long noSleepUntil;
    private long noUnstuckUntil;
    private long noShoreUntil;
    private long noTreatUntil, nextHealCall, noForageUntil, nextFragileWork;
    private int graveDives;
    private Player patient;
    /** Ticks spent in deep water with Baritone not taking us anywhere. */
    private int wetIdle;
    private int idleCooldown;
    private long nextBoardCheck;
    private boolean claimPending;
    /** Posts we recently failed at, so we don't claim them straight back: post id -> retry time. */
    private final java.util.Map<String, Long> skipPosts = new java.util.HashMap<>();

    public Progression progression() {
        return progression;
    }

    // ---- jobs ------------------------------------------------------------------------

    /** Give the bot a job. {@code repeatCmd} (a command line) is re-run each time it succeeds. */
    public void assign(Task task, String source, String repeatCmd) {
        clearJob();
        job = task;
        jobSource = source;
        this.repeatCmd = repeatCmd;
    }

    public void clearJob() {
        if (job instanceof BoardJobTask b) b.abandon(Bot.get());
        if (job != null) job.stop(Bot.get());
        job = null;
        jobSource = null;
        repeatCmd = null;
    }

    public Task job() {
        return job;
    }

    public String jobSource() {
        return jobSource;
    }

    public void skipNight() {
        noSleepUntil = System.currentTimeMillis() + 8 * 60_000L;
    }

    public void stopAll(Bot bot) {
        for (Task t : new Task[]{reflex, job, progress, idle}) if (t != null) t.stop(bot);
        reflex = null;
        progress = null;
        idle = null;
        milestone = null;
        Nav.stop();
        Controls.releaseAll();
    }

    public String describe() {
        Task t = switch (active == null ? Layer.IDLE : active) {
            case REFLEX -> reflex;
            case JOB -> job;
            case PROGRESS -> progress;
            case IDLE -> idle;
        };
        String layer = active == null ? "idle" : active.name().toLowerCase();
        if (active == Layer.PROGRESS && milestone != null) layer = "goal " + milestone.id();
        if (active == Layer.JOB && jobSource != null) layer = "job from " + jobSource;
        String chain = t == null ? "nothing" : t.chain();
        if (chain.length() > 220) chain = chain.substring(0, 60) + " … " + chain.substring(chain.length() - 150);
        return "[" + layer + "] " + chain;
    }

    // ---- tick ------------------------------------------------------------------------

    public void tick(Bot bot) {
        String want = wantedReflex(bot);
        if (want != null && (reflex == null || !want.equals(reflexKind) && priority(want) > priority(reflexKind))) {
            if (reflex != null) reflex.stop(bot);
            reflex = makeReflex(bot, want);
            reflexKind = want;
        }
        if (reflex != null) {
            pause(bot, Layer.REFLEX);
            Task.Status s = reflex.tick(bot);
            if (s != Task.Status.RUNNING) {
                if (s == Task.Status.FAILURE) {
                    bot.event(reflex.failureReason());
                    if ("sleep".equals(reflexKind)) skipNight();
                    if ("unstuck".equals(reflexKind)) noUnstuckUntil = System.currentTimeMillis() + 2 * 60_000L;
                    if ("shore".equals(reflexKind)) noShoreUntil = System.currentTimeMillis() + 60_000L;
                    if ("grave".equals(reflexKind)) bot.clearPendingGrave();
                }
                if (s == Task.Status.SUCCESS && "grave".equals(reflexKind)) bot.clearPendingGrave();
                reflex.stop(bot);
                reflex = null;
                reflexKind = null;
            }
            return;
        }

        if (job != null) {
            pause(bot, Layer.JOB);
            Task.Status s = job.tick(bot);
            if (s != Task.Status.RUNNING) {
                String result = s == Task.Status.SUCCESS ? "done: " + job.name() : "failed: " + job.failureReason();
                if (s == Task.Status.FAILURE && job instanceof BoardJobTask b) skipPosts.put(b.postId(), System.currentTimeMillis() + 10 * 60_000L);
                bot.event("job " + result);
                bot.reply(jobSource, "job " + result);
                if (repeatCmd != null && s == Task.Status.SUCCESS) {
                    job.stop(bot);
                    job = Commands.build(bot, jobSource, repeatCmd.split("\\s+"));
                    if (job == null) jobSource = null;
                } else {
                    job.stop(bot);
                    job = null;
                    jobSource = null;
                }
            }
            return;
        }

        // hurt with no way to heal on our own: no risky work until a medic or a warm bed fixes it
        boolean fragile = bot.role().autonomous() && bot.health().fragile(bot);
        // the way out of fragile is a warm bed: let it work on that (and other quiet jobs at home)
        if (fragile && progress == null && progression.next(true) != null && bot.ticks() >= nextFragileWork) {
            nextFragileWork = bot.ticks() + 200;
            milestone = progression.next(true);
            progress = milestone.make().apply(bot);
            if (progress == null) {
                progression.snooze(milestone.id(), 5 * 60_000L);
                milestone = null;
            } else {
                if (idle != null) {
                    idle.stop(bot);
                    idle = null;
                }
                bot.event("hurt, but working on " + milestone.id() + " (safe)");
            }
        }
        if (fragile && progress == null) {
            pause(bot, Layer.IDLE);
            if (!(idle instanceof RestTask)) {
                if (idle != null) idle.stop(bot);
                idle = new RestTask();
                bot.event("hurt (" + Math.round(bot.player().getHealth()) + ") and not healing on my own: resting near home");
            }
            if (bot.ticks() >= nextHealCall && !bot.team().inRange().isEmpty()) {
                nextHealCall = bot.ticks() + 20 * 120;
                bot.radio("all", "heal " + bot.name());
            }
            if (idle.tick(bot) != Task.Status.RUNNING) {
                idle.stop(bot);
                idle = null;
            }
            return;
        }

        if (bot.role().autonomous() && !fragile && BotConfig.get().takeBoardJobs && progression.basicsDone()
                && !claimPending && bot.ticks() >= nextBoardCheck) {
            nextBoardCheck = bot.ticks() + 200;
            tryClaim(bot);
        }

        if (bot.role().autonomous()) {
            if (progress == null) {
                milestone = progression.next();
                if (milestone != null) {
                    progress = milestone.make().apply(bot);
                    if (progress == null) {
                        progression.snooze(milestone.id(), 5 * 60_000L);
                        milestone = null;
                    } else {
                        bot.event("working on " + milestone.id());
                    }
                }
            }
            if (progress != null) {
                pause(bot, Layer.PROGRESS);
                Task.Status s = progress.tick(bot);
                if (s != Task.Status.RUNNING) {
                    if (s == Task.Status.FAILURE) {
                        bot.event("couldn't finish " + milestone.id() + ": " + progress.failureReason());
                        progression.snooze(milestone.id(), 10 * 60_000L);
                        if (progress instanceof ObtainTask o) askBoard(bot, o.target());
                    } else {
                        if (milestone.done().getAsBoolean()) bot.event("milestone " + milestone.id() + " done");
                    }
                    progress.stop(bot);
                    progress = null;
                    milestone = null;
                }
                return;
            }
        }

        pause(bot, Layer.IDLE);
        if (idle == null) {
            if (idleCooldown-- > 0) return;
            idle = makeIdle(bot);
            idleCooldown = 100;
        }
        if (idle != null && idle.tick(bot) != Task.Status.RUNNING) {
            idle.stop(bot);
            idle = null;
        }
    }

    // ---- job boards ------------------------------------------------------------------

    /**
     * Take a share of the first open post on a nearby board that we can fulfil — directly, or
     * by asking teammates for the ingredients we can't get. The share is split with the other
     * workers in range and capped at a stack, so big requests get worked on in parallel.
     */
    private void tryClaim(Bot bot) {
        BotConfig cfg = BotConfig.get();
        int workers = 1 + (int) bot.team().inRange().stream()
                .filter(m -> m.state().has("role") && "worker".equals(m.state().get("role").getAsString())).count();
        // remember which names are bots (they're on the hub), so a post for one that has wandered
        // off can be told apart from one for a player who'll come to the board
        var roster = bot.memory().roster;
        for (var m : bot.team().inRange()) {
            var r = roster.computeIfAbsent(m.name(), n -> new lol.zym.zymbot.memory.BotMemory.RosterEntry());
            r.name = m.name();
            r.last_seen_millis = System.currentTimeMillis();
        }
        for (JsonObject board : bot.team().boards()) {
            for (JsonElement e : board.getAsJsonArray("posts")) {
                JsonObject post = e.getAsJsonObject();
                if (!"open".equals(post.get("status").getAsString())) continue;
                if (bot.name().equals(post.get("by").getAsString())) continue;
                if (skipPosts.getOrDefault(post.get("id").getAsString(), 0L) > System.currentTimeMillis()) continue;
                int open = post.has("open") ? post.get("open").getAsInt() : post.get("count").getAsInt() - post.get("delivered").getAsInt();
                if (open <= 0) continue;
                // for a teammate bot that has wandered out of radio range: we couldn't hand it over
                String to = post.has("deliverTo") ? post.get("deliverTo").getAsString() : "";
                boolean toBot = roster.keySet().stream().anyMatch(to::equalsIgnoreCase)
                        || bot.team().knownNames().stream().anyMatch(to::equalsIgnoreCase);
                if (toBot && bot.team().member(to).isEmpty()) continue;
                ItemTarget item = ItemTarget.parse(post.get("item").getAsString(), 1);
                if (item == null) continue;
                Item it = item.candidates().get(0);
                Set<Item> chain = BoardJobTask.chainOf(post);
                if (chain.contains(it)) continue;
                boolean doable = bot.planner().cost(it) < 400
                        || cfg.postRequests && chain.size() < BoardJobTask.MAX_CHAIN && bot.planner().split(it, 1, chain, false) != null;
                if (!doable) continue;
                int cap = Math.max(1, Math.min(cfg.boardShare, new ItemStack(it).getMaxStackSize()));
                int share = Math.min(open, Math.min(cap, (open + workers - 1) / workers));
                claimPending = true;
                JsonObject op = new JsonObject();
                op.addProperty("op", "claim");
                op.addProperty("board", board.get("id").getAsString());
                op.addProperty("post", post.get("id").getAsString());
                op.addProperty("count", share);
                bot.team().boardOp(op, r -> {
                    claimPending = false;
                    if (!r.get("ok").getAsBoolean() || job != null) {
                        if (r.get("ok").getAsBoolean()) { // got it but became busy meanwhile: give it back
                            JsonObject rel = new JsonObject();
                            rel.addProperty("op", "release");
                            rel.addProperty("board", board.get("id").getAsString());
                            rel.addProperty("post", post.get("id").getAsString());
                            bot.team().boardOp(rel, x -> {});
                        }
                        return;
                    }
                    JsonObject claimed = r.getAsJsonObject("post");
                    int got = r.get("share").getAsInt();
                    assign(new BoardJobTask(board, claimed, got, item), "board:" + board.get("name").getAsString(), null);
                    bot.event("claimed " + got + " of " + claimed.get("count") + "x " + claimed.get("item").getAsString()
                            + " on post #" + claimed.get("id").getAsString());
                });
                return;
            }
        }
    }

    /** Stuck on something (often a CivFabric class gate): ask teammates via the nearest board. */
    private void askBoard(Bot bot, ItemTarget want) {
        if (!BotConfig.get().postRequests || bot.team().boards().isEmpty() || want.candidates().isEmpty()) return;
        // a craft that failed because we were too hungry isn't something to ask the team for
        if (bot.civ().present() && bot.player().getFoodData().getFoodLevel() <= 4) return;
        JsonObject board = bot.team().boards().get(0);
        JsonObject op = new JsonObject();
        op.addProperty("op", "post");
        op.addProperty("board", board.get("id").getAsString());
        op.addProperty("item", lol.zym.zymbot.inv.Inv.id(want.candidates().get(0)));
        op.addProperty("count", Math.max(1, want.count() - want.have()));
        op.addProperty("deliver_to", bot.name());
        op.addProperty("note", "auto: blocked on it");
        bot.team().boardOp(op, r -> {
            if (r.get("ok").getAsBoolean()) bot.event("asked board " + board.get("name").getAsString() + " for " + want);
        });
    }

    /** Release the controls of whatever layer we're switching away from. */
    private void pause(Bot bot, Layer next) {
        if (active == next) return;
        if (active != null) {
            Task prev = switch (active) {
                case REFLEX -> null; // reflexes are discarded when done
                case JOB -> job;
                case PROGRESS -> progress;
                case IDLE -> idle;
            };
            if (prev != null) prev.stop(bot);
            if (active == Layer.IDLE) idle = null;
        }
        active = next;
    }

    private static int priority(String kind) {
        if (kind == null) return -1;
        return switch (kind) {
            case "air" -> 6;
            case "flee" -> 5;
            case "fight" -> 4;
            case "eat", "shore", "shelter" -> 3;
            case "unstuck", "grave", "receive", "forage" -> 2;
            case "sleep", "treat" -> 1;
            default -> 0;
        };
    }

    /** The most hurt teammate within reach of our stitches. */
    private Player hurtTeammate(Bot bot) {
        var me = bot.player();
        return bot.level().players().stream()
                .filter(o -> o != me && bot.isTeammate(o.getGameProfile().getName()) && o.distanceTo(me) < 16 && TreatTask.needsTreatment(o))
                .min(Comparator.comparingDouble(o -> o.getHealth())).orElse(null);
    }

    /** A Pokémon that hurt us in the last minute and is still close (fliers hit, back off, come again). */
    private LivingEntity angryPokemon(Bot bot) {
        if (bot.ticks() - bot.lastHurtTick > 1200 || bot.lastAttackerId < 0) return null;
        var e = bot.level().getEntity(bot.lastAttackerId);
        if (!(e instanceof LivingEntity le) || !le.isAlive() || !CatchPokemonTask.isPokemon(le)) return null;
        return le.distanceTo(bot.player()) < 24 ? le : null;
    }

    private LivingEntity threat(Bot bot) {
        var p = bot.player();
        return bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(12),
                        e -> e instanceof Enemy && e.isAlive() && !CatchPokemonTask.isPokemon(e)
                                && (e.distanceTo(p) < 4 || e.distanceTo(p) < 10 && p.hasLineOfSight(e)))
                .stream()
                // pacifism keeps natural hostiles off us: leave them be, unless one just hurt us
                .filter(e -> !bot.pacifism().atPeaceWith(e) || p.hurtTime > 0 && e.distanceTo(p) < 4)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
    }

    private String wantedReflex(Bot bot) {
        var p = bot.player();
        BotConfig cfg = BotConfig.get();
        // running out of air beats everything: a flooded hole kills faster than any mob
        // diving for our own grave needs the breath: surface at a third, not at four fifths
        double airFloor = "grave".equals(reflexKind) ? 0.35 : 0.8;
        if (p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() * airFloor) return "air";
        // a wild Pokémon that has turned on us stays angry until one of us dies (Cobblemon's anger
        // never wears off), and fliers keep up with a sprint: running only spreads the hits out. Fight
        // it. Someone's own Pokémon defending its owner is different: back off from that one.
        // dug in under a blood moon: whatever is standing on the cover can't reach us, and going out to
        // fight it is how we'd die. Only something actually hurting us down there gets an answer
        boolean hidden = reflex instanceof ShelterTask st && st.dugIn() && bot.ticks() - bot.lastHurtTick > 40;
        LivingEntity mon = hidden ? null : angryPokemon(bot);
        if (mon != null) return CatchPokemonTask.wild(mon) ? "fight" : "flee";
        LivingEntity t = hidden ? null : threat(bot);
        if (t != null) {
            boolean weak = p.getHealth() <= bot.health().fleeAt(bot);
            boolean boom = t instanceof Creeper c && (c.getSwellDir() > 0 || Inv.bestWeapon() < 0);
            // a Super Blood Moon's mobs outrun and outlast us: getting into the ground beats a fight
            // we'd lose or a race we can't win. Only one already on us gets fought off
            if (bot.pacifism().superBloodMoon() && !boom && t.distanceTo(p) > 3 && !WorldView.deepWater(p.blockPosition())
                    && !p.isUnderWater()) return "shelter";
            // hitting a mob pacifism still covers would turn its whole kin on us: walk away instead
            if (weak || boom || bot.pacifism().fightingCosts(t)) return "flee";
            return "fight";
        }
        // hit by something we can't see: most likely another player
        if (p.hurtTime > 0 && p.getHealth() < p.getMaxHealth() - 4) {
            Player attacker = bot.nearestOtherPlayer(6);
            if (attacker != null && !bot.isTeammate(attacker.getGameProfile().getName())) return "flee";
        }
        // out of deep water before anything slow (eating, a grave in the water is the exception)
        boolean deep = WorldView.deepWater(p.blockPosition());
        wetIdle = deep && !Nav.busy() ? wetIdle + 1 : 0;
        if (wetIdle > 60 && System.currentTimeMillis() > noShoreUntil && !Set.of("air", "unstuck", "grave").contains(String.valueOf(reflexKind)))
            return "shore";
        int food = p.getFoodData().getFoodLevel();
        boolean wet = deep || p.isUnderWater();
        boolean hurtForGood = bot.health().hurt(bot) && !bot.health().naturalRegen(bot);
        if ((!wet || food <= 3) && (food <= 14 || food < 20 && p.getHealth() < p.getMaxHealth() - 6) && EatTask.choose(bot) >= 0) return "eat";
        if (!wet && hurtForGood && EatTask.chooseHealing(bot) >= 0) return "eat";
        // a blood moon: every mob is after us until dawn. Go home and dig in, ahead of foraging,
        // graves and errands (all of which mean walking about in the dark)
        if (bot.pacifism().bloodMoon() && !wet) return "shelter";
        // hungry with nothing to eat: go and get food before anything else (CivFabric won't
        // even let us craft below 4)
        if (!wet && food <= 6 && EatTask.choose(bot) < 0 && System.currentTimeMillis() > noForageUntil) return "forage";
        if (bot.stuck() && System.currentTimeMillis() > noUnstuckUntil) return "unstuck";
        // a grave trip is a long walk: not unarmed (unless it's close), the progression makes a sword first
        if (bot.pendingGrave() != null && (Inv.armed() || bot.pendingGrave().closerThan(p.blockPosition(), 48))) return "grave";
        if (bot.receiving()) return "receive";
        if (System.currentTimeMillis() > noTreatUntil && TreatTask.treatmentSlot() >= 0) {
            patient = hurtTeammate(bot);
            if (patient != null) return "treat";
        }
        if (cfg.sleep && WorldView.isNight() && bot.memory().bed != null && System.currentTimeMillis() > noSleepUntil
                && bot.memory().bed.pos().closerThan(p.blockPosition(), 128) && bot.dim().equals(bot.memory().bed.dim))
            return "sleep";
        return null;
    }

    private Task makeReflex(Bot bot, String kind) {
        return switch (kind) {
            case "flee" -> {
                LivingEntity mon = angryPokemon(bot);
                if (mon != null) {
                    bot.event("running from " + mon.getName().getString());
                    yield new FleeTask(mon, 24);
                }
                LivingEntity t = threat(bot);
                Player pl = bot.nearestOtherPlayer(8);
                yield new FleeTask(t != null ? t : pl != null ? pl : bot.player(), 16);
            }
            case "fight" -> {
                LivingEntity mon = angryPokemon(bot);
                if (mon != null) {
                    bot.event("fighting back against " + mon.getName().getString() + " (it won't stop chasing)");
                    yield new FightTask(mon);
                }
                yield new FightTask(threat(bot));
            }
            case "eat" -> new EatTask();
            case "grave" -> new RecoverGraveTask(bot.pendingGrave());
            case "shelter" -> {
                bot.event("blood moon" + (bot.pacifism().superBloodMoon() ? " (super)" : "") + ": taking shelter until dawn");
                yield new ShelterTask();
            }
            case "air" -> {
                bot.event("out of air; climbing out");
                // a grave too deep to reach on one breath: after a few tries, leave it
                if (bot.pendingGrave() != null && bot.player().blockPosition().closerThan(bot.pendingGrave(), 24) && ++graveDives >= 4) {
                    bot.event("our grave at " + bot.pendingGrave().toShortString() + " is too deep to reach; leaving it");
                    bot.clearPendingGrave();
                    graveDives = 0;
                }
                yield new UnstuckTask();
            }
            case "shore" -> {
                bot.event("in deep water; heading for shore");
                yield new ShoreTask();
            }
            case "unstuck" -> {
                bot.event("stuck underground; climbing out");
                yield new UnstuckTask();
            }
            case "receive" -> new ReceiveTask();
            case "forage" -> {
                noForageUntil = System.currentTimeMillis() + 90_000; // if the hunt fails, don't retry at once
                bot.event("hungry with no food; hunting");
                yield Progression.food(bot);
            }
            case "sleep" -> new SleepTask(bot.memory().bed.pos());
            case "treat" -> {
                noTreatUntil = System.currentTimeMillis() + 8_000;
                yield new TreatTask(patient);
            }
            default -> null;
        };
    }

    private Task makeIdle(Bot bot) {
        if (BotConfig.get().catchPokemon && Inv.find(CatchPokemonTask::isBall) >= 0 && CatchPokemonTask.findWild(bot, 24) != null)
            return new CatchPokemonTask();
        if (!bot.role().autonomous()) return null; // leaders wait for direction
        return new ExploreTask(48);
    }
}
