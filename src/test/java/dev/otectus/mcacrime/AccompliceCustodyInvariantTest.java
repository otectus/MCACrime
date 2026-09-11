package dev.otectus.mcacrime;

import dev.otectus.mcacrime.captivity.NpcReleaseEffects;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What letting a villager out of custody does, in order — and what it must never do.
 *
 * <p>The promise the whole accomplice and thief custody design rests on is that the villager taken is
 * the villager returned: same entity, same identity, same inventory, same family. There is therefore
 * no "remove the entity" step for this to assert the absence of; {@link NpcReleaseEffects.Effects}
 * cannot express one. What is left to pin is the ordering, which is easy to get wrong and invisible
 * when it is: the lead has to come off before MCA's control does, and the family are told last.
 */
class AccompliceCustodyInvariantTest {

    /** Records the order the release steps were taken in. */
    private static final class Recorder implements NpcReleaseEffects.Effects {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void clearLeash() {
            calls.add("leash");
        }

        @Override
        public void releaseControl() {
            calls.add("control");
        }

        @Override
        public void clearDistraction() {
            calls.add("distraction");
        }

        @Override
        public void notifyFamily() {
            calls.add("family");
        }
    }

    @Test
    void aLoadedLawfulCaptiveIsUnleashedThenHandedBackThenUndistractedThenAnnounced() {
        Recorder recorder = new Recorder();
        NpcReleaseEffects.apply(recorder, true, true);
        assertEquals(List.of("leash", "control", "distraction", "family"), recorder.calls);
    }

    @Test
    void aKidnappingReleaseTellsNobodyBecauseNobodyWasTold() {
        Recorder recorder = new Recorder();
        NpcReleaseEffects.apply(recorder, true, false);
        assertEquals(List.of("leash", "control", "distraction"), recorder.calls);
    }

    @Test
    void anUnloadedCaptiveStillHasItsDistractionCleared() {
        // The effect is keyed on a uuid and lives in memory; an unloaded chunk is not a reason to
        // leave somebody's witness radius shrunk by a relative who is no longer anywhere.
        Recorder recorder = new Recorder();
        NpcReleaseEffects.apply(recorder, false, true);
        assertEquals(List.of("distraction", "family"), recorder.calls);
    }

    @Test
    void aNullEffectSetIsANoOpRatherThanAThrow() {
        NpcReleaseEffects.apply(null, true, true);
        assertTrue(true, "release must never be the thing that throws");
    }
}
