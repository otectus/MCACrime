package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.restraint.RestraintAction;
import dev.otectus.mcacrime.restraint.RestraintService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a leg-restrained subject jumping (0.7.5 M2.9).
 *
 * <p>A mixin because Forge has no cancellable jump event: {@code LivingJumpEvent} fires <em>after</em>
 * the upward velocity has been written, so the previous implementation had to zero the motion
 * afterwards and mark the player for a position correction. That works and looks like a rubber band.
 * Cancelling at the head of {@code jumpFromGround} means the jump simply never happens.
 *
 * <p>Narrow on purpose: one vanilla method, a HEAD injection, one question asked of the server's own
 * policy, and no effect on anything that is not a restrained player. Knockback, elytra, bubble columns
 * and every other upward force go through different paths and are untouched — a restrained subject can
 * still be thrown, they simply cannot hop.
 */
@Mixin(LivingEntity.class)
public abstract class RestraintJumpMixin {

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void mcacrime$restraintBlocksJump(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer)) {
            return; // the server decides; the client's own attempt is corrected by the server anyway
        }
        if (!RestraintService.policy(self).permits(RestraintAction.JUMP)) {
            ci.cancel();
        }
    }
}
