package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.thief.ThiefState;
import dev.otectus.mcacrime.ai.thief.ThiefWorkGate;
import dev.otectus.mcacrime.job.NativeOccupationPolicy;
import dev.otectus.mcacrime.job.NativeOccupationPolicy.NativeDecision;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the two acquisition mixins apply, and the work-yield rule the brain wrapper applies.
 *
 * <p>A mixin cannot be unit-tested — it only exists after the transformer has run — which is exactly
 * why every decision inside one was extracted into plain functions. These are those functions.
 */
class NativeOccupationPolicyTest {

    /** Stand-ins for two points of interest; the composition never looks inside a holder. */
    private static final Holder<PoiType> STATION = null;

    @Test
    void everyNonThiefProfessionLosesTheStationAndKeepsEverythingElse() {
        Predicate<Holder<PoiType>> original = holder -> true;
        Predicate<Holder<PoiType>> filtered =
                NativeOccupationPolicy.excludingMaskStation(original, holder -> holder == STATION);

        assertFalse(filtered.test(STATION), "the mask station is the one type removed");
    }

    @Test
    void anAlreadyNarrowedPredicateStaysNarrowed() {
        // Another mod may have restricted a profession's acquirable sites. Composing rather than
        // replacing is what keeps that restriction: a POI the original refused is still refused.
        Predicate<Holder<PoiType>> original = holder -> false;
        Predicate<Holder<PoiType>> filtered =
                NativeOccupationPolicy.excludingMaskStation(original, holder -> false);

        assertFalse(filtered.test(STATION));
    }

    @Test
    void aNullOriginalPredicateAcceptsNothingRatherThanEverything() {
        assertFalse(NativeOccupationPolicy.excludingMaskStation(null, holder -> false).test(STATION));
    }

    @Test
    void anUnrelatedJobSiteIsDelegatedToVanillaUntouched() {
        assertEquals(NativeDecision.DELEGATE,
                NativeOccupationPolicy.decide(false, true, true, true));
        assertEquals(NativeDecision.DELEGATE,
                NativeOccupationPolicy.decide(false, false, false, false),
                "a composter is nothing to do with this mixin whatever the thief state says");
    }

    @Test
    void aCommittedThiefStandingAtItsStationRebinds() {
        assertEquals(NativeDecision.REBIND, NativeOccupationPolicy.decide(true, true, true, true));
    }

    @Test
    void arrivalIsRequiredRatherThanTheSpawnDistanceShortcut() {
        assertEquals(NativeDecision.REJECT, NativeOccupationPolicy.decide(true, false, true, true),
                "a thief that has not reached the station must not claim it from across the village");
    }

    @Test
    void unapprovedNativeAcquisitionIsRejected() {
        assertEquals(NativeDecision.REJECT, NativeOccupationPolicy.decide(true, true, false, true),
                "a villager with no Crime occupation must not become a visible Thief natively");
    }

    @Test
    void disabledThievesCannotAcquireAStationEvenWhenCommitted() {
        assertEquals(NativeDecision.REJECT, NativeOccupationPolicy.decide(true, true, true, false));
    }

    @Test
    void workYieldsToEachOfTheFiveConditionsIndependently() {
        assertFalse(ThiefWorkGate.shouldYield(false, false, false, false, false),
                "with nothing happening, ordinary work runs exactly as MCA wrote it");
        assertTrue(ThiefWorkGate.shouldYield(true, false, false, false, false), "custody");
        assertTrue(ThiefWorkGate.shouldYield(false, true, false, false, false), "sleep");
        assertTrue(ThiefWorkGate.shouldYield(false, false, true, false, false), "panic");
        assertTrue(ThiefWorkGate.shouldYield(false, false, false, true, false), "enforcement");
        assertTrue(ThiefWorkGate.shouldYield(false, false, false, false, true), "an active crime action");
    }

    @Test
    void anIdleOrCoolingThiefIsNotAnActiveCrimeAction() {
        assertFalse(ThiefWorkGate.crimeActive(ThiefState.IDLE));
        assertFalse(ThiefWorkGate.crimeActive(ThiefState.COOLDOWN));
        assertFalse(ThiefWorkGate.crimeActive(ThiefState.DEAD));
        assertTrue(ThiefWorkGate.crimeActive(ThiefState.APPROACHING));
        assertTrue(ThiefWorkGate.crimeActive(ThiefState.MUGGING));
        assertTrue(ThiefWorkGate.crimeActive(ThiefState.ARRESTED),
                "an arrested thief is owned by custody, so work must not start on top of it");
    }
}
