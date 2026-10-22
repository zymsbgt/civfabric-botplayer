package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.brain.tasks.*;
import lol.zym.zymbot.civ.Forecast;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.Spawners;
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
import java.util.List;
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
    private final ShoppingList shopping = new ShoppingList();
    private ShoppingList.Find detour;
    private long noDetourUntil;
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

    /** Nothing to do: no reflex, job or milestone, and not resting up from a wound. Fit to be given work. */
    public boolean workless() {
        return (active == null || active == Layer.IDLE) && !(idle instanceof RestTask);
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
        // anything but sleep needs us up: shelter, a fight, running (the "Leave Bed" button)
        if (reflex != null && !"sleep".equals(reflexKind) && bot.player().isSleeping()) {
            bot.player().connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
                    bot.player(), net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
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
                if ("detour".equals(reflexKind) && reflex instanceof DetourTask d) {
                    // give the errand a stretch before the next side trip
                    noDetourUntil = System.currentTimeMillis() + 20_000;
                    bot.event("detour " + (d.got() > 0 ? "done: +" + d.got() + " " : "came back empty: ") + detour.want().what());
                    shopping.stale();
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
                // "bed" too when we have none: a spare bed is help anyone can give
                bot.radio("all", "heal " + bot.name() + (bot.memory().bed == null && Inv.find(s -> s.is(net.minecraft.tags.ItemTags.BEDS)) < 0 ? " bed" : ""));
            }
            if (idle.tick(bot) != Task.Status.RUNNING) {
                idle.stop(bot);
                idle = null;
            }
            return;
        }

        if (bot.role().autonomous() && !fragile && BotConfig.get().takeBoardJobs && progression.basicsDone()
                && !claimPending && bot.ticks() >= nextBoardCheck && Inv.freeSlots() > 0) {
            nextBoardCheck = bot.ticks() + 200;
            tryClaim(bot);
        }

        if (bot.role().autonomous()) {
            for (Progression.Wait w : progression.newWaits()) {
                bot.event(w.milestone().id() + " needs " + w.why() + ": waiting for the rank or a teammate");
                askBoard(bot, ItemTarget.of(w.item(), w.count()));
            }
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
                        int streak = failStreak.merge(milestone.id(), 1, Integer::sum);
                        if (streak >= 3) bot.askForHelp("can't finish " + milestone.id() + " (" + streak + " tries): " + progress.failureReason());
                        progression.snooze(milestone.id(), 10 * 60_000L);
                        if (progress instanceof ObtainTask o) askBoard(bot, o.target());
                    } else if (milestone.done().getAsBoolean()) {
                        failStreak.remove(milestone.id());
                        bot.event("milestone " + milestone.id() + " done");
                    } else if (progress.ticks < 40) {
                        // ended at once and nothing changed: it would start again every tick
                        progression.snooze(milestone.id(), 60_000L);
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

    /**
     * The day whose night we're dug in for (a trident drowned came for us, or phantoms we mayn't
     * fight), and why: till dawn, as on a storm night.
     */
    private long hideNight = -1;
    private String hideWhy = "";

    public boolean hideNight(Bot bot) {
        long t = bot.level().getDayTime();
        long tod = t % 24000L;
        return hideNight == t / 24000L && tod >= 13000 && tod < 23000;
    }

    public String hideWhy() {
        return hideWhy;
    }

    private void hideTonight(Bot bot, String why) {
        hideNight = bot.level().getDayTime() / 24000L;
        hideWhy = why;
    }

    /** Milestones that failed several times running: past three, the bot asks Claude. */
    private final java.util.Map<String, Integer> failStreak = new java.util.HashMap<>();
    /** The food swap we're making, and when to look for the next. */
    private FoodShare.Gift gift;
    private long noShareUntil;

    private static int priority(String kind) {
        if (kind == null) return -1;
        return switch (kind) {
            case "air" -> 6;
            case "flee", "bed" -> 5;
            case "fight" -> 4;
            case "eat", "shore", "shelter" -> 3;
            case "unstuck", "grave", "receive", "forage" -> 2;
            case "sleep", "treat" -> 1;
            default -> 0;
        };
    }

    private long bedEnclosedAt = -1000;
    private boolean bedEnclosed;

    /** Our bed is in a closed room (asked every 10 s: walls don't change often). */
    private boolean bedEnclosed(Bot bot) {
        if (bot.ticks() - bedEnclosedAt >= 200) {
            bedEnclosedAt = bot.ticks();
            boolean was = bedEnclosed;
            bedEnclosed = WorldView.loaded(bot.memory().bed.pos()) && WorldView.enclosed(bot.memory().bed.pos());
            if (was != bedEnclosed) bot.event(bedEnclosed ? "our bed is walled and roofed" : "our bed is open to the night");
        }
        return bedEnclosed;
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
        List<LivingEntity> all = threats(bot);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Hostiles that are after us (or will be), nearest first. */
    private List<LivingEntity> threats(Bot bot) {
        var p = bot.player();
        return bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(16),
                        e -> e instanceof Enemy && e.isAlive() && !CatchPokemonTask.isPokemon(e)
                                && (e.distanceTo(p) < 4 || e.distanceTo(p) < 10 && p.hasLineOfSight(e)
                                    // a drowned's trident reaches further than we'd look
                                    || bot.huntedBy(e) && e.distanceTo(p) < 16))
                .stream()
                // pacifism keeps natural hostiles off us: leave them be, unless one has hurt us
                // (pacifism doesn't cover it after all) or something close just did
                .filter(e -> !bot.pacifism().atPeaceWith(e) || bot.huntedBy(e) || p.hurtTime > 0 && e.distanceTo(p) < 4)
                // neutral mobs leave us be until provoked: an enderman once it's screaming at us,
                // anything else once it has hit us (a blood moon doesn't change that)
                .filter(e -> !(e instanceof net.minecraft.world.entity.NeutralMob)
                        || (e instanceof net.minecraft.world.entity.monster.EnderMan em ? em.isCreepy() : e.getId() == bot.lastAttackerId))
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(p))).toList();
    }

    /** What to do about whatever is after us: "fight", "flee", "shelter", or null when nothing is. */
    private String defend(Bot bot) {
        var p = bot.player();
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
            // a trident drowned at night: a 12-point throw from 20 blocks off, and it outlasts a flee (four
            // bots dead in ten minutes, one chased up a hill). Into the ground till dawn, as for a storm.
            // Not when it's on us already, or we're in deep water: there, fight or get away as usual
            long tod = bot.level().getDayTime() % 24000L;
            boolean night = tod >= 13000 && tod < 23000;
            boolean dry = !WorldView.deepWater(p.blockPosition()) && !p.isUnderWater();
            // phantoms, while pacifism is on: never fought (out of it, as any mob). ZymLabs' Insomnia
            // rule: a phantom hunts only the player it spawned over, and pacifism keeps it off them too.
            // The one it's for (a week awake) goes to bed, the cure, even on a night it meant to work;
            // anyone else carries on, as it isn't after them
            if (t instanceof net.minecraft.world.entity.monster.Phantom && bot.pacifism().protecting()) {
                var bed = bot.memory().bed;
                if (bot.summoningPhantoms() && night && bed != null && bot.dim().equals(bed.dim) && bed.pos().closerThan(p.blockPosition(), 128))
                    return "bed";
                return null;
            }
            boolean tridentAbout = hideNight(bot) && hideWhy.startsWith("trident")
                    || FightOdds.tridentDrowned(t) && !FightOdds.canTake(bot, threats(bot));
            if (tridentAbout && night && !boom && t.distanceTo(p) > 3 && dry) {
                hideTonight(bot, "trident drowned about");
                return "shelter";
            }
            // a Super Blood Moon's mobs outrun and outlast us: getting into the ground beats a fight
            // we'd lose or a race we can't win. Only one already on us gets fought off
            if (bot.pacifism().superBloodMoon() && !boom && t.distanceTo(p) > 3 && !WorldView.deepWater(p.blockPosition())
                    && !p.isUnderWater()) return "shelter";
            // hitting a mob pacifism still covers would turn its whole kin on us: walk away instead
            // except one that is hunting us anyway: it won't stop at our bed, and finishing it within
            // 15 s of the first swing costs only the 20 s grudge for a hit (pacifist-fabric's grace)
            if (weak || boom || bot.pacifism().fightingCosts(t) && !bot.huntedBy(t)) return "flee";
            // a spawner's mob: another comes for each one killed while we're in range. Leave instead
            if (Spawners.near(t.blockPosition(), lol.zym.zymbot.civ.Pacifism.SPAWNED_RANGE) && t.distanceTo(p) > 2.5) return "flee";
            // more than our kit (and the armed teammates beside us) can take on, or a trident
            if (!FightOdds.canTake(bot, threats(bot))) return "flee";
            return "fight";
        }
        // hit by something we can't see: most likely another player
        if (p.hurtTime > 0 && p.getHealth() < p.getMaxHealth() - 4) {
            Player attacker = bot.nearestOtherPlayer(6);
            if (attacker != null && !bot.isTeammate(attacker.getGameProfile().getName())) return "flee";
        }
        return null;
    }

    /**
     * Paused: no work, but a bot that just stands there gets killed. Once something hurts us, fight
     * it or get away as we would running; then stand still again. True while that is going on.
     */
    public boolean guard(Bot bot) {
        if (reflex == null) {
            if (bot.ticks() - bot.lastHurtTick > 40) return false;
            String want = defend(bot);
            if ("shelter".equals(want)) want = "flee";
            if (want == null) return false;
            if (!Nav.configured()) Nav.configure();
            bot.event("paused, but under attack: " + (want.equals("fight") ? "fighting back" : "getting away"));
            reflex = makeReflex(bot, want);
            reflexKind = want;
        }
        if (reflex.tick(bot) != Task.Status.RUNNING) stopAll(bot);
        return true;
    }

    private String wantedReflex(Bot bot) {
        var p = bot.player();
        BotConfig cfg = BotConfig.get();
        // running out of air beats everything: a flooded hole kills faster than any mob
        // diving for our own grave needs the breath: surface at a third, not at four fifths
        double airFloor = "grave".equals(reflexKind) ? 0.35 : 0.8;
        if (p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() * airFloor) return "air";
        String defence = defend(bot);
        if (defence != null) return defence;
        // out of deep water before anything slow (eating, a grave in the water is the exception)
        boolean deep = WorldView.deepWater(p.blockPosition());
        wetIdle = deep && !Nav.busy() ? wetIdle + 1 : 0;
        if (wetIdle > 60 && System.currentTimeMillis() > noShoreUntil && !Set.of("air", "unstuck", "grave").contains(String.valueOf(reflexKind)))
            return "shore";
        int food = p.getFoodData().getFoodLevel();
        boolean wet = deep || p.isUnderWater();
        boolean hurtForGood = bot.health().hurt(bot) && !bot.health().naturalRegen(bot);
        // eat when something fits the gap (or we're down to the floor); where health regenerates
        // from a full bar, topping up while hurt is worth the spill
        boolean regen = bot.health().naturalRegen(bot);
        if ((!wet || food <= 3) && (food <= 14 && EatTask.fitsNow(bot) || food <= EatTask.MUST_EAT
                || regen && food < 20 && p.getHealth() < p.getMaxHealth() - 6) && EatTask.choose(bot) >= 0) return "eat";
        if (!wet && hurtForGood && EatTask.chooseHealing(bot) >= 0) return "eat";
        // a blood moon: every mob is after us until dawn. Dig in, ahead of foraging, graves and
        // errands (all of which mean walking about in the dark). CivFabric's night watch lets all but
        // one of us sleep instead (the night isn't skipped), and a warm bed mends us: worth it when
        // hurt and home is near, but only in a walled, roofed room. The siege walks mobs to the beds,
        // and a zombie woke a bot sleeping in the open with two hits (14 health to 5). Not on a super
        // one, when the whole horde marches on the town. Refused ("someone has to keep watch",
        // monsters by the bed) or woken, we dig in after all
        if (bot.pacifism().bloodMoon() && !wet) {
            boolean rest = cfg.sleep && bot.memory().bedWarm && bot.health().hurt(bot) && !bot.pacifism().superBloodMoon()
                    && System.currentTimeMillis() > noSleepUntil && bot.memory().bed != null && bot.dim().equals(bot.memory().bed.dim)
                    && bot.memory().bed.pos().closerThan(p.blockPosition(), 48) && bedEnclosed(bot);
            return rest ? "sleep" : "shelter";
        }
        // hungry with nothing to eat: go and get food before anything else (CivFabric won't
        // even let us craft below 4)
        if (!wet && food <= 6 && EatTask.choose(bot) < 0 && System.currentTimeMillis() > noForageUntil) return "forage";
        if ((bot.stuck() || bot.stranded()) && System.currentTimeMillis() > noUnstuckUntil) return "unstuck";
        // tonight's forecast names a blood moon: home before dusk, not caught out on a grave trip or
        // an errand when it rises (ShelterTask only walks home from within 48 blocks)
        var bedHome = !wet && Forecast.braceTime(bot) ? BraceTask.home(bot) : null;
        if (bedHome != null && bedHome.pos().closerThan(p.blockPosition(), 128) && !bedHome.pos().closerThan(p.blockPosition(), 8))
            return "brace";
        // a grave trip is a long walk: not unarmed (unless it's close), the progression makes a sword first
        if (bot.pendingGrave() != null && (Inv.armed() || bot.pendingGrave().closerThan(p.blockPosition(), 48))) return "grave";
        if (bot.receiving()) return "receive";
        if (System.currentTimeMillis() > noTreatUntil && TreatTask.treatmentSlot(bot) >= 0) {
            patient = hurtTeammate(bot);
            if (patient != null) return "treat";
        }
        // a thunderstorm night: CivFabric's drowned ignore pacifism and come out of every river (they
        // took a bot at full health in 25 s). A hurt bot sleeps it off in a walled warm bed; the rest dig in
        if (!wet && ShelterTask.stormNight(bot)) {
            boolean rest = cfg.sleep && bot.memory().bedWarm && bot.health().hurt(bot) && System.currentTimeMillis() > noSleepUntil
                    && bot.memory().bed != null && bot.dim().equals(bot.memory().bed.dim)
                    && bot.memory().bed.pos().closerThan(p.blockPosition(), 48) && bedEnclosed(bot);
            return rest ? "sleep" : "shelter";
        }
        // a week awake: phantoms spawn over us from now on (ZymLabs: 168000 ticks out of bed). One
        // night's sleep resets it, before any come
        if (cfg.sleep && WorldView.isNight() && bot.memory().bed != null && System.currentTimeMillis() > noSleepUntil
                && bot.summoningPhantoms() && bot.memory().bed.pos().closerThan(p.blockPosition(), 128)
                && bot.dim().equals(bot.memory().bed.dim))
            return "sleep";
        // to heal, not to pass the night: CivFabric's night watch doesn't skip it, so a healthy bot
        // in bed just loses the night's work
        if (cfg.sleep && WorldView.isNight() && bot.memory().bed != null && System.currentTimeMillis() > noSleepUntil
                && bot.health().hurt(bot)
                && bot.memory().bed.pos().closerThan(p.blockPosition(), 128) && bot.dim().equals(bot.memory().bed.dim))
            return "sleep";
        // something on the shopping list a few steps away: pick it up on the way. Only between our
        // own goals (never on a job someone gave us), by day, healthy, and not under a blood moon
        if (reflex == null && (active == Layer.PROGRESS || active == Layer.IDLE) && bot.role().autonomous()
                && System.currentTimeMillis() > noDetourUntil && bot.ticks() % 40 == 0 && !WorldView.isNight()
                && !bot.pacifism().bloodMoon() && !bot.health().hurt(bot) && !wet && Inv.freeSlots() > 2) {
            Task doing = active == Layer.PROGRESS ? progress : idle;
            // only out of walking about: a detour mid-smelt came back to a furnace it had lost track of
            Task now = doing == null ? null : doing.leaf();
            boolean walking = now == null || now instanceof GatherTask || now instanceof ExploreTask || now instanceof GoToTask
                    || now instanceof HuntTask;
            if (walking) {
                detour = shopping.find(bot, b -> doing != null && doing.inChain(x -> x instanceof GatherTask g && g.gathers(b)));
                if (detour != null) return "detour";
            }
        }
        // a teammate close by would get more out of some of our food than we would (Spice of Fabric
        // pays little for the tenth loaf, a lot for the first): walk over and hand it across. Between
        // our own goals, by the same rules as a detour, and looked at every 10 s
        if (reflex == null && (active == Layer.PROGRESS || active == Layer.IDLE) && bot.role().autonomous()
                && System.currentTimeMillis() > noShareUntil && bot.ticks() % 200 == 100 && !bot.pacifism().bloodMoon()
                && !bot.health().hurt(bot) && !wet && !bot.team().inRange().isEmpty()) {
            noShareUntil = System.currentTimeMillis() + 10_000;
            gift = FoodShare.best(bot);
            if (gift != null) return "share";
        }
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
                if (t != null && Spawners.near(t.blockPosition(), lol.zym.zymbot.civ.Pacifism.SPAWNED_RANGE)) {
                    // far enough that the spawner goes quiet: it only spawns with a player within 16
                    bot.event("a spawner's " + t.getName().getString() + ": leaving its range");
                    yield new FleeTask(t, 32);
                }
                if (t != null && FightOdds.tridentDrowned(t)) {
                    // it throws for 8 from ten blocks: out of its reach, not just a step back
                    bot.event("a drowned with a trident at " + t.blockPosition().toShortString()
                            + ": keeping away (taking one on needs two armed teammates)");
                    yield new FleeTask(t, 24);
                }
                if (t != null && !FightOdds.canTake(bot, threats(bot)))
                    bot.event("outmatched by " + threats(bot).size() + " hostile(s) led by " + t.getName().getString() + ": getting away");
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
                bot.event((bot.pacifism().bloodMoon() ? "blood moon" + (bot.pacifism().superBloodMoon() ? " (super)" : "") : "storm night: drowned are hunting")
                        + ": taking shelter until dawn");
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
                boolean stranded = !bot.stuck() && bot.stranded();
                bot.event(stranded ? "stranded up here: looking for a safe way down"
                        : bot.player().isInWater() ? "stuck in water; climbing out" : "stuck underground; climbing out");
                yield new UnstuckTask(true, stranded);
            }
            case "bed" -> {
                bot.event("phantoms, and we're the one they come for (a week without sleep): to bed");
                yield new SleepTask(bot.memory().bed.pos());
            }
            case "receive" -> new ReceiveTask();
            case "forage" -> {
                noForageUntil = System.currentTimeMillis() + 90_000; // if the hunt fails, don't retry at once
                bot.event("hungry with no food; hunting");
                // and ask: a teammate stood beside a starving bot with food in its pack
                if (!bot.team().inRange().isEmpty()) bot.radio("all", "feed " + bot.name());
                yield Progression.food(bot);
            }
            case "sleep" -> new SleepTask(bot.memory().bed.pos());
            case "brace" -> {
                bot.event("blood moon forecast: heading home before dusk");
                yield new BraceTask();
            }
            case "detour" -> {
                shopping.tried(detour.at());
                noDetourUntil = System.currentTimeMillis() + 60_000; // until it ends, at the least
                bot.event("on the way: " + detour.want().what() + " at " + detour.at().toShortString() + " (shopping list)");
                yield new DetourTask(detour, bot.player().blockPosition());
            }
            case "share" -> {
                FoodShare.gave(gift);
                noShareUntil = System.currentTimeMillis() + 60_000;
                bot.event(String.format("food swap: %d %s to %s (worth %.0f to them, %.0f to us)", gift.count(),
                        FoodShare.shortId(gift.item()), gift.to(), gift.gain(), gift.loss()));
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(gift.item()));
                yield new GiveTask(gift.to(), ItemTarget.of(item, gift.count())).carriedOnly();
            }
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
