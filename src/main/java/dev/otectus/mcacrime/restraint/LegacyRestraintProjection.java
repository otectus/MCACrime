package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * The one-way projection from real physical state back to the deprecated 0.7.4 enum (0.7.5 M2.11).
 *
 * <p>{@code captivity/RestraintType} survives only because two public API members promised it:
 * {@code api/event/EntityKidnappedEvent} and {@code api/model/CustodyView}. A companion mod compiled
 * against 0.7.4 must keep compiling and keep getting a sensible answer, so the answer is computed
 * here from the arm slot rather than stored anywhere.
 *
 * <p>Lossy on purpose, and only in the harmless direction. Nine definitions across three slots cannot
 * be expressed by four constants, so a hood or leg shackles project as the arm restraint the subject
 * is also wearing, or as {@code NONE} when the arms are free. Nothing reads this back: the
 * authoritative question is "what is in {@code PhysicalRestraintState}", and the migration in the
 * other direction is {@code RestraintMigrationReconciler.definitionFor}.
 */
public final class LegacyRestraintProjection {

    private LegacyRestraintProjection() {
    }

    /** What an old listener would have called the gear on this subject's arms. */
    @SuppressWarnings("deprecation")
    public static RestraintType of(@Nullable PhysicalRestraintState state) {
        if (state == null) {
            return RestraintType.NONE;
        }
        return state.slot(RestraintSlot.ARMS)
                .flatMap(AppliedRestraint::definition)
                .map(definition -> of(definition.id()))
                .orElse(RestraintType.NONE);
    }

    /** The same projection for a definition id on its own. */
    @SuppressWarnings("deprecation")
    public static RestraintType of(@Nullable ResourceLocation definitionId) {
        if (definitionId == null) {
            return RestraintType.NONE;
        }
        if (RestraintDefinitions.HANDCUFFS_ARMS.equals(definitionId)
                || RestraintDefinitions.HANDCUFFS_LEGS.equals(definitionId)) {
            return RestraintType.LOCKED_CUFFS;
        }
        if (RestraintDefinitions.SHACKLES_ARMS.equals(definitionId)
                || RestraintDefinitions.SHACKLES_LEGS.equals(definitionId)) {
            return RestraintType.CUFFS;
        }
        if (RestraintDefinitions.DUCK_TAPE_ARMS.equals(definitionId)
                || RestraintDefinitions.DUCK_TAPE_LEGS.equals(definitionId)
                || RestraintDefinitions.DUCK_TAPE_HEAD.equals(definitionId)
                || RestraintDefinitions.BUNDLE.equals(definitionId)) {
            return RestraintType.ROPE;
        }
        return RestraintType.NONE;
    }
}
