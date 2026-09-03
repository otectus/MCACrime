package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.action.ActionMenuKind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

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
public record RequestSelfMenuC2SPacket(ActionMenuKind kind) implements CustomPacketPayload {

    public static final Type<RequestSelfMenuC2SPacket> TYPE = new Type<>(McaCrime.id("request_self_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSelfMenuC2SPacket> STREAM_CODEC =
            StreamCodec.composite(
                    CrimeStreamCodecs.enumCodec(ActionMenuKind.class, "action menu kind"),
                    RequestSelfMenuC2SPacket::kind,
                    RequestSelfMenuC2SPacket::new);

    public RequestSelfMenuC2SPacket {
        kind = kind == null ? ActionMenuKind.SELF : kind;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
