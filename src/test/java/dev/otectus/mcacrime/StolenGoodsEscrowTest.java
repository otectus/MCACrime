package dev.otectus.mcacrime;

import dev.otectus.mcacrime.mug.npc.StolenGoodsLedger;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyEscrow;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property that could not be handed over, and where it goes instead (T20).
 *
 * <p>A claim used to be a deletion with a hopeful side effect. The ledger row came out, the caller was
 * given it, and whether anybody ever received the sword was somebody else's problem — so an owner who
 * was offline, far away, or simply full lost the item outright, and the expiry sweep deleted whatever
 * was left after a week under the heading "laundered". The tests below cover the three moments that
 * used to lose property: an undeliverable claim, an expiry, and a delivery into an inventory with no
 * room.
 *
 * <p>Currency-only records throughout: an {@code ItemStack} needs an item registry and none of this
 * logic looks at one. {@link RegistryAccess#EMPTY} is enough for the one round trip that persists.
 */
class StolenGoodsEscrowTest {

    private static final UUID THIEF = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static UUID file(CrimeWorldData data, long currency, long stolenAt) {
        UUID transaction = UUID.randomUUID();
        data.putStolenGoods(new StolenGoodsRecord(transaction, THIEF, OWNER, null, currency, stolenAt));
        return transaction;
    }

    @Test
    void anUndeliverableClaimBecomesALotAndLeavesTheLedger() {
        CrimeWorldData data = new CrimeWorldData();
        UUID transaction = file(data, 9L, 0L);

        List<StolenGoodsRecord> claimed = StolenGoodsLedger.claimAll(data, THIEF, record -> false, 100L);

        assertTrue(claimed.isEmpty(), "nothing was delivered, so nothing may be reported as returned");
        assertNull(data.stolenGoods(transaction), "the thief is still shown as holding delivered goods");
        List<PropertyLot> owed = data.propertyEscrowFor(OWNER);
        assertEquals(1, owed.size(), "the property vanished instead of being held for its owner");
        assertEquals(9L, owed.get(0).currency());
        assertEquals(transaction, owed.get(0).sourceRecordId(), "a lot has to name the theft it came from");
    }

    @Test
    void aDeliveredClaimLeavesNoLotBehind() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, 9L, 0L);

        assertEquals(1, StolenGoodsLedger.claimAll(data, THIEF, record -> true, 100L).size());
        assertTrue(data.propertyEscrow().isEmpty(), "delivered property was escrowed as well as given");
    }

    @Test
    void aSecondUndeliverableClaimCannotMintASecondLot() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, 9L, 0L);

        StolenGoodsLedger.claimAll(data, THIEF, record -> false, 100L);
        StolenGoodsLedger.claimAll(data, THIEF, record -> false, 100L);

        assertEquals(1, data.propertyEscrow().size(), "the claim was replayed into a duplicate lot");
    }

    @Test
    void expiryLaundersTheTrailWithoutDeletingTheProperty() {
        CrimeWorldData data = new CrimeWorldData();
        file(data, 12L, 0L);

        assertEquals(1, StolenGoodsLedger.expire(data, 10L, 7, Set.of()));

        assertTrue(data.stolenGoods().isEmpty(), "the expired row should leave the investigation ledger");
        assertEquals(1, data.propertyEscrowFor(OWNER).size(),
                "expiry deleted somebody's property instead of holding it");
    }

    // ------------------------------------------------------------------ delivery on login

    @Test
    void deliveryWithRoomEmptiesTheLot() {
        CrimeWorldData data = new CrimeWorldData();
        data.putPropertyLot(PropertyLot.ofCurrency(UUID.randomUUID(), OWNER, 20L, "mcacrime:test", null, 0L));

        int closed = PropertyEscrow.deliverPending(data, OWNER, lot -> lot.remaining(null, 0L));

        assertEquals(1, closed);
        assertTrue(data.propertyEscrowFor(OWNER).isEmpty());
    }

    @Test
    void deliveryWithoutRoomKeepsTheRemainder() {
        CrimeWorldData data = new CrimeWorldData();
        UUID lotId = UUID.randomUUID();
        data.putPropertyLot(PropertyLot.ofCurrency(lotId, OWNER, 20L, "mcacrime:test", null, 0L));

        // Half of it fits, which is what an inventory with one free slot does to a double stack.
        int closed = PropertyEscrow.deliverPending(data, OWNER, lot -> lot.remaining(null, 8L));

        assertEquals(0, closed, "a partial handover closed the lot");
        List<PropertyLot> owed = data.propertyEscrowFor(OWNER);
        assertEquals(1, owed.size());
        assertEquals(lotId, owed.get(0).lotId(), "the remainder must stay under the same lot");
        assertEquals(8L, owed.get(0).currency(), "the part that did not fit was lost");
        assertEquals(PropertyLot.DeliveryState.PARTIAL, owed.get(0).state());
    }

    @Test
    void aRefusedHandoverChangesNothing() {
        CrimeWorldData data = new CrimeWorldData();
        UUID lotId = UUID.randomUUID();
        data.putPropertyLot(PropertyLot.ofCurrency(lotId, OWNER, 20L, "mcacrime:test", null, 0L));

        assertEquals(0, PropertyEscrow.deliverPending(data, OWNER, lot -> lot));
        assertEquals(20L, data.propertyEscrowFor(OWNER).get(0).currency());
    }

    @Test
    void aLotRoundTripsThroughNbtByName() {
        PropertyLot lot = new PropertyLot(UUID.randomUUID(), OWNER, null, 42L, "mcacrime:emerald",
                UUID.randomUUID(), PropertyLot.DeliveryState.PARTIAL, 640L);
        CompoundTag tag = lot.save(RegistryAccess.EMPTY);

        assertEquals("PARTIAL", tag.getString("state"), "a delivery state stored as an ordinal would be "
                + "re-labelled by anybody who inserted a value into the enum");
        assertEquals(lot, PropertyLot.load(RegistryAccess.EMPTY, tag));
    }

    @Test
    void escrowIsNotDeliveredToTheWrongOwner() {
        CrimeWorldData data = new CrimeWorldData();
        data.putPropertyLot(PropertyLot.ofCurrency(UUID.randomUUID(), OWNER, 20L, "", null, 0L));

        assertEquals(0, PropertyEscrow.deliverPending(data, THIEF, lot -> lot.remaining(null, 0L)));
        assertFalse(data.propertyEscrowFor(OWNER).isEmpty());
    }
}
