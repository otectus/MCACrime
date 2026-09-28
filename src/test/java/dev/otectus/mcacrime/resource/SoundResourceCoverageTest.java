package dev.otectus.mcacrime.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registered sound id with no {@code sounds.json} entry behind it is a silent failure.
 *
 * <p>It shipped as one: all seven ids in {@code CrimeSoundEvents} were registered, no
 * {@code assets/mcacrime/sounds.json} existed, and every client start logged
 * "Missing sound for event: mcacrime:..." seven times while the ids themselves played nothing — the
 * exact outcome that class's javadoc says must not happen. This test is the tripwire for it.
 *
 * <p>The policy the entries follow is §3.11: <b>no {@code .ogg} file is carried over</b>, because the
 * upstream audio is unattributed. Each id is therefore defined as a single {@code "type": "event"}
 * redirect onto a <em>vanilla</em> sound event chosen to match the one {@code CrimeSounds} already
 * uses as that moment's fallback, so the id and the fallback sound identical and a later real file
 * changes the sound in one place. A resource pack can reach the id either way, which is why the ids
 * exist at all.
 *
 * <p>There is deliberately no "category" key: in 1.20.1 a {@code sounds.json} entry carries only
 * {@code sounds}, {@code subtitle} and {@code replace}. The mixer category is chosen where the sound
 * is played — {@code SoundSource.BLOCKS} for the device moments and {@code NEUTRAL} for the restraint
 * ones, in {@code CrimeSounds.playAt} and {@code CrimeSounds.play}.
 */
class SoundResourceCoverageTest {

    private static final Path SOUNDS =
            Path.of("src", "main", "resources", "assets", "mcacrime", "sounds.json");
    private static final Path LANG =
            Path.of("src", "main", "resources", "assets", "mcacrime", "lang", "en_us.json");
    private static final Path SOUND_EVENTS_SOURCE = Path.of("src", "main", "java", "dev", "otectus",
            "mcacrime", "audio", "CrimeSoundEvents.java");

    private static final Pattern REGISTERED = Pattern.compile("register\\(\"([^\"]+)\"\\)");

    /**
     * The vanilla 1.20.1 sound events this mod redirects onto, for environments where the mapped
     * client assets cannot be read.
     *
     * <p>Every id here was confirmed present in {@code assets/minecraft/sounds.json} of the 1.20.1
     * client asset index in the Gradle cache. {@link #vanillaEvents()} prefers that file when it is
     * reachable and falls back to this list when it is not, so the test never passes merely because
     * the cache is missing.
     */
    private static final Set<String> KNOWN_VANILLA_EVENTS = Set.of(
            "block.chain.place",
            "block.iron_trapdoor.close",
            "block.wooden_trapdoor.close",
            "block.bell.resonate",
            "block.iron_door.open",
            "block.iron_door.close");

    @Test
    void everyRegisteredSoundEventHasADefinition() {
        JsonObject definitions = json(SOUNDS);
        Set<String> missing = new TreeSet<>();
        for (String path : registeredPaths()) {
            if (!definitions.has(path)) {
                missing.add(path);
            }
        }
        assertTrue(missing.isEmpty(), "These sound events are registered by CrimeSoundEvents but have "
                + "no entry in assets/mcacrime/sounds.json, so the client logs \"Missing sound for "
                + "event\" and the id plays nothing:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyDefinitionBelongsToARegisteredSoundEvent() {
        Set<String> registered = registeredPaths();
        Set<String> orphans = new TreeSet<>();
        for (String key : json(SOUNDS).keySet()) {
            if (!registered.contains(key)) {
                orphans.add(key);
            }
        }
        assertTrue(orphans.isEmpty(), "These sounds.json entries name no registered sound event:\n  "
                + String.join("\n  ", orphans));
    }

    @Test
    void everyDefinitionRedirectsOntoAVanillaEventThatExists() {
        Set<String> vanilla = vanillaEvents();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json(SOUNDS).entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            JsonArray sounds = definition.getAsJsonArray("sounds");
            if (sounds == null || sounds.isEmpty()) {
                problems.add(entry.getKey() + " defines no sounds");
                continue;
            }
            for (JsonElement element : sounds) {
                if (!element.isJsonObject()) {
                    problems.add(entry.getKey() + " has a bare sound name; §3.11 ships no .ogg file, so "
                            + "each entry must be a \"type\": \"event\" redirect");
                    continue;
                }
                JsonObject sound = element.getAsJsonObject();
                String type = sound.has("type") ? sound.get("type").getAsString() : "file";
                String name = sound.has("name") ? sound.get("name").getAsString() : "";
                if (!"event".equals(type)) {
                    problems.add(entry.getKey() + " points at a file (" + name + "); this mod ships no "
                            + "audio files, so it must redirect onto a vanilla event");
                    continue;
                }
                String namespace = name.contains(":") ? name.substring(0, name.indexOf(':')) : "minecraft";
                String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                if (!"minecraft".equals(namespace)) {
                    problems.add(entry.getKey() + " redirects onto " + name + ", which is not a vanilla "
                            + "event");
                    continue;
                }
                if (!vanilla.contains(path)) {
                    problems.add(entry.getKey() + " redirects onto minecraft:" + path
                            + ", which is not a 1.20.1 vanilla sound event");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    @Test
    void everyDefinitionNamesASubtitleThatIsTranslated() {
        JsonObject lang = json(LANG);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : json(SOUNDS).entrySet()) {
            JsonObject definition = entry.getValue().getAsJsonObject();
            if (!definition.has("subtitle")) {
                problems.add(entry.getKey() + " has no subtitle, so players with subtitles on see "
                        + "nothing when it plays");
                continue;
            }
            String subtitle = definition.get("subtitle").getAsString();
            if (!subtitle.equals("subtitles.mcacrime." + entry.getKey())) {
                problems.add(entry.getKey() + " names the unexpected subtitle key " + subtitle);
            }
            if (!lang.has(subtitle)) {
                problems.add(subtitle + " is missing from en_us.json");
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    // --- sources ----------------------------------------------------------

    /**
     * The sound paths {@code CrimeSoundEvents} registers.
     *
     * <p>Read from the source rather than from the registry: {@code DeferredRegister.create} touches
     * {@code ForgeRegistries}, and every other test in this suite runs without bootstrapping the game.
     * The regex matches the single {@code register("...")} helper that class funnels all seven ids
     * through, so an eighth id added the same way is picked up here with no edit.
     */
    private static Set<String> registeredPaths() {
        String body = read(SOUND_EVENTS_SOURCE);
        Set<String> paths = new TreeSet<>();
        Matcher matcher = REGISTERED.matcher(body);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        assertTrue(paths.size() >= 7,
                "expected CrimeSoundEvents to still register its sound ids through register(\"...\"), "
                        + "found " + paths);
        return paths;
    }

    /** Vanilla 1.20.1 sound event ids, from the mapped client assets when reachable. */
    private static Set<String> vanillaEvents() {
        Set<String> fromCache = vanillaEventsFromGradleCache();
        return fromCache.isEmpty() ? KNOWN_VANILLA_EVENTS : fromCache;
    }

    /**
     * {@code assets/minecraft/sounds.json} out of the ForgeGradle asset cache, or empty.
     *
     * <p>The client's sounds file is not inside {@code client.jar}; it is a hashed object named by the
     * asset index, so this resolves the index entry and reads the object. Any failure at all is an
     * unreachable cache, not a test failure — the caller falls back to {@link #KNOWN_VANILLA_EVENTS}.
     */
    private static Set<String> vanillaEventsFromGradleCache() {
        Path indexes = Path.of(System.getProperty("user.home"), ".gradle", "caches", "forge_gradle",
                "assets", "indexes");
        Path objects = indexes.resolveSibling("objects");
        if (!Files.isDirectory(indexes) || !Files.isDirectory(objects)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(indexes)) {
            for (Path index : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject parsed;
                try {
                    parsed = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                } catch (RuntimeException | IOException unreadable) {
                    continue;
                }
                if (!parsed.has("objects")) {
                    continue;
                }
                JsonObject entries = parsed.getAsJsonObject("objects");
                if (!entries.has("minecraft/sounds.json")) {
                    continue;
                }
                String hash = entries.getAsJsonObject("minecraft/sounds.json").get("hash").getAsString();
                Path object = objects.resolve(hash.substring(0, 2)).resolve(hash);
                if (!Files.isRegularFile(object)) {
                    continue;
                }
                try {
                    return new TreeSet<>(JsonParser.parseString(
                            Files.readString(object, StandardCharsets.UTF_8)).getAsJsonObject().keySet());
                } catch (RuntimeException | IOException unreadable) {
                    continue;
                }
            }
        } catch (IOException unreachable) {
            return Set.of();
        }
        return Set.of();
    }

    private static JsonObject json(Path path) {
        return JsonParser.parseString(read(path)).getAsJsonObject();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }
}
