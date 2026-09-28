package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.restraint.RestraintAction;
import dev.otectus.mcacrime.restraint.RestraintService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server-side answer to a forged inventory click (0.7.5 M2.9).
 *
 * <p>A client whose inventory screen is suppressed can still send the click packet, and that packet is
 * handled by {@code AbstractContainerMenu.clicked} with no Forge event in front of it. Without this,
 * "a restrained player cannot rearrange their inventory" would be a client-side suggestion — which is
 * the class of hole the whole 0.7.5 design is written against.
 *
 * <p>Cancelling at HEAD means no slot is touched and no cursor stack is created, so there is nothing
 * to roll back. The client's own view is corrected by the menu's next broadcast.
 *
 * <p>Deliberately narrow: only a restrained <em>server</em> player whose composed policy denies
 * inventory mutation, and only that. Every other click by every other player goes through untouched.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class RestraintContainerClickMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void mcacrime$restraintBlocksInventoryMutation(int slotId, int button, ClickType clickType,
                                                           Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        if (!RestraintService.policy(player).permits(RestraintAction.MUTATE_INVENTORY)) {
            ci.cancel();
        }
    }
}
