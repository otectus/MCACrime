package dev.otectus.mcacrime.mixin;

import dev.otectus.mcacrime.job.ThiefWorkRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;

/**
 * Makes a villager's station work yield to custody, sleep, panic, enforcement and an active crime
 * action (0.7.2 §10.3).
 *
 * <p>Written for employed Thieves and since generalised: {@link ThiefWorkRegistry#needsWrapping} now
 * also answers yes for a villager MCA: Crime holds a live activity claim on, so an arrest, an escort
 * or a spell in a cell start-gates whatever work behaviours that villager has — vanilla's, MCA's or a
 * settlement companion's. This mixin itself is unchanged by that, and still targets only vanilla
 * {@link Brain}.
 *
 * <p>Scoped twice over. {@link ThiefWorkRegistry#needsWrapping} is the first statement and is normally
 * one {@code isEmpty()} on each of two empty sets, so a world with no thieves and no enforcement in
 * progress pays nothing for this being installed; and the wrap itself happens once per brain instance,
 * touching only that brain's {@link Activity#WORK} entries.
 *
 * <p>A brain refresh is why this is at {@code tick} rather than applied once at commit: MCA's
 * profession setter builds a brand-new brain with a brand-new task list, so anything installed
 * earlier would be silently discarded by the very call that creates the Thief.
 */
@Mixin(Brain.class)
public abstract class ThiefBrainMixin {

    @Shadow
    @Final
    private Map<Integer, Map<Activity, Set<?>>> availableBehaviorsByPriority;

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"))
    private void mcacrime$yieldThiefWork(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        if (ThiefWorkRegistry.needsWrapping(entity)) {
            ThiefWorkRegistry.wrapWorkBehaviors((Brain<?>) (Object) this, availableBehaviorsByPriority);
        }
    }
}
