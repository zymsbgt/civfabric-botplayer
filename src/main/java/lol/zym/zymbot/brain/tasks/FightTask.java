package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Fight one hostile mob until it's dead, it leaves, or we get too hurt (the brain then flees). */
public class FightTask extends Task {
    private final LivingEntity enemy;

    public FightTask(LivingEntity enemy) {
        this.enemy = enemy;
    }

    @Override
    public String name() {
        return "Fight " + enemy.getType().getDescription().getString();
    }

    public LivingEntity enemy() {
        return enemy;
    }

    @Override
    protected Status onTick(Bot bot) {
        if (!enemy.isAlive()) {
            bot.memory().count("kills");
            bot.pacifism().struck(enemy, true);
            return Status.SUCCESS;
        }
        if (enemy.distanceTo(bot.player()) > 20 || ticks > 20 * 60) return Status.SUCCESS;
        engage(bot, enemy);
        return Status.RUNNING;
    }

    /** Close in and hit when the attack is recharged. Never used on players. */
    public static void engage(Bot bot, LivingEntity target) {
        if (target instanceof Player) return;
        var p = bot.player();
        double d = p.distanceTo(target);
        if (d > 3.0) {
            if (bot.ticks() % 10 == 0) Nav.goNear(target.blockPosition(), 1);
            return;
        }
        Nav.stop();
        int w = Inv.bestWeapon();
        if (w >= 0) Inv.select(w);
        Controls.lookAt(target.getEyePosition().subtract(0, 0.3, 0), 60f);
        if (p.getAttackStrengthScale(0.5f) >= 0.95f && p.hasLineOfSight(target)) {
            Minecraft.getInstance().gameMode.attack(p, target);
            bot.pacifism().struck(target, false);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Nav.stop();
    }
}
