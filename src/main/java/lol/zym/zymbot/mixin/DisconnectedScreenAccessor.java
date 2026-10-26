package lol.zym.zymbot.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why we were thrown off: some reasons mean don't go back on our own. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("details")
    DisconnectionDetails zymbot$details();
}
