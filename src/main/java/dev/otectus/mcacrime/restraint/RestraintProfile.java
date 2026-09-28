package dev.otectus.mcacrime.restraint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One datapack's tuning override for one existing definition (plan §3.13).
 *
 * <p>An override, never a definition. {@link RestraintDefinitions} is a closed set because slot
 * semantics are an authorisation decision; what a pack may move are the numbers around them —
 * durability, which of the §7.1 actions the restraint takes away, how hard it is to pick, which key
 * family opens it and which rigs can wear it. Everything a pack cannot state (the slot, the render
 * pose, the statistics, the item) is simply absent from this record, so no amount of JSON can reach
 * it.
 *
 * <p>Every field is optional and every absent field means "keep what the code says". A file that
 * sets nothing is legal and changes nothing, which is what makes {@link #applyTo} safe to run over
 * every definition unconditionally.
 *
 * <p>Parsing is total: {@link #parse} never throws and never half-applies. It returns either a
 * profile or a list of reasons, and the loader rejects the whole file on the first reason — a
 * partially applied override would be a restraint whose durability came from the pack and whose
 * restrictions came from the code, which is a state neither the author nor the player asked for.
 *
 * @param definitionId  the definition this overrides; always an id that already exists
 * @param durability    starting durability, 1..{@link #MAX_DURABILITY}
 * @param restrictions  named {@link RestrictionPolicy} components and the value to force
 * @param pick          the whole pick profile, replaced rather than merged
 * @param keyFamily     the key identity, where {@link Optional#empty()} inside the outer optional
 *                      means "keyless"
 * @param supportedRigs rig ids this definition accepts, or {@link #ANY_RIG} for all of them
 */
public record RestraintProfile(
        ResourceLocation definitionId,
        OptionalInt durability,
        Map<String, Boolean> restrictions,
        Optional<RestraintDefinition.PickProfile> pick,
        Optional<Optional<RestraintFamily>> keyFamily,
        Optional<Set<String>> supportedRigs) {

    /** The same ceiling the {@code restraints.definitions.durability*} config keys carry. */
    public static final int MAX_DURABILITY = 4096;
    /** A meter alignment cannot add more than a whole meter. */
    public static final int MAX_PROGRESS_INCREASE = 40;
    /** The drain parameter's ceiling; above this the meter empties before the first alignment. */
    public static final int MAX_SPEED_INCREASE = 100;
    /** The wildcard a pack writes in {@code supported_rigs} to mean "any body". */
    public static final String ANY_RIG = "*";
    /** The {@code key_family} value that makes a definition keyless. */
    public static final String NO_KEY = "none";

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("durability", "restrictions", "pick", "key_family", "supported_rigs");
    private static final Set<String> PICK_KEYS =
            Set.of("pickable", "progress_increase", "speed_increase");

    public RestraintProfile {
        durability = durability == null ? OptionalInt.empty() : durability;
        restrictions = restrictions == null ? Map.of() : Map.copyOf(restrictions);
        pick = pick == null ? Optional.empty() : pick;
        keyFamily = keyFamily == null ? Optional.empty() : keyFamily;
        supportedRigs = supportedRigs == null ? Optional.empty()
                : supportedRigs.map(Set::copyOf);
    }

    /** An override that states nothing. */
    public static RestraintProfile none(ResourceLocation definitionId) {
        return new RestraintProfile(definitionId, OptionalInt.empty(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** True when this override would not change a single value. */
    public boolean empty() {
        return durability.isEmpty() && restrictions.isEmpty() && pick.isEmpty()
                && keyFamily.isEmpty() && supportedRigs.isEmpty();
    }

    /**
     * The result of reading one file: a profile, or the reasons it was refused.
     *
     * <p>Both, never neither: an empty error list means the profile is present.
     */
    public record Parsed(Optional<RestraintProfile> profile, List<String> errors) {

        public Parsed {
            profile = profile == null ? Optional.empty() : profile;
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static Parsed rejected(List<String> errors) {
            return new Parsed(Optional.empty(), errors);
        }

        static Parsed accepted(RestraintProfile profile) {
            return new Parsed(Optional.of(profile), List.of());
        }

        public boolean accepted() {
            return profile.isPresent();
        }
    }

    /**
     * Reads one file. Never throws, never returns a half-read profile.
     *
     * @param definitionId the id the file name resolved to; the caller has already checked it exists
     * @param json         the file's root element
     */
    public static Parsed parse(@Nullable ResourceLocation definitionId, @Nullable JsonElement json) {
        List<String> errors = new ArrayList<>();
        if (definitionId == null) {
            return Parsed.rejected(List.of("no definition id"));
        }
        if (json == null || !json.isJsonObject()) {
            return Parsed.rejected(List.of("root is not a JSON object"));
        }
        JsonObject root = json.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                errors.add("unknown field '" + key + "'");
            }
        }

        OptionalInt durability = OptionalInt.empty();
        if (root.has("durability")) {
            Optional<Integer> value = readInt(root.get("durability"), "durability", 1, MAX_DURABILITY, errors);
            if (value.isPresent()) {
                durability = OptionalInt.of(value.get());
            }
        }

        Map<String, Boolean> restrictions = readRestrictions(root, errors);
        Optional<RestraintDefinition.PickProfile> pick = readPick(root, errors);
        Optional<Optional<RestraintFamily>> keyFamily = readKeyFamily(root, errors);
        Optional<Set<String>> rigs = readRigs(root, errors);

        if (!errors.isEmpty()) {
            return Parsed.rejected(errors);
        }
        return Parsed.accepted(
                new RestraintProfile(definitionId, durability, restrictions, pick, keyFamily, rigs));
    }

    private static Map<String, Boolean> readRestrictions(JsonObject root, List<String> errors) {
        if (!root.has("restrictions")) {
            return Map.of();
        }
        JsonElement element = root.get("restrictions");
        if (!element.isJsonObject()) {
            errors.add("'restrictions' is not an object");
            return Map.of();
        }
        Map<String, Boolean> named = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String name = entry.getKey();
            if (!RestrictionPolicy.isComponent(name)) {
                errors.add("'restrictions." + name + "' is not a restriction component");
                continue;
            }
            Boolean value = readBoolean(entry.getValue(), "restrictions." + name, errors);
            if (value != null) {
                named.put(name, value);
            }
        }
        return named;
    }

    private static Optional<RestraintDefinition.PickProfile> readPick(JsonObject root, List<String> errors) {
        if (!root.has("pick")) {
            return Optional.empty();
        }
        JsonElement element = root.get("pick");
        if (!element.isJsonObject()) {
            errors.add("'pick' is not an object");
            return Optional.empty();
        }
        JsonObject pick = element.getAsJsonObject();
        for (String key : pick.keySet()) {
            if (!PICK_KEYS.contains(key)) {
                errors.add("unknown field 'pick." + key + "'");
            }
        }
        boolean pickable = true;
        if (pick.has("pickable")) {
            Boolean value = readBoolean(pick.get("pickable"), "pick.pickable", errors);
            pickable = value != null && value;
        }
        if (!pickable) {
            if (pick.has("progress_increase") || pick.has("speed_increase")) {
                errors.add("'pick' is not pickable but still names progress_increase or speed_increase");
            }
            return errors.isEmpty() ? Optional.of(RestraintDefinition.PickProfile.unpickable())
                    : Optional.empty();
        }
        if (!pick.has("progress_increase") || !pick.has("speed_increase")) {
            errors.add("'pick' is pickable and must name both progress_increase and speed_increase");
            return Optional.empty();
        }
        Optional<Integer> progress = readInt(pick.get("progress_increase"), "pick.progress_increase",
                1, MAX_PROGRESS_INCREASE, errors);
        Optional<Integer> speed = readInt(pick.get("speed_increase"), "pick.speed_increase",
                1, MAX_SPEED_INCREASE, errors);
        if (progress.isEmpty() || speed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RestraintDefinition.PickProfile.of(progress.get(), speed.get()));
    }

    private static Optional<Optional<RestraintFamily>> readKeyFamily(JsonObject root, List<String> errors) {
        if (!root.has("key_family")) {
            return Optional.empty();
        }
        JsonElement element = root.get("key_family");
        if (!isString(element)) {
            errors.add("'key_family' is not a string");
            return Optional.empty();
        }
        String raw = element.getAsString().trim().toLowerCase(Locale.ROOT);
        if (NO_KEY.equals(raw)) {
            return Optional.of(Optional.empty());
        }
        Optional<RestraintFamily> family = RestraintFamily.parse(raw);
        if (family.isEmpty()) {
            errors.add("'key_family' names no family: '" + raw + "'");
            return Optional.empty();
        }
        return Optional.of(family);
    }

    private static Optional<Set<String>> readRigs(JsonObject root, List<String> errors) {
        if (!root.has("supported_rigs")) {
            return Optional.empty();
        }
        JsonElement element = root.get("supported_rigs");
        if (!element.isJsonArray()) {
            errors.add("'supported_rigs' is not an array");
            return Optional.empty();
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            errors.add("'supported_rigs' is empty; a definition nothing can wear is not expressible");
            return Optional.empty();
        }
        Set<String> rigs = new LinkedHashSet<>();
        for (JsonElement entry : array) {
            if (!isString(entry)) {
                errors.add("'supported_rigs' holds a non-string entry");
                continue;
            }
            String rig = entry.getAsString().trim().toLowerCase(Locale.ROOT);
            if (rig.isEmpty()) {
                errors.add("'supported_rigs' holds a blank entry");
                continue;
            }
            rigs.add(rig);
        }
        return rigs.isEmpty() ? Optional.empty() : Optional.of(rigs);
    }

    private static Optional<Integer> readInt(JsonElement element, String field, int min, int max,
                                             List<String> errors) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            errors.add("'" + field + "' is not a number");
            return Optional.empty();
        }
        double raw = element.getAsDouble();
        if (raw != Math.floor(raw) || Double.isInfinite(raw)) {
            errors.add("'" + field + "' is not a whole number");
            return Optional.empty();
        }
        int value = element.getAsInt();
        if (value < min || value > max) {
            errors.add("'" + field + "' is " + value + ", outside [" + min + ", " + max + "]");
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Nullable
    private static Boolean readBoolean(JsonElement element, String field, List<String> errors) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            errors.add("'" + field + "' is not a boolean");
            return null;
        }
        return element.getAsBoolean();
    }

    private static boolean isString(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString();
    }

    /**
     * This override applied to {@code base}, as the definition everything then reads.
     *
     * <p>A new record rather than a mutation: the code table stays the immutable thing a test can
     * assert against, and {@code /reload}ing a pack away restores it exactly.
     */
    @Nullable
    public RestraintDefinition applyTo(@Nullable RestraintDefinition base) {
        if (base == null) {
            return null;
        }
        if (empty()) {
            return base;
        }
        RestraintDefinition.EscapeProfile escape = base.escape();
        if (durability.isPresent()) {
            escape = new RestraintDefinition.EscapeProfile(durability.getAsInt(),
                    escape.struggleBreakable(), escape.baseBreakChance(),
                    escape.removableWithCuttingTool());
        }
        RestrictionPolicy policy = base.restrictions();
        for (Map.Entry<String, Boolean> entry : restrictions.entrySet()) {
            policy = policy.with(entry.getKey(), entry.getValue()).orElse(policy);
        }
        Optional<RestraintFamily> family = base.family();
        Optional<ResourceLocation> keyItem = base.keyItem();
        if (keyFamily.isPresent()) {
            family = keyFamily.get();
            keyItem = family.flatMap(RestraintDefinitions::keyItemFor);
        }
        java.util.function.Predicate<RigProfile> rigPredicate = base.rigPredicate();
        if (supportedRigs.isPresent()) {
            Set<String> allowed = supportedRigs.get();
            rigPredicate = allowed.contains(ANY_RIG)
                    ? rig -> true
                    : rig -> rig != null && allowed.contains(rig.rigId());
        }
        return new RestraintDefinition(base.id(), base.slot(), family, base.item(), keyItem,
                policy, escape, pick.orElse(base.pick()), base.render(), rigPredicate,
                base.allowedEnchantments(), base.statistics(), base.applySound(), base.revision());
    }
}
