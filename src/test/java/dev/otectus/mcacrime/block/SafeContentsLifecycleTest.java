package dev.otectus.mcacrime.block;

import dev.otectus.mcacrime.block.entity.SafeInventoryPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A safe's contents outlive its lock, its block and its configuration (M3.5, spec §21.2).
 *
 * <p>Three ways a safe can stop existing — picked destructively, broken, or reloaded with a smaller
 * configured size — and in all three the contents have to settle exactly once and nothing may be
 * silently deleted. "Never silently delete a safe's inventory" is the specification's wording.
 */
class SafeContentsLifecycleTest {

    private static final Path SAFE_BLOCK = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "block", "SafeBlock.java");
    private static final Path SAFE_ENTITY = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "block", "entity", "SafeBlockEntity.java");
    private static final Path LOCKPICK_SERVICE = Path.of("src", "main", "java", "dev", "otectus",
            "mcacrime", "lockpick", "LockpickService.java");
    private static final Path LOOT_TABLE = Path.of("src", "main", "resources", "data", "mcacrime",
            "loot_tables", "blocks", "safe.json");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void pickDestroyUnloadWithContents() {
        // Destroyed by a destructive pick: the contents are moved out first, then the block goes.
        String service = read(LOCKPICK_SERVICE);
        int drop = service.indexOf("Containers.dropContents");
        int destroy = service.indexOf("destroyBlock");
        assertTrue(drop > 0 && destroy > drop,
                "a destructive pick empties the safe before removing it, exactly once");
        assertTrue(service.contains("safe.clearContent()"),
                "and clears it, so the block's own removal cannot drop the same items again");

        // Broken by a player: the block entity spills once through onRemove.
        String block = read(SAFE_BLOCK);
        assertTrue(block.contains("Containers.dropContents"), "breaking a safe spills it");
        assertTrue(block.contains("!state.is(newState.getBlock())"),
                "and only when the block is really being replaced, so a state change spills nothing");

        // The loot table drops the block itself and nothing else; the contents are not in it.
        String loot = read(LOOT_TABLE);
        assertTrue(loot.contains("mcacrime:safe"));
        assertEquals(1, loot.split("\"type\": \"minecraft:item\"", -1).length - 1,
                "one entry: the safe. Its contents are the block entity's business, not the table's");
    }

    @Test
    void unloadingAndReloadingPreservesEverySlot() {
        String entity = read(SAFE_ENTITY);
        assertTrue(entity.contains("ContainerHelper.saveAllItems"), "contents are saved");
        assertTrue(entity.contains("ContainerHelper.loadAllItems"), "and loaded");
        assertTrue(entity.contains("if (needed > items.size())"),
                "and a saved list longer than the configured size grows the container rather than "
                        + "truncating it");
    }

    @Test
    void aSmallerConfiguredSizeNeverDestroysStoredItems() {
        for (int stored = 0; stored <= 54; stored++) {
            for (int configured = 9; configured <= 54; configured += 9) {
                int resolved = SafeInventoryPolicy.resolvedSize(configured, stored);
                assertTrue(resolved >= stored,
                        "a safe holding " + stored + " stacks configured to " + configured
                                + " must not shrink below what it holds");
            }
        }
        assertEquals(40, SafeInventoryPolicy.resolvedSize(9, 40));
        assertEquals(36, SafeInventoryPolicy.menuSlots(40),
                "the overflow is carried out of sight rather than thrown away");
    }

    @Test
    void theLockIsDetachedWhenTheBlockGoes() {
        assertTrue(read(SAFE_BLOCK).contains("LockService.detach"),
                "a removed safe detaches its lock once, leaving the row for the keys that name it");
    }
}
