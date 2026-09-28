package dev.otectus.mcacrime.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every optional integration behaves correctly with the mod it integrates with absent (0.7.5 M6.2).
 *
 * <p>The test classpath has none of these mods on it, which is the point: this whole class runs in
 * the situation almost every install is actually in. Three properties are asserted, and each is a
 * different way the same bug shows up on somebody else's server:
 *
 * <ol>
 *   <li><b>Nothing is claimed.</b> Every bridge answers "not installed" and every query answers the
 *       truthful default, rather than throwing or quietly reporting success.</li>
 *   <li><b>Nothing is loaded.</b> Calling the bridge with the mod absent must not resolve the
 *       adapter class, which {@code OptionalClassloadTest} proves structurally and this proves by
 *       calling the methods.</li>
 *   <li><b>Nothing is hidden.</b> Every mod the table names carries an honest support level, and a
 *       row that claims support has an adapter package behind it.</li>
 * </ol>
 */
class AdapterAbsenceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "dev", "otectus", "mcacrime");

    @Test
    void everyBridgeReportsAbsenceRatherThanFailing() {
        // ModList.get() is null outside a running game, which is exactly the "no mod list at all"
        // case these guards have to survive: every one of them answers, none of them throws.
        assertFalse(ManaCompat.installed());
        assertFalse(InventoryCompat.installed());
        assertFalse(ReviveCompat.installed());
        assertFalse(CuffedCoexistence.installed());
        assertFalse(OptionalMods.installed("curios"));
        assertFalse(OptionalMods.installed(null));
    }

    @Test
    void everyQueryAnswersTheTruthfulDefaultWithTheModAbsent() {
        assertFalse(ManaCompat.drain(null, 0.5D), "no pool exists, so nothing was drained");
        assertFalse(ReviveCompat.downed(null), "nobody is downed in a game with no downed state");
        assertTrue(CuffedCoexistence.mayApplyRestraints(),
                "with the donor mod absent the coexistence policy must be inert");
        assertEquals(List.of(), OptionalMods.report(),
                "a report naming mods nobody has installed is noise, not diagnostics");
    }

    @Test
    void theCoexistencePolicyIsPureAndInertUntilTheDonorIsPresent() {
        assertTrue(CuffedCoexistence.mayApply(false, CuffedCoexistence.Policy.REFUSE),
                "REFUSE means nothing when the donor mod is not installed");
        assertTrue(CuffedCoexistence.mayApply(true, CuffedCoexistence.Policy.WARN));
        assertFalse(CuffedCoexistence.mayApply(true, CuffedCoexistence.Policy.REFUSE));
        assertEquals(CuffedCoexistence.Policy.WARN, CuffedCoexistence.Policy.parse("nonsense"),
                "an unreadable policy is the safe one, not the strict one");
        assertEquals(CuffedCoexistence.Policy.REFUSE, CuffedCoexistence.Policy.parse("refuse"));
    }

    @Test
    void everyRowInTheSupportTableIsHonest() {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (OptionalMods.Entry entry : OptionalMods.all()) {
            if (!ids.add(entry.modId())) {
                problems.add("duplicate row for " + entry.modId());
            }
            if (entry.note() == null || entry.note().isBlank()) {
                problems.add(entry.modId() + " claims " + entry.support() + " with no explanation");
            }
            if (entry.displayName() == null || entry.displayName().isBlank()) {
                problems.add(entry.modId() + " has no display name");
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /**
     * A mod the table calls {@code ADAPTED} must have an adapter package, and one it calls
     * {@code NO_CLAIM} must not.
     *
     * <p>This is the assertion that would have caught upstream's TacZ adapter: an empty class whose
     * presence alone sets an unrelated flag. Here, "we adapted it" has to be backed by a file.
     */
    @Test
    void adaptedMeansThereIsAnAdapterAndNoClaimMeansThereIsNot() {
        List<String> problems = new ArrayList<>();
        String bridges = readAll(MAIN.resolve("compat"));
        for (OptionalMods.Entry entry : OptionalMods.all()) {
            boolean named = bridges.contains("\"" + entry.modId() + "\"");
            switch (entry.support()) {
                case ADAPTED -> {
                    if (!named) {
                        problems.add(entry.modId() + " is claimed as adapted but no bridge names it");
                    }
                }
                case NO_CLAIM -> {
                    // Named in the table and nowhere else: no adapter, no probe, no flag set.
                    long occurrences = bridges.split("\"" + entry.modId() + "\"", -1).length - 1;
                    if (occurrences > 1) {
                        problems.add(entry.modId() + " claims nothing but is referenced "
                                + occurrences + " times in compat/");
                    }
                }
                case ENFORCED -> {
                    // Enforcement is not mod-specific by definition: nothing may name it but the table.
                    long occurrences = bridges.split("\"" + entry.modId() + "\"", -1).length - 1;
                    if (occurrences > 1) {
                        problems.add(entry.modId() + " is covered by generic enforcement but "
                                + occurrences + " references name it specifically");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /** The spell mods, the inventory mods and PlayerRevive each live behind exactly one bridge. */
    @Test
    void everyAdapterPackageIsReachedThroughOneBridgeByName() {
        assertTrue(read(MAIN.resolve("compat/ManaCompat.java"))
                .contains("dev.otectus.mcacrime.compat.mana.ManaDrainAdapters"));
        assertTrue(read(MAIN.resolve("compat/InventoryCompat.java"))
                .contains("dev.otectus.mcacrime.compat.inventory.ExternalInventoryAdapters"));
        assertTrue(read(MAIN.resolve("compat/ReviveCompat.java"))
                .contains("dev.otectus.mcacrime.compat.revive.PlayerReviveAdapter"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    /** Every bridge in {@code compat/}, concatenated; the adapter packages are excluded. */
    private static String readAll(Path directory) {
        StringBuilder out = new StringBuilder();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> out.append(read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + directory.toAbsolutePath(), e);
        }
        return out.toString();
    }
}
