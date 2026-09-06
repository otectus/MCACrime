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
 * Who is actually restrained (T10).
 *
 * <p>The bug this pins down is a player in a kidnapper's rope, or an outlaw a bounty hunter had taken
 * alive, being free to mine, swing and sprint because the only thing anybody asked was the lawful
 * arrest phase. The custody record said exactly which restraint was on them and nothing read it.
 */
class RestraintPolicyTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final BlockPos HOLD = new BlockPos(0, 64, 0);

    private static CustodyRecord record(boolean lawful, CustodyOwner owner, RestraintType restraint) {
        return new CustodyRecord(UUID.randomUUID(), true, lawful, owner, restraint, 0L, HOLD, OVERWORLD);
    }

    @Test
    void aKidnappedPlayerIsRestrainedByTheRopeThatHoldsThem() {
        Optional<RestraintType> effective = RestraintPolicy.effective(false, null,
                record(false, CustodyOwner.kidnapper(UUID.randomUUID()), RestraintType.ROPE));
        assertEquals(Optional.of(RestraintType.ROPE), effective);
    }

    @Test
    void aBountyHuntersLawfulHoldRestrainsJustAsMuch() {
        Optional<RestraintType> effective = RestraintPolicy.effective(false, null,
                record(true, CustodyOwner.bountyHunter(UUID.randomUUID()), RestraintType.CUFFS));
        assertEquals(Optional.of(RestraintType.CUFFS), effective);
    }

    @Test
    void noRecordAndNoArrestIsFree() {
        assertTrue(RestraintPolicy.effective(false, null, null).isEmpty());
    }

    @Test
    void aRecordCarryingNoRestraintDoesNotRestrain() {
        // Lawful jail custody is written with NONE: the cell holds them, not a rope, and the sentence
        // is JailService's clock rather than a set of cuffs nobody put on.
        assertTrue(RestraintPolicy.effective(false, null,
                record(true, CustodyOwner.jail(1, HOLD, OVERWORLD), RestraintType.NONE)).isEmpty());
    }

    @Test
    void anArrestRestrainsEvenWithNoRecordToNameTheKind() {
        assertEquals(Optional.of(RestraintType.CUFFS), RestraintPolicy.effective(true, null, null));
        assertEquals(Optional.of(RestraintType.CUFFS),
                RestraintPolicy.effective(true, RestraintType.NONE, null));
    }

    @Test
    void theArrestWinsOverWhateverTheRecordSays() {
        Optional<RestraintType> effective = RestraintPolicy.effective(true, RestraintType.LOCKED_CUFFS,
                record(false, CustodyOwner.kidnapper(UUID.randomUUID()), RestraintType.ROPE));
        assertEquals(Optional.of(RestraintType.LOCKED_CUFFS), effective);
    }
}
