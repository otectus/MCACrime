package dev.otectus.mcacrime.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How much a villager's Townstead personality is allowed to colour their reaction to a crime.
 *
 * <p>Datapack reload listener for {@code data/<ns>/townstead/personality_profiles/*.json}:
 *
 * <pre>{@code [{"personality": "townstead:brave", "threat": -0.05, "report": 0.05, "flee": -0.05}]}</pre>
 *
 * <ul>
 *   <li>{@code personality} — Townstead's own personality id. Not validated against a registry, for the
 *       same reason building types are not: Townstead owns that namespace and may not be installed when
 *       the pack loads.</li>
 *   <li>{@code threat} — how much more (or less) threatening this villager finds a given scene.</li>
 *   <li>{@code report} — how much more (or less) likely they are to carry an account to an authority.</li>
 *   <li>{@code flee} — how much more (or less) likely they are to run rather than intervene.</li>
 * </ul>
 *
 * <h2>Why every weight is capped, hard, at a tenth</h2>
 *
 * <p>This is the setting most likely to be turned into something it should not be. A personality tweak
 * that can reach ±1.0 is not a tweak, it is a switch: a "timid" villager who never reports anything makes
 * crime consequence-free in half the settlements on the map, and a "brave" one who always intervenes
 * makes every theft a brawl. Neither reads as a datapack value to the player — it reads as the mod being
 * broken.
 *
 * <p>So {@link #MAX_WEIGHT} is enforced at parse time and a value outside it is a load error with the
 * number in it, rather than a silent clamp. Clamping would leave a pack author convinced their 0.8 was
 * in effect. The same ±0.10 ceiling is what the needs modifiers use, and for the same reason.
 *
 * <p>These weights describe <em>disposition</em>, never <em>perception</em>. Nothing here may change
 * what a villager can see, whether an observation is recorded, or whether a report is truthful — a
 * personality that altered the evidence would be a personality that decided guilt.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TownsteadPersonalityProfiles extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();

    /** {@code data/<namespace>/townstead/personality_profiles/*.json}. */
    public static final String DIRECTORY = "townstead/personality_profiles";

    /** The largest nudge any one weight may apply, in either direction. */
    public static final double MAX_WEIGHT = 0.10D;

    private static volatile Map<String, Profile> profiles = Map.of();
    private static volatile List<TownsteadDataProblem> problems = List.of();

    /** The profile for a personality nobody described: no opinion at all. */
    public static final Profile NEUTRAL = new Profile("", 0.0D, 0.0D, 0.0D);

    /**
     * One personality's disposition, as three bounded nudges.
     *
     * @param personality Townstead's personality id, lowercased at the seam
     * @param threat      added to the perceived threat of a scene
     * @param report      added to the willingness to carry an account to an authority
     * @param flee        added to the preference for leaving over intervening
     */
    public record Profile(String personality, double threat, double report, double flee) {

        public Profile {
            personality = personality == null ? "" : personality.trim().toLowerCase(Locale.ROOT);
            threat = clamp(threat);
            report = clamp(report);
            flee = clamp(flee);
        }

        /** Belt and braces: parse refuses an out-of-range value, and the record cannot hold one anyway. */
        private static double clamp(double value) {
            if (!Double.isFinite(value)) {
                return 0.0D;
            }
            return Math.max(-MAX_WEIGHT, Math.min(MAX_WEIGHT, value));
        }

        /** Whether this profile says anything at all. */
        public boolean neutral() {
            return threat == 0.0D && report == 0.0D && flee == 0.0D;
        }
    }

    /** One reload's worth of files: what parsed, and what did not. */
    public record ParseResult(Map<String, Profile> profiles, List<TownsteadDataProblem> problems) {

        public ParseResult {
            profiles = Map.copyOf(profiles);
            problems = List.copyOf(problems);
        }
    }

    public TownsteadPersonalityProfiles() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new TownsteadPersonalityProfiles());
    }

    /**
     * The profile for one personality, never null.
     *
     * <p>{@link #NEUTRAL} for an unknown or absent personality, which is the honest answer and the safe
     * one: a villager MCA: Crime knows nothing about behaves exactly as they did before this file
     * existed.
     */
    public static Profile profile(@Nullable String personality) {
        if (personality == null || personality.isBlank()) {
            return NEUTRAL;
        }
        return profiles.getOrDefault(personality.trim().toLowerCase(Locale.ROOT), NEUTRAL);
    }

    /** Everything the last reload loaded. */
    public static Map<String, Profile> all() {
        return profiles;
    }

    /** What the last reload refused. */
    public static List<TownsteadDataProblem> problems() {
        return problems;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        ParseResult result = read(files);
        problems = result.problems();
        for (TownsteadDataProblem problem : result.problems()) {
            McaCrime.LOGGER.error("[MCA: Crime] Townstead personality profile {}", problem.describe());
        }
        if (!result.problems().isEmpty()) {
            if (strict()) {
                throw new IllegalStateException(result.problems().get(0).describe());
            }
            McaCrime.LOGGER.error("[MCA: Crime] The Townstead personality profiles were not replaced; the "
                    + "{} profile(s) from the last good reload are still in effect.", profiles.size());
            return;
        }
        profiles = Map.copyOf(result.profiles());
        McaCrime.LOGGER.info("Loaded {} Townstead personality profile(s).", profiles.size());
    }

    /** Reads one reload's files, as a pure function of them. */
    public static ParseResult read(Map<ResourceLocation, JsonElement> files) {
        Map<String, Profile> loaded = new LinkedHashMap<>();
        Map<String, ResourceLocation> origin = new LinkedHashMap<>();
        List<TownsteadDataProblem> found = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            String file = entry.getKey().toString();
            List<JsonObject> rows = new ArrayList<>();
            try {
                JsonElement element = entry.getValue();
                if (element.isJsonArray()) {
                    element.getAsJsonArray().forEach(child -> rows.add(child.getAsJsonObject()));
                } else {
                    rows.add(element.getAsJsonObject());
                }
            } catch (RuntimeException e) {
                found.add(new TownsteadDataProblem(file,
                        "is not a personality profile or a list of them: " + e.getMessage()));
                continue;
            }
            for (JsonObject row : rows) {
                try {
                    Profile profile = parse(row);
                    ResourceLocation previous = origin.get(profile.personality());
                    if (previous != null) {
                        found.add(new TownsteadDataProblem(file, "describes '" + profile.personality()
                                + "' again; it was already described in '" + previous + "'. One "
                                + "personality has one profile, so remove one of them."));
                        continue;
                    }
                    origin.put(profile.personality(), entry.getKey());
                    loaded.put(profile.personality(), profile);
                } catch (RuntimeException e) {
                    found.add(new TownsteadDataProblem(file, e.getMessage()));
                }
            }
        }
        return new ParseResult(loaded, found);
    }

    /** One profile, or an exception naming exactly what is wrong with it. */
    public static Profile parse(JsonObject json) {
        String raw = json.has("personality") ? json.get("personality").getAsString().trim() : "";
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("an entry has no 'personality' id");
        }
        // Lowercased before validation, for the same reason building types are: a resource location may
        // not contain an upper case letter, and a pack's casing is not a mistake worth refusing. The
        // message quotes what the author wrote.
        String personality = raw.toLowerCase(Locale.ROOT);
        if (ResourceLocation.tryParse(personality) == null) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid personality id");
        }
        return new Profile(personality,
                weight(json, "threat", raw),
                weight(json, "report", raw),
                weight(json, "flee", raw));
    }

    private static double weight(JsonObject json, String key, String personality) {
        if (!json.has(key)) {
            return 0.0D;
        }
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("the '" + key + "' weight for '" + personality
                    + "' is not a number");
        }
        if (Math.abs(value) > MAX_WEIGHT) {
            // Refused rather than clamped. See the class comment: a silently clamped 0.8 leaves a pack
            // author believing a switch is in place when a nudge is.
            throw new IllegalArgumentException("the '" + key + "' weight for '" + personality + "' is "
                    + value + "; personality nudges are capped at " + MAX_WEIGHT
                    + " in either direction, and a larger value would decide behaviour rather than "
                    + "colour it");
        }
        return value;
    }

    private static boolean strict() {
        try {
            return McaCrimeConfig.COMMON.strictJsonValidation.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
