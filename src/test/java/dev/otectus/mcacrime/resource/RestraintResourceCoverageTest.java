package dev.otectus.mcacrime.resource;

import dev.otectus.mcacrime.TestPaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restraint that exists only in the definitions table is unfinished (0.7.5 M2.12).
 *
 * <p>The same rule {@code MaskResourceCoverageTest} applies to masks, applied to the nine restraint
 * definitions: each one names an item, and that item has to have a model, a texture and a display
 * name, and has to be obtainable by some route a player can actually take.
 *
 * <p>The acquisition half is deliberately not "must have a recipe". The hood is the vanilla bundle
 * and is obtainable without a recipe of its own; that exception is named below, so adding a tenth
 * definition with no way to get it fails here rather than shipping.
 */
class RestraintResourceCoverageTest {

    /**
     * Resolved from {@code mcacrime.projectRoot} rather than from the working directory: the NeoForge
     * unit-test runner works out of {@code build/minecraft-junit}, so a relative {@code src/...} path
     * finds nothing and every assertion below would read as "missing resource". Same reason, and same
     * property, as {@code ProtectedTextureHashTest}.
     */
    private static final Path ASSETS = projectRoot()
            .resolve(Path.of("src", "main", "resources", "assets", "mcacrime"));
    private static final Path DATA = projectRoot()
            .resolve(Path.of("src", "main", "resources", "data", "mcacrime"));

    private static Path projectRoot() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return Path.of(root);
    }

    /**
     * Items whose acquisition is deliberately not a recipe of their own, and why.
     *
     * <p>{@code minecraft:bundle}: vanilla's own item, given a recipe under its own namespace.
     * {@code pillory}: a block, placed rather than worn; M4 registers it.
     */
    private static final Set<String> DOCUMENTED_WITHOUT_OWN_RECIPE =
            Set.of("bundle", "pillory");

    @Test
    void everyDefinitionNamesAnItemThatExistsAsAResource() {
        List<String> problems = new ArrayList<>();
        for (RestraintDefinition definition : RestraintDefinitions.all()) {
            Optional<net.minecraft.resources.ResourceLocation> item = definition.item();
            if (item.isEmpty()) {
                problems.add(definition.id() + " names no item");
                continue;
            }
            String namespace = item.get().getNamespace();
            String path = item.get().getPath();
            if (!"mcacrime".equals(namespace)) {
                continue; // somebody else's item: vanilla's bundle brings its own model and texture
            }
            if ("pillory".equals(path)) {
                continue; // a block item, registered by M4 with its own blockstate and model
            }
            if (!Files.isRegularFile(ASSETS.resolve("models/item/" + path + ".json"))) {
                problems.add("missing item model for " + path);
            }
            if (!Files.isRegularFile(ASSETS.resolve("textures/item/" + path + ".png"))) {
                problems.add("missing item texture for " + path);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyDefinitionHasADisplayNameAndItsItemDoes() {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> problems = new ArrayList<>();
        for (RestraintDefinition definition : RestraintDefinitions.all()) {
            String nameKey = "mcacrime.restraint." + definition.id().getPath() + ".name";
            if (!lang.has(nameKey)) {
                problems.add("missing lang key " + nameKey);
            }
            definition.item().ifPresent(item -> {
                // The pillory's item is a block item M4 registers; its name lands with the block.
                if ("mcacrime".equals(item.getNamespace()) && !"pillory".equals(item.getPath())
                        && !lang.has("item.mcacrime." + item.getPath())) {
                    problems.add("missing lang key item.mcacrime." + item.getPath());
                }
            });
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyKeyItemHasItsOwnResourcesAndARecipe() {
        List<String> problems = new ArrayList<>();
        for (RestraintDefinition definition : RestraintDefinitions.all()) {
            definition.keyItem().ifPresent(key -> {
                String path = key.getPath();
                if (!Files.isRegularFile(ASSETS.resolve("models/item/" + path + ".json"))) {
                    problems.add("missing model for key " + path);
                }
                if (!Files.isRegularFile(ASSETS.resolve("textures/item/" + path + ".png"))) {
                    problems.add("missing texture for key " + path);
                }
                if (!Files.isRegularFile(DATA.resolve("recipe/" + path + ".json"))) {
                    // A key with no recipe is a restraint nobody can unlock.
                    problems.add("missing recipe for key " + path);
                }
            });
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyDefinitionHasARecipeOrADocumentedAcquisition() {
        List<String> problems = new ArrayList<>();
        for (RestraintDefinition definition : RestraintDefinitions.all()) {
            String path = definition.item().map(item -> item.getPath()).orElse(null);
            if (path == null || DOCUMENTED_WITHOUT_OWN_RECIPE.contains(path)) {
                continue;
            }
            if (!Files.isRegularFile(DATA.resolve("recipe/" + path + ".json"))) {
                problems.add(definition.id() + " has neither a recipe nor a documented acquisition");
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void theHoodsRecipeShipsUnderItsOwnNamespace() {
        // minecraft:bundle is unobtainable in vanilla 1.20.1 without a datapack, so the hood needs
        // this recipe to exist at all. DATAPACK.md records it as a deliberate, collidable addition.
        assertTrue(Files.isRegularFile(DATA.resolve("recipe/bundle.json")),
                "the hood is the vanilla bundle and needs a recipe to be obtainable");
    }

    @Test
    void theLegacyRopeIsNoLongerCraftableButStillConvertible() {
        assertTrue(!Files.isRegularFile(DATA.resolve("recipe/restraint_rope.json")),
                "rope is a legacy carrier and must not be craftable");
        assertTrue(Files.isRegularFile(DATA.resolve("recipe/rope_to_duck_tape.json")),
                "an existing rope must have somewhere to go");
    }

    @Test
    void everyWornDefinitionHasAnEntityTexture() {
        List<String> problems = new ArrayList<>();
        for (String texture : List.of("handcuffs", "shackles", "duck_tape", "bundle")) {
            if (!Files.isRegularFile(ASSETS.resolve("textures/entity/restraint/" + texture + ".png"))) {
                problems.add("missing worn texture " + texture);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
