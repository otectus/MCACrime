package dev.otectus.mcacrime.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The one door every client-to-server packet on this channel goes through.
 *
 * <p>Three checks, in this order, and none of them optional. The <b>logical side</b> check is the one
 * that is easy to forget and only wrong on an integrated server: a packet registered without a
 * direction can be received by the client that sent it, and the handler would then run against the
 * single-player host's own state. The <b>sender</b> check is what makes "the server answers about
 * whoever sent it" true. The <b>budget</b> is {@link RequestBudget}.
 *
 * <p>The work is handed to {@code enqueueWork} rather than run here, because everything past this
 * point touches world state and the network thread is not the server thread.
 */
public final class ServerPacketGuard {

    private ServerPacketGuard() {
    }

    /**
     * Marks the packet handled and, if all three checks pass, schedules {@code work} on the server
     * thread with the sender.
     *
     * @return whether the work was scheduled — for the caller that wants to log a refusal
     */
    public static boolean accept(Supplier<NetworkEvent.Context> ctx, RequestBudget.Category category,
                                 Consumer<ServerPlayer> work) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (!context.getDirection().getReceptionSide().isServer()) {
            return false;
        }
        ServerPlayer sender = context.getSender();
        if (sender == null) {
            return false;
        }
        if (!RequestBudget.allow(sender.getUUID(), category)) {
            return false;
        }
        context.enqueueWork(() -> work.accept(sender));
        return true;
    }
}
