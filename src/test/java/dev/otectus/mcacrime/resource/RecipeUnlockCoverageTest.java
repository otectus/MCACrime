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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every fixed recipe shows up in the vanilla recipe book (0.8.0, section 7): a recipe with no unlock
 * advancement is never offered in the book until a player happens to craft it by hand, which for a pillory
 * or a padlock means never. Each one needs vanilla's own shape -- a {@code recipe_unlocked} criterion for
 * itself, at least one {@code inventory_changed} criterion for an ingredient, every criterion in the one
 * requirement group, and the recipe as the reward.
 *
 * <p>This mod's dynamic recipes (key copies, molds, rings, the Mask Station's) are deliberately left out of
 * the book: their results depend on the stack's data, and {@code /crime help} teaches them instead.
 */
class RecipeUnlockCoverageTest {

    private static final Path DATA = Path.of("src", "main", "resources", "data", "mcacrime");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyFixedRecipeHasAVanillaUnlockAdvancement() throws IOException {
        Map<String, JsonObject> unlocks = new HashMap<>();
        try (Stream<Path> files = Files.walk(DATA.resolve("advancements/recipes"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                unlocks.put(file.getFileName().toString().replace(".json", ""), json(file));
            }
        }
        List<String> problems = new ArrayList<>();
        int fixed = 0;
        try (Stream<Path> files = Files.list(DATA.resolve("recipes"))) {
            for (Path recipe : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                String type = json(recipe).get("type").getAsString();
                if (!type.startsWith("minecraft:")) {
                    continue;
                }
                fixed++;
                String name = recipe.getFileName().toString().replace(".json", "");
                String id = "mcacrime:" + name;
                JsonObject unlock = unlocks.get(name);
                if (unlock == null) {
                    problems.add(id + " has no unlock advancement");
                    continue;
                }
                JsonObject criteria = unlock.getAsJsonObject("criteria");
                boolean unlocked = false;
                boolean ingredient = false;
                for (Map.Entry<String, JsonElement> criterion : criteria.entrySet()) {
                    JsonObject body = criterion.getValue().getAsJsonObject();
                    String trigger = body.get("trigger").getAsString();
                    if (trigger.equals("minecraft:recipe_unlocked")) {
                        unlocked |= body.getAsJsonObject("conditions").get("recipe").getAsString().equals(id);
                    } else if (trigger.equals("minecraft:inventory_changed")) {
                        ingredient = true;
                    }
                }
                Set<String> required = new HashSet<>();
                unlock.getAsJsonArray("requirements").get(0).getAsJsonArray().forEach(e -> required.add(e.getAsString()));
                if (!unlocked) problems.add(id + ": no recipe_unlocked criterion for itself");
                if (!ingredient) problems.add(id + ": no ingredient criterion");
                if (!required.equals(criteria.keySet())) problems.add(id + ": requirements " + required + " are not its criteria");
                if (!unlock.getAsJsonObject("rewards").getAsJsonArray("recipes").toString().equals("[\"" + id + "\"]")) {
                    problems.add(id + ": rewards " + unlock.getAsJsonObject("rewards"));
                }
                if (!"minecraft:recipes/root".equals(unlock.get("parent").getAsString())) {
                    problems.add(id + ": not under minecraft:recipes/root");
                }
            }
        }
        assertTrue(fixed >= 30, "only " + fixed + " fixed recipes found");
        assertTrue(problems.isEmpty(), "recipe book gaps:\n  " + String.join("\n  ", problems));
    }
}
