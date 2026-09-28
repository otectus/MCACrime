package dev.otectus.mcacrime.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every key {@code McaCrimeConfig} declares, read out of its source (0.7.5 M7.1).
 *
 * <h2>Why the source and not the spec</h2>
 * A {@code ForgeConfigSpec} exists only once Forge has built one, and these tests deliberately run
 * without Forge, without a game and without a config directory — the same isolation that makes
 * "MCA absent" genuinely exercised. The declarations are plain text in one file, in order, so reading
 * them is exact: there is no second place a key can be declared, and a key that is not in this list is
 * not in the mod.
 *
 * <p>It is also the only reading that can see a <em>duplicate</em>. A spec silently keeps one of two
 * identical paths; the source shows both.
 */
final class ConfigKeyIndex {

    /** One declared key: its section path, its name, how it was declared and where. */
    record Key(String spec, String group, String name, String kind, int line) {

        /** The dotted path an operator sees in their TOML and in {@code CONFIG.md}. */
        String path() {
            return group.isEmpty() ? name : group + "." + name;
        }
    }

    private static final Path SOURCE = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "McaCrimeConfig.java");

    private static final Pattern DECLARATION = Pattern.compile(
            "\\.(push|pop|define|defineInRange|defineEnum|defineList|defineListAllowEmpty)\\s*\\("
                    + "\\s*(?:\"([A-Za-z0-9_]+)\")?");

    private ConfigKeyIndex() {
    }

    /** Every key in declaration order. */
    static List<Key> keys() {
        List<String> lines = source();
        String text = String.join("\n", lines);
        int clientAt = text.indexOf("public static final class Client");
        int commonAt = text.indexOf("public static final class Common");

        Deque<String> stack = new ArrayDeque<>();
        List<Key> keys = new ArrayList<>();
        Matcher matcher = DECLARATION.matcher(text);
        while (matcher.find()) {
            if (matcher.start() < commonAt) {
                continue; // field declarations and javadoc above the constructors
            }
            String call = matcher.group(1);
            String name = matcher.group(2);
            switch (call) {
                case "push" -> {
                    if (name != null) {
                        stack.addLast(name);
                    }
                }
                case "pop" -> stack.pollLast();
                default -> {
                    if (name != null) {
                        keys.add(new Key(matcher.start() < clientAt ? "COMMON" : "CLIENT",
                                String.join(".", stack), name, call,
                                lineOf(text, matcher.start())));
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    /** The config source with block and line comments blanked, so prose cannot be read as code. */
    private static List<String> source() {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : read(SOURCE)) {
            String trimmed = raw.trim();
            if (inBlock) {
                out.add("");
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                out.add("");
                inBlock = !trimmed.contains("*/");
                continue;
            }
            out.add(trimmed.startsWith("*") || trimmed.startsWith("//") ? "" : raw);
        }
        return out;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path.toAbsolutePath(), e);
        }
    }
}
