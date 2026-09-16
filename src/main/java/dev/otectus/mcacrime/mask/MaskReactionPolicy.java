package dev.otectus.mcacrime.mask;

import dev.otectus.mcacrime.ai.VictimReactionState;

/**
 * What a mask takes away from a witness, and what it does not (0.7.0).
 *
 * <p>The invariant both methods exist to hold: a mask removes the <em>record</em>, never the
 * <em>reaction</em>. Attribution — the stored observation, the victim's memory of who did it, a
 * responder's direct filing — is what nobody watching a masked crime can supply. The fact that a
 * person with a weapon is standing in front of them is not hidden by anything, so the villager must
 * still start reacting and must still be judged by the same evaluator as on the unmasked path.
 *
 * <p>A face-to-face observer therefore maps to {@link VictimReactionState#THREATENED} and never to
 * {@link VictimReactionState#SEEKING_HELP}, however little they know about who they are looking at:
 * {@code SEEKING_HELP} is listed in {@code ai/ReactionControlPolicy.refusesMugging}, so mapping a
 * victim there would turn every masked mugging into a refusal rather than hiding an identity.
 *
 * <p>Common code, and pure — no Minecraft types beyond the reaction enum, no client imports.
 */
public final class MaskReactionPolicy {

    private MaskReactionPolicy() {
    }

    /** Whether this crime's attribution is hidden: a mask was worn and the config honours it. */
    public static boolean hidesAttribution(boolean masked, boolean configOn) {
        return masked && configOn;
    }

    /**
     * The state an observer enters. {@code perceivedOffender} is the physical sighting — the direct
     * victim's {@code faceToFace} test, or an eyewitness's {@code identifiesActor} — not the question
     * of whether a name can be put to the face.
     */
    public static VictimReactionState initialReaction(boolean perceivedOffender) {
        return perceivedOffender ? VictimReactionState.THREATENED : VictimReactionState.SEEKING_HELP;
    }
}
