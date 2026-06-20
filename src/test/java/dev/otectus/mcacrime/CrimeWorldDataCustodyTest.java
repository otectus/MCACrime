package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custody table in CrimeWorldData (spec §2.3): round-trip, idempotency, malformed-skip, forward-compat. */
class CrimeWorldDataCustodyTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    @Test
    void custodyRoundTripsAndIsIdempotent() {
        CrimeWorldData data = new CrimeWorldData();
        UUID captive = UUID.randomUUID();
        CustodyRecord r = new CustodyRecord(captive, true, false, CustodyOwner.kidnapper(UUID.randomUUID()),
                RestraintType.ROPE, 0L, new BlockPos(1, 2, 3), OVERWORLD);
        data.putCustody(r);
        data.putCustody(r.copy()); // same captive -> replaces, not duplicates
        assertEquals(1, data.custodyRecords().size());
        assertTrue(data.isCaptive(captive));

        CrimeWorldData loaded = CrimeWorldData.load(data.save(new CompoundTag()));
        assertEquals(1, loaded.custodyRecords().size());
        assertNotNull(loaded.getCustody(captive));
        assertEquals(RestraintType.ROPE, loaded.getCustody(captive).getRestraint());

        loaded.removeCustody(captive);
        assertFalse(loaded.isCaptive(captive));
    }

    @Test
    void malformedCustodyEntrySkipped() {
        CrimeWorldData data = new CrimeWorldData();
        UUID good = UUID.randomUUID();
        data.putCustody(new CustodyRecord(good, false, false, CustodyOwner.none(), RestraintType.NONE, 0L, null, null));

        CompoundTag saved = data.save(new CompoundTag());
        saved.getCompound("custody").putString("not-a-uuid", "x"); // inject a bad key

        CrimeWorldData loaded = CrimeWorldData.load(saved);
        assertEquals(1, loaded.custodyRecords().size()); // good kept, bad skipped
    }

    @Test
    void unknownCustodyShapePreservedVerbatim() {
        // A hypothetical newer jar wrote "custody" as a list, not a compound — must not be parsed or dropped.
        CompoundTag onDisk = new CompoundTag();
        ListTag newerShape = new ListTag();
        newerShape.add(StringTag.valueOf("future-data"));
        onDisk.put("custody", newerShape);

        CrimeWorldData loaded = CrimeWorldData.load(onDisk);
        assertTrue(loaded.custodyRecords().isEmpty()); // didn't try to parse the foreign shape

        CompoundTag resaved = loaded.save(new CompoundTag());
        assertEquals(newerShape, resaved.get("custody")); // preserved verbatim
    }

    @Test
    void bountiesSlotStillReserved() {
        CompoundTag onDisk = new CompoundTag();
        CompoundTag bounties = new CompoundTag();
        bounties.putString("future", "data");
        onDisk.put("bounties", bounties);

        CrimeWorldData loaded = CrimeWorldData.load(onDisk);
        CompoundTag resaved = loaded.save(new CompoundTag());
        assertEquals(bounties, resaved.getCompound("bounties"));
    }
}
