package lol.zym.zymbot.hud;

import lol.zym.zymbot.Bot;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Top-left overlay: role, state, current task chain and the last few events. */
public final class StatusHud {
    private StatusHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register((g, delta) -> {
            Bot bot = Bot.get();
            Minecraft mc = Minecraft.getInstance();
            if (bot == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
            int y = 4;
            int color = bot.enabled() ? 0x7FDBFF : 0xAAAAAA;
            String head = "ZymBot " + bot.role().name().toLowerCase() + (bot.enabled() ? "" : " (paused — K to start)");
            g.drawString(mc.font, head, 4, y, color, true);
            y += 10;
            String task = bot.brain().describe();
            for (String line : wrap(task, 70)) {
                g.drawString(mc.font, line, 4, y, 0xFFFFFF, true);
                y += 10;
            }
            List<String> ev = bot.recentEvents();
            for (int i = Math.max(0, ev.size() - 3); i < ev.size(); i++) {
                g.drawString(mc.font, ev.get(i), 4, y, 0xBBBBBB, true);
                y += 10;
            }
        });
    }

    private static List<String> wrap(String s, int width) {
        if (s.length() <= width) return List.of(s);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < s.length(); i += width) out.add(s.substring(i, Math.min(s.length(), i + width)));
        return out;
    }
}
