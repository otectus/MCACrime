package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.ledger.CrimeFlag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * The crimes that are happening right now, as opposed to the ones that have been reported.
 *
 * <p>Spec §"Separate intent, attempt, and completed crime" needs a guard to be able to intervene
 * during the threat, before any property has moved. A {@code CrimeRecord} cannot serve that: it is
 * written when a crime completes, and a mugging that a guard stops never completes. This registry is
 * the missing middle — an in-memory list of open incidents that enforcement can read directly.
 *
 * <p>Memory-only and deliberately so. An incident lives for the few seconds of the attempt; a restart
 * that lost one has lost a mugging that also did not happen.
 */
public final class ActiveIncidentRegistry {

    /** How far an incident has got. Guards may act from {@link Phase#THREAT} onward. */
    public enum Phase {
        /** The threat has been made. Nothing has been taken. */
        THREAT,
        /** Property has changed hands (Phase 7 moves incidents here). */
        COMMITTED
    }

    /**
     * One open incident.
     *
     * @param flags what the resulting record will carry — caught in the act, mandatory custody, and
     *              an NPC rather than a player as the offender
     */
    public record ActiveIncident(UUID incidentId, UUID offenderId, UUID victimId, ResourceKey<Level> dimension,
                                 long startedAt, EnumSet<CrimeFlag> flags, Phase phase) {

        public ActiveIncident {
            flags = flags == null ? EnumSet.noneOf(CrimeFlag.class) : flags.clone();
        }

        @Override
        public EnumSet<CrimeFlag> flags() { return flags.clone(); }

        /** The same incident, one phase further on. */
        public ActiveIncident withPhase(Phase next) {
            return new ActiveIncident(incidentId, offenderId, victimId, dimension, startedAt, flags, next);
        }
    }

    /**
     * Whether enforcement may act on an incident at this phase.
     *
     * <p>Both current phases qualify, and the method exists anyway. Spec §"Guard intervention" ends on
     * the test that a thief which approaches but never threatens is never arrested, and the way that
     * holds today is that scouting and approaching open no incident at all. This is where the rule
     * gets written down, so a later phase added below {@link Phase#THREAT} has to decide about it
     * here rather than silently becoming actionable.
     */
    public static boolean visibleToGuards(Phase phase) {
        return phase == Phase.THREAT || phase == Phase.COMMITTED;
    }

    /** Keyed by offender: one villager can only be committing one of these at a time. */
    private static final Map<UUID, ActiveIncident> OPEN = new ConcurrentHashMap<>();

    private ActiveIncidentRegistry() {
    }

    /** Opens an incident, replacing any the same offender had already left open. */
    public static void open(ActiveIncident incident) {
        if (incident != null) {
            OPEN.put(incident.offenderId(), incident);
        }
    }

    /** Moves an open incident to a later phase. No-op when the offender has none. */
    public static void advance(UUID offenderId, Phase phase) {
        OPEN.computeIfPresent(offenderId, (id, incident) -> incident.withPhase(phase));
    }

    /** Closes and returns the offender's incident, if they had one. */
    public static Optional<ActiveIncident> close(UUID offenderId) {
        return Optional.ofNullable(offenderId == null ? null : OPEN.remove(offenderId));
    }

    public static Optional<ActiveIncident> get(UUID offenderId) {
        return Optional.ofNullable(offenderId == null ? null : OPEN.get(offenderId));
    }

    public static Collection<ActiveIncident> all() {
        return List.copyOf(OPEN.values());
    }

    /** Drops everything. Called on server stop so a restart never inherits an open incident. */
    public static void clearAll() {
        OPEN.clear();
    }
}
