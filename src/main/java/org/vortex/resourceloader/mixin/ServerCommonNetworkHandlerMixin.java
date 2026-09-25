package org.vortex.resourceloader.mixin;

import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vortex.resourceloader.ResourceLoaderMod;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
    private void resourceloader$handlePackStatus(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        Object self = this;
        if (self instanceof ServerGamePacketListenerImpl gameHandler) {
            ServerPlayer player = gameHandler.player;
            if (player != null && ResourceLoaderMod.getInstance() != null) {
                ResourceLoaderMod.getInstance().getEnforcer().onPackStatus(player, packet.action());
            }
        }
    }
}
