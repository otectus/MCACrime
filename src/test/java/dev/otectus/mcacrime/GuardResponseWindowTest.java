package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.GuardChallenge;
import dev.otectus.mcacrime.network.GuardChallengeDisplayedC2SPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GuardResponseWindowTest {
    private GuardChallenge pending(long configured) {
        return new GuardChallenge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                1, 20, true, 1000, 1000 + configured).awaitDisplay();
    }

    @Test void oldTenSecondConfigurationStillAllowsFifteenFullSeconds() {
        var challenge = pending(200).displayed(1025);
        assertEquals(300, challenge.remaining(1025));
        assertFalse(challenge.expired(1324));
        assertTrue(challenge.expired(1325));
    }

    @Test void deliveryDoesNotConsumeTheDisplayedResponseWindow() {
        var challenge = pending(300);
        assertEquals(300, challenge.remaining(1000));
        assertEquals(300, challenge.remaining(1075));
        assertEquals(300, challenge.displayed(1075).remaining(1075));
    }

    @Test void reopeningAndReplayedAcknowledgmentsCannotExtendTheDeadline() {
        var started = pending(300).displayed(1025);
        assertSame(started, started.displayed(1200));
        assertSame(started, started.displayed(1324));
        assertEquals(1, started.displayed(1324).remaining(1324));
    }

    @Test void withholdingOrDelayingTheAckHasABoundedGrace() {
        var challenge = pending(300);
        assertTrue(challenge.expired(1400));
        assertEquals(1400, challenge.displayed(1300).expiresAt());
    }

    @Test void longerServerConfiguredWindowsRemainAvailable() {
        assertEquals(600, pending(600).displayed(1020).remaining(1020));
    }

    @Test void acknowledgmentRoundTripsOnlyTheEncounterIdentity() {
        var packet = new GuardChallengeDisplayedC2SPacket(UUID.randomUUID());
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            GuardChallengeDisplayedC2SPacket.encode(packet, buf);
            assertEquals(16, buf.readableBytes());
            assertEquals(packet, GuardChallengeDisplayedC2SPacket.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
}
