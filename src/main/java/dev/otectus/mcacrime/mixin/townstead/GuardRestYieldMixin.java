package dev.otectus.mcacrime.mixin.townstead;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadMixinStatus;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Stops Townstead's guard-rest ticker from erasing a walk order MCA: Crime is currently issuing.
 *
 * <h2>The collision</h2>
 *
 * <p>Townstead keeps off-duty guards from patrolling aimlessly: during {@code REST}, with no attack
 * target, not drowsy, not asleep and with no home, it erases the guard's {@code WALK_TARGET} and
 * {@code LOOK_TARGET} and stops their navigation. Every tick.
 *
 * <p>MCA: Crime's lawful approach is a walk target and no attack target — that is what makes it
 * lawful. So a guard told to walk over and challenge a suspect at night has that order erased before
 * the next guard scan can even see it. MCA: Crime re-issues, Townstead erases, and the guard stands
 * still while the ledger fills with pursuits that never start. Crime scans every ten ticks; the
 * ticker runs every one, so no amount of re-asserting from outside can win. The order has to be
 * defended where it is erased.
 *
 * <h2>Why redirects, and why these three</h2>
 *
 * <p>{@code GuardRestEnforcerTicker.tick} takes MCA's villager type, so its descriptor cannot be
 * written down in this mod and the method is matched by name alone with {@code remap = false}. What
 * the hook then intercepts are the three <em>vanilla</em> calls inside it — two
 * {@code Brain.eraseMemory} and one {@code PathNavigation.stop}, each with a vanilla owner in
 * Townstead's own bytecode. Those carry {@code remap = true} on the {@code @At} so the annotation
 * processor writes them into {@code mcacrime.refmap.json} and they resolve both in a development
 * runtime (Mojang names) and in a production jar (SRG). A hand-written SRG name with
 * {@code remap = false} would have worked only in the second.
 *
 * <p>One redirect covers both {@code eraseMemory} calls: {@code WALK_TARGET} and {@code LOOK_TARGET}
 * are erased by the same instruction shape and the answer is the same for both, so no {@code ordinal}
 * is used and a Townstead that erased a third memory would be covered too.
 *
 * <h2>Whose villager is this?</h2>
 *
 * <p>Each redirect captures the target method's villager argument as a vanilla
 * {@link LivingEntity} with {@code @Coerce}. This works with either MCA package layout and
 * protects the actual guard even when a caller runs the ticker outside Forge's entity tick.
 */
@Mixin(targets = "com.aetherianartificer.townstead.tick.GuardRestEnforcerTicker", remap = false)
public abstract class GuardRestYieldMixin {

    /**
     * Keeps {@code WALK_TARGET} and {@code LOOK_TARGET} while a claim forbids rest travel.
     *
     * <p>{@code expect = 1} rather than 2 on purpose: two invocations match today, and a Townstead
     * that stopped erasing the look target would still be correctly handled — what is worth a warning
     * is matching none at all.
     */
    @Redirect(method = "tick", remap = false, require = 0, expect = 1,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/Brain;eraseMemory"
                            + "(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)V",
                    remap = true))
    private static void mcacrime$keepWalkOrder(Brain<?> brain, MemoryModuleType<?> memory,
                                                @Coerce LivingEntity entity) {
        if (!mcacrime$yields(entity)) {
            brain.eraseMemory(memory);
        }
    }

    /** The same decision for the navigation stop that follows the two erases. */
    @Redirect(method = "tick", remap = false, require = 0, expect = 1,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V",
                    remap = true))
    private static void mcacrime$keepPath(PathNavigation navigation, @Coerce LivingEntity entity) {
        if (!mcacrime$yields(entity)) {
            navigation.stop();
        }
    }

    /**
     * Whether Townstead must stand aside for this villager right now.
     *
     * <p>Marks the hook as fired first and unconditionally — that is the evidence
     * {@code /crime debug townstead} reports, and it has to be recorded on the ordinary pass where
     * nothing is claimed as well as on the rare one where something is. Then: no entity, no claim, or
     * a claim that tolerates rest travel all answer "no", and the original call runs.
     *
     * <p>Never throws. It runs inside another mod's per-tick method, where an exception would be
     * attributed to Townstead and would take its guard handling down with it; a thrown error here
     * would also skip the original call, which is the worst of both outcomes.
     */
    private static boolean mcacrime$yields(LivingEntity entity) {
        try {
            TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_GUARD_REST);
            UUID villager = entity == null ? null : entity.getUUID();
            if (villager == null) {
                return false;
            }
            if (CrimeActivityRegistry.permits(villager, CrimeActivityOperation.REST_TRAVEL)) {
                return false;
            }
            // The kill switch, read last and only here: on the ordinary pass there is no claim, so a
            // config lookup never happens on the hot path, and an operator who switched the
            // integration off gets Townstead's behaviour back exactly as it ships.
            return TownsteadBridge.integrationEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
