package dev.otectus.mcacrime.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.facility.FacilityRole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three Townstead datapack mappings, read the way the game reads them.
 *
 * <h2>Why a mapping refuses instead of skipping</h2>
 *
 * <p>MCA: Crime's other loaders skip a bad row. These three do not, and the difference is the whole
 * point of the tests below: a mapping with a hole in it does not degrade, it lies. A building-role file
 * that silently dropped its jail line leaves a settlement whose jail is not a jail, with an operator
 * reading a facility list that agrees with them that it should be. A reaction binding that dropped one
 * event leaves a village reacting to arrests but not to jailbreaks, which reads as a deliberate design
 * choice rather than as a typo.
 *
 * <p>So the assertions come in pairs: the good file loads, and the bad file both produces a named
 * problem and leaves the mapping unpublished.
 */
class TownsteadDatapackTest {

    /**
     * The shipped data directory.
     *
     * <p>Resolved from {@code mcacrime.projectRoot} rather than relatively, and lazily rather than in a
     * static field: the NeoForge unit-test runner works out of {@code build/minecraft-junit}, so
     * {@code src/main/resources/...} on its own resolves to nothing and every assertion below would
     * fail as "the shipped directory is missing" — which looks like missing data and is not.
     */
    private static Path data() {
        String root = System.getProperty("mcacrime.projectRoot");
        assertTrue(root != null && !root.isBlank(),
                "mcacrime.projectRoot is not set; the test task in build.gradle supplies it");
        return Paths.get(root, "src", "main", "resources", "data", "mcacrime", "townstead");
    }

    private static Map<ResourceLocation, JsonElement> file(String name, String json) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(ResourceLocation.fromNamespaceAndPath("mcacrime", name), JsonParser.parseString(json));
        return files;
    }

    private static Map<ResourceLocation, JsonElement> shipped(String directory) {
        Path dir = data().resolve(directory);
        assertTrue(Files.isDirectory(dir), "the shipped " + directory + " directory is missing: " + dir);
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = path.getFileName().toString().replace(".json", "");
                files.put(ResourceLocation.fromNamespaceAndPath("mcacrime", name),
                        JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir.toAbsolutePath(), e);
        }
        assertFalse(files.isEmpty(), directory + " ships no default file");
        return files;
    }

    // --- reaction bindings -----------------------------------------------------------------------

    /** Every event MCA: Crime can raise has a reaction in the shipped pack, or it raises nothing. */
    @Test
    void theShippedBindingsCoverEveryEvent() {
        TownsteadReactionBindings.ParseResult result =
                TownsteadReactionBindings.read(shipped("reaction_bindings"));
        assertTrue(result.problems().isEmpty(), "the shipped bindings must load cleanly: "
                + TownsteadDataProblem.describeAll(result.problems()));
        for (TownsteadReactionEvent event : TownsteadReactionEvent.values()) {
            assertNotNull(result.bindings().get(event),
                    "no shipped binding for '" + event.id() + "', so it would silently play nothing");
        }
    }

    /** An event id nobody recognises is named in the error, with the list of what was allowed. */
    @Test
    void anUnknownEventIsALoadError() {
        TownsteadReactionBindings.ParseResult result = TownsteadReactionBindings.read(
                file("bad", "[{\"event\": \"crime_happened\", \"reaction\": \"townstead:alarmed\"}]"));
        assertEquals(1, result.problems().size());
        String message = result.problems().get(0).describe();
        assertTrue(message.contains("crime_happened"), message);
        assertTrue(message.contains(TownsteadReactionEvent.CRIME_WITNESSED.id()), message);
        assertTrue(result.bindings().isEmpty(), "nothing from a file with an unknown id is published");
    }

    /** A radius outside the bound is refused rather than clamped, and the number is in the message. */
    @Test
    void anAbsurdRadiusIsALoadError() {
        TownsteadReactionBindings.ParseResult result = TownsteadReactionBindings.read(
                file("bad", "[{\"event\": \"alarm_heard\", \"reaction\": \"townstead:x\", "
                        + "\"radius\": 4096}]"));
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).describe().contains("4096"),
                result.problems().get(0).describe());
    }

    /** Two files binding the same event name both of them, rather than one quietly winning. */
    @Test
    void aDuplicateBindingNamesBothFiles() {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(ResourceLocation.fromNamespaceAndPath("mcacrime", "a"),
                JsonParser.parseString("[{\"event\": \"rescued\", \"reaction\": \"townstead:a\"}]"));
        files.put(ResourceLocation.fromNamespaceAndPath("mcacrime", "b"),
                JsonParser.parseString("[{\"event\": \"rescued\", \"reaction\": \"townstead:b\"}]"));
        TownsteadReactionBindings.ParseResult result = TownsteadReactionBindings.read(files);
        assertEquals(1, result.problems().size());
        String message = result.problems().get(0).describe();
        assertTrue(message.contains("mcacrime:a") && message.contains("mcacrime:b"), message);
    }

    // --- building roles --------------------------------------------------------------------------

    /** The shipped roles load, and deliberately declare no cell: Townstead has no jail to declare. */
    @Test
    void theShippedBuildingRolesLoadAndClaimNoJail() {
        TownsteadBuildingRoles.ParseResult result = TownsteadBuildingRoles.read(shipped("building_roles"));
        assertTrue(result.problems().isEmpty(), "the shipped building roles must load cleanly: "
                + TownsteadDataProblem.describeAll(result.problems()));
        assertFalse(result.roles().isEmpty());
        for (TownsteadBuildingRoles.Recognition recognition : result.roles().values()) {
            assertFalse(recognition.role() == FacilityRole.JAIL_CELL,
                    "nothing may be recognised as a cell by default: a kitchen is not a cell because it "
                            + "has a door, and " + recognition.buildingType() + " was not built to hold "
                            + "anybody");
        }
    }

    /** An unknown role is a load error naming the roles that exist. */
    @Test
    void anUnknownRoleIsALoadError() {
        TownsteadBuildingRoles.ParseResult result = TownsteadBuildingRoles.read(
                file("bad", "[{\"building\": \"townstead:gaol\", \"role\": \"dungeon\"}]"));
        assertEquals(1, result.problems().size());
        String message = result.problems().get(0).describe();
        assertTrue(message.contains("dungeon"), message);
        assertTrue(message.contains(FacilityRole.JAIL_CELL.id()), message);
        assertTrue(result.roles().isEmpty());
    }

    /** A building type that is not a resource location at all is caught before anything uses it. */
    @Test
    void aMalformedBuildingIdIsALoadError() {
        TownsteadBuildingRoles.ParseResult result = TownsteadBuildingRoles.read(
                file("bad", "[{\"building\": \"Not An Id\", \"role\": \"guard_post\"}]"));
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).describe().contains("Not An Id"));
    }

    /** Casing is not meaning: a type is matched however the pack spelled it. */
    @Test
    void buildingTypesAreMatchedCaseInsensitively() {
        TownsteadBuildingRoles.ParseResult result = TownsteadBuildingRoles.read(
                file("ok", "[{\"building\": \"townstead:Guardhouse\", \"role\": \"guardhouse\"}]"));
        assertTrue(result.problems().isEmpty());
        assertSame(FacilityRole.GUARDHOUSE, result.roles().get("townstead:guardhouse").role());
    }

    // --- personality profiles --------------------------------------------------------------------

    /** The shipped profiles load and every one of them is inside the cap. */
    @Test
    void theShippedPersonalityProfilesAreAllSmall() {
        TownsteadPersonalityProfiles.ParseResult result =
                TownsteadPersonalityProfiles.read(shipped("personality_profiles"));
        assertTrue(result.problems().isEmpty(), "the shipped profiles must load cleanly: "
                + TownsteadDataProblem.describeAll(result.problems()));
        assertFalse(result.profiles().isEmpty());
        for (TownsteadPersonalityProfiles.Profile profile : result.profiles().values()) {
            assertTrue(Math.abs(profile.threat()) <= TownsteadPersonalityProfiles.MAX_WEIGHT);
            assertTrue(Math.abs(profile.report()) <= TownsteadPersonalityProfiles.MAX_WEIGHT);
            assertTrue(Math.abs(profile.flee()) <= TownsteadPersonalityProfiles.MAX_WEIGHT);
        }
    }

    /**
     * An oversized weight is refused, not clamped.
     *
     * <p>The case that matters most in this file. A silently clamped {@code 0.8} leaves a pack author
     * convinced they have written a switch when they have written a nudge, and the behaviour they were
     * trying to produce simply never appears — with no error anywhere to explain why.
     */
    @Test
    void anOversizedWeightIsALoadErrorRatherThanAClamp() {
        TownsteadPersonalityProfiles.ParseResult result = TownsteadPersonalityProfiles.read(
                file("bad", "[{\"personality\": \"townstead:timid\", \"report\": -0.8}]"));
        assertEquals(1, result.problems().size());
        String message = result.problems().get(0).describe();
        assertTrue(message.contains("-0.8"), message);
        assertTrue(message.contains(String.valueOf(TownsteadPersonalityProfiles.MAX_WEIGHT)), message);
        assertTrue(result.profiles().isEmpty(), "nothing from a file with a bad weight is published");
    }

    /** A personality nobody described has no opinion, which is what an absent settlement mod produces. */
    @Test
    void anUnknownPersonalityIsNeutral() {
        assertTrue(TownsteadPersonalityProfiles.profile("townstead:nobody").neutral());
        assertTrue(TownsteadPersonalityProfiles.profile(null).neutral());
        assertTrue(TownsteadPersonalityProfiles.profile("").neutral());
    }
}
