package dev.otectus.mcacrime.action;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * One registered action. Handlers are the code allowlist behind {@link ActionHandlerRegistry}: a
 * datapack may retune a crime and may author dialogue for an action, but it can never introduce one,
 * because an action with no handler has no validation, no finite cost policy and no repeat policy.
 *
 * <p>The actor is a {@link CrimeActor} rather than a {@code ServerPlayer} so that the same handler can
 * one day run for an NPC offender (spec §10.5). A handler that genuinely needs player internals calls
 * {@link CrimeActor#asPlayer()} and copes with {@code null}.
 */
public interface CrimeActionHandler {

    /** Static presentation metadata for the action screen. Never consulted for validation. */
    ActionDescriptor descriptor();

    /**
     * Whether this actor may perform this action against this target right now, and why not if not.
     * Called once per menu build and again on click — a row drawn as available is never trusted.
     */
    ActionAvailability evaluate(CrimeActor actor, LivingEntity target, ServerLevel level, long now);

    /** Begins the action. Implementations must re-run {@link #evaluate} rather than trusting the caller. */
    ActionResult start(CrimeActor actor, LivingEntity target, ServerLevel level, UUID nonce);

    /** Advances a channelled action. Only called for actions that opened an {@link ActionSession}. */
    void tick(ActionSession session, CrimeActor actor, LivingEntity target, ServerLevel level);

    /** Cleanup when a session ends early. Consequences stamped at the point of no return must stand. */
    default void cancel(ActionSession session, CancelReason reason) {}
}
