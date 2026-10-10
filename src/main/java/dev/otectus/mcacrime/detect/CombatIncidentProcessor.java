package dev.otectus.mcacrime.detect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Finality, legal provenance, harm cooldown and commit composition for one server dimension. */
public final class CombatIncidentProcessor {
    /**
     * One applied hit.
     *
     * @param amount        the final damage, for the minor-harm rule (0.7.7)
     * @param minorHarm     the facts that let {@link MinorHarmPolicy} consider this hit, or null when it may not
     *                      (a non-damaging act, an NPC attacker, a player victim)
     * @param answersThreat the attacker is the victim of a mugging by the struck villager (0.7.7), so a new
     *                      encounter between them is the thief's, not the attacker's
     */
    public record Hit(UUID id, UUID attacker, UUID victim, long at, boolean playerAttacker,
                      boolean harmCharge, boolean killCharge, boolean lawfulNpc, boolean raidSplash,
                      double amount, MinorHarmPolicy.Eligibility minorHarm, boolean answersThreat) {
        public Hit(UUID id, UUID attacker, UUID victim, long at, boolean playerAttacker,
                   boolean harmCharge, boolean killCharge, boolean lawfulNpc, boolean raidSplash,
                   double amount, MinorHarmPolicy.Eligibility minorHarm) {
            this(id, attacker, victim, at, playerAttacker, harmCharge, killCharge, lawfulNpc, raidSplash, amount,
                    minorHarm, false);
        }

        public Hit(UUID id, UUID attacker, UUID victim, long at, boolean playerAttacker,
                   boolean harmCharge, boolean killCharge, boolean lawfulNpc, boolean raidSplash) {
            this(id, attacker, victim, at, playerAttacker, harmCharge, killCharge, lawfulNpc, raidSplash, 0.0D, null);
        }
    }
    public record Assessment(Hit hit, DamageFinality.Outcome outcome, CombatEncounters.Decision decision) {}
    /** How one completion ended, for the caller that has to act on a forgiven hit (0.7.7). */
    public enum Completion {
        NONE, EXEMPT, FORGIVEN, COOLDOWN, COMMITTED, REFUSED;
        public boolean committed() { return this == COMMITTED; }
    }
    private record Pair(UUID attacker, UUID victim) {}
    private final CombatEncounters combat = new CombatEncounters();
    private final Map<Pair, Long> lastHarm = new LinkedHashMap<>();
    private final Map<UUID, Long> processed = new LinkedHashMap<>();

    public boolean complete(Hit hit, DamageFinality.Outcome outcome, int cooldown,
                            Function<Assessment, Boolean> commit) {
        return completeDetailed(hit, outcome, cooldown, null, MinorHarmPolicy.Settings.OFF, commit).committed();
    }

    /**
     * The completion, in its fixed order: finality and replay, the lawful exemptions exactly as before,
     * then the minor-harm grace (0.7.7), then the harm cooldown, then the commit.
     *
     * <p>The grace sits after the exemptions so self-defence, lawful force and the raid rule never depend
     * on it, and before the cooldown so a forgiven hit stamps no cooldown: the next hit inside the window
     * is charged once, as an ordinary first offence.
     */
    public Completion completeDetailed(Hit hit, DamageFinality.Outcome outcome, int cooldown,
                                       MinorHarmPolicy grace, MinorHarmPolicy.Settings settings,
                                       Function<Assessment, Boolean> commit) {
        if (outcome == DamageFinality.Outcome.NONE || processed.containsKey(hit.id)) return Completion.NONE;
        // Consume before callbacks; a nested completion of the same hit cannot acquire a new case.
        remember(processed, hit.id, hit.at);
        boolean lethal = outcome == DamageFinality.Outcome.KILL;
        boolean charge = lethal ? hit.killCharge : hit.harmCharge;
        var decision = combat.record(hit.attacker, hit.victim, hit.at, lethal,
                hit.playerAttacker ? !charge : hit.lawfulNpc, hit.raidSplash, hit.answersThreat);
        if (!hit.playerAttacker || !charge || decision.exempt()) return Completion.EXEMPT;
        if (grace != null && hit.minorHarm != null && grace.assess(settings, new MinorHarmPolicy.Facts(
                hit.attacker, hit.victim, hit.at, hit.amount, lethal, hit.minorHarm)).forgiven()) {
            return Completion.FORGIVEN;
        }
        Pair pair = new Pair(hit.attacker, hit.victim);
        Long last = lastHarm.get(pair);
        if (!lethal && last != null && hit.at >= last && hit.at - last < cooldown) return Completion.COOLDOWN;
        if (!commit.apply(new Assessment(hit, outcome, decision))) return Completion.REFUSED;
        if (lethal) lastHarm.remove(pair);
        else remember(lastHarm, pair, hit.at);
        return Completion.COMMITTED;
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
