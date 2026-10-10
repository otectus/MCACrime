package dev.otectus.mcacrime.mug.npc;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who has held up whom, kept past the session that made the threat (0.7.7).
 *
 * <p>A mugging is the thief's aggression even though the thief never lands a blow, so the victim
 * hitting back is self-defence. The question "is this target holding up this player" used to be asked
 * of the live session, and every way of fighting back destroys that session before the blow is
 * classified: a drawn weapon aborts it, sand aborts it, a punch now interrupts it, a death ends it. So
 * the threat is written here when {@link NpcMuggingService#begin} opens the session, stamped closed
 * when the session ends for any reason, and honoured for {@code villageJustice.thiefDefenseGraceTicks}
 * afterwards -- long enough that a mugger running off with the purse is still the aggressor to the
 * player chasing them, short enough that it never becomes a standing licence.
 *
 * <p>Memory-only, bounded and server-authoritative. A threat is a fact about the last minute; a
 * restart that loses one loses a recovery window, not property or a case. Nothing here grants
 * anything: {@code ThiefCombatService} reads it as one input among the target's age, role and
 * restraint, and those still protect a child, a guard or a prisoner whatever they did.
 */
public final class MuggingThreats {

    /** One threat. {@code endedAt} is {@link Long#MAX_VALUE} while the session is still open. */
    public record Threat(UUID transactionId, UUID thief, UUID victim, long startedAt, long endedAt) {

        public boolean active() {
            return endedAt == Long.MAX_VALUE;
        }

        /**
         * Whether this threat still justifies the victim's force at {@code now}: while it is open, and for
         * the {@code graceTicks} ticks starting with the one it ended in. A zero window ends with the
         * session. The blow that ends a session is not left out by that: its decision is sampled before
         * the interruption it causes.
         */
        public boolean covers(long now, long graceTicks) {
            if (now < startedAt) {
                return false; // a clock that ran backwards (a /time set) never extends a window
            }
            return active() || now >= endedAt && now - endedAt < Math.max(0L, graceTicks);
        }
    }

    private record Key(UUID thief, UUID victim) {
    }

    /** Far more than any server holds at once; the oldest closed threat is dropped first. */
    static final int MAX_THREATS = 4096;

    private static final Map<Key, Threat> THREATS = new LinkedHashMap<>();

    private MuggingThreats() {
    }

    /** Records the threat a session has just made. Replaces an older threat between the same two. */
    public static synchronized void opened(UUID transactionId, UUID thief, UUID victim, long now) {
        if (transactionId == null || thief == null || victim == null) {
            return;
        }
        Key key = new Key(thief, victim);
        THREATS.remove(key);
        if (THREATS.size() >= MAX_THREATS) {
            evictOne();
        }
        THREATS.put(key, new Threat(transactionId, thief, victim, now, Long.MAX_VALUE));
    }

    /** Stamps the threat this transaction made as over, starting its recovery window. Idempotent. */
    public static synchronized void closed(UUID transactionId, UUID thief, UUID victim, long now) {
        if (transactionId == null || thief == null || victim == null) {
            return;
        }
        Key key = new Key(thief, victim);
        Threat threat = THREATS.get(key);
        if (threat != null && threat.transactionId().equals(transactionId) && threat.active()) {
            THREATS.put(key, new Threat(transactionId, thief, victim, threat.startedAt(), Math.max(now,
                    threat.startedAt())));
        }
    }

    /** Whether {@code thief} is holding up {@code victim} now, or did within the recovery window. */
    public static synchronized boolean threatens(UUID thief, UUID victim, long now, long graceTicks) {
        return find(thief, victim).filter(threat -> threat.covers(now, graceTicks)).isPresent();
    }

    public static synchronized Optional<Threat> find(UUID thief, UUID victim) {
        if (thief == null || victim == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(THREATS.get(new Key(thief, victim)));
    }

    /** Drops closed threats whose recovery window has passed. Open threats are never expired here. */
    public static synchronized int expire(long now, long graceTicks) {
        int before = THREATS.size();
        THREATS.values().removeIf(threat -> !threat.active() && !threat.covers(now, graceTicks));
        return before - THREATS.size();
    }

    public static synchronized int size() {
        return THREATS.size();
    }

    /** Server stop. A threat never outlives the server that saw it. */
    public static synchronized void clear() {
        THREATS.clear();
    }

    private static void evictOne() {
        Iterator<Map.Entry<Key, Threat>> it = THREATS.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().active()) {
                it.remove();
                return;
            }
        }
        // Every one is open, which nothing in normal play can produce: drop the oldest rather than grow.
        it = THREATS.entrySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
