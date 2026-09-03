package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * One open guard challenge (spec §13.2). Memory-only: an encounter is a window measured in seconds,
 * and a challenge that survived a restart would confront a player about a conversation the guard no
 * longer remembers starting.
 *
 * <p>The encounter id is the thing that makes a response answerable. A player clicking "Surrender"
 * is answering <em>this</em> guard about <em>these</em> charges, so a stale screen, a second guard,
 * or a replayed packet cannot be used to surrender to an encounter that has already closed.
 */
public record GuardChallenge(UUID encounterId,
                             UUID guardId,
                             UUID playerId,
                             @Nullable CrimeCommunityKey jurisdiction,
                             int chargeCount,
                             long assessedFine,
                             boolean finable,
                             long openedAt,
                             long expiresAt) {

    public boolean expired(long now) {
        return now >= expiresAt;
    }

    /** Ticks left to answer, floored at zero so the client never renders a negative countdown. */
    public long remaining(long now) {
        return Math.max(0L, expiresAt - now);
    }

    /** Whether paying is actually on the table: it needs a price, and charges that money can settle. */
    public boolean canPay() {
        return finable && assessedFine > 0L && chargeCount > 0;
    }
}
