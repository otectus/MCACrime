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
                e -> e != villager && e != actor && dev.otectus.mcacrime.ai.NpcAwareness.canObserveAct(e) && McaCompat.isMcaVillager(e))) {
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
                bravery(villager, factors), factors.combatConfidence(), fear, anger, recent, allies, guard,
                ArmedResolver.classify(villager).armed(), family);
    }

    /**
     * Bravery, with a settlement companion's hunger, thirst and fatigue allowed a small say.
     *
     * <p>Off by default and capped at ±{@value dev.otectus.mcacrime.compat.TownsteadNeeds#MAX_THREAT_ADJUSTMENT}
     * when it is on. Both of those are the same decision: this changes a number a server owner has
     * already tuned, so it has to be something they turned on deliberately and something that can move
     * a village's average without deciding any single encounter. An untracked reading contributes
     * exactly zero, so an install without the companion produces the identical context it always did.
     */
    private static double bravery(LivingEntity villager, ReactionFactors factors) {
        boolean enabled;
        try {
            enabled = McaCrimeConfig.COMMON.townsteadEnabled.get()
                    && McaCrimeConfig.COMMON.townsteadNeedResponseModifiers.get();
        } catch (Throwable t) {
            enabled = false;
        }
        if (!enabled) {
            return factors.bravery();
        }
        double adjusted = dev.otectus.mcacrime.compat.TownsteadNeeds.adjustFactor(factors.bravery(),
                dev.otectus.mcacrime.compat.TownsteadSnapshotCache.snapshot(villager).needs(), true);
        return withPersonality(villager, adjusted);
    }

    /**
     * The datapack's opinion about this villager's personality, applied after their needs.
     *
     * <p>Behind the same switch as the needs modifiers, for the same reason: both change a number a
     * server owner already tuned, and neither may be something that happens to somebody who did not ask
     * for it. Both are also capped at a tenth, so the two together can move a villager's bravery by at
     * most a fifth — enough for a brave villager and a timid one to feel different at the margin, never
     * enough to decide an encounter on its own.
     *
     * <p>A personality nobody described contributes exactly zero, which is every personality until a
     * pack says otherwise and every villager when the settlement mod is absent.
     */
    private static double withPersonality(LivingEntity villager, double bravery) {
        if (!dev.otectus.mcacrime.compat.TownsteadBridge
                .has(dev.otectus.mcacrime.compat.TownsteadCapability.READ_VILLAGER)) {
            // The personality lives on the villager read rather than on the cached needs snapshot, so
            // without that capability there is nothing to look up and no reason to pay for the query.
            return bravery;
        }
        String personality = dev.otectus.mcacrime.compat.TownsteadBridge.villager(villager).asOptional()
                .map(dev.otectus.mcacrime.compat.TownsteadVillagerView::personalityId)
                .orElse("");
        dev.otectus.mcacrime.compat.TownsteadPersonalityProfiles.Profile profile =
                dev.otectus.mcacrime.compat.TownsteadPersonalityProfiles.profile(personality);
        if (profile.neutral()) {
            return bravery;
        }
        // Threat felt and bravery are opposite ends of one axis: a personality that finds a scene more
        // threatening is a villager who acts less bravely in it.
        return Math.max(0.0D, Math.min(1.0D, bravery - profile.threat() + (-profile.flee())));
    }
}
