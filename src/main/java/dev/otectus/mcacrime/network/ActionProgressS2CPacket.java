package dev.otectus.mcacrime.network;

import dev.otectus.mcacrime.client.CrimeClientHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

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
 * <p>{@code outcomeText} is the formatted outcome and {@code outcomeKey} its bare identity. Both
 * travel because they answer different questions: the key is what the server-side ledger and the
 * replay cache speak in, while the text is the only one of the two that can carry the amount a
 * mugging took. A key alone put "You rob the villager of %s emeralds." on the HUD verbatim.
 */
public record ActionProgressS2CPacket(UUID sessionId, String actionLabelKey, int progress, int required,
                                      Phase phase, String outcomeKey, Component outcomeText) {

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

    private static final Phase[] PHASES = Phase.values();

    public ActionProgressS2CPacket {
        actionLabelKey = actionLabelKey == null ? "" : actionLabelKey;
        outcomeKey = outcomeKey == null ? "" : outcomeKey;
        outcomeText = outcomeText == null ? Component.empty() : outcomeText;
        phase = phase == null ? Phase.PROGRESS : phase;
        required = Math.max(1, required);
        progress = Math.max(0, Math.min(progress, required));
    }

    /** A bar that has just appeared, at zero. */
    public static ActionProgressS2CPacket started(UUID sessionId, String labelKey, int required) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 0, required, Phase.STARTED, "", Component.empty());
    }

    /** A bar that ended on a key alone, for an outcome that takes no arguments. */
    public static ActionProgressS2CPacket ended(UUID sessionId, String labelKey, Phase phase, String outcomeKey) {
        return ended(sessionId, labelKey, phase, outcomeKey, Component.empty());
    }

    /** A bar that ended, one way or the other, carrying the formatted outcome line. */
    public static ActionProgressS2CPacket ended(UUID sessionId, String labelKey, Phase phase, String outcomeKey,
                                                Component outcomeText) {
        return new ActionProgressS2CPacket(sessionId, labelKey, 1, 1, phase, outcomeKey, outcomeText);
    }

    public static void encode(ActionProgressS2CPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeUtf(msg.actionLabelKey, 128);
        buf.writeVarInt(msg.progress);
        buf.writeVarInt(msg.required);
        buf.writeEnum(msg.phase);
        buf.writeUtf(msg.outcomeKey, 256);
        buf.writeComponent(msg.outcomeText);
    }

    public static ActionProgressS2CPacket decode(FriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        String label = buf.readUtf(128);
        int progress = buf.readVarInt();
        int required = buf.readVarInt();
        // Read defensively: a decoder throw drops the connection, and a bad ordinal here is worth
        // less than the session it would cost.
        int ordinal = buf.readVarInt();
        Phase phase = ordinal >= 0 && ordinal < PHASES.length ? PHASES[ordinal] : Phase.PROGRESS;
        String outcome = buf.readUtf(256);
        Component outcomeText = buf.readComponent();
        return new ActionProgressS2CPacket(sessionId, label, progress, required, phase, outcome, outcomeText);
    }

    public static void handle(ActionProgressS2CPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CrimeClientHandlers.onActionProgress(msg)));
        context.setPacketHandled(true);
    }
}
