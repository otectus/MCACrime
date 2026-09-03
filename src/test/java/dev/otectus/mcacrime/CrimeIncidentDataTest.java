package dev.otectus.mcacrime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The incident definitions this mod ships into MCA: Reputation's datapack namespace, and the table
 * that decides which crime produces which one.
 *
 * <p>Validated against a local copy of the schema rather than by calling Reputation's own codec, so
 * the check still runs when the sibling is not on the classpath. That is the point of the exercise:
 * these files are shipped inside <em>our</em> jar, and an authoring mistake in them must fail our
 * build, not somebody's server log.
 */
class CrimeIncidentDataTest {

    private static final Path INCIDENTS =
            Paths.get("src", "main", "resources", "data", "mcacrime", "mcareputation", "incidents");
    private static final Path LANG =
            Paths.get("src", "main", "resources", "assets", "mcacrime", "lang", "en_us.json");

    private static final Set<String> VISIBILITIES = Set.of("private", "witnessed", "village", "global");
    private static final Set<String> SEVERITIES =
            Set.of("trivial", "minor", "moderate", "major", "severe");
    /** The schema's own ceiling on a delta. */
    private static final int HARD_DELTA_CAP = 10000;
    /** The schema's default when a file does not say — low enough that omitting it silently clamps. */
    private static final int DEFAULT_MAX_OVERRIDE_ABS = 100;

    private static List<Path> incidentFiles() throws IOException {
        assertTrue(Files.isDirectory(INCIDENTS), "incident definitions not found at " + INCIDENTS.toAbsolutePath());
        try (Stream<Path> files = Files.list(INCIDENTS)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    // ------------------------------------------------------------------ the mapping table

    /**
     * One crime type, one incident. Structural rather than remembered — it is what guarantees a
     * mugging-murder cannot also emit a generic killing.
     */
    @Test
    void everyBuiltInCrimeMapsToExactlyOneIncident() {
        List<ResourceLocation> crimes = List.of(CrimeIds.HARM_VILLAGER, CrimeIds.KILL_VILLAGER,
                CrimeIds.ASSAULT_GUARD, CrimeIds.JAILBREAK, CrimeIds.KIDNAP, CrimeIds.THEFT,
                CrimeIds.MUGGING_MURDER);

        Set<ResourceLocation> incidents = new HashSet<>();
        for (ResourceLocation crime : crimes) {
            Optional<ResourceLocation> incident = CrimeIncidentMapping.incidentFor(crime);
            assertTrue(incident.isPresent(), crime + " has no civic incident");
            assertTrue(incidents.add(incident.get()),
                    incident.get() + " is claimed by more than one crime type");
        }
    }

    /** Assault and killing reuse Reputation's own ids so existing dialogue keeps working. */
    @Test
    void overlappingDeedsReuseTheCompanionsOwnIncidents() {
        assertEquals(Optional.of(CrimeIncidentMapping.VILLAGER_ASSAULTED),
                CrimeIncidentMapping.incidentFor(CrimeIds.HARM_VILLAGER));
        assertEquals(Optional.of(CrimeIncidentMapping.VILLAGER_KILLED),
                CrimeIncidentMapping.incidentFor(CrimeIds.KILL_VILLAGER));

        assertTrue(CrimeIncidentMapping.overlapsNativeDetection(CrimeIds.HARM_VILLAGER));
        assertTrue(CrimeIncidentMapping.overlapsNativeDetection(CrimeIds.KILL_VILLAGER));
        assertFalse(CrimeIncidentMapping.overlapsNativeDetection(CrimeIds.THEFT));
    }

    @Test
    void anUnknownCrimeTypeMapsToNothing() {
        assertTrue(CrimeIncidentMapping.incidentFor(new ResourceLocation("othermod", "jaywalking")).isEmpty());
    }

    // ------------------------------------------------------------------ resolution mapping

    @Test
    void settledCasesMapToAResolvedStatus() {
        assertEquals(Optional.of("atoned"),
                CrimeIncidentMapping.statusFor(Resolution.FINED, "atoned", "atoned"));
        assertEquals(Optional.of("apologized"),
                CrimeIncidentMapping.statusFor(Resolution.FINED, "apologized", "atoned"));
        assertEquals(Optional.of("atoned"),
                CrimeIncidentMapping.statusFor(Resolution.SERVED, "atoned", "atoned"));
        assertEquals(Optional.of("forgiven"),
                CrimeIncidentMapping.statusFor(Resolution.PARDONED, "atoned", "atoned"));
    }

    /**
     * Escaping is not atonement and a case ageing out is not the village forgiving a murder. Both must
     * leave the incident exactly where it is.
     */
    @Test
    void escapingAndExpiringChangeNothingCivically() {
        assertTrue(CrimeIncidentMapping.statusFor(Resolution.ESCAPED, "atoned", "atoned").isEmpty());
        assertTrue(CrimeIncidentMapping.statusFor(Resolution.EXPIRED, "atoned", "atoned").isEmpty());
        assertTrue(CrimeIncidentMapping.statusFor(Resolution.UNRESOLVED, "atoned", "atoned").isEmpty());
    }

    @Test
    void aNonsenseConfiguredStatusFallsBackRatherThanBreaking() {
        assertEquals(Optional.of("atoned"),
                CrimeIncidentMapping.statusFor(Resolution.FINED, "nonsense", "atoned"));
        assertFalse(CrimeIncidentMapping.isValidConfiguredStatus("forgiven"),
                "a fine must not be configurable into a pardon");
    }

    // ------------------------------------------------------------------ shipped JSON

    @Test
    void everyShippedIncidentHasTheRequiredFields() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            for (String required : List.of("display", "default_delta", "visibility", "severity")) {
                assertTrue(json.has(required), name + " is missing the required field " + required);
            }
            assertTrue(VISIBILITIES.contains(json.get("visibility").getAsString()),
                    name + " has an unknown visibility");
            assertTrue(SEVERITIES.contains(json.get("severity").getAsString()),
                    name + " has an unknown severity");
        }
    }

    /**
     * The schema's {@code max_override_abs} defaults to 100, so a file that omits it silently clamps
     * any caller delta at that. Setting it explicitly everywhere makes the intent visible.
     */
    @Test
    void everyIncidentSetsItsOverrideCapExplicitlyAndItCoversTheDelta() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            assertTrue(json.has("max_override_abs"),
                    name + " should set max_override_abs rather than inherit the default of "
                            + DEFAULT_MAX_OVERRIDE_ABS);
            int cap = json.get("max_override_abs").getAsInt();
            int delta = Math.abs(json.get("default_delta").getAsInt());
            assertTrue(cap >= delta, name + " caps overrides below its own default delta");
            assertTrue(delta <= HARD_DELTA_CAP, name + " exceeds the schema's hard delta cap");
        }
    }

    /** Positive deeds must read as positive and negative ones as negative — a sign error is silent. */
    @Test
    void amendsAreRewardsAndCrimesArePenalties() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            int delta = json.get("default_delta").getAsInt();
            List<String> tags = new ArrayList<>();
            json.getAsJsonArray("tags").forEach(element -> tags.add(element.getAsString()));

            if (tags.contains("amends")) {
                assertTrue(delta > 0, name + " is tagged amends but costs the player standing");
            }
            if (tags.contains("crime")) {
                assertTrue(delta < 0, name + " is tagged crime but rewards the player");
            }
        }
    }

    @Test
    void tagsAreLowercaseAndBounded() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            var tags = json.getAsJsonArray("tags");
            assertTrue(tags.size() <= 16, name + " has more than 16 tags");
            for (JsonElement element : tags) {
                String tag = element.getAsString();
                assertEquals(tag.toLowerCase(Locale.ROOT), tag, name + " has a non-lowercase tag: " + tag);
                assertTrue(tag.length() <= 48, name + " has an over-long tag: " + tag);
            }
        }
    }

    @Test
    void resolutionMultipliersAreFractions() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            if (!json.has("resolution")) {
                continue;
            }
            String name = file.getFileName().toString();
            json.getAsJsonObject("resolution").entrySet().forEach(entry -> {
                double value = entry.getValue().getAsDouble();
                assertTrue(value >= 0.0D && value <= 1.0D,
                        name + " resolution " + entry.getKey() + " is outside 0..1");
            });
        }
    }

    /**
     * A jailbreak is known to the authority without a villager having seen it — the one deed that is
     * legitimately public with no witness. Everything else must earn its visibility.
     */
    @Test
    void onlyTheJailbreakIsPublicWithoutAWitness() throws IOException {
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            if (!"village".equals(json.get("visibility").getAsString())) {
                continue;
            }
            List<String> tags = new ArrayList<>();
            json.getAsJsonArray("tags").forEach(element -> tags.add(element.getAsString()));
            assertTrue(name.equals("jailbreak.json") || tags.contains("amends"),
                    name + " is village-visible without being an official act or an amends deed; "
                            + "witnessed visibility is the honest default");
        }
    }

    /** Every phrase the definitions name has to exist, or the village gossips a raw key at the player. */
    @Test
    void everyDisplayAndGossipKeyIsTranslated() throws IOException {
        JsonObject lang = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> missing = new ArrayList<>();
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String display = json.getAsJsonObject("display").get("translate").getAsString();
            if (!lang.has(display)) {
                missing.add(display);
            }
            if (json.has("gossip")) {
                String phrase = json.getAsJsonObject("gossip").get("phrase").getAsString();
                if (!lang.has(phrase)) {
                    missing.add(phrase);
                }
            }
        }
        assertTrue(missing.isEmpty(), "untranslated keys: " + missing);
    }

    /** The files are shipped under our namespace, so their ids are ours; the mapping must agree. */
    @Test
    void theShippedFilesAreExactlyTheOnesTheMappingNames() throws IOException {
        Set<String> onDisk = new HashSet<>();
        for (Path file : incidentFiles()) {
            onDisk.add(file.getFileName().toString().replace(".json", ""));
        }
        for (ResourceLocation incident : List.of(CrimeIncidentMapping.GUARD_ASSAULTED,
                CrimeIncidentMapping.JAILBREAK, CrimeIncidentMapping.KIDNAPPING,
                CrimeIncidentMapping.THEFT, CrimeIncidentMapping.MUGGING_MURDER,
                CrimeIncidentMapping.FINE_PAID, CrimeIncidentMapping.SENTENCE_SERVED,
                CrimeIncidentMapping.CAPTIVE_RESCUED)) {
            assertEquals("mcacrime", incident.getNamespace(),
                    incident + " must be in our namespace to be shipped in our jar");
            assertTrue(onDisk.contains(incident.getPath()),
                    "the mapping names " + incident + " but no definition ships for it");
        }
    }
}
