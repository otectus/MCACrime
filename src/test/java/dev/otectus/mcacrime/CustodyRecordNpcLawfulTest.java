package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An arrested villager is a {@link CustodyRecord} with {@code captiveIsPlayer=false, lawful=true} —
 * the one combination the table could always express and nothing ever wrote (0.5.1).
 *
 * <p>The transfer is the part worth pinning down. At the end of an escort custody passes from the
 * arresting guard to the jail, and it does so by rewriting the owner in place rather than by a
 * release and a re-capture. The restraint and the remaining sentence must survive that untouched: a
 * thief that arrives at the cell with its cuffs off, or with its clock reset, is a bug that only
 * shows up ten minutes into a playthrough.
 */
class CustodyRecordNpcLawfulTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void lawfulNpcRecordRoundTripsThroughNbt() {
        UUID thief = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        CustodyRecord record = new CustodyRecord(thief, false, true, CustodyOwner.guard(guard),
                RestraintType.CUFFS, 0L, new BlockPos(10, 64, -20), OVERWORLD);
        record.setRemainingJailTicks(12_000L);

        CompoundTag tag = record.save();
        CustodyRecord loaded = CustodyRecord.load(tag);

        assertEquals(thief, loaded.getCaptive());
        assertFalse(loaded.isCaptivePlayer());
        assertTrue(loaded.isLawful());
        assertEquals(CustodyOwnerType.GUARD, loaded.getOwner().type());
        assertEquals(guard, loaded.getOwner().ownerUuid().orElseThrow());
        assertEquals(RestraintType.CUFFS, loaded.getRestraint());
        assertEquals(12_000L, loaded.getRemainingJailTicks());
        assertEquals(new BlockPos(10, 64, -20), loaded.getHoldPos());
        assertEquals(OVERWORLD, loaded.getHoldDim());
    }

    @Test
    void guardToJailTransferPreservesRestraintAndSentence() {
        CustodyRecord record = new CustodyRecord(UUID.randomUUID(), false, true,
                CustodyOwner.guard(UUID.randomUUID()), RestraintType.CUFFS, 0L, BlockPos.ZERO, OVERWORLD);
        record.setRemainingJailTicks(9_000L);

        // What CustodyService.transferLawfulCustody does: the owner, and only the owner.
        record.setOwner(CustodyOwner.jail(7, new BlockPos(4, 65, 4), OVERWORLD));

        assertEquals(CustodyOwnerType.JAIL, record.getOwner().type());
        assertEquals(7, record.getOwner().villageId().orElseThrow());
        assertEquals(RestraintType.CUFFS, record.getRestraint());
        assertEquals(9_000L, record.getRemainingJailTicks());
        assertTrue(record.isLawful());
        assertFalse(record.isCaptivePlayer());
    }

    @Test
    void aLawfulNpcRecordIsNotAKidnapping() {
        UUID guard = UUID.randomUUID();
        CustodyRecord record = new CustodyRecord(UUID.randomUUID(), false, true, CustodyOwner.guard(guard),
                RestraintType.CUFFS, 0L, BlockPos.ZERO, OVERWORLD);
        // The one predicate that drives Legal Target and theft-of-captive must not fire for an arrest.
        assertFalse(record.getOwner().isKidnapper(guard));
    }
}
