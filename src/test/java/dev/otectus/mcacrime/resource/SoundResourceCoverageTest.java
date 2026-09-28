package dev.otectus.mcacrime.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.TestPaths;
import dev.otectus.mcacrime.audio.CrimeSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registered sound id with nothing behind it is a warning on every client start and silence in play.
 *
 * <p>The seven ids in {@link CrimeSoundEvents} were registered before their audio existed. Without an
 * {@code assets/mcacrime/sounds.json} entry the client logs {@code Missing sound for event:
 * mcacrime:...} for each one at startup and the event plays nothing at all — the exact silent failure
 * {@code CrimeSoundEvents}' own javadoc warns about. §3.11's policy is vanilla audio only, so each id
 * is defined as a single {@code "type": "event"} alias onto a vanilla sound that fits the moment; a
 * resource pack can still replace the id, which is why the ids exist at all.
 *
 * <p>This test is the tripwire for the three ways that arrangement can rot: an eighth id registered
 * with no entry, an entry aliasing a vanilla event that does not exist, and a subtitle key that never
 * reached the language file.
 *
 * <p>The vanilla ids are checked against {@link #VANILLA_SOUND_EVENTS} rather than the game's assets:
 * {@code assets/minecraft/sounds.json} is not in the Minecraft jar in 1.21.1 (it is an asset-index
 * object, fetched separately), so there is nothing on the test classpath to read. Each id below was
 * verified present in the 1.21.1 asset index before being listed, and the registry is no substitute —
 * vanilla itself registers sound events with no audio behind them.
 */
class SoundResourceCoverageTest {

    private static final Path ASSETS = TestPaths.resources("assets", "mcacrime");

    /**
     * Vanilla 1.21.1 sound events this mod is allowed to alias, each confirmed present in vanilla's
     * own {@code sounds.json} (asset index 17) with real audio behind it.
     */
    private static final Set<String> VANILLA_SOUND_EVENTS = Set.of(
            "minecraft:block.chain.place",
            "minecraft:block.iron_trapdoor.close",
            "minecraft:block.wooden_trapdoor.close",
            "minecraft:block.bell.resonate",
            "minecraft:block.iron_door.open",
            "minecraft:block.iron_door.close");

    @Test
    void everyRegisteredSoundEventHasAnEntryThatAliasesAVanillaSound() {
        JsonObject sounds = json(ASSETS.resolve("sounds.json"));
        JsonObject lang = json(ASSETS.resolve("lang/en_us.json"));
        List<String> problems = new ArrayList<>();

        Set<String> registered = registeredPaths();
        assertFalse(registered.isEmpty(),
                "no sound ids were read from CrimeSoundEvents; the reflection below has gone stale");

        for (String path : registered) {
            JsonElement entry = sounds.get(path);
            if (entry == null || !entry.isJsonObject()) {
                problems.add("mcacrime:" + path + " is registered but has no sounds.json entry");
                continue;
            }
            JsonObject definition = entry.getAsJsonObject();

            String subtitle = definition.has("subtitle")
                    ? definition.get("subtitle").getAsString()
                    : null;
            if (subtitle == null) {
                problems.add(path + " has no subtitle");
            } else {
                if (!subtitle.equals("subtitles.mcacrime." + path)) {
                    problems.add(path + " names subtitle " + subtitle
                            + ", expected subtitles.mcacrime." + path);
                }
                if (!lang.has(subtitle)) {
                    problems.add(subtitle + " is referenced by sounds.json but missing from en_us.json");
                }
            }

            JsonArray entries = definition.has("sounds")
                    ? definition.getAsJsonArray("sounds")
                    : new JsonArray();
            if (entries.isEmpty()) {
                problems.add(path + " defines no sounds, so it would still play nothing");
                continue;
            }
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) {
                    problems.add(path + " has a bare file reference; §3.11 ships no audio of its own");
                    continue;
                }
                JsonObject sound = element.getAsJsonObject();
                String type = sound.has("type") ? sound.get("type").getAsString() : "sound";
                if (!"event".equals(type)) {
                    problems.add(path + " uses type " + type + "; only an event alias is allowed");
                    continue;
                }
                String name = sound.has("name") ? sound.get("name").getAsString() : "";
                if (!VANILLA_SOUND_EVENTS.contains(name)) {
                    problems.add(path + " aliases " + name
                            + ", which is not an allow-listed vanilla 1.21.1 sound event");
                }
            }
        }

        for (String key : sounds.keySet()) {
            if (!registered.contains(key)) {
                problems.add("sounds.json defines " + key + ", which no SoundEvent registers");
            }
        }

        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    /** The registered sound paths, read off {@link CrimeSoundEvents}' own holders. */
    private static Set<String> registeredPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Field field : CrimeSoundEvents.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !DeferredHolder.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                DeferredHolder<?, ?> holder = (DeferredHolder<?, ?>) field.get(null);
                ResourceLocation id = holder.getId();
                assertEquals("mcacrime", id.getNamespace(),
                        field.getName() + " is registered outside this mod's namespace");
                paths.add(id.getPath());
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not read " + field.getName(), e);
            }
        }
        return paths;
    }

    private static JsonObject json(Path path) {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }
}
