package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The movement and attack-speed penalties a restraint imposes (0.7.5 M2.8).
 *
 * <p>Attribute modifiers rather than {@code MobEffects.MOVEMENT_SLOWDOWN}, and the reason is worth
 * keeping written down: a potion effect shows in the inventory, emits particles and — decisively —
 * is curable with a bucket of milk, which would make every restraint a suggestion.
 *
 * <p>The cost of an attribute modifier is that a leaked one is permanent, so both are owned here with
 * fixed UUIDs, applied transiently, and removed by id rather than by value. Removing by id is what
 * makes {@link #clear} idempotent and what lets a modifier left behind by a crash be recognised and
 * removed instead of stacked with a second copy of itself.
 *
 * <p>The values come from the composed {@link RestrictionPolicy}, not from a phase: a subject whose
 * legs are free moves at full speed even while their arms are bound, which is the §7.1 matrix rather
 * than the legacy "restrained means slow" rule.
 */
public final class RestraintAttributes {

    /** Stable ids: a modifier left behind by a crash is recognised, not stacked. */
    private static final UUID MOVEMENT_ID = UUID.fromString("6f2b1c94-0d7a-4a1e-9c33-2a5b7e0d41c6");
    private static final UUID ATTACK_ID = UUID.fromString("2f8c3d71-5b4e-4a02-9d18-7c6a1f0b93d4");

    private static final String MOVEMENT_NAME = "mcacrime.restrained.movement";
    private static final String ATTACK_NAME = "mcacrime.restrained.attack";

    /** How much slower arm restraints make a swing. A fraction of total, like the movement penalty. */
    private static final double ATTACK_PENALTY = 0.5D;

    private RestraintAttributes() {
    }

    /**
     * Brings {@code subject}'s modifiers in line with {@code policy}.
     *
     * <p>One call does both directions: a policy that permits everything removes both modifiers, so
     * there is no separate "release" path that could be forgotten. Idempotent, because every
     * application removes by id first.
     */
    public static void apply(@Nullable LivingEntity subject, @Nullable RestrictionPolicy policy) {
        if (subject == null) {
            return;
        }
        RestrictionPolicy effective = policy == null ? RestrictionPolicy.unrestricted() : policy;
        set(subject, Attributes.MOVEMENT_SPEED, MOVEMENT_ID, MOVEMENT_NAME,
                effective.voluntaryMovement() ? 0.0D : movementPenalty());
        set(subject, Attributes.ATTACK_SPEED, ATTACK_ID, ATTACK_NAME,
                effective.attack() ? 0.0D : ATTACK_PENALTY);
    }

    /** Removes both modifiers. Safe for a subject that never had either. */
    public static void clear(@Nullable LivingEntity subject) {
        apply(subject, RestrictionPolicy.unrestricted());
    }

    private static void set(LivingEntity subject, net.minecraft.world.entity.ai.attributes.Attribute attribute,
                            UUID id, String name, double penalty) {
        AttributeInstance instance = subject.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        // Always remove first, then add. Removing by id is what makes a repeat call idempotent rather
        // than a second copy of the same penalty.
        instance.removeModifier(id);
        if (penalty <= 0.0D) {
            return;
        }
        instance.addTransientModifier(new AttributeModifier(id, name, -penalty,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** The configured escort speed penalty, reused so one server setting still governs how slow is slow. */
    private static double movementPenalty() {
        try {
            return McaCrimeConfig.COMMON.escortSpeedPenalty.get();
        } catch (IllegalStateException e) {
            return 0.0D;
        }
    }
}
