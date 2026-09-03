package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server→client: the receiving player's own karma/heat/band/wanted/jail/legal-target, for the reputation player card. */
public record SelfStatusS2CPacket(long karma, long heat, Band band, boolean wanted,
                                  long jailRemainingTicks, boolean legalTarget) {

    public static void encode(SelfStatusS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.karma);
        buf.writeLong(msg.heat);
        buf.writeEnum(msg.band);
        buf.writeBoolean(msg.wanted);
        buf.writeLong(msg.jailRemainingTicks);
        buf.writeBoolean(msg.legalTarget);
    }

    public static SelfStatusS2CPacket decode(FriendlyByteBuf buf) {
        return new SelfStatusS2CPacket(buf.readLong(), buf.readLong(), buf.readEnum(Band.class), buf.readBoolean(),
                buf.readLong(), buf.readBoolean());
    }

    public static void handle(SelfStatusS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onSelfStatus(msg)));
        context.setPacketHandled(true);
    }
}
