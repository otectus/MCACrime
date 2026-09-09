package dev.otectus.mcacrime;

import dev.otectus.mcacrime.bounty.BountyClaimKey;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.detect.DamageFinality;
import dev.otectus.mcacrime.detect.DeathConsequences;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.state.world.CrimeDataMigrations;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeathConsequencesTest {
    private final UUID target = UUID.randomUUID(), hunter = UUID.randomUUID(), owner = UUID.randomUUID();
    private final CrimeWorldData data = new CrimeWorldData();
    private final Warrant warrant = Warrant.open(UUID.randomUUID(), target,
            ResourceLocation.fromNamespaceAndPath("mcacrime", "murder_player"), UUID.randomUUID(), 10);
    private BountyService.KillOffer offer(boolean eligible, boolean lethal) {
        return new BountyService.KillOffer(BountyClaimKey.of(warrant), hunter, 100, "mcacrime:emerald",
                eligible, lethal, "Target");
    }
    private BountyService.Payout claim(BountyService.KillOffer offer, boolean confirmed) {
        return BountyService.claimKill(data, offer, confirmed, true, "mcacrime:emerald", 20);
    }
    private void prepare() {
        data.putWarrant(warrant);
        data.putStolenGoods(new StolenGoodsRecord(UUID.randomUUID(), target, owner, null, 12, 10));
    }

    @Test void canceledDeathRunsNoBountyPropertyOrControllerCleanup() {
        prepare(); AtomicInteger cleanup = new AtomicInteger();
        var finality = new DamageFinality(() -> false, () -> 100); finality.death(() -> true);
        var effects = new DeathConsequences(List.of(() -> claim(offer(true, true), true),
                () -> StolenGoodsLedger.escrowAll(data, target, 20), cleanup::incrementAndGet));
        assertFalse(effects.confirm(finality.resolve(true) == DamageFinality.Outcome.KILL));
        assertEquals(1, data.stolenGoodsByThief(target).size());
        assertTrue(data.bountyClaims().isEmpty()); assertTrue(data.propertyEscrow().isEmpty()); assertEquals(0, cleanup.get());
    }
    @Test void revivedDeathRunsNoConsequences() {
        var finality = new DamageFinality(() -> false, () -> 100); finality.death(() -> false);
        var effects = new DeathConsequences(List.of(() -> fail("revived")));
        assertFalse(effects.confirm(finality.resolve(false) == DamageFinality.Outcome.KILL));
    }
    @Test void laterCancellationIsRespectedBeforeCommittingEffects() {
        AtomicBoolean canceled = new AtomicBoolean(false);
        var finality = new DamageFinality(() -> false, () -> 100); finality.death(canceled::get);
        var effects = new DeathConsequences(List.of(() -> fail("late cancellation")));
        canceled.set(true); assertFalse(effects.confirm(finality.resolve(true) == DamageFinality.Outcome.KILL));
    }
    @Test void confirmedDeathMovesPropertyAndClaimsOnlyOnce() {
        prepare(); AtomicInteger cleanup = new AtomicInteger();
        var effects = new DeathConsequences(List.of(() -> assertTrue(claim(offer(true, true), true).claimed()),
                () -> StolenGoodsLedger.escrowAll(data, target, 20), cleanup::incrementAndGet));
        assertTrue(effects.confirm(true)); assertFalse(effects.confirm(true));
        assertTrue(data.stolenGoodsByThief(target).isEmpty()); assertEquals(12, data.propertyEscrowFor(owner).get(0).currency());
        assertEquals(1, data.bountyClaims().size()); assertEquals(1, cleanup.get());
    }
    @Test void failureInRewardDoesNotSuppressPropertyOrCleanup() {
        prepare(); AtomicInteger cleanup = new AtomicInteger();
        var effects = new DeathConsequences(List.of(() -> { throw new IllegalStateException("provider failure"); },
                () -> StolenGoodsLedger.escrowAll(data, target, 20), cleanup::incrementAndGet));
        assertTrue(effects.confirm(true)); assertEquals(1, data.propertyEscrowFor(owner).size()); assertEquals(1, cleanup.get());
    }
    @Test void reentryCannotRepeatAnyDeathAction() {
        AtomicInteger calls = new AtomicInteger(); DeathConsequences[] effects = new DeathConsequences[1];
        effects[0] = new DeathConsequences(List.of(() -> assertFalse(effects[0].confirm(true)), calls::incrementAndGet));
        assertTrue(effects[0].confirm(true)); assertEquals(1, calls.get());
    }
    @Test void killClaimNeedsConfirmedDeath() { prepare(); assertFalse(claim(offer(true, true), false).claimed()); }
    @Test void bountyEligibilityDoesNotGrantLethalForce() { prepare(); assertFalse(claim(offer(true, false), true).claimed()); }
    @Test void lethalForceAloneDoesNotGrantReward() { prepare(); assertFalse(claim(offer(false, true), true).claimed()); }
    @Test void missingWarrantRefusesClaim() { assertFalse(claim(offer(true, true), true).claimed()); }
    @Test void closedWarrantRefusesClaim() { data.putWarrant(warrant.closed(15)); assertFalse(claim(offer(true, true), true).claimed()); }
    @Test void revisedWarrantCannotBeSilentlyAdopted() {
        data.putWarrant(warrant.revised(UUID.randomUUID(), warrant.topOffense(), 15));
        assertFalse(claim(offer(true, true), true).claimed()); assertTrue(data.bountyClaims().isEmpty());
    }
    @Test void replacementWarrantCannotBeSilentlyAdopted() {
        data.putWarrant(Warrant.open(UUID.randomUUID(), target, warrant.topOffense(), null, 16));
        assertFalse(claim(offer(true, true), true).claimed());
    }
    @Test void providerChangeRefusesWithoutConsumingClaim() {
        prepare(); assertFalse(BountyService.claimKill(data, offer(true, true), true, true, "other:coins", 20).claimed());
        assertTrue(claim(offer(true, true), true).claimed());
    }
    @Test void disabledRewardRefusesWithoutConsumingClaim() {
        prepare(); assertFalse(BountyService.claimKill(data, offer(true, true), true, false, "mcacrime:emerald", 20).claimed());
        assertTrue(data.bountyClaims().isEmpty());
    }
    @Test void capturedPriceDoesNotReadoptLaterLedgerTotals() {
        prepare(); var paid = claim(offer(true, true), true); assertTrue(paid.claimed()); assertEquals(100, paid.amount());
        assertFalse(claim(offer(true, true), true).claimed());
    }
    @Test void selfClaimRefuses() {
        prepare(); var offer = new BountyService.KillOffer(BountyClaimKey.of(warrant), target, 100, "mcacrime:emerald", true, true, "Self");
        assertFalse(claim(offer, true).claimed());
    }
    @Test void saveReloadPreservesClaimReplayProtection() {
        prepare(); assertTrue(claim(offer(true, true), true).claimed());
        var loaded = CrimeWorldData.load(data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY), net.minecraft.core.RegistryAccess.EMPTY);
        assertFalse(BountyService.claimKill(loaded, offer(true, true), true, true, "mcacrime:emerald", 30).claimed());
    }
    @Test void futureStoreCannotAcquireADeathReward() {
        var tag = new CompoundTag(); tag.putInt(CrimeDataMigrations.TAG_SCHEMA, CrimeDataMigrations.CURRENT_SCHEMA + 1);
        assertFalse(BountyService.claimKill(CrimeWorldData.load(tag, net.minecraft.core.RegistryAccess.EMPTY), offer(true, true), true, true, "mcacrime:emerald", 30).claimed());
    }
}
