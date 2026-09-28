package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrimeConfig.RestraintPreset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two restraint tunings, and the rule that neither is ever imposed silently (M7.2).
 *
 * <p>{@code CUFFED_PARITY} is what the source did: put a restraint on and it is on, no opening
 * required, and a picked lock is a destroyed one. {@code BALANCED_VILLAGE} is the same system tuned
 * for a settlement that has laws — it takes time to restrain somebody, they have to be vulnerable
 * first, players cannot capture each other at all, and picking a lock opens it rather than breaking
 * it.
 *
 * <p>Everything here is asserted against the tables rather than against a running config, because the
 * tables are the decision. What a preset <em>did</em> to a particular server is the log line it printed
 * at the moment it was applied, and that sentence is asserted too.
 */
class PresetTest {

    @Test
    void theParityPresetIsImmediateAndUngated() {
        Map<String, Object> parity = RestraintPresets.values(RestraintPreset.CUFFED_PARITY);
        assertEquals(0, parity.get(RestraintPresets.CHANNEL_TICKS),
                "parity is immediate application: the source has no channel at all");
        assertEquals(List.of(), parity.get(RestraintPresets.VULNERABILITY_GATES),
                "low-health gating is off in the parity preset");
        assertEquals(true, parity.get(RestraintPresets.ENABLE_KIDNAPPING_PLAYER),
                "a player may restrain another player, as upstream allows");
        assertEquals(true, parity.get(RestraintPresets.DESTRUCTIVE_OUTCOME),
                "upstream's picked door or safe is destroyed");
    }

    @Test
    void theBalancedPresetAddsDurationVulnerabilityAndRestraintOnPlayers() {
        Map<String, Object> balanced = RestraintPresets.values(RestraintPreset.BALANCED_VILLAGE);
        assertEquals(60, balanced.get(RestraintPresets.CHANNEL_TICKS), "three seconds of application");
        assertEquals(RestraintPresets.BALANCED_GATES,
                balanced.get(RestraintPresets.VULNERABILITY_GATES));
        assertEquals(false, balanced.get(RestraintPresets.ENABLE_KIDNAPPING_PLAYER),
                "stricter player-capture permission is the point of this preset");
        assertEquals(false, balanced.get(RestraintPresets.DESTRUCTIVE_OUTCOME),
                "non-destructive picking: the lock opens, the safe survives");
        assertEquals(true, balanced.get(RestraintPresets.REQUIRE_LINE_OF_SIGHT));
    }

    /** Every gate the balanced preset asks for is a gate the engine understands. */
    @Test
    void theBalancedGatesAreAllRealGates() {
        for (String gate : RestraintPresets.BALANCED_GATES) {
            assertTrue(ConfigValidator.KNOWN_VULNERABILITY_GATES.contains(gate),
                    gate + " is not a gate the application transaction knows, so it would refuse nothing");
        }
    }

    /**
     * Switching to the other preset and back returns every key it touched.
     *
     * <p>The reason both tables carry the same keys. A preset that set something its counterpart did
     * not would be a trapdoor: an operator who tried {@code BALANCED_VILLAGE} for an evening could not
     * get back to where they started by putting the old value in the file.
     */
    @Test
    void thePresetsOwnExactlyTheSameKeys() {
        assertEquals(RestraintPresets.values(RestraintPreset.CUFFED_PARITY).keySet(),
                RestraintPresets.values(RestraintPreset.BALANCED_VILLAGE).keySet());
    }

    /** A preset is applied once. Matching the marker means it has already been written. */
    @Test
    void aPresetIsNotReappliedOverAnOperatorsOwnEdits() {
        assertFalse(RestraintPresets.shouldApply(RestraintPreset.CUFFED_PARITY, "CUFFED_PARITY"));
        assertFalse(RestraintPresets.shouldApply(RestraintPreset.CUFFED_PARITY, " cuffed_parity "));
        assertTrue(RestraintPresets.shouldApply(RestraintPreset.BALANCED_VILLAGE, "CUFFED_PARITY"));
        assertTrue(RestraintPresets.shouldApply(RestraintPreset.BALANCED_VILLAGE, ""),
                "an explicitly selected non-default preset applies over a file with no marker");
        assertFalse(RestraintPresets.shouldApply(RestraintPreset.CUFFED_PARITY, ""),
                "a file with no marker is a fresh or hand-blanked file, and writing the shipped "
                        + "default preset into it would flip lockpicking.destructiveOutcome, the one "
                        + "key D10 ships differently, without anybody choosing it");
        assertFalse(RestraintPresets.shouldApply(null, "CUFFED_PARITY"));
    }

    /** Applying a preset says which keys it moved, with the old value beside the new one. */
    @Test
    void everyChangedKeyIsNamedInTheReport() {
        Map<String, Object> parityNow = new LinkedHashMap<>(
                RestraintPresets.values(RestraintPreset.CUFFED_PARITY));

        List<String> report = RestraintPresets.describe(RestraintPreset.BALANCED_VILLAGE, parityNow);

        // Four of the five keys move; requireLineOfSight is true in both tunings, and a key that did
        // not move is deliberately not listed -- the report is what changed, not what was considered.
        assertEquals(5, report.size(), "a heading and one line per moved key");
        assertTrue(report.get(0).contains("BALANCED_VILLAGE"));
        String body = String.join("\n", report);
        for (String key : List.of(RestraintPresets.CHANNEL_TICKS, RestraintPresets.VULNERABILITY_GATES,
                RestraintPresets.ENABLE_KIDNAPPING_PLAYER, RestraintPresets.DESTRUCTIVE_OUTCOME)) {
            assertTrue(body.contains(key), key + " moved and was not reported");
        }
        assertFalse(body.contains(RestraintPresets.REQUIRE_LINE_OF_SIGHT),
                "a key that did not move is not reported as if it had");
        assertTrue(body.contains("0 -> 60"), "the old value is shown beside the new one");
    }

    /** Applying the preset a file already matches says so, rather than saying nothing at all. */
    @Test
    void aPresetThatChangesNothingStillSaysSo() {
        List<String> report = RestraintPresets.describe(RestraintPreset.CUFFED_PARITY,
                RestraintPresets.values(RestraintPreset.CUFFED_PARITY));
        assertEquals(1, report.size());
        assertTrue(report.get(0).contains("nothing was rewritten"));
    }

    /** An unknown name in the file is the shipped tuning, never an empty or half-applied one. */
    @Test
    void anUnknownPresetNameFallsBackToParity() {
        assertEquals(RestraintPreset.CUFFED_PARITY, RestraintPresets.parse("no_such_preset"));
        assertEquals(RestraintPreset.CUFFED_PARITY, RestraintPresets.parse(null));
        assertEquals(RestraintPreset.BALANCED_VILLAGE, RestraintPresets.parse("balanced_village"));
        assertTrue(ConfigValidator.validatePreset("no_such_preset", "CUFFED_PARITY").stream()
                .anyMatch(problem -> problem.contains("must be CUFFED_PARITY or BALANCED_VILLAGE")));
        assertEquals(List.of(),
                ConfigValidator.validatePreset("BALANCED_VILLAGE", "CUFFED_PARITY"),
                "a preset waiting to be applied is the ordinary state, not a problem");
    }

    /**
     * The shipped defaults <em>are</em> the parity preset, with one documented exception.
     *
     * <p>That is what makes {@code CUFFED_PARITY} an honest default: a fresh install writes nothing,
     * because there is nothing to write. The exception is {@code lockpicking.destructiveOutcome},
     * which ships {@code false} by decision D10 — a default that destroyed a player's safe the first
     * time somebody picked it is not one anybody should arrive at by accident, and an operator who
     * explicitly asks for upstream parity is asking for upstream's outcome.
     */
    @Test
    void theShippedDefaultsAreTheParityPresetExceptForPicking() {
        Map<String, String> shipped = shippedDefaults();
        Map<String, Object> parity = RestraintPresets.values(RestraintPreset.CUFFED_PARITY);
        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, Object> entry : parity.entrySet()) {
            String declared = shipped.get(entry.getKey());
            if (declared == null) {
                differences.add(entry.getKey() + " is not declared in McaCrimeConfig at all");
            } else if (!declared.equals(String.valueOf(entry.getValue()))) {
                differences.add(entry.getKey() + " ships " + declared + ", parity wants " + entry.getValue());
            }
        }
        assertEquals(List.of("lockpicking.destructiveOutcome ships false, parity wants true"),
                differences, "the only deliberate divergence is D10's non-destructive picking");
    }

    /** The declared default of each key a preset owns, read out of the config source. */
    private static Map<String, String> shippedDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        List<String> lines = ConfigKeyIndex.read(java.nio.file.Path.of("src", "main", "java", "dev",
                "otectus", "mcacrime", "McaCrimeConfig.java"));
        Map<String, String> wanted = Map.of(
                "channelTicks", RestraintPresets.CHANNEL_TICKS,
                "requireLineOfSight", RestraintPresets.REQUIRE_LINE_OF_SIGHT,
                "vulnerabilityGates", RestraintPresets.VULNERABILITY_GATES,
                "enableKidnappingPlayer", RestraintPresets.ENABLE_KIDNAPPING_PLAYER,
                "destructiveOutcome", RestraintPresets.DESTRUCTIVE_OUTCOME);
        for (String line : lines) {
            for (Map.Entry<String, String> entry : wanted.entrySet()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "\\.define(?:InRange|List)?\\(\"" + entry.getKey() + "\",\\s*(List\\.of\\(\\)|[^,)]+)")
                        .matcher(line);
                if (matcher.find()) {
                    String value = matcher.group(1).trim();
                    defaults.put(entry.getValue(), "List.of()".equals(value) ? "[]" : value);
                }
            }
        }
        return defaults;
    }
}
