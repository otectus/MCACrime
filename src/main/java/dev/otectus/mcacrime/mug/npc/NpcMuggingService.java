package dev.otectus.mcacrime.mug.npc;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.api.event.CrimeAttemptEvent;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.EntitySelectors;
import dev.otectus.mcacrime.dialogue.CrimeDialogueService;
import dev.otectus.mcacrime.dialogue.DialogueEvents;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.economy.Currency;
import dev.otectus.mcacrime.enforcement.ActiveIncidentRegistry;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.network.ActionProgressS2CPacket;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.util.CrimeDebug;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transactional half of a thief's crime: the threat, the timer, and every way it can end
 * (spec §"Player mugging interaction").
 *
 * <p>{@code ThiefBehaviorService} decides <em>whether</em> to try; this owns what happens once the
 * threat has been made, which is the split the spec asks for. Property moves in exactly one place,
 * {@link #complete(NpcMugSession)}, and only at the very end of it — so a guard, a drawn weapon or a
 * dead thief stopping this stops it before the victim has lost anything.
 *
 * <p>Sessions are keyed by victim, which is what enforces "no overlapping muggings": a second thief
 * cannot claim somebody who is already being robbed, and the claim is taken before any dialogue is
 * said, so two thieves arriving on the same tick produce one mugging and one disappointed villager.
 *
 * <p>Memory-only. Four seconds of threat has no business surviving a restart.
 */
public final class NpcMuggingService {

    /** How far a mugging can reach before the victim has simply walked away from it. */
    private static final double MUG_REACH = 6.0D;

    /** victim id -> the mugging they are currently subject to. */
    private static final Map<UUID, NpcMugSession> SESSIONS = new ConcurrentHashMap<>();

    private NpcMuggingService() {
    }

    // ------------------------------------------------------------------ queries

    public static boolean isVictim(UUID player) {
        return player != null && SESSIONS.containsKey(player);
    }

    public static Optional<NpcMugSession> sessionFor(UUID player) {
        return Optional.ofNullable(player == null ? null : SESSIONS.get(player));
    }

    /** The session this thief is running, if any. Bounded: there are never many at once. */
    public static Optional<NpcMugSession> sessionForThief(UUID thief) {
        if (thief == null) {
            return Optional.empty();
        }
        return SESSIONS.values().stream().filter(session -> thief.equals(session.thiefId())).findFirst();
    }

    public static int activeCount() {
        return SESSIONS.size();
    }

    /** Every open session, for {@code /crime debug thieves}. */
    public static List<NpcMugSession> snapshot() {
        return new ArrayList<>(SESSIONS.values());
    }

    /**
     * Registers a session against its victim, or refuses because somebody already has them.
     *
     * <p>Public because it is the whole of the one-session-per-victim rule and is worth testing on its
     * own; {@link #begin} is the only caller that should use it in anger.
     */
    public static boolean claim(NpcMugSession session) {
        return session != null && SESSIONS.putIfAbsent(session.victimId(), session) == null;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Makes the threat and opens the session.
     *
     * @return empty when the victim is already being robbed or a listener cancelled the attempt, in
     *         which case nothing has been said or drawn and the thief simply goes back to looking
     */
    public static Optional<NpcMugSession> begin(ServerLevel level, LivingEntity thief, ServerPlayer victim) {
        if (level == null || thief == null || victim == null || isVictim(victim.getUUID())
                || sessionForThief(thief.getUUID()).isPresent()) {
            return Optional.empty();
        }
        if (abortReason(level, thief, victim, true) != null) return Optional.empty();
        UUID transactionId = UUID.randomUUID();
        CrimeAttemptEvent.Started started = new CrimeAttemptEvent.Started(transactionId, thief.getUUID(),
                victim.getUUID(), CrimeIds.MUGGING);
        if (MinecraftForge.EVENT_BUS.post(started)) {
            CrimeDebug.crime("npc mug refused by listener: thief {} victim {}", thief.getUUID(), victim.getUUID());
            return Optional.empty();
        }
        // A listener can change hearts, weapons, custody or the job, or open another session.
        if (abortReason(level, thief, victim, true) != null
                || sessionForThief(thief.getUUID()).isPresent()) return Optional.empty();

        long now = level.getGameTime();
        NpcMugSession session = new NpcMugSession(transactionId, thief.getUUID(), victim.getUUID(),
                level.dimension().location(), now, McaCrimeConfig.COMMON.thiefMugDurationTicks.get());
        if (!claim(session)) {
            return Optional.empty();
        }

        // The incident is what lets a guard act on a mugging nobody has reported: caught in the act,
        // custody is not optional, and the offender is an NPC rather than a player.
        ActiveIncidentRegistry.open(new ActiveIncidentRegistry.ActiveIncident(transactionId, thief.getUUID(),
                victim.getUUID(), level.dimension(), now,
                EnumSet.of(CrimeFlag.NPC_OFFENDER),
                ActiveIncidentRegistry.Phase.THREAT));

        CrimeDialogueService.speak(thief, victim, DialogueEvents.NPC_MUG_START,
                CrimeDialogueService.context(level, thief, victim, transactionId, DialogueEvents.NPC_MUG_START));
        CrimeSounds.mugStart(thief);
        CrimeNetwork.sendActionProgress(victim, ActionProgressS2CPacket.started(transactionId,
                "gui.mcacrime.action.npc_mug", session.requiredTicks()));
        session.scheduleHud(now, McaCrimeConfig.COMMON.npcMugHudUpdateIntervalTicks.get());
        session.scheduleWeaponCheck(now, McaCrimeConfig.COMMON.npcMugWeaponCheckIntervalTicks.get());

        CrimeDebug.crime("npc mug started: thief {} victim {} transaction {} required {}",
                thief.getUUID(), victim.getUUID(), transactionId, session.requiredTicks());
        return Optional.of(session);
    }

    /**
     * Advances every open session by one tick.
     *
     * <p>Called from {@link dev.otectus.mcacrime.ai.thief.ThiefTicker} rather than from a subscriber
     * of its own: a mug only exists because a thief is running one, and a second tick handler would
     * mean two places that each believe they own the session.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || SESSIONS.isEmpty()) {
            return;
        }
        int weaponInterval = McaCrimeConfig.COMMON.npcMugWeaponCheckIntervalTicks.get();
        int hudInterval = McaCrimeConfig.COMMON.npcMugHudUpdateIntervalTicks.get();

        for (NpcMugSession session : List.copyOf(SESSIONS.values())) {
            ServerLevel level = levelOf(server, session.dimension());
            ServerPlayer victim = server.getPlayerList().getPlayer(session.victimId());
            if (level == null || victim == null || !victim.isAlive() || victim.level() != level) {
                abort(session.victimId(), NpcMugAbortReason.VICTIM_GONE);
                continue;
            }
            Entity thiefEntity = level.getEntity(session.thiefId());
            if (!(thiefEntity instanceof LivingEntity thief) || !thief.isAlive()) {
                abort(session.victimId(), NpcMugAbortReason.THIEF_DEAD);
                continue;
            }
            long now = level.getGameTime();
            boolean checkWeapon = session.shouldCheckWeapon(now);
            if (checkWeapon) {
                session.scheduleWeaponCheck(now, weaponInterval);
            }
            NpcMugAbortReason reason = abortReason(level, thief, victim, checkWeapon);
            if (reason != null) {
                abort(session.victimId(), reason);
                continue;
            }

            session.advance();
            if (session.complete()) {
                complete(session);
                continue;
            }
            if (session.shouldSendHud(now)) {
                session.scheduleHud(now, hudInterval);
                CrimeNetwork.sendActionProgress(victim, new ActionProgressS2CPacket(session.transactionId(),
                        "gui.mcacrime.action.npc_mug", session.progress(), session.requiredTicks(),
                        ActionProgressS2CPacket.Phase.PROGRESS, "", Component.empty()));
            }
        }
    }

    /**
     * Ends a mugging without taking anything.
     *
     * <p>Also the hook a guard reaching the thief uses: {@code abort(victimId, GUARD_INTERVENTION)}
     * before the arrest, so the bar is already gone by the time the cuffs go on.
     */
    public static void abort(UUID victimId, NpcMugAbortReason reason) {
        NpcMugSession session = victimId == null ? null : SESSIONS.remove(victimId);
        if (session == null) {
            return;
        }
        closeAborted(ServerLifecycleHooks.getCurrentServer(), session, reason);
    }

    /** Shared by selection, approach and the debug command; reach is checked when the threat starts. */
    public static boolean canTarget(ServerLevel level, LivingEntity thief, ServerPlayer victim) {
        return participantAbortReason(level, thief, victim) == null && !WeaponDetector.isArmed(victim);
    }

    /** Inclusive MCA heart threshold. A negative setting disables only relationship protection. */
    public static boolean relationshipProtects(int hearts, int minimumHearts) {
        return minimumHearts >= 0 && hearts >= minimumHearts;
    }

    @Nullable
    private static NpcMugAbortReason participantAbortReason(ServerLevel level, LivingEntity thief,
                                                           ServerPlayer victim) {
        if (level == null || victim == null || !victim.isAlive() || victim.isRemoved() || victim.level() != level)
            return NpcMugAbortReason.VICTIM_GONE;
        if (thief == null || !thief.isAlive() || thief.isRemoved() || thief.level() != level)
            return NpcMugAbortReason.THIEF_DEAD;
        if (!ServerMutationGate.allows(level.getServer())
                || !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(thief)
                || victim.isSpectator() || victim.isCreative() || victim.isInvulnerable()
                || EntitySelectors.isProtected(victim) || JailService.isJailed(victim))
            return NpcMugAbortReason.CANCELLED;
        int threshold = McaCrimeConfig.COMMON.thiefMugProtectionHearts.get();
        if (threshold >= 0 && relationshipProtects(McaCompat.getHearts(victim, thief), threshold))
            return NpcMugAbortReason.RELATIONSHIP_PROTECTED;
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        if (!McaCrimeConfig.COMMON.enableThieves.get()
                || WorldCriminalJobService.of(level.getServer()).get(thief.getUUID())
                    != dev.otectus.mcacrime.job.CriminalJob.THIEF
                || data.isCaptive(thief.getUUID()) || data.isCaptive(victim.getUUID()))
            return NpcMugAbortReason.CANCELLED;
        return null;
    }

    @Nullable
    private static NpcMugAbortReason abortReason(ServerLevel level, LivingEntity thief, ServerPlayer victim,
                                                boolean checkWeapon) {
        NpcMugAbortReason reason = participantAbortReason(level, thief, victim);
        if (reason != null) return reason;
        if (thief.distanceToSqr(victim) > MUG_REACH * MUG_REACH) return NpcMugAbortReason.OUT_OF_RANGE;
        return checkWeapon && WeaponDetector.isArmed(victim) ? NpcMugAbortReason.VICTIM_ARMED : null;
    }

    /** Close the HUD, incident and attempt on every abort, including a failed final eligibility check. */
    private static void closeAborted(@Nullable MinecraftServer server, NpcMugSession session,
                                     NpcMugAbortReason reason) {
        session.markAborted();
        ServerPlayer victim = server == null ? null : server.getPlayerList().getPlayer(session.victimId());
        if (victim != null) {
            CrimeNetwork.sendActionProgress(victim, ActionProgressS2CPacket.ended(session.transactionId(),
                    "gui.mcacrime.action.npc_mug", ActionProgressS2CPacket.Phase.CANCELLED, reason.outcomeKey()));
            ServerLevel level = levelOf(server, session.dimension());
            LivingEntity thief = thiefOf(level, session);
            if (thief != null && level != null && reason == NpcMugAbortReason.VICTIM_ARMED) {
                CrimeDialogueService.speak(thief, victim, DialogueEvents.NPC_MUG_ABORT_ARMED,
                        CrimeDialogueService.context(level, thief, victim, session.transactionId(),
                                DialogueEvents.NPC_MUG_ABORT_ARMED));
            }
        }
        ActiveIncidentRegistry.close(session.thiefId());
        MinecraftForge.EVENT_BUS.post(new CrimeAttemptEvent.Ended(session.transactionId(), session.thiefId(),
                session.victimId(), CrimeIds.MUGGING, CrimeAttemptEvent.AttemptOutcome.ABORTED, reason.name()));
        CrimeDebug.crime("npc mug aborted ({}): thief {} victim {} at {}/{}", reason.name(), session.thiefId(),
                session.victimId(), session.progress(), session.requiredTicks());
        endThief(server, session);
    }

    /**
     * The timer ran out: the transaction commits.
     *
     * <p>Three steps, in the order the spec insists on. PREPARE reads the victim's balance and
     * inventory and decides, once, what is to be taken. COMMIT executes exactly that plan and reports
     * exactly what left. FINALIZE files the provenance, writes the crime record, tells everybody and
     * sends the thief away. Nothing between PREPARE and COMMIT can turn one theft into two, because
     * the plan is never re-rolled and the ledger stores the stack the removal handed back rather than
     * a fresh one built to match it.
     *
     * <p>The victim disappearing in the gap between the tick that filled the bar and this call is the
     * one race that matters, and it aborts: no debit, no record, no loot.
     */
    public static void complete(NpcMugSession session) {
        if (session == null || !session.running() || !session.complete()
                || !SESSIONS.remove(session.victimId(), session)) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = levelOf(server, session.dimension());
        ServerPlayer victim = server == null ? null : server.getPlayerList().getPlayer(session.victimId());
        if (server == null || level == null || victim == null || !victim.isAlive() || victim.level() != level) {
            closeAborted(server, session, NpcMugAbortReason.VICTIM_GONE);
            return;
        }
        LivingEntity thief = thiefOf(level, session);
        NpcMugAbortReason reason = abortReason(level, thief, victim, true);
        if (reason != null) {
            closeAborted(server, session, reason);
            return;
        }

        // PREPARE -- one roll, against a snapshot, before anything moves.
        Currency currency = Currencies.active();
        TheftPolicy policy = TheftPolicy.fromConfig();
        TheftPlanner.TheftPlan plan = TheftPlanner.plan(currency.balance(victim),
                TheftExecutor.snapshot(victim, policy), policy, level.random::nextInt);

        reason = abortReason(level, thief, victim, true);
        if (reason != null) {
            closeAborted(server, session, reason);
            return; // An external balance callback may have changed participant eligibility.
        }

        long now = level.getGameTime();
        var committed = StolenGoodsLedger.commitTheft(CrimeWorldData.get(server), session.transactionId(),
                session.thiefId(), victim.getUUID(), currency.id().toString(), now,
                () -> TheftExecutor.commit(victim, plan, currency));
        if (committed.isEmpty()) {
            closeAborted(server, session, NpcMugAbortReason.CANCELLED);
            return;
        }
        TheftExecutor.TheftResult result = committed.get();
        session.markFinished();
        ActiveIncidentRegistry.advance(session.thiefId(), ActiveIncidentRegistry.Phase.COMMITTED);
        EnumSet<CrimeFlag> flags = ActiveIncidentRegistry.get(session.thiefId())
                .map(ActiveIncidentRegistry.ActiveIncident::flags)
                .orElseGet(() -> EnumSet.of(CrimeFlag.NPC_OFFENDER));
        Optional<CrimeRecordView> view = dev.otectus.mcacrime.incident.IncidentService.commitNpc(
                session.transactionId(), thief, CrimeIds.MUGGING, victim, level, "npc", flags);

        tellVictim(victim, session, result, currency);
        CrimeDialogueService.speak(thief, victim, DialogueEvents.NPC_MUG_SUCCESS,
                CrimeDialogueService.context(level, thief, victim, session.transactionId(),
                        DialogueEvents.NPC_MUG_SUCCESS));

        ActiveIncidentRegistry.close(session.thiefId());
        MinecraftForge.EVENT_BUS.post(new CrimeAttemptEvent.Ended(session.transactionId(), session.thiefId(),
                session.victimId(), CrimeIds.MUGGING, CrimeAttemptEvent.AttemptOutcome.COMMITTED, ""));
        CrimeDebug.crime("npc mug {} committed: currency={} item={}", session.transactionId(),
                result.currency(), result.tookItem() ? result.stack() : "none");
        endThief(server, session);
    }

    /**
     * Closes the victim's bar and tells them what it cost them.
     *
     * <p>The chat line is not decoration: the HUD outcome is one fading line, and somebody robbed of a
     * particular item deserves to be able to read afterwards which one it was.
     */
    private static void tellVictim(ServerPlayer victim, NpcMugSession session,
                                   TheftExecutor.TheftResult result, Currency currency) {
        CrimeNetwork.sendActionProgress(victim, ActionProgressS2CPacket.ended(session.transactionId(),
                "gui.mcacrime.action.npc_mug", ActionProgressS2CPacket.Phase.FINISHED,
                result.tookSomething() ? "gui.mcacrime.outcome.npc_mug.stolen"
                        : "gui.mcacrime.outcome.npc_mug.nothing_stolen"));
        if (result.currency() > 0L) {
            victim.sendSystemMessage(Component.translatable("mcacrime.npc_mug.lost_currency",
                    currency.format(result.currency())));
        } else if (result.tookItem()) {
            victim.sendSystemMessage(Component.translatable("mcacrime.npc_mug.lost_item",
                    describe(result.stack())));
        } else {
            victim.sendSystemMessage(Component.translatable("mcacrime.npc_mug.nothing"));
        }
    }

    /** "Diamond Sword", or "12 Arrow" -- the count appears only when it is not one. */
    private static Component describe(ItemStack stack) {
        return stack.getCount() > 1
                ? Component.literal(stack.getCount() + " ").append(stack.getHoverName())
                : stack.getHoverName().copy();
    }

    /** Drops every session. Called on server stop so a restart never inherits an open mugging. */
    public static void clearAll() {
        for (UUID victim : List.copyOf(SESSIONS.keySet())) {
            abort(victim, NpcMugAbortReason.CANCELLED);
        }
        SESSIONS.clear();
    }

    // ------------------------------------------------------------------ internals

    /** Stamps the cooldown and hands the thief back to its own behaviour, whichever way this ended. */
    private static void endThief(@Nullable MinecraftServer server, NpcMugSession session) {
        if (server != null) {
            WorldCriminalJobService.of(server).touchMug(session.thiefId(), server.overworld().getGameTime());
        }
        ThiefBehaviorService.onMugEnded(session.thiefId());
    }

    @Nullable
    private static ServerLevel levelOf(@Nullable MinecraftServer server, ResourceLocation dimension) {
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    @Nullable
    private static LivingEntity thiefOf(@Nullable ServerLevel level, NpcMugSession session) {
        Entity entity = level == null ? null : level.getEntity(session.thiefId());
        return entity instanceof LivingEntity living ? living : null;
    }
}
