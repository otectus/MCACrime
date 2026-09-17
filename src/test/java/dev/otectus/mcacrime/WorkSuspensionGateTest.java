package dev.otectus.mcacrime;

import dev.otectus.mcacrime.activity.CrimeActivityOperation;
import dev.otectus.mcacrime.activity.CrimeActivityRegistry;
import dev.otectus.mcacrime.activity.CrimeActivityView;
import dev.otectus.mcacrime.ai.thief.ThiefState;
import dev.otectus.mcacrime.ai.thief.ThiefWorkGate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work suspension, generalised from "employed Thieves" to "anybody MCA: Crime is acting on".
 *
 * <p>The gate was written for a Thief's own station work, where MCA: Crime installed the behaviour and
 * knew what it was. The same wrapper now has to stop work behaviours this mod did not install and does
 * not recognise — vanilla's, MCA's, a settlement companion's — for a villager under an enforcement
 * claim. Two things have to remain true while it does that:
 *
 * <ul>
 *   <li>An <b>unclaimed</b> villager behaves exactly as they did, so a world with no crime in it is
 *       byte-for-byte the world it was.</li>
 *   <li>The original five Thief conditions are unchanged, so nothing about the 0.7.2 behaviour moved
 *       while the gate was being widened.</li>
 * </ul>
 *
 * <p>Note what is <em>not</em> asserted: that a running behaviour stops cleanly. This is start-gating.
 * A task with staged materials finishes them, because reconciling somebody else's half-done recipe from
 * the outside is not something MCA: Crime can do correctly.
 */
class WorkSuspensionGateTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private UUID villager;

    @BeforeEach
    void reset() {
        CrimeActivityRegistry.clearAll();
        villager = UUID.randomUUID();
    }

    @AfterEach
    void tidy() {
        CrimeActivityRegistry.clearAll();
    }

    /** The question the wrapper asks, expressed the way the live gate asks it. */
    private boolean workBlocked() {
        return !CrimeActivityRegistry.permits(villager, CrimeActivityOperation.WORK_START);
    }

    @Test
    void anUnclaimedVillagerStartsWorkExactlyAsBefore() {
        assertFalse(workBlocked(), "nothing claimed, nothing gated");
        assertFalse(ThiefWorkGate.shouldYield(false, false, false, false, false, workBlocked()),
                "a villager nobody is acting on works as vanilla, MCA or a companion wrote it");
    }

    @Test
    void aClaimedVillagerDoesNotStartWork() {
        CrimeActivityRegistry.claim(villager, OVERWORLD, CrimeActivityView.Kind.ARREST, "arrest", 0L, 40);
        CrimeActivityRegistry.sweep(0L);

        assertTrue(workBlocked(), "every claim kind yields WORK_START");
        assertTrue(ThiefWorkGate.shouldYield(false, false, false, false, false, workBlocked()),
                "a villager being arrested does not walk off to a workbench");
    }

    @Test
    void everyClaimKindGatesWork() {
        for (CrimeActivityView.Kind kind : CrimeActivityView.Kind.values()) {
            CrimeActivityRegistry.clearAll();
            CrimeActivityRegistry.claim(villager, OVERWORLD, kind, "test", 0L, 40);
            CrimeActivityRegistry.sweep(0L);
            assertTrue(workBlocked(), kind + " must gate work");
        }
    }

    @Test
    void theGateReopensWhenTheClaimLapses() {
        CrimeActivityRegistry.claim(villager, OVERWORLD, CrimeActivityView.Kind.CHALLENGE, "challenge", 0L, 40);
        CrimeActivityRegistry.sweep(0L);
        assertTrue(workBlocked());

        CrimeActivityRegistry.sweep(40L);
        assertFalse(workBlocked(), "a lease that lapsed hands the villager straight back to their day");
        assertFalse(ThiefWorkGate.shouldYield(false, false, false, false, false, workBlocked()));
    }

    @Test
    void theOriginalThiefConditionsAreUnchanged() {
        assertFalse(ThiefWorkGate.shouldYield(false, false, false, false, false), "nothing in the way");
        assertTrue(ThiefWorkGate.shouldYield(true, false, false, false, false), "custody");
        assertTrue(ThiefWorkGate.shouldYield(false, true, false, false, false), "unable to act");
        assertTrue(ThiefWorkGate.shouldYield(false, false, true, false, false), "panic");
        assertTrue(ThiefWorkGate.shouldYield(false, false, false, true, false), "enforcement");
        assertTrue(ThiefWorkGate.shouldYield(false, false, false, false, true), "an active crime action");

        assertFalse(ThiefWorkGate.crimeActive(ThiefState.IDLE));
        assertFalse(ThiefWorkGate.crimeActive(ThiefState.COOLDOWN));
        assertTrue(ThiefWorkGate.crimeActive(ThiefState.MUGGING));
    }

    @Test
    void theSixArgumentFormIsTheFiveArgumentOnePlusTheClaim() {
        for (int mask = 0; mask < 32; mask++) {
            boolean captive = (mask & 1) != 0;
            boolean incapable = (mask & 2) != 0;
            boolean panicking = (mask & 4) != 0;
            boolean enforcement = (mask & 8) != 0;
            boolean crime = (mask & 16) != 0;

            boolean original = ThiefWorkGate.shouldYield(captive, incapable, panicking, enforcement, crime);
            assertTrue(ThiefWorkGate.shouldYield(captive, incapable, panicking, enforcement, crime, true),
                    "a live claim always yields, mask " + mask);
            assertTrue(original
                            == ThiefWorkGate.shouldYield(captive, incapable, panicking, enforcement, crime, false),
                    "with no claim the answer is exactly the old one, mask " + mask);
        }
    }
}
