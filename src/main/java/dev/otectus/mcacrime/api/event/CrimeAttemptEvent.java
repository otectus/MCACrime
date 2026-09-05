package dev.otectus.mcacrime.api.event;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.UUID;

/**
 * A crime that is being committed but has not yet transferred anything (0.5.1).
 *
 * <p>The middle level of spec §"Separate intent, attempt, and completed crime": witnesses may react
 * and guards may intervene from here, but the victim has lost nothing, and an attempt that ends in
 * {@link AttemptOutcome#ABORTED} never becomes a completed offence.
 *
 * <p>Not a {@link CrimeEvent}: the offender is a villager, and the player-scoped base would have the
 * wrong subject. The transaction id is shared with the mug session, the victim's HUD and the active
 * incident, so a listener can correlate all four.
 */
public abstract class CrimeAttemptEvent extends Event {

    /** How an attempt ended. */
    public enum AttemptOutcome {
        /** It ran to completion; property moved. */
        COMMITTED,
        /** It broke off; nothing moved. */
        ABORTED
    }

    private final UUID transactionId;
    private final UUID offender;
    private final UUID victim;
    private final ResourceLocation crimeId;

    protected CrimeAttemptEvent(UUID transactionId, UUID offender, UUID victim, ResourceLocation crimeId) {
        this.transactionId = transactionId;
        this.offender = offender;
        this.victim = victim;
        this.crimeId = crimeId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getOffender() {
        return offender;
    }

    public UUID getVictim() {
        return victim;
    }

    public ResourceLocation getCrimeId() {
        return crimeId;
    }

    /**
     * The threat has been made. Cancelling this stops the attempt before it begins — no dialogue, no
     * incident, no progress bar — which is the hook a companion mod needs to exempt a protected
     * player or a scripted scene without editing this mod's config.
     */
    public static final class Started extends CrimeAttemptEvent implements ICancellableEvent {
        public Started(UUID transactionId, UUID offender, UUID victim, ResourceLocation crimeId) {
            super(transactionId, offender, victim, crimeId);
        }
    }

    /** The attempt is over, either way. Fired after the world has been left in its final state. */
    public static final class Ended extends CrimeAttemptEvent {

        private final AttemptOutcome outcome;
        private final String reason;

        public Ended(UUID transactionId, UUID offender, UUID victim, ResourceLocation crimeId,
                     AttemptOutcome outcome, String reason) {
            super(transactionId, offender, victim, crimeId);
            this.outcome = outcome;
            this.reason = reason == null ? "" : reason;
        }

        public AttemptOutcome getOutcome() {
            return outcome;
        }

        /** The abort reason's name for an aborted attempt, empty for a committed one. */
        public String getReason() {
            return reason;
        }
    }
}
