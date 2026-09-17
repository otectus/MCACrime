package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.activity.OperationPolicy;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.economy.SettlementPolicy;
import dev.otectus.mcacrime.economy.SettlementQuote;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two claims about offering civic work: it is only ever an alternative to something, and an NPC doing
 * it is held by a claim that both takes and lets go.
 *
 * <p>Neither half needs a server. The eligibility question is "would the ordinary settlement path have
 * priced this case?", which is {@code SettlementPolicy} with its settings handed in; the claim
 * question is the activity registry, which is memory-only by design. What cannot be exercised here is
 * the live {@code offer} call, because it reads a {@code ForgeConfigSpec} that no unit test has
 * loaded — and the one thing worth asserting about that is asserted below: with no config loaded the
 * switch reads as off and the whole feature is inert.
 */
class CivicWorkOfferTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final CrimeCommunityKey COMMUNITY = new CrimeCommunityKey(OVERWORLD, 3);
    private static final UUID OFFENDER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID VILLAGER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    /** Fines on, a real price per point of Heat, and nothing barred — the ordinary server. */
    private static final SettlementPolicy.Settings SETTINGS =
            new SettlementPolicy.Settings(true, 10, 2, 100, 0.5D, true, 4);

    @BeforeEach
    @AfterEach
    void clearClaims() {
        CrimeActivityRegistry.clearAll();
    }

    private static CrimeWorldData world(CrimeRecord... records) {
        CompoundTag tag = new CompoundTag();
        net.minecraft.nbt.ListTag ledger = new net.minecraft.nbt.ListTag();
        for (CrimeRecord record : records) {
            ledger.add(record.save());
        }
        tag.put("ledger", ledger);
        return CrimeWorldData.load(tag);
    }

    private static CrimeRecord record(UUID id, Map<String, String> context) {
        return new CrimeRecord(id, OFFENDER, null, new ResourceLocation("mcacrime", "theft"),
                java.util.OptionalInt.of(COMMUNITY.villageId()), COMMUNITY, true, Set.of(VILLAGER),
                100L, 20L, 0L, 0L, 0L, Resolution.UNRESOLVED, 0L, List.of(), null, context);
    }

    @Test
    void theFeatureIsInertWithNoConfigLoaded() {
        assertFalse(CivicWorkService.enabled(),
                "every entry point asks this first; a throw from an unloaded config must read as off");
        assertEquals(CivicWorkService.Refusal.DISABLED,
                CivicWorkService.offer(null, OFFENDER, true, CivicTask.VICTIM_AMENDS, null, null)
                        .refusal());
    }

    @Test
    void anOfferIsOnlyMadeWhereTheSettlementPathAlreadyPricesTheCase() {
        UUID caseId = UUID.randomUUID();
        CrimeWorldData data = world(record(caseId, Map.of()));

        SettlementQuote quote = SettlementPolicy.quoteExact(data, OFFENDER, 20L, Band.GREY,
                List.of(caseId), 100L, SETTINGS);

        assertTrue(quote.reject().isEmpty(), "this is the case a contract may stand in for");
        assertTrue(quote.amount() > 0L, "and there has to be a price for the work to replace");
    }

    @Test
    void aCaseThatCannotBeBoughtOffCannotBeWorkedOffEither() {
        UUID caseId = UUID.randomUUID();
        CrimeWorldData data = world(record(caseId,
                Map.of(CrimeContext.FLAGS, CrimeFlag.encode(EnumSet.of(CrimeFlag.MANDATORY_CUSTODY)))));

        SettlementQuote quote = SettlementPolicy.quoteExact(data, OFFENDER, 20L, Band.GREY,
                List.of(caseId), 100L, SETTINGS);

        assertEquals(Optional.of(SettlementQuote.RejectReason.MANDATORY_CUSTODY), quote.reject(),
                "community service is an alternative to a fine, so where there is no fine there is no "
                        + "alternative -- a murder must not become two days of errands");
    }

    @Test
    void aRestitutionContractIsNotOfferedWhenNothingCouldCreditIt() {
        assertFalse(CivicWorkService.creditable(CivicTask.RESTITUTION_DELIVERY),
                "property law is off in this fixture, so no receipt is ever written and returning the "
                        + "goods could never be counted; offering that task would be a trap");
        assertTrue(CivicWorkService.creditable(CivicTask.GUARD_ASSIST_PATROL));
        assertTrue(CivicWorkService.creditable(CivicTask.VICTIM_AMENDS));
    }

    @Test
    void anNpcOnCivicDutyIsClaimedAndTheClaimStopsAWorkShiftStarting() {
        long generation = CrimeActivityRegistry.claim(VILLAGER, OVERWORLD,
                CrimeActivityView.Kind.CIVIC_SERVICE, CivicWorkService.CLAIM_OWNER, 100L, 40);

        assertTrue(generation != CrimeActivityRegistry.REFUSED);
        CrimeActivityView claim = CrimeActivityRegistry.activeFor(VILLAGER, 100L).orElse(null);
        assertNotNull(claim);
        assertEquals(CrimeActivityView.Kind.CIVIC_SERVICE, claim.kind());
        assertTrue(claim.requiresYield(CrimeActivityOperation.WORK_START),
                "the whole point of the claim: a settlement work shift may not start on top of it");
        assertTrue(claim.requiresYield(CrimeActivityOperation.REST_TRAVEL));
        assertFalse(claim.requiresYield(CrimeActivityOperation.REACTION_LOCK),
                "a villager on community service waving at a neighbour is not a bug");
    }

    @Test
    void theClaimIsReleasedWhenTheContractCloses() {
        ServiceContract contract = ServiceContract.offered(UUID.randomUUID(), UUID.randomUUID(),
                VILLAGER, false, COMMUNITY, CivicTask.VICTIM_AMENDS, 1, 100L, 200L, null);
        CrimeActivityRegistry.claim(VILLAGER, OVERWORLD, CrimeActivityView.Kind.CIVIC_SERVICE,
                CivicWorkService.CLAIM_OWNER, 100L, 40);

        CivicWorkService.releaseClaim(contract);

        assertFalse(CrimeActivityRegistry.isClaimed(VILLAGER, 100L),
                "a finished contract that kept its claim would hold the villager off work forever");
    }

    @Test
    void aPlayerContractNeverTouchesTheActivityRegistry() {
        ServiceContract contract = ServiceContract.offered(UUID.randomUUID(), UUID.randomUUID(),
                OFFENDER, true, COMMUNITY, CivicTask.VICTIM_AMENDS, 1, 100L, 200L, null);
        CrimeActivityRegistry.claim(OFFENDER, OVERWORLD, CrimeActivityView.Kind.CUSTODY,
                "jail", 100L, 40);

        CivicWorkService.releaseClaim(contract);

        assertTrue(CrimeActivityRegistry.isClaimed(OFFENDER, 100L),
                "a player's contract has no claim of its own, so closing it must not clear somebody "
                        + "else's -- least of all a custody claim");
    }

    @Test
    void civicWorkNeverOutranksTheLaw() {
        CrimeActivityRegistry.claim(VILLAGER, OVERWORLD, CrimeActivityView.Kind.ARREST, "guard",
                100L, 40);

        long refused = CrimeActivityRegistry.claim(VILLAGER, OVERWORLD,
                CrimeActivityView.Kind.CIVIC_SERVICE, CivicWorkService.CLAIM_OWNER, 100L, 40);

        assertEquals(CrimeActivityRegistry.REFUSED, refused,
                "an arrest in progress must be able to take a villager straight out of civic work");
        assertTrue(OperationPolicy.yields(CrimeActivityView.Kind.CIVIC_SERVICE,
                CrimeActivityOperation.WORK_START));
    }

    @Test
    void aCollapsedOrRestingVillagerIsNotHeldToTheWork() {
        assertTrue(CivicWorkService.claimable(true, false));
        assertFalse(CivicWorkService.claimable(false, false),
                "the contract yields to incapacity exactly as everything else in this mod does");
        assertFalse(CivicWorkService.claimable(true, true),
                "civic work does not run through somebody's night");
    }
}
