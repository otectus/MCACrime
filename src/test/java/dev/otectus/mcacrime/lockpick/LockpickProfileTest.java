package dev.otectus.mcacrime.lockpick;

import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seven difficulty profiles, number by number (M3.3, specification §8).
 *
 * <p>These are parity values carried over from the source, so the test is a transcription of the
 * specification's own table. If one of them is ever retuned it should be a deliberate edit here as
 * well as there, which is precisely what a transcription test is for.
 */
class LockpickProfileTest {

    @Test
    void theSixProfilesCarryTheSourceNumbers() {
        assertEquals(6, LockpickProfile.values().length, "the six shipped profiles");
        assertProfile(LockpickProfile.PADLOCK, 8, 10);
        assertProfile(LockpickProfile.REINFORCED_PADLOCK, 6, 13);
        assertProfile(LockpickProfile.CELL_DOOR, 6, 14);
        assertProfile(LockpickProfile.SAFE, 3, 10);
        assertProfile(LockpickProfile.HANDCUFFS, 6, 12);
        assertProfile(LockpickProfile.SHACKLES, 8, 10);
    }

    private static void assertProfile(LockpickProfile profile, int progress, int speed) {
        assertEquals(progress, profile.progressIncrease(), profile.id() + " progress per alignment");
        assertEquals(speed, profile.speedIncrease(), profile.id() + " drain parameter");
    }

    @Test
    void theMeterBoundsAreTheSourcesOwn() {
        assertEquals(30, LockpickProfile.START_METER);
        assertEquals(40, LockpickProfile.WIN_METER);
        assertEquals(0, LockpickProfile.FAIL_METER);
    }

    @Test
    void reinforcingAPadlockChangesOnlyTheProfile() {
        assertEquals(LockpickProfile.PADLOCK, LockpickProfile.forPadlock(false));
        assertEquals(LockpickProfile.REINFORCED_PADLOCK, LockpickProfile.forPadlock(true));
        assertTrue(LockpickProfile.REINFORCED_PADLOCK.speedIncrease()
                > LockpickProfile.PADLOCK.speedIncrease(), "reinforced drains faster");
        assertTrue(LockpickProfile.REINFORCED_PADLOCK.progressIncrease()
                < LockpickProfile.PADLOCK.progressIncrease(), "and pays less per alignment");
    }

    @Test
    void theRestraintTableAgreesWithTheDefinitionsTable() {
        assertSame(RestraintDefinitions.HANDCUFFS_ARMS, LockpickProfile.HANDCUFFS);
        assertSame(RestraintDefinitions.HANDCUFFS_LEGS, LockpickProfile.HANDCUFFS);
        assertSame(RestraintDefinitions.SHACKLES_ARMS, LockpickProfile.SHACKLES);
        assertSame(RestraintDefinitions.SHACKLES_LEGS, LockpickProfile.SHACKLES);
    }

    private static void assertSame(net.minecraft.resources.ResourceLocation definitionId,
                                   LockpickProfile expected) {
        RestraintDefinition definition = RestraintDefinitions.get(definitionId).orElseThrow();
        LockpickProfile.Pick pick = LockpickProfile.of(definition).orElseThrow();
        assertEquals(expected.pick(), pick, definitionId + " must use the " + expected.id() + " numbers");
    }

    @Test
    void somethingWithNoLockCannotBePicked() {
        for (net.minecraft.resources.ResourceLocation id : java.util.List.of(
                RestraintDefinitions.DUCK_TAPE_ARMS,
                RestraintDefinitions.DUCK_TAPE_LEGS,
                RestraintDefinitions.DUCK_TAPE_HEAD,
                RestraintDefinitions.BUNDLE,
                RestraintDefinitions.PILLORY)) {
            RestraintDefinition definition = RestraintDefinitions.get(id).orElseThrow();
            Optional<LockpickProfile.Pick> pick = LockpickProfile.of(definition);
            assertTrue(pick.isEmpty(), id + " has no lock, so a pick has nothing to work on");
        }
        assertFalse(LockpickProfile.of(null).isPresent());
    }

    @Test
    void profilesAreLookedUpByTheirStableIds() {
        assertEquals(Optional.of(LockpickProfile.SAFE), LockpickProfile.byId("safe"));
        assertEquals(Optional.of(LockpickProfile.CELL_DOOR), LockpickProfile.byId("CELL_door"));
        assertTrue(LockpickProfile.byId("nothing").isEmpty());
        assertTrue(LockpickProfile.byId(null).isEmpty());
    }
}
