package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.compat.TownsteadMixinStatus.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two facts the Townstead mixin layer reports about itself, and the difference between them.
 *
 * <p>Everything downstream of this class — the capability the bridge advertises, the word
 * {@code /crime debug townstead} prints, whether {@code /crime validate} calls a switch degraded —
 * rests on "applied" and "fired" being separate. A registry that conflated them would report a mixin
 * whose injection point had moved as fully working, which is the precise failure the layer's
 * {@code require = 0} makes possible and this record exists to expose.
 */
class TownsteadMixinStatusTest {

    @BeforeEach
    @AfterEach
    void reset() {
        TownsteadMixinStatus.clear();
    }

    @Test
    void nothingIsAppliedOrFiredByDefault() {
        assertFalse(TownsteadMixinStatus.anyApplied());
        assertFalse(TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_GUARD_REST_YIELD));
        assertFalse(TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_GUARD_REST));
        assertEquals(new Snapshot(java.util.Set.of(), java.util.Set.of()), TownsteadMixinStatus.snapshot());
    }

    /**
     * The plugin knows the qualified name; every reader asks with the simple one.
     *
     * <p>Storing both is not a convenience — {@code postApply} is handed
     * {@code dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin} and the diagnostics line is
     * written in terms of {@code GuardRestYieldMixin}, so a registry that stored only what it was
     * given would answer "not applied" to every question anybody actually asks.
     */
    @Test
    void anAppliedMixinAnswersToBothItsNames() {
        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin");

        assertTrue(TownsteadMixinStatus.isApplied("GuardRestYieldMixin"));
        assertTrue(TownsteadMixinStatus.isApplied(
                "dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin"));
        assertTrue(TownsteadMixinStatus.anyApplied());
    }

    /** Applied is not fired. This is the state that has to read as degraded, not as available. */
    @Test
    void appliedWithoutAFiredHookIsNotInjected() {
        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.ReactionLockGateMixin");

        assertTrue(TownsteadMixinStatus.isApplied(TownsteadMixinStatus.MIXIN_REACTION_LOCK_GATE));
        assertFalse(TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_REACTION_LOCK));
    }

    @Test
    void aFiredHookIsRecordedOnceAndStaysRecorded() {
        TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_REACTION_LOCK);
        TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_REACTION_LOCK);

        assertTrue(TownsteadMixinStatus.isInjected(TownsteadMixinStatus.HOOK_REACTION_LOCK));
        assertEquals(1, TownsteadMixinStatus.snapshot().injected().size());
    }

    /** Written from inside somebody else's transformed method: it must never throw, ever. */
    @Test
    void junkIsIgnoredRatherThanThrown() {
        TownsteadMixinStatus.applied(null);
        TownsteadMixinStatus.applied("  ");
        TownsteadMixinStatus.injected(null);
        TownsteadMixinStatus.injected("");

        assertFalse(TownsteadMixinStatus.anyApplied());
        assertFalse(TownsteadMixinStatus.isApplied(null));
        assertFalse(TownsteadMixinStatus.isInjected(null));
        assertTrue(TownsteadMixinStatus.snapshot().applied().isEmpty());
    }

    /**
     * The four words an operator reads, and the three states behind them.
     *
     * <p>This is the whole point of keeping "applied" and "fired" apart, spelled out at the surface
     * where it is acted on. Nothing applied is {@code unavailable}; applied but never reached is
     * {@code degraded}, because it is indistinguishable from an injection point that moved; both
     * applied and both reached is the only state that may be called {@code available}.
     */
    @Test
    void activityCoordinationReportsAppliedAndFiredSeparately() {
        assertEquals(TownsteadDiagnostics.UNAVAILABLE,
                TownsteadDiagnostics.describe(TownsteadCapability.ACTIVITY_COORDINATION));

        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.ReactionLockGateMixin");
        assertEquals(TownsteadDiagnostics.UNAVAILABLE,
                TownsteadDiagnostics.describe(TownsteadCapability.ACTIVITY_COORDINATION),
                "half the layer is not a degraded version of it; the guard keeps standing still");

        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin");
        assertEquals(TownsteadDiagnostics.DEGRADED_MIXIN_UNOBSERVED,
                TownsteadDiagnostics.describe(TownsteadCapability.ACTIVITY_COORDINATION));

        TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_REACTION_LOCK);
        TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_GUARD_REST);
        assertEquals(TownsteadDiagnostics.AVAILABLE_MIXIN,
                TownsteadDiagnostics.describe(TownsteadCapability.ACTIVITY_COORDINATION));
    }

    /**
     * The capability the config validator reads is "installed", not "has fired".
     *
     * <p>{@code ConfigValidator} runs at setup, before any villager has moved. Were the bridge to wait
     * for a handler to run, every boot would report {@code automaticShiftAssignment} as DEGRADED and
     * nothing would ever correct it.
     */
    @Test
    void theBridgeReportsTheCapabilityAsSoonAsBothMixinsApply() {
        assertFalse(TownsteadBridge.has(TownsteadCapability.ACTIVITY_COORDINATION));

        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.ReactionLockGateMixin");
        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin");

        assertTrue(TownsteadBridge.has(TownsteadCapability.ACTIVITY_COORDINATION),
                "the hooks are installed; whether one has been reached yet is a separate line");
    }

    /**
     * Work suspension is never "available" and never "off".
     *
     * <p>MCA: Crime refuses a work behaviour before it starts and cannot interrupt one that has
     * committed to a recipe. Both halves of that are true at once, and the report has to say so
     * whether or not any mixin applied — the gate is MCA: Crime's own vanilla brain hook, not a
     * Townstead one.
     */
    @Test
    void workSuspensionIsAlwaysReportedAsStartGateOnly() {
        assertEquals(TownsteadDiagnostics.DEGRADED_START_GATE,
                TownsteadDiagnostics.describe(TownsteadCapability.WORK_SUSPENSION));

        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin");

        assertEquals(TownsteadDiagnostics.DEGRADED_START_GATE,
                TownsteadDiagnostics.describe(TownsteadCapability.WORK_SUSPENSION));
    }

    /** A read capability nothing bound is plainly unavailable, with no mixin vocabulary attached. */
    @Test
    void aReadCapabilityKeepsTheApiWording() {
        assertEquals(TownsteadDiagnostics.UNAVAILABLE,
                TownsteadDiagnostics.describe(TownsteadCapability.READ_NEEDS));
        assertEquals(TownsteadDiagnostics.UNAVAILABLE, TownsteadDiagnostics.describe(null));
    }

    @Test
    void theSnapshotDescribesOnlySimpleNames() {
        TownsteadMixinStatus.applied("dev.otectus.mcacrime.mixin.townstead.GuardRestYieldMixin");
        TownsteadMixinStatus.injected(TownsteadMixinStatus.HOOK_GUARD_REST);

        String described = TownsteadMixinStatus.snapshot().describe();

        assertEquals("applied=[GuardRestYieldMixin] fired=[guard_rest]", described);
    }
}
