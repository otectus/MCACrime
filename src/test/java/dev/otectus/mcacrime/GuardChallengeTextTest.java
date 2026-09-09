package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.enforcement.GuardChallengeText;
import dev.otectus.mcacrime.justice.LegalDecision;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuardChallengeTextTest {
    private Language original;

    @BeforeEach void saveLanguage() { original = Language.getInstance(); }
    @AfterEach void restoreLanguage() { Language.inject(original); }

    private static LegalDecision decision(LegalDecision.Basis... basis) {
        return new LegalDecision(UUID.randomUUID(), null, List.of(), Set.of(basis));
    }

    private static void language(Map<String, String> translations) {
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return translations.getOrDefault(key, fallback);
            }
            @Override public boolean has(String key) { return translations.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return FormattedCharSequence.EMPTY;
            }
        });
    }

    private static JsonObject english() throws Exception {
        try (var stream = GuardChallengeTextTest.class.getResourceAsStream("/assets/mcacrime/lang/en_us.json")) {
            assertNotNull(stream, "The packaged English translations must exist");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void missingClientTranslationReproducesTheReportedKeyAndNowShowsTheExplanation() {
        language(Map.of());
        var wanted = decision(LegalDecision.Basis.WANTED);
        assertEquals("mcacrime.challenge.reason.wanted", Component.translatable(wanted.detentionReasonKey()).getString(),
                "The old message reproduces the screenshot when the client lacks the key");
        assertEquals("You are Wanted because of your Heat. Surrender to the law; refusing will lead to pursuit and force.",
                GuardChallengeText.detentionReason(wanted).getString());
    }

    @Test void everyDetentionBasisKeepsItsKeyAndMatchesTheShippedEnglishFallback() throws Exception {
        language(Map.of());
        var english = english();
        for (var basis : LegalDecision.Basis.values()) {
            var decision = decision(basis);
            var message = GuardChallengeText.detentionReason(decision);
            var contents = assertInstanceOf(TranslatableContents.class, message.getContents());
            assertEquals(decision.detentionReasonKey(), contents.getKey());
            assertTrue(english.has(contents.getKey()), "Missing dynamically selected detention translation");
            assertEquals(english.get(contents.getKey()).getAsString(), contents.getFallback());
            assertEquals(contents.getFallback(), message.getString());
        }
    }

    @Test void emptyBasisAndMissingOfferAlsoHaveReadableText() {
        language(Map.of());
        assertEquals("The guard checks, and finds nothing against you.",
                GuardChallengeText.detentionReason(null).getString());
        assertEquals(GuardChallengeText.detentionReason(null).getString(),
                GuardChallengeText.detentionReason(decision()).getString());
    }

    @Test void clientTranslationAndResourceReloadStillOverrideTheFallback() {
        var message = GuardChallengeText.detentionReason(decision(LegalDecision.Basis.WANTED));
        language(Map.of("mcacrime.challenge.reason.wanted", "Custom localized detention explanation"));
        assertEquals("Custom localized detention explanation", message.getString());
        language(Map.of());
        assertTrue(message.getString().startsWith("You are Wanted because of your Heat."));
        language(Map.of("mcacrime.challenge.reason.wanted", "Updated translation"));
        assertEquals("Updated translation", message.getString());
    }

    @Test void overlappingReasonsRetainTheLegalDecisionPriority() {
        language(Map.of());
        var all = decision(LegalDecision.Basis.WANTED, LegalDecision.Basis.ESCAPED_PRISONER,
                LegalDecision.Basis.HOLDING_CAPTIVE, LegalDecision.Basis.RESISTING_ARREST);
        assertEquals("You are resisting arrest. Surrender to end the pursuit.",
                GuardChallengeText.detentionReason(all).getString());
    }

    @Test void serializedChatPreservesBothTheTranslationKeyAndFallback() {
        language(Map.of());
        var message = GuardChallengeText.detentionReason(decision(LegalDecision.Basis.WANTED));
        String encoded = Component.Serializer.toJson(message);
        var json = JsonParser.parseString(encoded).getAsJsonObject();
        assertEquals("mcacrime.challenge.reason.wanted", json.get("translate").getAsString());
        assertEquals(message.getString(), json.get("fallback").getAsString());
        var received = Component.Serializer.fromJson(encoded);
        assertNotNull(received);
        assertEquals(message.getString(), received.getString());
        language(Map.of("mcacrime.challenge.reason.wanted", "Client-selected language"));
        assertEquals("Client-selected language", received.getString());
    }
}
