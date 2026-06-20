package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Decides whether a crime was witnessed (spec §3.5): any MCA villager/guard (responder) within
 * {@code witnessRadius} that has line of sight to the victim. Event-driven and bounded — called once per
 * detected crime, never per tick (spec §20). Mirrors vanilla gossip's line-of-sight rule.
 */
public final class WitnessChecker {

    private WitnessChecker() {
    }

    /** Number of responder NPCs that can see the victim within the witness radius (the victim excluded). */
    public static int countWitnesses(ServerLevel level, LivingEntity victim) {
        double r = McaCrimeConfig.COMMON.witnessRadius.get();
        AABB box = victim.getBoundingBox().inflate(r);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != victim && e.isAlive() && McaCompat.isMcaVillager(e));
        int count = 0;
        for (LivingEntity witness : nearby) {
            if (witness.hasLineOfSight(victim)) {
                count++;
            }
        }
        return count;
    }
}
