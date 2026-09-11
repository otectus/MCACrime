package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.NpcArrestService;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Arresting a thief is exactly what it was before accomplices existed.
 *
 * <p>The accomplice sentence was added to the same arrest path the thief already used, which is the
 * cheap way to reuse custody, escort and the cell — and the risky way to change a shipped behaviour by
 * accident. The branch is on a standing warrant for aiding, so a villager with no accomplice record
 * cannot reach it; a villager with a spent one cannot either.
 */
class ThiefArrestUnchangedTest {

    private static final long THIEF_TICKS = 6000L;
    private static final long ACCOMPLICE_TICKS = 1200L;

    private static AccompliceRecord record(boolean wanted) {
        return new AccompliceRecord(UUID.randomUUID(), UUID.randomUUID(), "lookout", "", 0L, 100L,
                wanted, 1, 0L, 0);
    }

    @Test
    void aPlainThiefServesTheThiefTerm() {
        assertEquals(THIEF_TICKS, NpcArrestService.npcSentenceTicks(null, THIEF_TICKS, ACCOMPLICE_TICKS));
    }

    @Test
    void aVillagerWhoHelpedButIsNotWantedForItStillServesTheThiefTerm() {
        assertEquals(THIEF_TICKS,
                NpcArrestService.npcSentenceTicks(record(false), THIEF_TICKS, ACCOMPLICE_TICKS));
    }

    @Test
    void onlyAStandingWarrantForAidingProducesTheAccompliceTerm() {
        assertEquals(ACCOMPLICE_TICKS,
                NpcArrestService.npcSentenceTicks(record(true), THIEF_TICKS, ACCOMPLICE_TICKS));
    }
}
