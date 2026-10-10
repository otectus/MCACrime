package dev.otectus.mcacrime.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The always-loadable facade (0.7.8): total, never throwing, and honest about what can be asked. */
class CapitalsBridgeTest {

    @AfterEach
    void release() {
        CapitalsBridge.release();
    }

    /** A bridge that answers from fixed capabilities and throws from every query. */
    private static CapitalsBridge.Ops ops(Set<CapitalsCapability> capabilities) {
        return new CapitalsBridge.Ops() {
            @Override public CapitalsBridge.State state() { return CapitalsBridge.State.PARTIAL; }
            @Override public Set<CapitalsCapability> capabilities() { return capabilities; }
            @Override public String detectedVersion() { return "test"; }
            @Override public List<String> unresolvedMembers() { return List.of(); }
            @Override public CapitalsQueryResult<CapitalView> capitalAt(net.minecraft.server.level.ServerLevel l, int v) { throw new IllegalStateException(); }
            @Override public CapitalsQueryResult<Boolean> anyCapitals() { throw new IllegalStateException("boom"); }
            @Override public CapitalsQueryResult<CapitalStanding> standing(net.minecraft.server.level.ServerLevel l, int v, UUID c, UUID p) { throw new IllegalStateException(); }
            @Override public CapitalsQueryResult<CapitalCourtView> court(net.minecraft.server.level.ServerLevel l, int v, UUID c) { throw new IllegalStateException(); }
            @Override public CapitalsQueryResult<Boolean> royalGuard(net.minecraft.server.level.ServerLevel l, int v, UUID c, UUID g) { throw new IllegalStateException(); }
            @Override public CapitalsQueryResult<Boolean> chronicle(net.minecraft.server.level.ServerLevel l, int v, UUID c, String e, boolean h) { throw new IllegalStateException(); }
            @Override public CapitalsQueryResult<Boolean> playerWarrant(net.minecraft.server.level.ServerLevel l, int v, UUID c, UUID p) { throw new IllegalStateException(); }
        };
    }

    @Test
    void withNothingBoundEveryQueryIsUnavailableNeverAFact() {
        assertFalse(CapitalsBridge.decreesReady());
        assertTrue(CapitalsBridge.capitalAt(null, 1) instanceof CapitalsQueryResult.Unavailable<CapitalView>);
        assertTrue(CapitalsBridge.anyCapitals() instanceof CapitalsQueryResult.Unavailable<Boolean>);
        assertTrue(CapitalsBridge.standing(null, 1, UUID.randomUUID(), UUID.randomUUID())
                instanceof CapitalsQueryResult.Unavailable<CapitalStanding>);
        assertEquals(CapitalsBridge.DECREE_CAPABILITIES, CapitalsBridge.missingForDecrees());
    }

    @Test
    void decreesNeedRegistryAuthorityAndTitlesTogether() {
        CapitalsBridge.installForTest(ops(EnumSet.of(CapitalsCapability.REGISTRY, CapitalsCapability.AUTHORITY)));
        assertFalse(CapitalsBridge.decreesReady());
        assertEquals(Set.of(CapitalsCapability.TITLES), CapitalsBridge.missingForDecrees());
        CapitalsBridge.installForTest(ops(EnumSet.allOf(CapitalsCapability.class)));
        assertTrue(CapitalsBridge.decreesReady());
        assertTrue(CapitalsBridge.missingForDecrees().isEmpty());
    }

    @Test
    void anAdapterThatThrowsAnswersFailedAndNeverPropagates() {
        CapitalsBridge.installForTest(ops(EnumSet.allOf(CapitalsCapability.class)));
        CapitalsQueryResult<Boolean> any = CapitalsBridge.anyCapitals();
        assertTrue(any instanceof CapitalsQueryResult.Failed<Boolean>, any.describe());
        assertFalse(any.answered(), "a failure is never an answer");
    }

    @Test
    void aMissingLevelOrIdIsRefusedBeforeTheAdapterIsAsked() {
        CapitalsBridge.installForTest(ops(EnumSet.allOf(CapitalsCapability.class)));
        assertTrue(CapitalsBridge.capitalAt(null, 1) instanceof CapitalsQueryResult.Unavailable<CapitalView>);
        assertTrue(CapitalsBridge.chronicle(null, 1, UUID.randomUUID(), "x", false)
                instanceof CapitalsQueryResult.Unavailable<Boolean>);
        assertTrue(CapitalsBridge.playerWarrant(null, 1, null, UUID.randomUUID())
                instanceof CapitalsQueryResult.Unavailable<Boolean>);
    }

    @Test
    void theStandingOfAPlayerIsAboutThisCapitalOnly() {
        CapitalStanding foreignDuke = new CapitalStanding("STRANGER", null, null, null);
        assertFalse(foreignDuke.noble(), "a duke of another capital is a stranger here");
        assertTrue(new CapitalStanding("commoner", "knight", null, null).noble(), "names are case-insensitive");
        assertEquals("KNIGHT", new CapitalStanding("COMMONER", "knight", null, null).displayName());
        assertEquals("COMMONER", new CapitalStanding("COMMONER", "COMMONER", "", null).displayName());
    }
}
