package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.inv.Inv;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import java.util.List;

/**
 * Whether to take on what's after us or get away, after altoclef's MobDefenseChain: each mob counts
 * one, worse ones more, and what we can handle comes from armour, weapon and shield. Unlike
 * altoclef we count armed teammates standing with us. A drowned with a trident hits for 8 from ten
 * blocks away: nobody takes one on without two armed teammates alongside, whatever the sums say.
 */
public final class FightOdds {
    /** Teammates this close fight alongside us. */
    static final double WITH_US = 12;

    private FightOdds() {}

    public static boolean tridentDrowned(LivingEntity e) {
        return e instanceof Drowned d && (d.getMainHandItem().is(Items.TRIDENT) || d.getOffhandItem().is(Items.TRIDENT));
    }

    /** How much trouble these mobs are together (altoclef's dangerousness score). */
    static int danger(List<LivingEntity> mobs) {
        int n = 0;
        for (LivingEntity e : mobs) {
            n++;
            if (e instanceof EnderMan || e instanceof Slime || e instanceof Blaze) n += 1;
            else if (tridentDrowned(e)) n += 5;
        }
        return n;
    }

    /** What one player can take on: armour, then a weapon (bare hands count for nothing), then a shield. */
    static double kit(int armor, ItemStack weapon, boolean shield) {
        boolean armed = weapon.getItem() instanceof SwordItem || weapon.getItem() instanceof AxeItem || weapon.getItem() instanceof TridentItem;
        return armor * 3.6 / 20.0 + (armed ? Inv.attackDamage(weapon) * 0.4 + (shield ? 3 : 0) : 0);
    }

    public static double ourKit(Bot bot) {
        var p = bot.player();
        int w = Inv.bestWeapon();
        ItemStack weapon = w >= 0 ? p.getInventory().getItem(w) : ItemStack.EMPTY;
        boolean shield = p.getOffhandItem().is(Items.SHIELD) || Inv.find(s -> s.is(Items.SHIELD)) >= 0;
        return kit(p.getArmorValue(), weapon, shield);
    }

    /** Armed teammates close enough to fight alongside us (we see their armour and what they hold). */
    static List<Player> allies(Bot bot) {
        var p = bot.player();
        return bot.level().players().stream()
                .filter(o -> o != p && o.isAlive() && o.distanceTo(p) < WITH_US && bot.isTeammate(o.getGameProfile().getName())
                        && kit(0, o.getMainHandItem(), false) > 0)
                .map(o -> (Player) o).toList();
    }

    /** Fight these, rather than get away? */
    static boolean canTake(Bot bot, List<LivingEntity> mobs) {
        List<Player> allies = allies(bot);
        if (mobs.stream().anyMatch(FightOdds::tridentDrowned) && allies.size() < 2) return false;
        double ours = ourKit(bot);
        for (Player o : allies) ours += kit(o.getArmorValue(), o.getMainHandItem(), o.getOffhandItem().is(Items.SHIELD));
        // the careful (the team's Blacksmith) only take on what they'd beat with room to spare
        return Math.ceil(ours) >= danger(mobs) + (bot.careful() ? 2 : 0);
    }
}
