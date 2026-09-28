package dev.otectus.mcacrime.api.model;

import java.util.Optional;
import java.util.UUID;

/**
 * A live capital sentence, as a companion mod sees it (0.7.5 §3.19, M6.8).
 *
 * <p>Immutable, entity-free and deliberately unable to change anything: there is no companion method
 * to assign one, carry one out or clear one. A capital sentence is produced by an arrest from an
 * unresolved {@code mcacrime:kill_guard} case and ended by an operator's clemency or by a deliberate
 * act at a device, and a view that could do either would be a fourth way to kill somebody.
 *
 * <p>{@code pendingExecution} is the one time-bounded fact here, and it is the rescue window: while
 * it is true a device has been armed and the ceremony is running, and a pardon, a commutation, a
 * rescue, an escape, the guard's death or the device's destruction inside it leaves nobody dead.
 *
 * @param subject          who is condemned
 * @param subjectIsPlayer  whether they are a player
 * @param sentenceId       the sentence carrying the capital kind
 * @param holdingTicks     what is left of the custodial term the sentence also carries
 * @param pendingExecution whether an order is armed right now
 * @param device           the device the order names, when there is one
 * @param expiresAt        the game time the ceremony window closes at, when one is running
 */
public record CapitalSentenceView(UUID subject, boolean subjectIsPlayer, UUID sentenceId,
                                  long holdingTicks, boolean pendingExecution,
                                  Optional<long[]> device, long expiresAt) {

    public CapitalSentenceView {
        device = device == null ? Optional.empty() : device;
        holdingTicks = Math.max(0L, holdingTicks);
        expiresAt = Math.max(0L, expiresAt);
    }

    /** How long is left of the ceremony window at {@code now}, or zero when none is running. */
    public long windowRemaining(long now) {
        return pendingExecution ? Math.max(0L, expiresAt - now) : 0L;
    }
}
