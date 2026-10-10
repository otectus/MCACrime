package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.decree.DecreeMenuService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The decree screen's contents, issued by the server to the ruler alone (0.7.8). Bounded on both sides:
 * a malformed or hostile packet cannot make the client allocate or draw without limit.
 */
public record DecreeMenuS2CPacket(DecreeMenuService.Menu menu) {

    public static void encode(DecreeMenuS2CPacket msg, FriendlyByteBuf buf) {
        DecreeMenuService.Menu menu = msg.menu;
        buf.writeUUID(menu.id());
        buf.writeVarInt(menu.revision());
        buf.writeUUID(menu.hostId());
        buf.writeComponent(menu.village());
        List<DecreeMenuService.Candidate> candidates = menu.candidates().stream()
                .limit(DecreeMenuService.MAX_CANDIDATES).toList();
        buf.writeVarInt(candidates.size());
        for (DecreeMenuService.Candidate row : candidates) {
            buf.writeUUID(row.player());
            buf.writeComponent(row.name());
            buf.writeBoolean(row.eligible());
            buf.writeUtf(row.reasonKey(), PacketBounds.MAX_ID_LENGTH);
        }
        List<DecreeMenuService.OpenRow> open = menu.open().stream().limit(DecreeMenuService.MAX_OPEN).toList();
        buf.writeVarInt(open.size());
        for (DecreeMenuService.OpenRow row : open) {
            buf.writeUUID(row.decree());
            buf.writeComponent(row.name());
            buf.writeUtf(row.reasonId(), PacketBounds.MAX_ID_LENGTH);
            buf.writeVarLong(Math.max(0L, row.remainingTicks()));
        }
        buf.writeVarLong(Math.max(0L, menu.durationTicks()));
        buf.writeComponent(menu.fine());
        buf.writeBoolean(menu.payable());
    }

    public static DecreeMenuS2CPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int revision = buf.readVarInt();
        UUID host = buf.readUUID();
        Component village = buf.readComponent();
        List<DecreeMenuService.Candidate> candidates = PacketBounds.readBoundedList(buf,
                DecreeMenuService.MAX_CANDIDATES, in -> new DecreeMenuService.Candidate(in.readUUID(),
                        in.readComponent(), in.readBoolean(), PacketBounds.readId(in)));
        List<DecreeMenuService.OpenRow> open = PacketBounds.readBoundedList(buf, DecreeMenuService.MAX_OPEN,
                in -> new DecreeMenuService.OpenRow(in.readUUID(), in.readComponent(), PacketBounds.readId(in),
                        in.readVarLong()));
        long duration = buf.readVarLong();
        Component fine = buf.readComponent();
        boolean payable = buf.readBoolean();
        return new DecreeMenuS2CPacket(new DecreeMenuService.Menu(id, revision, host, village, candidates, open,
                duration, fine, payable));
    }

    public static void handle(DecreeMenuS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onDecreeMenu(msg)));
    }
}
