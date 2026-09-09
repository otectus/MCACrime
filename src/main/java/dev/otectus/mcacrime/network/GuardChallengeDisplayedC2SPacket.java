package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** Acknowledges the first visible frame of this player's encounter, never a response or an extension. */
public record GuardChallengeDisplayedC2SPacket(UUID encounterId) {
    public static void encode(GuardChallengeDisplayedC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.encounterId);
    }

    public static GuardChallengeDisplayedC2SPacket decode(FriendlyByteBuf buf) {
        return new GuardChallengeDisplayedC2SPacket(buf.readUUID());
    }

    public static void handle(GuardChallengeDisplayedC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isServer()) return;
        var player = context.getSender();
        if (player != null) context.enqueueWork(() -> GuardChallengeService.menuDisplayed(player, msg.encounterId));
    }
}
