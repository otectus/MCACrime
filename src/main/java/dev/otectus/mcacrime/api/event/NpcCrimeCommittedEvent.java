package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * An NPC completed a crime against a player (0.5.1).
 *
 * <p>The counterpart of {@link CrimeCommittedEvent}, which names a {@code ServerPlayer} offender and
 * therefore cannot describe a thief. Fired after the record is written and the property has moved, so
 * it is a notification and not a veto.
 *
 * <p>Declared in Phase 6 alongside the rest of the NPC crime plumbing; the theft transaction that
 * posts it lands in Phase 7.
 */
public final class NpcCrimeCommittedEvent extends Event {

    private final UUID offender;
    private final UUID victim;
    private final ResourceLocation crimeId;
    private final UUID transactionId;
    private final UUID recordId;

    public NpcCrimeCommittedEvent(UUID offender, UUID victim, ResourceLocation crimeId,
                                  UUID transactionId, UUID recordId) {
        this.offender = offender;
        this.victim = victim;
        this.crimeId = crimeId;
        this.transactionId = transactionId;
        this.recordId = recordId;
    }

    /** The villager who did it. */
    public UUID getOffender() {
        return offender;
    }

    public UUID getVictim() {
        return victim;
    }

    public ResourceLocation getCrimeId() {
        return crimeId;
    }

    /** Shared with the attempt events and the victim's HUD session. */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** The {@code CrimeRecord} this crime was written to. */
    public UUID getRecordId() {
        return recordId;
    }
}
