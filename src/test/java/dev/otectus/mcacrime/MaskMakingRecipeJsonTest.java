package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.recipe.MaskOperation;
import dev.otectus.mcacrime.recipe.MaskRecipeJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRAFT-10, as a unit test: every malformed field a pack author can write, refused with a diagnostic.
 *
 * <p>This is why the JSON contract is validated by a class with no registry in it. "Feed malformed
 * JSON, excessive counts, unknown results or empty required tags" is a matrix, and a matrix nobody can
 * run without launching a game is a matrix nobody runs.
 */
class MaskMakingRecipeJsonTest {

    private static final String GOOD = """
            {
              "type": "mcacrime:mask_making",
              "operation": "craft",
              "group": "mcacrime:clay_masks",
              "material": { "ingredient": { "item": "minecraft:clay_ball" }, "count": 4 },
              "binding": { "ingredient": { "item": "minecraft:string" }, "count": 2 },
              "allow_dye": true,
              "result": { "id": "mcacrime:clay_mask", "count": 1 },
              "sort_order": 3
            }""";

    private static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** The good file with one field replaced by {@code value} (a raw JSON fragment). */
    private static JsonObject with(String field, String value) {
        JsonObject object = json(GOOD);
        object.add(field, JsonParser.parseString(value));
        return object;
    }

    private static List<String> problems(JsonObject object) {
        return MaskRecipeJson.problems(object);
    }

    private static void rejects(JsonObject object, String mentioning) {
        List<String> problems = problems(object);
        assertFalse(problems.isEmpty(), "expected a rejection mentioning " + mentioning);
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(mentioning)),
                "expected a problem mentioning " + mentioning + ", got " + problems);
        assertTrue(MaskRecipeJson.parse(object).isEmpty(), "a rejected file must not parse");
    }

    @Test
    void theShippedShapeIsAccepted() {
        assertEquals(List.of(), problems(json(GOOD)));
        MaskRecipeJson.Fields fields = MaskRecipeJson.parse(json(GOOD)).orElseThrow();
        assertEquals(MaskOperation.CRAFT, fields.operation());
        assertEquals("mcacrime:clay_masks", fields.group());
        assertEquals(4, fields.materialCount());
        assertEquals(2, fields.bindingCount());
        assertTrue(fields.allowDye());
        assertEquals("mcacrime:clay_mask", fields.result().toString());
        assertEquals(1, fields.resultCount());
        assertEquals(3, fields.sortOrder());
    }

    @Test
    void optionalFieldsHaveTheDocumentedDefaults() {
        JsonObject object = json(GOOD);
        object.remove("allow_dye");
        object.remove("sort_order");
        object.remove("group");
        MaskRecipeJson.Fields fields = MaskRecipeJson.parse(object).orElseThrow();
        assertTrue(fields.allowDye(), "allow_dye defaults to true");
        assertEquals(0, fields.sortOrder());
        assertEquals("", fields.group());
    }

    @Test
    void anUnsupportedOperationIsRefusedRatherThanDefaulted() {
        rejects(with("operation", "\"enchant\""), "operation");
        JsonObject missing = json(GOOD);
        missing.remove("operation");
        rejects(missing, "operation");
        rejects(with("operation", "7"), "operation");
    }

    @Test
    void restyleIsAnAcceptedOperation() {
        assertEquals(MaskOperation.RESTYLE,
                MaskRecipeJson.parse(with("operation", "\"restyle\"")).orElseThrow().operation());
    }

    @Test
    void aResultMustBeOneRegisteredLookingItem() {
        JsonObject missing = json(GOOD);
        missing.remove("result");
        rejects(missing, "result");
        rejects(with("result", "{ \"count\": 1 }"), "result.id");
        rejects(with("result", "{ \"id\": \"not a resource location\" }"), "result.id");
        rejects(with("result", "{ \"id\": \"mcacrime:clay_mask\", \"count\": 4 }"), "result.count");
        rejects(with("result", "{ \"id\": \"mcacrime:clay_mask\", \"count\": 0 }"), "result.count");
        rejects(with("result", "\"mcacrime:clay_mask\""), "result");
        // 1.21 renamed result.item to result.id. The old spelling is a load failure, so it must be
        // refused by name rather than treated as a result block that happens to lack an id.
        rejects(with("result", "{ \"item\": \"mcacrime:clay_mask\", \"count\": 1 }"), "result.id");
    }

    @Test
    void ingredientCountsMustBePositiveAndBounded() {
        rejects(with("material", "{ \"ingredient\": { \"item\": \"minecraft:clay_ball\" }, \"count\": 0 }"),
                "material.count");
        rejects(with("binding", "{ \"ingredient\": { \"item\": \"minecraft:string\" }, \"count\": -3 }"),
                "binding.count");
        rejects(with("material", "{ \"ingredient\": { \"item\": \"minecraft:clay_ball\" }, \"count\": 65 }"),
                "material.count");
        rejects(with("material", "{ \"ingredient\": { \"item\": \"minecraft:clay_ball\" }, \"count\": 1.5 }"),
                "material.count");
    }

    @Test
    void aSlotWithoutAUsableIngredientIsRefused() {
        JsonObject missing = json(GOOD);
        missing.remove("binding");
        rejects(missing, "binding");
        rejects(with("material", "{ \"count\": 4 }"), "material.ingredient");
        rejects(with("material", "{ \"ingredient\": [] }"), "material.ingredient");
        rejects(with("material", "{ \"ingredient\": { \"count\": 4 } }"), "material.ingredient");
        rejects(with("material", "{ \"ingredient\": { \"tag\": \"not a tag id\" } }"), "material.ingredient");
        rejects(with("material", "{ \"ingredient\": { \"item\": 4 } }"), "material.ingredient");
    }

    @Test
    void aTagIngredientIsAcceptedBecauseRestyleRecipesNeedOne() {
        assertEquals(List.of(),
                problems(with("material", "{ \"ingredient\": { \"tag\": \"mcacrime:masks\" }, \"count\": 1 }")));
    }

    @Test
    void oversizedAndMistypedPayloadFieldsAreRefused() {
        rejects(with("group", "\"" + "x".repeat(200) + "\""), "group");
        rejects(with("group", "17"), "group");
        rejects(with("sort_order", "999999"), "sort_order");
        rejects(with("sort_order", "\"first\""), "sort_order");
        rejects(with("allow_dye", "\"yes\""), "allow_dye");
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        JsonObject broken = json("""
                {
                  "operation": "melt",
                  "material": { "ingredient": { "item": "minecraft:clay_ball" }, "count": 0 },
                  "binding": { "ingredient": { "item": "minecraft:string" }, "count": -1 },
                  "result": { "id": "mcacrime:clay_mask", "count": 9 }
                }""");
        assertEquals(4, problems(broken).size(), "a pack author gets the whole list: " + problems(broken));
    }

    @Test
    void nothingIsNotARecipe() {
        assertFalse(problems(null).isEmpty());
        assertTrue(MaskRecipeJson.parse(null).isEmpty());
    }
}
