package dev.otectus.mcacrime;

import dev.otectus.mcacrime.ai.ReactionSpeedModifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the speed-modifier id.
 *
 * <p>Not a tautology: the modifier is applied to a live villager and removed by id later, so changing
 * the constant between two builds of the same world orphans every modifier applied by the old one.
 * Orphaned attribute modifiers are invisible and permanent, which is exactly the class of bug nobody
 * reports and nobody can reproduce. A literal comparison here makes the change deliberate.
 */
class ReactionSpeedModifierIdTest {

    @Test
    void theModifierIdIsStableAcrossReleases() {
        assertEquals(UUID.fromString("7f3d1c86-4a2e-4c19-9f5b-2d0a6c8e51b4"), ReactionSpeedModifier.MODIFIER_ID);
    }

    @Test
    void theMultiplyTotalAmountIsTheMultiplierMinusOne() {
        // A frozen victim needs a full -1.0: MULTIPLY_TOTAL with anything less still leaves them able
        // to walk out of the mugging, and a sign error here would make them faster instead.
        assertEquals(-1.0D, ReactionSpeedModifier.amountFor(0.0D), 1.0E-9);
        assertEquals(-0.35D, ReactionSpeedModifier.amountFor(0.65D), 1.0E-9);
    }
}
