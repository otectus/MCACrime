package dev.otectus.mcacrime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.economy.fence.FencePriceLoader;
import dev.otectus.mcacrime.economy.fence.FencePriceLoader.FencePrice;
import dev.otectus.mcacrime.economy.fence.FencePriceLoader.ParseResult;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a fence price file is allowed to say (0.6.0, T17, audit finding B08).
 *
 * <p>Every bound here is the boundary of a number a player is eventually shown, so a datapack that
 * states nonsense has to be told which entry is nonsense and why — and the rest of the pack has to
 * survive being told. The distinction the tests are really about is between one bad entry, which is a
 * complaint and a skip, and a file that is not a price file at all, which abandons the reload and
 * leaves the last good prices standing rather than publishing a half-read set.
 *
 * <p>Parsed from inline strings through {@link JsonParser}: no resource manager, no registries, and
 * the item-existence check is a parameter, so the rules are asserted without a bootstrapped game.
 */
class FencePriceLoaderTest {

    private static final ResourceLocation FILE_A = ResourceLocation.fromNamespaceAndPath("mcacrime", "contraband");
    private static final ResourceLocation FILE_B = ResourceLocation.fromNamespaceAndPath("otherpack", "contraband");
    private static final ResourceLocation TNT = ResourceLocation.fromNamespaceAndPath("minecraft", "tnt");
    private static final ResourceLocation ROPE = ResourceLocation.fromNamespaceAndPath("mcacrime", "restraint_rope");

    /** The two items this "game" has registered. */
    private static final Predicate<ResourceLocation> KNOWN = Set.of(TNT, ROPE)::contains;

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static ParseResult read(Object... fileAndBody) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        for (int i = 0; i < fileAndBody.length; i += 2) {
            files.put((ResourceLocation) fileAndBody[i],
                    JsonParser.parseString((String) fileAndBody[i + 1]));
        }
        return FencePriceLoader.read(files, KNOWN);
    }

    // ------------------------------------------------------------------ per-entry rejections

    @Test
    void aBaseThatIsNotANumberIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FencePriceLoader.parse(json("{\"item\":\"minecraft:tnt\",\"base\":NaN}"), KNOWN));
        assertTrue(e.getMessage().contains("not a number"), e.getMessage());
    }

    @Test
    void aBaseBelowOneIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FencePriceLoader.parse(json("{\"item\":\"minecraft:tnt\",\"base\":0}"), KNOWN));
        assertTrue(e.getMessage().contains("at least 1"), e.getMessage());
    }

    @Test
    void aBaseAboveTheUpperBoundIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FencePriceLoader.parse(
                        json("{\"item\":\"minecraft:tnt\",\"base\":100001}"), KNOWN));
        assertTrue(e.getMessage().contains("at most " + FencePriceLoader.MAX_BASE_PRICE), e.getMessage());
        // The bound itself is legal: it is the config's own ceiling, not one past it.
        assertEquals(FencePriceLoader.MAX_BASE_PRICE, FencePriceLoader.parse(
                json("{\"item\":\"minecraft:tnt\",\"base\":100000}"), KNOWN).base());
    }

    @Test
    void anUnknownItemIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FencePriceLoader.parse(
                        json("{\"item\":\"nosuchmod:widget\",\"base\":12}"), KNOWN));
        assertTrue(e.getMessage().contains("no item"), e.getMessage());
    }

    @Test
    void aStatedDirectionIsRecordedAsStated() {
        FencePrice explicit = FencePriceLoader.parse(
                json("{\"item\":\"minecraft:tnt\",\"base\":12,\"buys\":false}"), KNOWN);
        assertTrue(explicit.buysStated(), "the file said so, and a stated direction replaces a tag's");
        assertFalse(explicit.buys());
        assertFalse(explicit.sellsStated(), "nothing was said about the other direction");
        assertTrue(explicit.sells());
    }

    @Test
    void oneBadEntryIsSkippedAndTheRestOfTheFileLoads() {
        ParseResult result = read(FILE_A,
                "[{\"item\":\"minecraft:tnt\",\"base\":12},"
                        + "{\"item\":\"nosuchmod:widget\",\"base\":4},"
                        + "{\"item\":\"mcacrime:restraint_rope\",\"base\":6}]");

        assertFalse(result.fileFailure(), "one typo does not abandon the reload");
        assertEquals(2, result.prices().size());
        assertEquals(12L, result.prices().get(TNT).base());
        assertEquals(6L, result.prices().get(ROPE).base());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains("nosuchmod:widget"), result.diagnostics().get(0));
    }

    // ------------------------------------------------------------------ duplicates and whole files

    @Test
    void aDuplicateIdNamesBothFiles() {
        ParseResult result = read(
                FILE_A, "{\"item\":\"minecraft:tnt\",\"base\":12}",
                FILE_B, "{\"item\":\"minecraft:tnt\",\"base\":99}");

        assertFalse(result.fileFailure());
        assertEquals(12L, result.prices().get(TNT).base(), "the first file to state a price keeps it");
        assertEquals(1, result.diagnostics().size());
        String diagnostic = result.diagnostics().get(0);
        assertTrue(diagnostic.contains(FILE_A.toString()), diagnostic);
        assertTrue(diagnostic.contains(FILE_B.toString()), diagnostic);
    }

    @Test
    void aFileThatIsNotAPriceFileAbandonsTheReload() {
        ParseResult result = read(
                FILE_A, "{\"item\":\"minecraft:tnt\",\"base\":12}",
                FILE_B, "\"a bare string, not a price entry\"");

        assertTrue(result.fileFailure(), "nothing in a file this shape can be trusted");
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).contains(FILE_B.toString()), result.diagnostics().get(0));
        // The entries that did parse are reported, but the caller keeps the last good map instead of
        // publishing them -- a half-read reload would quietly delete stock the pack still declares.
        assertEquals(1, result.prices().size());
    }

    @Test
    void anEmptyReloadIsNotAFailure() {
        ParseResult result = FencePriceLoader.read(Map.of(), KNOWN);
        assertFalse(result.fileFailure());
        assertTrue(result.prices().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }
}
