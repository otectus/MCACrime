package dev.otectus.mcacrime.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Blueprint compatibility relies on, read out of every MCA build in {@code mca_probe_versions}
 * (0.7.8).
 *
 * <p>MCA: Crime's two tag files only work while MCA defines its prison through {@code #mca:iron_bars} and
 * {@code #mca:iron_doors}. If an MCA release renamed either tag or moved the prison to a block list, the
 * Crime tags would load and do nothing, and a player's cell would silently stop being a prison. This
 * test reads the data straight out of each jar, so that change fails the build instead.
 *
 * <p>It also pins the claim that widening those two tags changes nothing but the prison: no other MCA
 * building type names them. A future type that did would be widened too, which deserves a decision.
 *
 * <p>Skipped when no MCA jar was resolved, exactly as {@link McaBindingProbeTest} is.
 */
class McaPrisonDefinitionProbeTest {

    private static final String BARS = "#mca:iron_bars";
    private static final String DOORS = "#mca:iron_doors";
    private static final String BEDS = "#minecraft:beds";

    private static List<Path> probeJars() {
        String property = System.getProperty("mcacrime.probe.jars", "");
        List<Path> jars = new ArrayList<>();
        for (String entry : property.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Path.of(entry.trim());
                if (Files.isRegularFile(path)) {
                    jars.add(path);
                }
            }
        }
        return jars;
    }

    private static JsonObject read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** 1.20.1 data packs use {@code tags/blocks}; 1.21 renamed the directory to {@code tags/block}. */
    private static ZipEntry tag(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry("data/mca/tags/blocks/" + name + ".json");
        return entry != null ? entry : zip.getEntry("data/mca/tags/block/" + name + ".json");
    }

    @Test
    void everyProbedMcaDefinesItsPrisonThroughTheTagsCrimeExtends() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No MCA jar to probe; run via Gradle to exercise this.");
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry prison = zip.getEntry("data/mca/building_types/prison.json");
                assertNotNull(prison, jar.getFileName() + " has no prison building type");
                JsonObject blocks = read(zip, prison).getAsJsonObject("blocks");
                assertNotNull(blocks, jar.getFileName() + " prison.json has no blocks map");
                assertTrue(blocks.has(BARS) && blocks.get(BARS).getAsInt() > 0,
                        jar.getFileName() + " no longer counts " + BARS + " toward a prison: " + blocks);
                assertTrue(blocks.has(DOORS) && blocks.get(DOORS).getAsInt() > 0,
                        jar.getFileName() + " no longer counts " + DOORS + " toward a prison: " + blocks);
                assertTrue(blocks.has(BEDS),
                        jar.getFileName() + " no longer needs a bed in a prison; update DATAPACK.md: " + blocks);

                for (String name : new String[] {"iron_bars", "iron_doors"}) {
                    ZipEntry entry = tag(zip, name);
                    assertNotNull(entry, jar.getFileName() + " no longer ships mca:" + name);
                    JsonObject tag = read(zip, entry);
                    assertTrue(!tag.has("replace") || !tag.get("replace").getAsBoolean(),
                            jar.getFileName() + " mca:" + name + " replaces; Crime's additive entry would be lost");
                }
                System.out.println("[prison] " + jar.getFileName() + " -> " + blocks);
            }
        }
    }

    @Test
    void onlyThePrisonNamesTheBarAndDoorTags() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No MCA jar to probe; run via Gradle to exercise this.");
        for (Path jar : jars) {
            Map<String, String> users = new TreeMap<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("data/mca/building_types/") || !name.endsWith(".json")) {
                        continue;
                    }
                    JsonObject blocks = read(zip, entry).getAsJsonObject("blocks");
                    if (blocks == null) {
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> block : blocks.entrySet()) {
                        if (block.getKey().equals(BARS) || block.getKey().equals(DOORS)) {
                            users.put(name.substring(name.lastIndexOf('/') + 1), block.getKey());
                        }
                    }
                }
            }
            assertEquals(List.of("prison.json"), List.copyOf(users.keySet()),
                    jar.getFileName() + ": widening the bar and door tags would now reclassify more than the prison");
        }
    }
}
