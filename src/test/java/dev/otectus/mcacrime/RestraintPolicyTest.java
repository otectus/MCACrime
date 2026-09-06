package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.enforcement.RestraintPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two routes into chains resolve to one answer (T10).
 *
 * <p>Only the arrest phase used to count, so a kidnapping victim and a bounty hunter's prisoner both
 * held a custody record naming the restraint on them and were nonetheless free to mine, fight and
 * sprint. The pure overload is what the three call sites — the speed modifier, the suppressed
 * interactions and the rendered pose — all end up asking, so it is what is asserted here; the
 * {@code ServerPlayer} overload is the same decision with the two sources read off a live player.
 */
class RestraintPolicyTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static CustodyRecord record(boolean lawful, CustodyOwner owner, RestraintType restraint) {
        return new CustodyRecord(UUID.randomUUID(), true, lawful, owner, restraint, 0L,
                new BlockPos(0, 64, 0), OVERWORLD);
    }

    @Test
    void aKidnapRecordRestrains() {
        CustodyRecord kidnapped = record(false, CustodyOwner.kidnapper(UUID.randomUUID()), RestraintType.ROPE);
        assertEquals(Optional.of(RestraintType.ROPE),
                RestraintPolicy.effective(false, null, kidnapped));
    }

    @Test
    void aHunterHeldRecordRestrainsJustAsMuch() {
        CustodyRecord held = record(true, CustodyOwner.bountyHunter(UUID.randomUUID()), RestraintType.CUFFS);
        assertEquals(Optional.of(RestraintType.CUFFS),
                RestraintPolicy.effective(false, null, held));
    }

    @Test
    void noRecordAndNoArrestIsFree() {
        assertTrue(RestraintPolicy.effective(false, null, null).isEmpty());
        assertTrue(RestraintPolicy.effective(null).isEmpty()); // no player, nothing to read
    }

    @Test
    void aRecordCarryingNoRestraintIsNotItselfARestraint() {
        CustodyRecord none = record(true, CustodyOwner.guard(UUID.randomUUID()), RestraintType.NONE);
        assertTrue(RestraintPolicy.effective(false, null, none).isEmpty());
    }

    @Test
    void theArrestPhaseWinsAndDefaultsToCuffs() {
        // The stricter state: a player being escorted to a cell is restrained whatever the table says,
        // and an arrest that never wrote a restraint kind still applied cuffs.
        assertEquals(Optional.of(RestraintType.CUFFS), RestraintPolicy.effective(true, null, null));
        assertEquals(Optional.of(RestraintType.CUFFS),
                RestraintPolicy.effective(true, RestraintType.NONE, null));
        CustodyRecord roped = record(false, CustodyOwner.kidnapper(UUID.randomUUID()), RestraintType.ROPE);
        assertEquals(Optional.of(RestraintType.ROPE), RestraintPolicy.effective(true, RestraintType.ROPE, roped));
    }
}
