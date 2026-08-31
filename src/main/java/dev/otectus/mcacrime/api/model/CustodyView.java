package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable projection of one custody record — who is being held, by whom, lawfully or not.
 *
 * <p>Deliberately carries no live entity reference and no {@code BlockPos}-bearing hold position
 * beyond its dimension. A view may be stored, queued in the integration outbox, or sent toward a
 * client long after the entity has unloaded, so holding a reference would either leak or dangle.
 *
 * <p>{@code lawful} is the jail-versus-kidnapping distinction the whole captivity system turns on:
 * escaping lawful custody is a jailbreak, escaping a kidnapper is not a crime at all.
 */
public record CustodyView(
        UUID captiveId,
        boolean captiveIsPlayer,
        boolean lawful,
        Optional<UUID> captorId,
        RestraintType restraint,
        long realTicksHeld,
        long remainingJailTicks,
        Optional<ResourceLocation> holdDimension,
        Optional<UUID> custodyId,
        Optional<UUID> linkedCaseId) {

    public CustodyView {
        captorId = captorId == null ? Optional.empty() : captorId;
        holdDimension = holdDimension == null ? Optional.empty() : holdDimension;
        custodyId = custodyId == null ? Optional.empty() : custodyId;
        linkedCaseId = linkedCaseId == null ? Optional.empty() : linkedCaseId;
        restraint = restraint == null ? RestraintType.NONE : restraint;
        realTicksHeld = Math.max(0L, realTicksHeld);
        remainingJailTicks = Math.max(0L, remainingJailTicks);
    }

    /** Unlawful custody — a kidnapping rather than a sentence. */
    public boolean kidnapping() {
        return !lawful;
    }
}
