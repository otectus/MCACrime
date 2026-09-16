package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the recovery ledger honest about effects this mod did not necessarily apply (0.7.2 §13.6).
 *
 * <p>Three moments matter and none of them is the application itself, which
 * {@code SandExposureService} already records:
 *
 * <ul>
 *   <li><b>Added by somebody else.</b> Another mod may legitimately apply {@code sand_blinded}
 *       through the effect registry. It gets the same recovery protection and no invented Sand Bottle
 *       attacker — a status effect is not a crime report.</li>
 *   <li><b>Expired or cured.</b> The recovery window starts from the moment sight returns, so milk is
 *       a real counter and not a way to reset the timer and be blinded again immediately.</li>
 *   <li><b>Server stopping.</b> The transient cache is dropped. The saved per-entity copies are left
 *       alone, so a restart inside a window still refuses the next bottle.</li>
 * </ul>
 *
 * <p>NeoForge's {@code MobEffectEvent.Remove} names the effect as a {@link Holder}, so the identity
 * test is holder equality against the registration rather than a raw {@code MobEffect} reference.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class SandEventHandlers {

    private SandEventHandlers() {
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!isSand(event.getEffectInstance()) || !isServer(event.getEntity())) {
            return;
        }
        LivingEntity entity = event.getEntity();
        long now = SandRecoveryLedger.clock(entity);
        SandRecovery.State state = SandRecoveryLedger.stateOf(entity, now);
        if (state.activeUntil() > now) {
            // Our own application already wrote the schedule; do not move it. A second bottle never
            // reaches here anyway, because the policy refuses an already-blinded candidate.
            return;
        }
        SandRecoveryLedger.applied(entity, now, event.getEffectInstance().getDuration(), recovery());
    }

    @SubscribeEvent
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        endedEarly(event.getEntity(), event.getEffect());
    }

    @SubscribeEvent
    public static void onEffectExpired(MobEffectEvent.Expired event) {
        endedEarly(event.getEntity(),
                event.getEffectInstance() == null ? null : event.getEffectInstance().getEffect());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SandRecoveryLedger.clearAll();
    }

    private static void endedEarly(LivingEntity entity, @Nullable Holder<MobEffect> effect) {
        if (effect == null || !CrimeEffects.SAND_BLINDED.isBound()
                || !effect.is(CrimeEffects.SAND_BLINDED.getKey()) || !isServer(entity)) {
            return;
        }
        SandRecoveryLedger.cured(entity, SandRecoveryLedger.clock(entity), recovery());
    }

    private static boolean isSand(@Nullable MobEffectInstance instance) {
        return instance != null && CrimeEffects.SAND_BLINDED.isBound()
                && instance.is(CrimeEffects.SAND_BLINDED);
    }

    private static boolean isServer(LivingEntity entity) {
        return entity != null && !entity.level().isClientSide() && entity.level().getServer() != null;
    }

    private static int recovery() {
        return McaCrimeConfig.COMMON.sandRecoveryTicks.get();
    }
}
