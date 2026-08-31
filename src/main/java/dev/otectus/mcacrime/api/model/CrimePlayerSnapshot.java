package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.crime.Band;

import java.util.UUID;

/**
 * An immutable summary of one player's legal standing at a moment in time — the whole answer to
 * "where does this player stand with the law?" in one object, so a caller never has to make six
 * separate queries that could disagree with each other halfway through.
 *
 * <p>Karma and heat are {@code long} to match {@code CrimeState}. Every field is derived
 * server-side from authoritative state; nothing here is client-supplied or client-trusted.
 */
public record CrimePlayerSnapshot(
        UUID playerId,
        long karma,
        long heat,
        Band band,
        boolean wanted,
        boolean legalTarget,
        boolean jailed,
        long remainingJailTicks,
        boolean captive,
        boolean holdingCaptive,
        int unresolvedCaseCount,
        long outstandingFineTotal) {

    public CrimePlayerSnapshot {
        band = band == null ? Band.GREY : band;
        remainingJailTicks = Math.max(0L, remainingJailTicks);
        unresolvedCaseCount = Math.max(0, unresolvedCaseCount);
        outstandingFineTotal = Math.max(0L, outstandingFineTotal);
    }

    /** Whether anything at all is outstanding — the cheap "is this player in trouble?" test. */
    public boolean hasOutstandingBusiness() {
        return wanted || jailed || unresolvedCaseCount > 0 || outstandingFineTotal > 0L;
    }
}
