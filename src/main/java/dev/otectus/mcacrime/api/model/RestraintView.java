package dev.otectus.mcacrime.api.model;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything physically on one subject (0.7.5 M6.4).
 *
 * <p>The physical half of the §1.4 split, and the counterpart to {@link CustodyView}'s legal half. A
 * companion mod that wants to know "is this villager in handcuffs" asks this; one that wants to know
 * "is this villager serving a sentence" asks that. Conflating the two is the mistake the whole
 * release is written against: cuffs are not a sentence and an empty slot is not a pardon.
 *
 * @param subject     who is wearing it
 * @param generation  the physical generation, which advances when the hold changes hands; a stale
 *                    view can therefore be told apart from a current one
 * @param revision    the state's own revision, for ordering two views of the same subject
 * @param slots       one entry per occupied region, in head, arms, legs order
 * @param tetherId    the chain or escort holding them, when one does
 * @param detentionId the device holding them, when one does
 * @param dimension   where they were when this was taken
 */
public record RestraintView(UUID subject, long generation, long revision, List<RestraintSlotView> slots,
                            Optional<UUID> tetherId, Optional<UUID> detentionId,
                            Optional<ResourceLocation> dimension) {

    public RestraintView {
        slots = slots == null ? List.of() : List.copyOf(slots);
        tetherId = tetherId == null ? Optional.empty() : tetherId;
        detentionId = detentionId == null ? Optional.empty() : detentionId;
        dimension = dimension == null ? Optional.empty() : dimension;
        generation = Math.max(1L, generation);
        revision = Math.max(0L, revision);
    }

    /** Whether anything at all is worn. A tether alone is a hold, not a restraint. */
    public boolean restrained() {
        return !slots.isEmpty();
    }

    /** The view of one region, by its id. */
    public Optional<RestraintSlotView> slot(String slot) {
        return slots.stream().filter(entry -> entry.slot().equalsIgnoreCase(slot)).findFirst();
    }
}
