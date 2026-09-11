package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.mug.npc.StolenGoodsReturn;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** NPC arrest authority: live evidence, successful capture, then controller and sentence changes. */
public final class NpcArrestService {
    private NpcArrestService() { }

    /** Takes an NPC into custody for the case the guard pursued. Null or stale evidence is refused. */
    public static boolean arrest(ServerLevel level, LivingEntity thief, LivingEntity guard,
                                 @Nullable ActiveIncidentRegistry.ActiveIncident incident) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || thief == null || !thief.isAlive() || !validGuard(level, guard)
                || thief.level() != level || thief == guard || thief.isInvisible()
                || !guard.hasLineOfSight(thief) || guard.distanceToSqr(thief) > 4.0D
                || !ServerMutationGate.allows(server)) return false;
        UUID thiefId = thief.getUUID();
        UUID guardId = guard.getUUID();
        if (CustodyRegistry.isCaptive(server, thiefId)) return false;
        if (ResponderAssignments.isEscorting(server, guardId, thiefId)
                || NpcCriminalPursuit.isAssignedElsewhere(guardId, thiefId)) return false;

        ActiveIncidentRegistry.ActiveIncident basis = incident;
        if (basis != null && basis.phase() == ActiveIncidentRegistry.Phase.THREAT) {
            basis = observeIntervention(level, guard, basis).orElse(null);
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CrimeRecord charge = admissibleCase(level, guard, basis).orElse(null);
        if (charge == null || !charge.offender().equals(thiefId)) return false;
        if (!CustodyService.captureNpcLawful(server, thief, CustodyOwner.guard(guardId), RestraintType.CUFFS,
                thief.blockPosition(), level.dimension().location()).ok()) return false;
        if (!SentenceAssignmentService.assign(data, thiefId, UUID.randomUUID(),
                List.of(charge.id()), level.getGameTime())) {
            CustodyService.release(server, thiefId, CustodyReleaseReason.ADMIN);
            return false;
        }
        CustodyRecord record = data.getCustody(thiefId);
        // A wanted accomplice serves the accomplice term, a thief serves the thief term. Read from the
        // accomplice table rather than from the charge, so the thief path is untouched by construction:
        // a villager with no accomplice record cannot reach the first branch at all.
        dev.otectus.mcacrime.state.world.AccompliceRecord accomplice = data.accomplice(thiefId);
        boolean asAccomplice = accomplice != null && accomplice.wanted();
        record.setRemainingJailTicks(npcSentenceTicks(accomplice,
                McaCrimeConfig.COMMON.thiefJailTicks.get(),
                McaCrimeConfig.COMMON.accompliceJailTicks.get()));
        if (asAccomplice) {
            // The warrant is spent on the arrest and the arrest is counted, which is what makes bailing
            // the same relative out a second time cost more than the first.
            data.putAccomplice(accomplice.arrested());
        }
        data.setDirty();

        // A declined capture must not leave the NPC's controller in the arrested/captive state.
        NpcMuggingService.sessionForThief(thiefId).ifPresent(s ->
                NpcMuggingService.abort(s.victimId(), NpcMugAbortReason.GUARD_INTERVENTION));
        ThiefBehaviorService.markArrested(thiefId);
        CrimeReactionService.clear(level, thiefId);
        CrimeReactionService.markCaptive(level, thief, guardId);
        McaCompat.leashTo(thief, guard);
        CrimeSounds.restrainApplied(thief);
        StolenGoodsReturn.onArrest(server, level, thiefId, thief.position());
        ActiveIncidentRegistry.close(thiefId);
        NpcCustodyService.beginEscort(level, thief, guard);
        CrimeDebug.crime("guard intervention against thief {} by guard {}", thiefId, guardId);
        announce(level, thief);
        if (asAccomplice) {
            AccompliceService.notifyFamily(server, thiefId, "mcacrime.msg.family.arrested");
        }
        return true;
    }

    /**
     * How long an arrested NPC serves.
     *
     * <p>Pure, and split out so the thief path can be pinned: a villager with no accomplice record, or
     * with one nobody is looking for, serves exactly the term it served before accomplices existed. The
     * accomplice term is reachable only through a standing warrant for aiding, which is the one thing a
     * thief arrest never produces.
     */
    public static long npcSentenceTicks(@Nullable dev.otectus.mcacrime.state.world.AccompliceRecord accomplice,
                                        long thiefTicks, long accompliceTicks) {
        return accomplice != null && accomplice.wanted() ? accompliceTicks : thiefTicks;
    }

    /**
     * Records a threat the guard actually sees, before aborting that attempt. A report-based pursuit
     * resolves its existing case instead. Pursuit failure therefore cannot erase a witnessed attempt,
     * and successful report-based arrest cannot fabricate another one.
     */
    public static Optional<ActiveIncidentRegistry.ActiveIncident> observeIntervention(
            ServerLevel level, LivingEntity guard, ActiveIncidentRegistry.ActiveIncident incident) {
        if (level == null || incident == null || !validGuard(level, guard)
                || !incident.dimension().equals(level.dimension())
                || !ServerMutationGate.allows(level.getServer())) return Optional.empty();
        if (incident.phase() == ActiveIncidentRegistry.Phase.COMMITTED) {
            return admissibleCase(level, guard, incident).map(record -> new ActiveIncidentRegistry.ActiveIncident(
                    record.id(), record.offender(), record.victim(), level.dimension(), incident.startedAt(),
                    CrimeFlag.decode(record.context().get(CrimeFlag.CONTEXT_KEY)),
                    ActiveIncidentRegistry.Phase.COMMITTED));
        }
        NpcMugSession session = NpcMuggingService.sessionForThief(incident.offenderId()).orElse(null);
        if (!NpcArrestEvidence.isCurrentThreat(incident,
                ActiveIncidentRegistry.get(incident.offenderId()).orElse(null),
                session == null ? null : session.transactionId())) return Optional.empty();
        if (!(level.getEntity(incident.offenderId()) instanceof LivingEntity thief)) return Optional.empty();
        ServerPlayer victim = level.getServer().getPlayerList().getPlayer(session.victimId());
        double radius = McaCrimeConfig.COMMON.guardThiefResponseRadius.get();
        if (victim == null || victim.level() != level || !victim.isAlive() || !thief.isAlive()
                || thief.isInvisible() || !guard.hasLineOfSight(thief) || !guard.hasLineOfSight(victim)
                || guard.distanceToSqr(thief) > radius * radius) return Optional.empty();
        // Consume the live incident before callbacks; a reentrant or second guard cannot record it again.
        ActiveIncidentRegistry.close(thief.getUUID());
        var committed = dev.otectus.mcacrime.incident.IncidentService.commitNpc(
                session.transactionId(), thief, CrimeIds.ATTEMPTED_MUGGING, victim, level, "guard",
                EnumSet.of(CrimeFlag.NPC_OFFENDER, CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.MANDATORY_CUSTODY));
        NpcMuggingService.abort(victim.getUUID(), NpcMugAbortReason.GUARD_INTERVENTION);
        return committed.map(view -> new ActiveIncidentRegistry.ActiveIncident(view.id(), thief.getUUID(),
                victim.getUUID(), level.dimension(), level.getGameTime(),
                EnumSet.of(CrimeFlag.NPC_OFFENDER, CrimeFlag.CAUGHT_IN_ACT, CrimeFlag.MANDATORY_CUSTODY),
                ActiveIncidentRegistry.Phase.COMMITTED));
    }

    static Optional<CrimeRecord> admissibleCase(ServerLevel level, LivingEntity guard,
                                                ActiveIncidentRegistry.ActiveIncident incident) {
        if (incident == null || !incident.dimension().equals(level.dimension())) return Optional.empty();
        return NpcArrestEvidence.caseFor(CrimeWorldData.get(level.getServer()), incident)
                .filter(record -> (CrimeFlag.decode(record.context().get(CrimeFlag.CONTEXT_KEY))
                        .contains(CrimeFlag.CAUGHT_IN_ACT) && record.witnessIds().contains(guard.getUUID()))
                        || ReportService.knownCase(level.getServer(), record,
                        ReportService.jurisdictionOf(level, guard), level.getGameTime()));
    }

    private static boolean validGuard(ServerLevel level, LivingEntity guard) {
        return guard != null && guard.isAlive() && guard.level() == level
                && EntitySelectors.isAvailableResponder(guard) && !McaCompat.isVillagerSleeping(guard)
                && !guard.hasEffect(MobEffects.BLINDNESS);
    }

    private static void announce(ServerLevel level, LivingEntity thief) {
        double radius = McaCrimeConfig.COMMON.guardThiefResponseRadius.get();
        AABB box = thief.getBoundingBox().inflate(radius);
        Component name = McaCompat.getVillagerDisplayName(thief);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box,
                player -> !player.isSpectator())) {
            player.sendSystemMessage(Component.translatable("mcacrime.npc_arrest.notice", name));
        }
    }
}
