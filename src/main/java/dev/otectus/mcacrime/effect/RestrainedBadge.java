package dev.otectus.mcacrime.effect;

import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import org.jetbrains.annotations.Nullable;

/**
 * Keeps the display-only {@code mcacrime:restrained} badge in step with what is actually worn.
 *
 * <p>Driven from {@code RestraintService.publish}, which runs after every change to a subject's
 * physical state, so the badge follows the state rather than the other way round. It is applied
 * infinite, invisible-particled and hidden from the HUD icon row only in the sense that it is
 * ambient: the point is a quiet status line, not a screen full of swirls.
 *
 * <p>Nothing reads the badge back. {@link #shouldShow} is the whole decision and it is pure, so the
 * rule — "anything worn, and only then" — is assertable without a server, and a milk bucket that
 * removes the icon cannot remove a restraint.
 */
public final class RestrainedBadge {

    private RestrainedBadge() {
    }

    /** Whether a subject in this state should carry the badge. */
    public static boolean shouldShow(@Nullable PhysicalRestraintState state) {
        return state != null && state.restrained();
    }

    /**
     * Applies or clears the badge to match {@code state}.
     *
     * <p>Server-side only, and silent on anything it cannot do: a badge is feedback, and a registry
     * that is not ready or an entity that refuses effects must never be able to stop a restraint from
     * going on.
     */
    public static void refresh(@Nullable LivingEntity subject, @Nullable PhysicalRestraintState state) {
        if (subject == null || subject.level().isClientSide()) {
            return;
        }
        try {
            // 1.21.1: a DeferredHolder *is* the Holder<MobEffect> every effect call now takes, so
            // there is nothing to unwrap — but it is only bound once the registry has run, and a
            // badge refresh can be reached from a test that never started one.
            if (!CrimeEffects.RESTRAINED.isBound()) {
                return;
            }
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect = CrimeEffects.RESTRAINED;
            if (shouldShow(state)) {
                if (subject.getEffect(effect) == null) {
                    subject.addEffect(new MobEffectInstance(effect, MobEffectInstance.INFINITE_DURATION,
                            0, true, false, true));
                }
            } else if (subject.getEffect(effect) != null) {
                subject.removeEffect(effect);
            }
        } catch (RuntimeException notReady) {
            // A badge is never worth an exception on the application path.
        }
    }
}
