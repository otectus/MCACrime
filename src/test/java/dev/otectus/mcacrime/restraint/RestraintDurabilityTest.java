package dev.otectus.mcacrime.restraint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a restraint's starting durability comes from (0.7.5 M2.2).
 *
 * <p>Two upstream problems are asserted away here. The first is the placeholder: all three restraint
 * items are registered with a vanilla durability of 999 that nothing ever reads, so the number a
 * server operator would find and change is not the number that governs anything. The second is
 * narrower and worse, because it looks like it works: the leg restraints read the <em>arm</em>
 * setting, so a server that makes leg tape flimsier changes nothing at all.
 *
 * <p>Both are properties of a pure function over seven integers, which is why it is a pure function
 * over six integers.
 */
class RestraintDurabilityTest {

    /** Deliberately asymmetric: every value distinct, so a mis-wired key cannot pass by coincidence. */
    private static final RestraintDurability.Settings DISTINCT =
            new RestraintDurability.Settings(41, 16, 6, 7, 8, 9);

    @Test
    void armAndLegTapeAreIndependent() {
        // The upstream defect, directly: these two must not be the same number by construction.
        assertEquals(6, RestraintDurability.resolve(RestraintDefinitions.DUCK_TAPE_ARMS, DISTINCT));
        assertEquals(7, RestraintDurability.resolve(RestraintDefinitions.DUCK_TAPE_LEGS, DISTINCT));
        assertEquals(8, RestraintDurability.resolve(RestraintDefinitions.DUCK_TAPE_HEAD, DISTINCT));
    }

    @Test
    void everyFamilyReadsItsOwnKey() {
        assertEquals(41, RestraintDurability.resolve(RestraintDefinitions.HANDCUFFS_ARMS, DISTINCT));
        assertEquals(41, RestraintDurability.resolve(RestraintDefinitions.HANDCUFFS_LEGS, DISTINCT));
        assertEquals(16, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_ARMS, DISTINCT));
        assertEquals(16, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_LEGS, DISTINCT));
        assertEquals(9, RestraintDurability.resolve(RestraintDefinitions.BUNDLE, DISTINCT));
    }

    @Test
    void theShippedDefaultsAreTheSourceNumbers() {
        RestraintDurability.Settings defaults = RestraintDurability.Settings.defaults();
        assertEquals(40, RestraintDurability.resolve(RestraintDefinitions.HANDCUFFS_ARMS, defaults));
        assertEquals(15, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_ARMS, defaults));
        assertEquals(5, RestraintDurability.resolve(RestraintDefinitions.DUCK_TAPE_ARMS, defaults));
        // 999 is upstream's placeholder and must appear nowhere.
        for (RestraintDefinition definition : RestraintDefinitions.wearable()) {
            assertTrue(RestraintDurability.resolve(definition.id(), defaults) < 999,
                    definition.id() + " is using the placeholder durability");
        }
    }

    @Test
    void anUnnamedDefinitionFallsBackToItsOwnDeclaration_notToOne() {
        // The pillory is a device: the settings do not name it, and it must not silently become
        // worth a single struggle input because of that.
        assertEquals(RestraintDefinitions.get(RestraintDefinitions.PILLORY).orElseThrow()
                        .escape().durability(),
                RestraintDurability.resolve(RestraintDefinitions.PILLORY, DISTINCT));
    }

    @Test
    void nullAndAbsentSettingsAreAnswerable() {
        assertEquals(0, RestraintDurability.resolve(null, DISTINCT));
        assertEquals(40, RestraintDurability.resolve(RestraintDefinitions.HANDCUFFS_ARMS, null));
    }

    /**
     * A datapack profile is the more specific statement, and the only definition it moves is its own.
     *
     * <p>The precedence question is real: the same number exists as a config key, and a pack naming
     * {@code mcacrime:shackles_arms} has said something narrower than a server-wide
     * {@code durabilityShackles}. The narrower statement wins, and leg shackles — which read the same
     * config key — are untouched, which is the arm/leg independence rule applied to the pack layer.
     */
    @Test
    void aDatapackProfileOverridesTheConfiguredDurability() {
        try {
            RestraintProfileOverrides.replaceAll(List.of(new RestraintProfile(
                    RestraintDefinitions.SHACKLES_ARMS, java.util.OptionalInt.of(99), java.util.Map.of(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty())));

            assertEquals(99, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_ARMS, DISTINCT));
            assertEquals(99, RestraintDefinitions.get(RestraintDefinitions.SHACKLES_ARMS)
                    .orElseThrow().escape().durability());
            assertEquals(16, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_LEGS, DISTINCT));
        } finally {
            RestraintProfileOverrides.clear();
        }
        assertEquals(16, RestraintDurability.resolve(RestraintDefinitions.SHACKLES_ARMS, DISTINCT));
    }
}
