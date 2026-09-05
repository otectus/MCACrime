package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import dev.otectus.mcacrime.item.weapon.WeaponPolicySnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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
public record WeaponPolicyS2CPacket(WeaponPolicySnapshot policy) {

    public static void encode(WeaponPolicyS2CPacket msg, FriendlyByteBuf buf) {
        msg.policy.encode(buf);
    }

    public static WeaponPolicyS2CPacket decode(FriendlyByteBuf buf) {
        return new WeaponPolicyS2CPacket(WeaponPolicySnapshot.decode(buf));
    }

    public static void handle(WeaponPolicyS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.onWeaponPolicy(msg)));
        context.setPacketHandled(true);
    }
}
