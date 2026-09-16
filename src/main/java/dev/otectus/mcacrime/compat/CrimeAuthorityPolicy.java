package dev.otectus.mcacrime.compat;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which deeds MCA: Crime claims from MCA: Reputation's own detector, and under what conditions.
 *
 * <p>Pure, always loadable, and named in no companion signature, so the rule can be unit-tested with
 * MCA: Reputation absent from the classpath entirely — which is how this mod's test suite runs. The
 * adapter's {@code CoreIncidentAuthority} translates the companion's enum to the names below and
 * answers from here.
 *
 * <h2>Why the kind matters</h2>
 *
 * <p>Until 0.7.3 the authority answered the same boolean for every kind it was asked about. That was
 * written when the companion had two kinds and both were ours, and it quietly became a bug when it
 * grew four more: a blanket {@code true} claimed villager rescues, cures, repelled raids, and
 * in-village player kills — none of which this mod detects — and MCA: Reputation stood down for all of
 * them. Nobody recorded those deeds at all, and nothing in either mod's log said so.
 *
 * <p>So the claim is now exactly two kinds, the two this mod genuinely produces, and the answer for
 * anything else is {@code false} regardless of how the integration is configured.
 */
public final class CrimeAuthorityPolicy {

    /** MCA: Reputation's name for harming a villager. Ours through {@code CrimeIds.HARM_VILLAGER}. */
    public static final String KIND_VILLAGER_ASSAULT = "MCA_VILLAGER_ASSAULT";

    /**
     * MCA: Reputation's name for killing a villager. Ours through {@code CrimeIds.KILL_VILLAGER} and
     * through {@code CrimeIds.MUGGING_MURDER}, which is the same deed with a motive attached.
     */
    public static final String KIND_VILLAGER_KILL = "MCA_VILLAGER_KILL";

    private static final Set<String> DECLARED =
            Set.of(KIND_VILLAGER_ASSAULT, KIND_VILLAGER_KILL);

    private CrimeAuthorityPolicy() {
    }

    /**
     * Exactly the kinds this mod detects. Never empty: an authority that declares nothing is treated
     * by the companion as a legacy blanket claim over the kinds that existed in 0.3.0, which is the
     * behaviour this class exists to stop relying on.
     */
    public static Set<String> declaredKinds() {
        return new LinkedHashSet<>(DECLARED);
    }

    /** Whether this mod produces the deed at all, before any configuration is considered. */
    public static boolean declares(String kindName) {
        return kindName != null && DECLARED.contains(kindName);
    }

    /**
     * Whether this mod is, right now, detecting and recording this kind itself.
     *
     * <p>Every {@code false} hands detection straight back to MCA: Reputation on the very next event,
     * which is exactly what should happen when our detector is off, the integration is disabled, or the
     * bridge has degraded. The answer is a few boolean reads because the companion asks it from inside
     * a damage event.
     */
    public static boolean owns(String kindName, boolean detectionEnabled, boolean integrationEnabled,
                               boolean bridgeAvailable) {
        return declares(kindName) && detectionEnabled && integrationEnabled && bridgeAvailable;
    }

    /**
     * Whether a claimed deed can actually be filed, which is a stronger question than owning it.
     *
     * <p>The one condition that separates the two is {@code replayPendingOperations}. Every civic write
     * this mod makes goes into the outbox first and is delivered by the pump, and the pump only runs
     * while that switch is on. With it off we would hold the claim, queue the incident, and never
     * deliver it — an incident black hole where the companion has stood down and we never file. The
     * companion asks this question precisely so that case hands detection back instead.
     */
    public static boolean canDeliver(String kindName, boolean detectionEnabled, boolean integrationEnabled,
                                     boolean bridgeAvailable, boolean deliveryPumpEnabled) {
        return owns(kindName, detectionEnabled, integrationEnabled, bridgeAvailable) && deliveryPumpEnabled;
    }
}
