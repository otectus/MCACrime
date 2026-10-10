package dev.otectus.mcacrime.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.block.CellDoorBlock;
import dev.otectus.mcacrime.block.prison.ReinforcedBarsBlock;
import dev.otectus.mcacrime.block.prison.ReinforcedBarsGappedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two MCA tags that make the Blueprint count the prison set (0.7.8).
 *
 * <p>MCA's prison is {@code {"#mca:iron_bars": 8, "#mca:iron_doors": 1, "#minecraft:beds": 1}} in every
 * supported build, so recognising Crime's bars and cell door is two additive tag files and nothing else.
 * What would break that quietly is a file that replaces MCA's own values (an iron-bar prison stops being
 * one), a typo in an id (a tag entry naming nothing fails the whole tag), or a block whose class no
 * longer behaves the way MCA's scanner needs: a door the scanner does not see as a {@code DoorBlock}
 * gives {@code NO_DOOR}, and the counting itself is by registry id, which both bar blocks have.
 */
class PrisonTagJsonTest {

    private static final Path MCA_TAGS = Path.of("src", "main", "resources", "data", "mca", "tags", "blocks");
    private static final Path CRIME_BLOCKS = Path.of("src", "main", "java", "dev", "otectus", "mcacrime",
            "block", "CrimeBlocks.java");

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    private static Set<String> values(JsonObject tag) {
        Set<String> values = new LinkedHashSet<>();
        JsonArray array = tag.getAsJsonArray("values");
        for (JsonElement element : array) {
            assertTrue(element.isJsonPrimitive(),
                    "every entry is a plain required id; an optional object entry would hide a typo: " + element);
            values.add(element.getAsString());
        }
        return values;
    }

    /** Every block id {@code CrimeBlocks} registers, read from its source so no bootstrap is needed. */
    private static Set<String> registeredBlockIds() {
        try {
            String source = Files.readString(CRIME_BLOCKS, StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("BLOCKS\\.register\\(\"([a-z0-9_]+)\"").matcher(source);
            Set<String> ids = new LinkedHashSet<>();
            while (matcher.find()) {
                ids.add("mcacrime:" + matcher.group(1));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void ironBarsAddBothReinforcedBarBlocksWithoutReplacingMca() {
        JsonObject tag = json(MCA_TAGS.resolve("iron_bars.json"));
        assertTrue(tag.has("replace") && !tag.get("replace").getAsBoolean(),
                "replace must be false, or MCA's own iron bars stop counting toward a prison");
        assertEquals(Set.of("mcacrime:reinforced_bars", "mcacrime:reinforced_bars_gap"), values(tag));
    }

    @Test
    void ironDoorsAddTheCellDoorWithoutReplacingMca() {
        JsonObject tag = json(MCA_TAGS.resolve("iron_doors.json"));
        assertTrue(tag.has("replace") && !tag.get("replace").getAsBoolean(),
                "replace must be false, or MCA's own iron door stops counting toward a prison");
        assertEquals(Set.of("mcacrime:cell_door"), values(tag));
    }

    @Test
    void everyTaggedIdIsARegisteredCrimeBlock() {
        Set<String> registered = registeredBlockIds();
        assertFalse(registered.isEmpty(), "CrimeBlocks registrations were not found; the pattern needs updating");
        for (String file : new String[] {"iron_bars.json", "iron_doors.json"}) {
            for (String id : values(json(MCA_TAGS.resolve(file)))) {
                assertTrue(registered.contains(id), file + " names " + id + ", which CrimeBlocks does not register; "
                        + "one bad entry makes the whole tag fail to load");
            }
        }
    }

    @Test
    void theTaggedBlocksAreTheShapesMcasScannerExpects() {
        // MCA's Building.validateBuilding asks `instanceof DoorBlock` for the entrance, so the cell door has
        // to remain a door; without it a Crime-only room scans as NO_DOOR however many bars it has.
        assertTrue(DoorBlock.class.isAssignableFrom(CellDoorBlock.class),
                "CellDoorBlock must extend DoorBlock for MCA's scanner to find the entrance");
        // The bars are counted by registry id, so their class does not matter to MCA; it is pinned here so
        // the vanilla pane behaviour (connections, no occlusion) the cell relies on is not lost silently.
        assertTrue(IronBarsBlock.class.isAssignableFrom(ReinforcedBarsBlock.class));
        assertFalse(DoorBlock.class.isAssignableFrom(ReinforcedBarsGappedBlock.class),
                "the barred window is a wall to MCA's flood fill, never a way in");
    }

    @Test
    void theBunkIsNotABedSoACrimePrisonStillNeedsOne() {
        // Documented limit: MCA's prison needs one #minecraft:beds. The bunk is a block entity block, not a
        // vanilla bed, and this mod does not put it in that tag.
        assertFalse(Files.exists(Path.of("src", "main", "resources", "data", "minecraft", "tags", "blocks",
                        "beds.json")),
                "MCA: Crime must not add blocks to #minecraft:beds");
    }
}
