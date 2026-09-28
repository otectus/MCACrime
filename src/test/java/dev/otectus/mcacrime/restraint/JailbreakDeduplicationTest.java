package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One jailbreak per episode, and the deduplication is structural (0.7.5 M4.8).
 *
 * <p>A prisoner in cuffs, leg shackles and a hood who struggles out of all three reaches the bridge
 * three times. Only one of those calls finds a lawful custody record still there to remove, because
 * removing it <em>is</em> the filing gate — so the other two file nothing, with no marker to keep, no
 * flag to get stale and nothing to reconcile after a restart.
 */
class JailbreakDeduplicationTest {

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");

    private CrimeWorldData data;
    private UUID prisoner;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        prisoner = UUID.randomUUID();
    }

    private void holdLawfully() {
        CustodyRecord record = new CustodyRecord(prisoner, true, true,
                CustodyOwner.guard(UUID.randomUUID()), 0L, new BlockPos(0, 64, 0), OVERWORLD);
        data.putCustody(record);
        assertNotNull(data.getCustody(prisoner));
    }

    /** The gate as the bridge applies it: the filing follows a removal that this call performed. */
    private boolean releaseAndWouldFile() {
        boolean held = data.getCustody(prisoner) != null;
        data.removeCustody(prisoner);
        return held && data.getCustody(prisoner) == null;
    }

    @Test
    void threeRestraintsComingOffFileOneJailbreak() {
        holdLawfully();
        int filings = 0;
        for (int slot = 0; slot < 3; slot++) {
            if (releaseAndWouldFile()) {
                filings++;
            }
        }
        assertEquals(1, filings, "the second and third removals find no custody left to release");
        assertNull(data.getCustody(prisoner));
    }

    @Test
    void aSecondPathReachingTheBridgeInTheSameTickFilesNothing() {
        holdLawfully();
        assertTrue(releaseAndWouldFile());
        assertFalse(releaseAndWouldFile(),
                "a device breaking open and a restraint snapping together are still one escape");
    }

    @Test
    void aFreshCustodyIsANewEpisodeAndGetsItsOwnJailbreak() {
        holdLawfully();
        assertTrue(releaseAndWouldFile());
        holdLawfully(); // re-arrested
        assertTrue(releaseAndWouldFile(), "escaping twice is two jailbreaks, not one");
    }

    @Test
    void anUnlawfulCustodyNeverReachesTheJailbreakRowAtAll() {
        data.putCustody(new CustodyRecord(prisoner, true, false,
                CustodyOwner.kidnapper(UUID.randomUUID()), 0L, new BlockPos(0, 64, 0), OVERWORLD));
        assertEquals(CustodyTransitionService.Removal.ESCAPE_FROM_UNLAWFUL_CUSTODY,
                CustodyTransitionService.classifyRemoval(false, false, true, false),
                "escaping a kidnapper is not a crime, so there is nothing to deduplicate");
    }

    @Test
    void somebodyNobodyIsHoldingFilesNothingHoweverManyTimesTheyAreFreed() {
        for (int i = 0; i < 5; i++) {
            assertFalse(releaseAndWouldFile());
        }
    }
}
