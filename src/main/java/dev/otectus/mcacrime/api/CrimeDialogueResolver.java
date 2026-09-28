package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.dialogue.DialogueContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * Optional hook an add-on registers through {@link CrimeDialogueHooks} to speak one of this mod's
 * dialogue events in its own voice — MCA: Conversations voicing a guard's challenge in the guard's
 * personality, say — instead of the datapack line {@code dialogue/CrimeDialogueService} would pick.
 *
 * <p>Called <b>server-side</b> when the line is about to be sent. The resolver receives the speaker
 * (nullable: a report filed with nobody present), the listener, the event id (one of the
 * {@code dialogue/DialogueEvents} constants), the selection context and the already-resolved fallback.
 * Return {@code null} to defer to the fallback, or to the next resolver.
 *
 * @since MCA: Crime 0.7.5
 */
@FunctionalInterface
public interface CrimeDialogueResolver {

    /**
     * @return a voiced line for this event, or {@code null} to use {@code fallback} (or a later resolver)
     */
    @Nullable
    Component resolve(@Nullable LivingEntity speaker, ServerPlayer listener, ResourceLocation event,
                      DialogueContext context, Component fallback);
}
