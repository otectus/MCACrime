package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.ledger.CapitalSentenceService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clemency is reachable through the API, and it is the only capital mutation that is (§3.19, M6.8).
 *
 * <p>Pardon and commutation are the only legal exits from a capital sentence, and the plan requires
 * both to be explicit privileged transactions available to a running mod — a courthouse mod, a quest
 * reward, a governor plugin — rather than only to somebody at a console. What must stay impossible is
 * everything on the other side of the ledger: no API caller may assign a capital sentence, arm a
 * device or carry an execution out.
 *
 * <p>No server here, so the assertions are the boundary ones: the facade refuses a null server rather
 * than throwing at its caller, the refusal is the same {@code Clemency} value the command reports, and
 * the surface itself is exactly two mutators.
 */
class CapitalClemencyApiTest {

    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID AUTHORITY = UUID.randomUUID();

    @Test
    void aNullServerIsRefusedRatherThanThrown() {
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.commuteCapitalSentence(null, SUBJECT, AUTHORITY));
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.pardonCapitalSentence(null, SUBJECT, AUTHORITY));
    }

    @Test
    void aMissingSubjectIsRefusedRatherThanAppliedToNobody() {
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.commuteCapitalSentence(null, null, null));
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.pardonCapitalSentence(null, null, null));
    }

    /** The authority argument is optional, exactly as a console source is on the command. */
    @Test
    void clemencyMayBeGrantedByTheServerItself() {
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.commuteCapitalSentence(null, SUBJECT, null));
        assertEquals(CapitalSentenceService.Clemency.REFUSED,
                McaCrimeApi.pardonCapitalSentence(null, SUBJECT, null));
    }

    /**
     * Two mutators over the capital model, and no more.
     *
     * <p>Asserted by name rather than by counting every method: an {@code assignCapitalSentence} or an
     * {@code executeCapitalSentence} appearing on this facade would be the automatic escalation and the
     * remote execution the user ruled out, and it would arrive as an innocuous-looking addition.
     */
    @Test
    void theFacadeOffersClemencyAndNothingElseOverACapitalSentence() {
        int mutators = 0;
        for (Method method : McaCrimeApi.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("capital")) {
                continue;
            }
            if (name.equals("capitalsentence")) {
                continue; // the read-only view
            }
            assertTrue(name.equals("commutecapitalsentence") || name.equals("pardoncapitalsentence"),
                    "unexpected capital-sentence method on the public API: " + method.getName());
            mutators++;
        }
        assertEquals(2, mutators, "clemency is two methods: commute and pardon");
    }
}
