package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
 */
public record ActionProgressS2CPacket(UUID sessionId, String actionLabelKey, int progress, int required,
                                      Phase phase, String outcomeKey) implements CustomPacketPayload {

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

    public static final StreamCodec<RegistryFriendlyByteBuf, ActionProgressS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ActionProgressS2CPacket::sessionId,
                    ByteBufCodecs.stringUtf8(MAX_LABEL_LENGTH), ActionProgressS2CPacket::actionLabelKey,
                    ByteBufCodecs.VAR_INT, ActionProgressS2CPacket::progress,
                    ByteBufCodecs.VAR_INT, ActionProgressS2CPacket::required,
                    CrimeStreamCodecs.enumCodec(Phase.class, "action phase"), ActionProgressS2CPacket::phase,
                    ByteBufCodecs.stringUtf8(MAX_OUTCOME_LENGTH), ActionProgressS2CPacket::outcomeKey,
                    ActionProgressS2CPacket::new);

    public ActionProgressS2CPacket {
        actionLabelKey = actionLabelKey == null ? "" : actionLabelKey;
        outcomeKey = outcomeKey == null ? "" : outcomeKey;
        phase = phase == null ? Phase.PROGRESS : phase;
        required = Math.max(1, required);
        progress = Math.max(0, Math.min(progress, required));
    }

    /** A bar that has just appeared, at zero. */
    public static ActionProgressS2CPacket started(UUID sessionId, String labelKey, int required) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 0, required, Phase.STARTED, "");
    }

    /** A bar that ended, one way or the other. */
    public static ActionProgressS2CPacket ended(UUID sessionId, String labelKey, Phase phase, String outcomeKey) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 1, 1, phase, outcomeKey);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
