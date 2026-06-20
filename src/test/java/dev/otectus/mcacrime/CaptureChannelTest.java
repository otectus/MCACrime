package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CaptureChannel;
import dev.otectus.mcacrime.captivity.RestraintType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capture channel (spec §8.2): completion timing and the pure break-geometry predicates. */
class CaptureChannelTest {

    private static CaptureChannel channel(int requiredTicks) {
        return new CaptureChannel(UUID.randomUUID(), UUID.randomUUID(), true, RestraintType.CUFFS,
                Vec3.ZERO, requiredTicks);
    }

    @Test
    void completesAfterRequiredTicks() {
        CaptureChannel c = channel(3);
        c.tick();
        c.tick();
        assertFalse(c.isComplete());
        c.tick();
        assertTrue(c.isComplete());
    }

    @Test
    void brokeByMoveOnlyBeyondLimit() {
        assertFalse(CaptureChannel.brokeByMove(Vec3.ZERO, new Vec3(1, 0, 0), 1.5));
        assertTrue(CaptureChannel.brokeByMove(Vec3.ZERO, new Vec3(2, 0, 0), 1.5));
    }

    @Test
    void outOfRangeOnlyBeyondLimit() {
        assertFalse(CaptureChannel.outOfRange(Vec3.ZERO, new Vec3(3, 0, 0), 4.0));
        assertTrue(CaptureChannel.outOfRange(Vec3.ZERO, new Vec3(5, 0, 0), 4.0));
    }

    @Test
    void markBrokenFlagsTheChannel() {
        CaptureChannel c = channel(10);
        assertFalse(c.isBroken());
        c.markBroken();
        assertTrue(c.isBroken());
    }
}
