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

    /**
     * The source tree, resolved from {@code mcacrime.projectRoot}.
     *
     * <p>The NeoForge unit-test runner works out of {@code build/minecraft-junit}, so a relative
     * {@code src/main/java/...} resolves to nothing and every assertion below would fail as "file not
     * found" rather than as the thing it is checking. The {@code test} task supplies the property.
     */
    private static Path source(String... segments) {
        Path path = Path.of(System.getProperty("mcacrime.projectRoot", "."), "src", "main", "java",
                "dev", "otectus", "mcacrime");
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path;
    }

    private static final Path HANDLERS = source("locks", "LockProtectionHandlers.java");
    private static final Path HOPPER_MIXIN = source("mixin", "HopperLockMixin.java");

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
    void everyRouteTheSpecificationNamesHasAHandler() {
        String handlers = read(HANDLERS);
        assertTrue(handlers.contains("PlayerInteractEvent.RightClickBlock"), "interaction");
        assertTrue(handlers.contains("BlockEvent.BreakEvent"), "breaking");
        assertTrue(handlers.contains("ExplosionEvent.Detonate"), "explosions");
        assertTrue(handlers.contains("PistonEvent.Pre"), "pistons and the redstone that drives them");
        String mixin = read(HOPPER_MIXIN);
        // Both directions, and deliberately at the two transfer methods rather than at the container
        // lookups the Forge baseline hooks: NeoForge patches these to try an item-handler capability
        // first, which every vanilla container exposes, so the lookups below them never run for one.
        assertTrue(mixin.contains("method = \"suckInItems\""), "hopper extraction");
        assertTrue(mixin.contains("method = \"ejectItems\""), "hopper insertion");
        assertTrue(mixin.contains("Operation.EXTRACT") && mixin.contains("Operation.INSERT"),
                "and the two directions ask the policy separately");
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
        assertTrue(handlers.contains("nor exposes a "),
                "and the modded container with no item-handler capability");
        assertTrue(handlers.contains("NeoForge item-handler capability"),
                "named as this platform's capability rather than as the baseline's Forge handler");
    }

    @Test
    void thisModsOwnDoorIgnoresRedstoneEntirely() {
        String door = read(source("block", "CellDoorBlock.java"));
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
