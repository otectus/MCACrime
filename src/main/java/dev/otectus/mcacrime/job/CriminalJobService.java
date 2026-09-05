package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is a criminal, as spec §"Criminal-job representation" defines the question (0.5.1).
 *
 * <p>An interface rather than a static utility for one reason: the storage layer must stay
 * independent of MCA's profession implementation. Callers ask this; only {@link WorldCriminalJobService}
 * knows that the answer lives in {@code CrimeWorldData} and that a fence may additionally be
 * <em>wearing</em> an MCA profession as presentation. If MCA's internals shift again, the fact that
 * this villager is a thief still reads correctly.
 */
public interface CriminalJobService {

    /** This villager's job, or {@link CriminalJob#NONE} for the overwhelming majority who have none. */
    CriminalJob get(UUID villager);

    /** Assigns or clears a job. {@link CriminalJob#NONE} clears it, presentation included. */
    void set(UUID villager, CriminalJob job);

    /** Shorthand for {@code get(villager) != NONE}. */
    boolean isCriminal(UUID villager);

    /** The whole persisted record, for callers that need the assignment day or the mug cooldown. */
    Optional<CriminalVillagerRecord> record(UUID villager);

    /** Stamps the last-mug time, which is what the per-thief cooldown is measured from. */
    void touchMug(UUID villager, long now);

    /** Every criminal the world knows about, loaded or not. Bounded by the persistence cap. */
    Collection<CriminalVillagerRecord> all();
}
