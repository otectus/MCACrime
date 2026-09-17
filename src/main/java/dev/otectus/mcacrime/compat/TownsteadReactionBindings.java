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
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which Townstead reaction plays for which MCA: Crime public event.
 *
 * <p>Datapack reload listener for {@code data/<ns>/townstead/reaction_bindings/*.json}. One file may
 * carry any number of bindings; the shape is flat, because what a pack author changes here is a pairing
 * rather than a structure:
 *
 * <pre>{@code [{"event": "custody_started", "reaction": "townstead:concerned", "radius": 24}]}</pre>
 *
 * <ul>
 *   <li>{@code event} — one of {@link TownsteadReactionEvent}'s ids. Anything else is a load error.</li>
 *   <li>{@code reaction} — the Townstead reaction id to play. Validated as a resource location and
 *       nothing more: Townstead owns that namespace, it may not be installed when the pack loads, and
 *       an id this mod refused would be a settlement mod's content vetoed by a law mod.</li>
 *   <li>{@code radius} — optional, in blocks, bounded by {@link #MAX_RADIUS}. How far the news carries.</li>
 * </ul>
 *
 * <h2>Why an unbound event plays nothing</h2>
 *
 * <p>An event with no binding is silence, not a default. The alternative — falling back to some generic
 * reaction — would mean a pack that deliberately removed a binding still got an animation, and a typo in
 * an event name would produce a reaction that looked deliberate. Silence is the only fallback that can
 * be told apart from a decision.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class TownsteadReactionBindings extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();

    /** {@code data/<namespace>/townstead/reaction_bindings/*.json}. */
    public static final String DIRECTORY = "townstead/reaction_bindings";

    /**
     * The furthest a reaction may carry, in blocks.
     *
     * <p>A ceiling on the audience, and therefore on the cost: the delivery resolves villagers in a box
     * this size, and an unbounded radius would be a full-level entity scan attached to every arrest.
     * It is also a knowledge bound — the people who react should be the people who could plausibly have
     * heard.
     */
    public static final int MAX_RADIUS = 48;

    /** What a binding says when it does not say otherwise. */
    public static final int DEFAULT_RADIUS = 24;

    private static volatile Map<TownsteadReactionEvent, Binding> bindings = Map.of();
    private static volatile List<TownsteadDataProblem> problems = List.of();

    /**
     * One event bound to one reaction.
     *
     * @param event    MCA: Crime's own event
     * @param reaction the Townstead reaction id to play
     * @param radius   how far it carries, in blocks
     */
    public record Binding(TownsteadReactionEvent event, ResourceLocation reaction, int radius) {

        public Binding {
            radius = Math.max(1, Math.min(MAX_RADIUS, radius));
        }
    }

    /** One reload's worth of files: what parsed, and what did not. */
    public record ParseResult(Map<TownsteadReactionEvent, Binding> bindings,
                              List<TownsteadDataProblem> problems) {

        public ParseResult {
            bindings = Map.copyOf(bindings);
            problems = List.copyOf(problems);
        }
    }

    public TownsteadReactionBindings() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new TownsteadReactionBindings());
    }

    /** The binding for one event, or empty when a pack bound nothing to it. */
    public static Optional<Binding> binding(@Nullable TownsteadReactionEvent event) {
        return event == null ? Optional.empty() : Optional.ofNullable(bindings.get(event));
    }

    /** Everything the last reload loaded. */
    public static Map<TownsteadReactionEvent, Binding> all() {
        return bindings;
    }

    /** What the last reload refused, for {@code /crime validate} and the config validator. */
    public static List<TownsteadDataProblem> problems() {
        return problems;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        ParseResult result = read(files);
        problems = result.problems();
        for (TownsteadDataProblem problem : result.problems()) {
            McaCrime.LOGGER.error("[MCA: Crime] Townstead reaction binding {}", problem.describe());
        }
        if (!result.problems().isEmpty()) {
            if (strict()) {
                throw new IllegalStateException(result.problems().get(0).describe());
            }
            // A mapping is published whole or not at all. See TownsteadDataProblem for why half of one
            // is worse than the previous one.
            McaCrime.LOGGER.error("[MCA: Crime] The Townstead reaction bindings were not replaced; the {} "
                    + "binding(s) from the last good reload are still in effect.", bindings.size());
            return;
        }
        bindings = Map.copyOf(result.bindings());
        McaCrime.LOGGER.info("Loaded {} Townstead reaction binding(s).", bindings.size());
    }

    /** Reads one reload's files, as a pure function of them. */
    public static ParseResult read(Map<ResourceLocation, JsonElement> files) {
        Map<TownsteadReactionEvent, Binding> loaded = new LinkedHashMap<>();
        Map<TownsteadReactionEvent, ResourceLocation> origin = new LinkedHashMap<>();
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
                        "is not a reaction binding or a list of them: " + e.getMessage()));
                continue;
            }
            for (JsonObject row : rows) {
                try {
                    Binding binding = parse(row);
                    ResourceLocation previous = origin.get(binding.event());
                    if (previous != null) {
                        found.add(new TownsteadDataProblem(file, "binds '" + binding.event().id()
                                + "' again; it was already bound in '" + previous + "'. One event has one "
                                + "reaction, so remove one of them."));
                        continue;
                    }
                    origin.put(binding.event(), entry.getKey());
                    loaded.put(binding.event(), binding);
                } catch (RuntimeException e) {
                    found.add(new TownsteadDataProblem(file, e.getMessage()));
                }
            }
        }
        return new ParseResult(loaded, found);
    }

    /** One binding, or an exception naming exactly what is wrong with it. */
    public static Binding parse(JsonObject json) {
        String rawEvent = json.has("event") ? json.get("event").getAsString() : "";
        TownsteadReactionEvent event = TownsteadReactionEvent.byId(rawEvent);
        if (event == null) {
            throw new IllegalArgumentException("'" + rawEvent + "' is not an MCA: Crime public event. "
                    + "Expected one of: " + TownsteadReactionEvent.knownIds());
        }
        String rawReaction = json.has("reaction") ? json.get("reaction").getAsString() : "";
        ResourceLocation reaction = ResourceLocation.tryParse(rawReaction);
        if (reaction == null) {
            throw new IllegalArgumentException("the reaction for '" + event.id() + "' is '" + rawReaction
                    + "', which is not a valid resource location");
        }
        int radius = DEFAULT_RADIUS;
        if (json.has("radius")) {
            radius = json.get("radius").getAsInt();
            if (radius < 1 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("the radius for '" + event.id() + "' is " + radius
                        + "; it must be between 1 and " + MAX_RADIUS + " blocks");
            }
        }
        return new Binding(event, reaction, radius);
    }

    private static boolean strict() {
        try {
            return McaCrimeConfig.COMMON.strictJsonValidation.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }
}
