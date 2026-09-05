package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * An NPC has decided somebody looks worth robbing (0.5.1).
 *
 * <p>The first of the three levels spec §"Separate intent, attempt, and completed crime" asks for,
 * and the one that is explicitly <em>not</em> a crime: nobody has been threatened, no witness may
 * react, and no guard may act. It exists so a listener can watch how thieves choose, and so the
 * distinction between choosing and doing is visible in the API rather than only in the code.
 *
 * <p>Not cancellable — vetoing an intention would only move the decision, not prevent the crime.
 * {@link CrimeAttemptEvent.Started} is the cancellable one.
 */
public final class CrimeIntentEvent extends Event {

    private final UUID offender;
    private final UUID victim;
    private final ResourceLocation crimeId;
    private final ResourceLocation dimension;

    public CrimeIntentEvent(UUID offender, UUID victim, ResourceLocation crimeId, ResourceLocation dimension) {
        this.offender = offender;
        this.victim = victim;
        this.crimeId = crimeId;
        this.dimension = dimension;
    }

    /** The would-be offender. A villager here, not a player. */
    public UUID getOffender() {
        return offender;
    }

    public UUID getVictim() {
        return victim;
    }

    public ResourceLocation getCrimeId() {
        return crimeId;
    }

    public ResourceLocation getDimension() {
        return dimension;
    }
}
