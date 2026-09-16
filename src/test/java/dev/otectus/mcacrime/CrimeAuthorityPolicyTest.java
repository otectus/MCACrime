package dev.otectus.mcacrime;

import dev.otectus.mcacrime.compat.CrimeAuthorityPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which deeds this mod claims from MCA: Reputation's own detector.
 *
 * <p>The bug these tests exist for: the authority used to answer the same boolean whatever kind it was
 * asked about. That was written when the companion had two kinds and both were ours, and it quietly
 * became a claim over villager rescues, cures, repelled raids and in-village player kills — none of
 * which this mod detects. MCA: Reputation stood down for all of them and nobody recorded those deeds
 * at all.
 *
 * <p>The rules live in an always-loadable class precisely so they can be asserted here, with the
 * companion absent from the test classpath.
 */
class CrimeAuthorityPolicyTest {

    /** The four kinds this mod has never detected. Named as strings, as the companion names them. */
    private static final Set<String> NOT_OURS = Set.of("MCA_VILLAGER_RESCUE", "MCA_VILLAGER_CURE",
            "MCA_RAID_REPELLED", "PLAYER_KILL_IN_VILLAGE");

    @Test
    void weDeclareExactlyTheTwoDeedsWeProduce() {
        assertEquals(Set.of(CrimeAuthorityPolicy.KIND_VILLAGER_ASSAULT,
                        CrimeAuthorityPolicy.KIND_VILLAGER_KILL),
                CrimeAuthorityPolicy.declaredKinds());
    }

    /** An empty declaration is read by the companion as a legacy blanket claim; ours must never be. */
    @Test
    void theDeclarationIsNeverEmpty() {
        assertFalse(CrimeAuthorityPolicy.declaredKinds().isEmpty());
    }

    @Test
    void theDeclarationIsACopyTheCallerCannotWidenInPlace() {
        Set<String> declared = CrimeAuthorityPolicy.declaredKinds();
        declared.add("MCA_RAID_REPELLED");

        assertEquals(2, CrimeAuthorityPolicy.declaredKinds().size());
    }

    @Test
    void weOwnAssaultAndKillingWhileEverythingIsOn() {
        assertTrue(CrimeAuthorityPolicy.owns(CrimeAuthorityPolicy.KIND_VILLAGER_ASSAULT, true, true, true));
        assertTrue(CrimeAuthorityPolicy.owns(CrimeAuthorityPolicy.KIND_VILLAGER_KILL, true, true, true));
    }

    /** The regression: a kind we do not detect is never ours, however healthy the bridge is. */
    @Test
    void weNeverClaimADeedWeDoNotDetect() {
        for (String kind : NOT_OURS) {
            assertFalse(CrimeAuthorityPolicy.owns(kind, true, true, true),
                    kind + " is not detected by this mod and must never be claimed");
            assertFalse(CrimeAuthorityPolicy.canDeliver(kind, true, true, true, true),
                    kind + " must not be deliverable either");
            assertFalse(CrimeAuthorityPolicy.declares(kind));
        }
    }

    @Test
    void anUnknownOrNullKindIsNotOurs() {
        assertFalse(CrimeAuthorityPolicy.owns("MCA_SOMETHING_NEW", true, true, true));
        assertFalse(CrimeAuthorityPolicy.owns(null, true, true, true));
        assertFalse(CrimeAuthorityPolicy.declares(null));
    }

    /** Each gate on its own hands detection back, because each one means we are not recording. */
    @Test
    void anyDisabledGateHandsDetectionBack() {
        String kind = CrimeAuthorityPolicy.KIND_VILLAGER_ASSAULT;

        assertFalse(CrimeAuthorityPolicy.owns(kind, false, true, true), "detection off");
        assertFalse(CrimeAuthorityPolicy.owns(kind, true, false, true), "integration off");
        assertFalse(CrimeAuthorityPolicy.owns(kind, true, true, false), "bridge down or degraded");
    }

    /**
     * Owning a deed and being able to file it are different questions, and the difference is the
     * outbox pump: every civic write is queued and delivered by it, so with it switched off we would
     * hold the claim and never file — the companion having stood down for a deed nobody records.
     */
    @Test
    void weCannotDeliverWithoutThePumpEvenThoughWeStillOwnTheDeed() {
        String kind = CrimeAuthorityPolicy.KIND_VILLAGER_KILL;

        assertTrue(CrimeAuthorityPolicy.owns(kind, true, true, true));
        assertFalse(CrimeAuthorityPolicy.canDeliver(kind, true, true, true, false));
        assertTrue(CrimeAuthorityPolicy.canDeliver(kind, true, true, true, true));
    }

    @Test
    void canDeliverIsNeverWeakerThanOwning() {
        for (String kind : Set.of(CrimeAuthorityPolicy.KIND_VILLAGER_ASSAULT,
                CrimeAuthorityPolicy.KIND_VILLAGER_KILL)) {
            for (boolean detection : new boolean[] {true, false}) {
                for (boolean integration : new boolean[] {true, false}) {
                    for (boolean bridge : new boolean[] {true, false}) {
                        boolean owns = CrimeAuthorityPolicy.owns(kind, detection, integration, bridge);
                        boolean delivers =
                                CrimeAuthorityPolicy.canDeliver(kind, detection, integration, bridge, true);
                        assertEquals(owns, delivers,
                                "with the pump on, canDeliver and owns must agree for " + kind);
                    }
                }
            }
        }
    }
}
