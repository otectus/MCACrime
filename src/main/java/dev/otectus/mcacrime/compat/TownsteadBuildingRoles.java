package dev.otectus.mcacrime.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.facility.FacilityRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which Townstead building types MCA: Crime may recognise as which facility, without an operator saying
 * so building by building.
 *
 * <p>Datapack reload listener for {@code data/<ns>/townstead/building_roles/*.json}:
 *
 * <pre>{@code [{"building": "townstead:jail_l1", "role": "jail_cell", "capacity": 2}]}</pre>
 *
 * <ul>
 *   <li>{@code building} — Townstead's own building type id, as its resources spell it. Not validated
 *       against a registry: Townstead may not be installed when the pack loads, and a settlement mod's
 *       content is not this mod's to veto.</li>
 *   <li>{@code role} — one of {@link FacilityRole}'s ids. Anything else is a load error.</li>
 *   <li>{@code capacity} — optional; how many prisoners one such building holds. Defaults to the role's
 *       own, and is bounded by {@link #MAX_CAPACITY}.</li>
 * </ul>
 *
 * <h2>Why this file exists, and why it ships empty of jails by default</h2>
 *
 * <p>{@code FacilityRole} says it plainly: Townstead names buildings for what they are, and it has no
 * notion of a jail at all. A kitchen is not a cell because it has a door. So MCA: Crime cannot infer any
 * of this, and the default data here binds only what is genuinely unambiguous. What this mapping adds is
 * the ability for a pack — or for Townstead, once it ships such a building — to say "this type is a
 * holding cell" once, instead of an operator running {@code /crime facility assign} in every village
 * they visit.
 *
 * <p>A recognised role is still only a candidate. {@code CrimeFacilityService} validates the building
 * and the anchor before anybody is held in it, exactly as it does for a hand-made assignment; this
 * decides what may be offered, never what is safe.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TownsteadBuildingRoles extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();

    /** {@code data/<namespace>/townstead/building_roles/*.json}. */
    public static final String DIRECTORY = "townstead/building_roles";

    /** The most prisoners one recognised building may be credited with. */
    public static final int MAX_CAPACITY = 16;

    private static volatile Map<String, Recognition> roles = Map.of();
    private static volatile List<TownsteadDataProblem> problems = List.of();

    /**
     * One Townstead building type MCA: Crime recognises.
     *
     * @param buildingType Townstead's type id, lowercased at the seam so a pack's casing does not matter
     * @param role         what MCA: Crime would use it for
     * @param capacity     how many prisoners it holds, for a role that holds any
     */
    public record Recognition(String buildingType, FacilityRole role, int capacity) {

        public Recognition {
            buildingType = buildingType == null ? "" : buildingType.trim().toLowerCase(Locale.ROOT);
            capacity = Math.max(0, Math.min(MAX_CAPACITY, capacity));
        }
    }

    /** One reload's worth of files: what parsed, and what did not. */
    public record ParseResult(Map<String, Recognition> roles, List<TownsteadDataProblem> problems) {

        public ParseResult {
            roles = Map.copyOf(roles);
            problems = List.copyOf(problems);
        }
    }

    public TownsteadBuildingRoles() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new TownsteadBuildingRoles());
    }

    /**
     * What MCA: Crime would use a building of this type for, if anything.
     *
     * <p>The type is matched case-insensitively and an unknown one is simply empty: an unrecognised
     * building is the overwhelmingly common case and is not a problem — it is a bakery.
     */
    public static Optional<Recognition> recognise(@Nullable String buildingType) {
        if (buildingType == null || buildingType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roles.get(buildingType.trim().toLowerCase(Locale.ROOT)));
    }

    /** Everything the last reload loaded. */
    public static Map<String, Recognition> all() {
        return roles;
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
            McaCrime.LOGGER.error("[MCA: Crime] Townstead building role {}", problem.describe());
        }
        if (!result.problems().isEmpty()) {
            if (strict()) {
                throw new IllegalStateException(result.problems().get(0).describe());
            }
            McaCrime.LOGGER.error("[MCA: Crime] The Townstead building roles were not replaced; the {} "
                    + "recognised type(s) from the last good reload are still in effect.", roles.size());
            return;
        }
        roles = Map.copyOf(result.roles());
        McaCrime.LOGGER.info("Loaded {} Townstead building role(s).", roles.size());
    }

    /** Reads one reload's files, as a pure function of them. */
    public static ParseResult read(Map<ResourceLocation, JsonElement> files) {
        Map<String, Recognition> loaded = new LinkedHashMap<>();
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
                        "is not a building role or a list of them: " + e.getMessage()));
                continue;
            }
            for (JsonObject row : rows) {
                try {
                    Recognition recognition = parse(row);
                    ResourceLocation previous = origin.get(recognition.buildingType());
                    if (previous != null) {
                        found.add(new TownsteadDataProblem(file, "gives '" + recognition.buildingType()
                                + "' a second role; it already has one in '" + previous + "'. One building "
                                + "type has one role, so remove one of them."));
                        continue;
                    }
                    origin.put(recognition.buildingType(), entry.getKey());
                    loaded.put(recognition.buildingType(), recognition);
                } catch (RuntimeException e) {
                    found.add(new TownsteadDataProblem(file, e.getMessage()));
                }
            }
        }
        return new ParseResult(loaded, found);
    }

    /** One recognition, or an exception naming exactly what is wrong with it. */
    public static Recognition parse(JsonObject json) {
        String raw = json.has("building") ? json.get("building").getAsString().trim() : "";
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("an entry has no 'building' type id");
        }
        // Lowercased before it is validated, not after: a resource location may not contain an upper
        // case letter, so a pack that wrote "Townstead:Guardhouse" would otherwise be told its own
        // building type is malformed. Casing is not meaning here. The message still quotes what the
        // author wrote, so they can find it in their file.
        String building = raw.toLowerCase(Locale.ROOT);
        if (ResourceLocation.tryParse(building) == null) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid building type id");
        }
        String rawRole = json.has("role") ? json.get("role").getAsString() : "";
        FacilityRole role = FacilityRole.parse(rawRole).orElseThrow(() ->
                new IllegalArgumentException("'" + rawRole + "' is not an MCA: Crime facility role for '"
                        + raw + "'. Expected one of: " + knownRoles()));
        int capacity = role.defaultCapacity();
        if (json.has("capacity")) {
            capacity = json.get("capacity").getAsInt();
            if (capacity < 0 || capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("the capacity for '" + raw + "' is " + capacity
                        + "; it must be between 0 and " + MAX_CAPACITY);
            }
        }
        return new Recognition(building, role, capacity);
    }

    private static String knownRoles() {
        StringBuilder out = new StringBuilder();
        for (FacilityRole role : FacilityRole.values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(role.id());
        }
        return out.toString();
    }

    private static boolean strict() {
        try {
            return McaCrimeConfig.COMMON.strictJsonValidation.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
