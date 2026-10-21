package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Controls;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.nav.Nav;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Heal a hurt teammate with CivFabric's treatments: stitches (best from a Medic), a medkit or
 * first aid kit, or wound mush (which needs a sneak-click). With natural regeneration off this
 * and bed rest by a campfire are how players get health back.
 */
public class TreatTask extends Task {
    /** Best first. Wound mush is the one anyone can make. */
    public static final List<String> TREATMENTS = List.of("civfabric:first_aid_kit", "civfabric:medkit", "civfabric:stitches", "civfabric:wound_mush");

    private final Player patient;
    private float before = -1;
    private int clickedAt = -1;
    private long clickedTick;
    private int tries;

    public TreatTask(Player patient) {
        this.patient = patient;
    }

    @Override
    public String name() {
        return "Treat " + patient.getGameProfile().getName();
    }

    /**
     * What we may treat a teammate with: a Medic anything on the list, anyone else only wound mush
     * (stitches and kits are a Medic's job; the rest of us hand over a consumable or a bed instead).
     */
    public static int treatmentSlot(Bot bot) {
        for (String id : lol.zym.zymbot.brain.ClassPlan.isMedic(bot) ? TREATMENTS : List.of("civfabric:wound_mush")) {
            int slot = Inv.find(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(id));
            if (slot >= 0) return slot;
        }
        return -1;
    }

    /** Hurt enough to be worth a treatment. */
    public static boolean needsTreatment(Player p) {
        return p.isAlive() && p.getHealth() <= p.getMaxHealth() - 6;
    }

    @Override
    protected Status onTick(Bot bot) {
        var me = bot.player();
        if (!patient.isAlive() || patient.isRemoved()) return fail(patient.getGameProfile().getName() + " is gone");
        if (ticks > 20 * 60) return fail("couldn't reach " + patient.getGameProfile().getName());
        if (clickedAt >= 0) {
            if (patient.getHealth() > before) {
                Controls.sneak(false);
                bot.memory().count("treated");
                bot.event("treated " + patient.getGameProfile().getName() + " (" + Math.round(before) + " -> " + Math.round(patient.getHealth()) + ")");
                return Status.SUCCESS;
            }
            if (ticks - clickedAt < 30) return Status.RUNNING;
            Controls.sneak(false);
            if (bot.heard("too hungry to treat", clickedTick)) return fail("too hungry to treat anyone");
            if (bot.heard("already at full health", clickedTick)) return Status.SUCCESS;
            if (bot.heard("has to be applied by someone else", clickedTick) || bot.heard("only works on players", clickedTick)) return fail("that treatment can't be used here");
            if (++tries >= 3) return fail("treatment didn't take");
            clickedAt = -1;
        }
        if (!needsTreatment(patient)) return Status.SUCCESS;
        if (me.distanceTo(patient) > 2.5) {
            if (ticks % 10 == 1) Nav.goNear(patient.blockPosition(), 1);
            return Status.RUNNING;
        }
        Nav.stop();
        int slot = treatmentSlot(bot);
        if (slot < 0 || !Inv.select(slot)) return fail("nothing to treat with");
        ItemStack held = Inv.held();
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        // stitches and medkits heal on a plain click; wound mush and first aid kits want a sneak-click
        Controls.sneak(id.equals("civfabric:wound_mush") || id.equals("civfabric:first_aid_kit"));
        Controls.lookAt(patient.getEyePosition().subtract(0, 0.4, 0), 90f);
        before = patient.getHealth();
        Minecraft.getInstance().gameMode.interact(me, patient, InteractionHand.MAIN_HAND);
        me.swing(InteractionHand.MAIN_HAND);
        clickedAt = ticks;
        clickedTick = bot.ticks();
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        Controls.sneak(false);
        Nav.stop();
    }
}
