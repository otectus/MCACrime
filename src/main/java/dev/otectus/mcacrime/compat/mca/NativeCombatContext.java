package dev.otectus.mcacrime.compat.mca;

import dev.otectus.mcacrime.justice.ThiefCombatDecision;
import dev.otectus.mcacrime.justice.ThiefCombatService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import java.util.ArrayDeque;
import java.util.UUID;

/** Invocation-scoped, exception-safe native callback bridge. No token survives its invocation. */
public final class NativeCombatContext {
    public record Scope(LivingEntity target, DamageSource source, ThiefCombatDecision decision) {}
    private static final ThreadLocal<ArrayDeque<Scope>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private NativeCombatContext() {}
    public static Scope enter(LivingEntity target, DamageSource source, boolean death) {
        Scope parent = STACK.get().peek();
        var decision = death && parent != null && parent.target == target && parent.source == source
                ? parent.decision : ThiefCombatService.sample(target, source, death ? "death" : "damage");
        Scope scope = new Scope(target, source, decision);
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
    public static int damageDelta(int delta) {
        Scope scope = STACK.get().peek();
        return delta < 0 && scope != null && scope.decision != null && scope.decision.exempt() ? 0 : delta;
    }
    public static int tragedyDelta(int delta, DamageSource source, Entity deceased) {
        Scope scope = STACK.get().peek();
        return scope != null && scope.source == source && scope.target == deceased ? damageDelta(delta) : delta;
    }
    public static boolean suppressesActor(Entity actor, Entity target) {
        Scope scope = STACK.get().peek();
        return actor != null && scope != null && scope.target == target && scope.decision != null && scope.decision.exempt()
                && scope.decision.actor().equals(actor.getUUID());
    }
    public static int standingDelta(UUID actor, int delta) {
        Scope scope = STACK.get().peek();
        return scope != null && scope.decision != null && scope.decision.actor().equals(actor) && scope.decision.exempt() ? 0 : delta;
    }
}
