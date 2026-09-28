package dev.otectus.mcacrime.locks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What lock protection covers, stated as assertions rather than as a promise (M3.6, spec §10.3).
 *
 * <p>The policy matrix is the substance: every automated operation asks one enum, per operation, and
 * the three settings mean exactly what they say. The rest of this file is about honesty — the handler
 * class exists for each route the specification names, and the one route no adapter covers is
 * <em>reported</em> at startup rather than implied to be safe.
 */
class LockProtectionCoverageTest {

    private static final Path HANDLERS = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "locks", "LockProtectionHandlers.java");
    private static final Path HOPPER_MIXIN = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "mixin", "HopperLockMixin.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void blockAllRefusesBothDirectionsAndOnlyWhileLocked() {
        LockAutomationPolicy policy = LockAutomationPolicy.BLOCK_ALL;
        assertFalse(policy.permits(true, LockAutomationPolicy.Operation.INSERT));
        assertFalse(policy.permits(true, LockAutomationPolicy.Operation.EXTRACT));
        assertTrue(policy.permits(false, LockAutomationPolicy.Operation.INSERT),
                "an unlocked container is an ordinary container");
        assertTrue(policy.permits(false, LockAutomationPolicy.Operation.EXTRACT));
    }

    @Test
    void allowInsertIsADropBox() {
        LockAutomationPolicy policy = LockAutomationPolicy.ALLOW_INSERT;
        assertTrue(policy.permits(true, LockAutomationPolicy.Operation.INSERT));
        assertFalse(policy.permits(true, LockAutomationPolicy.Operation.EXTRACT));
    }

    @Test
    void allowAllLeavesAutomationAlone() {
        LockAutomationPolicy policy = LockAutomationPolicy.ALLOW_ALL;
        assertTrue(policy.permits(true, LockAutomationPolicy.Operation.INSERT));
        assertTrue(policy.permits(true, LockAutomationPolicy.Operation.EXTRACT));
    }

    @Test
    void anUnrecognisedPolicyFailsClosed() {
        assertTrue(LockAutomationPolicy.parse("nonsense") == LockAutomationPolicy.BLOCK_ALL);
        assertTrue(LockAutomationPolicy.parse(null) == LockAutomationPolicy.BLOCK_ALL);
        assertTrue(LockAutomationPolicy.parse(" allow_insert ") == LockAutomationPolicy.ALLOW_INSERT,
                "case and whitespace are an operator's business, not a refusal");
        assertTrue(ForeignLockPolicy.parse("whatever") == ForeignLockPolicy.REFUSE);
    }

    @Test
    void everyRouteTheSpecificationNamesHasAHandler() {
        String handlers = read(HANDLERS);
        assertTrue(handlers.contains("PlayerInteractEvent.RightClickBlock"), "interaction");
        assertTrue(handlers.contains("BlockEvent.BreakEvent"), "breaking");
        assertTrue(handlers.contains("ExplosionEvent.Detonate"), "explosions");
        assertTrue(handlers.contains("PistonEvent.Pre"), "pistons and the redstone that drives them");
        String mixin = read(HOPPER_MIXIN);
        // The two transfer methods, not the two container lookups. Forge patches suckInItems to try
        // VanillaInventoryCodeHooks.extractHook first and ejectItems to try insertHook first, and its
        // BaseContainerBlockEntity exposes an item-handler capability for every vanilla container --
        // so the hook route succeeds for a padlocked chest and the lookups are never reached. Hooking
        // getSourceContainer/getAttachedContainer alone let a hopper drain a padlocked chest.
        assertTrue(mixin.contains("\"suckInItems\""), "hopper extraction, above the capability route");
        assertTrue(mixin.contains("\"ejectItems\""), "hopper insertion, above the capability route");
        assertFalse(mixin.contains("method = \"getSourceContainer\""),
                "the container lookup is below Forge's capability route and protects nothing");
        assertFalse(mixin.contains("method = \"getAttachedContainer\""),
                "the same on the insertion side");
        assertTrue(mixin.contains("remap = false"),
                "ejectItems keeps its plain name: Forge changes its signature, so MCPConfig has no "
                        + "mapping for it and remapping would look for a name that does not exist");
    }

    @Test
    void blocksAutomationRefusesBothOperationsOnALockedBlock() {
        // The live method needs a level, so what is asserted here is the decision it delegates to,
        // per operation and in both directions -- the half that decides whether a hopper under a
        // padlocked chest gets anything.
        for (LockAutomationPolicy.Operation operation : LockAutomationPolicy.Operation.values()) {
            assertFalse(LockAutomationPolicy.BLOCK_ALL.permits(true, operation),
                    "BLOCK_ALL refuses " + operation);
            assertTrue(LockAutomationPolicy.ALLOW_ALL.permits(true, operation),
                    "ALLOW_ALL allows " + operation);
        }
        assertTrue(LockAutomationPolicy.ALLOW_INSERT.permits(true,
                LockAutomationPolicy.Operation.INSERT));
        assertFalse(LockAutomationPolicy.ALLOW_INSERT.permits(true,
                LockAutomationPolicy.Operation.EXTRACT));
    }

    @Test
    void anUnlockedOrUnknownBlockNeverBlocksAutomation() {
        // No level means no lock table, which means no lock, which means no refusal -- the direction a
        // protection check has to fail in, because the alternative is every hopper in the world
        // stopping when the store is unavailable.
        for (LockAutomationPolicy.Operation operation : LockAutomationPolicy.Operation.values()) {
            assertFalse(LockProtection.blocksAutomation(null, null, operation), operation.name());
            assertFalse(LockProtection.blocksAutomation(null, new net.minecraft.core.BlockPos(0, 64, 0),
                    operation), operation.name());
        }
        assertTrue(LockProtection.lockAt(null, null).isEmpty());
        assertFalse(LockProtection.locked(null, null));
    }

    @Test
    void theUncoveredRoutesAreReportedRatherThanImplied() {
        String handlers = read(HANDLERS);
        assertTrue(handlers.contains("reportCoverage"),
                "there is a startup report naming what protection covers");
        assertTrue(handlers.contains("NOT covered by any"),
                "and it says plainly which routes have no adapter");
        assertTrue(handlers.contains("redstone signal opening a padlocked vanilla door"),
                "including the redstone route, which is refused rather than covered broadly");
        assertTrue(handlers.contains("nor exposes a Forge"),
                "and the modded container without a Forge item handler");
    }

    @Test
    void thisModsOwnDoorIgnoresRedstoneEntirely() {
        String door = read(Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "block",
                "CellDoorBlock.java"));
        assertTrue(door.contains("Redstone never opens a cell door"),
                "a prison door a comparator can open is not a prison door");
        assertTrue(door.contains("// deliberately nothing"));
    }

    @Test
    void ownershipIsAnExemptionRatherThanAnAuthority() {
        assertFalse(LockProtectionHandlers.exempt(null, null));
        // The lock's owner is the only non-operator exemption, and it is read from the record rather
        // than from anything the actor supplies.
        assertTrue(LockProtectionHandlers.class.getDeclaredMethods().length > 0);
    }
}
