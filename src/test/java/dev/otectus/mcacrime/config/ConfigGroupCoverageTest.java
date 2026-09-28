package dev.otectus.mcacrime.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every setting this mod declares is written down somewhere an operator can read it (0.7.5 M7.1).
 *
 * <p>A key that exists and is documented nowhere is a key nobody will ever set on purpose. The whole
 * point of a config is that somebody else can decide what it should be, and they cannot decide about
 * a number they have never seen. So this walks {@code McaCrimeConfig} and asserts that {@code CONFIG.md}
 * names each key it finds.
 *
 * <h2>How a key counts as documented</h2>
 * By name, in backticks, anywhere in the file — as {@code `key`} or as {@code `group.key`}. That is
 * deliberately loose about <em>where</em>: {@code CONFIG.md} is a document for people, some rows cover
 * three related switches at once, and a test that dictated one row per key would be a formatting rule
 * rather than a coverage rule. It is exact about <em>whether</em>, which is the part that matters.
 *
 * <h2>No exemption list</h2>
 * There is none. Until 0.7.5 this test carried an allow-list of keys that shipped undocumented before
 * the release; M7.1 is "every key documented", so the list is gone and the rule applies to every key
 * the mod declares, old or new. An undocumented key is a failure here and is fixed in
 * {@code CONFIG.md}, never by adding a name back to a list.
 */
class ConfigGroupCoverageTest {

    private static final Path CONFIG_MD = Path.of("CONFIG.md");

    @Test
    void everyDeclaredKeyIsInConfigMd() {
        Set<String> documented = documentedNames();
        List<String> missing = new ArrayList<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            if (!documented.contains(key.name())) {
                missing.add(key.spec() + "  " + key.path() + "   (McaCrimeConfig.java:" + key.line() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "CONFIG.md documents no such setting as:\n  "
                + String.join("\n  ", missing)
                + "\nEach needs a row with its default and range, under its own group heading.");
    }

    /** The guard must not pass vacuously: there have to be keys, and a document to check them against. */
    @Test
    void thereIsSomethingToCheck() {
        assertTrue(ConfigKeyIndex.keys().size() > 400,
                "expected the whole config to be read, found " + ConfigKeyIndex.keys().size());
        assertTrue(documentedNames().size() > 200,
                "expected CONFIG.md to name most of the config, found " + documentedNames().size());
    }

    /** No key is declared twice: a second declaration of one path is silently dropped by the spec. */
    @Test
    void noPathIsDeclaredTwice() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            if (!seen.add(key.spec() + " " + key.path())) {
                duplicates.add(key.spec() + " " + key.path() + " (McaCrimeConfig.java:" + key.line() + ")");
            }
        }
        assertEquals(List.of(), duplicates, "one of each pair would never be read");
    }

    /** Every key belongs to a section: a key at the top level of the file has no home in the TOML. */
    @Test
    void everyKeyIsInsideAGroup() {
        List<String> orphans = new ArrayList<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            if (key.group().isEmpty()) {
                orphans.add(key.name() + " (McaCrimeConfig.java:" + key.line() + ")");
            }
        }
        assertEquals(List.of(), orphans);
    }

    /**
     * The 0.7.5 groups all exist, by name.
     *
     * <p>§3.12 lists them. A group that quietly failed to be pushed would put its keys in whatever
     * section happened to be open, which reads as a typo rather than as a missing feature.
     */
    @Test
    void the075GroupsAreAllPresent() {
        Set<String> groups = new HashSet<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            groups.add(key.group());
        }
        for (String expected : List.of("restraints", "restraints.definitions", "restraints.application",
                "restraints.escape", "transport", "detention", "locks", "lockpicking", "frisking",
                "prison", "enchantments", "compatibility",
                "sentencing.capitalPunishment", "client")) {
            assertTrue(groups.contains(expected), "no [" + expected + "] section is ever pushed");
        }
    }

    /**
     * A key does not repeat the name of the section it is in.
     *
     * <p>{@code [restraints.application] channelTicks}, not {@code applicationChannelTicks}: the
     * section is already in the path, and repeating it is how one mod ends up with both spellings.
     * Scoped to the groups 0.7.5 introduced, because the older sections shipped with their names and
     * renaming a released key would break every existing config file for the sake of tidiness.
     */
    @Test
    void no075KeyRepeatsItsOwnGroupName() {
        List<String> offenders = new ArrayList<>();
        for (ConfigKeyIndex.Key key : ConfigKeyIndex.keys()) {
            if (!NEW_IN_075.contains(key.group())) {
                continue;
            }
            String leaf = key.group().substring(key.group().lastIndexOf('.') + 1);
            String lower = key.name().toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith(leaf.toLowerCase(java.util.Locale.ROOT)) && !lower.equals(
                    leaf.toLowerCase(java.util.Locale.ROOT))) {
                offenders.add(key.path() + " (McaCrimeConfig.java:" + key.line() + ")");
            }
        }
        assertEquals(List.of(), offenders, "the group name is already in the path");
    }

    private static final Set<String> NEW_IN_075 = Set.of("restraints.definitions",
            "restraints.application", "restraints.escape", "transport", "detention", "locks",
            "lockpicking", "frisking", "prison", "enchantments", "compatibility");

    /** Every backticked identifier in CONFIG.md, split on anything a key name cannot contain. */
    private static Set<String> documentedNames() {
        String text = String.join("\n", ConfigKeyIndex.read(CONFIG_MD));
        Set<String> names = new HashSet<>();
        Matcher matcher = Pattern.compile("`([^`]+)`").matcher(text);
        while (matcher.find()) {
            for (String piece : matcher.group(1).split("[^A-Za-z0-9_]+")) {
                if (!piece.isBlank()) {
                    names.add(piece);
                }
            }
        }
        return names;
    }
}
