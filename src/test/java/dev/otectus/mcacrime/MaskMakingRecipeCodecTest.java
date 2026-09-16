package dev.otectus.mcacrime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcacrime.item.MaskFamily;
import dev.otectus.mcacrime.item.MaskVariant;
import dev.otectus.mcacrime.recipe.MaskMakingRecipe;
import dev.otectus.mcacrime.recipe.MaskOperation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.21.1 half of the station's data contract: the shipped files actually decode.
 *
 * <p>1.20.1 parsed a recipe through {@code fromJson}, which is where {@code MaskRecipeJson} was
 * called; 1.21.1 parses it through a {@code MapCodec} that never sees a {@code JsonObject}. That makes
 * the codec a genuinely new piece of code sitting between thirty-two shipped files and the game, and a
 * file that validates by {@code MaskRecipeJson}'s rules but fails the codec's would be a datapack
 * error visible only in a server log. So the codec is run over the same files here.
 *
 * <p>Registry-free in the sense that matters: {@code Ingredient} and {@code ItemStack} resolve through
 * the static item registry and a tag ingredient carries only a key, so nothing here needs a world.
 */
class MaskMakingRecipeCodecTest {

    private static final Path STATION = TestPaths.resources("data", "mcacrime", "recipe", "mask_station");

    private static final RecipeSerializer<MaskMakingRecipe> SERIALIZER = new MaskMakingRecipe.Serializer();

    private static List<Path> shipped() {
        try (Stream<Path> files = Files.walk(STATION)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject read(Path file) {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DataResult<MaskMakingRecipe> decode(JsonObject json) {
        return SERIALIZER.codec().codec().parse(JsonOps.INSTANCE, json);
    }

    @Test
    void everyShippedStationRecipeDecodes() {
        List<Path> files = shipped();
        assertEquals(32, files.size(), "four families of four crafts and four restyles");
        List<String> broken = new ArrayList<>();
        for (Path file : files) {
            DataResult<MaskMakingRecipe> result = decode(read(file));
            result.error().ifPresent(error ->
                    broken.add(STATION.relativize(file) + ": " + error.message()));
        }
        assertTrue(broken.isEmpty(), "shipped station recipes the 1.21.1 codec refuses: " + broken);
    }

    /** The decoded values are the ones the file asked for, not defaults the codec quietly supplied. */
    @Test
    void aDecodedRecipeCarriesTheFilesOwnValues() {
        MaskMakingRecipe clay = decode(read(STATION.resolve("clay").resolve("clay_mask.json")))
                .getOrThrow(message -> new AssertionError(message));
        assertEquals(MaskOperation.CRAFT, clay.operation());
        assertEquals(MaskFamily.CLAY.recipeGroup(), clay.getGroup());
        assertEquals(4, clay.materialCount());
        assertEquals(2, clay.bindingCount());
        assertTrue(clay.allowDye());
        assertEquals(MaskVariant.CLAY.sortOrder(), clay.sortOrder());
        ItemStack result = clay.getResultItem(null);
        assertEquals("mcacrime:clay_mask", net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(result.getItem()).toString());
        assertEquals(1, result.getCount());

        MaskMakingRecipe restyle = decode(read(STATION.resolve("cloth").resolve("restyle_bandana.json")))
                .getOrThrow(message -> new AssertionError(message));
        assertEquals(MaskOperation.RESTYLE, restyle.operation());
        assertEquals(1, restyle.materialCount());
    }

    /** Re-encoding writes the 1.21 result shape, which is what a datapack dump has to look like. */
    @Test
    void encodingWritesTheNewResultKey() {
        MaskMakingRecipe bandana = decode(read(STATION.resolve("cloth").resolve("bandana.json")))
                .getOrThrow(message -> new AssertionError(message));
        JsonElement encoded = SERIALIZER.codec().codec()
                .encodeStart(JsonOps.INSTANCE, bandana)
                .getOrThrow(message -> new AssertionError(message));
        JsonObject result = encoded.getAsJsonObject().getAsJsonObject("result");
        assertFalse(result.has("item"), "1.21 writes result.id, never result.item");
        assertEquals("mcacrime:bandana", result.get("id").getAsString());
        assertEquals("craft", encoded.getAsJsonObject().get("operation").getAsString());
    }

    /**
     * The bounds {@code MaskRecipeJson} states are the bounds the codec enforces, so a pack author
     * cannot get past the validator by writing a file the 1.20.1 serializer would also have refused.
     */
    @Test
    void theCodecRefusesWhatTheContractRefuses() {
        JsonObject good = read(STATION.resolve("clay").resolve("clay_mask.json"));

        JsonObject zeroCount = good.deepCopy();
        zeroCount.getAsJsonObject("material").addProperty("count", 0);
        assertTrue(decode(zeroCount).error().isPresent(), "a zero material count must be refused");

        JsonObject oversized = good.deepCopy();
        oversized.getAsJsonObject("binding").addProperty("count", 65);
        assertTrue(decode(oversized).error().isPresent(), "a count above one stack must be refused");

        JsonObject badOperation = good.deepCopy();
        badOperation.addProperty("operation", "enchant");
        assertTrue(decode(badOperation).error().isPresent(), "an unknown operation must be refused");

        JsonObject oldResultKey = good.deepCopy();
        JsonObject legacy = new JsonObject();
        legacy.addProperty("item", "mcacrime:clay_mask");
        oldResultKey.add("result", legacy);
        assertTrue(decode(oldResultKey).error().isPresent(),
                "the 1.20.1 result.item spelling must fail loudly, not silently make air");

        JsonObject stackedResult = good.deepCopy();
        stackedResult.getAsJsonObject("result").addProperty("count", 4);
        assertTrue(decode(stackedResult).error().isPresent(), "a mask operation makes exactly one mask");
    }
}
