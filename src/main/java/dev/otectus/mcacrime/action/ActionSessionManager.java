package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Enforces one action per actor, one hostile lock per target, and in-memory nonce replay protection. */
public final class ActionSessionManager {
    private static final int MAX_RESULTS_PER_ACTOR = 256;
    private static final int MAX_NONCES_PER_ACTOR = 256;
    private static final Map<UUID, ActionSession> BY_ACTOR = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> TARGET_LOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, ActionResult>> RESULTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Long>> NONCE_PAYLOADS = new ConcurrentHashMap<>();
    private static final List<SessionEndListener> END_LISTENERS = new CopyOnWriteArrayList<>();

    private ActionSessionManager() {}

    /**
     * Notified once for every session that ends, however it ended.
     *
     * <p>{@code reason} is null when the session ran to completion and non-null when it was cancelled.
     * The reaction layer is the caller that needs this: a villager frozen by a mugging has to know
     * whether the mugging finished (recover) or was broken off (run, or go find a guard), and there is
     * no other moment at which that difference is visible.
     */
    public interface SessionEndListener {
        void onEnded(ActionSession session, @Nullable CancelReason reason);
    }

    /** Registers an end listener. Listeners are process-wide and never removed. */
    public static void addEndListener(SessionEndListener listener) {
        if (listener != null) END_LISTENERS.add(listener);
    }

    /**
     * The live coercive session naming this entity as its target, if any.
     *
     * <p>Target-keyed rather than actor-keyed on purpose: the question a threatened villager asks is
     * "is somebody robbing <em>me</em> right now", and {@code TARGET_LOCKS} already guarantees there is
     * at most one answer.
     */
    public static Optional<ActionSession> activeCoerciveAgainst(UUID targetId) {
        if (targetId == null) return Optional.empty();
        UUID actor = TARGET_LOCKS.get(targetId);
        if (actor == null) return Optional.empty();
        ActionSession session = BY_ACTOR.get(actor);
        if (session == null || !targetId.equals(session.targetId())) return Optional.empty();
        CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
        return handler != null && handler.coercive() ? Optional.of(session) : Optional.empty();
    }

    public static synchronized boolean begin(ActionSession session) {
        if (BY_ACTOR.containsKey(session.actorId()) || TARGET_LOCKS.containsKey(session.targetId())) {
            return false;
        }
        BY_ACTOR.put(session.actorId(), session);
        TARGET_LOCKS.put(session.targetId(), session.actorId());
        ActionFeedback.started(session);
        return true;
    }

    public static Optional<ActionSession> forActor(UUID actor) { return Optional.ofNullable(BY_ACTOR.get(actor)); }
    public static boolean targetLocked(UUID target) { return TARGET_LOCKS.containsKey(target); }
    public static ArrayList<ActionSession> active() { return new ArrayList<>(BY_ACTOR.values()); }

    /**
     * Ends a session exactly once.
     *
     * @return whether this call was the one that ended it. A second terminal call — the ticker
     *         completing an action that a damage event cancelled in the same tick, say — returns
     *         false and does nothing at all: no second payout, no second listener notification, and
     *         no releasing a target lock that a following session has already taken.
     */
    public static synchronized boolean finish(ActionSession session, ActionResult result) {
        return session.settle(result) && complete(session, result, null);
    }

    /** As {@link #finish}, carrying the cancellation reason through to the end listeners. */
    private static synchronized boolean complete(ActionSession session, ActionResult result,
                                                 @Nullable CancelReason reason) {
        BY_ACTOR.remove(session.actorId(), session);
        TARGET_LOCKS.remove(session.targetId(), session.actorId());
        remember(session.actorId(), session.requestNonce(), result);
        ActionFeedback.ended(session,
                result.accepted() ? dev.otectus.mcacrime.network.ActionProgressS2CPacket.Phase.FINISHED
                        : dev.otectus.mcacrime.network.ActionProgressS2CPacket.Phase.CANCELLED,
                result.code(), result.message());
        for (SessionEndListener listener : END_LISTENERS) {
            // One bad listener must not strand the session bookkeeping that already happened above.
            try {
                listener.onEnded(session, reason);
            } catch (Throwable t) {
                dev.otectus.mcacrime.McaCrime.LOGGER.debug("Action session end listener failed; ignoring", t);
            }
        }
        return true;
    }

    public static synchronized void remember(UUID actor, UUID nonce, ActionResult result) {
        Map<UUID, ActionResult> perActor = RESULTS.computeIfAbsent(actor, ignored -> new java.util.LinkedHashMap<>());
        perActor.put(nonce, result);
        while (perActor.size() > MAX_RESULTS_PER_ACTOR) {
            perActor.remove(perActor.keySet().iterator().next());
        }
    }

    public static synchronized boolean cancel(ActionSession session, CancelReason reason) {
        ActionResult result = ActionResult.rejected(
                "mcacrime.action.cancel." + reason.name().toLowerCase(java.util.Locale.ROOT));
        // Claim the ending before running the handler's cancel hook, so a session that has already
        // finished is never rolled back by a cancellation that arrived after it.
        if (!session.settle(result)) return false;
        CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
        if (handler != null) handler.cancel(session, reason);
        return complete(session, result, reason);
    }

    /**
     * The hash the replay cache compares a repeated nonce against.
     *
     * <p>A nonce alone only proves "I have sent this before". Hashing the request beside it is what
     * makes a replay detectable as a <em>different</em> request wearing an old nonce — the shape of
     * attack that otherwise turns one authorised mugging into an authorisation for any action against
     * any target, because the nonce is the only thing the server was checking.
     */
    public static long payloadHash(UUID nonce, ResourceLocation actionId, UUID target, int menuRevision) {
        return java.util.Objects.hash(nonce, actionId, target, menuRevision) & 0xFFFFFFFFL;
    }

    /**
     * Records this nonce's payload, or reports that the same nonce was already used for a different
     * one. The first caller wins; a repeat of the identical request is allowed through so an honest
     * client retry still reaches the ordinary replay cache.
     */
    public static synchronized boolean claimNonce(UUID actor, UUID nonce, long payloadHash) {
        Map<UUID, Long> perActor = NONCE_PAYLOADS.computeIfAbsent(actor, ignored -> new java.util.LinkedHashMap<>());
        Long seen = perActor.get(nonce);
        if (seen != null) return seen == payloadHash;
        perActor.put(nonce, payloadHash);
        while (perActor.size() > MAX_NONCES_PER_ACTOR) {
            perActor.remove(perActor.keySet().iterator().next());
        }
        return true;
    }

    public static Optional<ActionResult> replay(UUID actor, UUID nonce) {
        Map<UUID, ActionResult> perActor = RESULTS.get(actor);
        return perActor == null ? Optional.empty() : Optional.ofNullable(perActor.get(nonce));
    }

    public static void clearFor(UUID entity, CancelReason reason) {
        for (ActionSession session : active()) {
            if (session.actorId().equals(entity) || session.targetId().equals(entity)) cancel(session, reason);
        }
    }

    /**
     * Drops every trace of an actor: their live session, their target lock, and their cached nonce
     * results. Called on logout, because without it {@code RESULTS} keeps up to 256 replay entries per
     * actor for the life of the server and never releases them for a player who has left.
     */
    public static synchronized void forgetActor(UUID actor) {
        ActionSession session = BY_ACTOR.get(actor);
        if (session != null) cancel(session, CancelReason.ACTOR_GONE);
        BY_ACTOR.remove(actor);
        TARGET_LOCKS.values().removeIf(actor::equals);
        RESULTS.remove(actor);
        NONCE_PAYLOADS.remove(actor);
    }

    public static int activeCount() { return BY_ACTOR.size(); }
    public static ResourceLocation actionFor(UUID actor) {
        ActionSession session = BY_ACTOR.get(actor);
        return session == null ? null : session.actionId();
    }
}
