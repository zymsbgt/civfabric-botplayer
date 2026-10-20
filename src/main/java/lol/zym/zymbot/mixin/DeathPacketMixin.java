package lol.zym.zymbot.mixin;

import lol.zym.zymbot.Bot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server says we died. With instant respawn (as on the ZymLabs server) the client never
 * shows a dead player or the death screen, so this packet is the only sure sign of a death.
 */
@Mixin(ClientPacketListener.class)
public class DeathPacketMixin {
    @Inject(method = "handlePlayerCombatKill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER))
    private void zymbot$died(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Bot bot = Bot.get();
        var p = Minecraft.getInstance().player;
        if (bot != null && p != null && packet.playerId() == p.getId()) bot.onKilled();
    }
}
