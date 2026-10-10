package dev.otectus.mcacrime.relationship;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.CrimeCommittedEvent;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.compat.mca.NativeCombatContext;
import dev.otectus.mcacrime.compat.mca.NativeJusticeCapabilities;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.integration.CrimeIntegrationHooks;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns abstract crime into MCA relationship damage and repair (spec §10.1, §11.3) — what makes the mod
 * feel native to MCA rather than a generic bounty system. A crime against a villager costs that villager
 * (and their family) hearts toward the offender and drops village reputation; paying a fine grants some
 * relationship recovery to the wronged community (restitution). All hearts route through {@code
 * McaCompat.addHearts}; everything is config-weighted and fail-safe.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
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
        // 0.7.7: personal hearts need somebody to feel them toward. A mask that hides identity leaves every
        // villager facing a stranger, so nobody's hearts move; the record, Heat and standing are unchanged.
        boolean masked = event.getRecordView().flatMap(view -> view.context(CrimeContext.MASKED))
                .map("true"::equals).orElse(false);
        if (NativeHeartPolicy.crimeHeartsApply(masked, c.maskHidesIdentityFromWitnesses.get())) {
            applyPersonalHearts(level, offender, event, victimId, living, c);
        }
        // Personal hearts are always ours: MCA: Reputation tracks community standing, not how a
        // particular villager feels about you, so there is nothing to double up on above this line.
        // Community standing is the part that can be counted twice, and is handled below.
        if (!c.enableObservations.get()) {
            applyVillagePenalty(level, offender, event.getCrimeType(), event.getRecordView()
                    .flatMap(dev.otectus.mcacrime.api.model.CrimeRecordView::community).orElse(null));
        }
    }

    /**
     * The victim, family and witness heart loss for one recorded crime.
     *
     * <p>For a killing, MCA has already charged its own penalty natively by the time this runs, and the
     * native hooks recorded exactly whom they charged. MCA: Crime adds its loss only for a mourner MCA
     * did not charge, only while {@code murderHeartPenalty} is on, and never while the hooks are
     * degraded: then MCA charges everybody nearby without any rule, and adding to that would stack an
     * uninformed penalty on an unjustified one (0.7.7).
     */
    private static void applyPersonalHearts(ServerLevel level, ServerPlayer offender, CrimeCommittedEvent event,
                                            UUID victimId, LivingEntity living, McaCrimeConfig.Common c) {
        if (living.isAlive() && c.directVictimHeartLoss.get() > 0) {
            McaCompat.addHearts(offender, living, -c.directVictimHeartLoss.get());
        }
        boolean killing = isKilling(event.getCrimeType());
        boolean nativeHooks = NativeJusticeCapabilities.combatComplete();
        if (killing && !nativeHooks) warnDegradedOnce();
        long now = level.getGameTime();
        List<UUID> family = familyOf(living);
        int familyLoss = c.familyHeartLoss.get();
        if (familyLoss > 0) {
            for (UUID rel : family) {
                Entity relEntity = level.getEntity(rel);
                if (relEntity instanceof LivingEntity relative && event.getRecordView().map(v -> v.witnessIds().contains(rel)).orElse(false)
                        && relative.hasLineOfSight(offender) && !offender.isInvisible()
                        && NativeHeartPolicy.crimeKillingLossApplies(killing, c.murderHeartPenalty.get(), nativeHooks,
                                NativeCombatContext.chargedByMca(offender.getUUID(), victimId, rel, now))) {
                    McaCompat.addHearts(offender, relEntity, -familyLoss);
                }
            }
        }
        // Everybody else who watched trusts the offender a little less (spec §10.1, "nearby witnesses
        // lose trust"). The victim and the family are charged above and are not charged twice here.
        int witnessLoss = c.witnessTrustLoss.get();
        if (witnessLoss > 0) {
            for (UUID witnessId : event.getRecordView().map(CrimeRecordView::witnessIds).orElse(Set.of())) {
                if (witnessId.equals(victimId) || family.contains(witnessId)) {
                    continue;
                }
                Entity witness = level.getEntity(witnessId);
                if (witness instanceof LivingEntity watcher && watcher.isAlive() && McaCompat.isMcaVillager(watcher)
                        && NativeHeartPolicy.crimeKillingLossApplies(killing, c.murderHeartPenalty.get(), nativeHooks,
                                NativeCombatContext.chargedByMca(offender.getUUID(), victimId, witnessId, now))) {
                    McaCompat.addHearts(offender, watcher, -witnessLoss);
                }
            }
        }
    }

    private static boolean isKilling(net.minecraft.resources.ResourceLocation crime) {
        return CrimeIds.KILL_VILLAGER.equals(crime) || CrimeIds.KILL_GUARD.equals(crime)
                || CrimeIds.MUGGING_MURDER.equals(crime);
    }

    private static boolean degradedWarned;

    private static void warnDegradedOnce() {
        if (degradedWarned) {
            return;
        }
        degradedWarned = true;
        McaCrime.LOGGER.warn("MCA: Crime's native justice hooks did not apply ({}), so MCA charges its own "
                + "killing penalty to every villager nearby and to the whole family with no witness rule. "
                + "MCA: Crime is skipping its own family and witness heart loss for killings so the two do "
                + "not stack; relationship.murderHeartPenalty and murderHeartsRequireWitness cannot act on "
                + "MCA's penalty until the hooks apply.", NativeJusticeCapabilities.status());
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
     * A rescue raises the rescuer's standing with the rescued villager's community by
     * {@code villageRepRise} (spec §10.1, "Rescue a villager -> village reputation rises").
     *
     * <p>Local store only. While MCA: Reputation keeps the canonical score it mirrors that score into
     * this store after every commit, so a local write would be overwritten at the next mirror and
     * counted twice until then; with the companion present the rise is skipped.
     */
    public static void applyRescueStanding(ServerPlayer rescuer, LivingEntity rescued) {
        int rise = McaCrimeConfig.COMMON.villageRepRise.get();
        if (rise <= 0 || rescuer == null || rescued == null
                || !(rescued.level() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        if (McaCrimeConfig.COMMON.enableReputation.get()
                && dev.otectus.mcacrime.compat.ReputationBridge.isAvailable()) {
            return;
        }
        dev.otectus.mcacrime.detect.CrimeCommunityResolver.resolve(rescued, level).ifPresent(community ->
                CrimeWorldData.get(level.getServer()).addReputation(community, rescuer.getUUID(), rise));
    }

    /**
     * Restitution (spec §11.3): paying a fine repairs some standing with the wronged community — grants
     * {@code restitutionHeartGain} hearts to nearby MCA villagers. Called from {@code FineService} after a
     * successful payment. Best-effort and fail-safe.
     */
    public static void applyRestitution(ServerPlayer player) {
        applyRestitution(player, 0L);
    }

    /**
     * Restitution scaled by what was actually paid.
     *
     * <p>{@code restitutionFractionOfFine} shipped describing "the fraction of a paid fine conceptually
     * returned to the victim", and nothing read it, so a one-emerald fine and a hundred-emerald fine
     * repaired exactly the same amount of goodwill. It now scales the grant: the fraction is the share
     * of the full heart gain that the payment earns, ramping up to the whole thing as the fine
     * approaches what a serious case costs. A zero fine still grants the base, because settling a case
     * that carried no charge is still settling it.
     *
     * @param finePaid emeralds actually taken, or 0 when the caller has no amount to attribute
     */
    public static void applyRestitution(ServerPlayer player, long finePaid) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        int gain = c.restitutionHeartGain.get();
        if (gain > 0 && finePaid > 0L) {
            double fraction = c.restitutionFractionOfFine.get();
            // The reference point is one full fine at the configured base: paying that much earns the
            // whole grant, paying less earns proportionally less, and paying more does not earn more.
            double reference = Math.max(1.0, c.fineBase.get());
            double share = Math.min(1.0, finePaid / reference);
            gain = (int) Math.max(1L, Math.round(gain * (1.0 - fraction + fraction * share)));
        }
        if (gain <= 0 || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        final int granted = gain;
        AABB box = player.getBoundingBox().inflate(c.witnessRadius.get());
        try {
            for (LivingEntity villager : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isMcaVillager)) {
                McaCompat.addHearts(player, villager, granted);
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
