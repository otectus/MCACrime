package dev.otectus.mcacrime.restraint;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The render-and-interaction projection of one subject's physical state (§1.6).
 *
 * <p>Deliberately much smaller than {@link PhysicalRestraintState}. A client is told what to draw and
 * what to grey out: which slot holds which definition, how worn it looks, whether somebody is holding
 * the lead and whether a device has them. It is never told who applied the gear, what the item
 * snapshot was, which custody it belongs to, or anything about a lock.
 *
 * <p>{@code generation} and {@code revision} travel with it so the client can discard an out-of-order
 * message, and so a later action packet can be pinned to the state it was shown.
 */
public record PhysicalRestraintView(UUID subject, long generation, long revision,
                                    Map<RestraintSlot, SlotView> slots, int tetherHolderEntityId,
                                    boolean detained) {

    /** Nobody is holding this subject. Entity ids are per level, so -1 cannot collide with one. */
    public static final int NO_HOLDER = -1;

    /**
     * One occupied slot, as the client sees it.
     *
     * @param definitionId        which definition, for the model and the name
     * @param durabilityFraction  0..1 of the definition's configured maximum; never the raw value
     * @param broken              whether it has been used up and is about to come off
     */
    public record SlotView(ResourceLocation definitionId, float durabilityFraction, boolean broken) {

        public SlotView {
            durabilityFraction = Float.isFinite(durabilityFraction)
                    ? Math.max(0.0F, Math.min(1.0F, durabilityFraction))
                    : 1.0F;
        }
    }

    public PhysicalRestraintView {
        Map<RestraintSlot, SlotView> copy = new EnumMap<>(RestraintSlot.class);
        if (slots != null) {
            slots.forEach((slot, view) -> {
                if (slot != null && view != null) {
                    copy.put(slot, view);
                }
            });
        }
        slots = Map.copyOf(copy);
        generation = Math.max(1L, generation);
        revision = Math.max(0L, revision);
        tetherHolderEntityId = tetherHolderEntityId < 0 ? NO_HOLDER : tetherHolderEntityId;
    }

    /** The projection of a server state. The one place the two shapes are related. */
    public static PhysicalRestraintView of(PhysicalRestraintState state, int tetherHolderEntityId,
                                           boolean detained) {
        Map<RestraintSlot, SlotView> slots = new EnumMap<>(RestraintSlot.class);
        for (RestraintSlot slot : RestraintSlot.values()) {
            state.slot(slot).ifPresent(restraint -> slots.put(slot,
                    new SlotView(restraint.definitionId(), restraint.durabilityFraction(),
                            restraint.broken())));
        }
        return new PhysicalRestraintView(state.subject(), state.generation(), state.revision(), slots,
                tetherHolderEntityId, detained);
    }

    /** An explicitly empty view: this subject wears nothing and is held by nothing. */
    public static PhysicalRestraintView empty(UUID subject, long generation, long revision) {
        return new PhysicalRestraintView(subject, generation, revision, Map.of(), NO_HOLDER, false);
    }

    public Optional<SlotView> slot(@Nullable RestraintSlot slot) {
        return slot == null ? Optional.empty() : Optional.ofNullable(slots.get(slot));
    }

    /** True when there is nothing to draw for this subject. */
    public boolean vacant() {
        return slots.isEmpty() && tetherHolderEntityId == NO_HOLDER && !detained;
    }
}
