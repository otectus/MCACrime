package dev.otectus.mcacrime;

import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import dev.otectus.mcacrime.enforcement.RestraintVisualType;
import dev.otectus.mcacrime.network.RestraintBulkSyncS2CPacket;
import dev.otectus.mcacrime.network.RestraintSyncS2CPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Both restraint packets, over a real buffer.
 *
 * <p>Worth asserting because the field order is the only thing holding the wire format together and
 * the enum was added between two fields that are both already on the wire. A reader and a writer that
 * disagree by one byte do not throw: they produce a plausible wrong answer — the guard entity id
 * shifts, and the rope is drawn to whatever entity happens to hold that id. Hence the protocol bump
 * to 7, and hence this.
 */
class RestraintSyncPacketTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void singleSyncRoundTrips() {
        for (RestraintVisualType type : RestraintVisualType.values()) {
            RestraintSyncS2CPacket sent = new RestraintSyncS2CPacket(SUBJECT, true, type, 4242);
            assertEquals(sent, roundTrip(sent));
        }
    }

    @Test
    void anUnrestrainedSubjectRoundTripsToo() {
        RestraintSyncS2CPacket sent =
                new RestraintSyncS2CPacket(SUBJECT, false, RestraintVisualType.NONE, -1);
        assertEquals(sent, roundTrip(sent));
    }

    /** {@code -1} is the "nobody is holding them" sentinel, and a VarInt must carry it intact. */
    @Test
    void theNoEscortSentinelSurvives() {
        RestraintSyncS2CPacket sent =
                new RestraintSyncS2CPacket(SUBJECT, true, RestraintVisualType.ROPE, -1);
        assertEquals(-1, roundTrip(sent).guardEntityId());
    }

    @Test
    void theFactoryMatchesTheStateItWasBuiltFrom() {
        RestraintVisualState state = new RestraintVisualState(true, RestraintVisualType.ROPE, 7);
        RestraintSyncS2CPacket packet = RestraintSyncS2CPacket.of(SUBJECT, state);
        assertEquals(new RestraintSyncS2CPacket(SUBJECT, true, RestraintVisualType.ROPE, 7), packet);
    }

    @Test
    void theBulkSnapshotRoundTrips() {
        Map<UUID, RestraintVisualState> snapshot = new LinkedHashMap<>();
        snapshot.put(SUBJECT, new RestraintVisualState(true, RestraintVisualType.HANDCUFFS, 12));
        snapshot.put(UUID.fromString("66666666-7777-8888-9999-000000000000"),
                new RestraintVisualState(true, RestraintVisualType.ROPE, -1));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RestraintBulkSyncS2CPacket.encode(new RestraintBulkSyncS2CPacket(snapshot), buf);
        Map<UUID, RestraintVisualState> back = RestraintBulkSyncS2CPacket.decode(buf).restrained();

        assertEquals(snapshot, back);
        assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what the encoder wrote");
    }

    @Test
    void anEmptySnapshotIsLegal() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RestraintBulkSyncS2CPacket.encode(new RestraintBulkSyncS2CPacket(Map.of()), buf);
        assertEquals(Map.of(), RestraintBulkSyncS2CPacket.decode(buf).restrained());
    }

    private static RestraintSyncS2CPacket roundTrip(RestraintSyncS2CPacket sent) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RestraintSyncS2CPacket.encode(sent, buf);
        RestraintSyncS2CPacket back = RestraintSyncS2CPacket.decode(buf);
        assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what the encoder wrote");
        return back;
    }
}
