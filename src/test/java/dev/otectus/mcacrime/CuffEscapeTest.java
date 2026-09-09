package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.enforcement.ArrestState;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CuffEscapeTest {
    @Test void defaultConfigDoesNotRequireAnItemAndLockedTimedWorkIsFinite() {
        assertFalse(McaCrimeConfig.COMMON.cuffEscapeRequiresLockpick.getDefault());
        assertEquals(1200, McaCrimeConfig.COMMON.escapeWorkTicksLockedCuffs.getDefault());
    }
    @Test void aMissResetsProgressAndOnlyTheFinalCorrectPinCompletes() {
        CuffLockProgress p = new CuffLockProgress();
        assertTrue(p.accept(2, 3, 1, 10));
        assertFalse(p.resolve(true, 3));
        assertTrue(p.accept(1, 3, 2, 12));
        assertFalse(p.resolve(false, 3));
        assertEquals(0, p.solved());
        for (int i = 0; i < 3; i++) {
            assertTrue(p.accept(i, 3, 3 + i, 14 + i * 2));
            assertEquals(i == 2, p.resolve(true, 3));
        }
        assertTrue(p.completed());
        assertFalse(p.accept(0, 3, 6, 50));
        assertFalse(p.resolve(true, 3), "a replay cannot complete twice");
    }

    @Test void rejectsReplayOutOfRangeAndBurstAttemptsWithoutAdvancing() {
        CuffLockProgress p = new CuffLockProgress();
        assertFalse(p.accept(-1, 5, 1, 10));
        assertFalse(p.accept(5, 5, 1, 10));
        assertTrue(p.accept(2, 5, 1, 10));
        p.resolve(true, 5);
        assertFalse(p.accept(3, 5, 1, 20));
        assertFalse(p.accept(3, 5, 0, 20));
        assertFalse(p.accept(3, 5, 2, 11));
        assertEquals(1, p.solved());
        assertTrue(p.accept(3, 5, 2, 12));
    }

    @Test void requiredPickAndSessionAuthorityAreIndependentGates() {
        assertTrue(CuffEscapeService.mayAttempt(true, true, true, true, false, false));
        assertTrue(CuffEscapeService.mayAttempt(true, true, true, true, true, true));
        assertFalse(CuffEscapeService.mayAttempt(true, true, true, true, true, false));
        assertFalse(CuffEscapeService.mayAttempt(false, true, true, true, false, true));
        assertFalse(CuffEscapeService.mayAttempt(true, false, true, true, false, true));
        assertFalse(CuffEscapeService.mayAttempt(true, true, false, true, false, true));
        assertFalse(CuffEscapeService.mayAttempt(true, true, true, false, false, true));
        assertTrue(CuffEscapeService.isCuff(RestraintType.CUFFS));
        assertTrue(CuffEscapeService.isCuff(RestraintType.LOCKED_CUFFS));
        assertFalse(CuffEscapeService.isCuff(RestraintType.ROPE));
    }

    @Test void custodyPreservesItsSecretWithoutExposingMutableAliases() {
        CustodyRecord c = new CustodyRecord(UUID.randomUUID(), true, false, CustodyOwner.none(),
                RestraintType.CUFFS, 0, null, null);
        byte[] secret = {2, 0, 1};
        c.setCuffCombination(secret);
        secret[0] = 1;
        c.getCuffCombination()[0] = 0;
        assertArrayEquals(new byte[] {2, 0, 1}, c.copy().getCuffCombination());
        assertArrayEquals(new byte[] {2, 0, 1}, CustodyRecord.load(c.save()).getCuffCombination());
        c.setCuffCombination(new byte[] {1, 1, 2});
        assertEquals(0, c.getCuffCombination().length);
        assertEquals(0, CustodyRecord.load(new CompoundTag()).getCuffCombination().length);
    }

    @Test void lawfulCuffEscapeSurvivesSaveAndPausesUntilRecapture() {
        JailState sentence = new JailState();
        sentence.setRemainingOnlineTicks(800);
        sentence.setSurrenderCredited(true);
        sentence.escapeCuffs();
        JailState saved = JailState.load(sentence.save());
        assertTrue(saved.isCuffEscape());
        assertTrue(saved.copy().isCuffEscape());
        assertEquals(sentence.getSentenceId(), saved.getSentenceId());
        assertNull(JailService.advanceTick(saved, 1000));
        assertEquals(800, saved.getRemainingOnlineTicks());
        saved.setEscaped(false);
        assertFalse(saved.isCuffEscape());
        JailService.advanceTick(saved, 1000);
        assertEquals(799, saved.getRemainingOnlineTicks());
        assertTrue(saved.isSurrenderCredited());
    }

    @Test void anEscortRetainsItsSurrenderCreditAcrossRestart() {
        ArrestState arrest = new ArrestState();
        arrest.setSurrenderCredited(true);
        assertTrue(arrest.copy().isSurrenderCredited());
        assertTrue(ArrestState.load(arrest.save()).isSurrenderCredited());
        assertFalse(ArrestState.load(new CompoundTag()).isSurrenderCredited());
    }
}
