package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.CrimeReactionService;
import dev.otectus.mcacrime.ai.thief.ThiefBehaviorService;
import dev.otectus.mcacrime.audio.CrimeSounds;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.captivity.RestraintType;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.CrimeDetector;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMugSession;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.mug.npc.StolenGoodsReturn;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import org.jetbrains.annotations.Nullable;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * The arrest of a criminal villager (spec §"Guards and thief arrests").
 *
 * <p>The NPC counterpart of {@code ArrestService}, and a separate class rather than an overload of it
 * because that one is {@code ServerPlayer}-typed all the way down: it writes a jail sentence into a
 * player capability, opens a challenge screen, and sends packets. A villager has none of those. What
 * the two share is the outcome — restrained, held, escorted, jailed — and that lives in {@code
 * CustodyRecord}, which has always been able to hold either.
 *
 * <p>Order is load-bearing. The mug is aborted <em>first</em>, before anything else happens, because
 * spec §"Player mugging interaction" requires guard intervention to beat the theft rather than race
 * it: once the session is gone, {@code NpcMuggingService.complete} can never run, so no property can
 * move afterwards no matter how the rest of this method goes.
 */
public final class NpcArrestService {

    private NpcArrestService() {
    }

    /**
     * Takes a criminal villager into lawful custody.
     *
     * @param incident the incident the guard reacted to, or null when the guard is acting on a filed
     *                 report rather than on something it watched happen
     * @return false when the thief was already held, or when custody could not be written
     */
    public static boolean arrest(ServerLevel level, LivingEntity thief, LivingEntity guard,
                                 @Nullable ActiveIncidentRegistry.ActiveIncident incident) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || thief == null || guard == null || !thief.isAlive()) {
            return false;
        }
        UUID thiefId = thief.getUUID();
        UUID guardId = guard.getUUID();
        if (CustodyRegistry.isCaptive(server, thiefId)) {
            return false; // idempotent: a second guard arriving does not arrest twice
        }

        // 1. The mug stops before anything else. Everything below can fail; this cannot be allowed to.
        // The pursuing guard already aborted the mug when it spotted this thief, which also closed the
        // incident, so the caller's copy is the fallback: an attempt a guard watched happen is still
        // charged even though the session and the registry entry are long gone by the time it is caught.
        Optional<ActiveIncidentRegistry.ActiveIncident> open = ActiveIncidentRegistry.get(thiefId)
                .or(() -> Optional.ofNullable(incident));
        Optional<NpcMugSession> session = NpcMuggingService.sessionForThief(thiefId);
        session.ifPresent(s -> NpcMuggingService.abort(s.victimId(), NpcMugAbortReason.GUARD_INTERVENTION));
        ThiefBehaviorService.markArrested(thiefId);

        // 2. The villager stops being a thief the reaction system is driving and starts being a captive.
        CrimeReactionService.clear(level, thiefId);
        CrimeReactionService.markCaptive(level, thief, guardId);

        // 3. Custody, restraints, and the physical hold.
        if (!CustodyService.captureNpcLawful(server, thief, CustodyOwner.guard(guardId), RestraintType.CUFFS,
                thief.blockPosition(), level.dimension().location()).ok()) {
            return false;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        CustodyRecord record = data.getCustody(thiefId);
        if (record != null) {
            record.setRemainingJailTicks(McaCrimeConfig.COMMON.thiefJailTicks.get());
            data.setDirty();
        }
        McaCompat.leashTo(thief, guard);
        CrimeSounds.restrainApplied(thief);

        // 4. Property goes back to whoever is standing here to receive it, before the walk to the cell.
        StolenGoodsReturn.onArrest(server, level, thiefId, thief.position());

        // 5. The record. Only when the guard actually caught something in progress: a thief taken on a
        //    report has already had its `mugging` record written by the mugging itself, and writing an
        //    `attempted_mugging` beside it would charge the same crime twice.
        if (open.isPresent()) {
            commitAttempt(level, thief, open.get(), session.orElse(null));
        }
        ActiveIncidentRegistry.close(thiefId);

        // 6. The escort begins.
        NpcCustodyService.beginEscort(level, thief, guard);
        CrimeDebug.crime("guard intervention against thief {} by guard {}", thiefId, guardId);
        announce(level, thief);
        return true;
    }

    /**
     * Files the attempted mugging the guard interrupted.
     *
     * <p>{@code CrimeIds.ATTEMPTED_MUGGING} rather than {@code MUGGING}, because nothing was taken —
     * that is the point of intervening. It still carries {@link CrimeFlag#CAUGHT_IN_ACT} and {@link
     * CrimeFlag#MANDATORY_CUSTODY}: spec §"Guards and thief arrests" says a thief caught in the act is
     * always jail-eligible, and {@code GuardChallengeService.finable} reads exactly that flag.
     */
    private static void commitAttempt(ServerLevel level, LivingEntity thief,
                                      ActiveIncidentRegistry.ActiveIncident incident,
                                      @Nullable NpcMugSession session) {
        MinecraftServer server = level.getServer();
        UUID victimId = incident.victimId() != null ? incident.victimId()
                : session == null ? null : session.victimId();
        ServerPlayer victim = victimId == null || server == null ? null
                : server.getPlayerList().getPlayer(victimId);
        EnumSet<CrimeFlag> flags = EnumSet.copyOf(incident.flags());
        flags.add(CrimeFlag.NPC_OFFENDER);
        flags.add(CrimeFlag.CAUGHT_IN_ACT);
        flags.add(CrimeFlag.MANDATORY_CUSTODY);
        CrimeDetector.commitNpc(thief, CrimeIds.ATTEMPTED_MUGGING, victim, level,
                WitnessChecker.resolve(level, victim == null ? thief : victim), "guard", flags);
    }

    /** Tells anybody near enough to have watched it happen. */
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
