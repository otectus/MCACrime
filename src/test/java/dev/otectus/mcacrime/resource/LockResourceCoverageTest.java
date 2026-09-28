package dev.otectus.mcacrime.resource;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every lock item, block and block entity ships what it needs to exist in a world (M3.8).
 *
 * <p>The same rule {@code MaskResourceCoverageTest} applies to masks and
 * {@code RestraintResourceCoverageTest} applies to restraints: a registry entry with no model, no
 * texture, no display name or no way to obtain it is an unfinished feature, and it should fail here
 * rather than in somebody's world.
 */
class LockResourceCoverageTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "mcacrime");
    private static final Path DATA = Path.of("src", "main", "resources", "data", "mcacrime");

    /** The items M3 registers behaviour for, all of which are craftable. */
    private static final List<String> ITEMS = List.of("key", "key_ring", "key_mold", "baked_key_mold",
            "padlock", "lockpick");

    /** The blocks M3 registers, which need a blockstate and a loot table as well. */
    private static final List<String> BLOCKS = List.of("cell_door", "safe");

    /**
     * Items obtained by crafting something else. {@code key_mold} is taken from a key with clay,
     * {@code baked_key_mold} is fired from a raw mold, and a {@code key_ring} is two bound keys tied
     * together -- so none of the three has a recipe file of its own, and each route is asserted below
     * rather than assumed.
     */
    private static final List<String> WITHOUT_OWN_RECIPE = List.of("key_mold", "baked_key_mold",
            "key_ring");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    @Test
    void everyLockItemHasAModelATextureAndAName() {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> problems = new ArrayList<>();
        for (String item : ITEMS) {
            if (!Files.isRegularFile(ASSETS.resolve("models/item/" + item + ".json"))) {
                problems.add("missing item model for " + item);
            }
            if (!Files.isRegularFile(ASSETS.resolve("textures/item/" + item + ".png"))) {
                problems.add("missing item texture for " + item);
            }
            if (!lang.has("item.mcacrime." + item)) {
                problems.add("missing display name for " + item);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyLockItemCanBeObtained() {
        List<String> problems = new ArrayList<>();
        for (String item : ITEMS) {
            if (WITHOUT_OWN_RECIPE.contains(item)) {
                continue;
            }
            if (!Files.isRegularFile(DATA.resolve("recipes/" + item + ".json"))) {
                problems.add("no recipe for " + item);
            }
        }
        // The two exceptions have a route of their own, and it has to exist too.
        assertTrue(Files.isRegularFile(DATA.resolve("recipes/key_mold_copy.json")),
                "a key mold is taken from a key");
        assertTrue(Files.isRegularFile(DATA.resolve("recipes/key_mold_bake.json")),
                "and fired into a baked one");
        assertTrue(Files.isRegularFile(DATA.resolve("recipes/key_ring_create.json")),
                "and a ring is made out of keys");
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyLockBlockHasABlockstateModelsLootAndAName() {
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
                problems.add("missing display name for " + block);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyModelTheCellDoorBlockstateNamesExists() {
        JsonObject blockstate = json(ASSETS.resolve("blockstates/cell_door.json"));
        JsonObject variants = blockstate.getAsJsonObject("variants");
        List<String> problems = new ArrayList<>();
        for (String variant : variants.keySet()) {
            String model = variants.getAsJsonObject(variant).get("model").getAsString();
            String path = model.substring(model.indexOf(':') + 1);
            if (!Files.isRegularFile(ASSETS.resolve("models/" + path + ".json"))) {
                problems.add(variant + " names a missing model: " + model);
            }
            if (!model.startsWith("mcacrime:")) {
                problems.add(variant + " still names another namespace: " + model);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
        assertTrue(variants.size() >= 32, "a door has at least facing, half, hinge and open variants");
    }

    @Test
    void everyTextureTheseModelsSampleShips() {
        List<String> problems = new ArrayList<>();
        for (Path model : models()) {
            String body;
            try {
                body = Files.readString(model, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"mcacrime:(block|item)/([a-z0-9_/]+)\"").matcher(body);
            while (matcher.find()) {
                Path texture = ASSETS.resolve("textures/" + matcher.group(1) + "/"
                        + matcher.group(2) + ".png");
                if (!Files.isRegularFile(texture)) {
                    problems.add(model.getFileName() + " samples a missing texture: " + matcher.group(2));
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    private static List<Path> models() {
        List<Path> models = new ArrayList<>();
        for (String name : List.of("cell_door", "safe")) {
            models.add(ASSETS.resolve("models/item/" + name + ".json"));
        }
        try (var files = Files.list(ASSETS.resolve("models/block"))) {
            files.filter(p -> p.getFileName().toString().startsWith("cell_door")
                    || p.getFileName().toString().startsWith("safe")).forEach(models::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return models;
    }

    @Test
    void theThreeLockTagsShip() {
        assertTrue(Files.isRegularFile(DATA.resolve("tags/blocks/lockable_blocks.json")));
        assertTrue(Files.isRegularFile(DATA.resolve("tags/items/can_reinforce_padlock.json")));
        assertTrue(Files.isRegularFile(DATA.resolve("tags/items/lockpicks.json")));
    }

    @Test
    void theGenericKeyTagIsNotAMasterKeyForBlockLocks() {
        JsonObject keys = json(DATA.resolve("tags/items/keys.json"));
        String values = keys.getAsJsonArray("values").toString();
        assertTrue(values.contains("handcuffs_key") || values.contains("cuff_keys"),
                "mcacrime:keys stays a cuff-family tag");
        assertTrue(!values.contains("\"mcacrime:key\""),
                "and the block key is deliberately not in it (§3.7)");
    }
}
