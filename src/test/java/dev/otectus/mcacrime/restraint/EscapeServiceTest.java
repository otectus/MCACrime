package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Struggling out of a restraint, as the server decides it (0.7.5 M2.7, §3.5).
 *
 * <p>Three upstream defects are asserted away here, and one deliberate departure is asserted in.
 *
 * <ul>
 *   <li><b>The cooldown that never expires.</b> The source sets {@code breakCooldown} on a successful
 *       roll and decrements it in no code path, so a restraint that has been strained once can never
 *       be strained again — issues #20 and #26. A deadline compared against the clock cannot fail to
 *       expire, because nothing has to remember to tick it.</li>
 *   <li><b>Alternation checked on the client.</b> The source compares key codes in a client screen,
 *       which a modified client simply does not do. It is checked here, on the accepted input.</li>
 *   <li><b>A forged interval.</b> Inputs arriving faster than the configured minimum are refused
 *       rather than counted, so a macro gains nothing over a human.</li>
 *   <li><b>Unbreaking never reaching zero.</b> The source's curve is kept for parity, with a floor:
 *       a restraint nobody can ever leave is a support ticket, not a mechanic.</li>
 * </ul>
 */
class EscapeServiceTest {

    private static WorkSession session(long expiry) {
        return new WorkSession(1L, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 0L,
                null, null, expiry, RestraintSlot.ARMS);
    }

    @Test
    void unbreakingScalesTheRollDownButNeverToZero() {
        assertEquals(0.5D, EscapeService.breakChance(0), 1.0E-9D);
        // The source's curve: ((1 - level/9) * 0.7 + 0.3) * 0.5.
        assertEquals(((1.0D - 1.0D / 9.0D) * 0.7D + 0.3D) * 0.5D,
                EscapeService.breakChance(1), 1.0E-9D);
        assertTrue(EscapeService.breakChance(3) < EscapeService.breakChance(1));
        // At and beyond the point where the curve would reach zero, it floors instead.
        assertEquals(0.15D, EscapeService.breakChance(9), 1.0E-9D);
        assertEquals(0.15D, EscapeService.breakChance(255), 1.0E-9D);
        assertTrue(EscapeService.breakChance(255) > 0.0D);
    }

    @Test
    void theCooldownGrowsWithUnbreakingAndIsAlwaysFinite() {
        assertEquals(20, EscapeService.cooldownTicks(0, 0));
        assertEquals(39, EscapeService.cooldownTicks(0, 19));
        assertEquals(40, EscapeService.cooldownTicks(3, 0));
        assertTrue(EscapeService.cooldownTicks(9, 19) < 200);
    }

    @Test
    void inputsMustAlternate() {
        WorkSession work = session(1000L);
        assertTrue(work.acceptInput(1, 100L, 4, StruggleInput.ATTACK.index(), true));
        // The same input again is refused however long the player waits.
        assertFalse(work.acceptInput(2, 200L, 4, StruggleInput.ATTACK.index(), true));
        assertTrue(work.acceptInput(3, 300L, 4, StruggleInput.USE.index(), true));
        assertEquals(StruggleInput.USE.index(), work.lastInputKind());
    }

    @Test
    void aForgedIntervalIsRefused() {
        WorkSession work = session(1000L);
        assertTrue(work.acceptInput(1, 100L, 4, StruggleInput.ATTACK.index(), true));
        // Three ticks later, under the configured four.
        assertFalse(work.acceptInput(2, 103L, 4, StruggleInput.USE.index(), true));
        assertTrue(work.acceptInput(3, 104L, 4, StruggleInput.USE.index(), true));
    }

    @Test
    void aReplayedSequenceNumberCountsOnce() {
        WorkSession work = session(1000L);
        assertTrue(work.acceptInput(5, 100L, 4, StruggleInput.ATTACK.index(), true));
        assertFalse(work.acceptInput(5, 200L, 4, StruggleInput.USE.index(), true));
        assertFalse(work.acceptInput(4, 300L, 4, StruggleInput.USE.index(), true));
        assertEquals(1, work.progress());
    }

    @Test
    void theCooldownExpires() {
        WorkSession work = session(10_000L);
        assertTrue(work.acceptInput(1, 100L, 4, StruggleInput.ATTACK.index(), true));
        work.stampCooldown(100L + EscapeService.cooldownTicks(0, 0));
        // Inside the cooldown: refused even with a correct alternation and a legal interval.
        assertFalse(work.acceptInput(2, 110L, 4, StruggleInput.USE.index(), true));
        // After it: accepted. This is the assertion the source cannot make.
        assertTrue(work.acceptInput(3, 121L, 4, StruggleInput.USE.index(), true));
    }

    @Test
    void anExpiredSessionAcceptsNothingFurther() {
        WorkSession work = session(200L);
        assertTrue(work.live(199L));
        assertFalse(work.live(200L));
    }

    @Test
    void anUnknownInputIndexIsRefusedRatherThanGuessedAt() {
        assertTrue(StruggleInput.byIndex(0).isPresent());
        assertTrue(StruggleInput.byIndex(StruggleInput.values().length - 1).isPresent());
        assertTrue(StruggleInput.byIndex(-1).isEmpty());
        assertTrue(StruggleInput.byIndex(StruggleInput.values().length).isEmpty());
    }

    @Test
    void everyBreakableDefinitionHasSomewhereToGo() {
        // Release at zero happens inside the one accepted input that reached zero, so a definition
        // that is breakable must have a durability above zero to reach it from.
        for (RestraintDefinition definition : RestraintDefinitions.wearable()) {
            if (definition.escape().struggleBreakable()) {
                assertTrue(definition.escape().durability() > 0,
                        definition.id() + " is breakable but starts at zero durability");
            }
        }
    }
}
