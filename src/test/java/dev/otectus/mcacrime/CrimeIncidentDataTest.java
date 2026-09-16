package dev.otectus.mcacrime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Path PROFILES = Paths.get("src", "main", "resources", "data", "mcacrime",
            "mcareputation", "incident_profiles");
    private static final Path CREDIT_POLICIES = Paths.get("src", "main", "resources", "data", "mcacrime",
            "mcareputation", "credit_policies");
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

    private static List<Path> jsonFiles(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory), "no files at " + directory.toAbsolutePath());
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String id(Path directory, Path file) {
        return "mcacrime:" + directory.relativize(file).toString().replace('\\', '/')
                .replace(".json", "");
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

    // ------------------------------------------------------------------ public profiles (0.6.0)

    /**
     * The shipped facets, as MCA: Reputation 0.6.0 defines them: id to whether the facet is bipolar.
     *
     * <p>A local copy, for the same reason the incident schema above is: these files ship inside
     * <em>our</em> jar and an authoring mistake in them must fail our build rather than turn up in
     * somebody's server log. A unipolar facet can never hold a negative value, so authoring one is an
     * error the companion rejects -- and a rejected facet takes every profile that referenced it down
     * with it, which on this side would mean crimes recorded with no social evidence at all.
     */
    private static final Map<String, Boolean> REPUTATION_FACETS = Map.of(
            "mcareputation:bravery", false,
            "mcareputation:compassion", true,
            "mcareputation:generosity", false,
            "mcareputation:lawfulness", true,
            "mcareputation:mercy", false,
            "mcareputation:reliability", true,
            "mcareputation:violence", false);

    private static final Set<String> RESOLUTION_MODES = Set.of("recognition", "historical", "evaluative");
    private static final Set<String> CREDIT_CLASSES = Set.of("commendable", "adverse", "mixed", "neutral");
    /** The schema's default profile decay step; every lifetime must be a whole multiple of it. */
    private static final long DECAY_STEP = 24000L;

    private static Map<String, JsonObject> profiles() throws IOException {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (Path file : jsonFiles(PROFILES)) {
            out.put(id(PROFILES, file), read(file));
        }
        return out;
    }

    private static Map<String, JsonObject> creditPolicies() throws IOException {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (Path file : jsonFiles(CREDIT_POLICIES)) {
            out.put(id(CREDIT_POLICIES, file), read(file));
        }
        return out;
    }

    /**
     * Every deed this mod files says what it is socially worth, and the profile it names ships.
     *
     * <p>An unresolvable {@code social_profile} is not fatal to the incident -- MCA: Reputation keeps
     * the scalar definition and drops only the attachment -- which is exactly why it needs asserting
     * here: the failure is silent, and its symptom is a village that never learns what a player is
     * known for.
     */
    @Test
    void everyShippedIncidentNamesAProfileThatShips() throws IOException {
        Map<String, JsonObject> profiles = profiles();
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String name = file.getFileName().toString();
            assertTrue(json.has("social_profile"),
                    name + " has no social_profile, so the deed produces no recognition and no facet "
                            + "evidence at all");
            String profileId = json.get("social_profile").getAsString();
            assertTrue(profiles.containsKey(profileId),
                    name + " names " + profileId + ", which this mod does not ship");
        }
    }

    /** A profile that does not admit the incident pointing at it is dropped with a warning. */
    @Test
    void everyProfileAdmitsTheIncidentsThatNameIt() throws IOException {
        Map<String, JsonObject> profiles = profiles();
        for (Path file : incidentFiles()) {
            JsonObject json = read(file);
            String incidentId = id(INCIDENTS, file);
            String profileId = json.get("social_profile").getAsString();
            JsonObject profile = profiles.get(profileId);
            List<String> allowed = new ArrayList<>();
            if (profile.has("allowed_incidents")) {
                profile.getAsJsonArray("allowed_incidents").forEach(e -> allowed.add(e.getAsString()));
            }
            assertTrue(allowed.isEmpty() || allowed.contains(incidentId),
                    profileId + " does not allow " + incidentId + ", so the attachment is refused");
        }
    }

    /** And nothing ships that nothing uses: an orphan profile is authored content nobody sees. */
    @Test
    void everyShippedProfileIsUsedByAnIncident() throws IOException {
        Set<String> referenced = new HashSet<>();
        for (Path file : incidentFiles()) {
            referenced.add(read(file).get("social_profile").getAsString());
        }
        for (String profileId : profiles().keySet()) {
            assertTrue(referenced.contains(profileId), profileId + " is shipped but no incident names it");
        }
    }

    @Test
    void profileChannelsAreWithinTheSchemasBounds() throws IOException {
        for (Map.Entry<String, JsonObject> entry : profiles().entrySet()) {
            String profileId = entry.getKey();
            JsonObject profile = entry.getValue();
            assertTrue(profile.has("recognition") || profile.has("facets"),
                    profileId + " authors neither recognition nor a facet, which is the same as having "
                            + "no profile");

            if (profile.has("recognition")) {
                JsonObject recognition = profile.getAsJsonObject("recognition");
                int points = recognition.get("points").getAsInt();
                assertTrue(points >= 0 && points <= 100,
                        profileId + " recognition points " + points + " is outside 0..100 -- becoming "
                                + "infamous makes you better known, not less");
                assertChannel(profileId + " recognition", recognition);
            }
            if (profile.has("facets")) {
                JsonObject facets = profile.getAsJsonObject("facets");
                assertTrue(facets.size() <= 8, profileId + " authors more than 8 facets");
                for (String facet : facets.keySet()) {
                    assertTrue(REPUTATION_FACETS.containsKey(facet),
                            profileId + " names facet " + facet + ", which no loaded pack defines");
                    JsonObject channel = facets.getAsJsonObject(facet);
                    int points = channel.get("points").getAsInt();
                    assertTrue(points >= -100 && points <= 100,
                            profileId + " facet " + facet + " is outside -100..100");
                    assertTrue(points >= 0 || REPUTATION_FACETS.get(facet),
                            profileId + " gives the unipolar facet " + facet + " a negative value, "
                                    + "which the companion rejects");
                    assertChannel(profileId + " " + facet, channel);
                }
            }
        }
    }

    private static void assertChannel(String label, JsonObject channel) {
        assertTrue(channel.has("lifetime_ticks"), label + " has no lifetime_ticks");
        long lifetime = channel.get("lifetime_ticks").getAsLong();
        assertTrue(lifetime >= DECAY_STEP && lifetime <= 100_000_000L,
                label + " lifetime " + lifetime + " is outside 24000..100000000");
        assertEquals(0L, lifetime % DECAY_STEP,
                label + " lifetime must be a whole multiple of the decay step or it silently truncates");
        assertTrue(channel.has("resolution_mode"), label + " does not say what an apology does to it");
        assertTrue(RESOLUTION_MODES.contains(channel.get("resolution_mode").getAsString()),
                label + " has an unknown resolution_mode");
    }

    /**
     * Crime evidence is never discounted for repetition, and a discount is never authored on a class
     * that may not carry one.
     *
     * <p>The rule that matters: repetition must not make harm cheaper. A {@code commendable} profile
     * with a negative facet, or a {@code credit_policy} on an adverse one, are both validation errors
     * on the companion's side -- and both would read here as "we limited farming" when nothing was
     * limited.
     */
    @Test
    void creditClassesAndPoliciesAgree() throws IOException {
        Map<String, JsonObject> policies = creditPolicies();
        for (Map.Entry<String, JsonObject> entry : profiles().entrySet()) {
            String profileId = entry.getKey();
            JsonObject profile = entry.getValue();
            String creditClass = profile.has("credit_class")
                    ? profile.get("credit_class").getAsString()
                    : "neutral";
            assertTrue(CREDIT_CLASSES.contains(creditClass), profileId + " has an unknown credit_class");

            if (profile.has("credit_policy")) {
                assertEquals("commendable", creditClass,
                        profileId + " authors a credit_policy on a '" + creditClass + "' profile; only "
                                + "commendable may be discounted");
                String policyId = profile.get("credit_policy").getAsString();
                assertTrue(policies.containsKey(policyId),
                        profileId + " names credit policy " + policyId + ", which this mod does not ship");
            }
            if ("commendable".equals(creditClass) && profile.has("facets")) {
                JsonObject facets = profile.getAsJsonObject("facets");
                for (String facet : facets.keySet()) {
                    assertTrue(facets.getAsJsonObject(facet).get("points").getAsInt() >= 0,
                            profileId + " is commendable but " + facet + " is adverse; repetition must "
                                    + "never make harm cheaper");
                }
            }
            if ("adverse".equals(creditClass) || "mixed".equals(creditClass)) {
                assertFalse(profile.has("credit_policy"),
                        profileId + " is " + creditClass + " and must receive full accountability");
            }
        }
    }

    /**
     * The anti-farming schedules themselves.
     *
     * <p>A non-decreasing schedule or a non-zero tail would let a player pay fines, or rescue the same
     * captive, indefinitely for full social credit -- which is the exploit the whole mechanism exists
     * to close.
     */
    @Test
    void everyCreditScheduleActuallyDiminishes() throws IOException {
        for (Map.Entry<String, JsonObject> entry : creditPolicies().entrySet()) {
            String policyId = entry.getKey();
            JsonObject policy = entry.getValue();
            assertEquals(policyId, policy.get("group").getAsString(),
                    policyId + " declares a different group than its file id, which is how two files "
                            + "end up disagreeing about one allowance");
            long window = policy.get("window_ticks").getAsLong();
            assertTrue(window >= 20L && window <= 100_000_000L, policyId + " has an out-of-range window");
            assertTrue(Set.of("player_community", "player_global")
                            .contains(policy.has("scope") ? policy.get("scope").getAsString()
                                    : "player_community"),
                    policyId + " has an unknown scope");

            List<Integer> schedule = new ArrayList<>();
            policy.getAsJsonArray("credit_schedule_bp").forEach(e -> schedule.add(e.getAsInt()));
            assertTrue(!schedule.isEmpty() && schedule.size() <= 32,
                    policyId + " must have 1..32 schedule entries");
            int previous = 10_000;
            for (int step : schedule) {
                assertTrue(step >= 0 && step <= 10_000, policyId + " has a step outside 0..10000");
                assertTrue(step <= previous, policyId + " has a rising schedule step");
                previous = step;
            }
            int tail = policy.has("tail_bp") ? policy.get("tail_bp").getAsInt() : 0;
            assertEquals(0, tail,
                    policyId + " pays a tail forever, which is slow farming by definition");
            assertTrue(tail <= schedule.get(schedule.size() - 1),
                    policyId + " has a tail above its last step, which restarts the schedule");
            assertEquals(0, schedule.get(schedule.size() - 1).intValue(),
                    policyId + " never reaches zero credit inside its window");
        }
    }

    /**
     * MCA: Reputation reads the four profile directories with a strict parser that errors on a
     * repeated key at any depth, and one error drops the whole profile bundle for that reload.
     *
     * <p>Gson resolves {@code {"points": 8, "points": 80}} to 80 without a word, so this is the one
     * authoring mistake in these files that could not be caught by reading the parsed object.
     */
    @Test
    void noProfileOrPolicyFileRepeatsAKey() throws IOException {
        List<Path> files = new ArrayList<>(jsonFiles(PROFILES));
        files.addAll(jsonFiles(CREDIT_POLICIES));
        for (Path file : files) {
            try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
                assertNoDuplicateKeys(reader, file.getFileName().toString());
            }
        }
    }

    private static void assertNoDuplicateKeys(JsonReader reader, String name) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> seen = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    assertTrue(seen.add(key), name + " repeats the key '" + key + "'");
                    assertNoDuplicateKeys(reader, name);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) {
                    assertNoDuplicateKeys(reader, name);
                }
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }
}
