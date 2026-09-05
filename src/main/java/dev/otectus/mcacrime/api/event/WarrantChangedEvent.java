package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.ledger.Warrant;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * An offender's warrant opened, took a new revision, or closed (0.5.1).
 *
 * <p>Not a {@link CrimeEvent}: that base names a live {@code ServerPlayer}, and a warrant closing is
 * exactly the sort of thing that wants to be reportable for somebody who is not online at the moment
 * the ledger settles. The offender is a UUID for the same reason.
 *
 * <p>{@link Change#REVISED} is the one a bounty board cares about, because it is the edge at which an
 * already-paid claim key stops covering the target: a new revision is a new price on a new offence.
 */
public final class WarrantChangedEvent extends Event {

    /** What happened to the warrant. */
    public enum Change {
        /** The offender became Wanted and had no open warrant. */
        OPENED,
        /** A qualifying crime was committed while the warrant was open; the revision went up. */
        REVISED,
        /** The offender stopped being Wanted. The warrant is kept, closed, so old claim keys resolve. */
        CLOSED
    }

    private final UUID offender;
    private final Warrant warrant;
    private final Change change;

    public WarrantChangedEvent(UUID offender, Warrant warrant, Change change) {
        this.offender = offender;
        this.warrant = warrant;
        this.change = change;
    }

    public UUID getOffender() {
        return offender;
    }

    /** The warrant as it now stands, after the change. */
    public Warrant getWarrant() {
        return warrant;
    }

    public Change getChange() {
        return change;
    }
}
