package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * The life of one channelled action, as the client needs to draw it.
 *
 * <p>Until this existed, an action that broke off simply stopped. {@code ActionSessionManager.cancel}
 * recorded the reason in a replay result that nothing read, so a player whose mugging was interrupted
 * by stepping backwards saw the progress text stop and was told nothing — the nine reasons an action
 * can be cancelled were all invisible. This packet is what makes them visible.
 *
 * <p>Display only. The client cannot start, advance or cancel anything with it; it is told what
 * already happened on the server.
 *
 * <p>{@code outcomeKey} and {@code outcomeText} both travel because they answer different questions.
 * The key is the identity of the outcome, which is what the client's fade logic and the tests key on;
 * the text is the same outcome with its arguments already substituted, because seven of these keys
 * hold a {@code %s} and the server is the only side that knows the fine, the ransom or the name that
 * belongs in it. Sending only the key is what put "You rob the villager of %s emeralds." on the HUD.
 */
public record ActionProgressS2CPacket(UUID sessionId, String actionLabelKey, int progress, int required,
                                      Phase phase, String outcomeKey, Component outcomeText)
        implements CustomPacketPayload {

    /** A label is a translation key, so this is generous already. */
    public static final int MAX_LABEL_LENGTH = 128;

    /** An outcome key may carry a reason suffix, so it gets twice the room a label does. */
    public static final int MAX_OUTCOME_LENGTH = 256;

    /** Where in its life the action is. */
    public enum Phase {
        /** A channel opened; start drawing the bar. */
        STARTED,
        /** Routine advance. */
        PROGRESS,
        /** Completed on its own terms. {@code outcomeKey} says how it turned out. */
        FINISHED,
        /** Broke off early. {@code outcomeKey} is the reason, and the player needs to see it. */
        CANCELLED
    }

    public static final Type<ActionProgressS2CPacket> TYPE = new Type<>(McaCrime.id("action_progress"));

    /**
     * Seven fields, so this is written by hand rather than through {@code StreamCodec.composite},
     * which stops at six — the same reason {@link ActionMenuEntry} writes its own.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionProgressS2CPacket> STREAM_CODEC =
            StreamCodec.of(ActionProgressS2CPacket::write, ActionProgressS2CPacket::read);

    private static final StreamCodec<RegistryFriendlyByteBuf, Phase> PHASE_CODEC =
            CrimeStreamCodecs.enumCodec(Phase.class, "action phase");

    private static void write(RegistryFriendlyByteBuf buf, ActionProgressS2CPacket msg) {
        buf.writeUUID(msg.sessionId());
        buf.writeUtf(msg.actionLabelKey(), MAX_LABEL_LENGTH);
        buf.writeVarInt(msg.progress());
        buf.writeVarInt(msg.required());
        PHASE_CODEC.encode(buf, msg.phase());
        buf.writeUtf(msg.outcomeKey(), MAX_OUTCOME_LENGTH);
        ComponentSerialization.STREAM_CODEC.encode(buf, msg.outcomeText());
    }

    private static ActionProgressS2CPacket read(RegistryFriendlyByteBuf buf) {
        return new ActionProgressS2CPacket(buf.readUUID(), buf.readUtf(MAX_LABEL_LENGTH),
                buf.readVarInt(), buf.readVarInt(), PHASE_CODEC.decode(buf),
                buf.readUtf(MAX_OUTCOME_LENGTH), ComponentSerialization.STREAM_CODEC.decode(buf));
    }

    public ActionProgressS2CPacket {
        actionLabelKey = actionLabelKey == null ? "" : actionLabelKey;
        outcomeKey = outcomeKey == null ? "" : outcomeKey;
        outcomeText = outcomeText == null ? Component.empty() : outcomeText;
        phase = phase == null ? Phase.PROGRESS : phase;
        required = Math.max(1, required);
        progress = Math.max(0, Math.min(progress, required));
    }

    /** A bar that has just appeared, at zero. Nothing has happened yet, so there is no outcome text. */
    public static ActionProgressS2CPacket started(UUID sessionId, String labelKey, int required) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 0, required, Phase.STARTED, "",
                Component.empty());
    }

    /** A bar that ended, one way or the other. */
    public static ActionProgressS2CPacket ended(UUID sessionId, String labelKey, Phase phase,
                                                String outcomeKey, Component outcomeText) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 1, 1, phase, outcomeKey, outcomeText);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
