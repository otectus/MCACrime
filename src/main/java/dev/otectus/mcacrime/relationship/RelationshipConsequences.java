package dev.otectus.mcacrime.relationship;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns abstract crime into MCA relationship damage and repair (spec §10.1, §11.3) — what makes the mod
 * feel native to MCA rather than a generic bounty system. A crime against a villager costs that villager
 * (and their family) hearts toward the offender and drops village reputation; paying a fine grants some
 * relationship recovery to the wronged community (restitution). All hearts route through {@code
 * McaCompat.addHearts}; everything is config-weighted and fail-safe.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RelationshipConsequences {

    private RelationshipConsequences() {
    }

    @SubscribeEvent
    public static void onCrimeCommitted(CrimeCommittedEvent event) {
        ServerPlayer offender = event.getPlayer();
        UUID victimId = event.getVictim();
        if (victimId == null || !(offender.level() instanceof ServerLevel level)) {
            return;
        }
        Entity victim = level.getEntity(victimId);
        if (!(victim instanceof LivingEntity living) || !McaCompat.isMcaVillager(victim)) {
            return;
        }
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        if (living.isAlive() && c.directVictimHeartLoss.get() > 0) {
            McaCompat.addHearts(offender, living, -c.directVictimHeartLoss.get());
        }
        int familyLoss = c.familyHeartLoss.get();
        if (familyLoss > 0) {
            for (UUID rel : familyOf(victim)) {
                Entity relEntity = level.getEntity(rel);
                if (relEntity != null) {
                    McaCompat.addHearts(offender, relEntity, -familyLoss);
                }
            }
        }
        int repDrop = c.villageRepDrop.get();
        if (repDrop > 0 && level.getServer() != null) {
            McaCompat.getHomeVillageId(victim).ifPresent(id ->
                    CrimeWorldData.get(level.getServer()).addReputation(id, offender.getUUID(), -repDrop));
        }
    }

    /**
     * Restitution (spec §11.3): paying a fine repairs some standing with the wronged community — grants
     * {@code restitutionHeartGain} hearts to nearby MCA villagers. Called from {@code FineService} after a
     * successful payment. Best-effort and fail-safe.
     */
    public static void applyRestitution(ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        int gain = c.restitutionHeartGain.get();
        if (gain <= 0 || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        AABB box = player.getBoundingBox().inflate(c.witnessRadius.get());
        try {
            for (LivingEntity villager : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isMcaVillager)) {
                McaCompat.addHearts(player, villager, gain);
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("restitution heart grant failed; ignoring", t);
        }
    }

    private static List<UUID> familyOf(Entity victim) {
        List<UUID> out = new ArrayList<>();
        McaCompat.getSpouseUuid(victim).ifPresent(out::add);
        out.addAll(McaCompat.getParentUuids(victim));
        out.addAll(McaCompat.getChildUuids(victim));
        out.addAll(McaCompat.getSiblingUuids(victim));
        return out;
    }
}
