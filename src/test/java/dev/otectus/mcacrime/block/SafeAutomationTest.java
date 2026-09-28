package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.block.entity.SafeInventoryPolicy;
import dev.otectus.mcacrime.locks.LockAutomationPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safe's two access routes, and the rule that a cached handler cannot outlive a lock (M3.5).
 *
 * <p>The specification is unusually specific here: "every cached handler must re-evaluate current lock
 * state; toggling the lock cannot leave a previously obtained unrestricted handler usable", and
 * "handle insertion as well as extraction". Both are structural properties of the handler rather than
 * values, so the policy matrix is asserted directly and the structure is asserted against the source.
 */
class SafeAutomationTest {

    private static final Path HANDLER = project("src", "main", "java", "dev", "otectus", "mcacrime",
            "block", "entity", "LockAwareItemHandler.java");
    private static final Path SAFE = project("src", "main", "java", "dev", "otectus", "mcacrime",
            "block", "entity", "SafeBlockEntity.java");

    /**
     * The source tree, resolved from {@code mcacrime.projectRoot}.
     *
     * <p>The NeoForge unit-test runner works out of {@code build/minecraft-junit}, so a relative
     * {@code src/...} path resolves to nothing and every assertion below would fail as "file not
     * found" rather than as the thing it is checking. The {@code test} task supplies the property.
     */
    private static Path project(String... segments) {
        Path path = Path.of(System.getProperty("mcacrime.projectRoot", "."));
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void hopperMinecartAndCachedHandlerFollowTheDeclaredPolicy() {
        // Every automated route -- hopper, hopper minecart, a pipe mod's cached IItemHandler -- asks
        // this one question, so one matrix covers all three.
        for (LockAutomationPolicy policy : LockAutomationPolicy.values()) {
            assertTrue(policy.permits(false, LockAutomationPolicy.Operation.INSERT),
                    policy + " must not interfere with an unlocked safe");
            assertTrue(policy.permits(false, LockAutomationPolicy.Operation.EXTRACT));
        }
        assertFalse(LockAutomationPolicy.BLOCK_ALL.permits(true, LockAutomationPolicy.Operation.EXTRACT));
        assertFalse(LockAutomationPolicy.BLOCK_ALL.permits(true, LockAutomationPolicy.Operation.INSERT));
        assertTrue(LockAutomationPolicy.ALLOW_INSERT.permits(true, LockAutomationPolicy.Operation.INSERT));
        assertFalse(LockAutomationPolicy.ALLOW_INSERT.permits(true, LockAutomationPolicy.Operation.EXTRACT));
    }

    @Test
    void theHandlerAsksPerOperationRatherThanCachingAnAnswer() {
        String handler = read(HANDLER);
        assertTrue(handler.contains("insertItem"), "insertion is covered");
        assertTrue(handler.contains("extractItem"), "and so is extraction");
        assertTrue(handler.contains("setStackInSlot"), "and the modifiable escape hatch");
        int asks = handler.split("automationPermits", -1).length - 1;
        assertTrue(asks >= 4, "every operation asks the lock afresh; found " + asks + " checks");
        assertFalse(handler.contains("private final boolean locked"),
                "nothing here may capture the lock state it was built with");
    }

    @Test
    void lockWhileContainerOpen() {
        String safe = read(SAFE);
        assertTrue(safe.contains("onLockChanged"), "a lock change is an event the safe reacts to");
        assertTrue(safe.contains("closeContainer"),
                "and an open menu on a safe that has just been locked is closed");
        assertTrue(safe.contains("invalidateHandler"),
                "the cached capability is dropped at the same moment");
    }

    @Test
    void theSlotCountIsOneAuthoritativeValue() {
        assertEquals(36, SafeInventoryPolicy.DEFAULT_SLOTS,
                "the specification's source-derived safe default");
        assertEquals(36, SafeInventoryPolicy.clampConfigured(36));
        assertEquals(27, SafeInventoryPolicy.clampConfigured(27));
        assertEquals(27, SafeInventoryPolicy.clampConfigured(35), "rounded down to whole rows");
        assertEquals(54, SafeInventoryPolicy.clampConfigured(999));
        assertEquals(9, SafeInventoryPolicy.clampConfigured(0));
    }

    @Test
    void slotShrinkWithOverflowKeepsEverything() {
        // Thirty-six slots in use, and the operator lowers the setting to twenty-seven.
        assertEquals(36, SafeInventoryPolicy.resolvedSize(27, 36),
                "the container stays as large as what it is holding");
        assertEquals(27, SafeInventoryPolicy.menuSlots(SafeInventoryPolicy.resolvedSize(27, 0)),
                "an empty safe simply gets smaller");
        assertEquals(3, SafeInventoryPolicy.rows(27));
        assertEquals(4, SafeInventoryPolicy.rows(36));
        assertEquals(6, SafeInventoryPolicy.rows(99), "and a menu never shows more than six rows");
        assertEquals(54, SafeInventoryPolicy.menuSlots(60));
    }
}
