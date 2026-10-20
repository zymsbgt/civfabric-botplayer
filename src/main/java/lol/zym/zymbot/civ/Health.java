package lol.zym.zymbot.civ;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.BotConfig;
import net.minecraft.world.effect.MobEffects;

/**
 * Whether health comes back by itself. The ZymLabs server turns natural regeneration off (on
 * Hard), so health only returns from bed rest by a campfire, a medic's stitches, potions or
 * healing food: it's a resource to spend carefully. We find out by watching: a well-fed bot
 * that doesn't heal for 30 s has no regeneration.
 */
public final class Health {
    private float last = -1;
    private long lastDrop, lastRise;

    public void tick(Bot bot) {
        var p = bot.player();
        float h = p.getHealth();
        long t = bot.ticks();
        if (last < 0) {
            last = h;
            lastDrop = lastRise = t;
            return;
        }
        boolean helped = p.hasEffect(MobEffects.REGENERATION) || p.hasEffect(MobEffects.HEAL) || p.isSleeping();
        if (h < last) lastDrop = t;
        if (h > last) {
            lastRise = t;
            if (!helped && t - bot.lastHealedAt() > 100) bot.memory().naturalRegen = true;
        }
        if (h < p.getMaxHealth() - 1 && p.getFoodData().getFoodLevel() >= 18 && !helped
                && t - lastDrop > 600 && t - lastRise > 600 && t - bot.lastHealedAt() > 600) {
            if (bot.memory().naturalRegen == null || bot.memory().naturalRegen) bot.event("health isn't coming back on its own: playing it safe");
            bot.memory().naturalRegen = false;
            lastRise = t; // don't re-announce every tick
        }
        last = h;
    }

    /** Until seen otherwise, assume a CivFabric server doesn't regenerate. */
    public boolean naturalRegen(Bot bot) {
        Boolean r = bot.memory().naturalRegen;
        return r != null ? r : !bot.civ().present();
    }

    /** Health at which to stop fighting and run: much earlier when it won't come back. */
    public float fleeAt(Bot bot) {
        int cfg = BotConfig.get().fleeHealth;
        return naturalRegen(bot) ? cfg : Math.max(cfg, 12);
    }

    /** Too hurt to take risks: rest near home until healed. */
    public boolean fragile(Bot bot) {
        return !naturalRegen(bot) && bot.player().getHealth() <= 10;
    }

    public boolean hurt(Bot bot) {
        var p = bot.player();
        return p.getHealth() <= p.getMaxHealth() - 6;
    }
}
