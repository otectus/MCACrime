package dev.otectus.mcacrime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19.1, as a test: "a mask that exists only as an icon is unfinished".
 *
 * <p>Sixteen styles times five artefacts is eighty chances to ship half a mask, and every one of them
 * fails in a way nobody notices until a player equips it — a missing layer texture is an invisible
 * face, and a missing tag entry is a disguise that does not disguise. All eighty are checked here
 * against the files on disk rather than trusted to a checklist.
 */
class MaskResourceCoverageTest {

    private static final Path ASSETS = Path.of("src", "main", "resources", "assets", "mcacrime");
    private static final Path DATA = Path.of("src", "main", "resources", "data", "mcacrime");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> tagValues(Path path) {
        assertTrue(Files.isRegularFile(path), "missing item tag " + path);
        Set<String> values = new TreeSet<>();
        JsonArray array = json(path).getAsJsonArray("values");
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    @Test
    void everyStyleHasAnIconALayerAndAModel() {
        List<String> missing = new ArrayList<>();
        for (MaskVariant variant : MaskVariant.values()) {
            String id = variant.textureName();
            Path icon = ASSETS.resolve("textures/item").resolve(id + ".png");
            Path layer = ASSETS.resolve("textures/models/armor").resolve(id + "_layer_1.png");
            Path model = ASSETS.resolve("models/item").resolve(id + ".json");
            if (!Files.isRegularFile(icon)) missing.add("icon " + icon);
            if (!Files.isRegularFile(layer)) missing.add("worn layer " + layer);
            if (!Files.isRegularFile(model)) {
                missing.add("item model " + model);
                continue;
            }
            JsonObject definition = json(model);
            assertEquals("item/generated", definition.get("parent").getAsString(), model.toString());
            assertEquals("mcacrime:item/" + id,
                    definition.getAsJsonObject("textures").get("layer0").getAsString(), model.toString());
        }
        assertTrue(missing.isEmpty(), "unfinished masks:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyStyleHasADisplayName() {
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> missing = new ArrayList<>();
        for (MaskVariant variant : MaskVariant.values()) {
            if (!lang.has(variant.displayKey())) missing.add(variant.displayKey());
        }
        for (MaskFamily family : MaskFamily.values()) {
            if (!lang.has(family.displayKey())) missing.add(family.displayKey());
        }
        assertTrue(missing.isEmpty(), "a mask with no name renders as its raw key: " + missing);
    }

    /**
     * Concealment is the {@code mcacrime:masks} tag, so a style outside it is not a mask at all —
     * however good its art is (§4.3: one authoritative disguise resolver, not sixteen).
     */
    @Test
    void everyStyleIsInTheConcealmentTag() {
        Set<String> masks = tagValues(DATA.resolve("tags/items/masks.json"));
        for (MaskVariant variant : MaskVariant.values()) {
            assertTrue(masks.contains(variant.styleId()),
                    variant.styleId() + " is registered but does not hide a face");
        }
        assertEquals(16, masks.size(), "the masks tag should hold exactly the shipped styles");
    }

    /** Each family sub-tag holds exactly its own four styles — the restyle ingredient depends on it. */
    @Test
    void everyFamilySubTagHoldsItsOwnFourStyles() {
        for (MaskFamily family : MaskFamily.values()) {
            Set<String> tagged = tagValues(DATA.resolve("tags/items/masks").resolve(family.key() + ".json"));
            Set<String> expected = new TreeSet<>(MaskVariant.of(family).stream()
                    .map(MaskVariant::styleId).toList());
            assertEquals(expected, tagged, "mcacrime:masks/" + family.key());
        }
    }
}
