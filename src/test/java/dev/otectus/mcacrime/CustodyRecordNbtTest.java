package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyOwnerType;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custody persistence (spec §2.3): record/owner NBT round-trip, deep copy, absent-key safety, enum fail-safes. */
class CustodyRecordNbtTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void kidnapRecordRoundTripsAllFields() {
        UUID captive = UUID.randomUUID();
        UUID captor = UUID.randomUUID();
        CustodyRecord r = new CustodyRecord(captive, true, false, CustodyOwner.kidnapper(captor),
                RestraintType.CUFFS, 1234L, new BlockPos(1, 2, 3), OVERWORLD);
        r.setRealTicksHeld(99L);
        r.setVirtual(true);

        CustodyRecord loaded = CustodyRecord.load(r.save());
        assertEquals(captive, loaded.getCaptive());
        assertTrue(loaded.isCaptivePlayer());
        assertFalse(loaded.isLawful());
        assertEquals(CustodyOwnerType.KIDNAPPER, loaded.getOwner().type());
        assertEquals(captor, loaded.getOwner().ownerUuid().orElseThrow());
        assertEquals(RestraintType.CUFFS, loaded.getRestraint());
        assertEquals(1234L, loaded.getStartTickOnline());
        assertEquals(99L, loaded.getRealTicksHeld());
        assertEquals(new BlockPos(1, 2, 3), loaded.getHoldPos());
        assertEquals(OVERWORLD, loaded.getHoldDim());
        assertTrue(loaded.isVirtual());
        assertTrue(loaded.hasValidHold());
    }

    @Test
    void lawfulJailOwnerRoundTrips() {
        UUID captive = UUID.randomUUID();
        CustodyRecord r = new CustodyRecord(captive, true, true,
                CustodyOwner.jail(42, new BlockPos(0, 64, 0), OVERWORLD),
                RestraintType.NONE, 0L, new BlockPos(0, 64, 0), OVERWORLD);
        r.setRemainingJailTicks(500L);

        CustodyRecord loaded = CustodyRecord.load(r.save());
        assertTrue(loaded.isLawful());
        assertEquals(CustodyOwnerType.JAIL, loaded.getOwner().type());
        assertEquals(42, loaded.getOwner().villageId().orElseThrow());
        assertEquals(500L, loaded.getRemainingJailTicks());
    }

    @Test
    void copyIsDeep() {
        CustodyRecord r = new CustodyRecord(UUID.randomUUID(), false, false,
                CustodyOwner.kidnapper(UUID.randomUUID()), RestraintType.ROPE, 0L, null, null);
        r.setRealTicksHeld(10L);
        CustodyRecord c = r.copy();
        r.setRealTicksHeld(20L);
        r.setVirtual(true);
        assertEquals(10L, c.getRealTicksHeld()); // unaffected by mutating the original
        assertFalse(c.isVirtual());
    }

    @Test
    void absentKeysLoadSafeDefaults() {
        CustodyRecord r = CustodyRecord.load(new CompoundTag());
        assertNull(r.getCaptive());
        assertFalse(r.isLawful());
        assertEquals(CustodyOwnerType.NONE, r.getOwner().type());
        assertEquals(RestraintType.NONE, r.getRestraint());
        assertEquals(0L, r.getRealTicksHeld());
        assertNull(r.getHoldPos());
        assertFalse(r.hasValidHold());
    }

    @Test
    void enumsParseFailSafe() {
        assertEquals(RestraintType.NONE, RestraintType.parse("garbage"));
        assertEquals(RestraintType.CUFFS, RestraintType.parse("CUFFS"));
        assertEquals(RestraintType.NONE, RestraintType.byOrdinal(999));
        assertEquals(RestraintType.ROPE, RestraintType.byOrdinal(RestraintType.ROPE.ordinal()));
        assertEquals(CustodyOwnerType.NONE, CustodyOwnerType.parse("garbage"));
        assertEquals(CustodyReleaseReason.ADMIN, CustodyReleaseReason.parse("garbage"));
    }

    @Test
    void ownerFactoriesAndKidnapperCheck() {
        UUID a = UUID.randomUUID();
        assertTrue(CustodyOwner.kidnapper(a).isKidnapper(a));
        assertFalse(CustodyOwner.guard(a).isKidnapper(a));
        assertFalse(CustodyOwner.kidnapper(a).isKidnapper(UUID.randomUUID()));
        assertEquals(CustodyOwnerType.NONE, CustodyOwner.none().type());
        assertEquals(7, CustodyOwner.authority(7).villageId().orElseThrow());
    }
}
