package dev.otectus.mcacrime;

import com.google.gson.JsonArray;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mixin config, asserted rather than assumed.
 *
 * <p>This mod shipped without mixins on purpose until 0.4.0, and the one it now has is client-only —
 * it poses a restrained player's arms and does nothing else. Two properties have to stay true, and
 * neither is visible by reading the code that uses them:
 *
 * <ul>
 *   <li>Every mixin is in the {@code client} array and none is in {@code mixins}, which is what makes
 *       an accidental dedicated-server install inert: Mixin never loads the class outside
 *       {@code Dist.CLIENT}, so there is nothing to fail.</li>
 *   <li>The config and the source directory agree in <em>both</em> directions. A mixin listed but
 *       absent is a crash at load; a mixin present but unlisted silently never applies, which is far
 *       worse — the feature simply does not work and nothing says so.</li>
 * </ul>
 */
class MixinConfigTest {

    private static final Path CONFIG = TestPaths.resources("mcacrime.mixins.json");
    private static final Path MIXIN_SOURCE_ROOT =
            TestPaths.sources("dev", "otectus", "mcacrime", "mixin", "client");

    @Test
    void everyMixinIsClientOnlySoADedicatedServerLoadsNone() {
        JsonObject config = config();
        assertTrue(config.has("mixins"), "the mixins array must be present, even when empty");
        assertEquals(0, config.getAsJsonArray("mixins").size(),
                "a common-side mixin would be loaded on a dedicated server, where none of this mod's "
                        + "rendering exists");
        assertFalse(clientMixins().isEmpty(), "the config exists to carry at least one client mixin");
    }

    @Test
    void everyListedMixinExistsOnDisk() {
        for (String name : clientMixins()) {
            Path source = MIXIN_SOURCE_ROOT.resolve(name + ".java");
            assertTrue(Files.exists(source),
                    "mcacrime.mixins.json lists " + name + " but " + source + " does not exist");
        }
    }

    /** The direction that actually bites: a mixin nobody listed applies to nothing and says nothing. */
    @Test
    void everyMixinOnDiskIsListed() {
        List<String> listed = clientMixins();
        for (String name : sourceNames()) {
            assertTrue(listed.contains(name),
                    name + " exists under mixin/client but is not named in mcacrime.mixins.json, so it "
                            + "would never be applied");
        }
    }

    @Test
    void thePackageMatchesTheDirectoryAndTheToolchain() {
        JsonObject config = config();
        assertEquals("dev.otectus.mcacrime.mixin.client", config.get("package").getAsString());
        assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
        // No refmap: NeoForge 1.21.1 production runs on Mojang names, so the annotation processor is
        // gone and a leftover refmap declaration would point Mixin at a file that is never generated.
        assertFalse(config.has("refmap"),
                "a refmap is neither generated nor needed on NeoForge 1.21.1; declaring one names a "
                        + "file that will not be in the jar");
    }

    private static JsonObject config() {
        try {
            return JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + CONFIG.toAbsolutePath(), e);
        }
    }

    private static List<String> clientMixins() {
        JsonArray array = config().getAsJsonArray("client");
        List<String> names = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> names.add(element.getAsString()));
        }
        return names;
    }

    private static List<String> sourceNames() {
        if (!Files.isDirectory(MIXIN_SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(MIXIN_SOURCE_ROOT)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + MIXIN_SOURCE_ROOT.toAbsolutePath(), e);
        }
    }
}
