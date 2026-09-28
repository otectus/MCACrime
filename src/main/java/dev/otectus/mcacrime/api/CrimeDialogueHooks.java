package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.dialogue.DialogueContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guarded holder for the optional {@link CrimeDialogueResolver}s (0.7.5).
 *
 * <p>Modelled on MCA: Quests' {@code QuestDialogueHooks}: an add-on registers its resolver during its
 * own setup with {@link #addResolver}; {@code dialogue/CrimeDialogueService} calls {@link #resolve} for
 * every line it is about to say. With nothing registered (the default) the datapack line is used
 * unchanged — the degrade-to-static guarantee — and a resolver that throws or returns {@code null}
 * hands over to the next one and finally to that line, so a misbehaving add-on can never silence a
 * guard. Resolvers are an ordered chain keyed by the add-on's id, consulted in registration order.
 *
 * @since MCA: Crime 0.7.5
 */
public final class CrimeDialogueHooks {

    private static final Map<String, CrimeDialogueResolver> RESOLVERS = new LinkedHashMap<>();
    private static volatile List<CrimeDialogueResolver> chain = List.of();

    private CrimeDialogueHooks() {
    }

    /**
     * Adds, or replaces, the resolver registered under {@code id} (an add-on's mod id, or
     * {@code modid:purpose} when it has several). Registration order is consultation order; replacing
     * keeps the original position.
     */
    public static synchronized void addResolver(String id, CrimeDialogueResolver resolver) {
        if (id == null || id.isBlank() || resolver == null) {
            throw new IllegalArgumentException("a dialogue resolver needs a non-blank id and a resolver");
        }
        RESOLVERS.put(id, resolver);
        chain = List.copyOf(RESOLVERS.values());
    }

    /** Withdraws the resolver registered under {@code id}. Idempotent. */
    public static synchronized void removeResolver(String id) {
        if (id != null && RESOLVERS.remove(id) != null) {
            chain = List.copyOf(RESOLVERS.values());
        }
    }

    /** The registered resolver ids in consultation order, for {@code /crime debug integrations}. */
    public static synchronized List<String> resolverIds() {
        return List.copyOf(RESOLVERS.keySet());
    }

    /**
     * The first resolver's voiced line for this event, or {@code fallback} when none is registered,
     * every resolver returns {@code null}, or they all throw.
     */
    public static Component resolve(@Nullable LivingEntity speaker, ServerPlayer listener, ResourceLocation event,
                                    DialogueContext context, Component fallback) {
        List<CrimeDialogueResolver> active = chain;
        for (CrimeDialogueResolver resolver : active) {
            try {
                Component voiced = resolver.resolve(speaker, listener, event, context, fallback);
                if (voiced != null) {
                    return voiced;
                }
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA: Crime - a dialogue resolver threw for {}; asking the next one", event, t);
            }
        }
        return fallback;
    }

    /** Drops every resolver. Test hook; add-ons re-register at their next setup. */
    public static synchronized void clear() {
        RESOLVERS.clear();
        chain = List.of();
    }
}
