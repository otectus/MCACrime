package dev.otectus.mcacrime.economy.fence;

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
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Datapack reload listener for fence prices in {@code data/<ns>/mcacrime/fence_prices/*.json}, in the
 * shape of the two loaders that came before it.
 *
 * <p>One file may name any number of items; the format is deliberately flat, because the interesting
 * decision a pack author makes here is a number rather than a structure:
 *
 * <pre>{@code {"item": "minecraft:tnt", "base": 12, "buys": true, "sells": true}}</pre>
 *
 * <p>A price for an item no tag names still counts: stating a price is how a pack introduces stock
 * without touching the tags at all. Stating {@code "sells"} or {@code "buys"} explicitly <em>replaces</em>
 * what a tag said about that direction rather than adding to it, which is how a pack takes a direction
 * away again.
 *
 * <p>0.6.0 separates two kinds of failure (audit finding B08). One bad entry is a diagnostic and is
 * skipped — a typo in one item's price must never take every fence in the world offline. A file that
 * is not a fence-price file at all is a different matter: the reload is abandoned and the last good
 * map is kept, because publishing a half-read set of prices would quietly delete stock the pack still
 * declares. {@code strictJsonValidation} turns either into a loud failure.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class FencePriceLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "mcacrime/fence_prices";

    /**
     * The largest base price an entry may state, matching the upper bound of
     * {@code criminalJobs.fence.defaultBasePrice}. Above this a single offer costs more currency than
     * a merchant screen has slots for, so the entry could only ever be an offer nobody is shown.
     */
    public static final long MAX_BASE_PRICE = 100_000L;

    private static volatile Map<ResourceLocation, FencePrice> prices = Map.of();

    /**
     * One priced item. {@code base} is before any Karma/Heat modifier.
     *
     * <p>{@code sellsStated}/{@code buysStated} record whether the file said anything about that
     * direction at all. An absent flag merges with whatever the tags contributed; a stated one wins.
     */
    public record FencePrice(ResourceLocation item, long base, boolean sells, boolean buys,
                             boolean sellsStated, boolean buysStated) {
    }

    /** What one reload's worth of files came to: the prices, the complaints, and whether to publish. */
    public record ParseResult(Map<ResourceLocation, FencePrice> prices, List<String> diagnostics,
                              boolean fileFailure) {
    }

    public FencePriceLoader() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new FencePriceLoader());
    }

    /** Everything the last reload loaded. Empty before the first one. */
    public static Collection<FencePrice> prices() {
        return prices.values();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        boolean strict = strict();
        ParseResult result = read(files, item -> ForgeRegistries.ITEMS.containsKey(item));
        for (String diagnostic : result.diagnostics()) {
            McaCrime.LOGGER.error("[MCA: Crime] {}", diagnostic);
        }
        if (strict && !result.diagnostics().isEmpty()) {
            throw new IllegalStateException(result.diagnostics().get(0));
        }
        if (result.fileFailure()) {
            McaCrime.LOGGER.error("[MCA: Crime] A fence price file could not be read at all; the {} price(s) "
                    + "from the last good reload are still in effect.", prices.size());
            return;
        }
        prices = Map.copyOf(result.prices());
        McaCrime.LOGGER.info("Loaded {} fence price(s) with {} error(s).", prices.size(),
                result.diagnostics().size());
        // The goods list is the join of these prices with the tags, and both have just changed.
        FenceGoodsRegistry.rebuild();
    }

    /**
     * Reads one reload's files, as a pure function of them.
     *
     * <p>No resource manager, no registry and no config: {@code itemExists} is the only thing that
     * needs the game, and it is a parameter so the rules above can be asserted without one.
     */
    public static ParseResult read(Map<ResourceLocation, JsonElement> files,
                                   Predicate<ResourceLocation> itemExists) {
        Map<ResourceLocation, FencePrice> loaded = new LinkedHashMap<>();
        // Which file each id came from, so a duplicate can name both of them.
        Map<ResourceLocation, ResourceLocation> origin = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        boolean fileFailure = false;

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            List<JsonObject> rows = new ArrayList<>();
            try {
                JsonElement element = entry.getValue();
                if (element.isJsonArray()) {
                    element.getAsJsonArray().forEach(child -> rows.add(child.getAsJsonObject()));
                } else {
                    rows.add(element.getAsJsonObject());
                }
            } catch (RuntimeException e) {
                // Not a fence price file at all. Nothing in it can be trusted, including the entries
                // that happen to have parsed, so the whole reload is abandoned below.
                diagnostics.add("Fence price file '" + fileId + "' is not a price entry or a list of them: "
                        + e.getMessage());
                fileFailure = true;
                continue;
            }
            for (JsonObject row : rows) {
                try {
                    FencePrice price = parse(row, itemExists);
                    ResourceLocation previous = origin.get(price.item());
                    if (previous != null) {
                        diagnostics.add("Fence price for '" + price.item() + "' is stated in both '"
                                + previous + "' and '" + fileId + "'; the entry in '" + fileId
                                + "' was ignored.");
                        continue;
                    }
                    origin.put(price.item(), fileId);
                    loaded.put(price.item(), price);
                } catch (RuntimeException e) {
                    diagnostics.add("Fence price '" + fileId + "': " + e.getMessage());
                }
            }
        }
        return new ParseResult(loaded, List.copyOf(diagnostics), fileFailure);
    }

    /**
     * One entry, or an exception naming what is wrong with it.
     *
     * <p>Every bound here exists because the value flows into a price a player is shown and a stack
     * count a merchant screen has to fit. A non-finite or absurd base is refused rather than clamped,
     * because clamping a hand-written 1e30 to a hundred thousand would be a fence quietly selling
     * something at a price nobody asked for.
     */
    public static FencePrice parse(JsonObject json, Predicate<ResourceLocation> itemExists) {
        String rawId = json.get("item").getAsString();
        ResourceLocation item = ResourceLocation.tryParse(rawId);
        if (item == null) {
            throw new IllegalArgumentException("'" + rawId + "' is not a valid item id");
        }
        if (itemExists != null && !itemExists.test(item)) {
            throw new IllegalArgumentException("no item '" + rawId + "' is registered");
        }
        long base = 1L;
        if (json.has("base")) {
            double raw = json.get("base").getAsDouble();
            if (!Double.isFinite(raw)) {
                throw new IllegalArgumentException("base price for '" + rawId + "' is not a number");
            }
            if (raw < 1.0D) {
                throw new IllegalArgumentException("base price for '" + rawId + "' must be at least 1");
            }
            if (raw > (double) MAX_BASE_PRICE) {
                throw new IllegalArgumentException("base price for '" + rawId + "' must be at most "
                        + MAX_BASE_PRICE);
            }
            base = (long) raw;
        }
        boolean sellsStated = json.has("sells");
        boolean buysStated = json.has("buys");
        boolean sells = !sellsStated || json.get("sells").getAsBoolean();
        boolean buys = !buysStated || json.get("buys").getAsBoolean();
        return new FencePrice(item, base, sells, buys, sellsStated, buysStated);
    }

    private static boolean strict() {
        try {
            return McaCrimeConfig.COMMON.strictJsonValidation.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }
}
