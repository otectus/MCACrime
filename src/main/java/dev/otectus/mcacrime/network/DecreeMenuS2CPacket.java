package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.decree.DecreeMenuService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The decree screen's contents, issued by the server to the ruler alone (0.7.8). Bounded on both sides:
 * a malformed or hostile payload cannot make the client allocate or draw without limit.
 */
public record DecreeMenuS2CPacket(DecreeMenuService.Menu menu) implements CustomPacketPayload {

    public static final Type<DecreeMenuS2CPacket> TYPE = new Type<>(McaCrime.id("decree_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecreeMenuS2CPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.write(buf), DecreeMenuS2CPacket::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(menu.id());
        buf.writeVarInt(menu.revision());
        buf.writeUUID(menu.hostId());
        ComponentSerialization.STREAM_CODEC.encode(buf, menu.village());
        List<DecreeMenuService.Candidate> candidates = menu.candidates().stream()
                .limit(DecreeMenuService.MAX_CANDIDATES).toList();
        buf.writeVarInt(candidates.size());
        for (DecreeMenuService.Candidate row : candidates) {
            buf.writeUUID(row.player());
            ComponentSerialization.STREAM_CODEC.encode(buf, row.name());
            buf.writeBoolean(row.eligible());
            buf.writeUtf(row.reasonKey(), PacketBounds.MAX_ID_LENGTH);
        }
        List<DecreeMenuService.OpenRow> open = menu.open().stream().limit(DecreeMenuService.MAX_OPEN).toList();
        buf.writeVarInt(open.size());
        for (DecreeMenuService.OpenRow row : open) {
            buf.writeUUID(row.decree());
            ComponentSerialization.STREAM_CODEC.encode(buf, row.name());
            buf.writeUtf(row.reasonId(), PacketBounds.MAX_ID_LENGTH);
            buf.writeVarLong(Math.max(0L, row.remainingTicks()));
        }
        buf.writeVarLong(Math.max(0L, menu.durationTicks()));
        ComponentSerialization.STREAM_CODEC.encode(buf, menu.fine());
        buf.writeBoolean(menu.payable());
    }

    private static DecreeMenuS2CPacket read(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int revision = buf.readVarInt();
        UUID host = buf.readUUID();
        Component village = ComponentSerialization.STREAM_CODEC.decode(buf);
        int candidateCount = PacketBounds.readCount(buf, DecreeMenuService.MAX_CANDIDATES);
        List<DecreeMenuService.Candidate> candidates = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(new DecreeMenuService.Candidate(buf.readUUID(), ComponentSerialization.STREAM_CODEC.decode(buf),
                    buf.readBoolean(), PacketBounds.readId(buf)));
        }
        int openCount = PacketBounds.readCount(buf, DecreeMenuService.MAX_OPEN);
        List<DecreeMenuService.OpenRow> open = new ArrayList<>(openCount);
        for (int i = 0; i < openCount; i++) {
            open.add(new DecreeMenuService.OpenRow(buf.readUUID(), ComponentSerialization.STREAM_CODEC.decode(buf),
                    PacketBounds.readId(buf), buf.readVarLong()));
        }
        long duration = buf.readVarLong();
        Component fine = ComponentSerialization.STREAM_CODEC.decode(buf);
        boolean payable = buf.readBoolean();
        return new DecreeMenuS2CPacket(new DecreeMenuService.Menu(id, revision, host, village, candidates, open,
                duration, fine, payable));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
