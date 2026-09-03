package dev.otectus.mcacrime;

import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.ledger.CaseTransitions;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards on the case lifecycle a served sentence now drives.
 *
 * <p>Before 0.4.0 {@link Resolution#SERVED} was produced nowhere: a player could be jailed, serve the
 * full term, and walk out with every charge still open and actionable. These tests pin the two rules
 * that make the fix correct — that the transitions it depends on are actually permitted, and that a
 * sentence carries a stable identity so a settled case can name the sentence that settled it.
 *
 * <p>The service itself needs a live {@code MinecraftServer} and so cannot be exercised here; the
 * end-to-end behaviour is section G of the in-world checklist.
 */
class SentenceResolutionTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private static JailState sentence(long ticks) {
        return new JailState(ticks, new BlockPos(0, 64, 0), OVERWORLD, 8, JailContainmentMode.PHYSICAL);
    }

    @Test
    void servingASentenceIsAPermittedOrdinaryTransition() {
        // If this were privileged-only, serving a sentence could never close a case without an
        // operator, which is exactly the state the mod shipped in.
        assertTrue(CaseTransitions.allowed(Resolution.UNRESOLVED, Resolution.SERVED, false));
    }

    @Test
    void aPrisonerWhoEscapedCanStillServeTheSentenceLater() {
        // Escaping is not forgiveness, but it is not a permanent bar either: someone who broke out,
        // was recaptured, and then served the term has answered for the offence.
        assertTrue(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.SERVED, false));
        assertTrue(CaseTransitions.allowed(Resolution.ESCAPED, Resolution.FINED, false));
    }

    @Test
    void anEscapedCaseIsStillActionable() {
        assertTrue(CaseTransitions.isActionable(Resolution.ESCAPED),
                "A guard must still have a legal basis to pursue someone who broke out");
        assertFalse(CaseTransitions.isFinal(Resolution.ESCAPED));
    }

    @Test
    void aServedCaseIsFinalAndNoLongerActionable() {
        assertTrue(CaseTransitions.isFinal(Resolution.SERVED));
        assertFalse(CaseTransitions.isActionable(Resolution.SERVED),
                "Serving the sentence is what stops the pursuit; if it stayed actionable it would "
                        + "cost the player time and change nothing");
    }

    @Test
    void aServedCaseDoesNotSilentlyReopen() {
        assertFalse(CaseTransitions.allowed(Resolution.SERVED, Resolution.UNRESOLVED, false));
        assertFalse(CaseTransitions.allowed(Resolution.SERVED, Resolution.ESCAPED, false));
        assertTrue(CaseTransitions.allowed(Resolution.SERVED, Resolution.UNRESOLVED, true),
                "Correcting a settled case is an administrative act and should look like one");
    }

    @Test
    void everySentenceHasAnIdentity() {
        JailState first = sentence(100L);
        JailState second = sentence(100L);
        assertNotNull(first.getSentenceId());
        assertNotEquals(first.getSentenceId(), second.getSentenceId(),
                "Two sentences sharing an id would let one release dedupe away the other's settlement");
    }

    @Test
    void sentenceIdentitySurvivesSaveAndCopy() {
        JailState original = sentence(100L);
        UUID id = original.getSentenceId();

        assertEquals(id, original.copy().getSentenceId(),
                "copy() runs on death; losing the id there would orphan the settlement");
        assertEquals(id, JailState.load(original.save()).getSentenceId(),
                "The id must survive a restart, or a sentence resumed after one settles under a "
                        + "different name than it was served under");
    }

    @Test
    void aSentenceSavedBeforeIdentityExistedStillLoads() {
        // Schema-4 player NBT has no sentenceId. It must load, and it must come back with a usable id
        // rather than a null that would throw the first time a sentence ends.
        CompoundTag legacy = sentence(100L).save();
        legacy.remove("sentenceId");

        JailState loaded = JailState.load(legacy);
        assertNotNull(loaded.getSentenceId());
        assertEquals(100L, loaded.getRemainingOnlineTicks());
    }
}
