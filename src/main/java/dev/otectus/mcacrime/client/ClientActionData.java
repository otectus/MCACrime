package dev.otectus.mcacrime.client;

import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;
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

    /**
     * The bar the player actually sees, walked toward {@link #targetFraction} rather than snapped to
     * it. Progress arrives every few ticks; without this the bar advances in visible steps, which for
     * an NPC mugging is the difference between a threat and a stutter.
     */
    private static volatile float displayedFraction;
    private static volatile float previousFraction;
    private static volatile float targetFraction;
    /** Client ticks since the last packet, and how many the one before that took. */
    private static volatile int ticksSinceSample;
    private static volatile int lastSampleInterval = 1;

    private static volatile String outcomeKey = "";
    private static volatile Component outcomeText = Component.empty();
    private static volatile boolean outcomeWasCancellation;
    private static volatile int outcomeTicksLeft;

    private ClientActionData() {
    }

    public static void apply(ActionProgressS2CPacket msg) {
        sessionId = msg.sessionId();
        labelKey = msg.actionLabelKey();
        progress = msg.progress();
        required = Math.max(1, msg.required());
        float sample = required <= 0 ? 0.0F : Math.min(1.0F, (float) progress / required);
        switch (msg.phase()) {
            case STARTED, PROGRESS -> {
                if (msg.phase() == ActionProgressS2CPacket.Phase.STARTED) {
                    displayedFraction = sample;
                    lastSampleInterval = 1;
                } else {
                    // The gap between the last two packets is the best estimate of the next one, and
                    // it costs one int to keep.
                    lastSampleInterval = Math.max(1, ticksSinceSample);
                }
                previousFraction = displayedFraction;
                targetFraction = sample;
                ticksSinceSample = 0;
                channelling = true;
                // A new action clears the previous outcome immediately; showing "you were struck"
                // over a bar that is already filling again reads as the new action having failed.
                outcomeTicksLeft = 0;
                outcomeKey = "";
                outcomeText = Component.empty();
            }
            case FINISHED, CANCELLED -> {
                previousFraction = sample;
                targetFraction = sample;
                displayedFraction = sample;
                channelling = false;
                outcomeKey = msg.outcomeKey();
                outcomeText = msg.outcomeText();
                outcomeWasCancellation = msg.phase() == ActionProgressS2CPacket.Phase.CANCELLED;
                outcomeTicksLeft = outcomeKey.isEmpty() ? 0 : OUTCOME_TICKS;
            }
        }
    }

    /** Ages the outcome fade, and walks the bar one client tick toward the last reported progress. */
    public static void tick() {
        if (outcomeTicksLeft > 0) outcomeTicksLeft--;
        if (channelling) {
            ticksSinceSample++;
            float t = Math.min(1.0F, ticksSinceSample / (float) Math.max(1, lastSampleInterval));
            displayedFraction = previousFraction + (targetFraction - previousFraction) * t;
        }
    }

    /** Drops everything. Called on disconnect so a rejoin never inherits a stale bar. */
    public static void clear() {
        sessionId = null;
        labelKey = "";
        progress = 0;
        required = 1;
        channelling = false;
        displayedFraction = 0.0F;
        previousFraction = 0.0F;
        targetFraction = 0.0F;
        ticksSinceSample = 0;
        lastSampleInterval = 1;
        outcomeKey = "";
        outcomeText = Component.empty();
        outcomeTicksLeft = 0;
    }

    public static boolean channelling() {
        return channelling;
    }

    public static String labelKey() {
        return labelKey;
    }

    /** Channel completion in the range 0..1, interpolated between the last two progress packets. */
    public static float fraction() {
        return Math.max(0.0F, Math.min(1.0F, displayedFraction));
    }

    /** Completion exactly as the server last reported it, with no interpolation. */
    public static float reportedFraction() {
        return required <= 0 ? 0.0F : Math.min(1.0F, (float) progress / required);
    }

    public static boolean hasOutcome() {
        return outcomeTicksLeft > 0 && !outcomeKey.isEmpty();
    }

    public static String outcomeKey() {
        return outcomeKey;
    }

    /**
     * The outcome as it should be drawn: the server's pre-substituted text, or the bare key when a
     * peer sent none. Rendering the key alone is what used to show "%s" where an emerald count went.
     */
    public static Component outcomeText() {
        Component text = outcomeText;
        return text.getString().isEmpty() ? Component.translatable(outcomeKey) : text;
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
