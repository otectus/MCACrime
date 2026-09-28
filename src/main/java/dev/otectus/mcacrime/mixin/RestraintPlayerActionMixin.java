package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.restraint.RestraintAction;
import dev.otectus.mcacrime.restraint.RestraintService;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses an off-hand swap from a subject whose arms are bound (0.7.5 M2.9).
 *
 * <p>{@code SWAP_ITEM_WITH_OFFHAND} arrives as a player-action packet, not as an interaction, so no
 * Forge event sees it. Everything else in the same packet — block breaking, item dropping, shield
 * release — is already covered by an event, which is why this injection tests the action and returns
 * immediately for every other value rather than gating the whole handler.
 *
 * <p>The narrowest possible scope: one action constant, one permission, and a HEAD cancel so the swap
 * never reaches the inventory.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RestraintPlayerActionMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void mcacrime$restraintBlocksOffhandSwap(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet == null
                || packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            return;
        }
        if (this.player != null
                && !RestraintService.policy(this.player).permits(RestraintAction.SWAP_OFFHAND)) {
            ci.cancel();
        }
    }
}
