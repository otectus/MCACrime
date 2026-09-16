package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.job.NativeJobAssignmentControl;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps the behaviour vanilla's factory returns, so a Mask Station is assigned through Crime's
 * transaction and every other job site is untouched (0.7.2 §10.1).
 *
 * <p>The <em>factory</em> is the target, at RETURN, rather than the declarative behaviour's synthetic
 * lambda. Those lambda names are compiler artefacts with no stability guarantee whatsoever, and a
 * mixin bound to {@code lambda$create$4} would break on any Mojang rebuild that reordered the file.
 * The returned {@link BehaviorControl} is a published type with five methods, so wrapping it is both
 * stable and complete.
 *
 * <p>MCA keeps this vanilla behaviour in its own occupational task list (verified in all three
 * supported builds), which is why intercepting it covers MCA as well as vanilla villagers.
 */
@Mixin(AssignProfessionFromJobSite.class)
public abstract class NativeJobAssignmentMixin {

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void mcacrime$routeMaskStations(CallbackInfoReturnable<BehaviorControl<Villager>> cir) {
        BehaviorControl<Villager> original = cir.getReturnValue();
        if (original != null && !(original instanceof NativeJobAssignmentControl)) {
            cir.setReturnValue(new NativeJobAssignmentControl(original));
        }
    }
}
