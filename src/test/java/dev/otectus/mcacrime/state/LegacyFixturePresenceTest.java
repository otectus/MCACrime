package dev.otectus.mcacrime.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.crime.Band;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real Phase 0 fixture check (spec §7.4 row 1, §13.3 step 6): a captured Forge 1.20.1
 * {@code playerdata/<uuid>.dat} imports to exactly the values a human wrote down while that world was
 * still running under Forge.
 *
 * <p>Disabled because the fixture does not exist yet - see
 * {@code src/test/resources/fixtures/forge-1.20.1/README.md}. Everything below is wired against the
 * committed manifest shape, so enabling it is deleting the {@link Disabled} annotation and nothing
 * else. {@link LegacyPlayerCrimeImporterTest} covers the same algorithm in the meantime against an
 * INTERIM synthetic root, which proves the parsing but not the surrounding real-world file shape.
 *
 * <p>Expected {@code manifest.json}:
 * <pre>
 * {
 *   "players": [
 *     {"uuid": "...", "karma": -4200, "heat": 913, "cachedBand": "RED", "wanted": true,
 *      "onlineTicksLived": 123456, "jailed": true, "arrested": false}
 *   ]
 * }
 * </pre>
 * Every key but {@code uuid} is optional; a key that is absent is simply not asserted, so the
 * manifest can start small and grow without this test changing.
 */
class LegacyFixturePresenceTest {

    /** Same 16 MiB ceiling the production importer reads player files under (spec §7.3). */
    private static final long READ_LIMIT = 16L * 1024L * 1024L;

    @Test
    @Disabled("Phase 0 fixture not yet captured")
    void capturedForgePlayerFilesMatchTheManifest() throws IOException {
        Path fixture = fixtureRoot();
        JsonObject manifest = JsonParser.parseString(
                Files.readString(fixture.resolve("manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();

        List<JsonObject> players = new ArrayList<>();
        manifest.getAsJsonArray("players").forEach(element -> players.add(element.getAsJsonObject()));
        assertFalse(players.isEmpty(), "the manifest names no players");

        for (JsonObject expected : players) {
            UUID uuid = UUID.fromString(expected.get("uuid").getAsString());
            Path file = fixture.resolve("playerdata").resolve(uuid + ".dat");
            assertTrue(Files.isRegularFile(file), "no captured player file for " + uuid);

            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(READ_LIMIT));
            assertNotNull(root, "unreadable player file for " + uuid);

            PlayerCrimeData imported = new PlayerCrimeData();
            assertTrue(LegacyPlayerCrimeImporter.importFromForgeCaps(root, imported),
                    "no ForgeCaps crime blob in the captured file for " + uuid);
            assertMatches(uuid, expected, imported);
        }
    }

    /** Asserts every field the manifest actually states, and ignores the ones it does not. */
    private static void assertMatches(UUID uuid, JsonObject expected, PlayerCrimeData imported) {
        number(expected, "karma").ifPresent(v ->
                assertEquals(v.longValue(), imported.getKarma(), uuid + " karma"));
        number(expected, "heat").ifPresent(v ->
                assertEquals(v.longValue(), imported.getHeat(), uuid + " heat"));
        number(expected, "onlineTicksLived").ifPresent(v ->
                assertEquals(v.longValue(), imported.getOnlineTicksLived(), uuid + " onlineTicksLived"));
        string(expected, "cachedBand").ifPresent(v ->
                assertEquals(Band.valueOf(v), imported.getCachedBand(), uuid + " cachedBand"));
        bool(expected, "wanted").ifPresent(v ->
                assertEquals(v, imported.isWantedCached(), uuid + " wanted"));
        bool(expected, "jailed").ifPresent(v ->
                assertEquals(v, imported.getJail() != null, uuid + " jailed"));
        bool(expected, "arrested").ifPresent(v ->
                assertEquals(v, imported.getArrest() != null, uuid + " arrested"));
        string(expected, "heldCaptiveRef").ifPresent(v ->
                assertEquals(UUID.fromString(v), imported.getHeldCaptiveRef(), uuid + " heldCaptiveRef"));
        string(expected, "heldByRef").ifPresent(v ->
                assertEquals(UUID.fromString(v), imported.getHeldByRef(), uuid + " heldByRef"));
    }

    private static Optional<Number> number(JsonObject object, String key) {
        return value(object, key).map(JsonElement::getAsNumber);
    }

    private static Optional<String> string(JsonObject object, String key) {
        return value(object, key).map(JsonElement::getAsString);
    }

    private static Optional<Boolean> bool(JsonObject object, String key) {
        return value(object, key).map(JsonElement::getAsBoolean);
    }

    private static Optional<JsonElement> value(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? Optional.empty() : Optional.of(element);
    }

    /**
     * Resolved from {@code mcacrime.projectRoot}, not relatively: the NeoForge unit-test runner works
     * out of {@code build/minecraft-junit}, and the fixture is a directory of binary files read off
     * disk rather than classpath resources.
     */
    private static Path fixtureRoot() {
        String projectRoot = System.getProperty("mcacrime.projectRoot");
        assertNotNull(projectRoot, "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        Path fixture = Path.of(projectRoot, "src", "test", "resources", "fixtures", "forge-1.20.1");
        assertTrue(Files.isDirectory(fixture), "missing fixture directory " + fixture);
        return fixture;
    }
}
