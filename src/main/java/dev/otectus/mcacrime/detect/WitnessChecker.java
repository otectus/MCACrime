package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides who witnessed a crime (spec §3.5): any MCA villager/guard (responder) within
 * {@code witnessRadius} that has line of sight to the victim. Event-driven and bounded — called once
 * per detected crime, never per tick (spec §20). Mirrors vanilla gossip's line-of-sight rule.
 *
 * <p>The scan now captures <em>identities</em>, not just a count, because who saw a crime decides who
 * may later speak about it. The identities are snapshotted here, once, and stored on the record; they
 * are never re-derived, since a villager who wandered past afterwards did not witness anything.
 */
public final class WitnessChecker {

    private WitnessChecker() {
    }

    /**
     * Number of responder NPCs that can see the victim within the witness radius (the victim excluded).
     *
     * @deprecated use {@link #resolve(ServerLevel, LivingEntity)}, which also captures who they were.
     *         Kept so existing callers keep working; it simply discards the identities.
     */
    @Deprecated
    public static int countWitnesses(ServerLevel level, LivingEntity victim) {
        return resolve(level, victim).count();
    }

    /**
     * The witnesses to a crime against {@code victim}: identities, whether it was witnessed at all,
     * and the true crowd size even when the stored set is capped.
     *
     * <p>Spectators are excluded explicitly. An MCA villager is never in spectator mode today, but
     * the filter costs nothing and stops a future or modded case from producing a witness who cannot
     * physically be there.
     */
    public static WitnessResult resolve(ServerLevel level, LivingEntity victim) {
        if (level == null || victim == null) {
            return WitnessResult.none();
        }
        double r = McaCrimeConfig.COMMON.witnessRadius.get();
        AABB box = victim.getBoundingBox().inflate(r);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != victim && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(e) && !e.isSpectator() && McaCompat.isMcaVillager(e));

        List<WitnessSelection.Candidate> candidates = new ArrayList<>(nearby.size());
        for (LivingEntity witness : nearby) {
            if (witness.hasLineOfSight(victim)) {
                candidates.add(new WitnessSelection.Candidate(
                        witness.getUUID(), witness.distanceToSqr(victim)));
            }
        }
        return WitnessSelection.select(candidates,
                McaCrimeConfig.COMMON.maxStoredWitnesses.get(), nearby.size());
    }

    /** Per-crime radius, spherical bounds, and sight of the act without assumed identity. */
    public static WitnessResult resolve(ServerLevel level, LivingEntity actor, LivingEntity victim,
                                        dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        if (!McaCrimeConfig.COMMON.enableWitnessSystem.get()) return resolve(level, victim == null ? actor : victim);
        LivingEntity center = victim == null ? actor : victim;
        double radius = awareness.visualRadius() * McaCrimeConfig.COMMON.visualWitnessRadiusMultiplier.get();
        List<WitnessSelection.Candidate> candidates = new ArrayList<>();
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, center.getBoundingBox().inflate(radius),
                e -> e != actor && e != victim && dev.otectus.mcacrime.ai.NpcAwareness.isAwake(e) && !e.isSpectator() && McaCompat.isMcaVillager(e));
        for (LivingEntity witness : nearby) {
            if (perceive(witness, actor, center, awareness).sawAct()) {
                candidates.add(new WitnessSelection.Candidate(witness.getUUID(), witness.distanceToSqr(center)));
            }
        }
        return WitnessSelection.select(candidates, McaCrimeConfig.COMMON.maxStoredWitnesses.get(), nearby.size());
    }

    public static PerceptionRules.Result perceive(LivingEntity observer, LivingEntity actor, LivingEntity center,
                                                   dev.otectus.mcacrime.crime.type.CrimeAwareness awareness) {
        if (!dev.otectus.mcacrime.ai.NpcAwareness.isAwake(observer)) return new PerceptionRules.Result(false, false, 0);
        var toward = center.getEyePosition().subtract(observer.getEyePosition()).normalize();
        boolean seesActor = observer.hasLineOfSight(actor);
        boolean seesAct = seesActor || observer.hasLineOfSight(center);
        return PerceptionRules.evaluate(new PerceptionRules.Input(Math.sqrt(observer.distanceToSqr(center)),
                awareness.visualRadius() * McaCrimeConfig.COMMON.visualWitnessRadiusMultiplier.get(),
                awareness.soundRadius() * McaCrimeConfig.COMMON.auditoryWitnessRadiusMultiplier.get(),
                seesAct, seesActor, observer.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS),
                McaCompat.isVillagerSleeping(observer), actor.isInvisible(), actor.isCrouching(), !seesAct,
                observer.getLookAngle().dot(toward), actor.level().getMaxLocalRawBrightness(actor.blockPosition()) / 15.0,
                actor.level().isRainingAt(actor.blockPosition())));
    }
}
