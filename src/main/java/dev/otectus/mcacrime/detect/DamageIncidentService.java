package dev.otectus.mcacrime.detect;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.justice.JusticeService;
import dev.otectus.mcacrime.mug.MuggingService;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** One tick of finality reconciliation, owned by the server and combat dimension. */
public final class DamageIncidentService {
    private static final int MAX_PENDING = 4096;
    private static final Map<MinecraftServer, Runtime> SERVERS = new WeakHashMap<>();
    private static final class Runtime {
        final List<Pending> pending = new ArrayList<>();
        final Map<LivingDamageEvent, Pending> sampled = new java.util.IdentityHashMap<>();
        final Map<LivingEntity, Pending> latest = new java.util.IdentityHashMap<>();
        final Map<LivingEntity, Boolean> confirmedDeaths = new WeakHashMap<>();
        final Map<ResourceLocation, CombatIncidentProcessor> combat = new HashMap<>();
        boolean overflowLogged;
    }
    private static final class Pending {
        final UUID id = UUID.randomUUID();
        final ServerLevel level;
        final LivingEntity victim, actor;
        final DamageSource source;
        final DamageFinality finality;
        final LivingDamageEvent damageEvent;
        final long at;
        final boolean harmCharge, killCharge, lawfulNpc, raidSplash, mugging;
        final List<LivingDeathEvent> deaths = new ArrayList<>();
        DeathConsequences deathConsequences;
        Pending(ServerLevel level, LivingEntity victim, LivingEntity actor, DamageSource source,
                LivingDamageEvent damageEvent) {
            this.level = level; this.victim = victim; this.actor = actor; this.source = source;
            this.damageEvent = damageEvent; this.at = level.getGameTime();
            this.finality = new DamageFinality(damageEvent == null ? () -> true : damageEvent::isCanceled,
                    damageEvent == null ? () -> 0 : damageEvent::getAmount);
            harmCharge = CrimeGate.resolveOffender(victim, source, level, false).isPresent();
            killCharge = CrimeGate.resolveOffender(victim, source, level, true).isPresent();
            lawfulNpc = !(actor instanceof ServerPlayer) && victim instanceof ServerPlayer player
                    && (EntitySelectors.isResponder(actor) && JusticeService.forGuard(level, actor, player).mayChallenge()
                        || MuggingService.isRecentThreat(player.getUUID(), actor.getUUID(), at)
                        || CustodyRegistry.byOwner(level.getServer(), player.getUUID()).stream()
                            .anyMatch(held -> held.getCaptive().equals(actor.getUUID()) && !held.isLawful()));
            var raid = McaCrimeConfig.COMMON.raidGrace.get() ? level.getRaidAt(victim.blockPosition()) : null;
            raidSplash = raid != null && raid.isActive() && !(victim instanceof ServerPlayer)
                    && !EntitySelectors.isResponder(victim) && source.is(DamageTypeTags.IS_EXPLOSION)
                    && source.getDirectEntity() != actor;
            mugging = actor instanceof ServerPlayer
                    && MuggingService.isRecentThreat(actor.getUUID(), victim.getUUID(), at);
        }
    }
    private DamageIncidentService() {}

    public static void damage(LivingDamageEvent event, ServerLevel level) {
        LivingEntity actor = actor(event.getEntity(), event.getSource());
        if (actor == null) return;
        Runtime runtime = SERVERS.computeIfAbsent(level.getServer(), unused -> new Runtime());
        if (event.getEntity().isAlive()) runtime.confirmedDeaths.remove(event.getEntity());
        // Also protects a handler accidentally receiving the same event twice.
        if (runtime.sampled.containsKey(event)) return;
        if (room(runtime)) {
            Pending hit = new Pending(level, event.getEntity(), actor, event.getSource(), event);
            runtime.pending.add(hit);
            runtime.sampled.put(event, hit);
            runtime.latest.put(event.getEntity(), hit);
        }
    }

    public static void death(LivingDeathEvent event, ServerLevel level) {
        Runtime runtime = SERVERS.computeIfAbsent(level.getServer(), unused -> new Runtime());
        if (runtime.confirmedDeaths.containsKey(event.getEntity())) return;
        Pending terminal = runtime.latest.get(event.getEntity());
        if (terminal != null && (terminal.level != level || terminal.source != event.getSource())) terminal = null;
        if (terminal == null) {
            LivingEntity actor = actor(event.getEntity(), event.getSource());
            // Retain unattributed deaths for confirmed custody cleanup, without inventing an offender.
            if (!room(runtime)) return;
            terminal = new Pending(level, event.getEntity(), actor == null ? event.getEntity() : actor,
                    event.getSource(), null);
            runtime.pending.add(terminal);
            runtime.latest.put(event.getEntity(), terminal);
        }
        if (!terminal.deaths.contains(event)) {
            terminal.deaths.add(event);
            terminal.finality.death(event::isCanceled);
        }
        if (terminal.deathConsequences == null)
            terminal.deathConsequences = DeathConsequences.capture(level, event.getEntity(), event.getSource());
    }

    private static LivingEntity actor(LivingEntity victim, DamageSource source) {
        if (!(CrimeGate.responsibleActor(source) instanceof LivingEntity actor) || actor == victim
                || actor instanceof FakePlayer || victim instanceof FakePlayer) return null;
        boolean playerActor = actor instanceof ServerPlayer;
        boolean playerVictim = victim instanceof ServerPlayer && McaCrimeConfig.COMMON.pvpCountsAsCrime.get();
        if (playerActor && (playerVictim || EntitySelectors.isProtected(victim))) return actor;
        return victim instanceof ServerPlayer && (EntitySelectors.isProtected(actor) || EntitySelectors.isResponder(actor))
                ? actor : null;
    }

    private static boolean room(Runtime runtime) {
        if (runtime.pending.size() < MAX_PENDING) return true;
        if (!runtime.overflowLogged) {
            runtime.overflowLogged = true;
            McaCrime.LOGGER.warn("Crime damage reconciliation capacity reached; excess hits are not classified this session tick");
        }
        return false;
    }

    public static void flush(MinecraftServer server) {
        Runtime runtime = SERVERS.get(server);
        if (runtime == null) return;
        List<Pending> batch = List.copyOf(runtime.pending);
        runtime.pending.clear(); // Remove before listeners can reenter; new damage belongs to the next batch.
        runtime.sampled.clear();
        runtime.latest.clear();
        runtime.confirmedDeaths.keySet().removeIf(LivingEntity::isAlive);
        int cooldown = McaCrimeConfig.COMMON.harmCooldownTicks.get();
        for (ServerLevel level : server.getAllLevels()) {
            var processor = runtime.combat.get(level.dimension().location());
            if (processor != null) processor.expire(level.getGameTime(), cooldown);
        }
        for (Pending hit : batch) {
            var outcome = hit.finality.resolve(hit.victim.isDeadOrDying());
            if (outcome == DamageFinality.Outcome.NONE) continue;
            if (outcome == DamageFinality.Outcome.KILL && runtime.confirmedDeaths.putIfAbsent(hit.victim, true) != null) continue;
            if (McaCrimeConfig.COMMON.enableCrimeDetection.get() && ServerMutationGate.allows(server)) {
                dev.otectus.mcacrime.incident.IncidentNotifications.safely(() -> reconcile(runtime, hit, outcome, cooldown));
            }
            if (outcome == DamageFinality.Outcome.KILL) {
                if (hit.deathConsequences != null) hit.deathConsequences.confirm(true);
                runtime.combat.values().forEach(encounters -> encounters.forget(hit.victim.getUUID()));
            }
        }
    }

    private static void reconcile(Runtime runtime, Pending hit, DamageFinality.Outcome outcome, int cooldown) {
        if (hit.actor == hit.victim) return;
        var snapshot = new CombatIncidentProcessor.Hit(hit.id, hit.actor.getUUID(), hit.victim.getUUID(),
                hit.at, hit.actor instanceof ServerPlayer, hit.harmCharge, hit.killCharge, hit.lawfulNpc, hit.raidSplash);
        runtime.combat.computeIfAbsent(hit.level.dimension().location(), unused -> new CombatIncidentProcessor())
                .complete(snapshot, outcome, cooldown, assessment -> commit(hit, assessment));
    }

    private static boolean commit(Pending hit, CombatIncidentProcessor.Assessment assessment) {
        boolean lethal = assessment.outcome() == DamageFinality.Outcome.KILL;
        var decision = assessment.decision();
        ServerPlayer player = (ServerPlayer) hit.actor;
        ResourceLocation crime = lethal ? hit.mugging ? CrimeIds.MUGGING_MURDER
                : CrimeClassifier.classifyKill(hit.victim) : CrimeClassifier.classifyHarm(hit.victim);
        Map<String, String> context = new LinkedHashMap<>();
        context.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_BASIS, decision.basis().name().toLowerCase(java.util.Locale.ROOT));
        if (decision.encounterId() != null) context.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_ENCOUNTER, decision.encounterId().toString());
        if (decision.initiator() != null) context.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_INITIATOR, decision.initiator().toString());
        context.put(dev.otectus.mcacrime.ledger.CrimeContext.DAMAGE_ATTRIBUTION,
                hit.source.getEntity() != hit.actor ? "tame_owner"
                        : hit.source.getDirectEntity() != hit.actor ? "projectile_or_indirect" : "direct");
        return IncidentService.commitPlayer(hit.id, player, crime, hit.victim, hit.level, WitnessResult.none(),
                "damage", context).isPresent();
    }
    public static void forget(MinecraftServer server, UUID actor) {
        Runtime runtime = SERVERS.get(server);
        if (runtime != null) {
            // Pending applied hits are still facts even if a participant logs out/changes dimension.
            runtime.combat.values().forEach(encounters -> encounters.forget(actor));

        }
    }

    public static void clear(MinecraftServer server) { SERVERS.remove(server); }

    /** Compatibility cleanup for old facades that carry only an actor ID. */
    public static void forgetAcrossServers(UUID actor) {
        SERVERS.values().forEach(runtime -> runtime.combat.values().forEach(state -> state.forget(actor)));
    }
}
