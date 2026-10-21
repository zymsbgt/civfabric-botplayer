package lol.zym.zymbot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import lol.zym.zymbot.brain.Commands;
import lol.zym.zymbot.chat.ChatListener;
import lol.zym.zymbot.hud.Brightness;
import lol.zym.zymbot.hud.StatusHud;
import lol.zym.zymbot.nav.Nav;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ZymBotMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("zymbot");

    private static KeyMapping toggle;
    private static ServerData lastServer;
    private static long reconnectAt;

    @Override
    public void onInitializeClient() {
        BotConfig.load();
        lol.zym.zymbot.team.Updater.start();
        toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.zymbot.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.zymbot"));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(() -> {
            lastServer = mc.getCurrentServer();
            reconnectAt = 0;
            Nav.resetConfigured();
            if (mc.player != null) Bot.start(mc.player);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            Bot.end();
            if (BotConfig.get().autoReconnect && lastServer != null) reconnectAt = System.currentTimeMillis() + 30_000;
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Bot bot = Bot.get();
            while (toggle.consumeClick()) {
                if (bot != null) {
                    bot.setEnabled(!bot.enabled());
                    bot.feedback(bot.enabled() ? "started (" + bot.role().name().toLowerCase() + ")" : "paused");
                }
            }
            if (bot != null) bot.tick();
            Brightness.update(mc, bot != null && bot.enabled());
            if (bot == null) {
                maybeReconnect(mc);
                maybeOpenDevWorld(mc);
                skipExperimentalWarning(mc);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(Brightness::restore);
        ChatListener.register();
        StatusHud.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommandManager.literal("zymbot")
                        .executes(c -> {
                            Bot b = Bot.get();
                            c.getSource().sendFeedback(Component.literal(b == null ? "not in a world" : b.statusLine()));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("reload").executes(c -> {
                            BotConfig.load();
                            Nav.configure();
                            c.getSource().sendFeedback(Component.literal("config reloaded"));
                            return 1;
                        }))
                        .then(ClientCommandManager.argument("command", StringArgumentType.greedyString()).suggests(ZymBotMod::suggest).executes(c -> {
                            Bot b = Bot.get();
                            if (b == null) return 0;
                            String out = Commands.execute(b, b.name(), StringArgumentType.getString(c, "command"));
                            for (String line : out.split("\n")) c.getSource().sendFeedback(Component.literal("§b[ZymBot]§r " + line));
                            return 1;
                        }))));
        LOG.info("ZymBot loaded");
    }

    /** Tab completion for "/zymbot <command>": the command word, then a few known second words. */
    private static CompletableFuture<Suggestions> suggest(CommandContext<?> ctx, SuggestionsBuilder b) {
        String rest = b.getRemaining().toLowerCase(Locale.ROOT);
        int space = rest.indexOf(' ');
        if (space < 0) {
            for (String w : Commands.WORDS) if (w.startsWith(rest)) b.suggest(w);
            return b.buildFuture();
        }
        String rest2 = rest.substring(space + 1);
        if (rest2.contains(" ")) return b.buildFuture();
        SuggestionsBuilder b2 = b.createOffset(b.getStart() + space + 1);
        for (String w : Commands.NEXT_WORDS.getOrDefault(rest.substring(0, space), List.of())) if (w.startsWith(rest2)) b2.suggest(w);
        return b2.buildFuture();
    }

    private static boolean devWorldOpened;

    /**
     * Testing aid: with -Dzymbot.devWorld=NAME the client creates (or opens) that singleplayer
     * survival world as soon as the title screen shows, so a bot can be tried without a server.
     * ./gradlew runClient -PdevWorld=zymbot-test
     */
    private static void maybeOpenDevWorld(Minecraft mc) {
        String name = System.getProperty("zymbot.devWorld");
        if (name == null || devWorldOpened || !(mc.screen instanceof TitleScreen)) return;
        devWorldOpened = true;
        var flows = mc.createWorldOpenFlows();
        if (mc.getLevelSource().levelExists(name)) {
            flows.openWorld(name, () -> mc.setScreen(new TitleScreen()));
        } else {
            var settings = new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.SURVIVAL, false,
                    net.minecraft.world.Difficulty.NORMAL, true /* cheats, so test scripts can /give and /setblock */, new net.minecraft.world.level.GameRules(),
                    net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            flows.createFreshLevel(name, settings, net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, new TitleScreen());
        }
    }

    /**
     * The pack's worlds use experimental features, so opening one asks whether to back it up
     * first. A bot can't answer, so press "I know what I'm doing!" (load without a backup).
     */
    private static void skipExperimentalWarning(Minecraft mc) {
        if (!(mc.screen instanceof BackupConfirmScreen screen)) return;
        for (var child : screen.children()) {
            if (child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals("selectWorld.backupJoinSkipButton")) {
                LOG.info("Opening the world past the experimental-features warning");
                b.onPress();
                return;
            }
        }
    }

    private static void maybeReconnect(Minecraft mc) {
        if (reconnectAt == 0 || System.currentTimeMillis() < reconnectAt || lastServer == null) return;
        if (!(mc.screen instanceof DisconnectedScreen || mc.screen instanceof TitleScreen)) return;
        reconnectAt = 0;
        LOG.info("Reconnecting to {}", lastServer.ip);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
    }
}
