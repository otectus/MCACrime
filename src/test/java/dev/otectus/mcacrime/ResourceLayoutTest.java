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
    private static final String[] ITEM_IDS =
            {"restraint_rope", "restraint_cuffs", "restraint_locked_cuffs"};
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
            String layer0 = object(file).getAsJsonObject("textures").get("layer0").getAsString();
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
