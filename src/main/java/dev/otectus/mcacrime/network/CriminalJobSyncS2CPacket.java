package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.job.CriminalJob;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

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
public record CriminalJobSyncS2CPacket(UUID villager, CriminalJob job) implements CustomPacketPayload {

    public static final Type<CriminalJobSyncS2CPacket> TYPE = new Type<>(McaCrime.id("criminal_job_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CriminalJobSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CriminalJobSyncS2CPacket::villager,
                    CrimeStreamCodecs.enumCodec(CriminalJob.class, "criminal job"),
                    CriminalJobSyncS2CPacket::job,
                    CriminalJobSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
