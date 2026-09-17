package dev.otectus.mcacrime.compat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability ids are part of what an operator reads and what config validation quotes, so they
 * are pinned here rather than left to whatever the enum happens to be called this week.
 */
class TownsteadCapabilityTest {

    @Test
    void everyIdIsUniqueAndLowercase() {
        Set<String> seen = new HashSet<>();
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            assertFalse(capability.id().isBlank(), capability + " has no id");
            assertEquals(capability.id().toLowerCase(Locale.ROOT), capability.id(),
                    capability + " must have a lowercase id: it is quoted verbatim in operator output");
            assertTrue(seen.add(capability.id()),
                    "duplicate Townstead capability id: " + capability.id());
        }
    }

    @Test
    void everyCapabilityExplainsItself() {
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            assertFalse(capability.description().isBlank(),
                    capability + " has no description; /crime debug townstead would print a bare id and "
                            + "leave the operator to guess what is missing");
        }
    }

    @Test
    void fromIdRoundTripsEveryConstant() {
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            assertSame(capability, TownsteadCapability.fromId(capability.id()));
            assertSame(capability, TownsteadCapability.fromId(capability.name()),
                    "the constant name must resolve too, so an operator may type either form");
            assertSame(capability, TownsteadCapability.fromId("  " + capability.id().toUpperCase(Locale.ROOT) + " "),
                    "case and surrounding space must not decide whether a capability resolves");
        }
    }

    /**
     * An unknown id has to fail loudly. A capability gate that silently matched nothing would report
     * the feature as configured while nothing checked anything — the exact failure this whole
     * diagnostic layer exists to prevent.
     */
    @Test
    void anUnknownIdIsARejection() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> TownsteadCapability.fromId("read_moods"));
        assertTrue(thrown.getMessage().contains("read_moods"), "the message should quote the bad id");
        assertTrue(thrown.getMessage().contains(TownsteadCapability.READ_NEEDS.id()),
                "the message should list the valid set");

        assertThrows(IllegalArgumentException.class, () -> TownsteadCapability.fromId(""));
        assertThrows(IllegalArgumentException.class, () -> TownsteadCapability.fromId(null));
    }

    /**
     * The read capabilities are the ones a Townstead that ships today can satisfy; the cooperation
     * ones cannot bind until upstream grows the surface. Both groups must stay declared, because a
     * switch resting on a missing capability has to be reportable as degraded.
     */
    @Test
    void bothGroupsAreDeclared() {
        assertTrue(TownsteadCapability.values().length >= 18,
                "capabilities were removed; a feature that needs one can no longer report as degraded");
        assertSame(TownsteadCapability.READ_NEEDS, TownsteadCapability.fromId("read_needs"));
        assertSame(TownsteadCapability.EQUIPMENT_PROVENANCE, TownsteadCapability.fromId("equipment_provenance"));
        assertSame(TownsteadCapability.STAGE_CAPABILITIES, TownsteadCapability.fromId("stage_capabilities"));
    }
}
