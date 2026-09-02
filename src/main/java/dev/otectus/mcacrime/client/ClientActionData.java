package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.ActionProgressS2CPacket;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What the client knows about the action it is currently performing, and how the last one ended.
 *
 * <p>Display-only, like every other client cache in this mod. The server is the only thing that
 * advances an action; this is a record of what it last said.
 *
 * <p>The outcome is kept for a short fade after the action ends rather than cleared immediately. That
 * is the whole fix for the mod's quietest UX defect: an interrupted action used to simply stop, with
 * the reason recorded in a server-side replay result that nothing ever showed anyone.
 */
public final class ClientActionData {

    /** How long a finished or cancelled outcome stays on screen, in client ticks. */
    public static final int OUTCOME_TICKS = 60;

    @Nullable
    private static volatile UUID sessionId;
    private static volatile String labelKey = "";
    private static volatile int progress;
    private static volatile int required = 1;
    private static volatile boolean channelling;

    private static volatile String outcomeKey = "";
    private static volatile boolean outcomeWasCancellation;
    private static volatile int outcomeTicksLeft;

    private ClientActionData() {
    }

    public static void apply(ActionProgressS2CPacket msg) {
        sessionId = msg.sessionId();
        labelKey = msg.actionLabelKey();
        progress = msg.progress();
        required = Math.max(1, msg.required());
        switch (msg.phase()) {
            case STARTED, PROGRESS -> {
                channelling = true;
                // A new action clears the previous outcome immediately; showing "you were struck"
                // over a bar that is already filling again reads as the new action having failed.
                outcomeTicksLeft = 0;
                outcomeKey = "";
            }
            case FINISHED, CANCELLED -> {
                channelling = false;
                outcomeKey = msg.outcomeKey();
                outcomeWasCancellation = msg.phase() == ActionProgressS2CPacket.Phase.CANCELLED;
                outcomeTicksLeft = outcomeKey.isEmpty() ? 0 : OUTCOME_TICKS;
            }
        }
    }

    /** Ages the outcome fade by one client tick. */
    public static void tick() {
        if (outcomeTicksLeft > 0) outcomeTicksLeft--;
    }

    /** Drops everything. Called on disconnect so a rejoin never inherits a stale bar. */
    public static void clear() {
        sessionId = null;
        labelKey = "";
        progress = 0;
        required = 1;
        channelling = false;
        outcomeKey = "";
        outcomeTicksLeft = 0;
    }

    public static boolean channelling() {
        return channelling;
    }

    public static String labelKey() {
        return labelKey;
    }

    /** Channel completion in the range 0..1. */
    public static float fraction() {
        return required <= 0 ? 0.0F : Math.min(1.0F, (float) progress / required);
    }

    public static boolean hasOutcome() {
        return outcomeTicksLeft > 0 && !outcomeKey.isEmpty();
    }

    public static String outcomeKey() {
        return outcomeKey;
    }

    public static boolean outcomeWasCancellation() {
        return outcomeWasCancellation;
    }

    /** Opacity for the fading outcome line, 0..1. */
    public static float outcomeAlpha() {
        return Math.min(1.0F, outcomeTicksLeft / (float) Math.max(1, OUTCOME_TICKS / 3));
    }

    @Nullable
    public static UUID sessionId() {
        return sessionId;
    }
}
