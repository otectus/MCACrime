package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.api.model.CapitalSentenceView;
import dev.otectus.mcacrime.api.model.DetentionView;
import dev.otectus.mcacrime.api.model.JailSentenceView;
import dev.otectus.mcacrime.api.model.RestraintSlotView;
import dev.otectus.mcacrime.api.model.RestraintView;
import dev.otectus.mcacrime.jail.JailContainmentMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a companion mod is allowed to know about a death sentence (0.7.5 §3.19, M6.8).
 *
 * <p>The views are the whole public surface of the capital feature, and every assertion here is
 * about what they <em>cannot</em> do: no view assigns a sentence, carries one out, clears one or
 * hands back a mutable reference to anything. The one time-bounded fact any of them carries is the
 * ceremony window, which is the rescue window, and it is read-only too.
 */
class CapitalSentenceViewTest {

    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID SENTENCE = UUID.randomUUID();

    @Test
    void theWindowIsOnlyOpenWhileAnOrderIsPending() {
        CapitalSentenceView condemned = new CapitalSentenceView(SUBJECT, true, SENTENCE, 1200L, false,
                Optional.empty(), 0L);
        assertEquals(0L, condemned.windowRemaining(100L),
                "condemned in custody is not a countdown; there is no window to run out");

        CapitalSentenceView pending = new CapitalSentenceView(SUBJECT, true, SENTENCE, 1200L, true,
                Optional.of(new long[] {1L, 64L, 2L}), 2000L);
        assertEquals(500L, pending.windowRemaining(1500L));
        assertEquals(0L, pending.windowRemaining(9999L), "a closed window never reads as negative");
    }

    @Test
    void aViewCarriesNoNegativeTimeAndNoNullDevice() {
        CapitalSentenceView view = new CapitalSentenceView(SUBJECT, false, SENTENCE, -5L, false,
                null, -1L);
        assertEquals(0L, view.holdingTicks());
        assertEquals(0L, view.expiresAt());
        assertTrue(view.device().isEmpty());
    }

    /** The additive field on the existing sentence view: a v1 consumer sees a custodial sentence. */
    @Test
    void theJailSentenceViewGainedItsKindWithoutBreakingTheOldShape() {
        JailSentenceView old = new JailSentenceView(Optional.of(SENTENCE), 600L, 100L,
                Optional.empty(), false, JailContainmentMode.CONTAINMENT, Set.of());
        assertEquals("custodial", old.sentenceKind());
        assertFalse(old.capital());

        JailSentenceView capital = new JailSentenceView(Optional.of(SENTENCE), 600L, 100L,
                Optional.empty(), false, JailContainmentMode.CONTAINMENT, Set.of(), "capital");
        assertTrue(capital.capital());

        JailSentenceView blank = new JailSentenceView(Optional.of(SENTENCE), 600L, 100L,
                Optional.empty(), false, JailContainmentMode.CONTAINMENT, Set.of(), "  ");
        assertEquals("custodial", blank.sentenceKind(), "an unreadable kind is the ordinary one");
    }

    @Test
    void aDetentionViewSaysWhetherItsOccupantIsCondemned() {
        DetentionView ordinary = new DetentionView(UUID.randomUUID(), SUBJECT, "pillory",
                Optional.empty(), new long[] {0L, 64L, 0L}, 1L, false);
        assertFalse(ordinary.condemned(),
                "a device holding somebody who is not condemned can release them and never execute");

        DetentionView guillotine = new DetentionView(UUID.randomUUID(), SUBJECT, "guillotine",
                Optional.empty(), new long[] {1L, 64L, 1L}, 3L, true);
        assertTrue(guillotine.condemned());
        assertEquals(3L, guillotine.generation());
    }

    @Test
    void aDetentionViewHandsBackACopyOfItsPosition() {
        long[] device = {1L, 64L, 2L};
        DetentionView view = new DetentionView(UUID.randomUUID(), SUBJECT, "guillotine",
                Optional.empty(), device, 1L, true);

        long[] first = view.device();
        first[0] = 99L;
        assertEquals(1L, view.device()[0], "a caller must not be able to move a device by editing a view");
        assertNotSame(view.device(), view.device());
    }

    @Test
    void aRestraintViewIsAReadOnlyProjectionOfWhatIsWorn() {
        RestraintSlotView arms = new RestraintSlotView("arms",
                ResourceLocation.fromNamespaceAndPath("mcacrime", "handcuffs_arms"), 0.5F, Optional.of(SUBJECT),
                Optional.empty(), false);
        RestraintView view = new RestraintView(SUBJECT, 2L, 7L, List.of(arms), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertTrue(view.restrained());
        assertEquals(arms, view.slot("ARMS").orElseThrow(), "slots are named case-insensitively");
        assertTrue(view.slot("head").isEmpty());
        assertFalse(arms.spent());
        assertEquals(1.0F, new RestraintSlotView("head", arms.definitionId(), Float.NaN,
                Optional.empty(), Optional.empty(), true).durability(),
                "a non-finite durability reads as intact rather than as a broken restraint");
    }

    @Test
    void anEmptyRestraintViewIsWellFormed() {
        RestraintView view = new RestraintView(SUBJECT, 0L, -1L, null, null, null, null);
        assertFalse(view.restrained());
        assertEquals(1L, view.generation(), "a generation is never below the first");
        assertEquals(0L, view.revision());
        assertTrue(view.slots().isEmpty());
    }

    @Test
    void theApiVersionMovedForTheNewSurface() {
        assertTrue(McaCrimeApi.getApiVersion() >= 2,
                "the physical and capital surfaces are additive, but a consumer has to be able to see "
                        + "that they exist");
    }
}
