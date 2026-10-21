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
            Path geometry = ASSETS.resolve("geo/masks").resolve(id + ".geo.json");
            if (!Files.isRegularFile(geometry)) missing.add("worn geometry " + geometry);
            if (!Files.isRegularFile(model)) {
                missing.add("item model " + model);
                continue;
            }
            JsonObject definition = json(model);
            if (definition.has("parent")) {
                assertEquals("item/generated", definition.get("parent").getAsString(), model.toString());
                assertEquals("mcacrime:item/" + id,
                        definition.getAsJsonObject("textures").get("layer0").getAsString(), model.toString());
            } else {
                assertEquals("mcacrime:item/" + id,
                        definition.getAsJsonObject("textures").get("mask").getAsString());
                assertTrue(definition.getAsJsonArray("elements").size() > 0);
                for (JsonElement element : definition.getAsJsonArray("elements")) {
                    for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                        assertEquals(0, face.getValue().getAsJsonObject().get("tintindex").getAsInt());
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "unfinished masks:\n  " + String.join("\n  ", missing));
    }

    @Test
    void wornModelsHaveHeadAttachmentAndMatchingTextureDimensions() throws IOException {
        for (MaskVariant variant : MaskVariant.values()) {
            String id = variant.textureName();
            JsonObject geometry = json(ASSETS.resolve("geo/masks/" + id + ".geo.json"))
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            var image = javax.imageio.ImageIO.read(ASSETS.resolve("textures/models/armor/" + id + "_layer_1.png").toFile());
            assertEquals(image.getWidth(), description.get("texture_width").getAsInt(), id);
            assertEquals(image.getHeight(), description.get("texture_height").getAsInt(), id);
            Set<String> bones = new TreeSet<>();
            int cubes = 0;
            for (JsonElement entry : geometry.getAsJsonArray("bones")) {
                JsonObject bone = entry.getAsJsonObject();
                assertTrue(bones.add(bone.get("name").getAsString()), "duplicate bone in " + id);
                if (bone.has("parent")) assertTrue(bones.contains(bone.get("parent").getAsString()), id);
                if (bone.has("cubes")) cubes += bone.getAsJsonArray("cubes").size();
            }
            assertTrue(bones.contains("armorHead"), id + " must follow the wearer's head");
            assertTrue(cubes >= 2, id + " must include its face and bindings");
            if (variant == MaskVariant.RAVEN) {
                assertTrue(bones.contains("plague") && !bones.contains("jackal"));
            } else if (variant == MaskVariant.JACKAL) {
                assertTrue(bones.contains("jackal") && !bones.contains("plague"));
            }
        }
        assertTrue(json(ASSETS.resolve("animations/masks.animation.json")).has("animations"));
    }

    /**
     * A dyed mask draws different art from an undyed one (0.8.0, D4), so a style is unfinished until its
     * dyed art is: a neutral base the colour can tint and a detail layer that keeps its eyes, lenses,
     * straps and trim, for the icon and for the worn model, selected by the style's own model through
     * {@code mcacrime:dyed}. Checked against the pixels as well as the files, because the failure that
     * matters -- a dye that cannot show, or a detail that lost its colour -- leaves every file present.
     */
    @Test
    void everyStyleShipsDyedArtThatTheDyeCanShowOn() throws IOException {
        List<String> problems = new ArrayList<>();
        for (MaskVariant variant : MaskVariant.values()) {
            String id = variant.textureName();
            for (String[] pair : new String[][]{{"textures/item/", id + ".png"},
                    {"textures/models/armor/", id + "_layer_1.png"}}) {
                Path folder = ASSETS.resolve(pair[0]);
                Path basePath = folder.resolve(id + "_dyed.png");
                Path detailPath = folder.resolve(id + "_dyed_detail.png");
                if (!Files.isRegularFile(basePath) || !Files.isRegularFile(detailPath)) {
                    problems.add("missing dyed art for " + id + " in " + pair[0]);
                    continue;
                }
                var original = javax.imageio.ImageIO.read(folder.resolve(pair[1]).toFile());
                var base = javax.imageio.ImageIO.read(basePath.toFile());
                var detail = javax.imageio.ImageIO.read(detailPath.toFile());
                if (base.getWidth() != original.getWidth() || base.getHeight() != original.getHeight()
                        || detail.getWidth() != original.getWidth() || detail.getHeight() != original.getHeight()) {
                    problems.add(id + " " + pair[0] + ": dyed art is not the original's size");
                    continue;
                }
                int opaque = 0;
                int dyeable = 0;
                for (int y = 0; y < original.getHeight(); y++) {
                    for (int x = 0; x < original.getWidth(); x++) {
                        int o = original.getRGB(x, y);
                        int b = base.getRGB(x, y);
                        int d = detail.getRGB(x, y);
                        boolean shown = (o >>> 24) != 0;
                        if (shown != ((b >>> 24) != 0)) {
                            problems.add(id + " " + pair[0] + ": base silhouette differs at " + x + "," + y);
                        }
                        if (!shown) {
                            if ((d >>> 24) != 0) problems.add(id + " " + pair[0] + ": detail outside the mask at " + x + "," + y);
                            continue;
                        }
                        opaque++;
                        int r = (b >> 16) & 0xFF;
                        int g = (b >> 8) & 0xFF;
                        if (r != g || g != (b & 0xFF)) {
                            problems.add(id + " " + pair[0] + ": base is not neutral grey at " + x + "," + y);
                        }
                        if ((d >>> 24) == 0) {
                            dyeable++;
                        } else if ((d & 0xFFFFFF) != (o & 0xFFFFFF)) {
                            problems.add(id + " " + pair[0] + ": detail changed the original's colour at " + x + "," + y);
                        }
                    }
                }
                // The Owl dyes its enamel face and keeps its frame and eyes, the smallest share of any
                // style; anything below a quarter would be a dye most of the mask does not show.
                if (dyeable * 4 < opaque) {
                    problems.add(id + " " + pair[0] + ": only " + dyeable + " of " + opaque + " pixels take the dye");
                }
            }

            JsonObject model = json(ASSETS.resolve("models/item/" + id + ".json"));
            JsonArray overrides = model.getAsJsonArray("overrides");
            if (overrides == null || overrides.size() != 1
                    || !overrides.get(0).getAsJsonObject().getAsJsonObject("predicate").has("mcacrime:dyed")
                    || overrides.get(0).getAsJsonObject().getAsJsonObject("predicate").get("mcacrime:dyed").getAsInt() != 1
                    || !("mcacrime:item/" + id + "_dyed").equals(overrides.get(0).getAsJsonObject().get("model").getAsString())) {
                problems.add(id + ": its model does not select mcacrime:item/" + id + "_dyed for a dyed stack");
            }
            Path dyedModelPath = ASSETS.resolve("models/item/" + id + "_dyed.json");
            if (!Files.isRegularFile(dyedModelPath)) {
                problems.add("missing " + dyedModelPath);
                continue;
            }
            JsonObject dyed = json(dyedModelPath);
            JsonObject textures = dyed.getAsJsonObject("textures");
            if (dyed.has("parent")) {
                assertEquals("item/generated", dyed.get("parent").getAsString(), id);
                assertEquals("mcacrime:item/" + id + "_dyed", textures.get("layer0").getAsString(), id);
                assertEquals("mcacrime:item/" + id + "_dyed_detail", textures.get("layer1").getAsString(), id);
            } else {
                // The hand-built domino: every tinted base face carries tint index 0, every detail face
                // none, and each base element is followed by its detail twin in the same place.
                assertEquals("mcacrime:item/" + id + "_dyed", textures.get("base").getAsString(), id);
                assertEquals("mcacrime:item/" + id + "_dyed_detail", textures.get("detail").getAsString(), id);
                JsonArray elements = dyed.getAsJsonArray("elements");
                assertEquals(0, elements.size() % 2, id);
                for (int i = 0; i < elements.size(); i += 2) {
                    JsonObject tinted = elements.get(i).getAsJsonObject();
                    JsonObject untinted = elements.get(i + 1).getAsJsonObject();
                    assertEquals(tinted.get("from"), untinted.get("from"), id);
                    assertEquals(tinted.get("to"), untinted.get("to"), id);
                    for (var face : tinted.getAsJsonObject("faces").entrySet()) {
                        assertEquals("#base", face.getValue().getAsJsonObject().get("texture").getAsString(), id);
                        assertEquals(0, face.getValue().getAsJsonObject().get("tintindex").getAsInt(), id);
                    }
                    for (var face : untinted.getAsJsonObject("faces").entrySet()) {
                        assertEquals("#detail", face.getValue().getAsJsonObject().get("texture").getAsString(), id);
                        assertTrue(!face.getValue().getAsJsonObject().has("tintindex"), id + " detail must stay untinted");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "dyed mask art:\n  " + String.join("\n  ", problems));
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
