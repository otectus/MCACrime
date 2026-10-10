package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.detect.CrimeGate;
import dev.otectus.mcacrime.enforcement.LegalBasis;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.ledger.Warrant;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code enforcement.outlawCombatLegal}: an outlaw may be fought and killed by anybody, with no Wanted
 * status or bounty behind it, and nothing else about the law moves (0.7.7).
 *
 * <p>Four answers sit side by side and the point is that they stay apart: whether the guards hunt
 * somebody ({@code lawfulCombatTarget}), what the guards may do ({@code lethalForceLawful}), whether
 * there is a price ({@code bountyEligible}), and whether a private fight is charged
 * ({@code attackLawful}, {@code killLawful}). Only the last two are this setting's.
 */
class OutlawCombatLegalityTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ResourceLocation OFFENSE = new ResourceLocation("mcacrime", "assault");

    private static Warrant openWarrant() {
        return Warrant.open(UUID.randomUUID(), OFFENDER, OFFENSE, UUID.randomUUID(), 100L);
    }

    /** The full picture, with the setting and the two legacy Red-band switches named. */
    private static OutlawStatus evaluate(boolean wanted, Band band, boolean redIsLegalTarget,
                                         boolean allowKillingRed, boolean escaped, boolean holdingCaptive,
                                         boolean resisting, Warrant warrant, boolean redBandBounty,
                                         boolean outlawCombatLegal) {
        return OutlawResolver.evaluate(wanted, band, redIsLegalTarget, allowKillingRed, escaped, holdingCaptive,
                resisting, false, false, 0L, -500L, warrant, true, redBandBounty, outlawCombatLegal);
    }

    private static OutlawStatus outlawOnly(boolean outlawCombatLegal) {
        return evaluate(false, Band.RED, false, false, false, false, false, null, false, outlawCombatLegal);
    }

    @Test
    void anOutlawMayBeFoughtAndKilledWithNoWantedStatusOrBounty() {
        OutlawStatus outlaw = outlawOnly(true);

        assertTrue(outlaw.attackLawful(), "hitting an outlaw is no crime");
        assertTrue(outlaw.killLawful(), "killing one is no crime either");
        assertTrue(CrimeGate.forceLawful(outlaw, false));
        assertTrue(CrimeGate.forceLawful(outlaw, true));
    }

    @Test
    void theSettingDoesNotSendTheGuardsOrPutAPriceOnAReputation() {
        OutlawStatus outlaw = evaluate(false, Band.RED, false, false, false, false, false, openWarrant(), false,
                true);

        assertFalse(outlaw.lawfulCombatTarget(), "an outlaw anybody may fight is not one the guards must hunt");
        assertFalse(outlaw.lethalForceLawful(), "the law's own force is still allowKillingRed's question");
        assertFalse(outlaw.bountyEligible(), "a reputation is not a warrant");
        assertEquals(LegalBasis.NONE, outlaw.basis());
    }

    @Test
    void offRestoresTheLegacyRedBandRulesExactly() {
        assertFalse(outlawOnly(false).attackLawful(), "a reputation alone justifies nothing");
        assertFalse(outlawOnly(false).killLawful());

        OutlawStatus subdueOnly = evaluate(false, Band.RED, true, false, false, false, false, null, false, false);
        assertTrue(subdueOnly.attackLawful(), "redIsLegalTarget lets anybody subdue an outlaw");
        assertFalse(subdueOnly.killLawful(), "allowKillingRed off forbids executing one");
        assertFalse(CrimeGate.forceLawful(subdueOnly, true));

        OutlawStatus execute = evaluate(false, Band.RED, true, true, false, false, false, null, false, false);
        assertTrue(execute.killLawful());
    }

    @Test
    void withTheSettingOnAllowKillingRedNoLongerDecidesAPrivateKilling() {
        OutlawStatus status = evaluate(false, Band.RED, true, false, false, false, false, null, false, true);

        assertTrue(status.lawfulCombatTarget(), "redIsLegalTarget still sends the guards");
        assertFalse(status.lethalForceLawful(), "and still bars the guards from executing on reputation alone");
        assertTrue(status.killLawful(), "but a private fight with an outlaw is legal to the end");
    }

    @Test
    void nobodyWhoIsNotAnOutlawIsCovered() {
        for (Band band : new Band[]{Band.GREY, Band.BLUE}) {
            OutlawStatus status = evaluate(false, band, true, true, false, false, false, null, true, true);
            assertFalse(status.attackLawful(), band + " is protected");
            assertFalse(status.killLawful(), band + " is protected");
            assertFalse(CrimeGate.forceLawful(status, false));
        }
    }

    @Test
    void everyLegalTargetMayStillBeFoughtWhateverTheSetting() {
        for (boolean setting : new boolean[]{true, false}) {
            assertTrue(evaluate(true, Band.GREY, false, false, false, false, false, null, false, setting)
                    .killLawful(), "a Wanted player may be killed");
            assertTrue(evaluate(false, Band.BLUE, false, false, true, false, false, null, false, setting)
                    .attackLawful(), "an escaped prisoner may be stopped");
            assertTrue(evaluate(false, Band.GREY, false, false, false, true, false, null, false, setting)
                    .killLawful(), "an active kidnapper may be killed");
            assertTrue(evaluate(false, Band.GREY, false, false, false, false, true, null, false, setting)
                    .attackLawful(), "resisting arrest may be met with force");
        }
    }

    @Test
    void theOlderOverloadsKeepTheirMeaning() {
        boolean[] flags = {false, true};
        for (boolean wanted : flags) for (boolean red : flags) for (boolean redLegal : flags)
            for (boolean killRed : flags) for (boolean escaped : flags) {
                Band band = red ? Band.RED : Band.GREY;
                OutlawStatus legacy = OutlawResolver.evaluate(wanted, band, redLegal, killRed, escaped, false,
                        false, false, false, 0L, 0L, null, true, false);
                assertEquals(legacy.lawfulCombatTarget(), legacy.attackLawful(),
                        "without the setting a private attack is lawful exactly when the law's is");
                assertEquals(legacy.lethalForceLawful(), legacy.killLawful(),
                        "and a private killing exactly when the law's lethal force is");
            }
    }

    @Test
    void bountyEligibilityIgnoresTheSettingEntirely() {
        boolean[] flags = {false, true};
        for (boolean wanted : flags) for (boolean redBounty : flags) for (boolean redLegal : flags) {
            OutlawStatus on = evaluate(wanted, Band.RED, redLegal, false, false, false, false, openWarrant(),
                    redBounty, true);
            OutlawStatus off = evaluate(wanted, Band.RED, redLegal, false, false, false, false, openWarrant(),
                    redBounty, false);
            assertEquals(off.bountyEligible(), on.bountyEligible(), "combat legality is not a price");
            assertEquals(off.lawfulCombatTarget(), on.lawfulCombatTarget(), "nor a reason for the guards");
        }
    }

    @Test
    void aMaskedOutlawIsResolvedWithTheSettingAlreadyOff() {
        // The resolver is handed the setting with maskSuppressesRedBandTargeting already applied, because
        // nobody can tell a masked figure is an outlaw. Off is exactly what that hands it.
        OutlawStatus hidden = outlawOnly(false);
        assertFalse(hidden.attackLawful());
        assertFalse(hidden.killLawful());
    }

    @Test
    void theGateTreatsAMissingPictureAsProtected() {
        assertFalse(CrimeGate.forceLawful(null, false));
        assertFalse(CrimeGate.forceLawful(OutlawStatus.none(0L, 0L, Band.RED), true));
    }
}
