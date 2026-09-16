package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.job.ThiefPoiValidationControl;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ValidateNearbyPoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Wraps vanilla's nearby-POI validator so an employed Thief's claim is not revoked merely because
 * nobody can currently look at it (0.7.2 §10.4).
 *
 * <p>The factory is the target, at RETURN, for the same reason {@code NativeJobAssignmentMixin} uses
 * one: the declarative behaviour's synthetic lambda names are compiler artefacts and binding to them
 * would be a mixin that breaks on an unrelated Mojang rebuild.
 *
 * <p>MCA retains this vanilla behaviour for both site memories in all three supported builds, so the
 * wrapper covers MCA villagers as well as vanilla ones.
 */
@Mixin(ValidateNearbyPoi.class)
public abstract class ThiefPoiValidationMixin {

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void mcacrime$deferUnresolvedSites(Predicate<Holder<PoiType>> predicate,
                                                      MemoryModuleType<GlobalPos> memory,
                                                      CallbackInfoReturnable<BehaviorControl<LivingEntity>> cir) {
        BehaviorControl<LivingEntity> original = cir.getReturnValue();
        if (original != null && !(original instanceof ThiefPoiValidationControl)) {
            cir.setReturnValue(new ThiefPoiValidationControl(original, memory));
        }
    }
}
