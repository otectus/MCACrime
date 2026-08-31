package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.ledger.CrimeResolutionEntry;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired when a crime case genuinely changes disposition — paid off, served out, pardoned, escaped
 * from, or expired.
 *
 * <p>Carries the case both {@link #getBefore() before} and {@link #getAfter() after}, because the
 * interesting question is almost always the transition rather than the destination. "Unresolved to
 * fined" is restitution; "escaped to fined" is someone finally settling up after running. A listener
 * given only the new state cannot tell those apart.
 *
 * <p>Fires only on a real change. A replayed transaction that finds the case already in the target
 * state posts nothing, so a retrying delivery cannot look like the player atoning twice.
 */
public final class CrimeRecordResolvedEvent extends CrimeEvent {

    private final CrimeRecordView before;
    private final CrimeRecordView after;
    private final CrimeResolutionEntry entry;

    public CrimeRecordResolvedEvent(ServerPlayer offender, CrimeRecordView before,
                                    CrimeRecordView after, CrimeResolutionEntry entry) {
        super(offender);
        this.before = before;
        this.after = after;
        this.entry = entry;
    }

    /** The case as it stood before this change. */
    public CrimeRecordView getBefore() {
        return before;
    }

    /** The case as it now stands. */
    public CrimeRecordView getAfter() {
        return after;
    }

    /** Who changed it, under which transaction, and when. */
    public CrimeResolutionEntry getEntry() {
        return entry;
    }
}
