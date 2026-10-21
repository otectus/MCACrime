package dev.otectus.mcacrime.help;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-game help (0.8.0): every topic resolves, every alias the instructions name works, and every
 * line of every page is a translation key that exists with exactly as many placeholders as the page
 * passes arguments -- walked through the built components, not found by searching the source.
 */
class CrimeHelpPagesTest {

    private static final Path LANG = dev.otectus.mcacrime.TestPaths.resources("assets", "mcacrime", "lang", "en_us.json");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?s");

    private static JsonObject lang() throws Exception {
        return JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theRequestedTopicsAndAliasesAllResolve() {
        for (String name : List.of("commands", "targeting", "guillotine", "keys", "keyring", "keymold", "masks",
                "key_ring", "key_mold", "ring", "mold", "pillory", "mask", "KEYRING")) {
            assertTrue(CrimeHelpTopic.byName(name).isPresent(), name + " opens a topic");
        }
        assertEquals(CrimeHelpTopic.KEYRING, CrimeHelpTopic.byName("keyring").orElseThrow());
        assertEquals(CrimeHelpTopic.KEYMOLD, CrimeHelpTopic.byName("keymold").orElseThrow());
        assertTrue(CrimeHelpTopic.byName("nonsense").isEmpty());
    }

    @Test
    void namesAndAliasesNeverCollide() {
        Set<String> seen = new HashSet<>();
        for (String name : CrimeHelpTopic.allNames()) {
            assertTrue(seen.add(name), name + " names two topics");
        }
    }

    @Test
    void everyPageLineIsATranslatedKeyWithMatchingArguments() throws Exception {
        JsonObject lang = lang();
        Set<String> problems = new TreeSet<>();
        for (CrimeHelpTopic topic : CrimeHelpTopic.values()) {
            check(Component.translatable(topic.titleKey()), lang, problems, topic.id());
            check(Component.translatable(topic.summaryKey()), lang, problems, topic.id());
            check(topic.link(), lang, problems, topic.id());
            check(topic.tooltipHint(), lang, problems, topic.id());
            List<CrimeHelpPages.Line> lines = CrimeHelpPages.lines(topic);
            assertTrue(!lines.isEmpty(), topic.id() + " has a body");
            for (CrimeHelpPages.Line line : lines) {
                check(line.text(), lang, problems, topic.id());
            }
        }
        for (Component line : CrimeHelpPages.overview()) {
            check(line, lang, problems, "overview");
        }
        assertTrue(problems.isEmpty(), "help text problems:\n  " + String.join("\n  ", problems));
    }

    @Test
    void operatorLinesAreMarkedWithTheirPermissionLevel() {
        for (CrimeHelpTopic topic : CrimeHelpTopic.values()) {
            for (CrimeHelpPages.Line line : CrimeHelpPages.lines(topic)) {
                String text = keysOf(line.text()).toString();
                if (text.contains(".op.")) {
                    assertTrue(line.permission() >= 2, topic.id() + " shows an operator line to everybody");
                }
            }
        }
    }

    @Test
    void pagesStayShort() {
        assertTrue(CrimeHelpPages.PAGE_LINES <= 10, "a help page is a few lines, not a manual");
    }

    private static Set<String> keysOf(Component component) {
        Set<String> keys = new TreeSet<>();
        collect(component, keys);
        return keys;
    }

    private static void collect(Component component, Set<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested) {
                    collect(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collect(sibling, keys);
        }
    }

    private static void check(Component component, JsonObject lang, Set<String> problems, String where) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            if (!lang.has(key)) {
                problems.add(where + ": missing key " + key);
            } else {
                int expected = placeholders(lang.get(key).getAsString());
                if (expected != translatable.getArgs().length) {
                    problems.add(where + ": " + key + " has " + expected + " placeholder(s) but "
                            + translatable.getArgs().length + " argument(s)");
                }
            }
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested) {
                    check(nested, lang, problems, where);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            check(sibling, lang, problems, where);
        }
    }

    /** Distinct argument slots a lang string consumes: %s in order, or %n$s by index. */
    static int placeholders(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text.replace("%%", ""));
        int sequential = 0;
        int highestIndexed = 0;
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                highestIndexed = Math.max(highestIndexed, Integer.parseInt(matcher.group(1)));
            } else {
                sequential++;
            }
        }
        return Math.max(sequential, highestIndexed);
    }
}
