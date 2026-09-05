package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the exact inputs the server's weapon rules are compiled from (0.5.1).
 *
 * <p>Sent on login and re-broadcast on a config reload, and no more often than that -- the policy is
 * a handful of lists that change when an operator edits the TOML, so it is deliberately not
 * piggybacked on the frequent status packet.
 *
 * <p>Display only, and specifically only so the Crime button can grey itself out for the same reason
 * the server would refuse the packet. The server re-checks the gate regardless of what the client
 * believes.
 */
public record WeaponPolicyS2CPacket(WeaponPolicySnapshot policy) implements CustomPacketPayload {

    public static final Type<WeaponPolicyS2CPacket> TYPE = new Type<>(McaCrime.id("weapon_policy"));

    /** The whole body is the snapshot, so the codec is the snapshot's own, wrapped once. */
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponPolicyS2CPacket> STREAM_CODEC =
            WeaponPolicySnapshot.STREAM_CODEC.map(WeaponPolicyS2CPacket::new, WeaponPolicyS2CPacket::policy);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
