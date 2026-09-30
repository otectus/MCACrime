package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code restraints.definitions.headTapeMufflesTextChat} does to a gagged player's words.
 *
 * <p>The key existed, was documented and was warned about by the validator, and turning it on changed
 * nothing. The message is muffled rather than cancelled, because chat is a protected action: the
 * player is still visibly speaking.
 */
class RestraintChatMuffleTest {

    @Test
    void everyWordBecomesOneMuffle() {
        assertEquals("mph mmph mmmmph", RestraintChatMuffle.muffle("hi you there"));
    }

    @Test
    void longWordsDoNotGrowWithoutBound() {
        assertEquals("mmmmph", RestraintChatMuffle.muffle("incomprehensibilities"));
    }

    @Test
    void aCapitalAndTheClosingToneSurvive() {
        assertEquals("Mmmph mmmph!", RestraintChatMuffle.muffle("Help them!"));
        assertEquals("Mmph?", RestraintChatMuffle.muffle("Why?"));
    }

    @Test
    void punctuationAloneIsStillAnAttemptToSpeak() {
        assertEquals("Mmph.", RestraintChatMuffle.muffle("..."));
        assertEquals("Mmph.", RestraintChatMuffle.muffle("   "));
        assertEquals("Mmph.", RestraintChatMuffle.muffle(null));
    }
}
