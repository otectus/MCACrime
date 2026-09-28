package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.dialogue.DialogueContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The add-on dialogue resolver chain must never change a line unless a resolver opts in, and a
 * misbehaving resolver must never silence a guard: null and thrown both hand over to the next resolver
 * and finally to the datapack line. No entity is needed; {@code resolve} never dereferences them.
 */
class CrimeDialogueHooksTest {

    private static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath("mcacrime", "guard_challenge");
    private static final DialogueContext CONTEXT = DialogueContext.builder(1L).build();

    @AfterEach
    void clear() {
        CrimeDialogueHooks.clear();
    }

    @Test
    void returnsFallbackWhenNothingIsRegistered() {
        Component fallback = Component.literal("static");
        assertSame(fallback, CrimeDialogueHooks.resolve(null, null, EVENT, CONTEXT, fallback));
    }

    @Test
    void firstNonNullLineWinsInRegistrationOrder() {
        CrimeDialogueHooks.addResolver("examplemod:quiet", (speaker, listener, event, context, fb) -> null);
        CrimeDialogueHooks.addResolver("mcaconversations", (speaker, listener, event, context, fb) -> Component.literal("voiced"));
        CrimeDialogueHooks.addResolver("examplemod:late", (speaker, listener, event, context, fb) -> Component.literal("late"));
        assertEquals(Component.literal("voiced"),
                CrimeDialogueHooks.resolve(null, null, EVENT, CONTEXT, Component.literal("static")));
        assertEquals(List.of("examplemod:quiet", "mcaconversations", "examplemod:late"), CrimeDialogueHooks.resolverIds());
    }

    @Test
    void aThrowingResolverHandsOverAndNeverSilencesTheLine() {
        CrimeDialogueHooks.addResolver("examplemod:broken", (speaker, listener, event, context, fb) -> {
            throw new IllegalStateException("boom");
        });
        Component fallback = Component.literal("static");
        assertSame(fallback, CrimeDialogueHooks.resolve(null, null, EVENT, CONTEXT, fallback));
        CrimeDialogueHooks.addResolver("mcaconversations", (speaker, listener, event, context, fb) -> Component.literal("voiced"));
        assertEquals(Component.literal("voiced"), CrimeDialogueHooks.resolve(null, null, EVENT, CONTEXT, fallback));
    }

    @Test
    void reRegistrationReplacesInPlaceAndRemovalIsIdempotent() {
        CrimeDialogueHooks.addResolver("mcaconversations", (speaker, listener, event, context, fb) -> Component.literal("one"));
        CrimeDialogueHooks.addResolver("mcaconversations", (speaker, listener, event, context, fb) -> Component.literal("two"));
        assertEquals(Component.literal("two"),
                CrimeDialogueHooks.resolve(null, null, EVENT, CONTEXT, Component.literal("static")));
        CrimeDialogueHooks.removeResolver("mcaconversations");
        CrimeDialogueHooks.removeResolver("mcaconversations");
        assertEquals(List.of(), CrimeDialogueHooks.resolverIds());
    }
}
