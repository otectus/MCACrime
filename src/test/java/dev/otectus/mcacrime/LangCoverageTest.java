package dev.otectus.mcacrime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcacrime.action.ActionCategory;
import dev.otectus.mcacrime.action.ActionDuration;
import dev.otectus.mcacrime.action.ActionLegality;
import dev.otectus.mcacrime.action.ActionRequirement;
import dev.otectus.mcacrime.action.CancelReason;
import dev.otectus.mcacrime.client.hud.HudAnchor;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.memory.ObserverRole;
import dev.otectus.mcacrime.memory.ReportState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A standing tripwire against the quietest bug this mod can ship: a message the player sees as a raw
 * translation key.
 *
 * <p>It failed on eleven keys when it was written. Nine of them were the {@code action.cancel.*}
 * family, built by string concatenation in {@code ActionSessionManager.cancel} — exactly the shape a
 * plain "grep for {@code Component.translatable}" check misses, which is why the concatenated families
 * are enumerated here from their own enums rather than scanned for.
 *
 * <p>The scan is deliberately literal-only. A key assembled from a runtime value cannot be verified
 * statically, so every such family must be listed below; if you add one and forget, this test cannot
 * help you, but {@link #everyConcatenatedFamilyIsEnumerated()} at least records which ones exist.
 */
class LangCoverageTest {

    private static final Path SOURCE_ROOT = TestPaths.sources();
    private static final Path LANG = TestPaths.resources("assets", "mcacrime", "lang", "en_us.json");

    /**
     * {@code Component.translatable("...")} where the key is a <em>complete</em> literal.
     *
     * <p>The trailing {@code [,)]} is load-bearing. Without it this also matches the literal prefix in
     * {@code Component.translatable("mcacrime.band." + band.lower())} and reports that prefix as a
     * missing key — it is not one. Those concatenated families are covered instead by
     * {@link #everyConcatenatedFamilyIsEnumerated()}, which expands them from their own enums.
     */
    private static final Pattern TRANSLATABLE = Pattern.compile(
            "Component\\.translatable\\(\\s*\"((?:mcacrime|gui\\.mcacrime|item\\.mcacrime"
                    + "|crime\\.mcacrime|dialogue\\.mcacrime|key\\.mcacrime)\\.[^\"]+)\"\\s*[,)]");

    private static JsonObject lang() {
        try {
            return JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + LANG.toAbsolutePath(), e);
        }
    }

    private static List<Path> sources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + SOURCE_ROOT.toAbsolutePath(), e);
        }
    }

    @Test
    void everyLiteralTranslationKeyExists() {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();
        for (Path source : sources()) {
            String body;
            try {
                body = Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + source, e);
            }
            Matcher matcher = TRANSLATABLE.matcher(body);
            while (matcher.find()) {
                String key = matcher.group(1);
                if (!lang.has(key)) missing.add(key + "  (" + SOURCE_ROOT.relativize(source) + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "These translation keys are used in code but absent from en_us.json, so a player sees "
                        + "the raw key:\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyConcatenatedFamilyIsEnumerated() {
        JsonObject lang = lang();
        Set<String> missing = new TreeSet<>();

        // ActionSessionManager.cancel builds "mcacrime.action.cancel." + reason.name().toLowerCase().
        for (CancelReason reason : CancelReason.values()) {
            require(lang, missing, "mcacrime.action.cancel." + reason.name().toLowerCase(Locale.ROOT));
        }
        // PlayerCardPanel and ChatNameColor build band keys from Band.
        for (Band band : Band.values()) {
            require(lang, missing, "mcacrime.band." + band.name().toLowerCase(Locale.ROOT));
            require(lang, missing, "mcacrime.msg.band." + band.name().toLowerCase(Locale.ROOT));
        }
        // The action screen builds its marker keys from the presentation enums.
        for (ActionCategory category : ActionCategory.values()) require(lang, missing, category.labelKey());
        for (ActionLegality legality : ActionLegality.values()) require(lang, missing, legality.labelKey());
        for (ActionDuration duration : ActionDuration.values()) require(lang, missing, duration.labelKey());
        for (ActionRequirement requirement : ActionRequirement.values()) require(lang, missing, requirement.labelKey());

        // CaseDossierScreen builds "gui.mcacrime.resolution." + resolution.name().toLowerCase().
        for (Resolution resolution : Resolution.values()) {
            require(lang, missing, "gui.mcacrime.resolution." + resolution.name().toLowerCase(Locale.ROOT));
        }
        // HudAnchor.labelKey() is concatenated the same way, and the config screen shows every value.
        for (HudAnchor anchor : HudAnchor.values()) require(lang, missing, anchor.labelKey());

        // GuardChallengeService.sendCharges and CaseDossierScreen build "crime.<namespace>.<path>".
        // This family is the reason the test is being widened: assault_player and murder_player shipped
        // with a JSON definition, a registration, and no display name, and both the literal scan and
        // this list walked straight past them -- the scan because the prefix was not covered, this list
        // because CrimeIds was not enumerated. Read reflectively, so adding a twelfth crime id cannot
        // quietly skip the check the way the eleventh did.
        for (ResourceLocation id : declaredCrimeIds()) {
            require(lang, missing, "crime." + id.getNamespace() + "." + id.getPath());
        }
        // ChallengeResponse.labelKey() names the four buttons on the guard challenge panel.
        for (ChallengeResponse response : ChallengeResponse.values()) {
            require(lang, missing, response.labelKey());
        }
        // The reaction, observation and report state machines each render a state by name.
        for (VictimReactionState state : VictimReactionState.values()) {
            require(lang, missing, "mcacrime.reaction." + state.name().toLowerCase(Locale.ROOT));
        }
        for (ObserverRole role : ObserverRole.values()) {
            require(lang, missing, "mcacrime.observation.role." + role.name().toLowerCase(Locale.ROOT));
        }
        for (ReportState state : ReportState.values()) {
            require(lang, missing, "mcacrime.report.state." + state.name().toLowerCase(Locale.ROOT));
        }

        assertTrue(missing.isEmpty(),
                "These translation keys are built by concatenation and have no entry in en_us.json:\n  "
                        + String.join("\n  ", missing));
    }

    /** Every {@code ResourceLocation} constant declared on {@link CrimeIds}, read reflectively. */
    private static List<ResourceLocation> declaredCrimeIds() {
        List<ResourceLocation> out = new ArrayList<>();
        for (Field field : CrimeIds.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ResourceLocation.class) {
                try {
                    out.add((ResourceLocation) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("CrimeIds." + field.getName() + " is not readable", e);
                }
            }
        }
        return out;
    }

    @Test
    void everyRegisteredActionHasALabelAndDescription() {
        JsonObject lang = lang();
        dev.otectus.mcacrime.action.CrimeActionService.bootstrap();
        Set<String> missing = new TreeSet<>();
        for (java.lang.reflect.Field field : dev.otectus.mcacrime.action.CrimeActionIds.class.getDeclaredFields()) {
            if (field.getType() != net.minecraft.resources.ResourceLocation.class) continue;
            net.minecraft.resources.ResourceLocation id;
            try {
                id = (net.minecraft.resources.ResourceLocation) field.get(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            var handler = dev.otectus.mcacrime.action.ActionHandlerRegistry.get(id);
            if (handler == null) continue;
            require(lang, missing, handler.descriptor().labelKey());
            require(lang, missing, handler.descriptor().descriptionKey());
        }
        assertTrue(missing.isEmpty(),
                "Every action on the menu needs a name and a one-line explanation:\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    void languageFileHasNoBlankValues() {
        JsonObject lang = lang();
        List<String> blank = new ArrayList<>();
        for (String key : lang.keySet()) {
            if (lang.get(key).getAsString().isBlank()) blank.add(key);
        }
        assertTrue(blank.isEmpty(), "A blank translation renders as nothing at all: " + blank);
    }

    private static void require(JsonObject lang, Set<String> missing, String key) {
        if (!lang.has(key)) missing.add(key);
    }
}
