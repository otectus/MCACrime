package dev.otectus.mcacrime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resource tree, checked against the 1.21.1 data-pack contract (spec §10.4).
 *
 * <p>Everything asserted here fails silently in game. A recipe left under {@code recipes/} or a tag
 * left under {@code tags/items/} is not an error — 1.21 simply never looks there, so the recipe does
 * not exist and the tag is empty, and the only symptom is a player saying the item cannot be crafted.
 * A {@code result.item} instead of {@code result.id} is a load-time parse failure buried in a log. A
 * missing lang key renders as {@code item.mcacrime.restraint_rope}, and a model pointing at a texture
 * nobody shipped is the purple checkerboard.
 *
 * <p>It reads the source tree rather than the classpath on purpose: this asserts what is committed,
 * which is what a datapack author and a jar inspection both see.
 */
class ResourceLayoutTest {

    private static final Path RESOURCES = TestPaths.resources();
    private static final Path DATA = TestPaths.resources("data");
    private static final Path ASSETS = TestPaths.resources("assets");

    /** The item IDs from {@code CrimeItems}, which is what the lang keys have to line up with. */
    private static final String[] ITEM_IDS = {
            "restraint_rope", "restraint_cuffs", "restraint_locked_cuffs",
            // The sixteen mask styles, in family and catalogue order (0.7.2 §4.1).
            "bandana", "highwaymans_domino", "wrapped_scarf", "half_veil",
            "leather_mask", "raven_mask", "jackal_mask", "stitched_mask",
            "hockey_mask", "clay_mask", "comedy_mask", "tragedy_mask",
            "iron_skull_mask", "brigand_visor", "owl_mask", "blank_iron_mask",
            "sand_bottle"};
    /**
     * The block IDs from {@code CrimeBlocks}. Their BlockItems take the <em>block</em> description id,
     * so {@code block.mcacrime.mask_station} is the key that has to exist, not an {@code item.} one.
     */
    private static final String[] BLOCK_IDS = {"mask_station"};
    /** The four key mappings and their category, from {@code CrimeKeybinds}. */
    private static final String[] KEY_KEYS = {"key.categories.mcacrime", "key.mcacrime.dossier",
            "key.mcacrime.self_panel", "key.mcacrime.challenge", "key.mcacrime.crime_menu"};

    @Test
    void noPluralDataDirectorySurvives() throws IOException {
        List<String> plural = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                Path parent = dir.getParent();
                boolean underTags = parent != null && parent.getFileName().toString().equals("tags");
                if (name.equals("recipes") || (underTags && (name.equals("items") || name.equals("blocks")))) {
                    plural.add(relative(dir));
                }
            });
        }
        assertTrue(plural.isEmpty(),
                "1.21 reads the singular folders only, so these are dead weight the game never loads: "
                        + plural);
    }

    @Test
    void everyJsonFileParses() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path file : jsonFiles(DATA, ASSETS)) {
            try {
                JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (JsonSyntaxException e) {
                broken.add(relative(file) + ": " + e.getMessage());
            }
        }
        assertTrue(broken.isEmpty(), "unparseable JSON: " + broken);
    }

    @Test
    void everyRecipeResultUsesTheNewIdKey() throws IOException {
        List<Path> recipes = recipeFiles();
        assertFalse(recipes.isEmpty(), "no recipes found under data/*/recipe/");
        for (Path file : recipes) {
            JsonObject result = object(file).getAsJsonObject("result");
            assertTrue(result != null, relative(file) + " has no result object");
            assertFalse(result.has("item"),
                    relative(file) + " still uses the 1.20.1 result.item; 1.21 reads result.id");
            assertTrue(result.has("id") && result.get("id").isJsonPrimitive()
                            && result.get("id").getAsJsonPrimitive().isString(),
                    relative(file) + " must carry a string result.id");
        }
    }

    @Test
    void everyTagEntryIsAStringOrAnOptionalObjectAndNamesNoForgeTag() throws IOException {
        List<Path> tags = tagFiles();
        assertFalse(tags.isEmpty(), "no tag files found under data/*/tags/");
        for (Path file : tags) {
            JsonObject tag = object(file);
            assertTrue(tag.has("values"), relative(file) + " has no values array");
            for (JsonElement value : tag.getAsJsonArray("values")) {
                String id;
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    id = value.getAsString();
                } else {
                    assertTrue(value.isJsonObject(),
                            relative(file) + " has a tag entry that is neither a string nor an object");
                    JsonObject entry = value.getAsJsonObject();
                    assertTrue(entry.has("id"), relative(file) + " has an object entry with no id");
                    // The object form exists to say "required": false; writing it without that is a
                    // verbose string entry and usually means the optionality was forgotten.
                    assertTrue(entry.has("required"),
                            relative(file) + " object entry " + entry.get("id")
                                    + " must state required explicitly");
                    id = entry.get("id").getAsString();
                }
                assertFalse(id.startsWith("forge:") || id.startsWith("#forge:"),
                        relative(file) + " names " + id + "; 1.21.1 common tags live under c:");
            }
        }
    }

    @Test
    void theLangFileCoversTheItemsTheTabAndTheKeybindings() throws IOException {
        JsonObject lang = object(TestPaths.resources("assets", "mcacrime", "lang", "en_us.json"));
        for (String item : ITEM_IDS) {
            assertTrue(lang.has("item.mcacrime." + item), "en_us.json has no name for " + item);
        }
        for (String block : BLOCK_IDS) {
            assertTrue(lang.has("block.mcacrime." + block), "en_us.json has no name for " + block);
        }
        assertTrue(lang.has("itemGroup.mcacrime"), "the creative tab would render as its raw key");
        for (String key : KEY_KEYS) {
            assertTrue(lang.has(key), "en_us.json has no name for " + key);
        }
    }

    @Test
    void everyItemModelPointsAtATextureThatExists() throws IOException {
        Path models = TestPaths.resources("assets", "mcacrime", "models", "item");
        List<Path> files;
        try (Stream<Path> paths = Files.list(models)) {
            files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no item models found at " + models);
        for (Path file : files) {
            JsonObject textures = object(file).getAsJsonObject("textures");
            if (textures == null || !textures.has("layer0")) {
                // A block item's model is the block model by reference; it has no flat layer to check.
                continue;
            }
            String layer0 = textures.get("layer0").getAsString();
            String[] split = layer0.split(":", 2);
            assertTrue(split.length == 2 && split[0].equals("mcacrime"),
                    relative(file) + " points layer0 at " + layer0 + ", not at this mod's namespace");
            Path texture = TestPaths.resources("assets", "mcacrime", "textures")
                    .resolve(split[1] + ".png");
            assertTrue(Files.isRegularFile(texture),
                    relative(file) + " references " + layer0 + " but " + relative(texture)
                            + " does not exist (this is the purple checkerboard)");
        }
    }

    @Test
    void theModMetadataIsTheNeoForgeOneAndTheForgeEraFilesAreGone() {
        assertTrue(Files.isRegularFile(RESOURCES.resolve("META-INF").resolve("neoforge.mods.toml")),
                "NeoForge 1.21.1 reads META-INF/neoforge.mods.toml; without it the mod does not load");
        assertFalse(Files.exists(RESOURCES.resolve("META-INF").resolve("mods.toml")),
                "the Forge-era mods.toml must not survive alongside the NeoForge one");
        assertFalse(Files.exists(RESOURCES.resolve("pack.mcmeta")),
                "NeoForge synthesises pack metadata for mods; a committed pack.mcmeta overrides it");
    }

    /**
     * Every block ships the four files that make it visible and recoverable: a blockstate, a block
     * model, an item model and a loot table. Any one of them missing is a silent failure — no
     * blockstate is the purple-and-black cube, no loot table is a block that mines into nothing.
     */
    @Test
    void everyBlockShipsABlockstateModelsAndALootTable() {
        for (String block : BLOCK_IDS) {
            assertTrue(Files.isRegularFile(
                            TestPaths.resources("assets", "mcacrime", "blockstates", block + ".json")),
                    block + " has no blockstate; it would render as the missing-model cube");
            assertTrue(Files.isRegularFile(
                            TestPaths.resources("assets", "mcacrime", "models", "block", block + ".json")),
                    block + " has no block model");
            assertTrue(Files.isRegularFile(
                            TestPaths.resources("assets", "mcacrime", "models", "item", block + ".json")),
                    block + " has no item model, so its BlockItem would be invisible in an inventory");
            assertTrue(Files.isRegularFile(
                            TestPaths.resources("data", "mcacrime", "loot_table", "blocks", block + ".json")),
                    "1.21 reads data/<ns>/loot_table/ (singular); " + block + " would mine into nothing");
        }
    }

    /**
     * 1.21 renamed {@code loot_tables/} to {@code loot_table/} and {@code tags/blocks/} to
     * {@code tags/block/}. Both old spellings load without an error and simply never take effect.
     */
    @Test
    void noPluralLootTableOrBlockTagDirectorySurvives() throws IOException {
        List<String> plural = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(DATA)) {
            paths.filter(Files::isDirectory).forEach(dir -> {
                if (dir.getFileName().toString().equals("loot_tables")) {
                    plural.add(relative(dir));
                }
            });
        }
        assertTrue(plural.isEmpty(), "1.21 reads data/<ns>/loot_table/, not: " + plural);
    }

    /** Every blockstate variant points at a block model file that exists. */
    @Test
    void everyBlockstateVariantNamesAModelThatExists() throws IOException {
        Path blockstates = TestPaths.resources("assets", "mcacrime", "blockstates");
        List<Path> files;
        try (Stream<Path> paths = Files.list(blockstates)) {
            files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no blockstates found at " + blockstates);
        for (Path file : files) {
            JsonObject variants = object(file).getAsJsonObject("variants");
            assertTrue(variants != null, relative(file) + " has no variants object");
            for (String variant : variants.keySet()) {
                String model = variants.getAsJsonObject(variant).get("model").getAsString();
                String[] split = model.split(":", 2);
                assertTrue(split.length == 2 && split[0].equals("mcacrime"),
                        relative(file) + " variant " + variant + " names " + model);
                assertTrue(Files.isRegularFile(
                                TestPaths.resources("assets", "mcacrime", "models")
                                        .resolve(split[1] + ".json")),
                        relative(file) + " variant " + variant + " references " + model
                                + ", which does not exist");
            }
        }
    }

    private static List<Path> recipeFiles() throws IOException {
        return jsonFiles(DATA).stream()
                .filter(p -> relative(p).matches("data/[^/]+/recipe/.+\\.json"))
                .toList();
    }

    private static List<Path> tagFiles() throws IOException {
        return jsonFiles(DATA).stream()
                .filter(p -> relative(p).matches("data/[^/]+/tags/.+\\.json"))
                .toList();
    }

    private static List<Path> jsonFiles(Path... roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".json")).sorted().forEach(files::add);
            }
        }
        return files;
    }

    private static JsonObject object(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Slash-separated and rooted at src/main/resources, so a failure message reads the same anywhere. */
    private static String relative(Path file) {
        return RESOURCES.relativize(file).toString().replace('\\', '/');
    }
}
