package dev.otectus.mcacrime.justice;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.detect.CrimeGate;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.memory.ReportService;
import dev.otectus.mcacrime.memory.ReportState;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.neoforge.common.util.FakePlayer;
import java.util.UUID;

/**
 * One sampled decision about a player's act against a villager under the Thief combat policy.
 *
 * <p>0.7.7 adds the mugging threat ({@code MuggingThreats}) as an input every policy honours: the
 * victim of a hold-up may fight it off, and under {@code NORMAL_LAW} only lethal force is still
 * charged. Age, law identity and restraint are read live and still protect whoever they protect.
 *
 * <p>MCA's Outlaw is the other input every policy honours (0.7.7): with {@code outlawCombatLegal} on,
 * fighting or killing an adult Outlaw who is not restrained or held is exempt, so it files nothing and
 * the native hooks hold back MCA's hearts, gossip, murder witnesses and guard alert for it.
 */
public final class ThiefCombatService {
    private ThiefCombatService() {}
    public static ThiefCombatDecision sample(LivingEntity target, DamageSource source, String action) {
        return CrimeGate.responsibleActor(source) instanceof ServerPlayer player ? sample(target, player, action) : null;
    }
    public static ThiefCombatDecision sample(LivingEntity target, ServerPlayer player, String action) {
        if (!(target.level() instanceof ServerLevel level) || player instanceof FakePlayer || player == target
                || player.getServer() != level.getServer() || player.isSpectator()) return null;
        var server = level.getServer();
        var data = CrimeWorldData.get(server);
        var policy = dev.otectus.mcacrime.config.CrimeWorldSettings.resolve(server).thiefCombatPolicy();
        var facts = WorldCriminalJobService.facts(target, WorldCriminalJobService.of(server).get(target.getUUID()));
        var physical = data.physicalRestraint(target.getUUID());
        boolean held = data.isCaptive(target.getUUID()) || physical != null && physical.restrained();
        long now = server.overworld().getGameTime();
        UUID activeCase = dev.otectus.mcacrime.mug.npc.NpcMuggingService.sessionForThief(target.getUUID())
                .map(dev.otectus.mcacrime.mug.npc.NpcMugSession::transactionId).orElse(null);
        var evidence = data.observationsBy(player.getUUID()).stream()
                .filter(o -> target.getUUID().equals(o.suspectedActorId()) && o.identifiesActor() && o.sawAct()
                        && o.dimension().equals(level.dimension().location()) && !o.expired(now)
                        && o.reportState() != ReportState.SUPPRESSED
                        && (o.actionId().equals(dev.otectus.mcacrime.crime.type.CrimeIds.MUGGING)
                        || o.actionId().equals(dev.otectus.mcacrime.crime.type.CrimeIds.ATTEMPTED_MUGGING))
                        && withinEvidenceWindow(o.observedAt(), now, McaCrimeConfig.COMMON.thiefDefenseGraceTicks.get(),
                                o.incidentId().equals(activeCase)))
                .findFirst().orElse(null);
        var community = CrimeCommunityResolver.resolve(level.dimension().location(),
                dev.otectus.mcacrime.compat.mca.McaHandles.villageAt(level, target.blockPosition())).orElse(null);
        boolean report = ReportService.warrantExists(server, target.getUUID(), community, now);
        boolean currentThief = dev.otectus.mcacrime.job.CriminalProfessions.THIEF_ID.equals(
                dev.otectus.mcacrime.compat.McaCompat.getProfessionId(target).orElse(null));
        // The mugging this target opened on this player, live or within the recovery window (0.7.7).
        // Read from the threat ledger, never from the session: by the time a blow is classified the
        // punch, the drawn weapon, the sand or the death has already ended the session.
        boolean threatened = dev.otectus.mcacrime.mug.npc.MuggingThreats.threatens(target.getUUID(),
                player.getUUID(), now, McaCrimeConfig.COMMON.thiefDefenseGraceTicks.get());
        boolean livingAdult = facts.loaded() && facts.mcaVillager() && facts.adult();
        // MCA's own Outlaw, while outlaw combat is legal (0.7.7). Read live from the profession, like the
        // Thief role above: a villager MCA has made an outlaw since the last blow is one now.
        boolean outlaw = McaCrimeConfig.COMMON.outlawCombatLegal.get()
                && dev.otectus.mcacrime.compat.McaCompat.isOutlaw(target);
        var reason = policy.decide(livingAdult && facts.thiefRecord() && currentThief,
                facts.classifiable(), facts.responder(), held, evidence != null, report, threatened, livingAdult,
                outlaw);
        return new ThiefCombatDecision(player.getUUID(), target.getUUID(), level.dimension().location(),
                action, policy.ruleValue(), reason, evidence == null ? null : evidence.incidentId());
    }
    static boolean withinEvidenceWindow(long observed, long now, long grace, boolean activeThreat) {
        return now >= observed && (activeThreat || now - observed <= Math.max(0L, grace));
    }
}
