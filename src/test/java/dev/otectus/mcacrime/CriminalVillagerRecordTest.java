package dev.otectus.mcacrime;

import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.state.world.CriminalVillagerRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The persisted half of a criminal job.
 *
 * <p>The null {@code previousProfessionId} is the case worth its own assertion: it is the difference
 * between a fence whose old profession can be given back and one that would be reverted to a
 * profession it never had, and it is stored by omission rather than as a value.
 */
class CriminalVillagerRecordTest {

    @Test
    void aFullRecordRoundTrips() {
        UUID villager = UUID.randomUUID();
        CriminalVillagerRecord record = new CriminalVillagerRecord(villager, CriminalJob.FENCE,
                12L, 480L, 19L, false, 987654321L, "minecraft:cleric");
        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(record.save());
        assertEquals(record, loaded);
    }

    @Test
    void anAbsentPreviousProfessionStaysAbsent() {
        CriminalVillagerRecord record = new CriminalVillagerRecord(UUID.randomUUID(), CriminalJob.THIEF,
                3L, 0L, 3L, true, -42L, null);
        CompoundTag tag = record.save();
        CriminalVillagerRecord loaded = CriminalVillagerRecord.load(tag);
        assertNull(loaded.previousProfessionId(), "nothing was taken away, so nothing is given back");
        assertEquals(record, loaded);
    }

    @Test
    void anUnknownJobReadsAsNone() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("villager", UUID.randomUUID());
        tag.putString("job", "smuggler");
        assertEquals(CriminalJob.NONE, CriminalVillagerRecord.load(tag).job(),
                "a job written by a later version must not throw on an older one");
    }
}
