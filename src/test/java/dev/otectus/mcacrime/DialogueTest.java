package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.dialogue.CrimeDialogueDefinition;
import dev.otectus.mcacrime.dialogue.DialogueContext;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialogue selection (spec §16.1–§16.3).
 *
 * <p>Three properties matter more than the wording: a variant is chosen by conditions and never by
 * matching text, the same encounter always says the same line, and nothing is ever selected into an
 * empty string. The last is the one that shows up as a bug report — a villager who opens their mouth
 * and says nothing reads as a crash even when the rest of the interaction worked.
 */
class DialogueTest {

    private static final ResourceLocation FILE = new ResourceLocation("mcacrime", "mug_opening");

    private static CrimeDialogueDefinition parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return CrimeDialogueDefinition.parse(FILE, object);
    }

    private static final String SAMPLE = """
            {
              "event": "mcacrime:mug_opening",
              "fallback": "dialogue.mcacrime.mug.opening.generic",
              "variants": [
                {
                  "priority": 100,
                  "when": { "personality": "#mcacrime:bold", "supportNearby": "true" },
                  "lines": ["bold.1", "bold.2"]
                },
                {
                  "priority": 80,
                  "when": { "relationship": "family" },
                  "lines": ["family.1"]
                },
                { "priority": 0, "lines": ["dialogue.mcacrime.mug.opening.generic"] }
              ]
            }
            """;

    private static DialogueContext context(long seed, String... pairs) {
        DialogueContext.Builder builder = DialogueContext.builder(seed);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            builder.put(pairs[i], pairs[i + 1]);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void theSpecsOwnExampleParses() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        assertEquals(new ResourceLocation("mcacrime", "mug_opening"), definition.event());
        assertEquals(3, definition.variants().size());
    }

    @Test
    void variantsAreSortedByDescendingPriorityWhateverOrderTheyWereWrittenIn() {
        CrimeDialogueDefinition definition = parse("""
                {
                  "event": "mcacrime:test",
                  "fallback": "generic",
                  "variants": [
                    { "priority": 1, "lines": ["low"] },
                    { "priority": 99, "lines": ["high"] }
                  ]
                }
                """);
        assertEquals(99, definition.variants().get(0).priority());
    }

    @Test
    void aVariantWithNoLinesIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                {
                  "event": "mcacrime:test",
                  "fallback": "generic",
                  "variants": [ { "priority": 1, "lines": [] } ]
                }
                """));
    }

    @Test
    void aFileWithNoFallbackIsAnError() {
        // Without a fallback there is a context that selects nothing, and "nothing" is not a line.
        assertThrows(IllegalArgumentException.class, () -> parse("""
                { "event": "mcacrime:test", "variants": [ { "lines": ["a"] } ] }
                """));
    }

    // ------------------------------------------------------------------ selection

    @Test
    void theHighestPriorityMatchWins() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        String line = definition.select(context(0L,
                "personality", "bold", "supportNearby", "true", "relationship", "family"));
        assertTrue(line.startsWith("bold."), "a bold villager with backup should not use the family line");
    }

    @Test
    void aPartiallyMatchingVariantDoesNotMatch() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        // Bold, but alone: every condition in a variant has to hold, not just one.
        assertEquals("dialogue.mcacrime.mug.opening.generic",
                definition.select(context(0L, "personality", "bold")));
    }

    @Test
    void anUnknownFactNeverMatchesAndNeverThrows() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        assertEquals("dialogue.mcacrime.mug.opening.generic",
                definition.select(context(0L, "somethingElse", "yes")));
    }

    @Test
    void theTagFormFromTheSpecAndTheBareValueMeanTheSameThing() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        // The file writes "#mcacrime:bold"; the runtime fact is "bold". Accepting only one of them
        // would make the specification's own example JSON fail to select.
        assertTrue(definition.select(context(0L, "personality", "bold", "supportNearby", "true"))
                .startsWith("bold."));
        assertTrue(definition.select(context(0L, "personality", "#mcacrime:bold", "supportNearby", "true"))
                .startsWith("bold."));
    }

    @Test
    void alternativesSeparatedByPipeBothMatch() {
        CrimeDialogueDefinition definition = parse("""
                {
                  "event": "mcacrime:test",
                  "fallback": "generic",
                  "variants": [ { "priority": 10, "when": { "band": "red|grey" }, "lines": ["either"] } ]
                }
                """);
        assertEquals("either", definition.select(context(0L, "band", "red")));
        assertEquals("either", definition.select(context(0L, "band", "grey")));
        assertEquals("generic", definition.select(context(0L, "band", "blue")));
    }

    @Test
    void oneEncounterAlwaysGetsTheSameLine() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        DialogueContext first = context(12345L, "personality", "bold", "supportNearby", "true");
        DialogueContext again = context(12345L, "personality", "bold", "supportNearby", "true");
        assertEquals(definition.select(first), definition.select(again),
                "reopening a conversation must not reroll until the player likes the line");
    }

    @Test
    void differentEncountersCanGetDifferentLines() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        String a = definition.select(context(0L, "personality", "bold", "supportNearby", "true"));
        String b = definition.select(context(1L, "personality", "bold", "supportNearby", "true"));
        assertNotEquals(a, b, "a two-line pool that always says line one is not a pool");
    }

    @Test
    void selectionNeverReturnsNothing() {
        CrimeDialogueDefinition definition = parse(SAMPLE);
        for (long seed = -5; seed < 5; seed++) {
            String line = definition.select(context(seed));
            assertTrue(line != null && !line.isBlank());
        }
    }

    // ------------------------------------------------------------------ context

    @Test
    void bandsBucketIntoLowMidHigh() {
        DialogueContext context = DialogueContext.builder(0L)
                .putBand("bravery", 0.1F)
                .putBand("greed", 0.5F)
                .putBand("loyalty", 0.9F)
                .build();
        assertEquals("low", context.facts().get("bravery"));
        assertEquals("mid", context.facts().get("greed"));
        assertEquals("high", context.facts().get("loyalty"));
    }

    @Test
    void factsAreNormalisedOnTheWayIn() {
        DialogueContext context = DialogueContext.builder(0L).put("role", "MCA:Guard").build();
        assertEquals("guard", context.facts().get("role"));
    }
}
