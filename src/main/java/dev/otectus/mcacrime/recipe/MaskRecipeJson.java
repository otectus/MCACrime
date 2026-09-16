package dev.otectus.mcacrime.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shape of a {@code mcacrime:mask_making} recipe file, checked before a single registry is touched
 * (0.7.2 §7.3-§7.5).
 *
 * <p>This class is deliberately free of {@code Ingredient}, {@code ItemStack} and every registry: the
 * malformed-field matrix (CRAFT-10) is the part that has to be provable in a unit test, and a check
 * that needs a bootstrapped game to run is a check nobody runs. What is left to the serializer is
 * exactly the part that genuinely needs the registries — resolving the ingredient and the result item
 * — and that resolution only ever runs against a file this class has already accepted.
 *
 * <p>Every rejection is collected rather than thrown at the first one. A pack author fixing a recipe
 * wants the whole list, not one line per reload.
 */
public final class MaskRecipeJson {

    /** The biggest counted ingredient a two-slot station can ask for: one full stack. */
    public static final int MAX_INGREDIENT_COUNT = 64;
    /** The widest sort key accepted, so an ordering field cannot carry a payload. */
    public static final int MAX_SORT_ORDER = 100_000;
    /** The longest group string accepted. Group names are looked up and shown, never parsed. */
    public static final int MAX_GROUP_LENGTH = 128;

    /**
     * One accepted recipe file, as plain values.
     *
     * @param materialCount how many of the material the recipe consumes
     * @param bindingCount  how many of the binding the recipe consumes
     */
    public record Fields(MaskOperation operation, String group, int materialCount, int bindingCount,
                         boolean allowDye, ResourceLocation result, int resultCount, int sortOrder) {
    }

    private MaskRecipeJson() {
    }

    /**
     * Every problem with this file, in reading order. Empty means {@link #parse} will succeed.
     */
    public static List<String> problems(JsonObject json) {
        List<String> problems = new ArrayList<>();
        if (json == null) {
            problems.add("recipe is not a JSON object");
            return problems;
        }
        String operation = string(json, "operation").orElse(null);
        if (operation == null) {
            problems.add("operation: missing or not a string");
        } else if (MaskOperation.parse(operation).isEmpty()) {
            problems.add("operation: '" + operation + "' is not 'craft' or 'restyle'");
        }

        Optional<String> group = string(json, "group");
        if (json.has("group") && group.isEmpty()) {
            problems.add("group: not a string");
        } else if (group.orElse("").length() > MAX_GROUP_LENGTH) {
            problems.add("group: longer than " + MAX_GROUP_LENGTH + " characters");
        }

        checkSlot(json, "material", problems);
        checkSlot(json, "binding", problems);

        if (json.has("allow_dye") && !isBoolean(json.get("allow_dye"))) {
            problems.add("allow_dye: not a boolean");
        }

        JsonObject result = object(json, "result").orElse(null);
        if (result == null) {
            problems.add("result: missing or not an object");
        } else {
            String item = string(result, "item").orElse(null);
            if (item == null) {
                problems.add("result.item: missing or not a string");
            } else if (ResourceLocation.tryParse(item) == null) {
                problems.add("result.item: '" + item + "' is not a resource location");
            }
            if (result.has("count")) {
                Integer count = integer(result, "count").orElse(null);
                if (count == null) {
                    problems.add("result.count: not an integer");
                } else if (count != 1) {
                    // §7.5: a mask operation makes exactly one mask. A stack of four would be four
                    // identities from one face, and the restyle path could not say which one was worn.
                    problems.add("result.count: must be 1 for a mask operation, was " + count);
                }
            }
        }

        if (json.has("sort_order")) {
            Integer sort = integer(json, "sort_order").orElse(null);
            if (sort == null) {
                problems.add("sort_order: not an integer");
            } else if (Math.abs(sort) > MAX_SORT_ORDER) {
                problems.add("sort_order: outside +/-" + MAX_SORT_ORDER);
            }
        }
        return problems;
    }

    /** The accepted fields, or empty when {@link #problems} found anything. */
    public static Optional<Fields> parse(JsonObject json) {
        if (!problems(json).isEmpty()) {
            return Optional.empty();
        }
        JsonObject result = object(json, "result").orElseThrow();
        return Optional.of(new Fields(
                MaskOperation.parse(string(json, "operation").orElseThrow()).orElseThrow(),
                string(json, "group").orElse(""),
                count(json, "material"),
                count(json, "binding"),
                !json.has("allow_dye") || json.get("allow_dye").getAsBoolean(),
                ResourceLocation.tryParse(string(result, "item").orElseThrow()),
                1,
                json.has("sort_order") ? json.get("sort_order").getAsInt() : 0));
    }

    /** The {@code ingredient} element of a validated slot, for the serializer to resolve. */
    public static JsonElement ingredient(JsonObject json, String slot) {
        return json.getAsJsonObject(slot).get("ingredient");
    }

    /** The validated count of a slot. */
    public static int count(JsonObject json, String slot) {
        JsonObject object = json.getAsJsonObject(slot);
        return object.has("count") ? object.get("count").getAsInt() : 1;
    }

    private static void checkSlot(JsonObject json, String slot, List<String> problems) {
        JsonObject object = object(json, slot).orElse(null);
        if (object == null) {
            problems.add(slot + ": missing or not an object");
            return;
        }
        JsonElement ingredient = object.get("ingredient");
        if (ingredient == null || (!ingredient.isJsonObject() && !ingredient.isJsonArray())) {
            problems.add(slot + ".ingredient: missing, or not an ingredient object or array");
        } else if (ingredient.isJsonArray() && ((JsonArray) ingredient).isEmpty()) {
            problems.add(slot + ".ingredient: empty ingredient array");
        } else if (ingredient.isJsonObject()) {
            JsonObject one = ingredient.getAsJsonObject();
            String named = string(one, "item").or(() -> string(one, "tag")).orElse(null);
            if (!one.has("item") && !one.has("tag")) {
                problems.add(slot + ".ingredient: neither 'item' nor 'tag'");
            } else if (named == null) {
                problems.add(slot + ".ingredient: 'item'/'tag' is not a string");
            } else if (ResourceLocation.tryParse(named) == null) {
                problems.add(slot + ".ingredient: '" + named + "' is not a resource location");
            }
        }
        if (object.has("count")) {
            Integer count = integer(object, "count").orElse(null);
            if (count == null) {
                problems.add(slot + ".count: not an integer");
            } else if (count < 1) {
                problems.add(slot + ".count: must be at least 1, was " + count);
            } else if (count > MAX_INGREDIENT_COUNT) {
                problems.add(slot + ".count: at most " + MAX_INGREDIENT_COUNT + ", was " + count);
            }
        }
    }

    private static Optional<JsonObject> object(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonObject() ? Optional.of(element.getAsJsonObject()) : Optional.empty();
    }

    private static Optional<String> string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isString()) {
            return Optional.empty();
        }
        return Optional.of(element.getAsString());
    }

    private static Optional<Integer> integer(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isNumber()) {
            return Optional.empty();
        }
        double value = element.getAsDouble();
        return value == Math.floor(value) ? Optional.of(element.getAsInt()) : Optional.empty();
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive() && ((JsonPrimitive) element).isBoolean();
    }
}
