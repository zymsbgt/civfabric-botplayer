package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;

/**
 * A unit of behaviour that runs over many ticks. Tasks are cheap to throw away: the brain
 * cancels and recreates them freely, so they re-check the world each tick instead of trusting
 * what they saw when they started.
 */
public abstract class Task {
    public enum Status { RUNNING, SUCCESS, FAILURE }

    protected Task child;
    private String failure;
    protected int ticks;

    public abstract String name();

    /** Advance one tick. */
    protected abstract Status onTick(Bot bot);

    public final Status tick(Bot bot) {
        ticks++;
        return onTick(bot);
    }

    /** Run the current child; clears it when finished and returns its result. */
    protected Status runChild(Bot bot) {
        if (child == null) return Status.SUCCESS;
        Status s = child.tick(bot);
        if (s != Status.RUNNING) {
            if (s == Status.FAILURE) failure = child.failureReason();
            child.stop(bot);
            child = null;
        }
        return s;
    }

    protected void setChild(Bot bot, Task t) {
        if (child != null) child.stop(bot);
        child = t;
    }

    protected Status fail(String why) {
        failure = name() + ": " + why;
        return Status.FAILURE;
    }

    public String failureReason() {
        return failure == null ? name() + " failed" : failure;
    }

    /** Release anything held (keys, Baritone goals, open screens). */
    public void stop(Bot bot) {
        if (child != null) {
            child.stop(bot);
            child = null;
        }
    }

    /** "Obtain stone_pickaxe > Gather cobblestone > Mine 12,64,3" */
    public String chain() {
        return child == null ? name() : name() + " > " + child.chain();
    }
}
