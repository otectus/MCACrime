package dev.otectus.mcacrime.resource;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every cell door state resolves to exactly one blockstate variant, and every variant to a real model
 * (0.7.7).
 *
 * <p>Added while a modpack crash report was investigated without a crash log: the static audit found
 * the 128 states, sixteen models and textures all valid, and this keeps them that way. A state with no
 * variant is a missing-model cube in the world; a state with two is an overlapping definition the model
 * loader refuses.
 *
 * <p>The property domain is the block's own: {@code CellDoorBlock.createBlockStateDefinition} adds
 * half, facing, open, hinge, powered and in_bars. {@code powered} never changes a model, so the shipped
 * file omits it and each variant covers both of its values.
 */
class CellDoorBlockstateCoverageTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "mcacrime");

    /** CellDoorBlock's state definition, in declaration order. */
    private static final Map<String, List<String>> PROPERTIES = new LinkedHashMap<>();

    static {
        PROPERTIES.put("half", List.of("lower", "upper"));
        PROPERTIES.put("facing", List.of("north", "south", "west", "east"));
        PROPERTIES.put("open", List.of("false", "true"));
        PROPERTIES.put("hinge", List.of("left", "right"));
        PROPERTIES.put("powered", List.of("false", "true"));
        PROPERTIES.put("in_bars", List.of("false", "true"));
    }

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    private static Map<String, String> parse(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (key.isEmpty()) {
            return out;
        }
        for (String pair : key.split(",")) {
            String[] parts = pair.split("=", 2);
            out.put(parts[0], parts.length == 2 ? parts[1] : "");
        }
        return out;
    }

    private static List<Map<String, String>> allStates() {
        List<Map<String, String>> states = new ArrayList<>();
        states.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<String>> property : PROPERTIES.entrySet()) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : states) {
                for (String value : property.getValue()) {
                    Map<String, String> state = new LinkedHashMap<>(partial);
                    state.put(property.getKey(), value);
                    next.add(state);
                }
            }
            states = next;
        }
        return states;
    }

    private static JsonObject variants() {
        return json(ASSETS.resolve("blockstates/cell_door.json")).getAsJsonObject("variants");
    }

    @Test
    void everyStateMatchesExactlyOneVariant() {
        JsonObject variants = variants();
        List<Map<String, String>> states = allStates();
        assertEquals(128, states.size(), "the cell door has 128 states");
        List<String> problems = new ArrayList<>();
        for (Map<String, String> state : states) {
            List<String> matches = new ArrayList<>();
            for (String key : variants.keySet()) {
                Map<String, String> condition = parse(key);
                if (condition.entrySet().stream().allMatch(e -> e.getValue().equals(state.get(e.getKey())))) {
                    matches.add(key);
                }
            }
            if (matches.size() != 1) {
                problems.add(state + " matches " + matches.size() + " variants " + matches);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * A property another mod adds to every door still leaves each state exactly one variant (0.7.7).
     *
     * <p>Dramatic Doors injects {@code waterlogged} into {@code DoorBlock.createBlockStateDefinition}, and
     * since 0.7.7 {@code CellDoorBlock} builds on that definition instead of replacing it, so with the mod
     * installed the door has twice the states. The variants name neither {@code powered} nor
     * {@code waterlogged}, so each still covers both values of each and none needs a model of its own.
     */
    @Test
    void anInjectedWaterloggedPropertyStillResolvesEveryState() {
        JsonObject variants = variants();
        List<String> problems = new ArrayList<>();
        int states = 0;
        for (Map<String, String> base : allStates()) {
            for (String waterlogged : List.of("false", "true")) {
                Map<String, String> state = new LinkedHashMap<>(base);
                state.put("waterlogged", waterlogged);
                states++;
                long matches = variants.keySet().stream().map(CellDoorBlockstateCoverageTest::parse)
                        .filter(condition -> condition.entrySet().stream()
                                .allMatch(e -> e.getValue().equals(state.get(e.getKey()))))
                        .count();
                if (matches != 1) {
                    problems.add(state + " matches " + matches + " variants");
                }
            }
        }
        assertEquals(256, states, "Dramatic Doors doubles the cell door's 128 states");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyVariantNamesOnlyRealPropertiesAndValues() {
        List<String> problems = new ArrayList<>();
        for (String key : variants().keySet()) {
            for (Map.Entry<String, String> condition : parse(key).entrySet()) {
                List<String> values = PROPERTIES.get(condition.getKey());
                if (values == null || !values.contains(condition.getValue())) {
                    problems.add(key + " names " + condition.getKey() + "=" + condition.getValue());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyNamedModelAndTextureExists() {
        Set<String> missing = new TreeSet<>();
        List<JsonObject> models = new ArrayList<>();
        for (Map.Entry<String, JsonElement> variant : variants().entrySet()) {
            List<JsonElement> options = variant.getValue().isJsonArray()
                    ? variant.getValue().getAsJsonArray().asList() : List.of(variant.getValue());
            for (JsonElement option : options) {
                String model = option.getAsJsonObject().get("model").getAsString();
                Path file = ASSETS.resolve("models/" + model.substring(model.indexOf(':') + 1) + ".json");
                if (!Files.isRegularFile(file)) {
                    missing.add(model + "  (" + variant.getKey() + ")");
                } else {
                    models.add(json(file));
                }
            }
        }
        models.add(json(ASSETS.resolve("models/item/cell_door.json")));
        for (JsonObject model : models) {
            JsonObject textures = model.getAsJsonObject("textures");
            if (textures == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                String id = texture.getValue().getAsString();
                if (id.startsWith("#") || !id.startsWith("mcacrime:")) {
                    continue;
                }
                if (!Files.isRegularFile(ASSETS.resolve("textures/" + id.substring("mcacrime:".length()) + ".png"))) {
                    missing.add(id);
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing cell door models or textures: " + missing);
    }
}
