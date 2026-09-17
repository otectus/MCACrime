package dev.otectus.mcacrime.mixin.townstead;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses a Townstead reaction lock on a villager MCA: Crime is currently moving.
 *
 * <h2>What a reaction lock does, and why it has to be refused</h2>
 *
 * <p>Townstead freezes a villager for the length of a reaction: it stops their navigation, erases
 * their walk target every tick so the brain cannot restart, and restores the saved walk target when
 * the lock expires. That is correct for a villager going about their day and ruinous for one being
 * walked to a cell — the escort's walk order is erased on the next tick, the guard's prisoner stops
 * dead, and the escort either stalls or is quietly re-issued forever.
 *
 * <p>{@code OperationPolicy} already says which MCA: Crime activities can tolerate a freeze: standing
 * as law and sitting in custody can, an arrest, an escort, a pursuit and a chase cannot. This hook is
 * where that table binds: it refuses new locks and makes existing reactions yield after an escort
 * takes control. The old reaction's saved walk order is also discarded if it expires during escort.
 *
 * <h2>Why this shape</h2>
 *
 * <p>{@code ReactionLockTracker.lock} is the one Townstead entry point whose entire signature is
 * vanilla, which is what makes an ordinary {@code @Inject} with captured arguments legal here: the
 * villager arrives as a {@code LivingEntity} and no MCA type is named. Everything else about the
 * class is the usual discipline — the target is a dotted string so no Townstead reference reaches
 * this mod's bytecode, {@code remap = false} because every name in the signature is either
 * Townstead's or a class name (never obfuscated on 1.20.1 Forge), and {@code require = 0} so a
 * Townstead point release that moved the method degrades one capability instead of stopping the game.
 *
 * <p>The body cannot throw. It runs inside another mod's method on the server thread, where an
 * exception would be attributed to Townstead and would take the reaction system down with it.
 */
@Mixin(targets = "com.aetherianartificer.townstead.reaction.ReactionLockTracker", remap = false)
public abstract class ReactionLockGateMixin {

    /** A lock may predate the escort that now owns movement. */
    @Inject(method = "freeze(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 1)
    private static void mcacrime$yieldExistingLock(LivingEntity entity, CallbackInfo ci) {
        if (mcacrime$mustYield(entity)) {
            ci.cancel();
        }
    }

    /** Expiring a pre-escort reaction must not restore a walk order saved before the handover. */
    @Inject(method = "restoreWalkTarget", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0, expect = 1)
    private static void mcacrime$discardStaleWalk(@Coerce LivingEntity entity, WalkTarget saved,
                                                CallbackInfo ci) {
        if (mcacrime$mustYield(entity)) {
            ci.cancel();
        }
    }

    private static boolean mcacrime$mustYield(LivingEntity entity) {
        try {
            return entity != null && TownsteadBridge.integrationEnabled()
                    && !CrimeActivityRegistry.permits(entity.getUUID(), CrimeActivityOperation.REACTION_LOCK);
        } catch (Throwable ignored) {
            return false;
        }
    }


    /**
     * Cancels the lock when a live MCA: Crime claim does not allow one.
     *
     * <p>Marks the hook as having fired first and unconditionally: that is what lets
     * {@code /crime debug townstead} tell "Townstead moved the method" from "nothing has locked a
     * villager yet", and it has to be recorded even on the ordinary pass where nothing is claimed.
     */
    @Inject(method = "lock(Lnet/minecraft/world/entity/LivingEntity;JILnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0, expect = 1)
    private static void mcacrime$refuseLockWhileClaimed(LivingEntity entity, long gameTime, int lockTicks,
                                                        ResourceLocation reactionId, CallbackInfo ci) {
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_REACTION_LOCK);
            if (entity == null) {
                return;
            }
            if (!CrimeActivityRegistry.permits(entity.getUUID(), CrimeActivityOperation.REACTION_LOCK)
                    && TownsteadBridge.integrationEnabled()) {
                // The kill switch is read last, so the ordinary pass -- no claim on this villager --
                // never touches the config, and an operator who switched the integration off gets
                // Townstead's reaction locks back exactly as they ship.
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never propagate. A refused lock is a nicety; a crash inside Townstead's reaction
            // dispatcher is not, and an unclaimed villager must behave exactly as they do without
            // MCA: Crime installed.
        }
    }
}
