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
        /** One grace ledger per server, across dimensions; a relog keeps it, a restart clears it. */
        final MinorHarmPolicy minorHarm = new MinorHarmPolicy();
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
        final boolean harmCharge, killCharge, lawfulNpc, raidSplash, mugging, ownHit, answersThreat;
        final dev.otectus.mcacrime.justice.ThiefCombatDecision thiefDecision;
        final List<LivingDeathEvent> deaths = new ArrayList<>();
        DeathConsequences deathConsequences;
        Pending(ServerLevel level, LivingEntity victim, LivingEntity actor, DamageSource source,
                LivingDamageEvent damageEvent) {
            this.level = level; this.victim = victim; this.actor = actor; this.source = source;
            this.damageEvent = damageEvent; this.at = level.getGameTime();
            this.finality = new DamageFinality(damageEvent == null ? () -> true : damageEvent::isCanceled,
                    damageEvent == null ? () -> 0 : damageEvent::getAmount);
            var nativeDecision = dev.otectus.mcacrime.compat.mca.NativeCombatContext.decision(victim, source);
            thiefDecision = nativeDecision != null ? nativeDecision : dev.otectus.mcacrime.justice.ThiefCombatService.sample(victim, source, "damage");
            boolean thiefExempt = thiefDecision != null && thiefDecision.exempt();
            // A kill is judged separately (0.7.7): fighting off a mugger is exempt under every policy,
            // killing one is not under NORMAL_LAW, where it is excessive defensive force.
            boolean killExempt = thiefDecision != null && thiefDecision.exemptsKill();
            harmCharge = !thiefExempt && CrimeGate.resolveOffender(victim, source, level, false).isPresent();
            killCharge = !killExempt && CrimeGate.resolveOffender(victim, source, level, true).isPresent();
            answersThreat = thiefDecision != null && thiefDecision.answersThreat();
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
            // The attacker's own blow or projectile; a tamed animal's bite is attributed to its owner but
            // is never minor friendly fire on the owner's behalf.
            ownHit = source.getEntity() == actor;
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
        // Sampled first, so the threat this blow answers was read before the blow ends the mugging
        // (0.7.7). The victim's own strike, arrow or pet ends the hold-up at once.
        if (actor instanceof ServerPlayer player) {
            dev.otectus.mcacrime.mug.npc.NpcMuggingService.interruptByVictim(player.getUUID(),
                    event.getEntity().getUUID());
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
        boolean playerVictim = victim instanceof ServerPlayer && dev.otectus.mcacrime.config.CrimeWorldSettings.resolve((ServerLevel) victim.level()).pvpCrime();
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
        MinorHarmPolicy.Settings grace = minorHarmSettings();
        for (ServerLevel level : server.getAllLevels()) {
            var processor = runtime.combat.get(level.dimension().location());
            if (processor != null) processor.expire(level.getGameTime(), cooldown);
        }
        runtime.minorHarm.expire(server.overworld().getGameTime(), grace.windowTicks());
        try {
            for (Pending hit : batch) {
                var outcome = hit.finality.resolve(hit.victim.isDeadOrDying());
                if (outcome == DamageFinality.Outcome.NONE) continue;
                if (outcome == DamageFinality.Outcome.KILL && runtime.confirmedDeaths.putIfAbsent(hit.victim, true) != null) continue;
                boolean[] forgiven = {false};
                if (dev.otectus.mcacrime.config.CrimeWorldSettings.resolve(server).crimeDetection() && ServerMutationGate.allows(server)) {
                    dev.otectus.mcacrime.incident.IncidentNotifications.safely(() ->
                            forgiven[0] = reconcile(runtime, hit, outcome, cooldown, grace) == CombatIncidentProcessor.Completion.FORGIVEN);
                }
                // MCA's own hurt hearts for this hit were held back or applied when it landed; now that the
                // hit is decided, a forgiven hit costs none and any other hit costs exactly MCA's amount.
                dev.otectus.mcacrime.compat.mca.NativeCombatContext.settleHurtHearts(hit.victim, hit.source, forgiven[0]);
                if (outcome == DamageFinality.Outcome.KILL) {
                    if (hit.deathConsequences != null) hit.deathConsequences.confirm(true);
                    runtime.combat.values().forEach(encounters -> encounters.forget(hit.victim.getUUID()));
                }
            }
        } finally {
            // A withheld charge whose hit never reached a damage event (cancelled, or blocked by
            // invulnerability) was never forgiven either, so MCA's amount is applied as it always was.
            dev.otectus.mcacrime.compat.mca.NativeCombatContext.flushHurtHearts();
        }
    }

    /** The {@code detection.minorHarm} block, or {@link MinorHarmPolicy.Settings#OFF} without config. */
    public static MinorHarmPolicy.Settings minorHarmSettings() {
        try {
            McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
            return new MinorHarmPolicy.Settings(c.enableMinorHarmForgiveness.get(), c.minorHarmMaxDamage.get(),
                    c.minorHarmWindowTicks.get(), c.minorHarmMaxVictimsPerWindow.get());
        } catch (Throwable t) {
            return MinorHarmPolicy.Settings.OFF;
        }
    }

    /**
     * Whether a hit landing now could be forgiven as minor friendly fire, changing nothing (0.7.7).
     *
     * <p>Asked twice before the hit is decided at the end of the tick: by the native hurt hook, with the
     * raw damage, to decide whether MCA's hearts are held back until then; and on Forge by MCA:
     * Reputation's exemption query, with the final damage, so a forgiven hit is not charged there either.
     * Either answer can be wrong only in the safe direction, because the hearts are settled against the
     * real decision at flush.
     */
    public static boolean minorHarmCandidate(LivingEntity victim, DamageSource source, double amount) {
        if (victim == null || source == null || !(victim.level() instanceof ServerLevel level)) return false;
        MinorHarmPolicy.Settings settings = minorHarmSettings();
        if (!settings.enabled()) return false;
        LivingEntity actor = actor(victim, source);
        if (!(actor instanceof ServerPlayer player)) return false;
        Runtime runtime = SERVERS.get(level.getServer());
        MinorHarmPolicy policy = runtime == null ? new MinorHarmPolicy() : runtime.minorHarm;
        long at = level.getGameTime();
        return policy.peek(settings, new MinorHarmPolicy.Facts(player.getUUID(), victim.getUUID(), at, amount, false,
                eligibility(level, victim, player, source.getEntity() == player,
                        MuggingService.isRecentThreat(player.getUUID(), victim.getUUID(), at)))).forgiven();
    }

    private static MinorHarmPolicy.Eligibility eligibility(ServerLevel level, LivingEntity victim, ServerPlayer attacker,
                                                           boolean ownHit, boolean mugging) {
        return new MinorHarmPolicy.Eligibility(
                !(victim instanceof net.minecraft.world.entity.player.Player) && EntitySelectors.isProtected(victim),
                ownHit, mugging,
                !dev.otectus.mcacrime.memory.VictimMemoryService.rawMemories(level.getServer(), victim.getUUID(),
                        attacker.getUUID()).isEmpty());
    }

    private static CombatIncidentProcessor.Completion reconcile(Runtime runtime, Pending hit, DamageFinality.Outcome outcome,
                                                                int cooldown, MinorHarmPolicy.Settings grace) {
        if (hit.actor == hit.victim) return CombatIncidentProcessor.Completion.NONE;
        MinorHarmPolicy.Eligibility minorHarm = hit.actor instanceof ServerPlayer player && grace.enabled()
                ? eligibility(hit.level, hit.victim, player, hit.ownHit, hit.mugging) : null;
        var snapshot = new CombatIncidentProcessor.Hit(hit.id, hit.actor.getUUID(), hit.victim.getUUID(),
                hit.at, hit.actor instanceof ServerPlayer, hit.harmCharge, hit.killCharge, hit.lawfulNpc, hit.raidSplash,
                hit.finality.amount(), minorHarm, hit.answersThreat);
        var completion = runtime.combat.computeIfAbsent(hit.level.dimension().location(), unused -> new CombatIncidentProcessor())
                .completeDetailed(snapshot, outcome, cooldown, runtime.minorHarm, grace, assessment -> commit(hit, assessment));
        if (completion == CombatIncidentProcessor.Completion.FORGIVEN) warnForgiven(hit);
        return completion;
    }

    /**
     * Tells the attacker the hit was taken as an accident and the next one will not be (0.7.7). The
     * villager's line obeys the speech radius like every other; the warning is the attacker's own
     * feedback and always arrives.
     */
    private static void warnForgiven(Pending hit) {
        if (!(hit.actor instanceof ServerPlayer player)) return;
        var event = dev.otectus.mcacrime.dialogue.DialogueEvents.MINOR_HARM_WARNED;
        dev.otectus.mcacrime.dialogue.CrimeDialogueService.speak(hit.victim, player, event,
                dev.otectus.mcacrime.dialogue.CrimeDialogueService.context(hit.level, hit.victim, player, hit.id, event));
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.minor_harm.warned",
                dev.otectus.mcacrime.compat.McaCompat.getVillagerDisplayName(hit.victim)));
        dev.otectus.mcacrime.util.CrimeDebug.crime("Minor harm by {} on {} forgiven ({} damage)",
                player.getUUID(), hit.victim.getUUID(), hit.finality.amount());
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
    /**
     * Commits a hostile act that dealt no damage through the same classification tail as one that did
     * (0.7.2 §14.1).
     *
     * <p>The alternative was {@code hurt(0)}. That reaches this pipeline, but it also fires every
     * damage listener, armour hook, knockback calculation and combat tracker in the game for a blow
     * that never landed, and duplicates the incident for any other mod watching. So the act enters
     * here instead, as a {@link CombatIncidentProcessor.Hit} with {@link DamageFinality.Outcome#HARM}
     * already decided — there is nothing to reconcile, because a non-damaging act has no cancellable
     * amount and cannot become a kill.
     *
     * <p>What it buys by going through the processor rather than straight to {@code IncidentService}:
     * the encounter/self-defence basis, the per-pair harm cooldown, and the replay guard that makes a
     * repeated impact of the same attempt change nothing. The caller supplies the incident id, so
     * those guarantees hold across a retry.
     *
     * @param snapshot the launch-time observation set for a delayed act, or null to scan now
     * @return whether an incident was actually recorded
     */
    public static boolean completeNonDamage(ServerLevel level, ServerPlayer actor, LivingEntity victim,
                                            UUID incidentId, ResourceLocation crime, String detection,
                                            Map<String, String> context,
                                            dev.otectus.mcacrime.incident.ObservationSnapshot snapshot) {
        if (level == null || actor == null || victim == null || actor == victim
                || !dev.otectus.mcacrime.config.CrimeWorldSettings.resolve(level).crimeDetection()
                || !ServerMutationGate.allows(level.getServer())) {
            return false;
        }
        var thiefDecision = dev.otectus.mcacrime.justice.ThiefCombatService.sample(victim, actor, "non_damaging");
        // Cuffs, a shove or a sack thrown by the victim of a hold-up end it too (0.7.7); the decision above
        // was sampled first, so it already saw the threat the act answers.
        dev.otectus.mcacrime.mug.npc.NpcMuggingService.interruptByVictim(actor.getUUID(), victim.getUUID());
        if (thiefDecision != null && thiefDecision.exempt()) return false;
        if (CrimeGate.resolveNonDamageOffender(victim, actor, level).isEmpty()) {
            return false;
        }
        Runtime runtime = SERVERS.computeIfAbsent(level.getServer(), unused -> new Runtime());
        long at = level.getGameTime();
        var hit = new CombatIncidentProcessor.Hit(incidentId, actor.getUUID(), victim.getUUID(), at,
                true, true, false, false, false);
        Map<String, String> full = new LinkedHashMap<>(context);
        return runtime.combat.computeIfAbsent(level.dimension().location(), unused -> new CombatIncidentProcessor())
                .complete(hit, DamageFinality.Outcome.HARM, McaCrimeConfig.COMMON.harmCooldownTicks.get(),
                        assessment -> {
                            var decision = assessment.decision();
                            full.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_BASIS,
                                    decision.basis().name().toLowerCase(java.util.Locale.ROOT));
                            if (decision.encounterId() != null) {
                                full.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_ENCOUNTER,
                                        decision.encounterId().toString());
                            }
                            if (decision.initiator() != null) {
                                full.put(dev.otectus.mcacrime.ledger.CrimeContext.COMBAT_INITIATOR,
                                        decision.initiator().toString());
                            }
                            full.put(dev.otectus.mcacrime.ledger.CrimeContext.DAMAGE_ATTRIBUTION, "non_damaging");
                            return IncidentService.commitPlayer(incidentId, actor, crime, victim, level,
                                    WitnessResult.none(), detection, full, null, null, snapshot).isPresent();
                        });
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
