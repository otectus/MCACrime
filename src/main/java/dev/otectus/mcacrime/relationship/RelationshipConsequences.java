package dev.otectus.mcacrime.relationship;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

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
        // Personal hearts are always ours: MCA: Reputation tracks community standing, not how a
        // particular villager feels about you, so there is nothing to double up on above this line.
        // Community standing is the part that can be counted twice, and is handled below.
        applyVillagePenalty(level, offender, event.getCrimeType(), event.getRecordView()
                .flatMap(dev.otectus.mcacrime.api.model.CrimeRecordView::community).orElse(null));
    }

    /**
     * Drops the offender's standing with the wronged community — unless MCA: Reputation is going to
     * record the deed canonically, in which case applying our own penalty as well would charge the
     * player twice for one crime.
     *
     * <p>The check is <b>predictive</b>, not observed. This method runs from a {@code
     * CrimeCommittedEvent} listener, and the outbox entry is queued during the commit that posts that
     * event, so asking "has anything been queued?" would depend on listener ordering. Asking "will
     * anything be?" is a pure function of config, the incident mapping, and bridge health, and gives
     * the same answer wherever it is called from.
     */
    private static void applyVillagePenalty(ServerLevel level, ServerPlayer offender,
                                            net.minecraft.resources.ResourceLocation crimeType,
                                            @Nullable CrimeCommunityKey community) {
        int repDrop = McaCrimeConfig.COMMON.villageRepDrop.get();
        if (repDrop <= 0 || level.getServer() == null || community == null) {
            return;
        }
        if (CrimeIntegrationHooks.willRecordCanonically(crimeType)) {
            return;
        }
        CrimeWorldData.get(level.getServer()).addReputation(community, offender.getUUID(), -repDrop);
    }

    /**
     * Applies the village penalty that was skipped because a companion mod was expected to record the
     * deed, after that delivery has definitively failed.
     *
     * <p>Without this, a crime whose civic record never arrived would cost the player nothing publicly
     * at all — the local penalty suppressed in favour of a write that never happened. Late and local
     * is a better answer than silently free.
     */
    public static void applyDeferredVillagePenalty(MinecraftServer server, @Nullable UUID recordId) {
        int repDrop = McaCrimeConfig.COMMON.villageRepDrop.get();
        if (server == null || recordId == null || repDrop <= 0) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        data.recordById(recordId).ifPresent(record -> record.communityKey().ifPresent(community -> {
            data.addReputation(community, record.offender(), -repDrop);
            McaCrime.LOGGER.info("MCA: Crime applied its own village standing penalty for crime {} after the "
                    + "cross-mod record could not be delivered.", recordId);
        }));
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
