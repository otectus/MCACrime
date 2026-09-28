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

    /**
     * The resource tree, resolved from {@code mcacrime.projectRoot}.
     *
     * <p>The NeoForge unit-test runner works out of {@code build/minecraft-junit}, so a relative
     * {@code src/main/resources/...} resolves to nothing and every assertion below would fail as
     * "missing file" rather than as the thing it is checking. The {@code test} task supplies it.
     */
    private static Path resources(String... segments) {
        Path path = Path.of(System.getProperty("mcacrime.projectRoot", "."), "src", "main", "resources");
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return path;
    }

    private static final Path ASSETS = resources("assets", "mcacrime");
    private static final Path DATA = resources("data", "mcacrime");

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
            if (!Files.isRegularFile(DATA.resolve("recipe/" + item + ".json"))) {
                problems.add("no recipe for " + item);
            }
        }
        // The two exceptions have a route of their own, and it has to exist too.
        assertTrue(Files.isRegularFile(DATA.resolve("recipe/key_mold_copy.json")),
                "a key mold is taken from a key");
        assertTrue(Files.isRegularFile(DATA.resolve("recipe/key_mold_bake.json")),
                "and fired into a baked one");
        assertTrue(Files.isRegularFile(DATA.resolve("recipe/key_ring_create.json")),
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
            if (!Files.isRegularFile(DATA.resolve("loot_table/blocks/" + block + ".json"))) {
                problems.add("missing loot table for " + block);
            }
            if (!Files.isRegularFile(DATA.resolve("recipe/" + block + ".json"))) {
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

    /**
     * Reads the {@code textures} block rather than scanning the whole file.
     *
     * <p>The baseline scans the raw text for {@code "mcacrime:item/..."}, which is fine when no model
     * names another model. The key ring's override list does exactly that, and a scan would read each
     * override's <em>model</em> id as a missing texture — so the parse is the honest version of the
     * same check.
     */
    @Test
    void everyTextureTheseModelsSampleShips() {
        List<String> problems = new ArrayList<>();
        for (Path model : models()) {
            JsonObject textures = json(model).getAsJsonObject("textures");
            if (textures == null) {
                continue;
            }
            for (String key : textures.keySet()) {
                String id = textures.get(key).getAsString();
                if (!id.startsWith("mcacrime:")) {
                    continue;
                }
                Path texture = ASSETS.resolve("textures/" + id.substring("mcacrime:".length()) + ".png");
                if (!Files.isRegularFile(texture)) {
                    problems.add(model.getFileName() + " samples a missing texture: " + id);
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
        // The key ring's five count-based override models sample four of the ring textures plus the
        // plain one, so they belong in the same sweep as the block models below.
        for (String name : List.of("key_ring", "key_ring_0", "key_ring_1", "key_ring_2", "key_ring_3",
                "key_ring_4")) {
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

    /**
     * The key ring draws the number of keys it is carrying (0.7.5 M3.2, coordinator note).
     *
     * <p>Five states — 0, 1, 2, 3 and 4-or-more — chosen by the {@code mcacrime:keys} item property,
     * which reads the ring's own data component. A model with no overrides would leave four of the
     * five shipped ring textures unreferenced and every ring looking identical.
     */
    @Test
    void theKeyRingHasFiveCountOverridesAndEachModelExists() {
        JsonObject model = json(ASSETS.resolve("models/item/key_ring.json"));
        com.google.gson.JsonArray overrides = model.getAsJsonArray("overrides");
        assertTrue(overrides != null && overrides.size() == 5,
                "five overrides, one per drawn key count");
        List<String> problems = new ArrayList<>();
        for (com.google.gson.JsonElement element : overrides) {
            JsonObject override = element.getAsJsonObject();
            assertTrue(override.getAsJsonObject("predicate").has("mcacrime:keys"),
                    "each override is keyed on the ring's own key count");
            String named = override.get("model").getAsString();
            String path = named.substring(named.indexOf(':') + 1);
            if (!Files.isRegularFile(ASSETS.resolve("models/" + path + ".json"))) {
                problems.add("override names a missing model: " + named);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void theThreeLockTagsShip() {
        assertTrue(Files.isRegularFile(DATA.resolve("tags/block/lockable_blocks.json")));
        assertTrue(Files.isRegularFile(DATA.resolve("tags/item/can_reinforce_padlock.json")));
        assertTrue(Files.isRegularFile(DATA.resolve("tags/item/lockpicks.json")));
    }

    @Test
    void theGenericKeyTagIsNotAMasterKeyForBlockLocks() {
        JsonObject keys = json(DATA.resolve("tags/item/keys.json"));
        String values = keys.getAsJsonArray("values").toString();
        assertTrue(values.contains("handcuffs_key") || values.contains("cuff_keys"),
                "mcacrime:keys stays a cuff-family tag");
        assertTrue(!values.contains("\"mcacrime:key\""),
                "and the block key is deliberately not in it (§3.7)");
    }
}
