package dev.otectus.mcacrime.api.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * What is worn on one body region, as a companion mod sees it (0.7.5 M6.4).
 *
 * <p>Immutable, entity-free and durability-as-a-fraction, for the same reason every other view in
 * this package is shaped that way: a view may be stored, queued or read long after the subject has
 * unloaded, and a live reference would either leak or dangle.
 *
 * <p>Deliberately <em>not</em> carried: the item snapshot, the enchantment list, the escape session
 * and the applier's inventory. A companion mod being told what a prisoner is wearing is not being
 * told how to take it off.
 *
 * @param slot         the region: {@code head}, {@code arms} or {@code legs}
 * @param definitionId which of the definitions, as a registry id
 * @param durability   0..1 of the configured maximum; 1 for a definition with no durability
 * @param applier      who put it on, when a person did
 * @param custodyId    the legal custody it belongs to, when it belongs to one
 * @param systemIssued whether the server minted it for an arrest, so removal owes nobody an item
 */
public record RestraintSlotView(String slot, ResourceLocation definitionId, float durability,
                                Optional<UUID> applier, Optional<UUID> custodyId,
                                boolean systemIssued) {

    public RestraintSlotView {
        slot = slot == null ? "" : slot;
        applier = applier == null ? Optional.empty() : applier;
        custodyId = custodyId == null ? Optional.empty() : custodyId;
        durability = Float.isFinite(durability) ? Math.max(0.0F, Math.min(1.0F, durability)) : 1.0F;
    }

    /** Whether this restraint has been worn down to nothing and is about to break. */
    public boolean spent() {
        return durability <= 0.0F;
    }
}
