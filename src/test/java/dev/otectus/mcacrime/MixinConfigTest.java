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

/** Verify common loot capture and client-only rendering stay correctly registered and isolated. */
class MixinConfigTest {

    private static final Path CONFIG =
            Path.of("src", "main", "resources", "mcacrime.mixins.json");
    private static final Path MIXIN_SOURCE_ROOT =
            Path.of("src", "main", "java", "dev", "otectus", "mcacrime", "mixin");

    @Test
    void onlyEquipmentCaptureLoadsOnADedicatedServer() {
        assertEquals(List.of("MobDeathEquipmentMixin"), mixins("mixins"));
        assertEquals(List.of("client.RestraintPoseMixin"), mixins("client"));
    }

    @Test
    void everyListedMixinExistsOnDisk() {
        for (String name : allMixins()) {
            Path source = MIXIN_SOURCE_ROOT.resolve(name.replace('.', '/') + ".java");
            assertTrue(Files.exists(source),
                    "mcacrime.mixins.json lists " + name + " but " + source + " does not exist");
        }
    }

    /** The direction that actually bites: a mixin nobody listed applies to nothing and says nothing. */
    @Test
    void everyMixinOnDiskIsListed() {
        List<String> listed = allMixins();
        for (String name : sourceNames()) {
            assertTrue(listed.contains(name),
                    name + " exists under mixin/client but is not named in mcacrime.mixins.json, so it "
                            + "would never be applied");
        }
    }

    @Test
    void thePackageMatchesTheDirectoryAndTheToolchain() {
        JsonObject config = config();
        assertEquals("dev.otectus.mcacrime.mixin", config.get("package").getAsString());
        assertEquals("JAVA_17", config.get("compatibilityLevel").getAsString());
        assertEquals("mcacrime.refmap.json", config.get("refmap").getAsString(),
                "the refmap name must match the one build.gradle asks the annotation processor for, or "
                        + "member names stay in `official` mappings and the mixin fails in production");
    }

    private static JsonObject config() {
        try {
            return JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + CONFIG.toAbsolutePath(), e);
        }
    }

    private static List<String> mixins(String side) {
        JsonArray array = config().getAsJsonArray(side);
        List<String> names = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> names.add(element.getAsString()));
        }
        return names;
    }

    private static List<String> allMixins() {
        List<String> all = new ArrayList<>(mixins("mixins"));
        all.addAll(mixins("client"));
        return all;
    }

    private static List<String> sourceNames() {
        if (!Files.isDirectory(MIXIN_SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(MIXIN_SOURCE_ROOT)) {
            return paths.map(path -> MIXIN_SOURCE_ROOT.relativize(path).toString().replace('\\', '.').replace('/', '.'))
                    .filter(name -> name.endsWith(".java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + MIXIN_SOURCE_ROOT.toAbsolutePath(), e);
        }
    }
}
