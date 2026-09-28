package dev.otectus.mcacrime.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recipe may not craft a stack that cannot exist (0.7.5, transfer defect).
 *
 * <p>Upstream registered several {@code stacksTo(1)} tools with durability and then gave each of them
 * a recipe yielding <b>four</b>. Vanilla clamps the result to the item's maximum on craft, so three of
 * the four are simply destroyed: the player pays four ingots' worth of iron and receives one tool.
 * Those tools are gone from this mod, but the rule stays — the next time somebody writes a count next
 * to a one-per-stack item, it fails here.
 *
 * <p>The stack limits are read out of {@code item/CrimeItems} rather than restated, because a
 * restatement is a second source of truth that goes stale the first time an item's properties change.
 * Items registered under a name this parser cannot see as a literal — the sixteen masks, registered in
 * a loop — resolve to a limit of one, which is what they are: {@code MaskItem} is armour with
 * durability, and durability implies {@code stacksTo(1)}.
 */
class RecipeResultStackSizeTest {

    private static final Path RECIPES = Path.of("src", "main", "resources", "data", "mcacrime", "recipes");
    private static final Path CRIME_ITEMS =
            Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "item", "CrimeItems.java");

    /** {@code ITEMS.register("padlock",} — the literal-named registrations. */
    private static final Pattern REGISTRATION = Pattern.compile("ITEMS\\.register\\(\\s*\"([a-z0-9_]+)\"");
    private static final Pattern STACKS_TO = Pattern.compile("stacksTo\\((\\d+)\\)");
    private static final Pattern DURABILITY = Pattern.compile("\\.durability\\(");

    /**
     * Vanilla results these recipes produce, and their real limits.
     *
     * <p>Only {@code minecraft:bundle} is one today (the hood restraint's recipe, R07). Anything else
     * from another namespace is assumed to be an ordinary 64, which is the direction that cannot hide
     * one of our own defects.
     */
    private static final Map<String, Integer> FOREIGN_LIMITS = Map.of("minecraft:bundle", 1);

    private static final int DEFAULT_FOREIGN_LIMIT = 64;

    @Test
    void noRecipeCraftsMoreThanItsResultCanStack() {
        Map<String, Integer> limits = stackLimits();
        List<String> problems = new ArrayList<>();
        for (Path file : recipeFiles()) {
            JsonObject root = json(file);
            Result result = result(root);
            if (result == null) {
                continue; // a serializer whose output is computed, not declared
            }
            int limit = limit(result.item(), limits);
            if (result.count() > limit) {
                problems.add(RECIPES.relativize(file) + " yields " + result.count() + " × "
                        + result.item() + ", which stacks to " + limit);
            }
        }
        assertTrue(problems.isEmpty(), "Recipes crafting an impossible stack: " + problems);
    }

    /** The parser itself has to stay honest, or the rule above silently passes everything. */
    @Test
    void stackLimitsComeOutOfTheItemRegistrations() {
        Map<String, Integer> limits = stackLimits();
        assertFalse(limits.isEmpty(), "no item registrations parsed out of CrimeItems");
        assertEquals(1, limits.get("lockpick"), "lockpick is registered with durability");
        assertEquals(1, limits.get("restraint_cuffs"), "cuffs are registered stacksTo(1)");
        assertEquals(16, limits.get("sand_bottle"), "sand bottle is registered stacksTo(16)");
        assertEquals(16, limits.get("padlock"), "padlock is registered stacksTo(16)");
        assertEquals(64, limits.get("duck_tape"), "duct tape takes the default stack size");
    }

    private static int limit(String item, Map<String, Integer> limits) {
        if (item.startsWith("mcacrime:")) {
            // Unknown means registered under a computed name: the masks, which are armour with
            // durability. One is the true answer for those and the strict answer for anything new.
            return limits.getOrDefault(item.substring("mcacrime:".length()), 1);
        }
        return FOREIGN_LIMITS.getOrDefault(item, DEFAULT_FOREIGN_LIMIT);
    }

    /** {@code (item, count)} of a recipe's declared result, or null when it declares none. */
    private record Result(String item, int count) {
    }

    private static Result result(JsonObject root) {
        if (!root.has("result")) {
            return null;
        }
        JsonElement result = root.get("result");
        if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
            // Stonecutting: the result is the id and the count sits beside it.
            int count = root.has("count") ? root.get("count").getAsInt() : 1;
            return new Result(result.getAsString(), count);
        }
        if (!result.isJsonObject()) {
            return null;
        }
        JsonObject object = result.getAsJsonObject();
        if (!object.has("item")) {
            return null;
        }
        int count = object.has("count") ? object.get("count").getAsInt() : 1;
        return new Result(object.get("item").getAsString(), count);
    }

    private static Map<String, Integer> stackLimits() {
        String source = read(CRIME_ITEMS);
        Map<String, Integer> limits = new LinkedHashMap<>();
        Matcher matcher = REGISTRATION.matcher(source);
        List<String> names = new ArrayList<>();
        List<Integer> bodyStarts = new ArrayList<>();
        List<Integer> matchStarts = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
            bodyStarts.add(matcher.end());
            matchStarts.add(matcher.start());
        }
        for (int i = 0; i < names.size(); i++) {
            // Up to the next registration, so one entry's properties cannot leak into another's.
            int end = i + 1 < matchStarts.size() ? matchStarts.get(i + 1) : source.length();
            limits.put(names.get(i), limitOf(source.substring(bodyStarts.get(i), end)));
        }
        return limits;
    }

    private static int limitOf(String registrationBody) {
        if (DURABILITY.matcher(registrationBody).find()) {
            return 1; // vanilla forces a damageable item to a single-item stack
        }
        Matcher stacks = STACKS_TO.matcher(registrationBody);
        return stacks.find() ? Integer.parseInt(stacks.group(1)) : 64;
    }

    private static List<Path> recipeFiles() {
        try (Stream<Path> walk = Files.walk(RECIPES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject json(Path path) {
        return JsonParser.parseString(read(path)).getAsJsonObject();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
