package dev.otectus.mcacrime.effect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one sand impact owes the legal system, decided from provenance rather than from the world at
 * the moment of impact (0.7.2 §14).
 *
 * <p>Two things this exists to stop. The first is the retroactive identity rewrite: the thrower's
 * worn mask is read at <em>launch</em> and carried on the projectile, so swapping masks during flight
 * cannot change what a witness saw somebody throw (§14.3, SAND-15). The second is the fabricated
 * charge: a miss, a vetoed effect, the thrower's own splash and a defensive throw against an active
 * mugger all produce no incident at all, and every successful hostile exposure produces exactly one
 * (§14.1, SAND-13, SAND-16).
 */
public final class SandIncidentPolicy {

    private SandIncidentPolicy() {
    }

    /**
     * Everything the incident system needs that only existed at launch.
     *
     * @param launchId       identifies the throw. Every victim's incident id is derived from it, so a
     *                       retried impact cannot mint a second case for the same bottle.
     * @param maskedAtLaunch whether the thrower was validly masked when they threw
     * @param witnesses      who had already seen the throw when it left their hand
     */
    public record LaunchSnapshot(UUID thrower, UUID launchId, long launchTick, boolean maskedAtLaunch,
                                 Set<UUID> witnesses) {

        public LaunchSnapshot {
            witnesses = Set.copyOf(witnesses);
        }

        public static LaunchSnapshot of(UUID thrower, UUID launchId, long launchTick, boolean masked,
                                        Collection<UUID> witnesses) {
            return new LaunchSnapshot(thrower, launchId, launchTick, masked, new LinkedHashSet<>(witnesses));
        }
    }

    /**
     * One victim's outcome, after the effect either landed or did not.
     *
     * @param applied      the effect was genuinely added. A veto by another mod means false, and a
     *                     false here is never reported as a completed blinding (SAND-17).
     * @param selfDefence  this victim was, at the instant before the sand interrupted them, mugging
     *                     the thrower. Captured before the abort, because the abort destroys the
     *                     evidence that the throw was defensive (§14.2, SAND-08).
     */
    public record Exposure(UUID victim, boolean applied, boolean selfExposure, boolean selfDefence) {
    }

    /** One incident that will be committed, with the id it will be committed under. */
    public record Charge(UUID victim, UUID incidentId) {
    }

    /**
     * The deterministic incident id for one victim of one throw.
     *
     * <p>Name-based rather than random because {@code IncidentService} deduplicates by incident id:
     * a replay of the same impact has to collide with the record it already wrote, and two victims of
     * the same impact have to <em>not</em> collide, or the second one is silently discarded.
     */
    public static UUID incidentIdFor(UUID launchId, UUID victim) {
        String seed = "mcacrime:sand/" + launchId + "/" + victim;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The charges one impact produces, in victim order, with no duplicates.
     *
     * <p>Everything that is not a completed hostile exposure falls out here rather than downstream,
     * which is what keeps "one impact, one logical attempt" true without a second case ledger.
     */
    public static List<Charge> charges(LaunchSnapshot launch, List<Exposure> exposures) {
        List<Charge> charges = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (Exposure exposure : exposures) {
            if (!exposure.applied() || exposure.selfExposure() || exposure.selfDefence()) {
                continue;
            }
            if (exposure.victim() == null || exposure.victim().equals(launch.thrower())) {
                continue;
            }
            if (!seen.add(exposure.victim())) {
                continue;
            }
            charges.add(new Charge(exposure.victim(), incidentIdFor(launch.launchId(), exposure.victim())));
        }
        return List.copyOf(charges);
    }

    /**
     * Whether the observation set the incident is committed with is the launch one.
     *
     * <p>A throw nobody saw leave the hand is still a throw; the empty set is the honest answer and
     * is not a reason to fall back to a fresh scan at the impact point, which would hand impact-only
     * bystanders knowledge of an owner they never saw (§14.3, SAND-14).
     */
    public static boolean usesLaunchObservations(LaunchSnapshot launch) {
        return launch != null;
    }
}
