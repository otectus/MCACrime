package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.ClientFriskData;
import dev.otectus.mcacrime.frisk.SeizureKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

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
 */
public record FriskSnapshotS2CPacket(long sessionId, SeizureKind kind, List<Integer> revisions) {

    public FriskSnapshotS2CPacket {
        kind = kind == null ? SeizureKind.CRIMINAL_SEIZURE : kind;
        revisions = revisions == null ? List.of()
                : List.copyOf(revisions.subList(0, Math.min(revisions.size(), PacketBounds.MAX_FRISK_SLOTS)));
    }

    public static void encode(FriskSnapshotS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.sessionId);
        PacketBounds.writeEnumOrdinal(buf, msg.kind);
        buf.writeVarInt(msg.revisions.size());
        msg.revisions.forEach(buf::writeInt);
    }

    public static FriskSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        long sessionId = buf.readLong();
        SeizureKind kind = PacketBounds.readEnumOrdinal(buf, SeizureKind.class)
                .orElse(SeizureKind.CRIMINAL_SEIZURE);
        return new FriskSnapshotS2CPacket(sessionId, kind,
                PacketBounds.readBoundedList(buf, PacketBounds.MAX_FRISK_SLOTS,
                        FriendlyByteBuf::readInt));
    }

    public static void handle(FriskSnapshotS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientFriskData.accept(msg)));
        ctx.get().setPacketHandled(true);
    }
}
