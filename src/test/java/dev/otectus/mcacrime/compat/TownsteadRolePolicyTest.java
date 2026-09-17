package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.compat.TownsteadRolePolicy.Role;
import dev.otectus.mcacrime.compat.TownsteadRolePolicy.Stance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may be drafted into the guard, once a settlement mod has opinions about it.
 *
 * <h2>The asymmetry this is really testing</h2>
 *
 * <p>Guard recruitment is destructive: converting a villager overwrites their profession, and there is
 * no pass anywhere in this mod that converts one back. So the two mistakes are not equally bad. A
 * village left briefly short of guards recovers on the next cooldown; a village whose baker was
 * drafted does not recover at all without a player noticing and fixing it by hand.
 *
 * <p>Every case below is a statement of that asymmetry. The one worth reading twice is
 * {@link #anUnreadableRoleIsNotAnAvailableOne()} — the whole failure mode is that "Townstead could not
 * tell me" is quietly treated as "nobody objected".
 */
class TownsteadRolePolicyTest {

    /** A villager with a Townstead trade is off limits whatever the clock says. */
    @Test
    void anEmployedVillagerIsProtectedEvenOffShift() {
        Role role = TownsteadRolePolicy.decide(true, "rest", true, "townstead:baker");
        assertSame(Stance.PROTECTED, role.stance());
        assertTrue(role.protectedWorker());
        assertTrue(role.roleKnown());
        assertFalse(role.halts());
        assertTrue(role.reason().contains("townstead:baker"), role.reason());
    }

    /**
     * An unemployed villager on a work shift is still producing something.
     *
     * <p>Townstead runs work through the schedule as well as through professions, so a villager with no
     * profession id can still be on shift — and drafting them takes them off it mid-task.
     */
    @Test
    void aVillagerOnShiftIsProtectedWithoutAProfession() {
        Role role = TownsteadRolePolicy.decide(true, "work", true, "");
        assertSame(Stance.PROTECTED, role.stance());
        assertTrue(role.protectedWorker());
    }

    /** Idle, unemployed, and known to be both. This is the villager the pass exists to convert. */
    @Test
    void anIdleUnemployedVillagerIsAvailable() {
        Role role = TownsteadRolePolicy.decide(true, "idle", true, "");
        assertSame(Stance.AVAILABLE, role.stance());
        assertFalse(role.protectedWorker());
        assertTrue(role.roleKnown());
        assertFalse(role.halts());
    }

    /** Resting is not working. A villager asleep at night is a legitimate recruit. */
    @Test
    void restingIsNotWorking() {
        assertSame(Stance.AVAILABLE, TownsteadRolePolicy.decide(true, "rest", true, "").stance());
        assertSame(Stance.AVAILABLE, TownsteadRolePolicy.decide(true, "meet", true, "").stance());
    }

    /** Townstead's own shift names arrive lowercased at the seam, but the rule does not rely on that. */
    @Test
    void theActivityComparisonIsCaseInsensitive() {
        assertSame(Stance.PROTECTED, TownsteadRolePolicy.decide(true, "WORK", true, "").stance());
    }

    /**
     * The case the whole class exists for.
     *
     * <p>Half a reading is not a reading. A schedule with no profession cannot tell an off-shift baker
     * from an unemployed villager; a profession with no schedule cannot tell one at work from one
     * asleep. Either way the answer is UNKNOWN, and UNKNOWN halts.
     */
    @Test
    void anUnreadableRoleIsNotAnAvailableOne() {
        for (Role role : new Role[]{
                TownsteadRolePolicy.decide(false, "idle", true, ""),
                TownsteadRolePolicy.decide(true, "idle", false, ""),
                TownsteadRolePolicy.decide(false, "", false, null)}) {
            assertSame(Stance.UNKNOWN, role.stance());
            assertFalse(role.protectedWorker(), "an unknown role must not read as protected either");
            assertFalse(role.roleKnown());
            assertTrue(role.halts(), "recruitment must stop rather than guess");
        }
    }

    /** A blank profession id is no profession; whitespace is not a trade. */
    @Test
    void aBlankProfessionIsNoProfession() {
        assertSame(Stance.AVAILABLE, TownsteadRolePolicy.decide(true, "idle", true, "   ").stance());
    }

    /**
     * The switch being off is not a gap in information.
     *
     * <p>The one distinction the {@code NOT_ENFORCED} stance exists to make: nobody asked for the
     * protection, so nothing is being withheld and recruitment carries on. That is the opposite of
     * {@link Stance#UNKNOWN}, which is a genuine gap and stops the pass.
     *
     * <p>Stated over the record rather than over {@code enforced()}, because whether the switch reads
     * as on is a fact about the environment rather than about the rule: these tests run under
     * ModDevGradle's NeoForge runner, which boots the mod loader and loads the common config, so the
     * defaults are live here in a way they are not in the Forge 1.20.1 baseline's plain JUnit run.
     */
    @Test
    void theSwitchBeingOffIsNotTheSameAsNotKnowing() {
        Role role = new Role(Stance.NOT_ENFORCED, "protectWorkerAssignments is off");
        assertFalse(role.protectedWorker());
        assertTrue(role.roleKnown());
        assertFalse(role.halts());
    }

    /**
     * No Townstead means no protection, whatever the two switches say.
     *
     * <p>The regression this pins is the one that hurt: {@code enforced()} used to read only the two
     * config values, so on a server with no Townstead at all — where both default to on — every
     * villager classified UNKNOWN, {@code GuardPopulationService.assess} set {@code halted}, and
     * automatic guard recruitment stopped in every village in the world. The policy has to distinguish
     * "the settlement mod would not tell me" from "there is no settlement mod to ask", and only the
     * first of those is allowed to stop anything.
     *
     * <p>This case bites harder here than in the Forge baseline. The ModDevGradle runner loads the
     * common config, so both switches genuinely read as on in this JVM — exactly the configuration
     * that used to halt recruitment - and the only thing keeping {@code enforced()} false is the
     * absence check being tested.
     *
     * <p>{@code TownsteadBridge.reset()} leaves the bridge in its {@code ABSENT} state, which is what a
     * server without Townstead has and what a unit-test JVM has for the same reason.
     */
    @Test
    void anAbsentTownsteadIsNotEnforcedAndNeverHalts() {
        TownsteadBridge.reset();
        assertSame(TownsteadBridge.State.ABSENT, TownsteadBridge.state());
        assertFalse(TownsteadRolePolicy.townsteadPresent());
        assertFalse(TownsteadRolePolicy.enforced(),
                "with no Townstead installed the worker protection must not be enforced at all");

        Role role = TownsteadRolePolicy.of(null);
        assertSame(Stance.NOT_ENFORCED, role.stance());
        assertFalse(role.protectedWorker());
        assertTrue(role.roleKnown(), "absent is a decision, not a gap in information");
        assertFalse(role.halts(), "recruitment must behave exactly as it did before Townstead existed");
        assertTrue(role.reason().toLowerCase(java.util.Locale.ROOT).contains("townstead"), role.reason());
    }
}
