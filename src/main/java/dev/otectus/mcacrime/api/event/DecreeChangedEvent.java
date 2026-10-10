package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.api.model.DecreeView;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * A royal decree was issued or ended (0.7.8). Posted on the Forge event bus after the change is stored.
 *
 * <p>Like {@link WarrantChangedEvent}, not a {@link CrimeEvent}: the offender is a UUID because a decree
 * may end -- expire, be withdrawn, be voided -- while they are offline. Not cancellable; a decree is
 * decided before this is posted.
 */
public final class DecreeChangedEvent extends Event {

    /** What happened. */
    public enum Change {
        ISSUED,
        WITHDRAWN,
        FINED,
        SERVED,
        EXPIRED,
        VOIDED
    }

    private final UUID offender;
    private final DecreeView decree;
    private final Change change;

    public DecreeChangedEvent(UUID offender, DecreeView decree, Change change) {
        this.offender = offender;
        this.decree = decree;
        this.change = change;
    }

    public UUID getOffender() {
        return offender;
    }

    /** The decree as it now stands. */
    public DecreeView getDecree() {
        return decree;
    }

    public Change getChange() {
        return change;
    }
}
