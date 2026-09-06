package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.action.ActionMenuKind;
import dev.otectus.mcacrime.action.CrimeActionService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "Open the panel about my own situation" — the captive panel, or the standing panel.
 *
 * <p>Separate from {@link RequestActionMenuC2SPacket} rather than folded into it with the sender's own
 * UUID, because {@link ActionMenuKind#CAPTIVE} and {@link ActionMenuKind#SELF} are different menus and
 * "the target happens to be me" does not say which one was asked for. Conflating them would let a
 * keybind open the captive panel for a player who is not captive.
 *
 * <p>The kind is a request, not an assertion: the server builds whichever action set that menu
 * declares and evaluates every row from live state, so asking for the captive panel while free simply
 * produces a panel with nothing available on it.
 */
public record RequestSelfMenuC2SPacket(ActionMenuKind kind) {

    private static final ActionMenuKind[] KINDS = ActionMenuKind.values();

    public RequestSelfMenuC2SPacket {
        kind = kind == null ? ActionMenuKind.SELF : kind;
    }

    public static void encode(RequestSelfMenuC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kind.ordinal());
    }

    public static RequestSelfMenuC2SPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        return new RequestSelfMenuC2SPacket(
                ordinal >= 0 && ordinal < KINDS.length ? KINDS[ordinal] : ActionMenuKind.SELF);
    }

    public static void handle(RequestSelfMenuC2SPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPacketGuard.accept(ctx, RequestBudget.Category.MENU,
                sender -> CrimeActionService.openSelfMenu(sender, msg.kind()));
    }
}
