package dev.otectus.mcacrime.detect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Finality, legal provenance, harm cooldown and commit composition for one server dimension. */
public final class CombatIncidentProcessor {
    public record Hit(UUID id, UUID attacker, UUID victim, long at, boolean playerAttacker,
                      boolean harmCharge, boolean killCharge, boolean lawfulNpc, boolean raidSplash) {}
    public record Assessment(Hit hit, DamageFinality.Outcome outcome, CombatEncounters.Decision decision) {}
    private record Pair(UUID attacker, UUID victim) {}
    private final CombatEncounters combat = new CombatEncounters();
    private final Map<Pair, Long> lastHarm = new LinkedHashMap<>();
    private final Map<UUID, Long> processed = new LinkedHashMap<>();

    public boolean complete(Hit hit, DamageFinality.Outcome outcome, int cooldown,
                            Function<Assessment, Boolean> commit) {
        if (outcome == DamageFinality.Outcome.NONE || processed.containsKey(hit.id)) return false;
        // Consume before callbacks; a nested completion of the same hit cannot acquire a new case.
        remember(processed, hit.id, hit.at);
        boolean lethal = outcome == DamageFinality.Outcome.KILL;
        boolean charge = lethal ? hit.killCharge : hit.harmCharge;
        var decision = combat.record(hit.attacker, hit.victim, hit.at, lethal,
                hit.playerAttacker ? !charge : hit.lawfulNpc, hit.raidSplash);
        if (!hit.playerAttacker || !charge || decision.exempt()) return false;
        Pair pair = new Pair(hit.attacker, hit.victim);
        Long last = lastHarm.get(pair);
        if (!lethal && last != null && hit.at >= last && hit.at - last < cooldown) return false;
        if (!commit.apply(new Assessment(hit, outcome, decision))) return false;
        if (lethal) lastHarm.remove(pair);
        else remember(lastHarm, pair, hit.at);
        return true;
    }

    private static <K> void remember(Map<K, Long> map, K key, long at) {
        if (map.size() >= 4096) map.remove(map.keySet().iterator().next());
        map.put(key, at);
    }

    public void expire(long now, int cooldown) {
        combat.expire(now);
        lastHarm.values().removeIf(at -> now < at || now - at >= Math.max(1, cooldown));
        processed.values().removeIf(at -> now < at || now - at >= Math.max(200, cooldown));
    }

    public void forget(UUID actor) {
        combat.forget(actor);
        lastHarm.keySet().removeIf(pair -> pair.attacker.equals(actor) || pair.victim.equals(actor));
    }
}
