package dev.otectus.mcacrime.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One dialogue event's line pool (spec §16.3), as loaded from
 * {@code data/<namespace>/mcacrime/dialogue/*.json}.
 *
 * <p>A definition is pure data: conditions and translation keys, no executable behaviour. That is the
 * §16.1 rule that keeps dialogue safe to accept from a datapack — a pack can add or replace what a
 * villager says, and can never change what actually happens, because nothing downstream branches on
 * the text. The server picks the key; the client renders it.
 *
 * <p>Variants are sorted by descending priority at parse time, so selection is a single scan and the
 * order is stable regardless of how the JSON was written.
 */
public record CrimeDialogueDefinition(ResourceLocation event, String fallback, List<Variant> variants) {

    /** Hard ceiling on variants per event, so a hostile pack cannot make selection expensive. */
    public static final int MAX_VARIANTS = 64;
    /** Hard ceiling on lines per variant. */
    public static final int MAX_LINES = 16;

    public CrimeDialogueDefinition {
        variants = List.copyOf(variants);
    }

    /**
     * One conditional line pool.
     *
     * @param priority higher wins; the first match in descending priority order is used
     * @param when     fact name to expected value; every entry must be satisfied
     * @param lines    translation keys, one of which is chosen deterministically per encounter
     */
    public record Variant(int priority, Map<String, String> when, List<String> lines) {
        public Variant {
            when = Map.copyOf(when);
            lines = List.copyOf(lines);
        }

        boolean matches(DialogueContext context) {
            for (Map.Entry<String, String> entry : when.entrySet()) {
                if (!context.satisfies(entry.getKey(), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Parses one definition file.
     *
     * @throws IllegalArgumentException with a message naming what is wrong, so the loader can report
     *         the offending file rather than swallowing it
     */
    public static CrimeDialogueDefinition parse(ResourceLocation fileId, JsonObject json) {
        String eventRaw = json.has("event") ? json.get("event").getAsString() : fileId.toString();
        ResourceLocation event = ResourceLocation.tryParse(eventRaw);
        if (event == null) {
            throw new IllegalArgumentException("'event' is not a valid id: " + eventRaw);
        }
        if (!json.has("fallback") || !json.get("fallback").isJsonPrimitive()) {
            throw new IllegalArgumentException("'fallback' translation key is required");
        }
        String fallback = json.get("fallback").getAsString();
        if (fallback.isBlank()) {
            throw new IllegalArgumentException("'fallback' must not be blank");
        }

        List<Variant> variants = new ArrayList<>();
        if (json.has("variants")) {
            JsonArray array = json.getAsJsonArray("variants");
            for (int i = 0; i < array.size() && variants.size() < MAX_VARIANTS; i++) {
                variants.add(parseVariant(array.get(i).getAsJsonObject(), i));
            }
        }
        // Descending priority, then declaration order for ties, so two equally specific variants always
        // resolve the same way rather than depending on which file happened to load first.
        variants.sort(Comparator.comparingInt(Variant::priority).reversed());
        return new CrimeDialogueDefinition(event, fallback, variants);
    }

    private static Variant parseVariant(JsonObject json, int index) {
        int priority = json.has("priority") ? json.get("priority").getAsInt() : 0;
        Map<String, String> when = new LinkedHashMap<>();
        if (json.has("when")) {
            JsonObject conditions = json.getAsJsonObject("when");
            for (Map.Entry<String, JsonElement> entry : conditions.entrySet()) {
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive()) {
                    throw new IllegalArgumentException(
                            "variant " + index + " condition '" + entry.getKey() + "' must be a string or boolean");
                }
                when.put(entry.getKey(), DialogueContext.normalise(value.getAsString()));
            }
        }
        List<String> lines = new ArrayList<>();
        if (json.has("lines")) {
            JsonArray array = json.getAsJsonArray("lines");
            for (int i = 0; i < array.size() && lines.size() < MAX_LINES; i++) {
                String line = array.get(i).getAsString();
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("variant " + index + " has no lines");
        }
        return new Variant(priority, when, lines);
    }

    /**
     * The translation key to speak for this context, or the fallback when nothing matches.
     *
     * <p>Never returns empty or null: §16.1 requires missing dialogue to degrade to a generic line
     * rather than an empty bubble, because an NPC that opens its mouth and says nothing reads as a
     * crash even when everything else worked.
     */
    public String select(DialogueContext context) {
        for (Variant variant : variants) {
            if (variant.matches(context)) {
                List<String> lines = variant.lines();
                int index = (int) Math.floorMod(context.variantSeed(), lines.size());
                return lines.get(index);
            }
        }
        return fallback;
    }
}
