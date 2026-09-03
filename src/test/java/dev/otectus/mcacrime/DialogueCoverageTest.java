package dev.otectus.mcacrime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.dialogue.CrimeDialogueDefinition;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped dialogue actually covers what the code fires, and every line it names exists.
 *
 * <p>{@code LangCoverageTest} cannot see any of this: dialogue keys live in JSON rather than in
 * {@code Component.translatable} calls, so a datapack file naming a key nobody ever added to
 * {@code en_us.json} would ship silently and show the player a raw key at exactly the dramatic moment
 * the line was written for.
 *
 * <p>Spec §16.4 sets the required matrix — an opening, a refusal, both interruptions, a success, a
 * partial, a resistance, a witness line, a repeat, and a recovery for every shipped action. The
 * matrix is expressed as {@link DialogueEvents#ALL}, so this test fails the build the moment an id is
 * added to that list without content behind it.
 */
class DialogueCoverageTest {

    private static final Path DIALOGUE = Path.of("src", "main", "resources", "data", "mcacrime",
            "mcacrime", "dialogue");
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "mcacrime",
            "lang", "en_us.json");

    private static JsonObject lang() {
        try {
            return JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + LANG.toAbsolutePath(), e);
        }
    }

    /** Every shipped definition, keyed by its declared event id. */
    private static Map<ResourceLocation, CrimeDialogueDefinition> shipped() {
        Map<ResourceLocation, CrimeDialogueDefinition> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(DIALOGUE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject json = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath("mcacrime",
                        file.getFileName().toString().replace(".json", ""));
                CrimeDialogueDefinition definition = CrimeDialogueDefinition.parse(fileId, json);
                loaded.put(definition.event(), definition);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DIALOGUE.toAbsolutePath(), e);
        }
        return loaded;
    }

    @Test
    void everyFiredEventHasShippedDialogue() {
        Map<ResourceLocation, CrimeDialogueDefinition> shipped = shipped();
        Set<String> missing = new TreeSet<>();
        for (ResourceLocation event : DialogueEvents.ALL) {
            if (!shipped.containsKey(event)) {
                missing.add(event.toString());
            }
        }
        assertTrue(missing.isEmpty(),
                "These dialogue events are fired by code with no shipped definition, so every one of them "
                        + "falls back to a generic line:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyShippedLineKeyExistsInTheLanguageFile() {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (CrimeDialogueDefinition definition : shipped().values()) {
            if (!lang.has(definition.fallback())) {
                missing.add(definition.fallback() + "  (fallback of " + definition.event() + ")");
            }
            for (CrimeDialogueDefinition.Variant variant : definition.variants()) {
                for (String line : variant.lines()) {
                    if (!lang.has(line)) {
                        missing.add(line + "  (" + definition.event() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "These dialogue lines are selectable but have no translation, so a villager says the raw "
                        + "key:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyEventAlsoHasTheServicesLastResortLine() {
        // CrimeDialogueService falls back to dialogue.mcacrime.<path>.generic when no definition loaded
        // at all -- a pack that replaces the directory, or a failed parse. That path must be renderable
        // too, or removing a file turns a villager mute in a way no test would otherwise catch.
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (ResourceLocation event : DialogueEvents.ALL) {
            String key = "dialogue.mcacrime." + event.getPath() + ".generic";
            if (!lang.has(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(),
                "Missing last-resort dialogue lines:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyShippedFileDeclaresAGenericVariant() {
        // A pool whose every variant is conditional can select nothing but the fallback, which works but
        // means the file is one condition away from never being used. Requiring an unconditional variant
        // keeps the shipped content honest about what it actually covers.
        List<String> conditionalOnly = new ArrayList<>();
        for (CrimeDialogueDefinition definition : shipped().values()) {
            boolean hasUnconditional = definition.variants().stream()
                    .anyMatch(variant -> variant.when().isEmpty());
            if (!hasUnconditional) {
                conditionalOnly.add(definition.event().toString());
            }
        }
        assertTrue(conditionalOnly.isEmpty(),
                "Every dialogue file needs one unconditional variant: " + conditionalOnly);
    }

    @Test
    void noShippedFileDeclaresAnEmptyVariantList() {
        // Guards the generator as much as the content: a file with "variants": [] parses fine and is
        // indistinguishable from a fallback-only file until someone wonders why a pool never varies.
        List<String> empty = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIALOGUE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject json = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray variants = json.getAsJsonArray("variants");
                if (variants == null || variants.isEmpty()) {
                    empty.add(file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DIALOGUE.toAbsolutePath(), e);
        }
        assertTrue(empty.isEmpty(), "Dialogue files with no variants at all: " + empty);
    }
}
