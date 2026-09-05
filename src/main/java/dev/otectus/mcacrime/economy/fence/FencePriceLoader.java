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
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * without touching the tags at all. A malformed entry is logged and skipped, so one typo never takes
 * every fence in the world offline — unless {@code strictJsonValidation} is on.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class FencePriceLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "mcacrime/fence_prices";

    private static volatile Map<ResourceLocation, FencePrice> prices = Map.of();

    /** One priced item. {@code base} is before any Karma/Heat modifier. */
    public record FencePrice(ResourceLocation item, long base, boolean sells, boolean buys) {
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
        Map<ResourceLocation, FencePrice> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                JsonElement element = entry.getValue();
                if (element.isJsonArray()) {
                    for (JsonElement child : element.getAsJsonArray()) {
                        FencePrice price = parse(child.getAsJsonObject());
                        loaded.put(price.item(), price);
                    }
                } else {
                    FencePrice price = parse(element.getAsJsonObject());
                    loaded.put(price.item(), price);
                }
            } catch (RuntimeException e) {
                String message = "Fence price '" + fileId + "': " + e.getMessage();
                errors.add(message);
                McaCrime.LOGGER.error("[MCA: Crime] {}", message);
                if (strict) {
                    throw new IllegalStateException(message, e);
                }
            }
        }

        prices = Map.copyOf(loaded);
        McaCrime.LOGGER.info("Loaded {} fence price(s) with {} error(s).", loaded.size(), errors.size());
        // The goods list is the join of these prices with the tags, and both have just changed.
        FenceGoodsRegistry.rebuild();
    }

    private static FencePrice parse(JsonObject json) {
        String rawId = json.get("item").getAsString();
        ResourceLocation item = ResourceLocation.tryParse(rawId);
        if (item == null) {
            throw new IllegalArgumentException("'" + rawId + "' is not a valid item id");
        }
        long base = json.has("base") ? json.get("base").getAsLong() : 1L;
        if (base < 1L) {
            throw new IllegalArgumentException("base price for '" + rawId + "' must be at least 1");
        }
        boolean sells = !json.has("sells") || json.get("sells").getAsBoolean();
        boolean buys = !json.has("buys") || json.get("buys").getAsBoolean();
        return new FencePrice(item, base, sells, buys);
    }

    private static boolean strict() {
        try {
            return McaCrimeConfig.COMMON.strictJsonValidation.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }
}
