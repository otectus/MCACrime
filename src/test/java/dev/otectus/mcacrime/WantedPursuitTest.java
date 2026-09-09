package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.CrimeMath;
import dev.otectus.mcacrime.economy.DispositionService;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestPhases;
import dev.otectus.mcacrime.enforcement.ChallengeBasis;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.justice.LegalDecision;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WantedPursuitTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final JusticeService.Settings LOCAL = new JusticeService.Settings(true, false, .75);

    private LegalDecision assess(CrimeWorldData world, long heat, long threshold, boolean resisting) {
        return JusticeService.evaluate(world, PLAYER, null, 10L, LOCAL, false, false,
                CrimeMath.isWanted(heat, threshold), resisting);
    }

    @Test void oneHundredHeatAuthorizesPursuitChallengeAndArrestWithoutAnyCaseOrReport() {
        var player = new PlayerCrimeData();
        player.setHeat(100); // The value written by CrimeState for the command and incident paths.
        long threshold = McaCrimeConfig.COMMON.wantedHeatThreshold.getDefault();
        var decision = assess(new CrimeWorldData(), player.getHeat(), threshold, false);
        assertTrue(decision.mayChallenge());
        assertEquals(Set.of(LegalDecision.Basis.WANTED), decision.basis());
        assertTrue(decision.cases().isEmpty());
        assertTrue(ChallengeBasis.hasBasis(0, false, false, false,
                CrimeMath.isWanted(player.getHeat(), threshold), false));
        assertEquals("mcacrime.challenge.reason.wanted", decision.detentionReasonKey());
    }

    @Test void configuredWantedBoundaryIsInclusiveAndReevaluatedWhenHeatChanges() {
        var world = new CrimeWorldData();
        assertFalse(assess(world, 99, 100, false).mayChallenge());
        assertTrue(assess(world, 100, 100, false).mayChallenge());
        assertTrue(assess(world, 101, 100, false).mayChallenge());
        assertFalse(assess(world, 99, 100, false).mayChallenge());
        assertFalse(assess(world, 0, 100, false).mayChallenge());
        assertFalse(assess(world, 100, 150, false).mayChallenge());
        assertTrue(assess(world, 0, 0, false).mayChallenge());
    }

    @Test void wantedDoesNotRevealOrFinePrivateCasesInAnyJurisdiction() {
        var world = new CrimeWorldData();
        var village = new CrimeCommunityKey(ResourceLocation.tryParse("minecraft:overworld"), 1);
        world.addRecord(new CrimeRecord(UUID.randomUUID(), PLAYER, null,
                ResourceLocation.tryParse("mcacrime:theft"), OptionalInt.empty(), village, false,
                Set.of(), 1L, 100L, -10L, 500L, 0L, Resolution.UNRESOLVED, 0L, List.of(), null, Map.of()));
        for (CrimeCommunityKey jurisdiction : java.util.Arrays.asList(null, village,
                new CrimeCommunityKey(ResourceLocation.tryParse("minecraft:the_nether"), 2))) {
            var decision = JusticeService.evaluate(world, PLAYER, jurisdiction, 10L, LOCAL,
                    false, false, true, false);
            assertTrue(decision.mayChallenge());
            assertTrue(decision.cases().isEmpty());
            var offer = DispositionService.offer(world, decision, 100, Band.GREY, 10L,
                    new SettlementPolicy.Settings(true, 8, 1, 80, .5, true, 8));
            assertFalse(offer.quote().ok(), "Heat-only detention must not invent payable charges");
            assertTrue(offer.quote().caseIds().isEmpty());
        }
        assertEquals(1, world.actionableFor(PLAYER).size());
    }

    @Test void refusalRemainsEnforceableAfterHeatFallsButEndsWhenResistanceDoes() {
        var world = new CrimeWorldData();
        var refused = assess(world, 0, 50, true);
        assertTrue(refused.mayChallenge());
        assertEquals(Set.of(LegalDecision.Basis.RESISTING_ARREST), refused.basis());
        assertEquals("mcacrime.challenge.reason.resisting", refused.detentionReasonKey());
        assertTrue(ChallengeBasis.hasBasis(0, false, false, false, false, true));
        assertTrue(ArrestPhases.forcePermitted(ArrestPhase.NONE, true, true));
        assertFalse(assess(world, 0, 50, false).mayChallenge());
    }

    @Test void wantedStillHonorsTheResponseWindowAndExistingCustody() {
        assertFalse(ArrestPhases.forcePermitted(ArrestPhase.CONFRONTED, true, false));
        for (ArrestPhase phase : List.of(ArrestPhase.RESTRAINED, ArrestPhase.JAILED)) {
            assertFalse(ArrestPhases.canOpenChallenge(phase));
            assertFalse(ArrestPhases.forcePermitted(phase, true, true));
        }
        assertFalse(ArrestPhases.canOpenChallenge(ArrestPhase.RECOVERY));
        assertFalse(ArrestPhases.forcePermitted(ArrestPhase.RECOVERY, true, false));
    }

    @Test void readOnlyFutureDataCannotAuthorizeAnArrestEvenWithWantedHeat() {
        CompoundTag tag = new CrimeWorldData().save(new CompoundTag());
        tag.putInt("schema", Integer.MAX_VALUE);
        assertFalse(assess(CrimeWorldData.load(tag), 100, 50, true).mayChallenge());
    }
}
