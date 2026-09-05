package dev.otectus.mcacrime.api.event;

import dev.otectus.mcacrime.job.CriminalJob;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Fired after a villager's criminal job has been assigned, changed or cleared (0.5.1).
 *
 * <p>Not a {@link CrimeEvent}: the subject is a villager, and the player-scoped base would have
 * nobody to name. Not cancellable either — the record is already written when this fires, so a
 * listener that wanted to veto the assignment would be vetoing something that has happened.
 */
public final class CriminalJobChangedEvent extends Event {

    private final UUID villager;
    private final CriminalJob previous;
    private final CriminalJob current;

    public CriminalJobChangedEvent(UUID villager, CriminalJob previous, CriminalJob current) {
        this.villager = villager;
        this.previous = previous;
        this.current = current;
    }

    public UUID getVillager() {
        return villager;
    }

    /** What the villager was before, {@link CriminalJob#NONE} for a first assignment. */
    public CriminalJob getPrevious() {
        return previous;
    }

    /** What the villager is now, {@link CriminalJob#NONE} when the job was cleared. */
    public CriminalJob getCurrent() {
        return current;
    }
}
