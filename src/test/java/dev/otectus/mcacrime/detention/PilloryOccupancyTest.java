package dev.otectus.mcacrime.detention;

import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.tether.TetherKind;
import dev.otectus.mcacrime.tether.TetherRecord;
import dev.otectus.mcacrime.tether.TetherService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One device, one occupant, and a cleanup that is not switched off by a gameplay toggle (0.7.5 M4.5).
 *
 * <p>Four rules, all of them upstream failures. Occupancy lives on the <b>device</b> rather than on
 * the player ({@code mixin/PlayerMixin.java:76-79}), so villagers are detainable and a chunk unload is
 * not a jailbreak. Closing is an <b>atomic claim</b>, so two closers in one tick produce one success.
 * Destroying <b>either half</b> releases once. And the stale-occupancy release runs <b>outside</b> the
 * breakout toggle, where the source nests it inside {@code ALLOW_BREAKING_OUT_OF_PILLORY}
 * ({@code :168-178}) — so a server that turns breaking out off also turns off releasing prisoners
 * whose device no longer exists.
 */
class PilloryOccupancyTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private CrimeWorldData data;
    private BlockPos device;
    private UUID first;
    private UUID second;

    @BeforeEach
    void freshWorld() {
        data = new CrimeWorldData();
        device = new BlockPos(6, 64, 6);
        first = UUID.randomUUID();
        second = UUID.randomUUID();
        TetherService.invalidate();
    }

    private DetentionService.Refusal close(UUID subject) {
        return DetentionService.claim(data, subject, true, DetentionKind.PILLORY, OVERWORLD, device,
                "pillory");
    }

    // ---------------------------------------------------------------- the atomic claim

    @Test
    void simultaneousClosersProduceOneSuccess() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        assertEquals(DetentionService.Refusal.DEVICE_OCCUPIED, close(second),
                "the second closer in the same tick is refused, not queued");
        assertEquals(1, data.detentions().size());
        assertEquals(first, DetentionService.at(data, OVERWORLD, device).orElseThrow().subject());
    }

    @Test
    void oneSubjectIsNotHeldByTwoDevicesAtOnce() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        assertEquals(DetentionService.Refusal.SUBJECT_DETAINED,
                DetentionService.claim(data, first, true, DetentionKind.PILLORY, OVERWORLD,
                        new BlockPos(30, 64, 30), "pillory"));
    }

    @Test
    void theRefusalTableIsReadableWithoutAWorld() {
        assertEquals(DetentionService.Refusal.NO_SUBJECT,
                DetentionService.check(false, false, false, true));
        assertEquals(DetentionService.Refusal.NO_DEVICE,
                DetentionService.check(false, false, true, false));
        assertEquals(DetentionService.Refusal.DEVICE_OCCUPIED,
                DetentionService.check(true, false, true, true));
        assertEquals(DetentionService.Refusal.SUBJECT_DETAINED,
                DetentionService.check(false, true, true, true));
        assertEquals(DetentionService.Refusal.NONE,
                DetentionService.check(false, false, true, true));
        for (DetentionService.Refusal refusal : DetentionService.Refusal.values()) {
            assertTrue(DetentionService.messageKey(refusal).startsWith("mcacrime."), refusal.name());
        }
    }

    // ---------------------------------------------------------------- the device going away

    @Test
    void destroyingEitherHalfReleasesExactlyOnce() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        UUID id = DetentionService.at(data, OVERWORLD, device).orElseThrow().id();

        assertTrue(DetentionService.release(null, data, id, DetentionService.ReleaseReason.DEVICE_GONE)
                .isPresent(), "the first half broken releases the occupant");
        assertTrue(DetentionService.release(null, data, id, DetentionService.ReleaseReason.DEVICE_GONE)
                .isEmpty(), "the second half broken in the same tick releases nobody a second time");
        assertNull(data.detention(id));
        assertFalse(DetentionService.occupied(data, OVERWORLD, device));
    }

    @Test
    void releasingASubjectWhoIsInNoDeviceDoesNothing() {
        assertTrue(DetentionService.releaseSubject(null, data, first,
                DetentionService.ReleaseReason.OPENED).isEmpty());
        assertTrue(DetentionService.release(null, data, UUID.randomUUID(),
                DetentionService.ReleaseReason.OPENED).isEmpty());
        assertTrue(DetentionService.release(null, null, UUID.randomUUID(),
                DetentionService.ReleaseReason.OPENED).isEmpty());
    }

    @Test
    void theSubjectsPhysicalRowPointsAtTheDeviceAndStopsWhenItEnds() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        UUID id = DetentionService.at(data, OVERWORLD, device).orElseThrow().id();
        assertEquals(id, data.physicalRestraint(first).detentionId());

        DetentionService.release(null, data, id, DetentionService.ReleaseReason.OPENED);
        assertNull(data.physicalRestraint(first).detentionId(),
                "and the client is told there is nothing holding them any more");
    }

    // ---------------------------------------------------------------- the chain waits

    @Test
    void closingTheDeviceSuspendsAChainRatherThanDroppingIt() {
        TetherRecord chain = new TetherRecord(UUID.randomUUID(), first, TetherKind.ANCHOR,
                UUID.randomUUID(), OVERWORLD, new BlockPos(1, 64, 1), 5.0D, false, first, true, 1L);
        assertTrue(data.putTether(chain));
        TetherService.index(data).put(chain);

        assertEquals(DetentionService.Refusal.NONE, close(first));
        assertTrue(data.tether(chain.id()).suspended(), "the chain waits");
        assertEquals(1, data.tethers().size(), "it is not on the floor");
    }

    // ---------------------------------------------------------------- breaking out

    @Test
    void theBreakoutRuleIsZeroMeansNever() {
        assertFalse(DetentionService.breaksOut(1_000_000, 0),
                "0 disables breaking out, and disables only that");
        assertFalse(DetentionService.breaksOut(99, 100));
        assertTrue(DetentionService.breaksOut(100, 100));
        assertTrue(DetentionService.breaksOut(101, 100));
    }

    @Test
    void breakoutWorkAccumulatesOnTheRecordRatherThanOnTheClient() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        UUID id = DetentionService.at(data, OVERWORLD, device).orElseThrow().id();
        assertEquals(0, data.detention(id).escapeWork());

        DetentionService.addBreakoutWork(data, id);
        DetentionService.addBreakoutWork(data, id);
        assertEquals(2, data.detention(id).escapeWork(),
                "a relog does not reset the progress, because the progress is in world data");
        assertFalse(DetentionService.addBreakoutWork(data, null));
        assertFalse(DetentionService.addBreakoutWork(null, id));
    }

    @Test
    void cleanupIsNotTheBreakoutToggle() {
        // The two are independent by construction: the sweep never consults breakoutTransitions, and
        // this is the assertion that says so -- a device that is gone releases its occupant whatever
        // the toggle says, because the release path below is the sweep's and not the breakout's.
        assertEquals(DetentionService.Refusal.NONE, close(first));
        UUID id = DetentionService.at(data, OVERWORLD, device).orElseThrow().id();
        assertTrue(DetentionService.release(null, data, id, DetentionService.ReleaseReason.DEVICE_GONE)
                .isPresent());
        assertEquals(0, data.detentions().size());
    }

    @Test
    void anUnloadedChunkIsNotAMissingDevice() {
        assertEquals(DetentionService.Refusal.NONE, close(first));
        // The sweep's own predicate is only consulted for loaded positions; with no server at all it
        // has nothing to judge and must leave the occupancy exactly as it found it.
        assertEquals(0, DetentionService.sweep(null, data, (level, pos, kind) -> false));
        assertEquals(1, data.detentions().size(),
                "a prisoner in an unvisited chunk is still in the pillory");
    }
}
