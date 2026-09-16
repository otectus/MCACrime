package dev.otectus.mcacrime.network;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * The one door every client-to-server payload on this channel goes through.
 *
 * <p>Three checks, in this order, and none of them optional. The <b>direction</b> check is the one
 * that is easy to forget and only wrong on an integrated server: a payload registered without a
 * direction can be received by the client that sent it, and the handler would then run against the
 * single-player host's own state. The <b>sender</b> check is what makes "the server answers about
 * whoever sent it" true. The <b>budget</b> is {@link RequestBudget}.
 *
 * <p>The work is handed to {@link IPayloadContext#enqueueWork(Runnable)} rather than run inline. The
 * payload registrar already calls server-bound handlers on the main thread, so this is belt and
 * braces rather than the load-bearing hop it was under Forge's {@code NetworkEvent.Context} — but a
 * handler that is correct wherever it is invoked from is cheaper than one that is only correct
 * because of where the registrar happens to call it.
 *
 * <p>There is no {@code setPacketHandled} analogue: the payload API considers a payload handled by
 * the act of dispatching it to the registered handler.
 */
public final class ServerPacketGuard {

    private ServerPacketGuard() {
    }

    /**
     * Schedules {@code work} on the server thread with the sender, if all three checks pass.
     *
     * @return whether the work was scheduled — for the caller that wants to log a refusal
     */
    public static boolean accept(IPayloadContext context, RequestBudget.Category category,
                                 Consumer<ServerPlayer> work) {
        if (context == null || context.flow() != PacketFlow.SERVERBOUND) {
            return false;
        }
        if (!(context.player() instanceof ServerPlayer sender)) {
            return false;
        }
        if (!RequestBudget.allow(sender.getUUID(), category)) {
            return false;
        }
        context.enqueueWork(() -> work.accept(sender));
        return true;
    }
}
