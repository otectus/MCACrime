package dev.otectus.mcacrime.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an operator is told about the settings 0.7.5 stopped reading (§5.2, §10.5, M7.1).
 *
 * <h2>The policy this asserts</h2>
 * Retired values are <b>dropped, not migrated</b>. Each of these keys governed a mechanic that no
 * longer exists, and carrying a number across into a setting that means something else would be worse
 * than losing it — a server would run with a figure nobody chose for the thing it now controls. What
 * is owed instead is a sentence, by name, saying which of their settings stopped doing anything and
 * what took its place. That report is this, and it must never fail startup: a key that no longer does
 * anything must not stop a server that still sets it.
 *
 * <p>{@code ForgeConfigSpec} silently drops unknown keys when it rewrites a file, so the survivors can
 * only be found by reading the raw text — which is why {@link ConfigValidator#retiredKeysIn} is a pure
 * function over lines and can be asserted here with no config directory in sight.
 */
class RetiredKeyReportTest {

    /** Every retired key has a replacement sentence, and no entry is stranded on either side. */
    @Test
    void everyRetiredKeyIsAnsweredForByName() {
        for (String key : ConfigValidator.RETIRED_KEYS) {
            List<String> report = ConfigValidator.retiredKeyReport(List.of(key));
            assertEquals(2, report.size(), key + " should produce a heading and one line");
            assertTrue(report.get(1).contains(key), "the report names the key the operator wrote");
            assertTrue(report.get(1).contains("->"), key + " has no replacement sentence at all");
            assertFalse(report.get(1).endsWith("-> "), key + " has an empty replacement");
        }
    }

    /** The whole list is the plan's §5.2, physical keys and the two client ones together. */
    @Test
    void theListIsTheDocumentedOne() {
        Set<String> retired = new HashSet<>(ConfigValidator.RETIRED_KEYS);
        assertEquals(ConfigValidator.RETIRED_KEYS.size(), retired.size(), "no key is listed twice");
        for (String expected : List.of("captureChannelTicks", "captureMaxMoveBlocks",
                "captureMaxRangeBlocks", "captureRequireLineOfSight", "captureLowHealthFraction",
                "villagerCaptureRelaxedVulnerability", "captureChannelMultiplierRope",
                "captureChannelMultiplierCuffs", "captureChannelMultiplierLockedCuffs",
                "restraintEscapeChanceRope", "restraintEscapeChanceCuffs",
                "restraintEscapeChanceLockedCuffs", "captiveTetherBlocks", "captiveCanEscapeByDistance",
                "escapeWorkTicksRope", "escapeWorkTicksCuffs", "escapeWorkTicksLockedCuffs",
                "cuffEscapeRequiresLockpick", "escapeAttemptCooldownTicks",
                "renderCuffs", "renderEscortRope")) {
            assertTrue(retired.contains(expected), expected + " is retired and must be reported");
        }
        assertEquals(21, retired.size(), "a key retired without a line here would vanish silently");
    }

    /** No retired key is still declared: the report would then contradict the file it is read from. */
    @Test
    void nothingRetiredIsStillDeclared() {
        List<String> zombies = new ArrayList<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            if (ConfigValidator.RETIRED_KEYS.contains(key.name())) {
                zombies.add(key.path() + " (McaCrimeConfig.java:" + key.line() + ")");
            }
        }
        assertEquals(List.of(), zombies, "a retired key that is still defined is not retired");
    }

    /** Every replacement names a setting that exists, or says plainly that there is none. */
    @Test
    void everyReplacementPointsSomewhereReal() {
        Set<String> declared = new HashSet<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            declared.add(key.path());
        }
        List<String> broken = new ArrayList<>();
        for (String key : ConfigValidator.RETIRED_KEYS) {
            String line = ConfigValidator.retiredKeyReport(List.of(key)).get(1);
            String replacement = line.substring(line.indexOf("->") + 2).trim();
            if (replacement.startsWith("no replacement")) {
                continue; // an honest "nothing replaced this", which is a supported answer
            }
            String path = replacement.split("\\s+")[0];
            if (!declared.contains(path) && declared.stream().noneMatch(d -> d.startsWith(path + "."))) {
                broken.add(key + " -> " + path);
            }
        }
        assertEquals(List.of(), broken, "a replacement that names no real key sends an operator hunting");
    }

    /** Only an assignment counts. A key inside a comment, or a longer key, is not a survivor. */
    @Test
    void onlyARealAssignmentIsReported() {
        List<String> found = ConfigValidator.retiredKeysIn(List.of(
                "# captureChannelTicks = 60 (this is a comment about the old key)",
                "captureChannelTicksLegacy = 60",
                "  captureMaxRangeBlocks = 4.0",
                "renderCuffs = true"));
        assertEquals(List.of("captureMaxRangeBlocks", "renderCuffs"), found);
    }

    /** A clean file says nothing at all, and a missing file is silence rather than a failure. */
    @Test
    void aFileWithNoRetiredKeysIsSilent() {
        assertEquals(List.of(), ConfigValidator.retiredKeysIn(List.of("channelTicks = 0", "")));
        assertEquals(List.of(), ConfigValidator.retiredKeysIn(null));
        assertEquals(List.of(), ConfigValidator.retiredKeyReport(List.of()));
        assertEquals(List.of(), ConfigValidator.retiredKeyReport(null));
    }

    /** One key is reported once, however many times a hand-edited file repeats it. */
    @Test
    void aRepeatedKeyIsReportedOnce() {
        assertEquals(List.of("escapeAttemptCooldownTicks"), ConfigValidator.retiredKeysIn(List.of(
                "escapeAttemptCooldownTicks = 40",
                "escapeAttemptCooldownTicks = 80")));
    }

    /** The report is a warning, not a refusal: it says what to delete and stops there. */
    @Test
    void theReportTellsTheOperatorTheyMayDeleteThem() {
        List<String> report = ConfigValidator.retiredKeyReport(List.of("captiveTetherBlocks"));
        assertTrue(report.get(0).contains("ignored"));
        assertTrue(report.get(0).contains("may be deleted"));
    }
}
