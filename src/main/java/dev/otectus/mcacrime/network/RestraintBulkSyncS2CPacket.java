package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.enforcement.RestraintVisualState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server to client: every restrained subject right now, sent to a joining client.
 *
 * <p>The twin of {@code BandBulkSyncS2CPacket}, and for the same reason: a client that connects
 * halfway through somebody else's arrest would otherwise see an uncuffed player walking beside a guard
 * until the next state change happened to broadcast. Since 0.5.1 it carries villagers too, so a cell
 * full of prisoners is drawn correctly from the first frame.
 */
public record RestraintBulkSyncS2CPacket(Map<UUID, RestraintVisualState> restrained)
        implements CustomPacketPayload {

    /** The same hard ceiling the band snapshot uses, and for the same reason (spec §9.6). */
    public static final int MAX_SUBJECTS = 1024;

    public static final Type<RestraintBulkSyncS2CPacket> TYPE =
            new Type<>(McaCrime.id("restraint_bulk_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestraintBulkSyncS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.map(HashMap::new, UUIDUtil.STREAM_CODEC,
                            RestraintVisualState.STREAM_CODEC, MAX_SUBJECTS),
                    RestraintBulkSyncS2CPacket::restrained,
                    RestraintBulkSyncS2CPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
