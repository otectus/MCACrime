package dev.otectus.mcacrime;

import dev.otectus.mcacrime.detect.WitnessModifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a lookout and a distraction actually do to witness selection.
 *
 * <p>Two properties matter most here and neither is obvious from the code. A lookout multiplier
 * <em>composes</em> — two relatives watching are better than one — but is clamped, so a household of
 * lookouts is not invisibility. And a distraction never applies to a responder at any distance,
 * because a guard that can be waved away from a crime makes the whole enforcement system optional.
 */
class WitnessModifiersTest {

    private static final UUID OFFENDER = UUID.nameUUIDFromBytes("offender".getBytes());
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @AfterEach
    void clear() {
        WitnessModifiers.clearAll();
    }

    private static WitnessModifiers.Modifier lookout(UUID accomplice, double multiplier, long expiry) {
        return new WitnessModifiers.Modifier(OFFENDER, accomplice, WitnessModifiers.Kind.LOOKOUT, 0.0D,
                multiplier, expiry);
    }

    private static WitnessModifiers.Modifier distraction(UUID accomplice, double radius, long expiry) {
        return new WitnessModifiers.Modifier(OFFENDER, accomplice, WitnessModifiers.Kind.DISTRACTION,
                radius, 1.0D, expiry);
    }

    @Test
    void nobodyWatchingLeavesTheRadiusAlone() {
        assertEquals(1.0D, WitnessModifiers.witnessRadiusMultiplier(OFFENDER, 0L), 1.0E-9D);
        assertEquals(1.0D, WitnessModifiers.witnessRadiusMultiplier(null, 0L), 1.0E-9D);
    }

    @Test
    void twoLookoutsCompose() {
        assertEquals(0.36D, WitnessModifiers.composeRadiusMultiplier(
                List.of(lookout(ALICE, 0.6D, 100L), lookout(BOB, 0.6D, 100L))), 1.0E-9D);
    }

    @Test
    void theCompositionIsClampedAtBothEnds() {
        assertEquals(WitnessModifiers.MIN_RADIUS_MULTIPLIER, WitnessModifiers.composeRadiusMultiplier(
                List.of(lookout(ALICE, 0.1D, 1L), lookout(BOB, 0.1D, 1L))), 1.0E-9D);
        // A multiplier above one would widen the radius; a lookout must never make things worse.
        assertEquals(1.0D, WitnessModifiers.composeRadiusMultiplier(List.of(lookout(ALICE, 4.0D, 1L))),
                1.0E-9D);
    }

    @Test
    void aDistractionDoesNotShrinkTheRadius() {
        assertEquals(1.0D, WitnessModifiers.composeRadiusMultiplier(
                List.of(distraction(ALICE, 10.0D, 100L))), 1.0E-9D);
    }

    @Test
    void anEffectStopsWorkingOnTheExactTickItExpires() {
        WitnessModifiers.put(lookout(ALICE, 0.5D, 100L));
        assertEquals(0.5D, WitnessModifiers.witnessRadiusMultiplier(OFFENDER, 99L), 1.0E-9D);
        assertEquals(1.0D, WitnessModifiers.witnessRadiusMultiplier(OFFENDER, 100L), 1.0E-9D);
    }

    @Test
    void thesameAccompliceCannotStackOneKindWithItself() {
        WitnessModifiers.put(lookout(ALICE, 0.5D, 100L));
        WitnessModifiers.put(lookout(ALICE, 0.5D, 200L));
        assertEquals(1, WitnessModifiers.active(OFFENDER, 0L).size());
        assertEquals(0.5D, WitnessModifiers.witnessRadiusMultiplier(OFFENDER, 150L), 1.0E-9D);
    }

    @Test
    void civiliansInsideTheRadiusAreDistractedAndResponderNeverAre() {
        WitnessModifiers.Modifier scene = distraction(ALICE, 10.0D, 100L);
        assertTrue(WitnessModifiers.distracts(scene, 99.0D, false));
        assertTrue(WitnessModifiers.distracts(scene, 100.0D, false), "the radius is inclusive");
        assertFalse(WitnessModifiers.distracts(scene, 100.01D, false));
        assertFalse(WitnessModifiers.distracts(scene, 0.0D, true), "a guard is never distracted");
    }

    @Test
    void aLookoutIsNotADistractionHoweverCloseYouStand() {
        assertFalse(WitnessModifiers.distracts(lookout(ALICE, 0.5D, 100L), 0.0D, false));
    }

    @Test
    void pruningReturnsWhatItDroppedAndKeepsWhatIsStillRunning() {
        WitnessModifiers.put(lookout(ALICE, 0.5D, 100L));
        WitnessModifiers.put(distraction(BOB, 8.0D, 400L));
        List<WitnessModifiers.Modifier> dropped = WitnessModifiers.prune(150L);
        assertEquals(1, dropped.size());
        assertEquals(ALICE, dropped.get(0).accomplice());
        assertEquals(1, WitnessModifiers.active(OFFENDER, 150L).size());
    }

    @Test
    void removingOneKindLeavesTheOther() {
        WitnessModifiers.put(lookout(ALICE, 0.5D, 400L));
        WitnessModifiers.put(distraction(ALICE, 8.0D, 400L));
        WitnessModifiers.remove(ALICE, WitnessModifiers.Kind.DISTRACTION);
        assertEquals(1, WitnessModifiers.active(OFFENDER, 0L).size());
        assertTrue(WitnessModifiers.distractions(OFFENDER, 0L).isEmpty());
    }
}
