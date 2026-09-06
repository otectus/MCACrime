package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.job.CriminalJob;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: one villager's criminal job (0.5.1).
 *
 * <p>Sent to a client that has just started tracking a criminal villager, so the Crime button can
 * enable itself for an unarmed player standing in front of a fence -- the one case where the weapon
 * gate does not apply. {@link CriminalJob#NONE} clears the client's entry.
 *
 * <p>Display only. The record of record is {@code CrimeWorldData.criminalVillagers}, and the server
 * re-reads it when the menu packet arrives.
 */
public record CriminalJobSyncS2CPacket(UUID villager, CriminalJob job) {

    public static void encode(CriminalJobSyncS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.villager);
        buf.writeEnum(msg.job);
    }

    public static CriminalJobSyncS2CPacket decode(FriendlyByteBuf buf) {
        return new CriminalJobSyncS2CPacket(buf.readUUID(), buf.readEnum(CriminalJob.class));
    }

    public static void handle(CriminalJobSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isClient()) {
            return;
        }
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onCriminalJob(msg)));
    }
}
