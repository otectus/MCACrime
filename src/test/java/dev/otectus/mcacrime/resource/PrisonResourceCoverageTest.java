package dev.otectus.mcacrime.resource;

import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every prison block and tag M5 registers ships what it needs to exist (0.7.5 M5.12).
 *
 * <p>The same rule {@code DetentionResourceCoverageTest} applies to M4: a registry entry with no
 * model, no texture, no display name or no way to obtain it is an unfinished feature, and it should
 * fail here rather than in somebody's world.
 */
class PrisonResourceCoverageTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "mcacrime");
    private static final Path DATA = Path.of("src", "main", "resources", "data", "mcacrime");
    private static final Path VANILLA_TAGS = Path.of("src", "main", "resources", "data", "minecraft",
            "tags", "blocks");

    /** The eight reinforced blocks, all of them placeable and craftable. */
    private static final List<String> BLOCKS = List.of("reinforced_stone", "reinforced_smooth_stone",
            "chiseled_reinforced_stone", "reinforced_lamp", "reinforced_stone_slab",
            "reinforced_stone_stairs", "reinforced_bars", "reinforced_bars_gap");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    @Test
    void everyPrisonBlockHasABlockstateModelsLootRecipeAndAName() {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> problems = new ArrayList<>();
        for (String block : BLOCKS) {
            if (!Files.isRegularFile(ASSETS.resolve("blockstates/" + block + ".json"))) {
                problems.add("missing blockstate for " + block);
            }
            if (!Files.isRegularFile(ASSETS.resolve("models/item/" + block + ".json"))) {
                problems.add("missing item model for " + block);
            }
            if (!Files.isRegularFile(DATA.resolve("loot_tables/blocks/" + block + ".json"))) {
                problems.add("missing loot table for " + block);
            }
            if (!Files.isRegularFile(DATA.resolve("recipes/" + block + ".json"))) {
                problems.add("no recipe for " + block);
            }
            if (!lang.has("block.mcacrime." + block)) {
                problems.add("missing block display name for " + block);
            }
            if (!lang.has("item.mcacrime." + block)) {
                problems.add("missing item display name for " + block);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /** Every model a blockstate names has to exist, or that state renders as the missing cube. */
    @Test
    void everyModelThePrisonBlockstatesNameExists() {
        Set<String> missing = new TreeSet<>();
        for (String blockstate : BLOCKS) {
            JsonObject root = json(ASSETS.resolve("blockstates/" + blockstate + ".json"));
            for (JsonElement entry : models(root)) {
                String model = entry.getAsString();
                String path = model.substring(model.indexOf(':') + 1);
                if (!Files.isRegularFile(ASSETS.resolve("models/" + path + ".json"))) {
                    missing.add(model + "  (" + blockstate + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "blockstates name models that do not exist: " + missing);
    }

    /** Both blockstate shapes: plain {@code variants} and the bars' {@code multipart}. */
    private static List<JsonElement> models(JsonObject blockstate) {
        List<JsonElement> found = new ArrayList<>();
        if (blockstate.has("variants")) {
            for (Map.Entry<String, JsonElement> entry
                    : blockstate.getAsJsonObject("variants").entrySet()) {
                JsonElement value = entry.getValue();
                List<JsonElement> options = value.isJsonArray()
                        ? new ArrayList<>(((JsonArray) value).asList()) : List.of(value);
                options.forEach(option -> found.add(option.getAsJsonObject().get("model")));
            }
        }
        if (blockstate.has("multipart")) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                JsonElement apply = part.getAsJsonObject().get("apply");
                List<JsonElement> options = apply.isJsonArray()
                        ? new ArrayList<>(((JsonArray) apply).asList()) : List.of(apply);
                options.forEach(option -> found.add(option.getAsJsonObject().get("model")));
            }
        }
        return found;
    }

    /** The block textures the copied models sample have to have been copied with them. */
    @Test
    void everyTextureThePrisonModelsSampleExists() {
        Set<String> missing = new TreeSet<>();
        List<String> models = new ArrayList<>(List.of("block/reinforced_stone",
                "block/reinforced_smooth_stone", "block/chiseled_reinforced_stone",
                "block/reinforced_lamp", "block/reinforced_stone_slab", "block/reinforced_stone_slab_top",
                "block/reinforced_stone_stairs", "block/reinforced_stone_stairs_inner",
                "block/reinforced_stone_stairs_outer", "block/reinforced_bars_gap"));
        for (String part : List.of("bottom", "middle", "top")) {
            for (String shape : List.of("cap", "cap_alt", "post", "post_ends", "side", "side_alt")) {
                models.add("block/reinforced_bars_" + part + "_" + shape);
            }
        }
        for (String model : models) {
            Path path = ASSETS.resolve("models/" + model + ".json");
            if (!Files.isRegularFile(path)) {
                missing.add("model missing: " + model);
                continue;
            }
            JsonObject root = json(path);
            if (!root.has("textures")) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                String id = entry.getValue().getAsString();
                if (id.startsWith("#")) {
                    continue;
                }
                String texture = id.substring(id.indexOf(':') + 1);
                boolean vanilla = id.startsWith("minecraft:");
                if (!vanilla && !Files.isRegularFile(ASSETS.resolve("textures/" + texture + ".png"))) {
                    missing.add(id + "  (" + model + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "models sample textures that do not ship: " + missing);
    }

    /** The stonecutting and mirrored-stairs recipes Appendix A.4 names. */
    @Test
    void theExtraRecipeFormsShip() {
        List<String> problems = new ArrayList<>();
        for (String recipe : List.of("reinforced_stone_slab_stonecutting",
                "reinforced_stone_stairs_stonecutting", "chiseled_reinforced_stone_stonecutting",
                "reinforced_stone_stairs_mirrored")) {
            if (!Files.isRegularFile(DATA.resolve("recipes/" + recipe + ".json"))) {
                problems.add("missing recipe " + recipe);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));

        // The mirrored stairs recipe has to be the mirror image, not a duplicate of the first.
        String plain = json(DATA.resolve("recipes/reinforced_stone_stairs.json"))
                .getAsJsonArray("pattern").toString();
        String mirrored = json(DATA.resolve("recipes/reinforced_stone_stairs_mirrored.json"))
                .getAsJsonArray("pattern").toString();
        assertTrue(!plain.equals(mirrored),
                "a mirrored recipe identical to the plain one can never be reached");
    }

    /** The mining tags, so a reinforced wall is qualified rather than merely slow. */
    @Test
    void theReinforcedSetIsPickaxeQualifiedInTheVanillaTags() {
        String pickaxe = read(VANILLA_TAGS.resolve("mineable/pickaxe.json"));
        String needsIron = read(VANILLA_TAGS.resolve("needs_iron_tool.json"));
        List<String> problems = new ArrayList<>();
        for (String block : List.of("reinforced_stone", "reinforced_smooth_stone",
                "chiseled_reinforced_stone", "reinforced_lamp", "reinforced_stone_slab",
                "reinforced_stone_stairs", "reinforced_bars", "reinforced_bars_gap")) {
            if (!pickaxe.contains("mcacrime:" + block)) {
                problems.add(block + " is not in mineable/pickaxe");
            }
            if (!needsIron.contains("mcacrime:" + block)) {
                problems.add(block + " is not in needs_iron_tool");
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
        assertTrue(read(VANILLA_TAGS.resolve("slabs.json")).contains("mcacrime:reinforced_stone_slab"));
        assertTrue(read(VANILLA_TAGS.resolve("stairs.json")).contains("mcacrime:reinforced_stone_stairs"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
