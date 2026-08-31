package dev.otectus.mcacrime.api.model;

import dev.otectus.mcacrime.jail.JailContainmentMode;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable projection of a player's current jail sentence.
 *
 * <p>{@code sentenceId} is what lets a companion mod say "this fine settled <em>that</em> sentence"
 * rather than "some sentence". {@code linkedCaseIds} names the exact cases the sentence answers for,
 * so serving it out resolves those and only those — the difference between atonement and a blanket
 * amnesty.
 *
 * <p>Time is counted in <b>online</b> ticks, never wall-clock: a sentence must not expire while the
 * player is logged out, or jail becomes a matter of waiting rather than serving.
 */
public record JailSentenceView(
        Optional<UUID> sentenceId,
        long remainingOnlineTicks,
        long realOnlineTicksServed,
        Optional<ResourceLocation> jailDimension,
        boolean escaped,
        JailContainmentMode containmentMode,
        Set<UUID> linkedCaseIds) {

    public JailSentenceView {
        sentenceId = sentenceId == null ? Optional.empty() : sentenceId;
        jailDimension = jailDimension == null ? Optional.empty() : jailDimension;
        linkedCaseIds = linkedCaseIds == null ? Set.of() : Set.copyOf(linkedCaseIds);
        containmentMode = containmentMode == null ? JailContainmentMode.CONTAINMENT : containmentMode;
        remainingOnlineTicks = Math.max(0L, remainingOnlineTicks);
        realOnlineTicksServed = Math.max(0L, realOnlineTicksServed);
    }

    /** Whether the sentence still has time left to serve. */
    public boolean active() {
        return remainingOnlineTicks > 0L;
    }
}
