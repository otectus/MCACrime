package dev.otectus.mcacrime.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.TestPaths;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No recipe yields more of an item than one stack of it can hold.
 *
 * <h2>Why this exists</h2>
 * The imported source registered several single-item tools with durability and then shipped crafting
 * recipes that yield four of each. On 1.21.1 that is not a balance quirk but an unloadable file: the
 * codec refuses it with <em>"Item stack with stack size of 4 was larger than maximum: 1"</em>, and
 * the recipe silently never exists. Those tools are gone from this mod, but this is the check that
 * keeps the class of mistake from coming back — with a datapack the game refuses to load, the symptom
 * an operator sees is a recipe that is simply missing.
 *
 * <h2>How the ceiling is known without a game</h2>
 * By reading {@code item/CrimeItems} itself: a {@code stacksTo(n)} gives the ceiling outright, and a
 * {@code durability(...)} implies one, because a damageable item cannot stack. Anything this mod
 * registers without either is vanilla's 64. That keeps the two files in step — raising an item's
 * stack size relaxes this test in the same edit — and needs no registry, no world and no config.
 */
class RecipeResultStackSizeTest {

    private static final Path RECIPES = TestPaths.resources("data", "mcacrime", "recipe");
    private static final Path ITEMS = TestPaths.sources("dev", "otectus", "mcacrime", "item",
            "CrimeItems.java");

    /** Ids this mod does not register, with the vanilla ceiling they are known to have. */
    private static final Map<String, Integer> VANILLA_LIMITS = Map.of("minecraft:bundle", 1);

    @Test
    void noRecipeYieldsMoreThanOneStack() {
        Map<String, Integer> limits = registeredLimits();
        assertTrue(limits.size() > 5, "expected CrimeItems to be read, found " + limits.size());

        List<String> offenders = new ArrayList<>();
        for (Path file : recipeFiles()) {
            JsonObject root = read(file);
            JsonObject result = root.getAsJsonObject("result");
            if (result == null || !result.has("id")) {
                continue; // a recipe whose result is not a literal stack (nothing ships one today)
            }
            String id = result.get("id").getAsString();
            int count = result.has("count") ? result.get("count").getAsInt() : 1;
            Integer max = limits.containsKey(id) ? limits.get(id) : VANILLA_LIMITS.get(id);
            if (max == null) {
                continue; // a block item or a vanilla stackable: 64, which no recipe here approaches
            }
            if (count > max) {
                offenders.add(RECIPES.relativize(file) + ": " + count + " x " + id
                        + ", which stacks to " + max);
            }
        }
        assertEquals(List.of(), offenders,
                "1.21.1 refuses to load a recipe whose result is bigger than one stack, so each of "
                        + "these recipes would simply not exist in game");
    }

    /** Every recipe is readable JSON with a type, so a typo cannot hide behind "no result". */
    @Test
    void everyRecipeFileParsesAndNamesAType() {
        List<Path> files = recipeFiles();
        assertTrue(files.size() > 30, "expected the recipe tree to be read, found " + files.size());
        List<String> broken = new ArrayList<>();
        for (Path file : files) {
            JsonObject root = read(file);
            if (!root.has("type")) {
                broken.add(RECIPES.relativize(file).toString());
            }
        }
        assertEquals(List.of(), broken, "a recipe without a type is not a recipe");
    }

    /** {@code id -> maximum stack size}, for every item {@code CrimeItems} registers explicitly. */
    private static Map<String, Integer> registeredLimits() {
        String source = text(ITEMS);
        Map<String, Integer> limits = new LinkedHashMap<>();
        Matcher registration = Pattern.compile("ITEMS\\.register\\(\\s*\"([A-Za-z0-9_]+)\"")
                .matcher(source);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (registration.find()) {
            names.add(registration.group(1));
            spans.add(new int[]{registration.end(), source.length()});
        }
        for (int i = 0; i < spans.size(); i++) {
            if (i + 1 < spans.size()) {
                spans.get(i)[1] = spans.get(i + 1)[0];
            }
            String body = source.substring(spans.get(i)[0], spans.get(i)[1]);
            Matcher stacksTo = Pattern.compile("stacksTo\\((\\d+)\\)").matcher(body);
            Matcher durability = Pattern.compile("\\.durability\\(").matcher(body);
            if (stacksTo.find()) {
                limits.put("mcacrime:" + names.get(i), Integer.parseInt(stacksTo.group(1)));
            } else if (durability.find()) {
                limits.put("mcacrime:" + names.get(i), 1); // damageable items never stack
            }
        }
        return limits;
    }

    private static List<Path> recipeFiles() {
        try (Stream<Path> walk = Files.walk(RECIPES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + RECIPES.toAbsolutePath(), e);
        }
    }

    private static JsonObject read(Path file) {
        JsonElement parsed = JsonParser.parseString(text(file));
        assertTrue(parsed.isJsonObject(), file + " is not a JSON object");
        return parsed.getAsJsonObject();
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file.toAbsolutePath(), e);
        }
    }
}
