package dev.otectus.mcacrime.crime.type;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datapack reload listener for crime definitions in {@code data/<ns>/mcacrime/crimes/*.json} (spec §5.1).
 * Reloads with {@code /reload} or {@code /crime reload}. A malformed crime is logged and skipped (the
 * built-in fallback covers it) unless {@code strictJsonValidation} is enabled. Mirrors
 * {@code mca-quests}' QuestDataLoader.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeTypeLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "mcacrime/crimes";

    public CrimeTypeLoader() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new CrimeTypeLoader());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        boolean strict = McaCrimeConfig.COMMON.strictJsonValidation.get();
        Map<ResourceLocation, CrimeType> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            CrimeType.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                    .resultOrPartial(message -> recordError(errors, strict, "Crime '" + fileId + "': " + message))
                    .ifPresent(crime -> {
                        if (loaded.containsKey(crime.id())) {
                            recordError(errors, strict, "Duplicate crime id '" + crime.id() + "' (from " + fileId + ")");
                            return;
                        }
                        loaded.put(crime.id(), crime);
                    });
        }

        CrimeTypeRegistry.replaceAll(loaded, errors);
        McaCrime.LOGGER.info("Loaded {} crime type(s) with {} error(s).", loaded.size(), errors.size());
    }

    private static void recordError(List<String> errors, boolean strict, String message) {
        errors.add(message);
        McaCrime.LOGGER.error("[MCA: Crime] {}", message);
        if (strict) {
            throw new IllegalStateException(message);
        }
    }
}
