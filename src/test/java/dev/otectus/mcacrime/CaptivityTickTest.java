package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Captivity cap accounting (spec §7.2/§8.4): advanceTick accumulates held time and fires the cap backstop. */
class CaptivityTickTest {

    private static CustodyRecord kidnap() {
        return new CustodyRecord(UUID.randomUUID(), true, false, CustodyOwner.kidnapper(UUID.randomUUID()),
                RestraintType.CUFFS, 0L, null, null);
    }

    @Test
    void eachTickAccumulatesExactlyOne() {
        CustodyRecord r = kidnap();
        assertNull(CustodyService.advanceTick(r, 1_000_000L));
        assertNull(CustodyService.advanceTick(r, 1_000_000L));
        assertEquals(2L, r.getRealTicksHeld());
    }

    @Test
    void captivityCapForcesReleaseBeforeAnUnboundedHold() {
        CustodyRecord r = kidnap();
        assertNull(CustodyService.advanceTick(r, 3L)); // held 1
        assertNull(CustodyService.advanceTick(r, 3L)); // held 2
        assertEquals(CustodyReleaseReason.CAPTIVITY_CAP, CustodyService.advanceTick(r, 3L)); // held 3 >= cap
    }

    @Test
    void zeroCapNeverFires() {
        CustodyRecord r = kidnap();
        r.setRealTicksHeld(1_000_000L);
        assertNull(CustodyService.advanceTick(r, 0L)); // cap disabled
    }
}
