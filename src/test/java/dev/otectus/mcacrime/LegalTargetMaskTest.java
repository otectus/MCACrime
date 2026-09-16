package dev.otectus.mcacrime;

import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.LegalBasis;
import dev.otectus.mcacrime.enforcement.LegalTarget;
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
 * The masked-pursuit term of the legal table (0.7.0): what a responder who saw a crime but not a face
 * is allowed to do about it.
 *
 * <p>The interesting assertion is the last one. A masked offender is a lawful target and must not be
 * bounty-eligible, because a bounty is paid against a warrant and a warrant names a person — which is
 * precisely what nobody watching a masked crime can supply.
 */
class LegalTargetMaskTest {

    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ResourceLocation OFFENSE = new ResourceLocation("mcacrime", "mugging");

    private static OutlawStatus resolve(boolean maskedPursuit, boolean maskedLethal, Warrant warrant) {
        return OutlawResolver.evaluate(false, Band.GREY, false, true, false, false, false,
                maskedPursuit, maskedLethal, 0L, 0L, warrant, true, false);
    }

    @Test
    void maskedPursuitAloneIsALegalTarget() {
        assertTrue(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false, false, true));
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false, false, false));
    }

    @Test
    void theOlderOverloadsHaveNoMaskedTerm() {
        // Every existing caller delegates with maskedPursuit=false; nothing changes for them.
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false, false));
        assertFalse(LegalTarget.isLegalTarget(false, Band.GREY, false, false, false));
    }

    @Test
    void maskedPursuitSitsBelowACaptorAndAboveWanted() {
        assertEquals(LegalBasis.MASKED_OFFENDER,
                LegalTarget.basisOf(true, false, false, false, false, true));
        assertEquals(LegalBasis.HOLDING_CAPTIVE,
                LegalTarget.basisOf(true, false, false, true, false, true));
        assertEquals(LegalBasis.WANTED,
                LegalTarget.basisOf(true, false, false, false, false, false));
    }

    @Test
    void lethalForceStaysUnlawfulUnlessConfigured() {
        assertTrue(resolve(true, false, null).lawfulCombatTarget());
        assertFalse(resolve(true, false, null).lethalForceLawful(), "subdue, do not execute");
        assertTrue(resolve(true, true, null).lethalForceLawful());
    }

    @Test
    void aMaskedOffenderIsNeverBountyEligible() {
        Warrant open = Warrant.open(UUID.randomUUID(), OFFENDER, OFFENSE, UUID.randomUUID(), 100L);
        OutlawStatus status = resolve(true, false, open);
        assertEquals(LegalBasis.MASKED_OFFENDER, status.basis());
        assertFalse(status.bountyEligible(), "a bounty needs a name, which is what the mask removed");
    }
}
