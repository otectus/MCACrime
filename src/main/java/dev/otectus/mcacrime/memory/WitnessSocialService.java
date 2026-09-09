package dev.otectus.mcacrime.memory;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.VictimReactionState;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

/** One-hop, delayed, local family conversations. Only UUIDs and timestamps enter the bounded queue. */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class WitnessSocialService {
    private static final Map<UUID, Long> WAITING = new LinkedHashMap<>();
    private WitnessSocialService() {}
    public static void schedule(UUID observer, long now) {
        if (WAITING.size() < 512) WAITING.putIfAbsent(observer, now + 200);
    }
    public static void clear() { WAITING.clear(); }
    @SubscribeEvent
    public static void onLoad(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && McaCompat.isMcaVillager(event.getEntity()))
            schedule(event.getEntity().getUUID(), level.getGameTime());
    }
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now % 40 != 0 || !ServerMutationGate.allows(server)) return;
        int budget = 8;
        for (var entry : new ArrayList<>(WAITING.entrySet())) {
            if (entry.getValue() > now) continue;
            if (budget-- <= 0) break;
            WAITING.remove(entry.getKey());
            LivingEntity speaker = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(entry.getKey()) instanceof LivingEntity entity) { speaker = entity; break; }
            }
            if (speaker == null || !speaker.isAlive()) continue;
            var level = (ServerLevel) speaker.level(); var data = CrimeWorldData.get(server);
            List<CrimeObservation> observations = data.observationsBy(speaker.getUUID()).stream()
                    .filter(o -> !o.expired(now)).limit(64).toList();
            if (observations.isEmpty()) continue;
            if (data.isCaptive(speaker.getUUID()) || McaCompat.isVillagerSleeping(speaker)) {
                WAITING.put(speaker.getUUID(), now + 1200); continue;
            }
            var pending = observations.stream().filter(CrimeObservation::pending).findFirst();
            if (pending.isPresent() && McaCompat.isAdult(speaker)
                    && CrimeReactionService.stateOf(speaker.getUUID()) == VictimReactionState.CALM) {
                var observation = pending.get();
                CrimeReactionService.trigger(level, speaker, observation.suspectedActorId(), VictimReactionState.SEEKING_HELP, observation.observationId());
            }
            if (McaCrimeConfig.COMMON.enableWitnessGossip.get()
                    && !dev.otectus.mcacrime.action.ActionSessionManager.activeCoerciveAgainst(speaker.getUUID()).isPresent()) {
                for (var observation : observations) {
                    if (observation.relayed() || !observation.identifiesActor() || !observation.sawAct()
                            || observation.role() == ObserverRole.INFORMED || now - observation.observedAt() < 200) continue;
                    for (UUID relative : VictimMemoryService.familyOf(speaker)) {
                        if (!(level.getEntity(relative) instanceof LivingEntity listener) || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(listener)
                                || !McaCompat.isMcaVillager(listener) || speaker.distanceToSqr(listener) > 36
                                || !speaker.hasLineOfSight(listener) || McaCompat.isVillagerSleeping(listener)) continue;
                        boolean alreadyKnows = data.observationsBy(relative).stream().anyMatch(o -> o.incidentId().equals(observation.incidentId()));
                        if (!alreadyKnows) {
                            var rumor = new CrimeObservation(UUID.randomUUID(), observation.incidentId(), relative,
                                    ObserverRole.INFORMED, observation.suspectedActorId(), observation.victimId(), observation.actionId(),
                                    observation.dimension(), observation.location(), now, Math.min(0.49F, observation.confidence() * 0.65F),
                                    false, false, false, ReportState.PENDING, observation.expiresAt(), true);
                            if (!data.addObservation(rumor)) continue;
                            schedule(relative, now);
                            boolean familyVictim = observation.victimId() != null && (speaker.getUUID().equals(observation.victimId())
                                    || VictimMemoryService.familyOf(listener).contains(observation.victimId()));
                            if (!familyVictim || McaCrimeConfig.COMMON.enableFamilyMemory.get()) {
                                var awareness = CrimeTypeRegistry.getOrBuiltin(observation.actionId()).map(t -> t.awareness()).orElse(null);
                                if (awareness != null && observation.victimId() != null)
                                    VictimMemoryService.remember(server, relative, VictimMemoryService.create(observation.suspectedActorId(),
                                            observation.victimId(), observation.incidentId(), familyVictim ? CrimeMemoryCategory.FAMILY_HARM : CrimeMemoryCategory.WITNESSED,
                                            now, awareness, familyVictim ? 0.4 : 0.2, true), "informed");
                            }
                        }
                        data.replaceObservation(observation.withRelayed());
                        break;
                    }
                    break; // at most one conversation per speaker per pass
                }
            }
            if (observations.stream().anyMatch(o -> o.pending() || !o.relayed() && o.sawAct()))
                WAITING.put(speaker.getUUID(), now + 1200);
        }
    }
}
