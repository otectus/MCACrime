package dev.otectus.mcacrime.job;

import dev.otectus.mcacrime.compat.OccupationSnapshot;
import dev.otectus.mcacrime.state.world.WorksiteRef;

import javax.annotation.Nullable;

import java.util.Optional;

/**
 * Every world change one occupation transition can make, as separate steps.
 *
 * <p>This interface exists so {@link OccupationTransaction} can be a sequence rather than a method on
 * a service that needs a {@code MinecraftServer}, a loaded MCA villager and a POI manager to run at
 * all. The ordering, the rollback and the "publish nothing until it committed" rule are the parts that
 * have to be right, and with this seam they are exercised by a fake that fails on demand at each step
 * — which is exactly what spec §21.3's failure-injection cases ask for and what no integration test in
 * a mod's unit suite can provide.
 *
 * <p>The live implementation is {@code EntityOccupationMutator}. Every method returns rather than
 * throws; a {@code false} is a step that did not take effect.
 */
public interface OccupationMutator {

    /** Captures the occupational state to roll back to, or empty when the villager cannot be read. */
    Optional<OccupationSnapshot> snapshot();

    /** Takes the native POI ticket at exactly this position. False when somebody else holds it. */
    boolean reserveTicket(WorksiteRef site);

    /** Confirms the villager already owns the ticket here, without taking a second one. */
    boolean adoptTicket(WorksiteRef site);

    /** Gives a ticket back. Called only for a ticket this transaction actually took. */
    void releaseTicket(WorksiteRef site);

    /** Releases the claim on a previous, different workstation. False when there was nothing to release. */
    boolean releaseOldJobSite(WorksiteRef site);

    /** Erases job site, potential job site, secondary sites and the last-worked stamp. Nothing else. */
    void clearOccupationalMemories();

    /** Applies the Thief profession through MCA's setter and verifies it reads back. */
    boolean applyProfession();

    /** Installs an empty trade list, so no previous profession's offers survive (spec §10.6). */
    boolean clearOffers();

    /** Writes the validated job-site memory. */
    boolean setJobSite(WorksiteRef site);

    /** Raises trading XP to at least one, which is what stops MCA's own reset. */
    boolean applyXpFloor();

    /** Final agreement check: native profession, empty offers, and the claim actually held. */
    boolean verify(@Nullable WorksiteRef site);

    /** Puts the snapshot back. False means the villager could not be fully restored. */
    boolean restore(OccupationSnapshot snapshot);
}
