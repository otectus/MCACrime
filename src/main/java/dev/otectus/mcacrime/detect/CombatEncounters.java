package dev.otectus.mcacrime.detect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, dimension-local provenance. Only confirmed harmful acts enter this store. */
public final class CombatEncounters {
    public static final long DISENGAGE_TICKS = 200;
    public static final int MAX_ENCOUNTERS = 4096;
    public enum Basis { INITIATING_AGGRESSION, CONTINUED_AGGRESSION, SELF_DEFENSE,
        EXCESSIVE_DEFENSIVE_FORCE, LAWFUL_FORCE, RAID_ACCIDENT, UNTRACKED }
    public record Decision(UUID encounterId, UUID initiator, Basis basis, boolean exempt) {}
    private record Pair(UUID first, UUID second) {
        static Pair of(UUID a, UUID b) { return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a); }
    }
    private static final class Encounter {
        final UUID id = UUID.randomUUID();
        final UUID initiator;
        final boolean lawfulStart;
        long lastAt;
        Encounter(UUID initiator, boolean lawfulStart, long now) {
            this.initiator = initiator; this.lawfulStart = lawfulStart; this.lastAt = now;
        }
    }
    private final Map<Pair, Encounter> encounters = new LinkedHashMap<>();
    private long lastSweep = Long.MIN_VALUE;

    public Decision record(UUID attacker, UUID victim, long now, boolean lethal,
                           boolean lawfulForce, boolean raidAccident) {
        if (lastSweep == Long.MIN_VALUE || now < lastSweep || now - lastSweep >= 20) expire(now);
        Pair pair = Pair.of(attacker, victim);
        Encounter encounter = encounters.get(pair);
        if (encounter != null && (now < encounter.lastAt || now - encounter.lastAt >= DISENGAGE_TICKS)) {
            encounters.remove(pair);
            encounter = null;
        }
        boolean first = encounter == null;
        if (first) {
            if (encounters.size() >= MAX_ENCOUNTERS)
                return new Decision(null, null, Basis.UNTRACKED, lawfulForce);
            encounter = new Encounter(attacker, lawfulForce, now);
            encounters.put(pair, encounter);
        }
        encounter.lastAt = now;
        Basis basis = lawfulForce ? Basis.LAWFUL_FORCE
                : first && !lethal && raidAccident ? Basis.RAID_ACCIDENT
                : !attacker.equals(encounter.initiator) && !encounter.lawfulStart
                    ? lethal ? Basis.EXCESSIVE_DEFENSIVE_FORCE : Basis.SELF_DEFENSE
                : first ? Basis.INITIATING_AGGRESSION : Basis.CONTINUED_AGGRESSION;
        return new Decision(encounter.id, encounter.initiator, basis,
                basis == Basis.LAWFUL_FORCE || basis == Basis.SELF_DEFENSE || basis == Basis.RAID_ACCIDENT);
    }

    public void expire(long now) {
        lastSweep = now;
        encounters.values().removeIf(encounter -> now < encounter.lastAt || now - encounter.lastAt >= DISENGAGE_TICKS);
    }

    public void forget(UUID actor) {
        encounters.keySet().removeIf(pair -> pair.first.equals(actor) || pair.second.equals(actor));
    }

    public int size() { return encounters.size(); }
}
