package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.ActionSessionManager;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.item.weapon.WeaponClass;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.memory.VictimMemoryService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;

/** Bounded world queries, invoked only for an active target on its reevaluation interval. */
public final class ThreatContexts {
    private ThreatContexts() {}
    public static ThreatEvaluator.Options options() {
        var c = McaCrimeConfig.COMMON;
        return new ThreatEvaluator.Options(c.meleeThreatRange.get(), c.rangedThreatRange.get(), c.enablePanic.get(),
                c.enableStalling.get(), c.armedVillagersCanResist.get(), c.freezeComplyingVictims.get());
    }
    public static boolean aimedAt(LivingEntity actor, LivingEntity target) {
        var drawn = WeaponDetector.drawnWeapon(actor).orElse(null);
        if (drawn == null || !actor.hasLineOfSight(target)) return false;
        if (actor.getLookAngle().dot(target.getEyePosition().subtract(actor.getEyePosition()).normalize()) < 0.75) return false;
        if (drawn.stack().getItem() instanceof BowItem) return actor.isUsingItem() && actor.getUseItem() == drawn.stack();
        if (drawn.stack().getItem() instanceof CrossbowItem) return CrossbowItem.isCharged(drawn.stack());
        // Modded guns use their existing weapon classification; explicit targeting supplies intent
        // when the optional weapon mod does not expose an aim state.
        return true;
    }
    public static ThreatContext build(LivingEntity villager, ServerPlayer actor, boolean coercive) {
        var weapon = WeaponDetector.drawnWeapon(actor).map(w -> w.match().weaponClass()).orElse(WeaponClass.NONE);
        var memories = VictimMemoryService.memories(actor.getServer(), villager.getUUID(), actor.getUUID());
        double fear = memories.stream().mapToDouble(m -> m.fear()).max().orElse(0);
        double anger = memories.stream().mapToDouble(m -> m.anger()).max().orElse(0);
        int repeats = memories.stream().mapToInt(m -> m.repeatCount()).max().orElse(0);
        var factors = ReactionFactors.of(villager.getUUID(), McaCompat.getHearts(actor, villager),
                EntitySelectors.isResponder(villager), McaCompat.isAdult(villager), repeats, false)
                .withPersonality(dev.otectus.mcacrime.compat.mca.McaHandles.personalityName(villager));
        boolean recent = memories.stream().anyMatch(m -> m.severity() >= 0.7 && actor.level().getGameTime() - m.timestamp() < 1200);
        int allies = 0; boolean guard = false; boolean family = false;
        var relatives = VictimMemoryService.familyOf(villager);
        for (LivingEntity other : villager.level().getEntitiesOfClass(LivingEntity.class, villager.getBoundingBox().inflate(8),
                e -> e != villager && e != actor && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(e) && McaCompat.isMcaVillager(e))) {
            if (villager.distanceToSqr(other) > 64 || !villager.hasLineOfSight(other)) continue;
            guard |= EntitySelectors.isResponder(other);
            if (ArmedResolver.classify(other).armed()) allies++;
            if (relatives.contains(other.getUUID())) {
                family |= ActionSessionManager.activeCoerciveAgainst(other.getUUID())
                        .filter(s -> s.actorId().equals(actor.getUUID())).isPresent();
            }
        }
        family |= memories.stream().anyMatch(m -> m.category().equals("FAMILY_HARM") && m.anger() > 0.4);
        return new ThreatContext(weapon, aimedAt(actor, villager), coercive, Math.sqrt(actor.distanceToSqr(villager)),
                Math.max(0, Math.min(1, villager.getHealth() / Math.max(1, villager.getMaxHealth()))),
                factors.bravery(), factors.combatConfidence(), fear, anger, recent, allies, guard,
                ArmedResolver.classify(villager).armed(), family);
    }
}
