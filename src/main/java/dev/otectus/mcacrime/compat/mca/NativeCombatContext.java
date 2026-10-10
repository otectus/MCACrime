package dev.otectus.mcacrime.compat.mca;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeAwareness;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.detect.CrimeClassifier;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.justice.ThiefCombatDecision;
import dev.otectus.mcacrime.justice.ThiefCombatService;
import dev.otectus.mcacrime.mask.MaskReactionPolicy;
import dev.otectus.mcacrime.mask.Masks;
import dev.otectus.mcacrime.relationship.NativeHeartPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invocation-scoped, exception-safe native callback bridge. No token survives its invocation.
 *
 * <p>0.7.7 adds three things the native hooks report back, all bounded and short-lived:
 * <ul>
 *   <li>MCA's killing penalty, one call per mourner, is decided by {@link NativeHeartPolicy}: whether
 *       that mourner identified the killer is asked once per scope with the kill's own awareness.</li>
 *   <li>The mourners MCA did charge are kept for a minute, so MCA: Crime's own family and witness loss
 *       for the same killing is not stacked on top.</li>
 *   <li>MCA's hurt hearts for a hit that may still be forgiven as minor friendly fire are held back
 *       until the hit is decided at the end of the tick, then applied, dropped or refunded.</li>
 * </ul>
 */
public final class NativeCombatContext {
    /**
     * @param amount     the raw damage MCA was handed, before its own and vanilla's reductions
     * @param identities mourner id to whether they identified the killer, filled lazily
     */
    public record Scope(LivingEntity target, DamageSource source, ThiefCombatDecision decision, float amount,
                        boolean death, Map<UUID, Boolean> identities) {}
    /** One native hurt charge in flight between the hit and the end-of-tick decision. */
    private record HurtHearts(LivingEntity victim, DamageSource source, ServerPlayer attacker, int delta,
                              boolean withheld) {}
    private static final ThreadLocal<ArrayDeque<Scope>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    /** "killer:deceased:mourner" to the game time MCA charged them. */
    private static final Map<String, Long> CHARGED = new LinkedHashMap<>();
    private static final List<HurtHearts> HURT = new ArrayList<>();
    private static final int MAX_ENTRIES = 4096;
    /** How long a charged mourner is remembered; the kill is committed at the end of the same tick. */
    private static final long CHARGED_TICKS = 1200L;
    private NativeCombatContext() {}
    public static Scope enter(LivingEntity target, DamageSource source, float amount, boolean death) {
        Scope parent = STACK.get().peek();
        var decision = death && parent != null && parent.target == target && parent.source == source
                ? parent.decision : ThiefCombatService.sample(target, source, death ? "death" : "damage");
        Scope scope = new Scope(target, source, decision, amount, death, new HashMap<>());
        STACK.get().push(scope);
        return scope;
    }
    public static void exit(Scope scope) {
        var stack = STACK.get();
        if (stack.peek() == scope) stack.pop(); else stack.remove(scope);
        if (stack.isEmpty()) STACK.remove();
    }
    public static ThiefCombatDecision decision(LivingEntity target, DamageSource source) {
        Scope scope = STACK.get().peek();
        return scope != null && scope.target == target && scope.source == source ? scope.decision : null;
    }
    /** MCA's hurt penalty: the hearts a villager loses toward the player who just hit it. */
    public static int damageDelta(int delta) {
        Scope scope = STACK.get().peek();
        if (delta >= 0 || scope == null) return delta;
        try {
            boolean exempt = scope.decision != null && scope.decision.exempt();
            Entity attacker = scope.source.getEntity();
            ServerPlayer player = attacker instanceof ServerPlayer p ? p : null;
            boolean masked = player != null && maskedHidden(player);
            boolean withhold = player != null && !exempt && !masked
                    && DamageIncidentService.minorHarmCandidate(scope.target, scope.source, scope.amount);
            int result = NativeHeartPolicy.hurtDelta(delta, player != null, exempt, masked, withhold);
            if (player != null && !exempt && !masked) remember(new HurtHearts(scope.target, scope.source, player, delta, withhold));
            return result;
        } catch (Throwable failure) {
            McaCrime.LOGGER.debug("MCA: Crime hurt-heart hook failed; MCA's value stands", failure);
            return scope.decision != null && scope.decision.exempt() ? 0 : delta;
        }
    }
    /**
     * MCA's killing penalty for one mourner.
     *
     * @param mourner the relationship's own villager, passed as {@code Object} so the hook descriptor
     *                names no type MCA might narrow
     */
    public static int tragedyDelta(int delta, DamageSource source, Entity deceased, Object mourner) {
        if (delta >= 0 || source == null) return delta;
        try {
            Entity killer = source.getEntity();
            if (!(killer instanceof Player player)) return delta;
            Scope scope = STACK.get().peek();
            if (scope != null && (scope.source != source || scope.target != deceased)) scope = null;
            Scope matched = scope;
            // A killing is exempt only where the policy exempts lethal force (0.7.7): under NORMAL_LAW a
            // mugging victim may fight but not kill, and the mourners' grief follows that.
            boolean exempt = matched != null && matched.decision != null && matched.decision.exemptsKill();
            LivingEntity mournerEntity = mourner instanceof LivingEntity living ? living : null;
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            int result = NativeHeartPolicy.tragedyDelta(delta, true, exempt, c.murderHeartPenalty.get(),
                    c.murderHeartsRequireWitness.get(), () -> maskedHidden(player),
                    () -> identified(matched, mournerEntity, player, deceased));
            if (result < 0 && mournerEntity != null) {
                charged(player.getUUID(), deceased.getUUID(), mournerEntity.getUUID(), mournerEntity.level().getGameTime());
            }
            return result;
        } catch (Throwable failure) {
            McaCrime.LOGGER.debug("MCA: Crime killing-heart hook failed; MCA's value stands", failure);
            return delta;
        }
    }
    public static boolean suppressesActor(Entity actor, Entity target) {
        Scope scope = STACK.get().peek();
        return actor != null && scope != null && scope.target == target && scope.decision != null && scope.decision.exempt()
                && scope.decision.actor().equals(actor.getUUID());
    }
    /** The murder-witness half of {@link #suppressesActor}: only a killing the policy exempts (0.7.7). */
    public static boolean suppressesMurderWitnesses(Entity actor, Entity target) {
        Scope scope = STACK.get().peek();
        return actor != null && scope != null && scope.target == target && scope.decision != null
                && scope.decision.exemptsKill() && scope.decision.actor().equals(actor.getUUID());
    }
    public static int standingDelta(UUID actor, int delta) {
        Scope scope = STACK.get().peek();
        return scope != null && scope.decision != null && scope.decision.actor().equals(actor) && scope.decision.exemptsKill() ? 0 : delta;
    }

    /**
     * MCA's own "is the attacker a friend" test inside its hurt handler (0.7.7).
     *
     * <p>When a player hits a villager MCA does more than dock hearts: it marks the villager as hit by
     * that player, raises a small bounty on it and on every guard within thirty-two blocks, and has those
     * guards warn and then attack the player. That is right for an assault and wrong for an exempt blow
     * -- a mugging victim fighting the mugger off would be set upon by the guards for it. MCA skips the
     * whole block for an attacker it counts as a friend, so for the duration of an exempt scope, and only
     * for the actor that scope exempts, this answers "friend". Every other attacker gets MCA's own answer.
     */
    public static boolean friendlyToMca(boolean friend) {
        if (friend) {
            return true;
        }
        try {
            Scope scope = STACK.get().peek();
            if (scope == null || scope.death || scope.decision == null || !scope.decision.exempt()) {
                return false;
            }
            Entity responsible = dev.otectus.mcacrime.detect.CrimeGate.responsibleActor(scope.source);
            return responsible != null && scope.decision.actor().equals(responsible.getUUID());
        } catch (Throwable failure) {
            McaCrime.LOGGER.debug("MCA: Crime friend-test hook failed; MCA's value stands", failure);
            return false;
        }
    }

    /** Whether MCA already charged this mourner for this killing, within the last minute. */
    public static boolean chargedByMca(UUID killer, UUID deceased, UUID mourner, long now) {
        if (killer == null || deceased == null || mourner == null) return false;
        synchronized (CHARGED) {
            Long at = CHARGED.get(killer + ":" + deceased + ":" + mourner);
            return at != null && now >= at && now - at < CHARGED_TICKS;
        }
    }

    /**
     * Decides MCA's hurt hearts for one hit now that the hit itself is decided: a forgiven hit costs
     * nothing (a held-back charge is dropped, one MCA already applied is refunded), and any other hit
     * costs exactly MCA's amount (a held-back charge is applied). A victim that died is left alone; its
     * killing has its own penalty.
     */
    public static void settleHurtHearts(LivingEntity victim, DamageSource source, boolean forgiven) {
        HurtHearts entry = null;
        synchronized (HURT) {
            for (Iterator<HurtHearts> it = HURT.iterator(); it.hasNext(); ) {
                HurtHearts candidate = it.next();
                if (candidate.victim == victim && candidate.source == source) {
                    entry = candidate;
                    it.remove();
                    break;
                }
            }
        }
        if (entry == null || !alive(victim)) return;
        if (forgiven && !entry.withheld) McaCompat.addHearts(entry.attacker, victim, -entry.delta);
        else if (!forgiven && entry.withheld) McaCompat.addHearts(entry.attacker, victim, entry.delta);
    }

    /** Applies every held-back charge whose hit was never decided, as MCA always would have. */
    public static void flushHurtHearts() {
        List<HurtHearts> left;
        synchronized (HURT) {
            if (HURT.isEmpty()) return;
            left = List.copyOf(HURT);
            HURT.clear();
        }
        for (HurtHearts entry : left) {
            if (entry.withheld && alive(entry.victim)) McaCompat.addHearts(entry.attacker, entry.victim, entry.delta);
        }
    }

    private static boolean maskedHidden(Player player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return MaskReactionPolicy.hidesAttribution(c.maskEnabled.get() && Masks.isMasked(player),
                c.maskHidesIdentityFromWitnesses.get());
    }

    /** Whether this mourner perceived the killer, with the kill's own awareness, asked once per scope. */
    private static boolean identified(Scope scope, LivingEntity mourner, Player killer, Entity deceased) {
        if (mourner == null || !(deceased instanceof LivingEntity victim)) return false;
        if (scope != null) {
            Boolean known = scope.identities.get(mourner.getUUID());
            if (known != null) return known;
        }
        boolean seen = mourner.level() == killer.level()
                && WitnessChecker.perceive(mourner, killer, victim, killAwareness(victim)).identifiesActor();
        if (scope != null) scope.identities.put(mourner.getUUID(), seen);
        return seen;
    }

    private static CrimeAwareness killAwareness(LivingEntity victim) {
        return CrimeTypeRegistry.getOrBuiltin(CrimeClassifier.classifyKill(victim))
                .map(type -> type.awareness())
                .orElse(CrimeAwareness.defaults(CrimeIds.KILL_VILLAGER));
    }

    private static void charged(UUID killer, UUID deceased, UUID mourner, long now) {
        synchronized (CHARGED) {
            String key = killer + ":" + deceased + ":" + mourner;
            CHARGED.remove(key);
            if (CHARGED.size() >= MAX_ENTRIES) {
                Iterator<String> oldest = CHARGED.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            CHARGED.put(key, now);
        }
    }

    private static void remember(HurtHearts entry) {
        synchronized (HURT) {
            if (HURT.size() >= MAX_ENTRIES) {
                // Bounded the cheap way: the oldest in-flight charge is applied now rather than dropped.
                HurtHearts oldest = HURT.remove(0);
                if (oldest.withheld && alive(oldest.victim)) McaCompat.addHearts(oldest.attacker, oldest.victim, oldest.delta);
            }
            HURT.add(entry);
        }
    }

    private static boolean alive(LivingEntity victim) {
        return victim != null && victim.isAlive() && !victim.isDeadOrDying() && !victim.isRemoved();
    }
}
