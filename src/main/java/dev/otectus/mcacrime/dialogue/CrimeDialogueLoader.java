package dev.otectus.mcacrime.dialogue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datapack reload listener for dialogue in {@code data/<ns>/mcacrime/dialogue/*.json} (spec §16.3).
 * Reloads with {@code /reload} or {@code /crime reload}, exactly like crime definitions.
 *
 * <p>Later packs replace earlier ones by event id, which is what lets a server owner rewrite what
 * villagers say without touching the built-ins: drop a file declaring the same {@code event} and the
 * whole pool is theirs. A malformed file is logged and skipped so one bad line never silences every
 * villager in the world — unless {@code strictJsonValidation} is on, where it is a hard error.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class CrimeDialogueLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "mcacrime/dialogue";

    public CrimeDialogueLoader() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new CrimeDialogueLoader());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        boolean strict = McaCrimeConfig.COMMON.strictJsonValidation.get();
        Map<ResourceLocation, CrimeDialogueDefinition> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                CrimeDialogueDefinition definition =
                        CrimeDialogueDefinition.parse(fileId, entry.getValue().getAsJsonObject());
                loaded.put(definition.event(), definition);
            } catch (RuntimeException e) {
                String message = "Dialogue '" + fileId + "': " + e.getMessage();
                errors.add(message);
                McaCrime.LOGGER.error("[MCA: Crime] {}", message);
                if (strict) {
                    throw new IllegalStateException(message, e);
                }
            }
        }

        CrimeDialogueService.replaceAll(loaded, errors);
        McaCrime.LOGGER.info("Loaded {} dialogue event(s) with {} error(s).", loaded.size(), errors.size());
    }
}
