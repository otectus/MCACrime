package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Enforces one action per actor, one hostile lock per target, and in-memory nonce replay protection. */
public final class ActionSessionManager {
    private static final int MAX_RESULTS_PER_ACTOR = 256;
    private static final Map<UUID, ActionSession> BY_ACTOR = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> TARGET_LOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, ActionResult>> RESULTS = new ConcurrentHashMap<>();

    private ActionSessionManager() {}

    public static synchronized boolean begin(ActionSession session) {
        if (BY_ACTOR.containsKey(session.actorId()) || TARGET_LOCKS.containsKey(session.targetId())) {
            return false;
        }
        BY_ACTOR.put(session.actorId(), session);
        TARGET_LOCKS.put(session.targetId(), session.actorId());
        return true;
    }

    public static Optional<ActionSession> forActor(UUID actor) { return Optional.ofNullable(BY_ACTOR.get(actor)); }
    public static boolean targetLocked(UUID target) { return TARGET_LOCKS.containsKey(target); }
    public static ArrayList<ActionSession> active() { return new ArrayList<>(BY_ACTOR.values()); }

    public static synchronized void finish(ActionSession session, ActionResult result) {
        BY_ACTOR.remove(session.actorId(), session);
        TARGET_LOCKS.remove(session.targetId(), session.actorId());
        remember(session.actorId(), session.requestNonce(), result);
    }

    public static synchronized void remember(UUID actor, UUID nonce, ActionResult result) {
        Map<UUID, ActionResult> perActor = RESULTS.computeIfAbsent(actor, ignored -> new java.util.LinkedHashMap<>());
        perActor.put(nonce, result);
        while (perActor.size() > MAX_RESULTS_PER_ACTOR) {
            perActor.remove(perActor.keySet().iterator().next());
        }
    }

    public static synchronized void cancel(ActionSession session, CancelReason reason) {
        CrimeActionHandler handler = ActionHandlerRegistry.get(session.actionId());
        if (handler != null) handler.cancel(session, reason);
        finish(session, ActionResult.rejected("mcacrime.action.cancel." + reason.name().toLowerCase(java.util.Locale.ROOT)));
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

    public static int activeCount() { return BY_ACTOR.size(); }
    public static ResourceLocation actionFor(UUID actor) {
        ActionSession session = BY_ACTOR.get(actor);
        return session == null ? null : session.actionId();
    }
}
