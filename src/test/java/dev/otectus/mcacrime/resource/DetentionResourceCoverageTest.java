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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every transport and detention block, item and entity ships what it needs to exist (0.7.5 M4.9).
 *
 * <p>The rule {@code LockResourceCoverageTest} applies to locks and {@code MaskResourceCoverageTest}
 * applies to masks: a registry entry with no model, no texture, no display name or no way to obtain it
 * is an unfinished feature, and it should fail here rather than in somebody's world. The blockstate
 * cross-check is the half that catches the subtler version — a state combination the code can produce
 * and the resource pack has no model for renders as the missing-texture cube.
 *
 * <p>1.21.1 note: the registry directories are singular here — {@code loot_table}, {@code recipe},
 * {@code tags/entity_type} — where the 1.20.1 baseline's are plural. Same files, same contents, the
 * names the data pack format of this version reads.
 */
class DetentionResourceCoverageTest {

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

    /** The blocks M4 registers: each needs a blockstate, an item model, loot, a recipe and a name. */
    private static final List<String> BLOCKS = List.of("pillory", "guillotine", "bunk");

    /** The entities M4 registers. Each must have a display name; their art is item art. */
    private static final List<String> ENTITIES = List.of("chain_knot");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    @Test
    void everyDetentionBlockHasABlockstateModelsLootRecipeAndAName() {
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
            if (!lang.has("item.mcacrime." + block)) {
                problems.add("missing item display name for " + block);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyEntityHasADisplayName() {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> problems = new ArrayList<>();
        for (String entity : ENTITIES) {
            if (!lang.has("entity.mcacrime." + entity)) {
                problems.add("missing display name for " + entity);
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /** Every model a blockstate names has to exist, or that state renders as the missing cube. */
    private void everyNamedModelExists(String blockstate) {
        JsonObject variants = json(ASSETS.resolve("blockstates/" + blockstate + ".json"))
                .getAsJsonObject("variants");
        java.util.Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            JsonElement value = entry.getValue();
            List<JsonElement> options = value.isJsonArray()
                    ? new ArrayList<>(((JsonArray) value).asList()) : List.of(value);
            for (JsonElement option : options) {
                String model = option.getAsJsonObject().get("model").getAsString();
                String path = model.substring(model.indexOf(':') + 1);
                if (!Files.isRegularFile(ASSETS.resolve("models/" + path + ".json"))) {
                    missing.add(model + "  (" + entry.getKey() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), blockstate + " names models that do not exist: " + missing);
    }

    @Test
    void everyModelTheDetentionBlockstatesNameExists() {
        BLOCKS.forEach(this::everyNamedModelExists);
    }

    /** The blockstates have to cover every combination the block's own properties can produce. */
    @Test
    void everyStateCombinationHasAVariant() {
        List<String> problems = new ArrayList<>();
        JsonObject pillory = json(ASSETS.resolve("blockstates/pillory.json"))
                .getAsJsonObject("variants");
        for (String half : List.of("lower", "upper")) {
            for (String closed : List.of("false", "true")) {
                for (String facing : List.of("north", "east", "south", "west")) {
                    String key = "half=" + half + ",closed=" + closed + ",facing=" + facing;
                    if (!pillory.has(key)) {
                        problems.add("pillory has no variant for " + key);
                    }
                }
            }
        }
        JsonObject guillotine = json(ASSETS.resolve("blockstates/guillotine.json"))
                .getAsJsonObject("variants");
        for (String facing : List.of("north", "east", "south", "west")) {
            for (String down : List.of("false", "true")) {
                for (String bloody : List.of("false", "true")) {
                    String key = "facing=" + facing + ",blade_down=" + down + ",bloody=" + bloody;
                    if (!guillotine.has(key)) {
                        problems.add("guillotine has no variant for " + key);
                    }
                }
            }
        }
        JsonObject bunk = json(ASSETS.resolve("blockstates/bunk.json")).getAsJsonObject("variants");
        for (String part : List.of("head", "foot")) {
            for (String facing : List.of("north", "east", "south", "west")) {
                for (String occupied : List.of("false", "true")) {
                    String key = "part=" + part + ",facing=" + facing + ",occupied=" + occupied;
                    if (!bunk.has(key)) {
                        problems.add("bunk has no variant for " + key);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /** The block textures the copied models sample have to have been copied with them. */
    @Test
    void everyTextureTheDetentionModelsSampleExists() {
        java.util.Set<String> missing = new TreeSet<>();
        for (String model : List.of("block/pillory_base", "block/pillory_open", "block/pillory_closed",
                "block/pillory_item", "block/guillotine_open", "block/guillotine_closed",
                "block/guillotine_open_bloody", "block/guillotine_closed_bloody",
                "block/bunk_left", "block/bunk_right", "item/guillotine", "item/bunk")) {
            JsonObject textures = json(ASSETS.resolve("models/" + model + ".json"))
                    .getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                String id = entry.getValue().getAsString();
                if (id.startsWith("#")) {
                    continue;
                }
                String path = id.substring(id.indexOf(':') + 1);
                if (!Files.isRegularFile(ASSETS.resolve("textures/" + path + ".png"))) {
                    missing.add(id + "  (" + model + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "models sample textures that do not ship: " + missing);
    }

    /** The {@code mcacrime:hang} damage type is a datapack file, and its tags are chosen on purpose. */
    @Test
    void theHangDamageTypeShipsWithTheTagsItWasGiven() {
        Path type = DATA.resolve("damage_type/hang.json");
        assertTrue(Files.isRegularFile(type), "mcacrime:hang has no datapack file");
        JsonObject hang = json(type);
        assertTrue(hang.has("message_id") && hang.has("scaling") && hang.has("exhaustion"));

        Path bypasses = resources("data", "minecraft", "tags", "damage_type", "bypasses_armor.json");
        assertTrue(Files.isRegularFile(bypasses));
        assertTrue(json(bypasses).getAsJsonArray("values").toString().contains("mcacrime:hang"));

        // Deliberately not is_explosion and not is_drowning, where the source puts its equivalent.
        for (String wrong : List.of("is_explosion", "is_drowning")) {
            Path tag = resources("data", "minecraft", "tags", "damage_type", wrong + ".json");
            assertFalse(Files.isRegularFile(tag) && json(tag).toString().contains("mcacrime:hang"),
                    "mcacrime:hang must not be in " + wrong);
        }
    }

    /** The chainable-entity tag ships, and does not narrow what the code already allows. */
    @Test
    void theChainableEntityTagShipsAndOnlyWidens() {
        Path tag = DATA.resolve("tags/entity_type/chainable_entities.json");
        assertTrue(Files.isRegularFile(tag));
        String body = json(tag).toString();
        assertFalse(body.contains("minecraft:player"),
                "players are chainable in code; a tag that could remove them would strand one");
        assertFalse(json(tag).get("replace").getAsBoolean(), "a pack extends this rather than owning it");
    }
}
