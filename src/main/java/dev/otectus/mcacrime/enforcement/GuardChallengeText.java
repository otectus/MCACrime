package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.justice.LegalDecision;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

/** Chat explanations must remain readable when the receiving client lacks a translation key. */
public final class GuardChallengeText {
    private GuardChallengeText() {}

    public static Component detentionReason(@Nullable LegalDecision decision) {
        String key = decision == null ? "mcacrime.challenge.no_charges" : decision.detentionReasonKey();
        String fallback = switch (key) {
            case "mcacrime.challenge.reason.wanted" ->
                    "You are Wanted because of your Heat. Surrender to the law; refusing will lead to pursuit and force.";
            case "mcacrime.challenge.reason.resisting" ->
                    "You are resisting arrest. Surrender to end the pursuit.";
            case "mcacrime.challenge.reason.escaped" ->
                    "You escaped custody. Surrender to resume your sentence.";
            case "mcacrime.challenge.reason.captive" ->
                    "You are holding someone unlawfully. The law demands your surrender.";
            case "mcacrime.challenge.no_charges" ->
                    "The guard checks, and finds nothing against you.";
            default -> "The law demands your surrender.";
        };
        // Keep the key for the client's chosen language/resource packs. Resolving getString() here
        // would instead freeze the server's language into chat; the fallback travels in the component.
        return Component.translatableWithFallback(key, fallback);
    }
}
