package lol.zym.zymbot.hud;

import lol.zym.zymbot.BotConfig;
import lol.zym.zymbot.mixin.OptionInstanceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

/**
 * Turns the gamma up while the bot is running ({@code taskGamma}; the slider's "Bright" is 1.0),
 * so whoever watches can see what it is doing at night and underground, and puts the player's
 * own setting back when it pauses, leaves the world or the game closes.
 */
public final class Brightness {
    private Brightness() {}

    private static Double saved;

    public static void update(Minecraft mc, boolean running) {
        double want = BotConfig.get().taskGamma;
        OptionInstance<Double> gamma = mc.options.gamma();
        if (running && want > 0) {
            if (saved == null) saved = gamma.get();
            if (gamma.get() != want) ((OptionInstanceAccessor) (Object) gamma).zymbot$setValue(want);
        } else {
            restore(mc);
        }
    }

    public static void restore(Minecraft mc) {
        if (saved == null) return;
        ((OptionInstanceAccessor) (Object) mc.options.gamma()).zymbot$setValue(saved);
        saved = null;
    }
}
