package dev.otectus.mcacrime.detect;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minor friendly fire: one light hit on a protected villager forgiven as an accident (0.7.7).
 *
 * <p>Until 0.7.7 any positive final damage was a full assault, and one punch produced a memory with
 * fear 0.7 and anger 0.775, both above the conversation thresholds. Swinging at a zombie beside a
 * villager was enough. A hit is forgiven now only when every one of these holds:
 *
 * <ol>
 *   <li>forgiveness is enabled and the outcome is not lethal;</li>
 *   <li>the final damage is above zero and at or below the configured maximum;</li>
 *   <li>the victim is a protected NPC, not a player;</li>
 *   <li>the hit is the attacker's own, direct or by projectile, not a pet's, and no mugging is in
 *       progress;</li>
 *   <li>the victim holds no memory of this attacker;</li>
 *   <li>no harm by this attacker on this victim exists inside the window;</li>
 *   <li>the attacker has been forgiven for fewer than the configured number of victims inside the
 *       window.</li>
 * </ol>
 *
 * <p>Every hit that reaches this policy is recorded against its pair, forgiven or not, so a second hit
 * inside the window is always charged normally and repeated taps gain nothing. The ledger is in memory:
 * a server restart clears it, a relog does not. Persisting it would need a schema change for a grace
 * that lasts five minutes by default.
 *
 * <p>Not thread-safe; it lives inside one server's damage runtime and is only touched on the server
 * thread.
 */
public final class MinorHarmPolicy {

    /** Ceiling on remembered pairs, so a long session cannot grow the map without bound. */
    private static final int MAX_PAIRS = 4096;
    /** Ceiling on attackers with forgiven victims inside the window. */
    private static final int MAX_ATTACKERS = 1024;

    /** The {@code detection.minorHarm} block, snapshotted per decision. */
    public record Settings(boolean enabled, double maxDamage, long windowTicks, int maxVictimsPerWindow) {

        public static final Settings OFF = new Settings(false, 0.0D, 0L, 0);
    }

    /**
     * The facts about the hit that do not depend on how hard it landed.
     *
     * @param protectedNpcVictim     the victim is a protected NPC and not a player
     * @param directOrProjectile     the attacker's own blow or projectile, not a tamed animal's
     * @param mugging                the attacker is threatening this victim in a mugging right now
     * @param victimRemembersAttacker the victim already holds a crime memory of this attacker
     */
    public record Eligibility(boolean protectedNpcVictim, boolean directOrProjectile, boolean mugging,
                              boolean victimRemembersAttacker) {
    }

    /** One hit, with its final damage and whether it was lethal. */
    public record Facts(UUID attacker, UUID victim, long at, double amount, boolean lethal,
                        Eligibility eligibility) {
    }

    /** Why a hit was or was not forgiven; the first failing condition in the class order. */
    public enum Verdict {
        FORGIVEN,
        DISABLED,
        LETHAL,
        NO_DAMAGE,
        TOO_HEAVY,
        NOT_PROTECTED,
        NOT_OWN_HIT,
        MUGGING,
        GRIEVANCE,
        REPEATED,
        VICTIM_CAP;

        public boolean forgiven() {
            return this == FORGIVEN;
        }
    }

    private record Pair(UUID attacker, UUID victim) {
    }

    private record Forgiven(UUID victim, long at) {
    }

    private final Map<Pair, Long> lastHarm = new LinkedHashMap<>();
    private final Map<UUID, Deque<Forgiven>> forgiven = new LinkedHashMap<>();

    /** The verdict for this hit, changing nothing. */
    public Verdict peek(Settings settings, Facts facts) {
        if (settings == null || facts == null || !settings.enabled()) {
            return Verdict.DISABLED;
        }
        if (facts.lethal()) {
            return Verdict.LETHAL;
        }
        if (!Double.isFinite(facts.amount()) || facts.amount() <= 0.0D) {
            return Verdict.NO_DAMAGE;
        }
        if (facts.amount() > settings.maxDamage()) {
            return Verdict.TOO_HEAVY;
        }
        Eligibility eligibility = facts.eligibility();
        if (eligibility == null || !eligibility.protectedNpcVictim()) {
            return Verdict.NOT_PROTECTED;
        }
        if (!eligibility.directOrProjectile()) {
            return Verdict.NOT_OWN_HIT;
        }
        if (eligibility.mugging()) {
            return Verdict.MUGGING;
        }
        if (eligibility.victimRemembersAttacker()) {
            return Verdict.GRIEVANCE;
        }
        Long last = lastHarm.get(new Pair(facts.attacker(), facts.victim()));
        if (last != null && within(last, facts.at(), settings.windowTicks())) {
            return Verdict.REPEATED;
        }
        if (forgivenInWindow(facts.attacker(), facts.at(), settings.windowTicks()) >= settings.maxVictimsPerWindow()) {
            return Verdict.VICTIM_CAP;
        }
        return Verdict.FORGIVEN;
    }

    /**
     * The verdict for this hit, recorded: the pair's harm is stamped whatever the answer, and a forgiven
     * victim counts against the attacker's cap.
     */
    public Verdict assess(Settings settings, Facts facts) {
        Verdict verdict = peek(settings, facts);
        if (verdict == Verdict.DISABLED || facts.lethal()) {
            return verdict;
        }
        remember(new Pair(facts.attacker(), facts.victim()), facts.at());
        if (verdict.forgiven()) {
            Deque<Forgiven> victims = forgiven.computeIfAbsent(facts.attacker(), unused -> new ArrayDeque<>());
            victims.addLast(new Forgiven(facts.victim(), facts.at()));
            while (victims.size() > 16) {
                victims.removeFirst();
            }
            if (forgiven.size() > MAX_ATTACKERS) {
                Iterator<UUID> oldest = forgiven.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        return verdict;
    }

    /** Drops what the window no longer covers. Cheap, and called once per damage flush. */
    public void expire(long now, long windowTicks) {
        lastHarm.values().removeIf(at -> !within(at, now, windowTicks));
        for (Iterator<Deque<Forgiven>> it = forgiven.values().iterator(); it.hasNext(); ) {
            Deque<Forgiven> victims = it.next();
            victims.removeIf(entry -> !within(entry.at(), now, windowTicks));
            if (victims.isEmpty()) {
                it.remove();
            }
        }
    }

    /** How many pairs are remembered, for tests and diagnostics. */
    public int size() {
        return lastHarm.size();
    }

    private int forgivenInWindow(UUID attacker, long now, long windowTicks) {
        Deque<Forgiven> victims = forgiven.get(attacker);
        if (victims == null) {
            return 0;
        }
        return (int) victims.stream().filter(entry -> within(entry.at(), now, windowTicks))
                .map(Forgiven::victim).distinct().count();
    }

    private void remember(Pair pair, long at) {
        lastHarm.remove(pair);
        if (lastHarm.size() >= MAX_PAIRS) {
            Iterator<Pair> oldest = lastHarm.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        lastHarm.put(pair, at);
    }

    /**
     * Whether {@code at} is inside the window ending at {@code now}. A clock that went backwards reads as
     * outside, the same convention the harm cooldown and the assignment cooldown use.
     */
    private static boolean within(long at, long now, long windowTicks) {
        return now >= at && now - at < windowTicks;
    }
}
