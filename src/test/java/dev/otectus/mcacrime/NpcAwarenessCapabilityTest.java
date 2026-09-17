package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.NpcAwareness;
import dev.otectus.mcacrime.ai.NpcAwareness.Capability;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.memory.CrimeObservation;
import dev.otectus.mcacrime.memory.ObserverRole;
import dev.otectus.mcacrime.memory.ReportState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a villager MCA: Crime cannot confirm is on their feet may still do.
 *
 * <p>The rule under test is {@link NpcAwareness#permits(Capability, boolean, boolean, boolean, boolean, boolean)},
 * which is the whole capability decision with the live lookups taken out. Two properties matter more
 * than the individual rows:
 *
 * <ul>
 *   <li><b>Unavailable data falls back to awake.</b> On the great majority of installs there is no
 *       settlement companion at all, and every predicate has to answer exactly what {@code isAwake}
 *       answered before this layer existed. A capability layer that made villagers quietly less
 *       capable when a mod was missing would be worse than no capability layer.</li>
 *   <li><b>Incapacity is about acting, not about remembering.</b> A collapsed witness stops perceiving
 *       and stops reporting. What they already saw is in world data and is not touched by any of
 *       this.</li>
 * </ul>
 */
class NpcAwarenessCapabilityTest {

    private static final boolean AWAKE = true;
    private static final boolean RESPECT = true;
    private static final boolean COLLAPSED = true;
    private static final boolean MOBILE = true;
    private static final boolean TALKABLE = true;

    private static boolean collapsed(Capability capability) {
        return NpcAwareness.permits(capability, AWAKE, RESPECT, COLLAPSED, MOBILE, TALKABLE);
    }

    @Test
    void aCollapsedVillagerCannotPerceiveIdentifyOrReport() {
        assertFalse(collapsed(Capability.OBSERVE), "somebody face down in the square sees nothing");
        assertFalse(collapsed(Capability.IDENTIFY), "and puts a name to nobody");
        assertFalse(collapsed(Capability.SPEAK), "and carries no report to a guard");
    }

    @Test
    void aCollapsedVillagerIsNoUseAsArmedSupportAndStartsNoCrime() {
        assertFalse(collapsed(Capability.GUARD_RESPONSE),
                "counting a collapsed guard as law is how a village looks defended while nobody is");
        assertFalse(collapsed(Capability.OBSERVE),
                "the ally count in ThreatContexts asks this one; a floored villager is not backup");
        assertFalse(collapsed(Capability.CRIMINAL_ACTION), "and starts no mugging of their own");
        assertFalse(collapsed(Capability.NAVIGATE), "and is not walked anywhere");
        assertFalse(collapsed(Capability.HANDS), "and neither takes nor gives anything");
    }

    @Test
    void aCollapsedVillagerKeepsWhatTheyAlreadySaw() {
        UUID observer = UUID.randomUUID();
        UUID offender = UUID.randomUUID();
        CrimeObservation stored = new CrimeObservation(UUID.randomUUID(), UUID.randomUUID(), observer,
                ObserverRole.EYEWITNESS, offender, null, CrimeIds.MUGGING,
                new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, 100L, 0.9F,
                true, true, false, ReportState.PENDING, 10_000L);

        // The observation was made before the collapse and is not this layer's to touch.
        assertFalse(collapsed(Capability.SPEAK), "they cannot deliver it while they are down");
        assertTrue(stored.pending(), "but it is still a pending report");
        assertTrue(stored.identifiesActor(), "and it still names who they saw");
        assertFalse(stored.expired(200L), "and incapacity is not a statute of limitations");
        assertEquals(offender, stored.suspectedActorId());
    }

    @Test
    void unavailableDataFallsBackToAwake() {
        for (Capability capability : Capability.values()) {
            assertTrue(NpcAwareness.permits(capability, AWAKE, RESPECT, false, MOBILE, TALKABLE),
                    capability + ": an untracked reading is not evidence of anything");
            assertTrue(NpcAwareness.permits(capability, AWAKE, false, COLLAPSED, false, false),
                    capability + ": respectIncapacity off means the pre-Townstead behaviour, exactly");
        }
    }

    @Test
    void sleepStillDecidesEverythingFirst() {
        for (Capability capability : Capability.values()) {
            assertFalse(NpcAwareness.permits(capability, false, false, false, MOBILE, TALKABLE),
                    capability + ": asleep is asleep whatever else is true");
        }
    }

    @Test
    void anImmobileStageStopsMovementWithoutStoppingPerception() {
        assertFalse(NpcAwareness.permits(Capability.NAVIGATE, AWAKE, RESPECT, false, false, TALKABLE));
        assertFalse(NpcAwareness.permits(Capability.GUARD_RESPONSE, AWAKE, RESPECT, false, false, TALKABLE));
        assertFalse(NpcAwareness.permits(Capability.CRIMINAL_ACTION, AWAKE, RESPECT, false, false, TALKABLE));
        assertTrue(NpcAwareness.permits(Capability.OBSERVE, AWAKE, RESPECT, false, false, TALKABLE),
                "a villager who cannot walk can still watch a crime happen in front of them");
        assertTrue(NpcAwareness.permits(Capability.SPEAK, AWAKE, RESPECT, false, false, TALKABLE),
                "and can still say so");
    }

    @Test
    void anUntalkableStageStopsSpeechAlone() {
        assertFalse(NpcAwareness.permits(Capability.SPEAK, AWAKE, RESPECT, false, MOBILE, false));
        assertTrue(NpcAwareness.permits(Capability.OBSERVE, AWAKE, RESPECT, false, MOBILE, false));
        assertTrue(NpcAwareness.permits(Capability.NAVIGATE, AWAKE, RESPECT, false, MOBILE, false));
        assertTrue(NpcAwareness.permits(Capability.HANDS, AWAKE, RESPECT, false, MOBILE, false));
    }
}
