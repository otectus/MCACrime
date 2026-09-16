package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.ReactionControlPolicy;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.mask.MaskReactionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mask's reaction contract (0.7.0): it takes away the record, not the reaction.
 *
 * <p>The load-bearing assertion is the face-to-face one. Mapping a masked victim to
 * {@code SEEKING_HELP} "because the identity is unknown" compiles and reads sensibly, and it is a bug:
 * {@code SEEKING_HELP} is refused by {@link ReactionControlPolicy#refusesMugging}, so every masked
 * mugging would still fail, only with a different message.
 */
class MaskReactionPolicyTest {

    @Test
    void attributionIsHiddenOnlyWhenAMaskIsWornAndTheConfigHonoursIt() {
        assertTrue(MaskReactionPolicy.hidesAttribution(true, true));
        assertFalse(MaskReactionPolicy.hidesAttribution(true, false));
        assertFalse(MaskReactionPolicy.hidesAttribution(false, true));
        assertFalse(MaskReactionPolicy.hidesAttribution(false, false));
    }

    @Test
    void aVictimLookingAtTheOffenderIsThreatenedAndNeverSeekingHelp() {
        assertEquals(VictimReactionState.THREATENED, MaskReactionPolicy.initialReaction(true));
        assertFalse(ReactionControlPolicy.refusesMugging(false, true,
                MaskReactionPolicy.initialReaction(true)));
    }

    @Test
    void anObserverWhoNeverSawTheOffenderGoesLookingForHelp() {
        assertEquals(VictimReactionState.SEEKING_HELP, MaskReactionPolicy.initialReaction(false));
        assertTrue(ReactionControlPolicy.refusesMugging(false, true,
                MaskReactionPolicy.initialReaction(false)));
    }
}
