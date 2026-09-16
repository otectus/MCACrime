package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.effect.SandBlindness;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The smallest vanilla hook that makes sand actually blind a mob (0.7.2 §13.5).
 *
 * <p>{@code Sensing} is where every goal-driven mob asks "can I see that". Melee approach, ranged
 * aiming, target validation and look control all funnel through this one method, so gating it is the
 * whole integration rather than a rewrite of anybody's AI.
 *
 * <p>Injected at HEAD, which is <em>before</em> the cached-positive return. Vanilla remembers a
 * successful sight check for the rest of the tick; injecting after that cache would let a mob that
 * looked at its target one tick before the sand landed keep seeing it for free.
 *
 * <p>What it does not do: it never returns true where vanilla said false, it never clears a target,
 * and it stops applying the instant the effect ends. A blinded mob keeps its target and keeps walking
 * toward where that target was — close contact still works, because
 * {@link SandBlindness#blocksSight} exempts it.
 */
@Mixin(Sensing.class)
public abstract class SandSensingMixin {

    @Shadow
    @Final
    private Mob mob;

    @Inject(method = "hasLineOfSight", at = @At("HEAD"), cancellable = true)
    private void mcacrime$sandBlocksLineOfSight(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (SandBlindness.blocksSight(this.mob, target)) {
            cir.setReturnValue(false);
        }
    }
}
