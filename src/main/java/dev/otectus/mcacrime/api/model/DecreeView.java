package dev.otectus.mcacrime.api.model;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable projection of one royal decree (0.7.8): a village ruler's order, through MCA Capitals,
 * that one player is wanted in that village.
 *
 * <p>A decree is not a crime. It has no victim and no evidence, changes no Heat, Karma or band, opens no
 * bounty warrant, and appears in no case ledger. A companion that wants to say something about a
 * player's record should keep the two apart, exactly as MCA: Crime does.
 *
 * <p>{@code state} and {@code reason} are lowercase strings rather than enums, so a state or reason
 * added later widens the vocabulary instead of breaking a consumer's switch. Times are overworld game
 * ticks; {@code expiresAt} does not apply while {@code answeringSentence} is present.
 *
 * @param state             {@code open}, {@code withdrawn}, {@code fined}, {@code served}, {@code expired}
 *                          or {@code void}
 * @param reason            {@code trespass}, {@code disorder}, {@code defiance}, {@code treason} or
 *                          {@code other}
 * @param answeringSentence the custody sentence currently answering the decree, if one is running
 */
public record DecreeView(UUID decreeId, UUID offender, CrimeCommunityKey community, UUID capitalId,
                         UUID issuedBy, String reason, long issuedAt, long expiresAt, String state,
                         long endedAt, Optional<UUID> answeringSentence) {

    public DecreeView {
        answeringSentence = answeringSentence == null ? Optional.empty() : answeringSentence;
        reason = reason == null ? "" : reason;
        state = state == null ? "" : state;
    }

    /** Whether the decree has not ended. Whether it is enforced right now also depends on its capital. */
    public boolean open() {
        return "open".equals(state);
    }
}
