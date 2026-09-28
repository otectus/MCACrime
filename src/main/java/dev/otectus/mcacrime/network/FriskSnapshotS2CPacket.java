package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.frisk.SeizureKind;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * What the searcher's screen needs and is allowed to know (M5.2, §1.6).
 *
 * <p>The items themselves arrive through the ordinary menu sync, because the slots are real slots of
 * a real container. What this adds is the session id the transfer packet has to quote and the
 * per-slot revision it has to echo, plus which of the two legal events the search is, so the
 * screen can say "seized into evidence" rather than implying a theft.
 *
 * <p>Sent only to the authorised searcher. Nothing here is another player's private state beyond
 * what that searcher is already looking at, and no lock secret or case evidence rides along.
 *
 * <p>The kind travels through {@link CrimeStreamCodecs#enumCodec}, which refuses an ordinal no build
 * ever wrote rather than falling back to a voluntary transfer — the baseline's fallback would have a
 * seizure describe itself as a gift.
 */
public record FriskSnapshotS2CPacket(long sessionId, SeizureKind kind, List<Integer> revisions)
        implements CustomPacketPayload {

    public static final Type<FriskSnapshotS2CPacket> TYPE = new Type<>(McaCrime.id("frisk_snapshot"));

    /** Bounded at the same slot ceiling the projection is: a list length is an allocation. */
    private static final StreamCodec<ByteBuf, List<Integer>> REVISIONS_CODEC =
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.INT, PacketBounds.MAX_FRISK_SLOTS);

    public static final StreamCodec<RegistryFriendlyByteBuf, FriskSnapshotS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.LONG, FriskSnapshotS2CPacket::sessionId,
                    CrimeStreamCodecs.enumCodec(SeizureKind.class, "seizure kind"),
                    FriskSnapshotS2CPacket::kind,
                    REVISIONS_CODEC, FriskSnapshotS2CPacket::revisions,
                    FriskSnapshotS2CPacket::new);

    public FriskSnapshotS2CPacket {
        kind = kind == null ? SeizureKind.CRIMINAL_SEIZURE : kind;
        revisions = revisions == null ? List.of()
                : List.copyOf(revisions.subList(0, Math.min(revisions.size(), PacketBounds.MAX_FRISK_SLOTS)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
