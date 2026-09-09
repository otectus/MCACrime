package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.economy.DispositionService;

import javax.annotation.Nullable;
import java.util.List;
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
                             long expiresAt,
                             long revision,
                             @Nullable DispositionService.Offer offer,
                             long displayWindowTicks,
                             boolean awaitingDisplay) {

    public static final int MIN_RESPONSE_TICKS = 15 * 20;
    public static final int DISPLAY_GRACE_TICKS = 5 * 20;

    public GuardChallenge(UUID encounterId, UUID guardId, UUID playerId, CrimeCommunityKey jurisdiction,
                          int chargeCount, long assessedFine, boolean finable, long openedAt, long expiresAt,
                          long revision, DispositionService.Offer offer) {
        this(encounterId, guardId, playerId, jurisdiction, chargeCount, assessedFine, finable,
                openedAt, expiresAt, revision, offer, 0L, false);
    }

    public GuardChallenge(UUID encounterId, UUID guardId, UUID playerId, CrimeCommunityKey jurisdiction,
                          int chargeCount, long assessedFine, boolean finable, long openedAt, long expiresAt) {
        this(encounterId, guardId, playerId, jurisdiction, chargeCount, assessedFine, finable,
                openedAt, expiresAt, 0L, null);
    }

    /** A requote retains the conversation and its original response deadline. */
    public GuardChallenge refresh(DispositionService.Offer updated) {
        return new GuardChallenge(encounterId, guardId, playerId, updated.decision().jurisdiction(),
                updated.decision().cases().size(), updated.quote().amount(), updated.quote().ok(),
                openedAt, expiresAt, revision + 1L, updated, displayWindowTicks, awaitingDisplay);
    }

    /** Delivery has a bounded allowance; withholding the acknowledgment cannot hold a guard forever. */
    public GuardChallenge awaitDisplay() {
        long window = Math.max(MIN_RESPONSE_TICKS, expiresAt - openedAt);
        return new GuardChallenge(encounterId, guardId, playerId, jurisdiction, chargeCount, assessedFine,
                finable, openedAt, openedAt + DISPLAY_GRACE_TICKS + window, revision, offer, window, true);
    }

    /** Only the first display acknowledgment starts the timer; requotes and reopening never reset it. */
    public GuardChallenge displayed(long now) {
        if (!awaitingDisplay) return this;
        long start = Math.max(openedAt, Math.min(now, openedAt + DISPLAY_GRACE_TICKS));
        return new GuardChallenge(encounterId, guardId, playerId, jurisdiction, chargeCount, assessedFine,
                finable, openedAt, start + displayWindowTicks, revision, offer, displayWindowTicks, false);
    }

    public boolean accepts(UUID encounter, long offeredRevision) {
        return encounterId.equals(encounter) && revision == offeredRevision;
    }

    /** Automatic payment uses this offer; an explicit request must name exactly the same cases. */
    public boolean acceptsSelection(List<UUID> requested) {
        return requested == null || requested.isEmpty()
                || offer != null && offer.quote().caseIds().equals(requested);
    }

    public boolean expired(long now) {
        return now >= expiresAt;
    }

    /** Ticks left to answer, floored at zero so the client never renders a negative countdown. */
    public long remaining(long now) {
        long remaining = Math.max(0L, expiresAt - now);
        return awaitingDisplay ? Math.min(displayWindowTicks, remaining) : remaining;
    }

    /** Whether paying is actually on the table: it needs a price, and charges that money can settle. */
    public boolean canPay() {
        return finable && assessedFine > 0L && chargeCount > 0;
    }
}
