package dev.otectus.mcacrime.restraint;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Every live {@link Session}, server-side and transient (M1.7).
 *
 * <p>One actor, one session. Opening a second cancels the first with
 * {@link SessionCancelCause#REPLACED} rather than refusing it, because the alternative is a player
 * who walked away from a lock being unable to start anything until their abandoned session expires.
 *
 * <p>The cap is a refusal, never an eviction. Evicting somebody else's in-progress work to make room
 * for a newcomer would make a full registry a way to interrupt other players, which is a worse
 * failure than being told to try again.
 *
 * <p>Not static state: a registry is owned by whoever runs it, so a test can hold one and a server
 * shutdown can drop one. {@link #fromConfig()} is the gameplay instance's factory and is the only
 * thing here that touches configuration, which keeps the rest unit-testable.
 */
public final class SessionRegistry {

    private final IntSupplier maxSessions;
    private final Map<Long, Session> byId = new LinkedHashMap<>();
    private final Map<UUID, Long> byActor = new LinkedHashMap<>();
    private long nextId = 1L;

    public SessionRegistry(IntSupplier maxSessions) {
        this.maxSessions = maxSessions == null ? () -> 0 : maxSessions;
    }

    /** A registry capped by a fixed number. What tests use. */
    public SessionRegistry(int maxSessions) {
        this(() -> maxSessions);
    }

    /** The gameplay registry, capped by {@code restraints.maxConcurrentSessions}. */
    public static SessionRegistry fromConfig() {
        return new SessionRegistry(() -> dev.otectus.mcacrime.McaCrimeConfig.COMMON.maxConcurrentSessions.get());
    }

    /** The one registry the running server uses. Transient: no session outlives a session. */
    private static SessionRegistry server;

    /**
     * The server's registry, created on first use.
     *
     * <p>A single holder rather than one registry per service, because the cap in
     * {@code restraints.maxConcurrentSessions} is a server-wide budget: three separate registries of
     * sixty-four would be a cap of one hundred and ninety-two under a different name.
     */
    public static synchronized SessionRegistry server() {
        if (server == null) {
            server = fromConfig();
        }
        return server;
    }

    /** Drops every session. Server stop, and the start of each test that opens one. */
    public static synchronized void resetServer(SessionCancelCause cause) {
        if (server != null) {
            server.clear(cause);
        }
        server = null;
    }

    /**
     * The next session id.
     *
     * <p>Allocated here rather than by the caller so two sessions can never share one, and so an id
     * is never guessable from anything the client knows.
     */
    public long allocateId() {
        return nextId++;
    }

    /**
     * Registers {@code session}, cancelling whatever its actor had open.
     *
     * @return the session, or empty when the registry is full
     */
    public Optional<Session> open(@Nullable Session session) {
        if (session == null || session.actor() == null) {
            return Optional.empty();
        }
        cancelForActor(session.actor(), SessionCancelCause.REPLACED);
        int cap = Math.max(0, maxSessions.getAsInt());
        if (byId.size() >= cap) {
            return Optional.empty();
        }
        byId.put(session.id(), session);
        byActor.put(session.actor(), session.id());
        return Optional.of(session);
    }

    public Optional<Session> get(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The session belonging to one actor, if any. */
    public Optional<Session> forActor(@Nullable UUID actor) {
        Long id = actor == null ? null : byActor.get(actor);
        return id == null ? Optional.empty() : get(id);
    }

    /**
     * The session {@code id}, if it belongs to {@code actor} and is still live at {@code now}.
     *
     * <p>The lookup every handler should use: it is what makes "the sender owns this session" a
     * check rather than an assumption, and it never resurrects an expired one.
     */
    public Optional<Session> validate(long id, @Nullable UUID actor, long now) {
        Session session = byId.get(id);
        if (session == null || actor == null || !actor.equals(session.actor()) || !session.live(now)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Ends one session. Returns whether it was there to end. */
    public boolean cancel(long id, SessionCancelCause cause) {
        Session removed = byId.remove(id);
        if (removed == null) {
            return false;
        }
        byActor.remove(removed.actor(), id);
        return true;
    }

    /** Ends whatever one actor had open: death, logout, dimension change, item swap. */
    public int cancelForActor(@Nullable UUID actor, SessionCancelCause cause) {
        Long id = actor == null ? null : byActor.get(actor);
        return id != null && cancel(id, cause) ? 1 : 0;
    }

    /**
     * Ends every session pointed at one target.
     *
     * <p>Several at once is the normal case: a prisoner being frisked by one guard while another
     * picks their cuffs has two sessions, and their release ends both.
     */
    public int cancelForTarget(@Nullable UUID target, SessionCancelCause cause) {
        if (target == null) {
            return 0;
        }
        List<Long> doomed = new ArrayList<>();
        byId.forEach((id, session) -> {
            if (target.equals(session.target())) {
                doomed.add(id);
            }
        });
        int cancelled = 0;
        for (Long id : doomed) {
            if (cancel(id, cause)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Ends every session whose expiry has passed. Called from the server tick. */
    public int expire(long now) {
        List<Long> doomed = new ArrayList<>();
        byId.forEach((id, session) -> {
            if (!session.live(now)) {
                doomed.add(id);
            }
        });
        int expired = 0;
        for (Long id : doomed) {
            if (cancel(id, SessionCancelCause.EXPIRED)) {
                expired++;
            }
        }
        return expired;
    }

    /** Every actor with a live session, in the order they opened one. */
    public java.util.List<UUID> actors() {
        return java.util.List.copyOf(byActor.keySet());
    }

    public int size() {
        return byId.size();
    }

    /** Ends everything: server shutdown, or a store that has gone read-only. */
    public void clear(SessionCancelCause cause) {
        byId.clear();
        byActor.clear();
    }
}
