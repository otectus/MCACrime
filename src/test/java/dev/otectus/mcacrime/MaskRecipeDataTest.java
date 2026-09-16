package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.recipe.MaskOperation;
import dev.otectus.mcacrime.recipe.MaskRecipeJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped station data, read the way the game reads it (0.7.2 §7.1-§7.3, MASK-01).
 *
 * <p>"All four styles from the same inputs" is a claim about thirty-two JSON files, not about a Java
 * class, so it is checked against the files themselves with the same validator the serializer runs.
 * A recipe that names a style nobody registered, or a cloth recipe that quietly costs two wool, is a
 * bug no amount of correct code prevents.
 */
class MaskRecipeDataTest {

    private static final Path STATION =
            Path.of("src", "main", "resources", "data", "mcacrime", "recipes", "mask_station");

    private record Recipe(Path path, JsonObject json, MaskRecipeJson.Fields fields) {
    }

    private static List<Recipe> shipped(MaskFamily family) {
        Path dir = STATION.resolve(family.key());
        assertTrue(Files.isDirectory(dir), "no shipped recipes for the " + family.key() + " family");
        List<Recipe> recipes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                JsonObject json = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> problems = MaskRecipeJson.problems(json);
                assertTrue(problems.isEmpty(), file + " is not a valid station recipe: " + problems);
                recipes.add(new Recipe(file, json, MaskRecipeJson.parse(json).orElseThrow()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return recipes;
    }

    private static List<Recipe> of(List<Recipe> recipes, MaskOperation operation) {
        return recipes.stream().filter(recipe -> recipe.fields().operation() == operation).toList();
    }

    /** The material and binding blocks verbatim — the thing four styles in a family must share. */
    private static String cost(Recipe recipe) {
        return recipe.json().get("material") + " + " + recipe.json().get("binding");
    }

    @Test
    void everyFamilyShipsFourCraftsAndFourRestyles() {
        for (MaskFamily family : MaskFamily.values()) {
            List<Recipe> recipes = shipped(family);
            assertEquals(8, recipes.size(), family.key() + " should ship four crafts and four restyles");
            assertEquals(4, of(recipes, MaskOperation.CRAFT).size());
            assertEquals(4, of(recipes, MaskOperation.RESTYLE).size());
        }
    }

    /** §7.1: same materials, four different faces. Identical inputs, not a randomized output. */
    @Test
    void theFourCraftsInAFamilyCostExactlyTheSame() {
        for (MaskFamily family : MaskFamily.values()) {
            Set<String> costs = new HashSet<>();
            for (Recipe recipe : of(shipped(family), MaskOperation.CRAFT)) {
                costs.add(cost(recipe));
            }
            assertEquals(1, costs.size(),
                    family.key() + " crafts must share one cost, found " + costs);
        }
    }

    @Test
    void theShippedCostsAreTheDocumentedOnes() {
        Map<MaskFamily, String> expected = Map.of(
                MaskFamily.CLOTH, "{\"tag\":\"minecraft:wool\"} x1 + {\"item\":\"minecraft:string\"} x1",
                MaskFamily.LEATHER, "{\"item\":\"minecraft:leather\"} x2 + {\"item\":\"minecraft:string\"} x1",
                MaskFamily.CLAY, "{\"item\":\"minecraft:clay_ball\"} x4 + {\"item\":\"minecraft:string\"} x2",
                MaskFamily.METAL, "{\"item\":\"minecraft:iron_ingot\"} x2 + {\"item\":\"minecraft:leather\"} x1");
        for (MaskFamily family : MaskFamily.values()) {
            for (Recipe recipe : of(shipped(family), MaskOperation.CRAFT)) {
                String actual = MaskRecipeJson.ingredient(recipe.json(), "material")
                        + " x" + MaskRecipeJson.count(recipe.json(), "material") + " + "
                        + MaskRecipeJson.ingredient(recipe.json(), "binding")
                        + " x" + MaskRecipeJson.count(recipe.json(), "binding");
                assertEquals(expected.get(family), actual, recipe.path() + " charges the wrong price");
            }
        }
    }

    /** One craft per registered style, sorted 0..3, all in the family's own group. */
    @Test
    void everyStyleHasExactlyOneCraftInItsOwnFamily() {
        for (MaskFamily family : MaskFamily.values()) {
            Map<Integer, String> byOrder = new TreeMap<>();
            for (Recipe recipe : of(shipped(family), MaskOperation.CRAFT)) {
                MaskVariant style = MaskVariant.byStyleId(recipe.fields().result().toString())
                        .orElseThrow(() -> new AssertionError(
                                recipe.path() + " makes " + recipe.fields().result()
                                        + ", which is not a registered mask style"));
                assertEquals(family, style.family(), recipe.path() + " is filed under the wrong family");
                assertEquals(family.recipeGroup(), recipe.fields().group(), recipe.path().toString());
                assertTrue(recipe.fields().allowDye(), recipe.path() + " must accept the optional dye");
                assertEquals(1, recipe.fields().resultCount());
                assertEquals(style.sortOrder(), recipe.fields().sortOrder(),
                        recipe.path() + " does not sort where the catalogue says it does");
                assertTrue(byOrder.put(recipe.fields().sortOrder(), style.styleId()) == null,
                        recipe.path() + " shares a sort order with " + byOrder.get(recipe.fields().sortOrder()));
            }
            assertEquals(Set.of(0, 1, 2, 3), byOrder.keySet(), family.key() + " sort orders");
            for (MaskVariant style : MaskVariant.of(family)) {
                assertTrue(byOrder.containsValue(style.styleId()), style.styleId() + " has no craft recipe");
            }
        }
    }

    /** §7.4: one restyle per style, drawing on the family sub-tag and one binding. */
    @Test
    void everyStyleHasExactlyOneRestyleFromItsOwnFamilyTag() {
        for (MaskFamily family : MaskFamily.values()) {
            Set<String> results = new HashSet<>();
            for (Recipe recipe : of(shipped(family), MaskOperation.RESTYLE)) {
                MaskVariant style = MaskVariant.byStyleId(recipe.fields().result().toString())
                        .orElseThrow(() -> new AssertionError(
                                recipe.path() + " restyles into an unregistered style"));
                assertEquals(family, style.family(), recipe.path() + " crosses a family boundary");
                assertTrue(results.add(style.styleId()), style.styleId() + " has two restyle recipes");
                assertEquals("{\"tag\":\"mcacrime:masks/" + family.key() + "\"}",
                        MaskRecipeJson.ingredient(recipe.json(), "material").toString(),
                        recipe.path() + " must consume any mask of its own family");
                assertEquals(1, MaskRecipeJson.count(recipe.json(), "material"),
                        "a restyle consumes exactly one mask");
                assertEquals(1, MaskRecipeJson.count(recipe.json(), "binding"),
                        "a restyle costs exactly one binding");
                assertTrue(recipe.fields().allowDye());
                assertEquals(family.recipeGroup(), recipe.fields().group());
            }
            assertEquals(4, results.size(), family.key() + " must offer a restyle into all four styles");
        }
    }

    /** The two 0.7.0 crafting-table recipes are still shipped under their own ids (§5.5). */
    @Test
    void theOriginalCraftingTableRecipesSurvive() {
        Path recipes = Path.of("src", "main", "resources", "data", "mcacrime", "recipes");
        assertTrue(Files.isRegularFile(recipes.resolve("clay_mask.json")));
        assertTrue(Files.isRegularFile(recipes.resolve("leather_mask.json")));
    }
}
